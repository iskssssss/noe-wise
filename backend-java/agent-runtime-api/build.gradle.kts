import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    `java-library`
}

dependencies {
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ABI 工具统一由本机 JDK 21 运行，避免 Gradle 守护进程版本改变 javap/jdeps 结果。
val javaToolchains = extensions.getByType<JavaToolchainService>()
val java21Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}
val apiJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
val apiBaseline = layout.projectDirectory.file("api-baseline/public-api.javap")
val apiBaselineReport = layout.buildDirectory.file("reports/api-baseline/current.txt")
val apiJdepsReport = layout.buildDirectory.file("reports/api-baseline/jdeps.txt")
val testClasses = sourceSets.named("test").map { it.output.classesDirs }

// 公开 API 只能依赖 JDK；解析主编译类路径后拒绝任何 project 或外部构件。
val verifyApiDependencies by tasks.registering {
    group = "verification"
    description = "Verifies that the Agent Runtime API has no compile classpath artifacts."

    doLast {
        val compileClasspath = configurations.compileClasspath.get()
        val files = compileClasspath.files
        val artifacts = compileClasspath
            .resolvedConfiguration
            .resolvedArtifacts

        // files 覆盖 files(...) 等 self-resolving 依赖，resolvedArtifacts 覆盖模块与 project 构件。
        val violations = buildList {
            if (files.isNotEmpty()) {
                add("files=[${files.joinToString { it.absolutePath }}]")
            }
            if (artifacts.isNotEmpty()) {
                add("artifacts=[${artifacts.joinToString { it.moduleVersion.id.toString() }}]")
            }
        }
        check(violations.isEmpty()) {
            "agent-runtime-api compileClasspath must be empty, but resolved " +
                violations.joinToString(separator = "; ")
        }
    }
}

// baseline 验证只写 build report，不得自动改写受版本控制的基线。
val verifyApiBaseline by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verifies the complete public Agent Runtime API javap baseline."
    dependsOn(tasks.named("jar"), tasks.named("compileTestJava"))
    javaLauncher.set(java21Launcher)
    classpath = testClasses.get()
    mainClass.set("com.skillforge.runtime.api.ApiBaselineTool")
    inputs.file(apiJar)
    inputs.file(apiBaseline)
    outputs.file(apiBaselineReport)
    args(
        "verify",
        apiJar.get().asFile.absolutePath,
        apiBaseline.asFile.absolutePath,
        apiBaselineReport.get().asFile.absolutePath,
    )
}

// 只有显式执行生成任务时才接受当前 ABI，常规 check 不依赖该任务。
val generateApiBaseline by tasks.registering(JavaExec::class) {
    group = "build setup"
    description = "Explicitly regenerates the committed Agent Runtime API javap baseline."
    dependsOn(tasks.named("jar"), tasks.named("compileTestJava"))
    javaLauncher.set(java21Launcher)
    classpath = testClasses.get()
    mainClass.set("com.skillforge.runtime.api.ApiBaselineTool")
    inputs.file(apiJar)
    outputs.file(apiBaseline)
    args(
        "generate",
        apiJar.get().asFile.absolutePath,
        apiBaseline.asFile.absolutePath,
    )
}

// 显式生成和验证同时执行时声明顺序；普通 check 不生成基线。
verifyApiBaseline.configure { mustRunAfter(generateApiBaseline) }

// 从 JDK 21 安装目录定位跨平台工具，不经过 shell 也不依赖 Gradle 内部 OS API。
fun resolveJdkTool(toolName: String): java.io.File {
    val javaHome = java21Launcher.get().metadata.installationPath.asFile
    val candidates = listOf(
        javaHome.resolve("bin/$toolName"),
        javaHome.resolve("bin/$toolName.exe"),
    )
    return candidates.firstOrNull { it.isFile }
        ?: error("JDK 21 tool not found: $toolName under ${javaHome.absolutePath}")
}

// jdeps 对最终 JAR 执行全量分析，缺失依赖或任何非 java.base 模块都必须失败。
val verifyApiJdeps by tasks.registering {
    group = "verification"
    description = "Verifies that the Agent Runtime API jar depends only on java.base."
    dependsOn(tasks.named("jar"))
    inputs.file(apiJar)
    outputs.file(apiJdepsReport)

    // 轻量工具始终实际执行，避免同 major JDK 的工具或运行模块变化复用旧报告。
    // 完整候选 Gate 的复用由外层 manifest 负责，不依赖 Gradle 对此自定义任务的推断。
    outputs.upToDateWhen { false }

    doLast {
        val command = listOf(
            resolveJdkTool("jdeps").absolutePath,
            "-J-Duser.language=en",
            "-J-Duser.country=US",
            "-J-Dfile.encoding=UTF-8",
            "--print-module-deps",
            apiJar.get().asFile.absolutePath,
        )
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val rawOutput = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
        val exitCode = process.waitFor()
        val normalizedOutput = rawOutput
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

        // 无论成功或失败都保留真实工具输出，供 CI 和本地定位。
        val report = apiJdepsReport.get().asFile.toPath()
        Files.createDirectories(report.parent)
        Files.writeString(report, normalizedOutput + "\n", StandardCharsets.UTF_8)

        check(exitCode == 0) {
            "jdeps failed with exit code $exitCode; see ${report.toAbsolutePath()}"
        }
        check(normalizedOutput == "java.base") {
            "agent-runtime-api must depend only on java.base, but jdeps reported " +
                "'$normalizedOutput'; see ${report.toAbsolutePath()}"
        }
    }
}

// 模块验收同时执行零生产依赖、JDK 模块与公开 ABI 基线门禁。
tasks.named("check") {
    dependsOn(verifyApiDependencies, verifyApiJdeps, verifyApiBaseline)
}
