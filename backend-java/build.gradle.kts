import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.io.File
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension

plugins {
    java
    pmd
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.dependency.management) apply false
}

allprojects {
    group = "com.skillforge"
    version = "0.2.0"
}

val agentRuntimeVersion = providers.gradleProperty("agentRuntimeVersion").orElse("1.0.0")
val javaRelease = libs.versions.java.get().toInt()
val runtimeJunitVersion = libs.versions.runtimeJUnit.get()
val pmdVersion = libs.versions.pmd.get()

fun Project.configureAlibabaPmd(version: String) {
    extensions.configure<PmdExtension> {
        toolVersion = version
        ruleSets = emptyList()
        ruleSetFiles = rootProject.files("config/pmd/alibaba-java21.xml")
        isIgnoreFailures = false
    }
    tasks.withType<Pmd>().configureEach {
        threads.set(2)
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
    // 生产规约门禁覆盖所有 main 源码；测试的资源/异步可靠性由模块行为测试验证。
    tasks.named("pmdTest") { enabled = false }
}

apply(from = "gradle/mybatis-access.gradle.kts")
configureAlibabaPmd(pmdVersion)

val positiveAlibabaRules = tasks.register<Pmd>("verifyAlibabaPositiveFixture") {
    setSource(fileTree("config/pmd/fixtures/positive") { include("**/*.java") })
    classpath = files()
    reports.xml.outputLocation.set(layout.buildDirectory.file("reports/pmd/alibaba-positive.xml"))
    reports.html.required.set(false)
}
val negativeAlibabaRules = tasks.register<Pmd>("verifyAlibabaNegativeFixture") {
    setSource(fileTree("config/pmd/fixtures/negative") { include("**/*.java") })
    classpath = files()
    ignoreFailures = true
    reports.xml.outputLocation.set(layout.buildDirectory.file("reports/pmd/alibaba-negative.xml"))
    reports.html.required.set(false)
}
val alibabaRuleFixtures = tasks.register("verifyAlibabaRuleFixtures") {
    group = "verification"
    dependsOn(positiveAlibabaRules, negativeAlibabaRules)
    doLast {
        val report = negativeAlibabaRules.get().reports.xml.outputLocation.get().asFile.readText()
        check(!Regex("<(?:error|configerror)\\b").containsMatchIn(report)) { "PMD fixture analysis failed" }
        val rules = Regex("rule=\"([^\"]+)\"").findAll(report).map { it.groupValues[1] }.toSet()
        check(rules == setOf("AlibabaExplicitThreadPool", "AvoidDecimalLiteralsInBigDecimalConstructor",
            "EqualsNull", "UseLocaleWithCaseConversions")) { "PMD negative fixture coverage changed: $rules" }
    }
}
val explicitSqlProjections = tasks.register("verifyExplicitSqlProjections") {
    group = "verification"
    val sources = fileTree(rootDir) {
        include("*/src/main/resources/mapper/*.xml", "*/src/main/java/**/*.java")
    }
    inputs.files(sources)
    doLast {
        // 排除正则转义（例如 \S.*）的字符类，仍检查 SQL 的普通别名通配投影。
        val selectAll = Regex("(?i)\\bselect\\s+\\*|(?<![\\\\\\w])(?:[a-zA-Z_]\\w*)\\.\\*")
        logger.debug("event=sql_projection_check_enter sourceCount={}", sources.files.size)
        check(listOf("SELECT * FROM t", "SELECT t.* FROM t", "SELECT t.*, x.id FROM t")
            .all { selectAll.containsMatchIn(it) }) { "SQL wildcard rejection fixtures failed" }
        check(listOf("SELECT count(*) FROM t", "SELECT t.id FROM t", "\\S.*")
            .none { selectAll.containsMatchIn(it) }) { "SQL projection acceptance fixtures failed" }
        val violations = sources.files.flatMap { file ->
            // 保留换行以输出准确位置；Java import 通配符与 SQL 投影无关。
            val text = file.readText().replace(Regex("(?s)<!--.*?-->")) { match ->
                match.value.map { if (it == '\n') '\n' else ' ' }.joinToString("")
            }.replace(Regex("(?m)^[\\t ]*import [^\\r\\n]+")) { " ".repeat(it.value.length) }
            selectAll.findAll(text).map { match ->
                val line = text.take(match.range.first).count { it == '\n' } + 1
                "${file.relativeTo(rootDir)}:$line"
            }.toList()
        }
        check(violations.isEmpty()) { "SQL projections must name their columns:\n${violations.joinToString("\n")}" }
        logger.debug("event=sql_projection_check_exit sourceCount={} violationCount=0", sources.files.size)
    }
}
val alibabaStaticCheck = tasks.register("alibabaStaticCheck") {
    group = "verification"
    description = "Java 21 上的阿里正确性规约子集、规则负例和 SQL 显式列门禁"
    dependsOn(alibabaRuleFixtures, explicitSqlProjections)
}
tasks.named("check") { dependsOn(alibabaStaticCheck) }

// 共用执行方式，白名单仍由每个 Adapter 显式声明，不能扩为跨模块的并集。
fun Project.registerHttpAdapterBoundary(label: String, allowed: Set<String>, runtimeOnlyAllowed: Set<String> = emptySet()) {
    val dependencyGate = tasks.register("verify${label}AdapterDependencies") {
        group = "verification"
        doLast {
            listOf(project.configurations.getByName("compileClasspath"), project.configurations.getByName("runtimeClasspath"))
                .forEach { configuration ->
                    val actual = configuration.resolvedConfiguration.resolvedArtifacts.map {
                        "${it.moduleVersion.id.group}:${it.moduleVersion.id.name}"
                    }.toSet()
                    val expected = allowed + if (configuration.name == "runtimeClasspath") runtimeOnlyAllowed else emptySet()
                    check(actual == expected) { "$label adapter dependencies must remain isolated: $actual" }
                }
        }
    }
    val jdepsGate = tasks.register("verify${label}AdapterJdeps") {
        group = "verification"
        dependsOn("jar")
        doLast {
            val launcher = project.extensions.getByType<JavaToolchainService>().launcherFor {
                languageVersion.set(JavaLanguageVersion.of(21))
            }.get()
            val bin = launcher.metadata.installationPath.asFile.resolve("bin")
            val executable = listOf(bin.resolve("jdeps"), bin.resolve("jdeps.exe")).first { it.isFile }
            val jar = project.tasks.named<org.gradle.api.tasks.bundling.Jar>("jar").get().archiveFile.get().asFile
            val classpath = project.configurations.getByName("runtimeClasspath").files
                .joinToString(File.pathSeparator) { it.absolutePath }
            val process = ProcessBuilder(executable.absolutePath, "-J-Duser.language=en", "-J-Duser.country=US",
                "--multi-release", "21", "--no-recursive", "--ignore-missing-deps",
                "--class-path", classpath, "--print-module-deps", jar.absolutePath).redirectErrorStream(true).start()
            val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8).trim()
            check(process.waitFor() == 0 && output.split(',').map(String::trim).toSet() == setOf("java.base", "java.net.http")) {
                "$label adapter JDK module footprint changed: $output"
            }
        }
    }
    tasks.named("check") { dependsOn(dependencyGate, jdepsGate) }
}

