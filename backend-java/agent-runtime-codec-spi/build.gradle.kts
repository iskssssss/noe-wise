import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    `java-library`
}

// 私有 Codec SPI 只描述 Runtime API 强类型结果；规范 JSON 实现继续由 Core 单独拥有。
dependencies {
    api(project(":agent-runtime-api"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

listOf("compileClasspath", "runtimeClasspath").forEach { configurationName ->
    configurations.named(configurationName) {
        attributes.attribute(
            LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
            objects.named(LibraryElements.JAR),
        )
    }
}

val apiJar = project(":agent-runtime-api").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val spiJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
val java21Launcher = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}
val jdepsReport = layout.buildDirectory.file("reports/codec-spi-boundary/jdeps.txt")

val verifyCodecSpiDependencies by tasks.registering {
    group = "verification"
    dependsOn(":agent-runtime-api:jar")
    doLast {
        val expectedFile = apiJar.get().asFile.canonicalFile
        val apiProject = project(":agent-runtime-api")
        val expectedArtifact = "${apiProject.group}:${apiProject.name}:${apiProject.version}"
        listOf(configurations.compileClasspath.get(), configurations.runtimeClasspath.get())
            .forEach { classpath ->
                check(classpath.files.map { it.canonicalFile }.toSet() == setOf(expectedFile)) {
                    "agent-runtime-codec-spi ${classpath.name} must contain only the API jar"
                }
                val artifacts = classpath.resolvedConfiguration.resolvedArtifacts
                check(artifacts.size == 1
                        && artifacts.single().moduleVersion.id.toString() == expectedArtifact) {
                    "agent-runtime-codec-spi must resolve only agent-runtime-api"
                }
            }
    }
}

val verifyCodecSpiJdeps by tasks.registering {
    group = "verification"
    dependsOn("jar", ":agent-runtime-api:jar")
    inputs.files(spiJar, apiJar)
    outputs.file(jdepsReport)
    outputs.upToDateWhen { false }
    doLast {
        val bin = java21Launcher.get().metadata.installationPath.asFile.resolve("bin")
        val executable = listOf(bin.resolve("jdeps"), bin.resolve("jdeps.exe"))
            .firstOrNull { it.isFile } ?: error("JDK 21 jdeps is required")
        val process = ProcessBuilder(
            executable.absolutePath,
            "-J-Duser.language=en",
            "-J-Duser.country=US",
            "-J-Dfile.encoding=UTF-8",
            "--class-path",
            apiJar.get().asFile.absolutePath,
            "--print-module-deps",
            spiJar.get().asFile.absolutePath,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
            .replace("\r\n", "\n").replace('\r', '\n').trim()
        val exitCode = process.waitFor()
        val report = jdepsReport.get().asFile.toPath()
        Files.createDirectories(report.parent)
        Files.writeString(report, output + "\n", StandardCharsets.UTF_8)
        check(exitCode == 0 && output == "java.base") {
            "Codec SPI must depend only on API and java.base; see $report"
        }
    }
}

tasks.named("check") {
    dependsOn(verifyCodecSpiDependencies, verifyCodecSpiJdeps)
}
