import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    `java-library`
    `java-test-fixtures`
}

// 生产适配器只消费已冻结的 Runtime API 与框架无关的 MyBatis Core；宿主提供
// DataSource 和驱动，Spring 与 SkillForge 持久化模块不得进入生产类路径。
dependencies {
    implementation(project(":agent-runtime-json"))
    api(project(":agent-runtime-api"))
    api(project(":agent-runtime-codec-spi"))
    api(project(":agent-runtime-session-spi"))
    // 事务钩子的同会话类型由宿主实现者消费，MyBatis仍只归JDBC适配器边界。
    api("org.mybatis:mybatis:${libs.versions.runtimeMybatis.get()}")
    // 成熟中英文分析器只属于检索适配器；不进入纯 Memory/Core 协调层。
    implementation(libs.lucene.core)
    implementation(libs.lucene.analysis.common)
    // 显式 schema 生命周期属于 JDBC 适配器；数据库驱动与角色仍由宿主提供。
    implementation("org.flywaydb:flyway-core:${libs.versions.runtimeFlyway.get()}")
    implementation("org.flywaydb:flyway-database-postgresql:${libs.versions.runtimeFlyway.get()}")
    implementation(platform("com.fasterxml.jackson:jackson-bom:${libs.versions.runtimeJackson.get()}"))
    testFixturesImplementation("org.mybatis:mybatis:${libs.versions.runtimeMybatis.get()}")

    testImplementation(testFixtures(project(":agent-runtime-adapter-testkit")))
    testImplementation(project(":agent-runtime-core"))
    testImplementation(project(":agent-runtime-memory"))
    testImplementation(project(":agent-runtime-session"))
    testImplementation(project(":agent-runtime-adapter-model"))
    testImplementation("com.zaxxer:HikariCP:${libs.versions.runtimeHikari.get()}")
    testImplementation("org.postgresql:postgresql:${libs.versions.runtimePostgresql.get()}")
    testImplementation("org.flywaydb:flyway-core:${libs.versions.runtimeFlyway.get()}")
    testImplementation("org.mockito:mockito-core:5.17.0")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:${libs.versions.runtimeJackson.get()}")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:${libs.versions.runtimeJackson.get()}")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:${libs.versions.runtimeJackson.get()}")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql:${libs.versions.runtimeFlyway.get()}")
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