val httpAdapterBoundaries = mapOf(
    "agent-runtime-adapter-model" to ("Model" to setOf(
        "com.skillforge:agent-runtime-api", "com.skillforge:agent-runtime-session-spi",
        "com.fasterxml.jackson.core:jackson-databind", "com.fasterxml.jackson.core:jackson-core",
        "com.fasterxml.jackson.core:jackson-annotations", "com.knuddels:jtokkit",
        "com.openai:openai-java", "com.openai:openai-java-client-okhttp", "com.openai:openai-java-core",
        "com.squareup.okhttp3:okhttp", "com.squareup.okio:okio-jvm",
        "org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains.kotlin:kotlin-stdlib-common",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7", "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
        "org.jetbrains:annotations", "com.google.errorprone:error_prone_annotations",
        "io.swagger.core.v3:swagger-annotations",
    )),
    "agent-runtime-adapter-mcp" to ("Mcp" to setOf(
        "com.skillforge:agent-runtime-api", "com.fasterxml.jackson.core:jackson-databind",
        "com.fasterxml.jackson.core:jackson-core", "com.fasterxml.jackson.core:jackson-annotations",
    )),
)
// OpenAI SDK 的运行期映射与 schema 支持；不传播到 MCP 适配器白名单。
val modelSdkRuntimeArtifacts = setOf(
    "com.fasterxml.jackson.datatype:jackson-datatype-jdk8",
    "com.fasterxml.jackson.datatype:jackson-datatype-jsr310",
    "com.fasterxml.jackson.module:jackson-module-kotlin", "org.jetbrains.kotlin:kotlin-reflect",
    "com.fasterxml:classmate", "com.github.victools:jsonschema-generator",
    "com.github.victools:jsonschema-module-jackson", "com.github.victools:jsonschema-module-swagger-2",
    "org.slf4j:slf4j-api",
)
val agentRuntimePublishedProjects = setOf(
    "agent-runtime-api",
    "agent-runtime-codec-spi",
    "agent-runtime-session-spi",
    "agent-runtime-json",
    "agent-runtime-core",
    "agent-runtime-session",
    "agent-runtime-memory",
    "agent-runtime-adapter-testkit",
    "agent-runtime-adapter-jdbc",
    "agent-runtime-adapter-model",
    "agent-runtime-adapter-mcp",
    "agent-runtime-spring-boot-starter",
    "agent-runtime-bom",
    "agent-runtime-adapter-sqlite",
)

