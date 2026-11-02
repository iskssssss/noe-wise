// 数据访问框架边界只扫描生产源码；夹具可独立使用 JDBC 构造和核验数据库事实。
val verifyMybatisAccess = tasks.register("verifyMybatisAccess") {
    group = "verification"
    description = "Verify business SQL uses MyBatis mappers and XML statements"
    val sources = fileTree(rootDir) { include("*/src/main/java/**/*.java") }
    inputs.files(sources)
    doLast {
        // 精确到文件的技术职责，不允许新增业务 SQL 执行器借用整包豁免。
        val jdbcBoundaries = mapOf(
            "skillforge-core-foundation/src/main/java/com/skillforge/core/foundation/config/TenantRlsInterceptor.java" to "当前事务连接的 RLS 上下文绑定",
            "agent-runtime-spring-boot-starter/src/main/java/com/skillforge/runtime/starter/AgentRuntimeJdbcObservation.java" to "连接与语句的无侵入耗时观测",
            "skillforge-core-knowledge/src/main/java/com/skillforge/core/knowledge/integration/runtime/knowledge/KnowledgeQueryCancellation.java" to "取消当前 MyBatis 查询持有的 Statement",
        )
        val typeHandlers = setOf(
            "agent-runtime-adapter-sqlite/src/main/java/com/skillforge/runtime/adapter/sqlite/SqliteInstantTypeHandler.java",
            "agent-runtime-adapter-jdbc/src/main/java/com/skillforge/runtime/adapter/jdbc/callback/CallbackInstantTypeHandler.java",
            "skillforge-core-agent/src/main/java/com/skillforge/core/agent/model/AgentDelegationConfigJsonbTypeHandler.java",
            "skillforge-core-agent/src/main/java/com/skillforge/core/agent/model/AgentTemplateDefinitionJsonbTypeHandler.java",
            "skillforge-core-knowledge/src/main/java/com/skillforge/core/knowledge/typehandler/VectorTypeHandler.java",
            "skillforge-core-foundation/src/main/java/com/skillforge/core/foundation/persistence/ControlOperationItemsTypeHandler.java",
            "skillforge-core-shared/src/main/java/com/skillforge/core/shared/config/JsonbTypeHandler.java",
            "skillforge-core-chat/src/main/java/com/skillforge/core/chat/persistence/ProfileSummariesTypeHandler.java",
            "skillforge-core-chat/src/main/java/com/skillforge/core/chat/persistence/ChatReferencesTypeHandler.java",
            "skillforge-core-foundation/src/main/java/com/skillforge/core/foundation/integration/ExecutionScopesTypeHandler.java",
        )
        val sqlAnnotations = Regex("@(?:org\\.apache\\.ibatis\\.annotations\\.)?(?:Select|Insert|Update|Delete)(?:Provider)?\\s*\\(")
        val springJdbc = Regex("(?:org\\.springframework\\.jdbc\\.core|\\b(?:JdbcTemplate|NamedParameterJdbcTemplate|JdbcClient)\\b)")
        val rawExecution = Regex("\\b(?:DriverManager|DefaultParameterHandler)\\b|\\.\\s*(?:prepareStatement|createStatement|prepareCall|executeQuery|executeUpdate|executeBatch)\\s*\\(")
        val rawStatementTypes = Regex("\\bjava\\.sql\\.(?:Statement|PreparedStatement|CallableStatement|ResultSet)\\b|\\bimport\\s+java\\.sql\\.\\*")
        val violations = mutableListOf<String>()
        // 固定正负探针防止规则退化为总是通过或误禁合法类型处理。
        check(springJdbc.containsMatchIn("new JdbcTemplate(source)"))
        check(rawExecution.containsMatchIn("connection.prepareStatement(sql)"))
        check(rawExecution.containsMatchIn("statement.executeQuery()"))
        check(sqlAnnotations.containsMatchIn("@SelectProvider(type = Query.class)"))
        check(!rawExecution.containsMatchIn("statement.setString(1, value)"))
        check(!rawExecution.containsMatchIn("mapper.selectById(id)"))
        check(rawStatementTypes.containsMatchIn("import java.sql.PreparedStatement;"))
        sources.files.sortedBy { it.path }.forEach { source ->
            val path = source.relativeTo(rootDir).invariantSeparatorsPath
            val text = source.readText().replace(Regex("(?s)/\\*.*?\\*/|(?m)//[^\\r\\n]*")) { match ->
                match.value.map { if (it == '\n') '\n' else ' ' }.joinToString("")
            }
            listOf("SQL 注解" to sqlAnnotations, "Spring JDBC 业务入口" to springJdbc).forEach { (rule, regex) ->
                regex.findAll(text).forEach { match ->
                    violations += "$path:${text.take(match.range.first).count { it == '\n' } + 1}: $rule"
                }
            }
            if (path !in jdbcBoundaries) {
                rawExecution.findAll(text).forEach { match ->
                    violations += "$path:${text.take(match.range.first).count { it == '\n' } + 1}: 原生 JDBC 执行"
                }
                if (path !in typeHandlers && rawStatementTypes.containsMatchIn(text)) {
                    violations += "$path: 业务代码直接依赖 JDBC Statement/ResultSet"
                }
            }
        }
        check(violations.isEmpty()) { "数据访问未统一到 MyBatis Mapper:\n${violations.joinToString("\n")}" }
        logger.lifecycle("MyBatis access verified: {} production files, {} infrastructure boundaries", sources.files.size, jdbcBoundaries.size)
    }
}

tasks.named("check") { dependsOn(verifyMybatisAccess) }
