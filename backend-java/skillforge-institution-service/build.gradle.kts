import org.gradle.api.tasks.testing.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

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

// 冻结依赖测试消费真实 React 官方清单；生产类路径不携带官方技能包。
tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processTestResources") {
    from(rootProject.layout.projectDirectory.dir("../official-skills")) {
        into("official-skills")
        include("web-app/manifest.json")
    }
}

dependencies {
    testImplementation(testFixtures(project(":skillforge-core-foundation")))
    implementation(project(":agent-runtime-api"))
    implementation(project(":skillforge-core-billing"))
    implementation(project(":skillforge-core-capability"))
    implementation(project(":skillforge-core-foundation"))
    implementation(project(":skillforge-core-model"))
    implementation(project(":skillforge-execution-contract"))
    // 显式执行装配根仅消费Core纯值/协议；可执行推进仍由Session唯一协调。
    implementation(project(":agent-runtime-core"))
    testImplementation(testFixtures(project(":skillforge-core-shared")))
    implementation(project(":skillforge-prompts"))
    testImplementation(project(":agent-runtime-core"))
    implementation(project(":skillforge-common"))
    implementation(project(":skillforge-core-shared"))
    implementation(project(":skillforge-core-agent"))
    implementation(project(":skillforge-core-chat"))
    implementation(project(":skillforge-core-workflow"))
    implementation(project(":skillforge-core-knowledge"))
    implementation(project(":skillforge-core-channel"))
    implementation(project(":skillforge-migration"))
    implementation(project(":agent-runtime-spring-boot-starter"))
    implementation(project(":agent-runtime-adapter-jdbc"))
    implementation(project(":agent-runtime-session"))
    implementation(project(":agent-runtime-session-spi"))
    implementation(project(":agent-runtime-memory"))
    implementation(project(":agent-runtime-adapter-model"))
    implementation(project(":agent-runtime-adapter-mcp"))
    implementation(project(":agent-runtime-codec-spi"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.websocket)
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
    implementation("org.jsoup:jsoup:${libs.versions.jsoup.get()}")
    implementation("com.twelvemonkeys.imageio:imageio-webp:${libs.versions.webpImageio.get()}")
    implementation("com.drewnoakes:metadata-extractor:${libs.versions.metadataExtractor.get()}")
    implementation(libs.tika.core)
    implementation(libs.tika.parsers.standard)

    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation("org.junit.platform:junit-platform-launcher")
}

tasks.bootJar {
    archiveFileName.set("skillforge-institution-service.jar")
}

tasks.named<Test>("test") {
    // 独立 JVM 恢复验收通过 Java argfile 复用正式测试运行类路径。
    val recoveryClasspathFile = layout.buildDirectory.file("tmp/jvm-recovery/runtime-classpath.txt")
    systemProperty("skillforge.test.runtime-classpath-file", recoveryClasspathFile.get().asFile.absolutePath)
    doFirst {
        val destination = recoveryClasspathFile.get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(sourceSets.named("test").get().runtimeClasspath.asPath, Charsets.UTF_8)
    }
    // Handoff 浏览器测试从 Java 启动真实前端；这些外部源码变化必须使缓存结果失效。
    inputs.files(fileTree(rootProject.projectDir.parentFile) {
        include("frontend-admin/src/**", "frontend-admin/e2e/handoff-human-assistance.spec.ts")
        include("frontend-admin/playwright.handoff.config.ts", "frontend-admin/vite.config.ts", "frontend-admin/index.html", "frontend-admin/package.json")
        include("frontend-chat/src/**", "frontend-chat/vite.config.ts", "frontend-chat/index.html", "frontend-chat/package.json")
        include("frontend-shared/src/**", "frontend-shared/package.json", "tools/frontend/**")
        include("package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml")
    }).withPropertyName("handoffBrowserSources").withPathSensitivity(PathSensitivity.RELATIVE)
    // 使用模块 build 下的确定路径，CI 不依赖随机端口或后台服务进程。
    val runtimeDocument = layout.buildDirectory.file("reports/open-platform/runtime-openapi.json")
    systemProperty("openPlatform.runtimeDocument", runtimeDocument.get().asFile.absolutePath)
    // 受保护环境 Smoke 只能通过下方显式任务运行。
    exclude("**/StagingRuntimeE2E.class")
    // 真实浏览器需要前端依赖与 Chromium，由独立 browser lane 验收。
    exclude("**/ChatExecutionProcessBrowserE2ETest.class")
    exclude("**/ChatInputRetryBrowserE2ETest.class")
    exclude("**/ChatAcceptedHistoryBrowserE2ETest.class")
    exclude("**/ChatSessionRestoreBrowserE2ETest.class")
    exclude("**/ChatStreamUnknownBrowserE2ETest.class")
    exclude("**/ChatCancelRetryBrowserE2ETest.class")
    exclude("**/KnowledgeSearchScopeBrowserE2ETest.class")
    exclude("**/McpResourceBrowserE2ETest.class")
    exclude("**/ChatSessionCloseReceiptBrowserE2ETest.class")
    exclude("**/ChatTeamDisableBrowserE2ETest.class")
    exclude("**/ChannelRuntimeBrowserE2ETest.class")
    exclude("**/ChannelBindingRoutingBrowserE2ETest.class")
    exclude("**/SessionCitationBrowserE2ETest.class")
    exclude("**/KnowledgeTitleReuseBrowserE2ETest.class")
    exclude("**/KnowledgeSourceChunkConflictBrowserE2ETest.class")
    exclude("**/KnowledgeChunkReceiptBrowserE2ETest.class")
    exclude("**/SkillPackageEncodingBrowserE2ETest.class")
    exclude("**/WorkflowHitlReplyBrowserE2ETest.class")
    exclude("**/WorkflowResumeCancelBrowserE2ETest.class")
    exclude("**/AgentSavePublishTrialBrowserE2ETest.class")
    exclude("**/AgentNoopSaveBrowserE2ETest.class")
    exclude("**/AgentRestoreUnknownBrowserE2ETest.class")
    exclude("**/AgentLifecycleBrowserE2ETest.class")
    exclude("**/AgentNameValidationBrowserE2ETest.class")
    exclude("**/AgentTemplateBudgetBrowserE2ETest.class")
    exclude("**/AgentTemplateResourcesBrowserE2ETest.class")
    exclude("**/AgentTemplateOptionalSlotBrowserE2ETest.class")
    exclude("**/JsonQueryBrowserE2ETest.class")
    exclude("**/JsonSchemaPropertiesBrowserE2ETest.class")
    exclude("**/JsonSchemaUnicodeBrowserE2ETest.class")
    exclude("**/ConvertDatetimeBrowserE2ETest.class")
    exclude("**/KnowledgeChunkOverlapBrowserE2ETest.class")
    exclude("**/KnowledgeSemanticThresholdBrowserE2ETest.class")
    exclude("**/KnowledgeProjectionRepairBrowserE2ETest.class")
    exclude("**/AgentSkillCrossTabBrowserE2ETest.class")
    exclude("**/AgentSkillRemoveLossBrowserE2ETest.class")
    exclude("**/AgentSkillOptionsBrowserE2ETest.class")
    exclude("**/SkillDirectPublishUnknownBrowserE2ETest.class")
    exclude("**/AgentKnowledgeSingleBrowserE2ETest.class")
    exclude("**/EconomicalKnowledgeRebuildBrowserE2ETest.class")
    exclude("**/LegacyKnowledgeRebuildBrowserE2ETest.class")
    exclude("**/KnowledgeRebuild401BrowserE2ETest.class")
    exclude("**/KnowledgeRebuildProgressBrowserE2ETest.class")
    exclude("**/KnowledgeUploadRejectionBrowserE2ETest.class")
    exclude("**/KnowledgeRebuildFenceBrowserE2ETest.class")
    exclude("**/ConversationTaskRecoveryBrowserE2ETest.class")
    exclude("**/TeamMemberCrossTabBrowserE2ETest.class")
    exclude("**/ToolDeploymentRecoveryBrowserE2ETest.class")
    exclude("**/ToolPlatformPermissionBrowserE2ETest.class")
    exclude("**/MemoryDirectoryLimitBrowserE2ETest.class")
    exclude("**/MemoryCorrectionStateBrowserE2ETest.class")
    exclude("**/MemoryCorrectionUnknownBrowserE2ETest.class")
    exclude("**/MemoryForgetUnknownBrowserE2ETest.class")
    exclude("**/AgentTemplateToolOptionsBrowserE2ETest.class")
    exclude("**/MemoryRestoreReviewBrowserE2ETest.class")
    exclude("**/MemoryApprovalUnknownBrowserE2ETest.class")
    exclude("**/BillingReconciliationRecoveryBrowserE2ETest.class")
    exclude("**/WorkflowAgentSingleBrowserE2ETest.class")
    exclude("**/WorkflowToolOptionsBrowserE2ETest.class")
    exclude("**/WorkflowCrossTabBrowserE2ETest.class")
    useJUnitPlatform { excludeTags("runtime-latency", "live", "native-stream-browser", "session-directory-browser", "knowledge-create-browser", "workflow-onboarding-browser", "workflow-publish-browser", "skill-publish-browser", "knowledge-publish-browser", "platform-deployment-browser", "tool-onboarding-browser", "connector-creation-browser", "institution-definition-recovery-browser") }
}

tasks.register<Test>("channelRuntimeBrowserE2E") {
    description = "验证渠道创建后使用会话端运行智能体"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.ChannelRuntimeBrowserE2ETest.createdChannelCanRunAgent") }
    inputs.files(fileTree("../..") {
        include("frontend-admin/src/**", "frontend-admin/e2e/channel-runtime-real.spec.ts", "frontend-admin/playwright.channel*.config.ts", "frontend-admin/vite.config.ts", "frontend-admin/index.html", "frontend-admin/package.json")
        include("frontend-chat/src/**", "frontend-chat/vite.config.ts", "frontend-chat/index.html", "frontend-chat/package.json")
        include("frontend-shared/src/**", "frontend-shared/package.json", "tools/frontend/**", "package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml")
    }).withPropertyName("channelRuntimeFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<Test>("channelBindingRoutingBrowserE2E") {
    description = "验证渠道绑定编辑保留路由参数与实际执行目标"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.ChannelBindingRoutingBrowserE2ETest.bindingEditPreservesRoutingForRuntime") }
    inputs.property("routingMode", providers.environmentVariable("E2E_CHANNEL_BINDING_ROUTING").orElse("candidate"))
    inputs.files(fileTree("../..") {
        include("frontend-admin/src/**", "frontend-admin/e2e/channel-binding-routing-real.spec.ts", "frontend-admin/playwright.config.ts", "frontend-admin/vite.config.ts", "frontend-admin/index.html", "frontend-admin/package.json")
        include("frontend-chat/src/**", "frontend-chat/vite.config.ts", "frontend-chat/index.html", "frontend-chat/package.json")
        include("frontend-shared/src/**", "frontend-shared/package.json", "tools/frontend/**", "package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml")
    }).withPropertyName("channelBindingRoutingFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<Test>("knowledgeTitleReuseBrowserE2E") {
    description = "验证知识标题修订复用原分块并保留新旧来源引用"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.KnowledgeTitleReuseBrowserE2ETest.titleOnlyEditReusesChunksAndRemainsCitable") }
    inputs.property("titleReuseMode", providers.environmentVariable("E2E_KNOWLEDGE_TITLE_REUSE").orElse("candidate"))
    outputs.upToDateWhen { false }
    inputs.files(fileTree("../..") {
        include("frontend-admin/src/**", "frontend-admin/e2e/knowledge-title-reuse-real.spec.ts", "frontend-admin/playwright.config.ts", "frontend-admin/vite.config.ts", "frontend-admin/index.html", "frontend-admin/package.json")
        include("frontend-shared/src/**", "frontend-shared/package.json", "tools/frontend/**", "package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml")
    }).withPropertyName("knowledgeTitleReuseFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<Test>("knowledgeChunkReceiptBrowserE2E") {
    description = "观察分块写入回执丢失后的真实状态"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.KnowledgeChunkReceiptBrowserE2ETest.observesLostChunkReceipt") }
    inputs.property("knowledgeChunkReceipt", providers.environmentVariable("E2E_KNOWLEDGE_CHUNK_RECEIPT").orElse("baseline"))
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/knowledge-chunk-receipt-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("knowledgeSourceChunkConflictBrowserE2E") {
    description = "验证正文草稿与已保存分块的覆盖确认"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.KnowledgeSourceChunkConflictBrowserE2ETest.sourceDraftDoesNotSilentlyOverwriteSavedChunk") }
    inputs.property("knowledgeSourceChunk", providers.environmentVariable("E2E_KNOWLEDGE_SOURCE_CHUNK").orElse("candidate"))
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/knowledge-source-chunk-conflict-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("sessionCitationBrowserE2E") {
    description = "验证会话记录能够读取实际知识引用来源"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.SessionCitationBrowserE2ETest.sessionHistoryCanOpenRuntimeKnowledgeCitation") }
    inputs.property("citationMode", providers.environmentVariable("E2E_SESSION_CITATION").orElse("candidate"))
    inputs.files(fileTree("../..") {
        include("frontend-admin/src/**", "frontend-admin/e2e/session-citation-real.spec.ts", "frontend-admin/playwright.config.ts", "frontend-admin/vite.config.ts", "frontend-admin/index.html", "frontend-admin/package.json")
        include("frontend-shared/src/**", "frontend-shared/package.json", "tools/frontend/**", "package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml")
    }).withPropertyName("sessionCitationFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<Test>("skillPackageEncodingBrowserE2E") {
    description = "验证技能文本编辑保留非文本资源原始字节"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.SkillPackageEncodingBrowserE2ETest.packageResourceEncodingSurvivesTextEditAndRuntime") }
    inputs.property("encodingMode", providers.environmentVariable("E2E_SKILL_PACKAGE_ENCODING").orElse("candidate"))
    outputs.upToDateWhen { false }
    inputs.files(fileTree("../..") {
        include("frontend-admin/src/**", "frontend-admin/e2e/skill-package-encoding-real.spec.ts", "frontend-admin/playwright.config.ts", "frontend-admin/vite.config.ts", "frontend-admin/index.html", "frontend-admin/package.json")
        include("frontend-shared/src/**", "frontend-shared/package.json", "tools/frontend/**", "package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml")
    }).withPropertyName("skillPackageEncodingFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<Test>("agentTemplateBudgetBrowserE2E") {
    description = "验证模板预算在平台发布前拒绝并可修正安装执行"
    group = "verification"
    dependsOn(":skillforge-platform-service:bootJar")
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.AgentTemplateBudgetBrowserE2ETest.templateBudgetValidationPrecedesInstallation") }
    inputs.property("templateBudget", providers.environmentVariable("E2E_TEMPLATE_BUDGET").orElse("candidate"))
    inputs.files(fileTree("../../frontend-platform") { include("src/**", "e2e/template-budget-browser.mjs", "vite.config.*", "package*.json", "index.html") },
        fileTree("../../frontend-shared") { include("src/**", "package.json") }, fileTree("../../tools/frontend") { include("**/*.ts", "**/*.mjs") })
        .withPropertyName("templateBudgetFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.upToDateWhen { false }
}

tasks.register<Test>("billingReconciliationRecoveryBrowserE2E") {
    description = "验证对账配置保存结果恢复"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter {
        includeTestsMatching("com.skillforge.institution.integration.BillingReconciliationRecoveryBrowserE2ETest.committedConfigurationWithLostResponseCannotBeAttributed")
    }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("memoryCorrectionStateBrowserE2E") {
    description = "验证记忆纠正状态与下一运行检索"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.MemoryCorrectionStateBrowserE2ETest.forgottenFactRequiresRestorationBeforeCorrectionAndRuntimeRecall") }
    inputs.property("memoryCorrectionState", providers.environmentVariable("E2E_MEMORY_CORRECTION_STATE").orElse("candidate"))
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/memory-correction-state-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("memoryForgetUnknownBrowserE2E") {
    description = "验证遗忘结果未知后的只读核对"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.MemoryForgetUnknownBrowserE2ETest.lostForgetResponseRequiresReadOnlyStatusCheck") }
    inputs.property("memoryForgetUnknown", providers.environmentVariable("E2E_MEMORY_FORGET_UNKNOWN").orElse("candidate"))
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/memory-forget-unknown-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("memoryCorrectionUnknownBrowserE2E") {
    description = "验证记忆纠正结果未知后只读核对与运行召回"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.MemoryCorrectionUnknownBrowserE2ETest.lostCorrectionResponseRequiresReadOnlyStatusCheck") }
    inputs.property("memoryCorrectionUnknown", providers.environmentVariable("E2E_MEMORY_CORRECTION_UNKNOWN").orElse("candidate"))
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/memory-correction-unknown-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("agentTemplateResourcesBrowserE2E") {
    description = "验证可选资源目录失败后模板仍可安装并执行"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.AgentTemplateResourcesBrowserE2ETest.optionalResourceReadFailureDoesNotBlockTemplateInstallation") }
    inputs.property("agentTemplateResources", providers.environmentVariable("E2E_AGENT_TEMPLATE_RESOURCES").orElse("candidate"))
    inputs.property("templateSummary", providers.environmentVariable("E2E_TEMPLATE_SUMMARY").orElse("0"))
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/agent-template-resources-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("agentTemplateOptionalSlotBrowserE2E") {
    description = "验证已选可选资源清空后安装并执行"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.AgentTemplateOptionalSlotBrowserE2ETest.selectedOptionalResourceCanBeClearedBeforeInstallation") }
    inputs.property("optionalSlotMode", providers.environmentVariable("E2E_OPTIONAL_SLOT_MODE").orElse("candidate"))
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/agent-template-optional-slot-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("sessionCloseReceiptBrowserE2E") {
    description = "验证已确认关闭回执可直接完成会话状态更新"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.ChatSessionCloseReceiptBrowserE2ETest.confirmedCloseReceiptDoesNotRequireAnotherDetailRead") }
    inputs.property("sessionCloseReceipt", providers.environmentVariable("E2E_SESSION_CLOSE_RECEIPT").orElse("candidate"))
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/session-close-receipt-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("memoryApprovalUnknownBrowserE2E") {
    description = "观察记忆批准回执丢失和后续召回"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.MemoryApprovalUnknownBrowserE2ETest.approvalResponseLossPreservesActualFactAndRuntimeRecall") }
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/memory-approval-unknown-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    inputs.property("memoryApprovalUnknown", providers.environmentVariable("E2E_MEMORY_APPROVAL_UNKNOWN").orElse("baseline"))
    outputs.upToDateWhen { false }
}

tasks.register<Test>("memoryRestoreReviewBrowserE2E") {
    description = "验证遗忘恢复保留记忆审核与实际检索边界"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.MemoryRestoreReviewBrowserE2ETest.restorePreservesPendingSharingReviewBeforeRuntimeRecall") }
    inputs.property("memoryRestoreReview", providers.environmentVariable("E2E_MEMORY_RESTORE_REVIEW").orElse("candidate"))
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/memory-restore-review-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("memoryDirectoryLimitBrowserE2E") {
    description = "验证记忆目录与运行时检索"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter {
        includeTestsMatching("com.skillforge.institution.integration.MemoryDirectoryLimitBrowserE2ETest.oldestFactOutsideFirstHundredRemainsRuntimeSearchable")
    }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("knowledgeSemanticThresholdBrowserE2E") {
    description = "验证语义阈值与真实向量检索结果"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.KnowledgeSemanticThresholdBrowserE2ETest.healthyLowSimilarityCannotBypassSemanticThreshold") }
    inputs.property("semanticThreshold", providers.environmentVariable("E2E_SEMANTIC_THRESHOLD").orElse("candidate"))
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/knowledge-semantic-threshold-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("knowledgeProjectionRepairBrowserE2E") {
    description = "验证知识来源投影页面修复及运行引用"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.KnowledgeProjectionRepairBrowserE2ETest.publishedSourceProjectionCanBeRepairedFromDocumentPage") }
    inputs.property("knowledgeProjectionRepair", providers.environmentVariable("E2E_KNOWLEDGE_PROJECTION_REPAIR").orElse("candidate"))
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/knowledge-projection-repair-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("knowledgeChunkOverlapBrowserE2E") {
    description = "验证知识长段重叠分块与真实检索引用"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.KnowledgeChunkOverlapBrowserE2ETest.longParagraphRemainsSearchableAcrossChunkBoundary") }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("convertDatetimeBrowserE2E") {
    description = "验证时间转换参数错误回传模型后在同一运行中纠正"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.ConvertDatetimeBrowserE2ETest.invalidTimezoneCanBeCorrectedInTheSameRuntime") }
    inputs.property("convertDatetime", providers.environmentVariable("E2E_CONVERT_DATETIME").orElse("candidate"))
    inputs.files(fileTree("../..") {
        include("frontend-admin/src/**", "frontend-admin/e2e/convert-datetime-real.spec.ts", "frontend-admin/playwright.config.ts", "frontend-admin/vite.config.ts", "frontend-admin/index.html", "frontend-admin/package.json")
        include("frontend-shared/src/**", "frontend-shared/package.json", "tools/frontend/**", "package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml")
    }).withPropertyName("convertDatetimeFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.upToDateWhen { false }
}

tasks.register<Test>("jsonSchemaUnicodeBrowserE2E") {
    description = "验证 JSON Schema 字符长度的真实工具回传"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.JsonSchemaUnicodeBrowserE2ETest.unicodeLengthResultsReachNextModelAndBrowser") }
    inputs.property("jsonSchemaUnicode", providers.environmentVariable("E2E_JSON_SCHEMA_UNICODE").orElse("candidate"))
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/json-schema-unicode-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("jsonSchemaPropertiesBrowserE2E") {
    description = "验证 JSON Schema 额外属性约束的真实工具回传"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.JsonSchemaPropertiesBrowserE2ETest.additionalPropertyResultsReachNextModelAndBrowser") }
    inputs.property("jsonSchemaProperties", providers.environmentVariable("E2E_JSON_SCHEMA_PROPERTIES").orElse("candidate"))
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/json-schema-properties-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("jsonQueryBrowserE2E") {
    description = "验证 JSON 查询空值与错误结果的真实回传"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.JsonQueryBrowserE2ETest.queryResultsReachNextModelAndBrowser") }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("toolDeploymentDirectoryBrowserE2E") {
    description = "验证部署目录展示、名称筛选与同一工具的真实执行"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.ToolDeploymentDirectoryBrowserE2ETest.deploymentNamesRemainSearchableAndOriginalToolRuns") }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("toolDeploymentRecoveryBrowserE2E") {
    description = "验证工具部署创建恢复与运行时调用"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter {
        includeTestsMatching("com.skillforge.institution.integration.ToolDeploymentRecoveryBrowserE2ETest.lostCreateResponseLeavesOriginalResultUnknownAndRuntimeUsesDeployment")
        includeTestsMatching("com.skillforge.institution.integration.ToolDeploymentRecoveryBrowserE2ETest.lostStopResponseKeepsOldPageWhileRuntimeRejectsDeployment")
    }
    inputs.property("toolStopMode", providers.environmentVariable("E2E_TOOL_STOP_MODE").orElse("candidate"))
    outputs.upToDateWhen { false }
}

tasks.register<Test>("toolPlatformPermissionBrowserE2E") {
    description = "验证无连接器读取权限时的平台工具部署与运行"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.ToolPlatformPermissionBrowserE2ETest.platformDeploymentDoesNotRequireConnectorRead") }
    inputs.property("toolPlatformPermission", providers.environmentVariable("E2E_TOOL_PLATFORM_PERMISSION").orElse("candidate"))
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/tool-platform-permission-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("teamMemberCrossTabBrowserE2E") {
    description = "验证团队成员目录与真实运行"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter {
        includeTestsMatching("com.skillforge.institution.integration.TeamMemberCrossTabBrowserE2ETest.publishedMemberAppearsInTeamAndPublishedTeamRuns")
    }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("conversationTaskRecoveryBrowserE2E") {
    description = "验证会话任务原操作回执的浏览器恢复路径"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter {
        includeTestsMatching("com.skillforge.institution.integration.ConversationTaskRecoveryBrowserE2ETest.realTaskOperationResponseLossUsesOriginalReceipt")
    }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("connectorCreationBrowserE2E") {
    description = "验证连接器创建回执的真实浏览器恢复路径"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    useJUnitPlatform { includeTags("connector-creation-browser") }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/connector-creation-real.spec.ts", "playwright.connector-creation-real.config.ts", "vite.config.*", "package*.json", "index.html") })
        .withPropertyName("connectorCreationFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<Test>("toolOnboardingBrowserE2E") {
    description = "验证工具接入时自动发现并显式确认的真实浏览器路径"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    useJUnitPlatform { includeTags("tool-onboarding-browser") }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/tool-onboarding-real.spec.ts", "playwright.tool-onboarding-real.config.ts", "vite.config.*", "package*.json", "index.html") })
        .withPropertyName("toolOnboardingFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<Test>("platformDeploymentBrowserE2E") {
    description = "验证平台选择机构后自动读取部署与授权的双宿主浏览器路径"
    group = "verification"
    dependsOn(":skillforge-platform-service:bootJar")
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    useJUnitPlatform { includeTags("platform-deployment-browser") }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
    inputs.files(fileTree("../../frontend-platform") { include("src/**", "e2e/platform-deployment-real.spec.ts", "e2e/tenant-create-recovery-real.spec.ts", "playwright.deployment-real.config.ts", "playwright.tenant-create-recovery.config.ts", "vite.config.*", "package*.json", "index.html") })
        .withPropertyName("platformDeploymentFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/agent-deployment-real.spec.ts", "playwright.agent-deployment-real.config.ts", "vite.config.*", "package*.json", "index.html") })
        .withPropertyName("institutionDeploymentFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<Test>("knowledgePublishBrowserE2E") {
    description = "验证知识库范围选择、发布及原回执的真实浏览器路径"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    useJUnitPlatform { includeTags("knowledge-publish-browser") }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/knowledge-publish-real.spec.ts", "playwright.knowledge-publish.config.ts", "vite.config.*", "package*.json", "index.html") })
}

tasks.register<Test>("workflowOnboardingBrowserE2E") {
    description = "验证 Agent 内工作流安装与挂载的真实浏览器路径"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    useJUnitPlatform { includeTags("workflow-onboarding-browser") }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
}

tasks.register<Test>("workflowPublishBrowserE2E") {
    description = "验证工作流保存发布与原回执的真实浏览器路径"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    useJUnitPlatform { includeTags("workflow-publish-browser") }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
}

tasks.register<Test>("institutionDefinitionRecoveryBrowserE2E") {
    description = "验证机构共享定义原保存响应中断后的恢复基线及精确修订运行"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    useJUnitPlatform { includeTags("institution-definition-recovery-browser") }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
}

tasks.register<Test>("skillDirectPublishUnknownBrowserE2E") {
    description = "观察技能直接发布回执丢失与实际运行"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.SkillDirectPublishUnknownBrowserE2ETest.directPublishLostResponsePreservesObservableFacts") }
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/skill-direct-publish-unknown-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    inputs.property("skillDirectPublish", providers.environmentVariable("E2E_SKILL_DIRECT_PUBLISH").orElse("baseline"))
    outputs.upToDateWhen { false }
}

tasks.register<Test>("skillPublishBrowserE2E") {
    description = "验证技能保存发布、原回执与持久化的真实浏览器路径"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    useJUnitPlatform { includeTags("skill-publish-browser") }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/skill-publish-real.spec.ts", "playwright.skill-publish.config.ts", "vite.config.*", "package*.json", "index.html") })
        .withPropertyName("skillPublishFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<Test>("knowledgeCreateBrowserE2E") {
    description = "验证知识库创建后进入文档维护的真实浏览器路径"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    useJUnitPlatform { includeTags("knowledge-create-browser") }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/knowledge-create-real.spec.ts", "playwright.knowledge-create.config.ts", "vite.config.*", "package*.json", "index.html") })
        .withPropertyName("knowledgeCreateFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<Test>("chatStreamUnknownBrowserE2E") {
    description = "验证模型流中断后的原运行状态和页面恢复"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.ChatStreamUnknownBrowserE2ETest.incompleteStreamPreservesUnknownAndClearsPreview") }
    inputs.files(fileTree("../../frontend-chat") { include("src/**", "e2e/stream-unknown-browser.mjs", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("chatCancelRetryBrowserE2E") {
    description = "验证未到达的取消可显式继续并核验原运行"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.ChatCancelRetryBrowserE2ETest.cancelNotArrivedCanBeExplicitlyContinued") }
    inputs.property("chatCancelRetry", providers.environmentVariable("E2E_CHAT_CANCEL_RETRY").orElse("candidate"))
    inputs.files(fileTree("../../frontend-chat") { include("src/**", "e2e/cancel-retry-browser.mjs", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("sessionDirectoryBrowserE2E") {
    description = "验证独立会话端目录标题、搜索和恢复"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    useJUnitPlatform { includeTags("session-directory-browser") }
    filter {
        includeTestsMatching("*ChatRuntimeChannelE2ETest.standaloneBrowserFindsFirstUserMessageWithoutCallingModel")
        includeTestsMatching("*ChatRuntimeChannelE2ETest.standaloneBrowserRestoresClosedSessionAsReadOnly")
    }
    inputs.property("closedSession", providers.environmentVariable("E2E_CLOSED_SESSION").orElse("0"))
    inputs.property("unknownSteering", providers.environmentVariable("E2E_UNKNOWN_STEERING").orElse("0"))
    inputs.property("cursorRecovery", providers.environmentVariable("E2E_CURSOR_RECOVERY").orElse("0"))
    inputs.property("previewStream", providers.environmentVariable("E2E_PREVIEW_STREAM").orElse("0"))
    setFinalizedBy(emptyList<Any>())
    inputs.files(fileTree("../../frontend-chat") { include("src/**", "e2e/session-directory-browser.mjs", "e2e/closed-session-browser.mjs", "e2e/history-scroll-browser.mjs", "e2e/mixed-upload-browser.mjs", "vite.config.*", "package*.json", "index.html") })
        .withPropertyName("sessionDirectoryFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<Test>("mcpElicitationBrowserE2E") {
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.ChatExecutionWaitingE2ETest.standaloneBrowserContinuesExactMcpElicitation") }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("chatTeamDisableBrowserE2E") {
    description = "验证个人团队停用与重新发布后的原会话提交"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.ChatTeamDisableBrowserE2ETest.disabledTeamBlocksExistingConversationUntilRepublished") }
    inputs.property("teamDisable", providers.environmentVariable("E2E_CHAT_TEAM_DISABLE").orElse("candidate"))
    inputs.files(fileTree("../../frontend-chat") { include("src/**", "e2e/team-disable-browser.mjs", "vite.config.*", "package*.json", "index.html") })
        .withPropertyName("teamDisableFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.upToDateWhen { false }
}

tasks.register<Test>("chatSessionRestoreBrowserE2E") {
    description = "验证目录读取失败后恢复原会话并延续模型上下文"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.ChatSessionRestoreBrowserE2ETest.savedSessionRestoresWhenDirectoryRefreshFails") }
    inputs.property("sessionRestore", providers.environmentVariable("E2E_CHAT_SESSION_RESTORE").orElse("candidate"))
    inputs.files(fileTree("../../frontend-chat") { include("src/**", "e2e/session-restore-browser.mjs", "vite.config.*", "package*.json", "index.html") })
        .withPropertyName("sessionRestoreFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.upToDateWhen { false }
}

tasks.register<Test>("chatAcceptedHistoryBrowserE2E") {
    description = "验证消息受理后历史读取失败的页面与运行事实"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.ChatAcceptedHistoryBrowserE2ETest.acceptedInputRemainsObservableWhenHistoryReadFails") }
    inputs.property("acceptedHistoryMode", providers.environmentVariable("E2E_CHAT_ACCEPTED_HISTORY").orElse("candidate"))
    inputs.files(fileTree("../../frontend-chat") { include("src/**", "e2e/accepted-history-browser.mjs", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("chatInputRetryBrowserE2E") {
    description = "验证原输入结果未知时重试拒绝与历史确认"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.ChatInputRetryBrowserE2ETest.rejectedRetryKeepsOriginalInputUntilHistoryConfirmsIt") }
    inputs.property("inputRetry", providers.environmentVariable("E2E_CHAT_INPUT_RETRY").orElse("candidate"))
    inputs.files(fileTree("../../frontend-chat") { include("src/**", "e2e/input-retry-browser.mjs", "vite.config.*", "package*.json", "index.html") })
        .withPropertyName("inputRetryFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.upToDateWhen { false }
}

tasks.register<Test>("nativeStreamBrowserE2E") {
    description = "锁住供应商完成信号，验收终结工具参数不提前公开与单次最终交付"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    useJUnitPlatform { includeTags("native-stream-browser") }
    filter { includeTestsMatching("*ChatRuntimeChannelE2ETest.finalAnswerArgumentsStayPrivateUntilProviderAndRunComplete") }
    setFinalizedBy(emptyList<Any>())
    inputs.files(fileTree("../../frontend-chat") { include("src/**", "e2e/true-stream.mjs", "vite.config.*", "package*.json", "index.html") })
        .withPropertyName("nativeStreamFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree("../../tools/full_project_testing/fixtures") { include("user_prompts*.json") })
        .withPropertyName("nativeStreamUserPrompts").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<Test>("chatProcessBrowserE2E") {
    description = "运行真实 Chat SSE 执行过程与历史恢复的浏览器验收"
    inputs.property("inputRecovery", providers.environmentVariable("E2E_CHAT_INPUT_RECOVERY").orElse("0"))
    inputs.property("inputRecoveryBaseline", providers.environmentVariable("E2E_CHAT_INPUT_RECOVERY_BASELINE").orElse("0"))
    inputs.property("historyPages", providers.environmentVariable("E2E_CHAT_HISTORY_PAGES").orElse("0"))
    inputs.property("httpStatus", providers.environmentVariable("E2E_HTTP_STATUS").orElse("0"))
    inputs.property("sessionMessageContent", providers.environmentVariable("E2E_SESSION_MESSAGE_CONTENT").orElse("0"))
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    // 父类 API 用例仍由普通 test/API lane 执行；此处只选择真实浏览器方法。
    filter {
        includeTestsMatching("com.skillforge.institution.integration.ChatExecutionProcessBrowserE2ETest.realBrowserRestoresPublicReplyWithoutExposingInternalReasoning")
    }
    inputs.files(
        fileTree("../../frontend-admin") {
            include("src/**", "e2e/chat-execution-process.spec.ts", "e2e/session-message-content-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html")
        },
        fileTree("../../frontend-shared") { include("src/**", "package.json") },
        fileTree("../../frontend-vendor/vben") { include("packages/**/src/**", "packages/**/package.json", "internal/**/src/**", "internal/**/package.json") },
        files("../../package.json", "../../pnpm-lock.yaml", "../../pnpm-workspace.yaml"),
    ).withPropertyName("chatProcessBrowserFrontend").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<Test>("workflowHitlReplyBrowserE2E") {
    description = "验证工作流测试待办回复的真实浏览器与运行时链路"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter {
        includeTestsMatching("com.skillforge.institution.integration.WorkflowHitlReplyBrowserE2ETest.realWorkflowReplyAfterResponseInterruption")
    }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("workflowToolOptionsBrowserE2E") {
    description = "验证工作流工具依赖选项及实际运行"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.WorkflowToolOptionsBrowserE2ETest.workflowToolOptionsRemainUsableWhenVersionReadFails") }
    inputs.property("workflowToolOptions", providers.environmentVariable("E2E_WORKFLOW_TOOL_OPTIONS").orElse("baseline"))
    outputs.upToDateWhen { false }
}

tasks.register<Test>("workflowAgentSingleBrowserE2E") {
    description = "验证唯一工作流接入及同一安装版本运行"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter {
        includeTestsMatching("com.skillforge.institution.integration.WorkflowAgentSingleBrowserE2ETest.browserOnboardsSingleWorkflowAndRuntimeExecutesExactInstallation")
    }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("workflowCrossTabBrowserE2E") {
    description = "验证跨标签发布工作流后刷新接入及运行"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter {
        includeTestsMatching("com.skillforge.institution.integration.WorkflowCrossTabBrowserE2ETest.publishedWorkflowAppearsAfterManualRefreshAndRuns")
    }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("agentKnowledgeSingleBrowserE2E") {
    description = "验证唯一已发布知识库预选及真实检索"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter {
        includeTestsMatching("com.skillforge.institution.integration.AgentKnowledgeSingleBrowserE2ETest.singlePublishedKnowledgeMountFeedsSameAgentRuntime")
    }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("knowledgeDeletedReuseBrowserE2E") {
    description = "验证运行中删除来源后的知识复用"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.KnowledgeDeletedReuseBrowserE2ETest.repeatedQueryAfterDelete") }
    inputs.property("deletedReuseMode", providers.environmentVariable("E2E_KNOWLEDGE_DELETED_REUSE").orElse("deleted"))
    outputs.upToDateWhen { false }
}

tasks.register<Test>("knowledgeSoftDeletedSourceBrowserE2E") {
    description = "验证删除来源后的检索与引用边界"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.KnowledgeSoftDeletedSourceBrowserE2ETest.softDeletedPublishedSourceIsExcluded") }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("economicalKnowledgeRebuildBrowserE2E") {
    description = "验证经济知识库拒绝向量重建及运行时检索"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter {
        includeTestsMatching("com.skillforge.institution.integration.EconomicalKnowledgeRebuildBrowserE2ETest.economicalRebuildRejectedAndOriginalSourceFeedsRuntime")
    }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("legacyKnowledgeRebuildBrowserE2E") {
    description = "验证历史知识库重建期间运营默认变化"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter {
        includeTestsMatching("com.skillforge.institution.integration.LegacyKnowledgeRebuildBrowserE2ETest.defaultChangesDuringRebuildAndRuntimeUsesPublishedSources")
    }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("knowledgeRebuild401BrowserE2E") {
    description = "验证重建发布响应中断后的真实页面及运行时"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter {
        includeTestsMatching("com.skillforge.institution.integration.KnowledgeRebuild401BrowserE2ETest.lostUnauthorizedPublicationResponseUsesSameRuntimeVersion")
    }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("knowledgeUploadRejectionBrowserE2E") {
    description = "验证知识上传拒绝回执及重新上传后的运行时检索"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.KnowledgeUploadRejectionBrowserE2ETest.rejectedUploadCanEndAndNewSourceCanReachRuntime") }
    inputs.property("knowledgeUploadRejection", providers.environmentVariable("E2E_KNOWLEDGE_UPLOAD_REJECTION").orElse("candidate"))
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/knowledge-upload-rejection-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("knowledgeRebuildProgressBrowserE2E") {
    description = "验证知识重建进展刷新及运行时检索"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter {
        includeTestsMatching("com.skillforge.institution.integration.KnowledgeRebuildProgressBrowserE2ETest.readyRequiresManualRefreshBeforePublishedRuntime")
    }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("knowledgeRebuildFenceBrowserE2E") {
    description = "验证封存重建发布后拒绝迟到写入"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter {
        includeTestsMatching("com.skillforge.institution.integration.KnowledgeRebuildFenceBrowserE2ETest.fencedPublicationRejectsLateOriginalWrite")
    }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("agentSkillCrossTabBrowserE2E") {
    description = "验证跨标签发布技能与原智能体挂载及运行"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter {
        includeTestsMatching("com.skillforge.institution.integration.AgentSkillCrossTabBrowserE2ETest.publishedSkillAppearsInOriginalMountDrawerAndRuntime")
    }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("agentSkillOptionsBrowserE2E") {
    description = "验证技能候选摘要读取、挂载与运行"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.AgentSkillOptionsBrowserE2ETest.skillMountUsesSinglePublishedOptions") }
    inputs.property("agentSkillOptions", providers.environmentVariable("E2E_AGENT_SKILL_OPTIONS").orElse("candidate"))
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/agent-skill-options-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("agentSavePublishTrialBrowserE2E") {
    description = "验证保存发布并试用的真实页面和运行时"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter {
        includeTestsMatching("com.skillforge.institution.integration.AgentSavePublishTrialBrowserE2ETest.editedAgentPublicationIsUsedByRealTrial")
        includeTestsMatching("com.skillforge.institution.integration.AgentSavePublishTrialBrowserE2ETest.crossTabDelegationCatalogReachesRuntime")
    }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("mcpResourceBrowserE2E") {
    description = "验证 MCP 文本资源返回模型和会话"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.McpResourceBrowserE2ETest.embeddedTextResourceReachesModelAndBrowser") }
    inputs.property("mcpResourceMode", providers.environmentVariable("E2E_MCP_RESOURCE").orElse("candidate"))
    inputs.files(fileTree("../../frontend-chat") { include("src/**", "e2e/mcp-resource-browser.mjs", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("knowledgeSearchScopeBrowserE2E") {
    description = "验证知识检索的预览与发布范围"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.KnowledgeSearchScopeBrowserE2ETest.unpublishedPreviewAndPublishedRuntimeUseExplicitScope") }
    inputs.property("knowledgeSearchScopeMode", providers.environmentVariable("E2E_KNOWLEDGE_SCOPE_MODE").orElse("candidate"))
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/knowledge-search-scope-real.spec.ts", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("agentTemplateToolOptionsBrowserE2E") {
    description = "验证模板工具槽目录及运行"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.AgentTemplateToolOptionsBrowserE2ETest.toolSlotUsesAvailableDeploymentWithoutVersionFanout") }
    inputs.property("templateToolOptionsMode", providers.environmentVariable("E2E_TEMPLATE_TOOL_MODE").orElse("candidate"))
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/agent-template-tool-options-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("agentRestoreUnknownBrowserE2E") {
    description = "采集历史草稿恢复回执丢失后的真实状态"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.AgentRestoreUnknownBrowserE2ETest.lostRestoreResponseLeavesDraftAndPublishedRuntimeObservable") }
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/agent-restore-unknown-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    inputs.property("restoreUnknownMode", System.getenv("E2E_AGENT_RESTORE_UNKNOWN") ?: "baseline")
    outputs.upToDateWhen { false }
}

tasks.register<Test>("agentNoopSaveBrowserE2E") {
    description = "验证未变更的已发布智能体不产生伪草稿"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.AgentNoopSaveBrowserE2ETest.unchangedPublishedAgentDoesNotNeedDraftSave") }
    inputs.property("agentNoopSave", providers.environmentVariable("E2E_AGENT_NOOP_SAVE").orElse("candidate"))
    inputs.property("agentNoopApiExpectClean", providers.environmentVariable("E2E_AGENT_NOOP_API_EXPECT_CLEAN").orElse("0"))
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/agent-noop-save-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("agentNameValidationBrowserE2E") {
    description = "验证智能体名称边界与真实保存发布执行"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.AgentNameValidationBrowserE2ETest.boundedAgentNameCanBePublishedAndUsedByRuntime") }
    inputs.property("agentNameValidation", providers.environmentVariable("E2E_AGENT_NAME_VALIDATION").orElse("candidate"))
    inputs.files(fileTree("../../frontend-admin") { include("src/**", "e2e/agent-name-validation-real.spec.ts", "playwright.config.*", "vite.config.*", "package*.json", "index.html") },
            fileTree("../../frontend-shared") { include("src/**", "package.json") })
    outputs.upToDateWhen { false }
}

tasks.register<Test>("agentLifecycleBrowserE2E") {
    description = "验证智能体停用、重新发布与真实运行的页面闭环"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter {
        includeTestsMatching("com.skillforge.institution.integration.AgentLifecycleBrowserE2ETest.disabledAgentCanBeRepublishedAndRunFromBrowser")
    }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("workflowResumeCancelBrowserE2E") {
    description = "验证人工回复后的工作流续跑取消竞争"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter {
        includeTestsMatching("com.skillforge.institution.integration.WorkflowResumeCancelBrowserE2ETest.realReplyThenCancelDuringResume")
    }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("liveToolConversationE2E") {
    description = "显式验收 DeepSeek 只读工具调用、同会话续聊及持久重放，最多三次模型调用"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    useJUnitPlatform { includeTags("live") }
    filter {
        includeTestsMatching("com.skillforge.institution.integration.ChatRuntimeChannelE2ETest.liveDeepSeekMcpToolAndConversationReplay")
    }
    setFinalizedBy(emptyList<Any>())
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
    outputs.upToDateWhen { false }
    doFirst {
        if (System.getenv("SKILLFORGE_LIVE_TOOL_CONVERSATION") != "1" || System.getenv("SDK4_LIVE_MODEL_KEY").isNullOrBlank()) {
            throw GradleException("liveToolConversationE2E 需要 SKILLFORGE_LIVE_TOOL_CONVERSATION=1 与 SDK4_LIVE_MODEL_KEY")
        }
    }
}

tasks.register<Test>("liveControlledMcpConversationE2E") {
    description = "真实 DeepSeek 与受控 MCP 的工具调用、同会话续聊及幂等重放，最多三次模型调用"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    useJUnitPlatform { includeTags("live") }
    filter {
        includeTestsMatching("com.skillforge.institution.integration.ChatExecutionWaitingE2ETest.liveDeepSeekControlledMcpAndConversationReplay")
    }
    setFinalizedBy(emptyList<Any>())
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
    outputs.upToDateWhen { false }
    doFirst {
        if (System.getenv("SKILLFORGE_LIVE_CONTROLLED_MCP") != "1" || System.getenv("SDK4_LIVE_MODEL_KEY").isNullOrBlank()) {
            throw GradleException("liveControlledMcpConversationE2E 需要 SKILLFORGE_LIVE_CONTROLLED_MCP=1 与 SDK4_LIVE_MODEL_KEY")
        }
    }
}

tasks.register<Test>("liveConversationE2E") {
    description = "显式验收 DeepSeek 同会话历史续聊与幂等重放，最多两次模型调用"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    useJUnitPlatform { includeTags("live") }
    filter {
        includeTestsMatching("com.skillforge.institution.integration.ChatRuntimeChannelE2ETest.liveDeepSeekConversationRestoresHistoryAndReplaysWithoutNewCalls")
    }
    setFinalizedBy(emptyList<Any>())
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
    outputs.upToDateWhen { false }
    doFirst {
        if (System.getenv("SKILLFORGE_LIVE_CONVERSATION") != "1" || System.getenv("SDK4_LIVE_MODEL_KEY").isNullOrBlank()) {
            throw GradleException("liveConversationE2E 需要 SKILLFORGE_LIVE_CONVERSATION=1 与 SDK4_LIVE_MODEL_KEY")
        }
    }
}

tasks.register<Test>("liveChatProcessBrowserE2E") {
    description = "显式调用一次 DeepSeek，验收真实浏览器 SSE 公开回复与持久历史恢复"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    useJUnitPlatform { includeTags("live-browser") }
    filter {
        includeTestsMatching("com.skillforge.institution.integration.ChatExecutionProcessBrowserE2ETest.liveDeepSeekBrowserSubmitsOnceAndRestoresPersistedPublicReply")
    }
    setFinalizedBy(emptyList<Any>())
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
    outputs.upToDateWhen { false }
    doFirst {
        if (System.getenv("SKILLFORGE_LIVE_CHAT_BROWSER") != "1" || System.getenv("SDK4_LIVE_MODEL_KEY").isNullOrBlank()) {
            throw GradleException("liveChatProcessBrowserE2E 需要 SKILLFORGE_LIVE_CHAT_BROWSER=1 与 SDK4_LIVE_MODEL_KEY")
        }
    }
}

// 真实模型验收单独编译和选择，普通 compileTestJava / test 不读取来源连接或发起付费调用。
// 显式付费知识模型验收独立于普通测试；预留和供应商回执写入一次性批次账本。
val liveKnowledgeAcceptanceSourceSet = sourceSets.create("liveKnowledgeAcceptance") {
    val ordinaryTests = sourceSets.named("test").get()
    compileClasspath += sourceSets.main.get().output + ordinaryTests.output + ordinaryTests.compileClasspath
    runtimeClasspath += output + sourceSets.main.get().output + ordinaryTests.output + ordinaryTests.runtimeClasspath
}
configurations.named(liveKnowledgeAcceptanceSourceSet.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}
configurations.named(liveKnowledgeAcceptanceSourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
}
tasks.register<Test>("liveKnowledgeAcceptance") {
    description = "在独立数据库中验收真实 Embedding 重建发布及 Rerank，按持久账本限制外调"
    group = "verification"
    testClassesDirs = liveKnowledgeAcceptanceSourceSet.output.classesDirs
    classpath = liveKnowledgeAcceptanceSourceSet.runtimeClasspath
    useJUnitPlatform()
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
    doFirst {
        if (System.getenv("LIVE_KNOWLEDGE_ACCEPTANCE") != "true") {
            throw GradleException("liveKnowledgeAcceptance 需要显式 LIVE_KNOWLEDGE_ACCEPTANCE=true")
        }
        listOf("LIVE_KNOWLEDGE_BATCH_DIR", "DASHSCOPE_API_KEY").forEach { name ->
            if (System.getenv(name).isNullOrBlank()) throw GradleException("liveKnowledgeAcceptance 缺少环境变量 $name")
        }
    }
}

val livePublicAcceptanceSourceSet = sourceSets.create("livePublicAcceptance") {
    val ordinaryTests = sourceSets.named("test").get()
    compileClasspath += ordinaryTests.output + ordinaryTests.compileClasspath
    runtimeClasspath += output + ordinaryTests.output + ordinaryTests.runtimeClasspath
}
configurations.named(livePublicAcceptanceSourceSet.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}
configurations.named(livePublicAcceptanceSourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
}
tasks.register<JavaExec>("livePublicBudgetPreflight") {
    description = "只读冻结真实模型验收预算，不启动服务或发起模型调用"
    group = "verification"
    classpath = livePublicAcceptanceSourceSet.runtimeClasspath
    mainClass.set("com.skillforge.institution.integration.LivePublicBudget")
}
tasks.register<Test>("livePublicAcceptance") {
    description = "在独立数据库中验收当前已发布默认模型的公开对话边界"
    group = "verification"
    testClassesDirs = livePublicAcceptanceSourceSet.output.classesDirs
    classpath = livePublicAcceptanceSourceSet.runtimeClasspath
    useJUnitPlatform()
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
    doFirst {
        if (System.getenv("SKILLFORGE_LIVE_PUBLIC_ACCEPTANCE") != "1") {
            throw GradleException("livePublicAcceptance 需要显式 SKILLFORGE_LIVE_PUBLIC_ACCEPTANCE=1")
        }
        listOf("LIVE_PUBLIC_SOURCE_TENANT_ID", "LIVE_PUBLIC_SOURCE_MODEL_ID",
            "LIVE_PUBLIC_SOURCE_MODEL_REVISION", "LIVE_PUBLIC_SOURCE_BUDGET_REVISION",
            "LIVE_PUBLIC_RUNTIME_REVISION", "LIVE_PUBLIC_BATCH_ID", "LIVE_PUBLIC_BATCH_DIR",
            "LIVE_PUBLIC_MAX_CALLS", "LIVE_PUBLIC_MAX_TOKENS", "LIVE_PUBLIC_EVIDENCE_FILE").forEach { name ->
            if (System.getenv(name).isNullOrBlank()) throw GradleException("livePublicAcceptance 缺少环境变量 $name")
        }
        val batch = Path.of(System.getenv("LIVE_PUBLIC_BATCH_DIR")).toAbsolutePath().normalize()
        val evidence = Path.of(System.getenv("LIVE_PUBLIC_EVIDENCE_FILE")).toAbsolutePath().normalize()
        if (!evidence.startsWith(batch) || Files.exists(evidence)) {
            throw GradleException("本批证据必须为独立批次目录内的新文件，不覆盖历史验收")
        }
        Files.createDirectories(batch)
        // 原子占用一次性批次；失败也保留占用事实，不借重新运行重置费用账本。
        Files.writeString(batch.resolve("started-batch.txt"),
            System.getenv("LIVE_PUBLIC_BATCH_ID") + "\n" + System.getenv("LIVE_PUBLIC_RUNTIME_REVISION"),
            StandardOpenOption.CREATE_NEW)
    }
}

// 图片真实外调独立门禁，来源库只读，不启动应用或修改共享数据。
val liveVisionSourceSet = sourceSets.create("liveVisionAcceptance") {
    val ordinaryTests = sourceSets.named("test").get()
    compileClasspath += ordinaryTests.output + ordinaryTests.compileClasspath
    runtimeClasspath += output + ordinaryTests.output + ordinaryTests.runtimeClasspath
}
configurations.named(liveVisionSourceSet.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}
configurations.named(liveVisionSourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
}
tasks.register<Test>("liveVisionAcceptance") {
    description = "只读当前已选模型并通过实际媒体准备和 ModelPort 验证随机图片像素"
    group = "verification"
    testClassesDirs = liveVisionSourceSet.output.classesDirs
    classpath = liveVisionSourceSet.runtimeClasspath
    useJUnitPlatform()
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
    doFirst {
        if (System.getenv("SKILLFORGE_LIVE_VISION_ACCEPTANCE") != "1")
            throw GradleException("liveVisionAcceptance 需要显式 SKILLFORGE_LIVE_VISION_ACCEPTANCE=1")
        val mode = System.getenv("LIVE_VISION_MODE")
        if (mode != "explicit" && mode != "published") throw GradleException("LIVE_VISION_MODE 必须为 explicit 或 published")
        val required = if (mode == "explicit") listOf("LIVE_VISION_SOURCE_TENANT_ID", "LIVE_VISION_ENDPOINT",
            "LIVE_VISION_API_KEY", "LIVE_VISION_EVIDENCE_DIR") else listOf("LIVE_VISION_SOURCE_TENANT_ID", "LIVE_VISION_SOURCE_MODEL_ID",
            "LIVE_VISION_SOURCE_MODEL_REVISION", "LIVE_VISION_EVIDENCE_DIR")
        required.forEach { name ->
            if (System.getenv(name).isNullOrBlank()) throw GradleException("liveVisionAcceptance 缺少环境变量 $name")
        }
    }
}

// 当前聊天容量验收只选择两个隔离场景；来源库只读，所有真实发送受测试预算端口门禁。
val liveChatCapacitySourceSet = sourceSets.create("liveChatCapacityAcceptance") {
    val ordinaryTests = sourceSets.named("test").get()
    compileClasspath += ordinaryTests.output + ordinaryTests.compileClasspath
    runtimeClasspath += output + ordinaryTests.output + ordinaryTests.runtimeClasspath
}
configurations.named(liveChatCapacitySourceSet.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}
configurations.named(liveChatCapacitySourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
}
tasks.register<Test>("liveChatCapacityAcceptance") {
    description = "最多两场景、十六次真实模型调用和十六万 Tokens 的聊天网站与协作验收"
    group = "verification"
    testClassesDirs = liveChatCapacitySourceSet.output.classesDirs
    classpath = liveChatCapacitySourceSet.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*ChatCapacityLiveAcceptance") }
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
    doFirst {
        if (System.getenv("SKILLFORGE_LIVE_CHAT_CAPACITY_ACCEPTANCE") != "1") {
            throw GradleException("liveChatCapacityAcceptance 需要显式 SKILLFORGE_LIVE_CHAT_CAPACITY_ACCEPTANCE=1")
        }
        listOf("LIVE_CAPACITY_SOURCE_TENANT_ID", "LIVE_CAPACITY_SOURCE_MODEL_ID",
            "LIVE_CAPACITY_SOURCE_MODEL_REVISION", "LIVE_CAPACITY_RUNTIME_REVISION",
            "LIVE_CAPACITY_EVIDENCE_FILE", "SANDBOX_ENDPOINT", "SANDBOX_SERVICE_TOKEN").forEach { name ->
            if (System.getenv(name).isNullOrBlank()) throw GradleException("liveChatCapacityAcceptance 缺少环境变量 $name")
        }
        maxParallelForks = 1
    }
}

// 新配置契约的真实供应商验收：只读已发布模型连接，使用独立 PG/Redis 和代码默认策略。
tasks.register<Test>("liveOperationalConfigAcceptance") {
    description = "验收超过旧 32k 窗口的真实多步骤任务与不可变配置冻结"
    group = "verification"
    testClassesDirs = liveChatCapacitySourceSet.output.classesDirs
    classpath = liveChatCapacitySourceSet.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*OperationalConfigLiveAcceptance") }
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
    doFirst {
        if (System.getenv("SKILLFORGE_LIVE_OPERATIONAL_CONFIG_ACCEPTANCE") != "1")
            throw GradleException("真实供应商验收需要 SKILLFORGE_LIVE_OPERATIONAL_CONFIG_ACCEPTANCE=1")
        listOf("LIVE_CONFIG_SOURCE_MODEL_ID", "LIVE_CONFIG_SOURCE_MODEL_REVISION", "LIVE_CONFIG_EVIDENCE_FILE").forEach { name ->
            if (System.getenv(name).isNullOrBlank()) throw GradleException("真实供应商验收缺少 $name")
        }
        maxParallelForks = 1
    }
}

// 真实轨迹验收单独选择；只读来源连接与临时服务资源保持分离。
tasks.register<Test>("liveTrajectoryAcceptance") {
    description = "隔离新库验收真实计算与委派轨迹、双端管理HTTP和可选只读浏览器，最多二十四次真实发送"
    group = "verification"
    dependsOn(":skillforge-platform-service:bootJar")
    testClassesDirs = liveChatCapacitySourceSet.output.classesDirs
    classpath = liveChatCapacitySourceSet.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*TrajectoryLiveAcceptance") }
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
    maxParallelForks = 1
    // 机构测试JVM与平台子进程共享临时库，必须在正式生成器首次装配前分配不同节点。
    environment("SNOWFLAKE_WORKER_ID", "1")
    doFirst {
        if (System.getenv("SKILLFORGE_LIVE_TRAJECTORY_ACCEPTANCE") != "1")
            throw GradleException("真实轨迹验收需要 SKILLFORGE_LIVE_TRAJECTORY_ACCEPTANCE=1")
        listOf("LIVE_TRAJECTORY_SOURCE_MODEL_ID", "LIVE_TRAJECTORY_SOURCE_MODEL_REVISION", "LIVE_TRAJECTORY_EVIDENCE_FILE",
            "LIVE_TRAJECTORY_FIXTURE_PASSWORD", "SECRET_KEY", "SPRING_DATASOURCE_URL", "PLATFORM_DATASOURCE_USERNAME",
            "PLATFORM_DATASOURCE_PASSWORD").forEach { name ->
            if (System.getenv(name).isNullOrBlank()) throw GradleException("真实轨迹验收缺少 $name")
        }
        if (System.getenv("LIVE_TRAJECTORY_READY_FILE").isNullOrBlank() != System.getenv("LIVE_TRAJECTORY_BROWSER_FINISH_FILE").isNullOrBlank())
            throw GradleException("真实轨迹浏览器握手需要同时提供 READY_FILE 和 BROWSER_FINISH_FILE")
    }
}

// 实时轨迹夹具的发送门禁先可独立离线核验。
tasks.register<Test>("trajectoryLiveHarnessTest") {
    description = "离线核验真实轨迹验收的发送上限、UNKNOWN停止和冻结来源门禁，不启服务或模型"
    group = "verification"
    testClassesDirs = liveChatCapacitySourceSet.output.classesDirs
    classpath = liveChatCapacitySourceSet.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*TrajectoryLiveAuditTest") }
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
    setFinalizedBy(emptyList<Any>())
    maxParallelForks = 1
    environment("SNOWFLAKE_WORKER_ID", "1")
}

// Session 缓存验收单独选择，普通测试或其它 live 任务不得隐式发送此样本。
tasks.register<Test>("liveSessionPromptCacheAcceptance") {
    description = "在独立新库验收八 Run Session 前缀，最多十八次请求、四十万 Tokens 和十分钟"
    group = "verification"
    testClassesDirs = liveChatCapacitySourceSet.output.classesDirs
    classpath = liveChatCapacitySourceSet.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*SessionPromptCacheLiveAcceptance") }
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
    maxParallelForks = 1
    doFirst {
        if (System.getenv("SKILLFORGE_LIVE_SESSION_PROMPT_CACHE_ACCEPTANCE") != "1")
            throw GradleException("Session 真实缓存验收需要 SKILLFORGE_LIVE_SESSION_PROMPT_CACHE_ACCEPTANCE=1")
        listOf("LIVE_PROMPT_CACHE_SOURCE_MODEL_ID", "LIVE_PROMPT_CACHE_SOURCE_MODEL_REVISION",
            "LIVE_PROMPT_CACHE_EVIDENCE_FILE").forEach { name ->
            if (System.getenv(name).isNullOrBlank()) throw GradleException("Session 真实缓存验收缺少 $name")
        }
    }
}

// 透明录制器仅使用内存 HTTP 协议样本，独立离线任务无需真实供应商环境或调用授权。
tasks.register<Test>("sessionPromptCacheRecorderTest") {
    description = "离线核对缓存录制器的透明转发、原始计量和前缀摘要"
    group = "verification"
    testClassesDirs = liveChatCapacitySourceSet.output.classesDirs
    classpath = liveChatCapacitySourceSet.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*PromptCacheRecordingHttpClientTest") }
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
    setFinalizedBy(emptyList<Any>())
    maxParallelForks = 1
}

// 记忆评测独立于普通测试；同一源集包含真实供应商与无收费的浏览器/预算验收入口。
val liveMemorySourceSet = sourceSets.create("liveMemoryAcceptance") {
    compileClasspath += sourceSets.main.get().output + configurations.testCompileClasspath.get()
    runtimeClasspath += output + sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
}
configurations.named(liveMemorySourceSet.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}
configurations.named(liveMemorySourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
}
tasks.named<JavaCompile>(liveMemorySourceSet.compileJavaTaskName) {
    // 只复用集成基类及实际派发审计/发送计量，不拉起整个普通测试源集的编译。
    source(file("src/test/java/com/skillforge/institution/integration/AbstractIntegrationTest.java"))
    source(file("src/test/java/com/skillforge/institution/integration/LiveDispatchedModelAudit.java"))
    source(file("src/test/java/com/skillforge/institution/integration/LiveGradleAttemptMeter.java"))
}

tasks.register<Test>("liveMemoryAcceptance") {
    description = "在独立数据库评测24类记忆场景各3次，累计最多100万Tokens与2小时"
    group = "verification"
    testClassesDirs = liveMemorySourceSet.output.classesDirs
    classpath = liveMemorySourceSet.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*MemoryLiveAcceptance") }
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
    maxParallelForks = 1
    doFirst {
        if (System.getenv("SKILLFORGE_LIVE_MEMORY_ACCEPTANCE") != "1")
            throw GradleException("真实记忆评测需要 SKILLFORGE_LIVE_MEMORY_ACCEPTANCE=1")
        listOf("LIVE_MEMORY_SOURCE_MODEL_ID", "LIVE_MEMORY_SOURCE_MODEL_REVISION",
            "LIVE_MEMORY_SUITE_FILE", "LIVE_MEMORY_EVIDENCE_DIR").forEach { name ->
            if (System.getenv(name).isNullOrBlank()) throw GradleException("真实记忆评测缺少 $name")
        }
    }
}

tasks.register<Test>("memoryEvaluationOfflineTest") {
    description = "离线验证记忆评测累计预算、未知用量与场景输入，不调用真实供应商"
    group = "verification"
    testClassesDirs = liveMemorySourceSet.output.classesDirs
    classpath = liveMemorySourceSet.runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("*MemoryEvaluation*Test")
        includeTestsMatching("*SemanticMemoryExtractionEvaluationTest")
    }
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
    setFinalizedBy(emptyList<Any>())
    maxParallelForks = 1
}

// A 的当前生产规则基线单独选择，默认离线、live 与浏览器任务均不触发。
tasks.register<Test>("semanticDecisionBaseline") {
    description = "冻结当前候选过滤和交付解析的 dev/heldout 基线，不调用模型"
    group = "verification"
    testClassesDirs = liveMemorySourceSet.output.classesDirs
    classpath = liveMemorySourceSet.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*SemanticDecisionBaseline") }
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
    maxParallelForks = 1
    doFirst {
        if (System.getenv("SEMANTIC_A_BASELINE_DIR").isNullOrBlank())
            throw GradleException("语义规则基线需要新 SEMANTIC_A_BASELINE_DIR")
    }
}

// 显式供应商采集入口：人工语料、有界批次，凭据只取进程环境，不进入默认测试。
tasks.register<Test>("memoryEmbeddingProviderCapture") {
    description = "使用批准的供应商采集 B 人工检索语料向量，不部署业务服务"
    group = "verification"
    testClassesDirs = liveMemorySourceSet.output.classesDirs
    classpath = liveMemorySourceSet.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*SemanticEmbeddingProviderCapture") }
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
    maxParallelForks = 1
    doFirst {
        listOf("SEMANTIC_B_EMBEDDING_API_KEY", "SEMANTIC_B_EMBEDDING_INPUT", "SEMANTIC_B_EMBEDDING_OUTPUT").forEach {
            if (System.getenv(it).isNullOrBlank()) throw GradleException("供应商采集缺少显式输入: $it")
        }
    }
}

// C 的真实调用显式隔离：仅使用批准的模型和人工语料，不进入普通/离线/浏览器任务。
tasks.register<Test>("semanticMemoryExtractionLiveAcceptance") {
    description = "在隔离PG通过正式系统Agent采集C人工记忆候选，最多32次模型调用"
    group = "verification"
    testClassesDirs = liveMemorySourceSet.output.classesDirs
    classpath = liveMemorySourceSet.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*SemanticMemoryExtractionLiveAcceptance") }
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
    maxParallelForks = 1
    doFirst {
        if (System.getenv("SKILLFORGE_SEMANTIC_C_LIVE_ACCEPTANCE") != "1")
            throw GradleException("真实C评估需要 SKILLFORGE_SEMANTIC_C_LIVE_ACCEPTANCE=1")
        listOf("SEMANTIC_C_CHAT_API_KEY", "SEMANTIC_C_CHAT_MODEL_ID", "SEMANTIC_C_SUITE_ROOT",
            "SEMANTIC_C_EVIDENCE_DIR").forEach { name ->
            if (System.getenv(name).isNullOrBlank()) throw GradleException("真实C评估缺少 $name")
        }
    }
}


tasks.register<JavaExec>("semanticMemoryExtractionReplay") {
    description = "只读C既有32例模型输出并用当前正式parser离线重评，不调用模型或数据库"
    group = "verification"
    dependsOn(liveMemorySourceSet.classesTaskName)
    classpath = liveMemorySourceSet.runtimeClasspath
    mainClass.set("com.skillforge.institution.integration.SemanticMemoryExtractionReplay")
    doFirst {
        val source = System.getenv("SEMANTIC_C_REPLAY_SOURCE")
        val output = System.getenv("SEMANTIC_C_REPLAY_OUTPUT")
        if (source.isNullOrBlank() || output.isNullOrBlank())
            throw GradleException("C离线重评需要 SEMANTIC_C_REPLAY_SOURCE 和新的 SEMANTIC_C_REPLAY_OUTPUT")
        setArgs(listOf(source, output))
    }
}

tasks.register<Test>("memoryGovernanceBrowserAcceptance") {
    description = "用真实机构认证和浏览器验证记忆治理、筛选与控件提示，无模型费用"
    group = "verification"
    testClassesDirs = liveMemorySourceSet.output.classesDirs
    classpath = liveMemorySourceSet.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*MemoryGovernanceBrowserAcceptance") }
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
    maxParallelForks = 1
    doFirst {
        if (System.getenv("LIVE_MEMORY_EVIDENCE_DIR").isNullOrBlank())
            throw GradleException("记忆浏览器验收缺少 LIVE_MEMORY_EVIDENCE_DIR")
    }
}

// 性能验收保留真实 HTTP/数据库/Redis 链路，使用与服务一致的无覆盖率插桩运行方式。
tasks.register<Test>("runtimeLatencyE2E") {
    description = "验证 Runtime 真实业务链路和固定负载时延"
    group = "verification"
    val testSourceSet = sourceSets.named("test")
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform { includeTags("runtime-latency") }
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
    setFinalizedBy(emptyList<Any>())
    outputs.upToDateWhen { false }
    // 与普通test复用同一正式运行类路径，供真实OS子JVM恢复验收使用。
    val recoveryClasspathFile = layout.buildDirectory.file("tmp/jvm-recovery/runtime-classpath.txt")
    systemProperty("skillforge.test.runtime-classpath-file", recoveryClasspathFile.get().asFile.absolutePath)
    // Java 21 下显式加载测试依赖中的 Mockito agent，避免运行时外部进程自挂载失败。
    doFirst {
        val destination = recoveryClasspathFile.get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(sourceSets.named("test").get().runtimeClasspath.asPath, Charsets.UTF_8)
        jvmArgs("-javaagent:${classpath.single { it.name.startsWith("mockito-core-") && it.extension == "jar" }.absolutePath}")
    }
}

tasks.named("jacocoTestReport") {
    // 根约定会依赖每个 Test 任务；普通 test 的覆盖率收尾不能拉起受保护 staging 任务。
    setDependsOn(listOf(tasks.named("test")))
}

/**
 * 仅运行显式的受保护环境 Smoke。普通 `test` 不选择 StagingRuntimeE2E，
 * 也不会读取 staging 凭据或端点。
 */
tasks.register<Test>("stagingRuntimeE2E") {
    description = "运行显式的真实 Provider 机构 staging Smoke"
    group = "verification"
    val testSourceSet = sourceSets.named("test")
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    // 根约定会为每个 Test 任务添加 JaCoCo 收尾；受保护 Smoke 缺少环境变量时必须立即失败，
    // 不能被普通测试或覆盖率任务间接拉起。
    setFinalizedBy(emptyList<Any>())
    filter {
        includeTestsMatching("com.skillforge.institution.integration.StagingRuntimeE2E")
    }
    doFirst {
        listOf(
            "STAGING_RUNTIME_BASE_URL",
            "STAGING_PROVIDER_PROFILES_FILE",
            "STAGING_DB_URL",
            "STAGING_DB_USERNAME",
            "STAGING_DB_PASSWORD"
        ).forEach { name ->
            if (System.getenv(name).isNullOrBlank()) {
                throw GradleException("stagingRuntimeE2E 缺少环境变量 $name")
            }
        }
    }
}


tasks.register<Test>("agentSkillRemoveLossBrowserE2E") {
    inputs.property("skillRemoveLoss", providers.environmentVariable("E2E_SKILL_REMOVE_LOSS").orElse("baseline"))
    description = "Verify skill removal response loss and publication runtime boundary"
    group = "verification"
    val ordinaryTests = sourceSets.named("test").get()
    testClassesDirs = ordinaryTests.output.classesDirs
    classpath = ordinaryTests.runtimeClasspath
    setFinalizedBy(emptyList<Any>())
    filter { includeTestsMatching("com.skillforge.institution.integration.AgentSkillRemoveLossBrowserE2ETest.removedSkillLostResponsePreservesPublishedRuntimeUntilPublication") }
    outputs.upToDateWhen { false }
}