subprojects {
    if (name != "agent-runtime-bom") {
        apply(plugin = "java")
        apply(plugin = "jacoco")
        apply(plugin = "pmd")
        configureAlibabaPmd(pmdVersion)
        val modulePmd = tasks.named("pmdMain")
        alibabaStaticCheck.configure { dependsOn(modulePmd) }

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
            toolchain.languageVersion.set(JavaLanguageVersion.of(javaRelease))
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(javaRelease)
            options.compilerArgs.add("-parameters")
        }

        tasks.withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions).apply {
                encoding = "UTF-8"
                charSet = "UTF-8"
                addBooleanOption("Xdoclint:none", true)
                addBooleanOption("quiet", true)
                addBooleanOption("notimestamp", true)
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // 测试环境容器与 JaCoCo 共享有限资源；固定单 worker，避免高核 CI 主机
            // 启动过多 Test Executor 导致 worker classpath/容器资源失效。
            maxParallelForks = 1
            finalizedBy("jacocoTestReport")
            testLogging {
                exceptionFormat = TestExceptionFormat.FULL
                events("passed", "skipped", "failed")
            }
        }

        // 每个模块都生成可审计的 XML/HTML 覆盖率报告；覆盖率门禁按任务在 CI 中
        // 针对变更类执行，避免未覆盖的历史适配层掩盖本轮交付证据。
        tasks.withType<JacocoReport>().configureEach {
            dependsOn(tasks.withType<Test>())
            reports {
                xml.required.set(true)
                html.required.set(true)
                csv.required.set(false)
            }
        }

        dependencies {
            "testImplementation"(platform("org.junit:junit-bom:$runtimeJunitVersion"))
            "testImplementation"("org.junit.jupiter:junit-jupiter")
        }
    }
    httpAdapterBoundaries[name]?.let { (label, allowed) ->
        registerHttpAdapterBoundary(label, allowed, if (name == "agent-runtime-adapter-model") modelSdkRuntimeArtifacts else emptySet())
    }

    if (name in agentRuntimePublishedProjects) {
        version = agentRuntimeVersion.get()
        if (name == "agent-runtime-bom") {
            apply(plugin = "java-platform")
        }
        apply(plugin = "maven-publish")

        plugins.withId("java") {
            extensions.configure<JavaPluginExtension> {
                withSourcesJar()
                withJavadocJar()
            }
            tasks.withType<AbstractArchiveTask>().configureEach {
                isPreserveFileTimestamps = false
                isReproducibleFileOrder = true
            }
        }

        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("sdk") {
                    val componentName = if (project.name == "agent-runtime-bom") "javaPlatform" else "java"
                    from(components[componentName])
                    pom {
                        name.set(project.name)
                        description.set("NoeWise Agent Runtime ${project.name.removePrefix("agent-runtime-")}")
                        url.set("https://github.com/iskssssss/noe-wise")
                        licenses {
                            license {
                                name.set("Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                distribution.set("repo")
                            }
                        }
                        scm {
                            connection.set("scm:git:https://github.com/iskssssss/noe-wise.git")
                            developerConnection.set("scm:git:https://github.com/iskssssss/noe-wise.git")
                            url.set("https://github.com/iskssssss/noe-wise")
                        }
                    }
                }
            }
            repositories {
                maven {
                    name = "sdkRelease"
                    url = rootProject.uri(rootProject.providers.gradleProperty("sdkReleaseRepository")
                        .orElse(rootProject.layout.buildDirectory.dir("sdk-repository")
                            .map { it.asFile.absolutePath }).get())
                }
            }
        }

        tasks.withType<AbstractPublishToMaven>().configureEach {
            doFirst {
                check(!project.version.toString().contains("SNAPSHOT", ignoreCase = true)) {
                    "SDK release publication rejects SNAPSHOT versions"
                }
            }
        }
    }
}

