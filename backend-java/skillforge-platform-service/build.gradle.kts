plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.dependency.management)
}

val springBootVersion = libs.versions.springBoot.get()
val springCloudVersion = libs.versions.springCloud.get()
val springCloudAlibabaVersion = libs.versions.springCloudAlibaba.get()

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:$springCloudVersion")
        mavenBom("com.alibaba.cloud:spring-cloud-alibaba-dependencies:$springCloudAlibabaVersion")
    }
}

dependencies {
    testImplementation(testFixtures(project(":skillforge-core-foundation")))
    implementation(project(":agent-runtime-api"))
    implementation(project(":skillforge-core-billing"))
    implementation(project(":skillforge-core-capability"))
    implementation(project(":skillforge-core-chat"))
    implementation(project(":skillforge-core-channel"))
    implementation(project(":skillforge-core-foundation"))
    implementation(project(":skillforge-core-model"))
    implementation(project(":skillforge-common"))
    implementation(project(":skillforge-core-shared"))
    implementation(project(":skillforge-core-agent"))
    implementation(project(":skillforge-system-agent-runtime"))
    implementation(project(":skillforge-core-knowledge"))
    implementation(project(":skillforge-core-workflow"))
    implementation(project(":skillforge-migration"))
    implementation(project(":agent-runtime-adapter-model"))
    implementation(project(":agent-runtime-session-spi"))
    // 管理员诊断通过 Runtime 所属类型化读取边界解释原冻结事实。
    implementation(project(":agent-runtime-adapter-jdbc"))
    implementation(project(":agent-runtime-session"))
    implementation(project(":agent-runtime-codec-spi"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.mybatis.plus)
    // 版本目录访问器类已接近方法数上限，扩展包直接引用目录版本号声明
    implementation("com.baomidou:mybatis-plus-extension:${libs.versions.mybatisPlus.get()}")
    // 分页插件自 3.5.9 起拆分到 jsqlparser 模块，需显式引入
    implementation("com.baomidou:mybatis-plus-jsqlparser:${libs.versions.mybatisPlus.get()}")
    implementation(libs.sa.token)
    implementation(libs.sa.token.jwt)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.nacos.discovery)
    implementation(libs.nacos.config)
    implementation(libs.sentinel)
    implementation(libs.springdoc)

    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(project(":agent-runtime-adapter-jdbc"))
    testImplementation(testFixtures(project(":agent-runtime-adapter-jdbc")))
    testImplementation(project(":agent-runtime-core"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation("org.junit.platform:junit-platform-launcher")
}

tasks.bootJar {
    archiveFileName.set("skillforge-platform-service.jar")
}

tasks.test {
    exclude("**/SystemAgentSkillActivationBrowserE2ETest.class")
    // 系统智能体创建测试会启动真实前端；页面变化必须使该测试结果失效。
    inputs.files(
        fileTree("../../frontend-platform") { include("src/**", "e2e/system-agent-create-real.spec.ts", "playwright.system-agent-create.config.ts", "vite.config.ts", "package*.json", "index.html") },
        fileTree("../../frontend-shared") { include("src/**", "package.json") },
        fileTree("../../frontend-vendor/vben") { include("packages/**/src/**", "packages/**/package.json", "internal/**/src/**", "internal/**/package.json") },
        fileTree("../../tools/frontend") { include("**/*.ts", "**/*.mjs") },
        files("../../package.json", "../../pnpm-lock.yaml", "../../pnpm-workspace.yaml")
    ).withPropertyName("systemAgentBrowserFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
    val settingsHistoryBrowser = providers.environmentVariable("SETTINGS_HISTORY_BROWSER").orElse("0")
    inputs.property("settingsHistoryBrowser", settingsHistoryBrowser)
    if (settingsHistoryBrowser.get() == "1") inputs.files(
        fileTree("../../frontend-platform") { include("src/**", "e2e/settings-history-real.spec.ts", "playwright.settings-history-real.config.ts", "vite.config.ts", "package*.json", "index.html") },
        fileTree("../../frontend-shared") { include("src/**", "package.json") },
        fileTree("../../frontend-vendor/vben") { include("packages/**/src/**", "packages/**/package.json", "internal/**/src/**", "internal/**/package.json") },
        fileTree("../../tools/frontend") { include("**/*.ts", "**/*.mjs") },
        files("../../package.json", "../../pnpm-lock.yaml", "../../pnpm-workspace.yaml")
    ).withPropertyName("settingsHistoryFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
    val liveContextSummary = providers.environmentVariable("SKILLFORGE_LIVE_CONTEXT_SUMMARY").orElse("0")
    inputs.property("liveContextSummary", liveContextSummary)
    outputs.upToDateWhen { liveContextSummary.get() != "1" }
    outputs.doNotCacheIf("实时摘要验收必须实际调用供应商") { liveContextSummary.get() == "1" }
    inputs.property("liveModelCompatibility", providers.environmentVariable("SKILLFORGE_LIVE_MODEL_COMPATIBILITY").orElse("false"))
    inputs.property("liveDeepSeekModel", providers.environmentVariable("SKILLFORGE_DEEPSEEK_MODEL").orElse(""))
    inputs.property("liveBailianModel", providers.environmentVariable("SKILLFORGE_BAILIAN_MODEL").orElse(""))
    // 浏览器组合验收消费 Java 以外的真实前端源码，防止页面修改后错误复用旧测试结果。
    val browserAcceptance = providers.environmentVariable("SESSION_OBSERVATION_BROWSER").orElse("0")
    inputs.property("sessionObservationBrowser", browserAcceptance)
    if (browserAcceptance.get() == "1") {
        inputs.files(
            fileTree("../../frontend-platform") {
                include("src/**", "e2e/session-observation-live.mjs", "e2e/usage-trajectory-live.mjs", "vite.config.ts", "package*.json", "index.html")
            },
            fileTree("../../frontend-shared") { include("src/**", "package.json") },
            fileTree("../../frontend-vendor/vben") { include("packages/**/src/**", "packages/**/package.json", "internal/**/src/**", "internal/**/package.json") },
            fileTree("../../tools/frontend") { include("**/*.ts", "**/*.mjs") },
            files("../../package.json", "../../pnpm-lock.yaml", "../../pnpm-workspace.yaml")
        ).withPropertyName("sessionObservationFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

tasks.register<Test>("systemAgentSkillActivationBrowserE2E") {
    description = "验证系统智能体按技能发布配置冻结激活方式并执行"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.platform.integration.SystemAgentSkillActivationBrowserE2ETest.publishedSkillActivationControlsSystemAgentRuntime") }
    inputs.property("systemAgentSkillActivation", providers.environmentVariable("E2E_SYSTEM_AGENT_SKILL_ACTIVATION").orElse("candidate"))
    inputs.files(fileTree("../../frontend-platform") { include("src/**", "e2e/system-agent-skill-activation-real.spec.ts", "playwright.system-agent-skill-activation.config.ts", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}
