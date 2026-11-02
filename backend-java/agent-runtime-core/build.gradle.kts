import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    `java-library`
}

// Core 的生产边界只允许冻结 API、Codec SPI 与纯 JSON 基础模块；宿主和基础设施模块不得进入类路径。
dependencies {
    api(project(":agent-runtime-json"))
    implementation(project(":agent-runtime-api"))
    implementation(project(":agent-runtime-codec-spi"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 明确消费已验收的 JAR，避免 Gradle 自动选择 classes 目录使构件白名单含糊。
listOf("compileClasspath", "runtimeClasspath").forEach { configurationName ->
    configurations.named(configurationName) {
        attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

val jsonJar = project(":agent-runtime-json").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val apiJar = project(":agent-runtime-api").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val codecSpiJar = project(":agent-runtime-codec-spi").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val coreJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
val coreJdepsReport = layout.buildDirectory.file("reports/core-boundary/jdeps.txt")
val java21Launcher = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

// 独立消费者没有 main/test classes、JUnit 或宿主构件，编译只使用 Core/API/Codec SPI/JSON JAR，运行增加 JSON 的 Jackson Core 依赖。
val coreConsumer = sourceSets.create("coreConsumer") {
    compileClasspath = files(coreJar, apiJar, codecSpiJar, jsonJar)
    runtimeClasspath = output + compileClasspath + configurations.getByName("runtimeClasspath")
}
tasks.named<JavaCompile>(coreConsumer.compileJavaTaskName) {
    dependsOn("jar", ":agent-runtime-json:jar", ":agent-runtime-api:jar", ":agent-runtime-codec-spi:jar")
}
val verifyCoreConsumer by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn(coreConsumer.classesTaskName)
    javaLauncher.set(java21Launcher)
    classpath = coreConsumer.runtimeClasspath
    mainClass.set("example.session.CoreConsumer")
    doFirst {
        check(coreConsumer.compileClasspath.files.map { it.canonicalFile }.toSet() == setOf(
            coreJar.get().asFile.canonicalFile,
            apiJar.get().asFile.canonicalFile,
            codecSpiJar.get().asFile.canonicalFile,
            jsonJar.get().asFile.canonicalFile,
        )) {
            "Core consumer must compile against exactly Core/API/Codec SPI/JSON jars"
        }
    }
}

val coreBaseline = layout.projectDirectory.file("api-baseline/public-api.javap")
val coreBaselineReport = layout.buildDirectory.file("reports/core-boundary/current.txt")
// Core 的 public surface 独立冻结；API 模块基线及其工具完全不变。
val verifyCoreBaseline by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn("jar", ":agent-runtime-json:jar", ":agent-runtime-api:jar", ":agent-runtime-codec-spi:jar", "compileTestJava")
    javaLauncher.set(java21Launcher)
    classpath = sourceSets.test.get().output.classesDirs
    mainClass.set("com.skillforge.runtime.core.CoreBaselineTool")
    inputs.files(coreJar, apiJar, codecSpiJar, jsonJar, coreBaseline)
    outputs.file(coreBaselineReport)
    args("verify", coreJar.get().asFile.absolutePath, apiJar.get().asFile.absolutePath,
        codecSpiJar.get().asFile.absolutePath, jsonJar.get().asFile.absolutePath,
        coreBaseline.asFile.absolutePath, coreBaselineReport.get().asFile.absolutePath)
}
// 常规 check 不自动接受 ABI 变更；只有显式生成任务更新受控文件。
val generateCoreBaseline by tasks.registering(JavaExec::class) {
    group = "build setup"
    dependsOn("jar", ":agent-runtime-json:jar", ":agent-runtime-api:jar", ":agent-runtime-codec-spi:jar", "compileTestJava")
    javaLauncher.set(java21Launcher)
    classpath = sourceSets.test.get().output.classesDirs
    mainClass.set("com.skillforge.runtime.core.CoreBaselineTool")
    inputs.files(coreJar, apiJar, codecSpiJar, jsonJar)
    outputs.file(coreBaseline)
    args("generate", coreJar.get().asFile.absolutePath, apiJar.get().asFile.absolutePath,
        codecSpiJar.get().asFile.absolutePath, jsonJar.get().asFile.absolutePath, coreBaseline.asFile.absolutePath)
}
// 同一次显式请求 generate+verify 时声明顺序；不引入依赖，check 仍绝不会自行生成基线。
verifyCoreBaseline.configure { mustRunAfter(generateCoreBaseline) }

// 同时检查解析文件和构件身份，生产类路径只允许 API、Codec SPI、JSON 及明确的 Jackson Core 运行依赖。
val verifyCoreDependencies by tasks.registering {
    group = "verification"
    dependsOn(":agent-runtime-json:jar", ":agent-runtime-api:jar", ":agent-runtime-codec-spi:jar")
    doLast {
        val projectArtifacts = setOf(
            "${project.group}:agent-runtime-api:${project.version}",
            "${project.group}:agent-runtime-codec-spi:${project.version}",
            "${project.group}:agent-runtime-json:${project.version}",
        )
        listOf(configurations.compileClasspath.get(), configurations.runtimeClasspath.get())
            .forEach { classpath ->
                check(classpath.files.map { it.canonicalFile }.toSet() ==
                    classpath.resolvedConfiguration.resolvedArtifacts.map { it.file.canonicalFile }.toSet()) {
                    "agent-runtime-core ${classpath.name} must contain only approved API/Codec SPI/JSON dependency jars"
                }
                val expectedArtifacts = projectArtifacts + if (classpath.name == "runtimeClasspath")
                    setOf("com.fasterxml.jackson.core:jackson-core:${libs.versions.runtimeJackson.get()}") else emptySet()
                val artifacts = classpath.resolvedConfiguration.resolvedArtifacts
                check(artifacts.size == expectedArtifacts.size
                        && artifacts.map { it.moduleVersion.id.toString() }.toSet() == expectedArtifacts) {
                    "agent-runtime-core must not resolve host or infrastructure artifacts"
                }
            }
    }
}

// SDK 模块依赖方向由自身验证；具体宿主的装配白名单由根工程拥有。
val verifyCoreConsumerBoundaries by tasks.registering {
    group = "verification"
    doLast {
        val approved = setOf(":agent-runtime-core", ":agent-runtime-session", ":agent-runtime-delegation", ":agent-runtime-spring-boot-starter")
        val coreReference = Regex("com\\s*\\.\\s*skillforge\\s*\\.\\s*runtime\\s*\\.\\s*core(?:\\s*\\.|\\b)")
        rootProject.allprojects.filter { it.name.startsWith("agent-runtime-") && !it.name.endsWith("-host") && it.path !in approved }.forEach { consumer ->
            // 只读取声明，不解析宿主依赖或触发网络；同时覆盖 api/implementation 与仅编译/运行的依赖入口。
            consumer.configurations.filter { it.name in setOf("api", "implementation", "compileOnly", "runtimeOnly") }.forEach { configuration ->
                check(configuration.allDependencies.filterIsInstance<ProjectDependency>().none { it.dependencyProject.path == project.path }) {
                    "${consumer.path}:${configuration.name} must not depend on Core components"
                }
            }
            // 除 import 外也捕获源码中的全限定名引用；只扫描各模块 main，不扫描历史、测试或用户目录。
            consumer.fileTree("src/main") { include("**/*.java", "**/*.kt", "**/*.groovy") }.files.forEach { source ->
                check(!coreReference.containsMatchIn(source.readText(Charsets.UTF_8))) {
                    "Unapproved Core component reference in ${source.relativeTo(rootProject.projectDir)}"
                }
            }
        }
    }
}

// 分析最终 Core JAR 并提供精确 API/Codec SPI 类路径；任何缺失依赖或额外 JDK 模块都拒绝。
val verifyCoreJdeps by tasks.registering {
    group = "verification"
    dependsOn("jar", ":agent-runtime-json:jar", ":agent-runtime-api:jar", ":agent-runtime-codec-spi:jar")
    inputs.files(coreJar, apiJar, codecSpiJar, jsonJar)
    outputs.file(coreJdepsReport)
    // 工具很轻量；避免 JDK 安装/模块变化被旧 UP-TO-DATE 结果遮蔽。
    outputs.upToDateWhen { false }
    doLast {
        val bin = java21Launcher.get().metadata.installationPath.asFile.resolve("bin")
        val executable = listOf(bin.resolve("jdeps"), bin.resolve("jdeps.exe")).firstOrNull { it.isFile }
            ?: error("JDK 21 jdeps is required")
        val dependencyPath = configurations.runtimeClasspath.get().files.toList()
            .joinToString(File.pathSeparator) { it.absolutePath }
        val process = ProcessBuilder(executable.absolutePath, "-J-Duser.language=en", "-J-Duser.country=US",
            "-J-Dfile.encoding=UTF-8", "--class-path", dependencyPath,
            "--multi-release", "21", "--print-module-deps", coreJar.get().asFile.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
            .replace("\r\n", "\n").replace('\r', '\n').trim()
        val exitCode = process.waitFor()
        val report = coreJdepsReport.get().asFile.toPath()
        Files.createDirectories(report.parent)
        Files.writeString(report, output + "\n", StandardCharsets.UTF_8)
        check(exitCode == 0 && output == "java.base") {
            "Core must depend only on API, Codec SPI, JSON and java.base; see $report (jdeps exit $exitCode)"
        }
    }
}

// 每次完整 Core check 都验证生产依赖，JUnit 负责内部类型、预算与参考模型边界。
tasks.named("check") {
    dependsOn(verifyCoreDependencies, verifyCoreJdeps, verifyCoreConsumerBoundaries, verifyCoreConsumer, verifyCoreBaseline)
}