// 测试源集和共享夹具不得回流到正式代码或主 JAR。
val verifyTestSourceIsolation by tasks.registering {
    group = "verification"
    val testOnlyModules = listOf("agent-runtime-adapter-testkit")
    val mainJars = testOnlyModules.map {
        project(":$it").tasks.named<org.gradle.api.tasks.bundling.Jar>("jar")
    }
    dependsOn(mainJars)
    doLast {
        testOnlyModules.forEach { module ->
            check(fileTree("$module/src/main").isEmpty) {
                "$module test support must live in src/test or src/testFixtures"
            }
        }
        mainJars.forEach { task ->
            ZipFile(task.get().archiveFile.get().asFile).use { archive ->
                val entries = archive.entries().asSequence().map { it.name }.toList()
                check(entries.none {
                    it.startsWith("com/skillforge/runtime/adapter/inmemory/")
                            || it.startsWith("com/skillforge/runtime/adapter/testkit/")
                            || it.endsWith("/MockConnector.class")
                            || it.endsWith("/ResilienceFixtures.class")
                }) { "Test support leaked into ${task.get().path}" }
            }
        }
    }
}

tasks.named("check") { dependsOn(verifyTestSourceIsolation) }

val verifySdkReleaseRepository by tasks.registering {
    group = "verification"
    description = "Verifies the isolated Agent Runtime 1.0.0 Maven repository and artifact contents."
    val manifest = layout.buildDirectory.file("reports/sdk-release/repository-sha256.txt")
    outputs.file(manifest)
    // 发布仓库可切换且构件可被重新发布；校验必须读取本次实际产物，不能只复用旧 manifest。
    outputs.upToDateWhen { false }
    doLast {
        val releaseVersion = agentRuntimeVersion.get()
        check(Regex("[1-9][0-9]*\\.[0-9]+\\.[0-9]+").matches(releaseVersion)) {
            "Agent Runtime release version must be a stable SemVer"
        }
        val repository = providers.gradleProperty("sdkReleaseRepository")
            .map { file(it) }
            .orElse(layout.buildDirectory.dir("sdk-repository").map { it.asFile })
            .get().canonicalFile
        val rows = mutableListOf<String>()
        agentRuntimePublishedProjects.sorted().forEach { artifactId ->
            val directory = repository.resolve("com/skillforge/$artifactId/$releaseVersion")
            check(directory.isDirectory) { "Missing release directory for com.skillforge:$artifactId:$releaseVersion" }
            val pom = directory.resolve("$artifactId-$releaseVersion.pom")
            val module = directory.resolve("$artifactId-$releaseVersion.module")
            check(pom.isFile && module.isFile) { "Missing POM or Gradle metadata for $artifactId" }
            val metadata = pom.readText() + "\n" + module.readText()
            check("SNAPSHOT" !in metadata && "unspecified" !in metadata
                    && "build/classes" !in metadata.replace('\\', '/')
                    && repository.absolutePath !in metadata) {
                "Non-release or local build metadata leaked into $artifactId"
            }
            check("<name>Apache License, Version 2.0</name>" in metadata
                    && "<url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>" in metadata) {
                "Release POM license metadata missing for $artifactId"
            }
            if (artifactId != "agent-runtime-bom") {
                val jar = directory.resolve("$artifactId-$releaseVersion.jar")
                val sources = directory.resolve("$artifactId-$releaseVersion-sources.jar")
                val javadoc = directory.resolve("$artifactId-$releaseVersion-javadoc.jar")
                check(jar.isFile && sources.isFile && javadoc.isFile) {
                    "Main, sources or javadoc artifact missing for $artifactId"
                }
                ZipFile(jar).use { archive ->
                    val entries = archive.entries().asSequence().map { it.name }.toList()
                    check(entries.none { it.endsWith(".java") || it.endsWith("Test.class")
                            || "/test/" in it.lowercase() }) {
                        "Source or test content leaked into $artifactId main jar"
                    }
                }
            }
            directory.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { artifact ->
                val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes())
                    .joinToString("") { "%02x".format(it) }
                rows += "com.skillforge:$artifactId:$releaseVersion\t${artifact.name}\t$digest"
            }
        }
        val output = manifest.get().asFile
        output.parentFile.mkdirs()
        output.writeText(rows.sorted().joinToString("\n", postfix = "\n"))
        logger.lifecycle("Verified ${agentRuntimePublishedProjects.size} SDK release modules at ${repository.absolutePath}")
    }
}