listOf("compileClasspath", "runtimeClasspath").forEach { configurationName ->
    configurations.named(configurationName) {
        attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

val apiJar = project(":agent-runtime-api").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val codecSpiJar = project(":agent-runtime-codec-spi").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val sessionSpiJar = project(":agent-runtime-session-spi").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val jdbcJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
val java21Launcher = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}
val jdbcDirectJdepsReport = layout.buildDirectory.file(
    "reports/jdbc-adapter-boundary/jdeps-direct.txt",
)
val jdbcTransitiveJdepsReport = layout.buildDirectory.file(
    "reports/jdbc-adapter-boundary/jdeps-transitive.txt",
)

val verifyJdbcAdapterDependencies by tasks.registering {
    group = "verification"
    dependsOn(
        ":agent-runtime-api:jar",
        ":agent-runtime-codec-spi:jar",
        ":agent-runtime-session-spi:jar",
    )
    doLast {
        val expectedArtifacts = setOf(
            "${project(":agent-runtime-json").group}:agent-runtime-json:${project.version}",
            "${project(":agent-runtime-api").group}:agent-runtime-api:${project.version}",
            "${project(":agent-runtime-codec-spi").group}:agent-runtime-codec-spi:${project.version}",
            "${project(":agent-runtime-session-spi").group}:agent-runtime-session-spi:${project.version}",
            "org.mybatis:mybatis:${libs.versions.runtimeMybatis.get()}",
            "org.apache.lucene:lucene-core:${libs.versions.runtimeLucene.get()}",
            "org.apache.lucene:lucene-analysis-common:${libs.versions.runtimeLucene.get()}",
            "org.flywaydb:flyway-core:${libs.versions.runtimeFlyway.get()}",
            "org.flywaydb:flyway-database-postgresql:${libs.versions.runtimeFlyway.get()}",
            "com.fasterxml.jackson.core:jackson-core:${libs.versions.runtimeJackson.get()}",
            "com.fasterxml.jackson.core:jackson-databind:${libs.versions.runtimeJackson.get()}",
            "com.fasterxml.jackson.core:jackson-annotations:${libs.versions.runtimeJackson.get()}",
            "com.fasterxml.jackson.dataformat:jackson-dataformat-toml:${libs.versions.runtimeJackson.get()}",
            "com.fasterxml.jackson.datatype:jackson-datatype-jsr310:${libs.versions.runtimeJackson.get()}",
        )
        listOf(configurations.compileClasspath.get(), configurations.runtimeClasspath.get()).forEach { classpath ->
            val artifacts = classpath.resolvedConfiguration.resolvedArtifacts.toList()
            val artifactIds = artifacts.map { it.moduleVersion.id.toString() }.toSet()
            check(artifacts.size == expectedArtifacts.size && artifactIds == expectedArtifacts) {
                "agent-runtime-adapter-jdbc ${classpath.name} contains unapproved artifacts: $artifactIds"
            }
            val artifactFiles = artifacts.map { it.file.canonicalFile }.toSet()
            val classpathFiles = classpath.files.map { it.canonicalFile }.toSet()
            check(classpathFiles == artifactFiles) {
                "agent-runtime-adapter-jdbc ${classpath.name} contains unapproved files: " +
                    (classpathFiles - artifactFiles)
            }
        }
    }
}

val verifyJdbcAdapterJdeps by tasks.registering {
    group = "verification"
    dependsOn(
        "jar",
        ":agent-runtime-api:jar",
        ":agent-runtime-codec-spi:jar",
        ":agent-runtime-session-spi:jar",
    )
    inputs.files(jdbcJar, apiJar, codecSpiJar, sessionSpiJar)
    outputs.files(jdbcDirectJdepsReport, jdbcTransitiveJdepsReport)
    outputs.upToDateWhen { false }
    doLast {
        val bin = java21Launcher.get().metadata.installationPath.asFile.resolve("bin")
        val executable = listOf(bin.resolve("jdeps"), bin.resolve("jdeps.exe")).firstOrNull { it.isFile }
            ?: error("JDK 21 jdeps is required")
        val classpath = configurations.compileClasspath.get().files
            .joinToString(File.pathSeparator) { it.absolutePath }
        val checks = listOf(
            Triple(
                listOf("--no-recursive"),
                jdbcDirectJdepsReport.get().asFile.toPath(),
                setOf("java.base", "java.sql"),
            ),
            Triple(
                emptyList(),
                jdbcTransitiveJdepsReport.get().asFile.toPath(),
                setOf(
                    "java.base", "java.desktop", "java.instrument", "java.management",
                    "java.naming", "java.sql", "jdk.attach", "jdk.jdi",
                    // Lucene 9 的 Java 21 multi-release 实现声明可选向量加速模块。
                    "jdk.incubator.vector",
                ),
            ),
        )
        for ((mode, report, allowedJdkModules) in checks) {
            val command = buildList {
                add(executable.absolutePath)
                addAll(listOf(
                    "-J-Duser.language=en",
                    "-J-Duser.country=US",
                    "-J-Dfile.encoding=UTF-8",
                    "--multi-release", "21",
                    "--ignore-missing-deps",
                ))
                addAll(mode)
                addAll(listOf(
                    "--class-path",
                    classpath,
                    "--print-module-deps",
                    jdbcJar.get().asFile.absolutePath,
                ))
            }
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace('\r', '\n').trim()
            val exitCode = process.waitFor()
            Files.createDirectories(report.parent)
            Files.writeString(report, output + "\n", StandardCharsets.UTF_8)
            check(exitCode == 0
                    && output.split(',').map(String::trim).toSet() == allowedJdkModules) {
                "JDBC adapter JDK module footprint changed; see $report"
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyJdbcAdapterDependencies, verifyJdbcAdapterJdeps)
}

// 仅启动工具自身的临时 PostgreSQL，不允许通过参数连接开发或生产数据库。
tasks.register<JavaExec>("exportRuntimeSchemaManifest") {
    group = "verification"
    description = "在临时 PostgreSQL 执行正式迁移并导出真实 Runtime 结构清单"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.skillforge.runtime.adapter.jdbc.schema.RuntimeSchemaManifestExporter")
    args(layout.buildDirectory.file("schema/runtime-schema-v1.tsv").get().asFile.absolutePath)
}
