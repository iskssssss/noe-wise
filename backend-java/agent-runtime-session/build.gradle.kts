import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    `java-library`
}

tasks.named<org.gradle.api.tasks.testing.Test>("test") {
    inputs.file(rootProject.file("../tools/knowledge_evaluation/suite.json"))
        .withPropertyName("knowledgeEvaluationCorpus").withPathSensitivity(PathSensitivity.RELATIVE)
}

// Session 直接打包自有的完整系统正文，不依赖宿主资源、生成目录或构建任务。
tasks.processResources {
    includeEmptyDirs = false
    // Copy 不清除已删除资源；同步清理本任务输出，防止旧正文残留在最终 JAR。
    doFirst {
        val destination = destinationDir.canonicalFile
        check(destination.toPath().startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath()) &&
                destination != layout.buildDirectory.get().asFile.canonicalFile) { "Resource output must stay inside build/" }
        delete(destination)
    }
}

// Session 直接消费完整PortalOutcome编解码契约，显式声明Codec SPI，不依赖Core的内部传递配置。
dependencies {
    implementation(project(":agent-runtime-json"))
    implementation(project(":agent-runtime-api"))
    api(project(":agent-runtime-session-spi"))
    implementation(project(":agent-runtime-core"))
    implementation(project(":agent-runtime-codec-spi"))
    implementation("com.fasterxml.jackson.core:jackson-databind:${libs.versions.runtimeJackson.get()}")
    implementation("org.commonmark:commonmark:${libs.versions.runtimeCommonmark.get()}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configurations.named("compileClasspath") {
    attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
}

val jsonJar = project(":agent-runtime-json").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val apiJar = project(":agent-runtime-api").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val codecSpiJar = project(":agent-runtime-codec-spi").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val sessionSpiJar = project(":agent-runtime-session-spi").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val coreJar = project(":agent-runtime-core").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val sessionJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
val sessionJdepsReport = layout.buildDirectory.file("reports/session-boundary/jdeps.txt")
val java21Launcher = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

// 独立消费者使用最终 Session/Session SPI/Core/API JAR，运行时包含 Core 的 Codec SPI。
val sessionConsumer = sourceSets.create("sessionConsumer") {
    compileClasspath = files(sessionJar, sessionSpiJar, coreJar, apiJar, jsonJar)
    runtimeClasspath = output + compileClasspath + configurations.getByName("runtimeClasspath")
}
tasks.named<JavaCompile>(sessionConsumer.compileJavaTaskName) {
    dependsOn("jar", ":agent-runtime-json:jar", ":agent-runtime-session-spi:jar", ":agent-runtime-core:jar", ":agent-runtime-api:jar")
}
val verifySessionConsumer by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn(sessionConsumer.classesTaskName, ":agent-runtime-codec-spi:jar")
    javaLauncher.set(java21Launcher)
    classpath = sessionConsumer.runtimeClasspath
    mainClass.set("example.host.SessionConsumer")
    doFirst {
        check(sessionConsumer.compileClasspath.files.map { it.canonicalFile }.toSet() ==
            setOf(sessionJar.get().asFile.canonicalFile, sessionSpiJar.get().asFile.canonicalFile,
                coreJar.get().asFile.canonicalFile,
                apiJar.get().asFile.canonicalFile, jsonJar.get().asFile.canonicalFile)) {
            "Session consumer must compile against exactly Session/Session-SPI/Core/API/JSON jars"
        }
    }
}