// 发布模块的生产依赖必须闭合于 SDK 集合；任何具体宿主均不得成为隐式依赖。
val verifyRuntimeModuleBoundaries by tasks.registering {
    group = "verification"
    doLast {
        agentRuntimePublishedProjects.forEach { module ->
            val sdk = project(":$module")
            sdk.configurations.filter { it.name in setOf("api", "implementation", "compileOnly", "runtimeOnly") }
                .forEach { configuration ->
                    val externalProjects = configuration.allDependencies.filterIsInstance<ProjectDependency>()
                        .map { it.dependencyProject.name }.filter { it !in agentRuntimePublishedProjects }
                    check(externalProjects.isEmpty()) {
                        "${sdk.path}:${configuration.name} depends on non-SDK projects: $externalProjects"
                    }
                }
        }
    }
}
tasks.named("check") { dependsOn(verifyRuntimeModuleBoundaries) }

val sdkReleaseGate by tasks.registering {
    group = "verification"
    description = "Runs publication, ABI/SPI, dependency, jdeps and artifact gates for the SDK release set."
    dependsOn(
        verifyTestSourceIsolation,
        verifyRuntimeModuleBoundaries,
        verifySdkReleaseRepository,
        ":agent-runtime-json:check",
        ":agent-runtime-api:verifyApiDependencies",
        ":agent-runtime-api:verifyApiJdeps",
        ":agent-runtime-api:verifyApiBaseline",
        ":agent-runtime-codec-spi:verifyCodecSpiDependencies",
        ":agent-runtime-codec-spi:verifyCodecSpiJdeps",
        ":agent-runtime-session-spi:verifySessionSpiDependencies",
        ":agent-runtime-session-spi:verifySessionSpiJdeps",
        ":agent-runtime-core:verifyCoreConsumer",
        ":agent-runtime-core:verifyCoreBaseline",
        ":agent-runtime-core:verifyCoreDependencies",
        ":agent-runtime-core:verifyCoreConsumerBoundaries",
        ":agent-runtime-core:verifyCoreJdeps",
        ":agent-runtime-session:verifySessionConsumer",
        ":agent-runtime-session:verifySessionDependencies",
        ":agent-runtime-session:verifySessionJdeps",
        ":agent-runtime-memory:verifyMemoryConsumer",
        ":agent-runtime-memory:verifyMemoryDependencies",
        ":agent-runtime-memory:verifyMemoryBoundaries",
        ":agent-runtime-memory:verifyMemoryJdeps",
        ":agent-runtime-memory:verifyMemoryBaseline",
        ":agent-runtime-adapter-jdbc:verifyJdbcAdapterDependencies",
        ":agent-runtime-adapter-jdbc:verifyJdbcAdapterJdeps",
        ":agent-runtime-adapter-model:verifyModelAdapterDependencies",
        ":agent-runtime-adapter-model:verifyModelAdapterJdeps",
        ":agent-runtime-adapter-mcp:verifyMcpAdapterDependencies",
        ":agent-runtime-adapter-mcp:verifyMcpAdapterJdeps",
        ":agent-runtime-spring-boot-starter:verifyStarterRuntimeBoundary",
    )
}

gradle.projectsEvaluated {
    verifySdkReleaseRepository.configure {
        dependsOn(agentRuntimePublishedProjects.map {
            project(":$it").tasks.named("publishSdkPublicationToSdkReleaseRepository")
        })
    }
}

