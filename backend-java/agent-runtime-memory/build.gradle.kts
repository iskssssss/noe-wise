import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    `java-library`
}

// Memory 是纯 JVM 协调层；持久化与宿主框架必须留在 API Port 之后。
dependencies {
    implementation(project(":agent-runtime-json"))
    implementation(project(":agent-runtime-api"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 明确解析最终 API JAR，避免 Gradle classes 目录变体掩盖构件边界。
listOf("compileClasspath", "runtimeClasspath").forEach { configurationName ->
    configurations.named(configurationName) {
        attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

val apiJar = project(":agent-runtime-api").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val memoryJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
val memoryJdepsReport = layout.buildDirectory.file("reports/memory-boundary/jdeps.txt")
val memoryBaseline = layout.projectDirectory.file("api-baseline/public-api.javap")
val memoryBaselineReport = layout.buildDirectory.file("reports/memory-boundary/current.txt")
val java21Launcher = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

// 独立消费者无法访问 main/test classes 目录或宿主构件。
val memoryConsumer = sourceSets.create("memoryConsumer") {
    compileClasspath = files(memoryJar, apiJar)
    runtimeClasspath = output + compileClasspath + configurations.getByName("runtimeClasspath")
}
tasks.named<JavaCompile>(memoryConsumer.compileJavaTaskName) {
    dependsOn("jar", ":agent-runtime-json:jar", ":agent-runtime-api:jar")
}
val verifyMemoryConsumer by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn(memoryConsumer.classesTaskName)
    javaLauncher.set(java21Launcher)
    classpath = memoryConsumer.runtimeClasspath
    mainClass.set("example.host.MemoryConsumer")
    doFirst {
        check(memoryConsumer.compileClasspath.files.map { it.canonicalFile }.toSet() ==
            setOf(memoryJar.get().asFile.canonicalFile, apiJar.get().asFile.canonicalFile)) {
            "Memory consumer must compile against exactly the Memory/API jars"
        }
    }
}

// 生产类路径精确限制为 API、JSON 与 Jackson Core 运行依赖。
val verifyMemoryDependencies by tasks.registering {
    group = "verification"
    dependsOn(":agent-runtime-json:jar", ":agent-runtime-api:jar")
    doLast {
        val projectArtifacts = setOf(
            "${project.group}:agent-runtime-api:${project.version}",
            "${project.group}:agent-runtime-json:${project.version}",
        )
        listOf(configurations.compileClasspath.get(), configurations.runtimeClasspath.get()).forEach { classpath ->
            check(classpath.files.map { it.canonicalFile }.toSet() ==
                classpath.resolvedConfiguration.resolvedArtifacts.map { it.file.canonicalFile }.toSet()) {
                "agent-runtime-memory ${classpath.name} must contain only approved API/JSON dependency jars"
            }
            val expectedArtifacts = projectArtifacts + if (classpath.name == "runtimeClasspath")
                    setOf("com.fasterxml.jackson.core:jackson-core:${libs.versions.runtimeJackson.get()}") else emptySet()
                val artifacts = classpath.resolvedConfiguration.resolvedArtifacts
            check(artifacts.size == expectedArtifacts.size && artifacts.map { it.moduleVersion.id.toString() }.toSet() == expectedArtifacts) {
                "agent-runtime-memory must not resolve host or infrastructure artifacts"
            }
        }
    }
}

// 源码扫描同时捕获依赖解析看不到的全限定或反射引用。
val verifyMemoryBoundaries by tasks.registering {
    group = "verification"
    doLast {
        val forbiddenMemoryReferences = linkedMapOf(
            "non-API SkillForge package" to Regex(
                "com\\s*\\.\\s*skillforge\\s*\\.\\s*(?!runtime\\s*\\.\\s*(?:api|memory|json)\\b)"
            ),
            "Spring" to Regex("org\\s*\\.\\s*springframework(?:\\s*\\.|\\b)"),
            "MyBatis" to Regex(
                "(?:org\\s*\\.\\s*(?:mybatis|apache\\s*\\.\\s*ibatis)|" +
                    "com\\s*\\.\\s*baomidou\\s*\\.\\s*mybatisplus)(?:\\s*\\.|\\b)"
            ),
            "database API or provider" to Regex(
                "(?:(?:java|javax)\\s*\\.\\s*sql|jakarta\\s*\\.\\s*persistence|" +
                    "org\\s*\\.\\s*(?:postgresql|mariadb|hibernate|h2|redisson|neo4j)|" +
                    "com\\s*\\.\\s*(?:mysql|microsoft\\s*\\.\\s*sqlserver|zaxxer\\s*\\.\\s*hikari|mongodb)|" +
                    "oracle\\s*\\.\\s*jdbc)(?:\\s*\\.|\\b)"
            ),
            "host data object or mapper" to Regex("\\b[A-Z][A-Za-z0-9_]*(?:DO|Mapper)\\b")
        )
        // 核心 MyBatis 包名不含 mybatis 字样，固定探针防止边界正则再次漏扫。
        check(forbiddenMemoryReferences.getValue("MyBatis")
            .containsMatchIn("org.apache.ibatis.session.SqlSession")) {
            "Memory MyBatis boundary must cover org.apache.ibatis"
        }
        fileTree("src/main") { include("**/*.java", "**/*.kt", "**/*.groovy") }.files.forEach { source ->
            val text = source.readText(Charsets.UTF_8)
            forbiddenMemoryReferences.forEach { (boundary, pattern) ->
                check(!pattern.containsMatchIn(text)) {
                    "Forbidden $boundary reference in ${source.relativeTo(rootProject.projectDir)}"
                }
            }
        }

        val memoryReference = Regex(
            "com\\s*\\.\\s*skillforge\\s*\\.\\s*runtime\\s*\\.\\s*memory(?:\\s*\\.|\\b)"
        )
        val allowedMemoryConsumers = setOf(":agent-runtime-spring-boot-starter")
        rootProject.allprojects.filter { it.name.startsWith("agent-runtime-") && !it.name.endsWith("-host") && it.path != project.path }.forEach { consumer ->
            consumer.configurations
                .filter { it.name in setOf("api", "implementation", "compileOnly", "runtimeOnly") }
                .forEach { configuration ->
                    check(configuration.allDependencies.filterIsInstance<ProjectDependency>()
                        .none { it.dependencyProject.path == project.path
                            && consumer.path !in allowedMemoryConsumers }) {
                        "${consumer.path}:${configuration.name} must not depend on Memory coordination"
                    }
                }
            if (consumer.path in allowedMemoryConsumers) {
                return@forEach
            }
            consumer.fileTree("src/main") { include("**/*.java", "**/*.kt", "**/*.groovy") }.files.forEach { source ->
                check(!memoryReference.containsMatchIn(source.readText(Charsets.UTF_8))) {
                    "Unapproved Memory component reference in ${source.relativeTo(rootProject.projectDir)}"
                }
            }
        }
    }
}

// 以 API JAR 为唯一类路径分析最终 Memory JAR。
val verifyMemoryJdeps by tasks.registering {
    group = "verification"
    dependsOn("jar", ":agent-runtime-json:jar", ":agent-runtime-api:jar")
    inputs.files(memoryJar, apiJar)
    outputs.file(memoryJdepsReport)
    outputs.upToDateWhen { false }
    doLast {
        val bin = java21Launcher.get().metadata.installationPath.asFile.resolve("bin")
        val executable = listOf(bin.resolve("jdeps"), bin.resolve("jdeps.exe")).firstOrNull { it.isFile }
            ?: error("JDK 21 jdeps is required")
        val process = ProcessBuilder(executable.absolutePath, "-J-Duser.language=en", "-J-Duser.country=US",
            "-J-Dfile.encoding=UTF-8", "--class-path", configurations.runtimeClasspath.get().files.joinToString(File.pathSeparator) { it.absolutePath },
            "--multi-release", "21",
            "--print-module-deps", memoryJar.get().asFile.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
            .replace("\r\n", "\n").replace('\r', '\n').trim()
        val exitCode = process.waitFor()
        val report = memoryJdepsReport.get().asFile.toPath()
        Files.createDirectories(report.parent)
        Files.writeString(report, output + "\n", StandardCharsets.UTF_8)
        check(exitCode == 0 && output == "java.base") {
            "Memory must depend only on API, JSON and java.base; see $report (jdeps exit $exitCode)"
        }
    }
}

// Memory 的公开协调入口独立冻结，常规 check 只验证，绝不自动接受 ABI 漂移。
val verifyMemoryBaseline by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn("jar", ":agent-runtime-json:jar", ":agent-runtime-api:jar", "compileTestJava")
    javaLauncher.set(java21Launcher)
    classpath = sourceSets.test.get().output.classesDirs
    mainClass.set("com.skillforge.runtime.memory.MemoryBaselineTool")
    inputs.files(memoryJar, apiJar, memoryBaseline)
    outputs.file(memoryBaselineReport)
    args("verify", memoryJar.get().asFile.absolutePath, apiJar.get().asFile.absolutePath,
        memoryBaseline.asFile.absolutePath, memoryBaselineReport.get().asFile.absolutePath)
}

// 只有显式任务才更新受版本控制的 ABI 基线。
val generateMemoryBaseline by tasks.registering(JavaExec::class) {
    group = "build setup"
    dependsOn("jar", ":agent-runtime-json:jar", ":agent-runtime-api:jar", "compileTestJava")
    javaLauncher.set(java21Launcher)
    classpath = sourceSets.test.get().output.classesDirs
    mainClass.set("com.skillforge.runtime.memory.MemoryBaselineTool")
    inputs.files(memoryJar, apiJar)
    outputs.file(memoryBaseline)
    args("generate", memoryJar.get().asFile.absolutePath, apiJar.get().asFile.absolutePath,
        memoryBaseline.asFile.absolutePath)
}
verifyMemoryBaseline.configure { mustRunAfter(generateMemoryBaseline) }

tasks.named("check") {
    dependsOn(verifyMemoryDependencies, verifyMemoryBoundaries, verifyMemoryJdeps,
        verifyMemoryConsumer, verifyMemoryBaseline)
}
