import org.gradle.api.artifacts.ProjectDependency
import java.util.zip.ZipFile

// 领域之间按真实能力单向组合；shared 只提供技术支撑，不重新承载业务出口。
val platformDomains = mapOf(
    "shared" to emptySet<String>(),
    "foundation" to setOf("shared"),
    "billing" to setOf("shared", "foundation"),
    "model" to setOf("shared", "foundation", "billing"),
    "capability" to setOf("shared", "foundation", "billing"),
    "knowledge" to setOf("shared", "foundation", "billing", "model", "capability"),
    "workflow" to setOf("shared", "foundation", "capability"),
    "agent" to setOf("shared", "foundation", "billing", "model", "capability", "knowledge", "workflow"),
    "channel" to setOf("shared", "foundation", "capability", "agent"),
    "chat" to setOf("shared", "foundation", "billing", "model", "capability", "agent")
)

val platformModuleBoundaries = tasks.register("verifyPlatformModuleBoundaries") {
    group = "verification"
    description = "校验平台领域依赖方向、技术共享层边界及 SDK 对平台的独立性"
    doLast {
        val persistenceReference = Regex("com\\.skillforge\\.core\\.([a-z]+)\\.(?:[\\w.]*\\.[A-Za-z0-9_]+DO\\b|(?:persistence|mapper)\\.)")
        val platformReference = Regex("com\\.skillforge\\.(?:core|common|prompts|institution|platform|systemagent)\\.")
        val domainWildcardImport = Regex("import\\s+(?:static\\s+)?com\\.skillforge\\.core\\.([a-z]+)\\.[\\w.]*\\*\\s*;")
        // 固定合法合同与禁止访问样例，防止规则重构时误放持久化类型或 SDK 宿主依赖。
        check(!persistenceReference.containsMatchIn("com.skillforge.core.billing.BillingTraceQueryService"))
        check(persistenceReference.find("com.skillforge.core.foundation.persistence.TenantMapper")?.groupValues?.get(1) == "foundation")
        check(persistenceReference.find("com.skillforge.core.billing.metering.CostEventDO")?.groupValues?.get(1) == "billing")
        check(!platformReference.containsMatchIn("com.skillforge.runtime.api.SessionRef"))
        check(platformReference.containsMatchIn("com.skillforge.common.context.TenantContext"))
        check(platformReference.containsMatchIn("com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures"))
        check(domainWildcardImport.find("import com.skillforge.core.agent.model.*;")?.groupValues?.get(1) == "agent")
        check(domainWildcardImport.find("import static com.skillforge.core.agent.model.AgentDO.*;")?.groupValues?.get(1) == "agent")
        check(!domainWildcardImport.containsMatchIn("import com.skillforge.core.agent.management.AgentManagementContracts;"))
        val graph = mutableMapOf<String, Set<String>>()
        platformDomains.forEach { (domain, allowed) ->
            val module = project(":skillforge-core-$domain")
            val dependencies = module.configurations
                .filter { it.name in setOf("api", "implementation", "compileOnly", "runtimeOnly") }
                .flatMap { it.allDependencies.filterIsInstance<ProjectDependency>() }
                .map { it.dependencyProject.name }.toSet()
            val actualDomains = dependencies.filter { it.startsWith("skillforge-core-") }
                .map { it.removePrefix("skillforge-core-") }.toSet()
            check(actualDomains.all { it in allowed }) {
                "${module.path} has forbidden domain dependencies: ${actualDomains - allowed}"
            }
            check(dependencies.none { it.endsWith("-service") || it == "skillforge-system-agent-runtime" }) {
                "${module.path} must not depend on a host application"
            }
            if (domain == "shared") check(dependencies == setOf("skillforge-common")) {
                "Technical shared project dependencies changed: $dependencies"
            }
            graph[domain] = actualDomains
            val sourceReference = Regex("com\\.skillforge\\.core\\.([a-z]+)\\.")
            module.fileTree("src/main") { include("**/*.java", "**/*.xml") }.files.forEach { source ->
                val foreignDomains = sourceReference.findAll(source.readText()).map { it.groupValues[1] }
                    .filter { it != domain }.toSet()
                check(foreignDomains.all { it in allowed }) {
                    "${source.relativeTo(rootDir)} references forbidden domains: ${foreignDomains - allowed}"
                }
                // 跨域合同必须显式导入，避免通配符隐藏 DO 或内部持久化类型。
                val wildcardDomains = domainWildcardImport.findAll(source.readText())
                    .map { it.groupValues[1] }.filter { it != domain }.toSet()
                check(wildcardDomains.isEmpty()) {
                    "${source.relativeTo(rootDir)} hides cross-domain types behind wildcard imports: $wildcardDomains"
                }
                val persistenceReferences = persistenceReference
                    .findAll(source.readText()).map { it.groupValues[1] }.filter { it != domain }.toSet()
                check(persistenceReferences.isEmpty()) {
                    "${source.relativeTo(rootDir)} crosses domain persistence boundaries: $persistenceReferences"
                }
            }
        }
        fun visit(node: String, path: List<String>) {
            check(node !in path) { "Platform module dependency cycle: ${(path + node).joinToString(" -> ")}" }
            graph[node].orEmpty().forEach { visit(it, path + node) }
        }
        graph.keys.forEach { visit(it, emptyList()) }

        // 同时禁止 SDK 测试借用平台 schema 或 fixtures，不能只检查正式 JAR。
        rootProject.subprojects.filter { it.name.startsWith("agent-runtime-") }.forEach { sdk ->
            sdk.configurations.forEach { configuration ->
                val platform = configuration.allDependencies.filterIsInstance<ProjectDependency>()
                    .map { it.dependencyProject.name }.filter { !it.startsWith("agent-runtime-") }
                check(platform.isEmpty()) { "${sdk.path}:${configuration.name} depends on platform $platform" }
            }
            sdk.fileTree("src") { include("**/*.java", "**/*.xml") }.files.forEach { source ->
                check(!platformReference.containsMatchIn(source.readText())) {
                    "SDK source references a platform implementation: ${source.relativeTo(rootDir)}"
                }
            }
        }
    }
}

val hostTestSourceIsolation = tasks.register("verifyHostTestSourceIsolation") {
    group = "verification"
    val jar = project(":skillforge-core-shared").tasks.named<org.gradle.api.tasks.bundling.Jar>("jar")
    dependsOn(jar)
    doLast {
        ZipFile(jar.get().archiveFile.get().asFile).use { archive ->
            val entries = archive.entries().asSequence().map { it.name }.toList()
            check(entries.none { it.endsWith("/MockConnector.class") || it.endsWith("/ResilienceFixtures.class") }) {
                "Host test fixtures leaked into the shared production artifact"
            }
        }
    }
}
val databaseNamingRules = tasks.register<Exec>("testDatabaseNamingRules") {
    group = "verification"
    description = "验证数据库命名检查器的合法、违规与 SQL 语义边界样例"
    workingDir(rootDir.parentFile)
    commandLine("python", "-X", "utf8", "-B", "-m", "unittest", "discover", "-s", "tools/database", "-p", "test_schema_names.py")
}
val databaseNaming = tasks.register<Exec>("verifyDatabaseNames") {
    group = "verification"
    description = "校验数据库目标名称清单与当前 SQL 消费者的一致性"
    dependsOn(databaseNamingRules)
    workingDir(rootDir.parentFile)
    commandLine("python", "-X", "utf8", "-B", "tools/database/schema_names.py", "--check")
}

tasks.named("check") { dependsOn(platformModuleBoundaries, hostTestSourceIsolation, databaseNaming) }