// 公开组件供Session及显式宿主装配根消费；业务代码不能因Java public绕过统一推进入口。
val verifyHostCoreCompositionBoundaries by tasks.registering {
    group = "verification"
    doLast {
        val approved = setOf(":agent-runtime-core", ":agent-runtime-session", ":agent-runtime-delegation", ":agent-runtime-spring-boot-starter")
        val compositionHosts = setOf(":skillforge-institution-service", ":skillforge-system-agent-runtime", ":agent-runtime-desktop-host", ":skillforge-core-capability")
        val hostCompositionTypes = mapOf(
            // 平台MCP装配只读取协议解码限额，不允许进入Runtime推进循环。
            "src/main/java/com/skillforge/core/capability/integration/runtime/AgentRuntimeCapabilityConfiguration.java" to setOf("CodecLimits"),
            // 独立桌面宿主只装配现有Session程序及其冻结值，不拥有第二套执行循环。
            "src/main/java/com/skillforge/runtime/desktop/DesktopRuntime.java" to setOf("CodecLimits", "CoreRuntime"),
            "src/main/java/com/skillforge/runtime/desktop/DesktopModels.java" to setOf("CodecLimits"),
            "src/main/java/com/skillforge/runtime/desktop/DesktopConfiguration.java" to setOf("LoopBudget"),
            "src/main/java/com/skillforge/runtime/desktop/DesktopCapabilities.java" to setOf("CoreRuntime"),
            "src/main/java/com/skillforge/institution/runtime/AgentRuntimeExecutionSources.java" to
                setOf("CodecLimits", "FrozenModelRequest", "LoopBudget"),
            "src/main/java/com/skillforge/institution/runtime/AgentRuntimeExecutionConfiguration.java" to
                setOf("CodecLimits", "CoreRuntime"),
            // 准入目录仅解码冻结Skill值，不能消费效果循环或自行推进Run。
            "src/main/java/com/skillforge/institution/runtime/AgentRuntimeExecutionCatalog.java" to
                setOf("CodecLimits", "SkillActivation"),
            // 本地计划边界只构造Session协议解析限额，不消费Core推进或效果实现。
            "src/main/java/com/skillforge/institution/runtime/AgentRuntimeExecutionPlans.java" to
                setOf("CodecLimits"),
            "src/main/java/com/skillforge/institution/runtime/AgentDelegationHost.java" to
                setOf("CodecLimits"),
            "src/main/java/com/skillforge/institution/runtime/AgentRuntimeExecutionSteering.java" to
                setOf("CodecLimits"),
            "src/main/java/com/skillforge/institution/runtime/AgentRuntimeProgramTools.java" to
                setOf("CodecLimits"),
            // 团队结果只解码准确已提交的结果，不取得协调器写权或外调能力。
            "src/main/java/com/skillforge/institution/team/TeamRuntimeFacts.java" to
                setOf("AgentAction", "AgentDecisionDecoder", "AgentExecutionLoop", "CodecLimits"),
            "src/main/java/com/skillforge/institution/runtime/WorkflowNodeRequests.java" to
                setOf("CodecLimits", "FrozenModelRequest"),
            "src/main/java/com/skillforge/institution/runtime/RegisteredExecutionProviderCalls.java" to
                setOf("CodecLimits", "CoreRuntime", "FrozenModelRequest"),
            // 系统宿主与机构宿主共用Session协调器；只允许其明确装配及冻结值类型。
            "src/main/java/com/skillforge/systemagent/runtime/SystemAgentExecutions.java" to
                setOf("CodecLimits", "CoreRuntime", "LoopBudget", "SkillActivation"),
            "src/main/java/com/skillforge/systemagent/runtime/SystemAgentCapabilitySource.java" to
                setOf("SkillActivation"),
            "src/main/java/com/skillforge/systemagent/runtime/SystemAgentRuntimeAutoConfiguration.java" to
                setOf("CoreRuntime"))
        val coreReference = Regex("com\\s*\\.\\s*skillforge\\s*\\.\\s*runtime\\s*\\.\\s*core(?:\\s*\\.|\\b)")
        rootProject.allprojects.filter { it.path !in approved }.forEach { consumer ->
            // 只读取声明，不解析宿主依赖或触发网络；同时覆盖 api/implementation 与仅编译/运行的依赖入口。
            consumer.configurations.filter { it.name in setOf("api", "implementation", "compileOnly", "runtimeOnly") }.forEach { configuration ->
                check(consumer.path in compositionHosts || configuration.allDependencies.filterIsInstance<ProjectDependency>().none { it.dependencyProject.path == ":agent-runtime-core" }) {
                    "${consumer.path}:${configuration.name} must not depend on Core components"
                }
            }
            // 除 import 外也捕获源码中的全限定名引用；只扫描各模块 main，不扫描历史、测试或用户目录。
            consumer.fileTree("src/main") { include("**/*.java", "**/*.kt", "**/*.groovy") }.files.forEach sourceLoop@ { source ->
                val hostAllowedTypes = hostCompositionTypes[source.relativeTo(consumer.projectDir).invariantSeparatorsPath]
                if (consumer.path in compositionHosts && hostAllowedTypes != null) {
                    check(!Regex("com\\.skillforge\\.runtime\\.core\\.\\*").containsMatchIn(source.readText(Charsets.UTF_8))) {
                        "Core composition roots must name their exact permitted types"
                    }
                    val imports = Regex("com\\.skillforge\\.runtime\\.core\\.([A-Za-z0-9_]+)").findAll(source.readText(Charsets.UTF_8)).map { it.groupValues[1] }.toSet()
                    check(imports.all { it in hostAllowedTypes }) { "Unapproved Core type in execution composition root: $imports" }
                    return@sourceLoop
                }
                check(!coreReference.containsMatchIn(source.readText(Charsets.UTF_8))) {
                    "Unapproved Core component reference in ${source.relativeTo(rootProject.projectDir)}"
                }
            }
        }
    }
}