// 编译与运行配置同时校验文件和构件身份，防止仅运行时依赖绕过模块边界。
val verifySessionDependencies by tasks.registering {
    group = "verification"
    dependsOn(":agent-runtime-json:jar", ":agent-runtime-api:jar", ":agent-runtime-session-spi:jar", ":agent-runtime-core:jar", ":agent-runtime-codec-spi:jar")
    doLast {
        val expectedFiles = setOf(apiJar.get().asFile.canonicalFile,
            sessionSpiJar.get().asFile.canonicalFile, coreJar.get().asFile.canonicalFile,
            codecSpiJar.get().asFile.canonicalFile, jsonJar.get().asFile.canonicalFile)
        val expectedArtifacts = listOf(project(":agent-runtime-api"), project(":agent-runtime-session-spi"),
            project(":agent-runtime-core"), project(":agent-runtime-codec-spi"), project(":agent-runtime-json"))
            .map { "${it.group}:${it.name}:${it.version}" }.toSet()
        listOf(configurations.compileClasspath.get(), configurations.runtimeClasspath.get()).forEach { classpath ->
            val jacksonNames = setOf("jackson-databind", "jackson-core", "jackson-annotations")
            val artifacts = classpath.resolvedConfiguration.resolvedArtifacts
            val jackson = artifacts.filter { it.moduleVersion.id.group == "com.fasterxml.jackson.core" && it.name in jacksonNames }
            check(jackson.size == 3) { "Session external payload parser must resolve exactly the three Jackson artifacts" }
            val markdown = artifacts.filter { it.moduleVersion.id.group == "org.commonmark" && it.name == "commonmark" }
            check(markdown.size == 1) { "Session citation parsing must resolve exactly the CommonMark core artifact" }
            val parsers = jackson + markdown
            val files = expectedFiles + parsers.map { it.file.canonicalFile }
            val allowedArtifacts = expectedArtifacts + parsers.map { it.moduleVersion.id.toString() }
            check(classpath.files.map { it.canonicalFile }.toSet() == files) {
                "agent-runtime-session ${classpath.name} must contain only API, Session SPI, Core, Codec SPI, Jackson and CommonMark jars"
            }
            check(artifacts.size == allowedArtifacts.size && artifacts.map { it.moduleVersion.id.toString() }.toSet() == allowedArtifacts) {
                "agent-runtime-session must not resolve host or infrastructure artifacts"
            }
        }
    }
}

// jdeps 使用显式 Core/API 类路径，缺失类型或任何 java.base 以外的 JDK 模块均拒绝。
val verifySessionJdeps by tasks.registering {
    group = "verification"
    dependsOn("jar", ":agent-runtime-api:jar", ":agent-runtime-session-spi:jar", ":agent-runtime-core:jar", ":agent-runtime-codec-spi:jar")
    inputs.files(sessionJar, sessionSpiJar, coreJar, apiJar, codecSpiJar)
    outputs.file(sessionJdepsReport)
    outputs.upToDateWhen { false }
    doLast {
        val bin = java21Launcher.get().metadata.installationPath.asFile.resolve("bin")
        val executable = listOf(bin.resolve("jdeps"), bin.resolve("jdeps.exe")).firstOrNull { it.isFile }
            ?: error("JDK 21 jdeps is required")
        val classpath = configurations.runtimeClasspath.get().files.joinToString(File.pathSeparator) { it.absolutePath }
        val process = ProcessBuilder(executable.absolutePath, "-J-Duser.language=en", "-J-Duser.country=US",
            "-J-Dfile.encoding=UTF-8", "--multi-release", "21", "--class-path", classpath,
            "--print-module-deps", sessionJar.get().asFile.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
            .replace("\r\n", "\n").replace('\r', '\n').trim()
        val exitCode = process.waitFor()
        val report = sessionJdepsReport.get().asFile.toPath()
        Files.createDirectories(report.parent)
        Files.writeString(report, output + "\n", StandardCharsets.UTF_8)
        check(exitCode == 0 && output.split(',').all { it in setOf("java.base", "java.desktop", "java.logging", "java.sql", "java.xml") }) {
            "Session must resolve only Core/API, explicit payload and Markdown parsers and their JDK modules; see $report (jdeps exit $exitCode)"
        }
    }
}

tasks.named("check") {
    dependsOn(verifySessionDependencies, verifySessionJdeps, verifySessionConsumer)
}