tasks.named("check") { dependsOn(verifyHostCoreCompositionBoundaries) }

val verifyHostMemoryCompositionBoundaries by tasks.registering {
    group = "verification"
    doLast {
        val memoryReference = Regex(
            "com\\s*\\.\\s*skillforge\\s*\\.\\s*runtime\\s*\\.\\s*memory(?:\\s*\\.|\\b)"
        )
        val allowedMemoryConsumers = setOf(
            ":agent-runtime-spring-boot-starter",
            // 生产宿主已在 SDK-3 接通 Runtime facade 与 Memory 协调层。
            ":skillforge-institution-service",
            // 系统用途宿主独立装配 Runtime，提供受控 Memory 事实读取。
            ":skillforge-system-agent-runtime"
        )
        // 观测投影只解释已授权事件的安全元数据，不装配或调用 Memory 执行组件。
        val observationConsumer = ":skillforge-core-chat"
        val observationSource = "src/main/java/com/skillforge/core/chat/trajectory/TrajectoryGraph.java"
        val observationReference = Regex(
            "com\\s*\\.\\s*skillforge\\s*\\.\\s*runtime\\s*\\.\\s*memory\\s*\\.\\s*MemoryObservation\\b"
        )
        rootProject.allprojects.filter { it.path != ":agent-runtime-memory" }.forEach { consumer ->
            consumer.configurations
                .filter { it.name in setOf("api", "implementation", "compileOnly", "runtimeOnly") }
                .forEach { configuration ->
                    check(configuration.allDependencies.filterIsInstance<ProjectDependency>()
                        .none { it.dependencyProject.path == ":agent-runtime-memory"
                            && consumer.path !in allowedMemoryConsumers && consumer.path != observationConsumer }) {
                        "${consumer.path}:${configuration.name} must not depend on Memory before host integration"
                    }
                }
            if (consumer.path in allowedMemoryConsumers) {
                return@forEach
            }
            consumer.fileTree("src/main") { include("**/*.java", "**/*.kt", "**/*.groovy") }.files.forEach { source ->
                val text = source.readText(Charsets.UTF_8)
                val checked = if (consumer.path == observationConsumer
                    && source.relativeTo(consumer.projectDir).invariantSeparatorsPath == observationSource)
                    text.replace(observationReference, "") else text
                check(!memoryReference.containsMatchIn(checked)) {
                    "Premature Memory component reference in ${source.relativeTo(rootProject.projectDir)}"
                }
            }
        }
    }
}
tasks.named("check") { dependsOn(verifyHostMemoryCompositionBoundaries) }

apply(from = "gradle/platform-module-boundaries.gradle.kts")
