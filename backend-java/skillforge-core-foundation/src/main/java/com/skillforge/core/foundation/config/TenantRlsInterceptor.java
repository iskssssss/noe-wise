package com.skillforge.core.foundation.config;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.access.AuthorizedSqlRule;
import com.skillforge.core.foundation.access.FoundationAuthorizedSqlRule;
import com.skillforge.core.foundation.access.PlatformObservationAccess;
import java.util.List;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.stereotype.Component;
import org.mybatis.spring.transaction.SpringManagedTransaction;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 租户 RLS 绑定拦截器。
 *
 * <p>Spring 实际事务内每个连接首次使用时绑定租户，后续 Mapper 核验同一身份；
 * 独立自动提交语句仍在执行前绑定、返回前清空，不把连接身份留给下一次借用。
 */
@Component
@Intercepts({
    @Signature(
            type = Executor.class,
            method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(
            type = Executor.class,
            method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                    CacheKey.class, BoundSql.class}),
    // MyBatis-Plus 的 selectOne 使用游标查询，同样必须在 SQL 执行前绑定租户。
    @Signature(
            type = Executor.class,
            method = "queryCursor",
            args = {MappedStatement.class, Object.class, RowBounds.class}),
    @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
})
public class TenantRlsInterceptor implements Interceptor {
    private static final Object TRANSACTION_BINDING = new Object();
    private static final ThreadLocal<TransactionBinding> ACTIVE_WINDOW = new ThreadLocal<>();
    private static final Pattern SET_CONFIG = Pattern.compile("\\bset_config\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern MUTATION = Pattern.compile("\\b(insert|update|delete|merge|call|do|create|alter|drop|truncate|copy|grant|revoke)\\b",Pattern.CASE_INSENSITIVE);
    private final List<AuthorizedSqlRule> sqlRules;

    /** 普通租户绑定无需领域许可；独立装配仅启用基础领域自身规则。 */
    public TenantRlsInterceptor() { this(List.of(new FoundationAuthorizedSqlRule())); }

    @org.springframework.beans.factory.annotation.Autowired
    public TenantRlsInterceptor(List<AuthorizedSqlRule> sqlRules) {
        this.sqlRules = List.copyOf(sqlRules);
    }

    private static void requireAllowed(List<AuthorizedSqlRule> rules, AuthorizedSqlRule.Access access, String error) {
        if (rules.stream().noneMatch(rule -> rule.allows(access))) throw new IllegalStateException(error);
    }

    /** 全局计费扫描在原事务内处理一个已选机构；仅放行计费领域声明的配额读取和状态CAS。 */
    public static <T> T withBillingScanTarget(long tenantId, Supplier<T> work) {
        if (tenantId < 1 || TenantContext.getTenantId() != null
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("billing_scan_requires_global_write_transaction");
        var transaction = transactionBinding();
        if (ACTIVE_WINDOW.get() != null || transaction.platformRead != null || transaction.targetWrite != null
                || transaction.headquartersUsageRead != null || transaction.observationRead != null
                || transaction.establishedTenant != null && !transaction.establishedTenant.isEmpty())
            throw new IllegalStateException("billing_scan_cannot_replace_tenant_transaction");
        String target = Long.toString(tenantId);
        var scope = new TargetWrite("", target, false, AuthorizedSqlRule.Window.BILLING_SCAN);
        try { for (var connection : transaction.connections.keySet()) scope.capture(connection); }
        catch (java.sql.SQLException failure) { throw new IllegalStateException("billing_scan_context_unavailable", failure); }
        transaction.targetWrite = scope;
        ACTIVE_WINDOW.set(transaction);
        Throwable failure = null;
        try { TenantContext.setTenantId(target); return work.get(); }
        catch (RuntimeException | Error rejected) { failure = rejected; throw rejected; }
        finally {
            TenantContext.clear();
            Throwable cleanupFailure = null;
            try { scope.restore(); } catch (RuntimeException | Error rejected) { cleanupFailure = rejected; }
            transaction.targetWrite = null;
            ACTIVE_WINDOW.remove();
            for (var executor : transaction.executors.keySet()) {
                try { executor.clearLocalCache(); }
                catch (RuntimeException | Error rejected) {
                    if (cleanupFailure == null) cleanupFailure = rejected; else cleanupFailure.addSuppressed(rejected);
                }
            }
            transaction.connections.clear(); transaction.executors.clear();
            if (cleanupFailure != null) {
                if (failure != null) failure.addSuppressed(cleanupFailure);
                else if (cleanupFailure instanceof RuntimeException rejected) throw rejected;
                else throw (Error) cleanupFailure;
            }
        }
    }

    /** 当前平台观测只为一个明确目标执行领域允许的纯读取；线程仍保留平台身份。 */
    public static <T> T withPlatformObservationTarget(long tenantId, Supplier<T> work) {
        var authorization = PlatformObservationAccess.currentAuthorization();
        if (authorization == null || !authorization.tenantIds().equals(Set.of(tenantId))
                || TenantContext.getTenantId() != null
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("platform_observation_scope_not_authorized");
        var transaction = transactionBinding();
        if (ACTIVE_WINDOW.get() != null || transaction.platformRead != null || transaction.targetWrite != null
                || transaction.headquartersUsageRead != null || transaction.observationRead != null
                || transaction.establishedTenant != null && !transaction.establishedTenant.isEmpty())
            throw new IllegalStateException("platform_observation_cannot_replace_tenant_transaction");
        var scope = new ObservationRead(Long.toString(tenantId));
        try { for (var connection : transaction.connections.keySet()) scope.capture(connection); }
        catch (java.sql.SQLException failure) { throw new IllegalStateException("platform_observation_context_unavailable", failure); }
        transaction.observationRead = scope;
        ACTIVE_WINDOW.set(transaction);
        Throwable failure = null;
        try { return work.get(); }
        catch (RuntimeException | Error rejected) { failure = rejected; throw rejected; }
        finally {
            TenantContext.clear();
            Throwable cleanupFailure = null;
            try { scope.restore(); } catch (RuntimeException | Error rejected) { cleanupFailure = rejected; }
            transaction.observationRead = null;
            ACTIVE_WINDOW.remove();
            for (var executor : transaction.executors.keySet()) {
                try { executor.clearLocalCache(); }
                catch (RuntimeException | Error rejected) {
                    if (cleanupFailure == null) cleanupFailure = rejected; else cleanupFailure.addSuppressed(rejected);
                }
            }
            transaction.connections.clear(); transaction.executors.clear();
            if (cleanupFailure != null) {
                if (failure != null) failure.addSuppressed(cleanupFailure);
                else if (cleanupFailure instanceof RuntimeException rejected) throw rejected;
                else throw (Error) cleanupFailure;
            }
        }
    }

    /** 总部仅在本次已授权目标集合读取用量；保留真实Tenant，不开启平台或其他业务表访问。 */
    public static <T> T withHeadquartersUsageTargets(String actorTenant, String action, Set<Long> tenantIds, Supplier<T> work) {
        var authorization = com.skillforge.core.foundation.institution.usage.HeadquartersUsageAccess.currentAuthorization();
        if (authorization == null || !actorTenant.equals(authorization.actorTenantId())
                || !action.equals(authorization.action()) || !tenantIds.equals(authorization.tenantIds())
                || !actorTenant.equals(TenantContext.getTenantId())
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("headquarters_usage_scope_not_authorized");
        var transaction = transactionBinding();
        if (ACTIVE_WINDOW.get() != null || transaction.platformRead != null || transaction.targetWrite != null
                || transaction.headquartersUsageRead != null
                || (transaction.establishedTenant != null && !actorTenant.equals(transaction.establishedTenant)))
            throw new IllegalStateException("headquarters_usage_nested_scope_forbidden");
        var scope = new HeadquartersUsageRead(actorTenant, action, tenantIds);
        try { for (var connection : transaction.connections.keySet()) scope.capture(connection); }
        catch (java.sql.SQLException failure) { throw new IllegalStateException("headquarters_usage_context_unavailable", failure); }
        transaction.headquartersUsageRead = scope;
        ACTIVE_WINDOW.set(transaction);
        Throwable failure = null;
        try { return work.get(); }
        catch (RuntimeException | Error rejected) { failure = rejected; throw rejected; }
        finally {
            TenantContext.setTenantId(actorTenant);
            Throwable cleanupFailure = null;
            try { scope.restore(); } catch (RuntimeException | Error rejected) { cleanupFailure = rejected; }
            transaction.headquartersUsageRead = null;
            ACTIVE_WINDOW.remove();
            for (var executor : transaction.executors.keySet()) {
                try { executor.clearLocalCache(); }
                catch (RuntimeException | Error rejected) {
                    if (cleanupFailure == null) cleanupFailure = rejected; else cleanupFailure.addSuppressed(rejected);
                }
            }
            transaction.connections.clear(); transaction.executors.clear();
            if (cleanupFailure != null) {
                if (failure != null) failure.addSuppressed(cleanupFailure);
                else if (cleanupFailure instanceof RuntimeException rejected) throw rejected;
                else throw (Error) cleanupFailure;
            }
        }
    }

    /** 总部已获得独立目标部署许可后，仅在该事务的一个目标范围组合部署所需存储。 */
    public static <T>T withInstitutionDeploymentTarget(String actorTenant,String targetTenant,Supplier<T> work) {
        var authorization=com.skillforge.core.foundation.institution.InstitutionDeploymentManagementService.currentTarget();
        if(authorization==null || !actorTenant.equals(authorization.actorTenantId()) || !targetTenant.equals(authorization.targetTenantId())
                || !actorTenant.equals(TenantContext.getTenantId()) || actorTenant.equals(targetTenant)
                || !TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("institution_deployment_target_not_authorized");
        if(TransactionSynchronizationManager.isCurrentTransactionReadOnly()&&!"tenant.agent_deployments.read".equals(authorization.action()))
            throw new IllegalStateException("institution_deployment_write_requires_transaction");
        var transaction=transactionBinding();
        if(ACTIVE_WINDOW.get()!=null || transaction.platformRead!=null || transaction.targetWrite!=null || transaction.headquartersUsageRead!=null
                || (transaction.establishedTenant!=null&&!actorTenant.equals(transaction.establishedTenant)))
            throw new IllegalStateException("institution_deployment_nested_scope_forbidden");
        var scope=new TargetWrite(actorTenant,targetTenant,TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||"tenant.agent_deployments.read".equals(authorization.action()));
        try {for(var connection:transaction.connections.keySet())scope.capture(connection);}
        catch(java.sql.SQLException failure){throw new IllegalStateException("institution_deployment_connection_scope_failed",failure);}
        transaction.targetWrite=scope;
        ACTIVE_WINDOW.set(transaction);
        Throwable failure=null;
        try {TenantContext.setTenantId(targetTenant);return work.get();}
        catch(RuntimeException|Error rejected){failure=rejected;throw rejected;}
        finally {
            TenantContext.setTenantId(actorTenant);
            Throwable cleanupFailure=null;
            try {scope.restore();}catch(RuntimeException|Error rejected){cleanupFailure=rejected;}
            transaction.targetWrite=null;
            ACTIVE_WINDOW.remove();
            for(var executor:transaction.executors.keySet()) {
                try{executor.clearLocalCache();}
                catch(RuntimeException|Error rejected){if(cleanupFailure==null)cleanupFailure=rejected;else cleanupFailure.addSuppressed(rejected);}
            }
            transaction.connections.clear();transaction.executors.clear();
            if(cleanupFailure!=null) {
                if(failure!=null)failure.addSuppressed(cleanupFailure);
                else if(cleanupFailure instanceof RuntimeException rejected)throw rejected;
                else throw (Error)cleanupFailure;
            }
        }
    }

    /**
     * 仅供定义影响读取边界：调用方已检查当前定义 read 和明确机构目标。
     * 窗口与当前只读事务同寿命的局部工作段，不允许普通机构事务改域或创建新事务。
     */
    public static <T>T withDefinitionImpactTargets(long definitionId,Set<Long> tenantIds,Supplier<T> work) {
        return withDefinitionImpactTargets(definitionId,tenantIds,null,work);
    }

    /** 机构源所有者复用同一极窄影响读取窗口，结束时恢复原所有者而非平台身份。 */
    public static <T>T withDefinitionImpactTargets(long definitionId,Set<Long> tenantIds,String ownerTenant,Supplier<T> work) {
        if(definitionId<1 || tenantIds==null || tenantIds.stream().anyMatch(id->id==null||id<1))
            throw new IllegalArgumentException("invalid_definition_impact_targets");
        if(!java.util.Objects.equals(TenantContext.getTenantId(),ownerTenant) || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive() || !TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("definition_impact_requires_platform_read_only_transaction");
        var transaction=transactionBinding();
        if((transaction.establishedTenant!=null&&!transaction.establishedTenant.equals(ownerTenant==null?"":ownerTenant))
                || ACTIVE_WINDOW.get()!=null || transaction.platformRead!=null || transaction.targetWrite!=null || transaction.headquartersUsageRead!=null)
            throw new IllegalStateException("definition_impact_cannot_replace_tenant_transaction");
        var scope=new PlatformRead(definitionId,tenantIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet()),ownerTenant);
        try {for(var connection:transaction.connections.keySet())scope.capture(connection);}
        catch(java.sql.SQLException failure){throw new IllegalStateException("definition_impact_connection_scope_failed",failure);}
        transaction.platformRead=scope;
        ACTIVE_WINDOW.set(transaction);
        Throwable failure=null;
        try{return work.get();}
        catch(RuntimeException|Error rejected){failure=rejected;throw rejected;}
        finally {
            if(ownerTenant==null)TenantContext.clear();else TenantContext.setTenantId(ownerTenant);
            Throwable cleanupFailure=null;
            try {scope.restore();}catch(RuntimeException|Error rejected){cleanupFailure=rejected;}
            transaction.platformRead=null;
            ACTIVE_WINDOW.remove();
            for(var executor:transaction.executors.keySet()) {
                try{executor.clearLocalCache();}
                catch(RuntimeException|Error rejected){if(cleanupFailure==null)cleanupFailure=rejected;else cleanupFailure.addSuppressed(rejected);}
            }
            transaction.connections.clear();transaction.executors.clear();
            if(cleanupFailure!=null) {
                if(failure!=null)failure.addSuppressed(cleanupFailure);
                else if(cleanupFailure instanceof RuntimeException rejected)throw rejected;
                else throw (Error)cleanupFailure;
            }
        }
    }

    /**
     * 拦截 MyBatis 执行器，在真实 SQL 执行前绑定租户上下文。
     *
     * @param invocation MyBatis 方法调用对象
     * @return 原始方法执行结果
     * @throws Throwable 底层 SQL 执行异常
     */
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        String tenantId = TenantContext.getTenantId();
        Executor executor = (Executor) invocation.getTarget();
        Connection connection = executor.getTransaction().getConnection();
        boolean autoCommit = connection.getAutoCommit();
        String tenant = tenantId == null ? "" : tenantId;
        TransactionBinding transaction = !autoCommit
                && executor.getTransaction() instanceof SpringManagedTransaction
                && TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive() ? transactionBinding() : null;
        // 授权窗口只覆盖原受管事务；另一数据源的自动提交或新事务也不能绕过窗口约束。
        var activeWindow=ACTIVE_WINDOW.get();
        if(activeWindow!=null && activeWindow!=transaction)
            throw new IllegalStateException("authorized_scope_requires_original_managed_transaction");
        boolean settings = changesSessionSettings(invocation);
        // 自动提交下 SET LOCAL 在设置语句结束即失效；此时使用会话绑定并在 finally 清除。
        // 显式事务使用事务级绑定，缺少上下文时也必须清空，防止借用先前租户身份。
        Throwable failure = null;
        try {
            if (transaction != null && transaction.observationRead != null) {
                if (!transaction.connections.containsKey(connection)) {
                    bind(connection, "", true);
                    transaction.connections.put(connection, "");
                }
                transaction.observationRead.requireRead(connection, invocation, tenant, sqlRules);
                executor.clearLocalCache();
                transaction.executors.put(executor, "");
                try { return invocation.proceed(); }
                finally { executor.clearLocalCache(); }
            }
            if (transaction == null) bind(connection, tenant, !autoCommit);
            else {
                if(transaction.platformRead!=null)transaction.platformRead.requireRead(connection,invocation,tenant,sqlRules);
                if(transaction.targetWrite!=null)transaction.targetWrite.requireOperation(connection,invocation,tenant,sqlRules);
                if(transaction.headquartersUsageRead!=null) {
                    // 窗口内首次使用的新连接仍先建立真实总部绑定；不继承池中其他请求身份。
                    transaction.requireTenant(tenant);
                    if(!transaction.connections.containsKey(connection)) {
                        bind(connection,tenant,true);transaction.connections.put(connection,tenant);
                    }
                    transaction.headquartersUsageRead.requireRead(connection,invocation,tenant,sqlRules);
                }
                transaction.requireTenant(tenant);
                if (settings || !tenant.equals(transaction.connections.get(connection))) {
                    bind(connection, tenant, true);
                    transaction.connections.put(connection, tenant);
                }
                if (settings) transaction.executors.clear();
                // 多个SqlSessionFactory可共享Spring连接，但不能共享彼此的一级缓存scope证明。
                if (!tenant.equals(transaction.executors.get(executor))) {
                    executor.clearLocalCache(); transaction.executors.put(executor, tenant);
                }
                // 显式领域 binder 可以改写 GUC；它不为下一条 Mapper 签发复用证明。
                if (settings) transaction.connections.remove(connection);
            }
            return invocation.proceed();
        } catch (Throwable error) {
            failure = error;
            throw error;
        } finally {
            if (transaction != null && settings) {
                transaction.connections.remove(connection); transaction.executors.clear();
            }
            if (autoCommit) {
                try { bind(connection, "", false); }
                catch (java.sql.SQLException resetFailure) {
                    if (failure != null) failure.addSuppressed(resetFailure);
                    else throw resetFailure;
                }
            }
        }
    }

    private static boolean changesSessionSettings(Invocation invocation) {
        var statement = (MappedStatement) invocation.getArgs()[0];
        var sql = statement.getBoundSql(invocation.getArgs()[1]);
        if (sql == null) return false;
        String text = sql.getSql();
        if (SET_CONFIG.matcher(text).find()) return true;
        int start = 0;
        while (start < text.length()) {
            int token = skipStatementPrefix(text, start);
            if (keyword(text, token, "set") || keyword(text, token, "reset")) return true;
            int separator = text.indexOf(';', token);
            if (separator < 0) return false;
            start = separator + 1;
        }
        return false;
    }

    /** PostgreSQL允许语句前的行注释与嵌套块注释；UPDATE中的SET不是独立设置语句。 */
    private static int skipStatementPrefix(String sql, int start) {
        int cursor = start;
        while (cursor < sql.length()) {
            if (Character.isWhitespace(sql.charAt(cursor))) { cursor++; continue; }
            if (sql.startsWith("--", cursor)) {
                cursor += 2;
                while (cursor < sql.length() && sql.charAt(cursor) != '\n' && sql.charAt(cursor) != '\r') cursor++;
            } else if (sql.startsWith("/*", cursor)) {
                cursor += 2; int depth = 1;
                while (cursor < sql.length() && depth > 0) {
                    if (sql.startsWith("/*", cursor)) { depth++; cursor += 2; }
                    else if (sql.startsWith("*/", cursor)) { depth--; cursor += 2; }
                    else cursor++;
                }
            } else return cursor;
        }
        return cursor;
    }

    private static boolean keyword(String sql, int start, String keyword) {
        int end = start + keyword.length();
        return sql.regionMatches(true, start, keyword, 0, keyword.length())
                && (end == sql.length() || !Character.isJavaIdentifierPart(sql.charAt(end)));
    }

    /** 资源跟随 Spring 事务挂起/恢复及完成，不跨请求保存身份或 IAM 结果。 */
    private static TransactionBinding transactionBinding() {
        var existing = (TransactionBinding) TransactionSynchronizationManager.getResource(TRANSACTION_BINDING);
        if (existing != null) return existing;
        var created = new TransactionBinding();
        TransactionSynchronizationManager.bindResource(TRANSACTION_BINDING, created);
        TransactionSynchronizationManager.registerSynchronization(created);
        return created;
    }

    private static final class TransactionBinding implements TransactionSynchronization {
        private final Map<Connection, String> connections = new IdentityHashMap<>();
        private final Map<Executor, String> executors = new IdentityHashMap<>();
        private String establishedTenant;
        private PlatformRead platformRead;
        private TargetWrite targetWrite;
        private HeadquartersUsageRead headquartersUsageRead;
        private ObservationRead observationRead;

        private void requireTenant(String tenant) {
            if (headquartersUsageRead != null) {
                if (!headquartersUsageRead.actorTenant.equals(tenant)) throw new IllegalStateException("headquarters_usage_actor_mismatch");
                return;
            }
            if(targetWrite!=null) {
                if(!targetWrite.targetTenant.equals(tenant))throw new IllegalStateException("institution_deployment_target_mismatch");
                return;
            }
            if(platformRead!=null) {
                if(!tenant.isEmpty()&&!tenant.equals(platformRead.ownerTenant)&&!platformRead.tenantIds.contains(tenant))throw new IllegalStateException("definition_impact_tenant_not_authorized");
                return;
            }
            if (tenant.isEmpty()) return;
            // 登录先匿名解析 tenant slug，再建立身份；建立后不能换成另一机构。
            if (establishedTenant == null) establishedTenant = tenant;
            else if (!establishedTenant.equals(tenant))
                throw new IllegalStateException("tenant_transaction_scope_mismatch");
        }

        @Override public void suspend() {
            if (TransactionSynchronizationManager.getResource(TRANSACTION_BINDING) == this)
                TransactionSynchronizationManager.unbindResource(TRANSACTION_BINDING);
        }
        @Override public void resume() {
            TransactionSynchronizationManager.bindResource(TRANSACTION_BINDING, this);
        }
        @Override public void savepointRollback(Object savepoint) { connections.clear(); executors.clear(); }
        @Override public void afterCompletion(int status) {
            connections.clear(); executors.clear(); establishedTenant = null; platformRead=null;targetWrite=null;headquartersUsageRead=null;observationRead=null;
            if (TransactionSynchronizationManager.getResource(TRANSACTION_BINDING) == this)
                TransactionSynchronizationManager.unbindResource(TRANSACTION_BINDING);
        }
    }

    /** 不开放任意SQL设置或写入，目标身份只由本窗口内部绑定并在退出时完整恢复。 */
    private static final class ObservationRead {
        private final String targetTenant;
        private final Map<Connection, Original> originals = new IdentityHashMap<>();
        private record Original(String tenant, String platform) { }
        private ObservationRead(String targetTenant) { this.targetTenant = targetTenant; }

        private void capture(Connection connection) throws java.sql.SQLException {
            if (connection.getAutoCommit()) throw new IllegalStateException("platform_observation_requires_transaction_connection");
            if (originals.containsKey(connection)) return;
            try (var query = connection.prepareStatement("SELECT COALESCE(current_setting('app.tenant_id',true),''),"
                    + "COALESCE(current_setting('app.platform_admin',true),'')"); var rows = query.executeQuery()) {
                if (!rows.next() || !rows.getString(1).isEmpty())
                    throw new IllegalStateException("platform_observation_original_scope_mismatch");
                originals.put(connection, new Original(rows.getString(1), rows.getString(2)));
            }
        }

        private void requireRead(Connection connection, Invocation invocation, String tenant,
                                 List<AuthorizedSqlRule> rules) throws java.sql.SQLException {
            var authorization = PlatformObservationAccess.currentAuthorization();
            if (!tenant.isEmpty() || authorization == null || !authorization.tenantIds().equals(Set.of(Long.parseLong(targetTenant))))
                throw new IllegalStateException("platform_observation_target_mismatch");
            var statement = (MappedStatement) invocation.getArgs()[0];
            var bound = statement.getBoundSql(invocation.getArgs()[1]);
            String sql = bound == null ? "" : bound.getSql();
            int start = skipStatementPrefix(sql, 0);
            if (!"query".equals(invocation.getMethod().getName()) || statement.getSqlCommandType() != SqlCommandType.SELECT
                    || !keyword(sql, start, "select") && !keyword(sql, start, "with")
                    || sql.indexOf(';') >= 0 || MUTATION.matcher(sql).find() || changesSessionSettings(invocation))
                throw new IllegalStateException("platform_observation_statement_not_allowed");
            requireAllowed(rules, new AuthorizedSqlRule.Access(AuthorizedSqlRule.Window.PLATFORM_OBSERVATION,
                    statement.getId(), true, false, invocation.getArgs()[1], targetTenant, 0,
                    Set.of(targetTenant), "", null, "foundation.sessions.read"), "platform_observation_statement_not_allowed");
            capture(connection);
            try (var setting = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true),set_config('app.platform_admin','false',true)")) {
                setting.setString(1, targetTenant); setting.execute();
            }
        }

        private void restore() {
            java.sql.SQLException failure = null;
            for (var entry : originals.entrySet()) {
                try (var setting = entry.getKey().prepareStatement("SELECT set_config('app.tenant_id',?,true),set_config('app.platform_admin',?,true)")) {
                    setting.setString(1, entry.getValue().tenant()); setting.setString(2, entry.getValue().platform()); setting.execute();
                } catch (java.sql.SQLException rejected) {
                    if (failure == null) failure = rejected; else failure.addSuppressed(rejected);
                }
            }
            if (failure != null) throw new IllegalStateException("platform_observation_context_restore_failed", failure);
        }
    }

    /** 仅开放固定用量查询语句；许可函数在RLS中复核当前关系和独立grant，不放行其他表或写入。 */
    private static final class HeadquartersUsageRead {
        private final String actorTenant;
        private final String action;
        private final Set<Long> tenantIds;
        private final String targetSetting;
        private final Map<Connection, Original> originals = new IdentityHashMap<>();
        private HeadquartersUsageRead(String actorTenant, String action, Set<Long> tenantIds) {
            this.actorTenant = actorTenant; this.action = action; this.tenantIds = Set.copyOf(tenantIds);
            this.targetSetting = tenantIds.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        }
        private record Original(String tenant, String platform, String action, String targets) { }

        private void capture(Connection connection) throws java.sql.SQLException {
            if (connection.getAutoCommit()) throw new IllegalStateException("headquarters_usage_requires_transaction_connection");
            if (originals.containsKey(connection)) return;
            try (var query = connection.prepareStatement("SELECT COALESCE(current_setting('app.tenant_id',true),''),"
                    + "COALESCE(current_setting('app.platform_admin',true),''),COALESCE(current_setting('app.headquarters_usage_action',true),''),"
                    + "COALESCE(current_setting('app.headquarters_usage_targets',true),'')"); var result = query.executeQuery()) {
                if (!result.next() || !actorTenant.equals(result.getString(1)) || "true".equals(result.getString(2)))
                    throw new IllegalStateException("headquarters_usage_original_scope_mismatch");
                originals.put(connection, new Original(result.getString(1), result.getString(2), result.getString(3), result.getString(4)));
            }
        }

        private void requireRead(Connection connection, Invocation invocation, String tenant, List<AuthorizedSqlRule> rules) throws java.sql.SQLException {
            if (!actorTenant.equals(tenant)) throw new IllegalStateException("headquarters_usage_actor_mismatch");
            var statement = (MappedStatement) invocation.getArgs()[0];
            String method = statement.getId();
            requireAllowed(rules, new AuthorizedSqlRule.Access(AuthorizedSqlRule.Window.HEADQUARTERS_USAGE,
                    method, statement.getSqlCommandType() == SqlCommandType.SELECT, changesSessionSettings(invocation),
                    invocation.getArgs()[1], tenant, 0, Set.of(), null, null, action), "headquarters_usage_statement_not_allowed");
            var bound = statement.getBoundSql(invocation.getArgs()[1]);
            String sql = bound == null ? "" : bound.getSql();
            int start = skipStatementPrefix(sql, 0);
            if (!"query".equals(invocation.getMethod().getName()) || statement.getSqlCommandType() != SqlCommandType.SELECT
                    || (!keyword(sql, start, "select") && !keyword(sql, start, "with"))
                    || sql.indexOf(';') >= 0 || MUTATION.matcher(sql).find() || SET_CONFIG.matcher(sql).find()
                    || changesSessionSettings(invocation)) throw new IllegalStateException("headquarters_usage_statement_not_allowed");
            if (!(invocation.getArgs()[1] instanceof Map<?, ?> parameters) || !tenantIds.equals(parameters.get("tenants")))
                throw new IllegalStateException("headquarters_usage_targets_mismatch");
            capture(connection);
            try (var bind = connection.prepareStatement("SELECT set_config('app.headquarters_usage_action',?,true),"
                    + "set_config('app.headquarters_usage_targets',?,true)")) {
                bind.setString(1, action); bind.setString(2, targetSetting); bind.execute();
            }
        }

        private void restore() {
            java.sql.SQLException failure = null;
            for (var entry : originals.entrySet()) {
                var original = entry.getValue();
                try (var query = entry.getKey().prepareStatement("SELECT set_config('app.tenant_id',?,true),set_config('app.platform_admin',?,true),"
                        + "set_config('app.headquarters_usage_action',?,true),set_config('app.headquarters_usage_targets',?,true)")) {
                    query.setString(1, original.tenant()); query.setString(2, original.platform());
                    query.setString(3, original.action()); query.setString(4, original.targets()); query.execute();
                } catch (java.sql.SQLException rejected) {
                    if (failure == null) failure = rejected; else failure.addSuppressed(rejected);
                }
            }
            if (failure != null) throw new IllegalStateException("headquarters_usage_context_restore_failed", failure);
        }
    }

    /** 显式领域跨机构事务窗口；普通租户事务仍禁止换域，允许语句由窗口类型和领域规则限定。 */
    private static final class TargetWrite {
        private final String actorTenant;
        private final String targetTenant;
        private final boolean readOnly;
        private final AuthorizedSqlRule.Window window;
        private Connection connection;
        private String originalPlatform;

        private TargetWrite(String actorTenant,String targetTenant,boolean readOnly) {
            this(actorTenant,targetTenant,readOnly,AuthorizedSqlRule.Window.INSTITUTION_DEPLOYMENT);
        }
        private TargetWrite(String actorTenant,String targetTenant,boolean readOnly,AuthorizedSqlRule.Window window) {
            this.actorTenant=actorTenant;this.targetTenant=targetTenant;this.readOnly=readOnly;this.window=window;
        }
        /** 保存当前真实连接；不从请求中的上下文声明推定数据库身份。 */
        private void capture(Connection candidate)throws java.sql.SQLException {
            if(candidate.getAutoCommit())throw new IllegalStateException("institution_deployment_requires_transaction_connection");
            if(connection!=null) {
                if(connection!=candidate)throw new IllegalStateException("institution_deployment_requires_same_connection");
                return;
            }
            try(var query=candidate.prepareStatement("SELECT COALESCE(current_setting('app.tenant_id',true),''),COALESCE(current_setting('app.platform_admin',true),'false')");var result=query.executeQuery()) {
                if(!result.next()||!actorTenant.equals(result.getString(1)))throw new IllegalStateException("institution_deployment_original_tenant_mismatch");
                originalPlatform=result.getString(2);
                if(window==AuthorizedSqlRule.Window.INSTITUTION_DEPLOYMENT&&"true".equals(originalPlatform))
                    throw new IllegalStateException("institution_deployment_cannot_replace_platform_scope");
            }
            connection=candidate;
        }
        /** 部署元数据和目标资源引用的固定存储清单，不包含业务正文读取或新执行。 */
        private void requireOperation(Connection candidate,Invocation invocation,String tenant,List<AuthorizedSqlRule> rules)throws java.sql.SQLException {
            if(!targetTenant.equals(tenant))throw new IllegalStateException("institution_deployment_target_mismatch");
            capture(candidate);
            var statement=(MappedStatement)invocation.getArgs()[0];String id=statement.getId();
            boolean query=statement.getSqlCommandType()==SqlCommandType.SELECT;
            if (readOnly && !query) throw new IllegalStateException("institution_deployment_statement_not_allowed");
            var bound=statement.getBoundSql(invocation.getArgs()[1]);String sql=bound==null?"":bound.getSql();
            if(readOnly&&MUTATION.matcher(sql).find())throw new IllegalStateException("institution_deployment_read_cannot_write");
            requireAllowed(rules, new AuthorizedSqlRule.Access(window,
                    id, query, changesSessionSettings(invocation), invocation.getArgs()[1], tenant,
                    0, Set.of(), null, null, null), changesSessionSettings(invocation)
                            ? "institution_deployment_setting_not_allowed" : "institution_deployment_statement_not_allowed");
        }
        /** 异常和成功都恢复原总部连接；失败事务由外层正常回滚。 */
        private void restore() {
            if(connection==null)return;
            try(var query=connection.prepareStatement("SELECT set_config('app.tenant_id',?,true),set_config('app.platform_admin',?,true)")) {
                query.setString(1,actorTenant);query.setString(2,originalPlatform);query.execute();
            }catch(java.sql.SQLException failure){throw new IllegalStateException("institution_deployment_context_restore_failed",failure);}
        }
    }

    private static final class PlatformRead {
        private final long definitionId;
        private final Set<String> tenantIds;
        private Connection connection;
        private String originalTenant;
        private String originalPlatform;
        private final String ownerTenant;
        private PlatformRead(long definitionId,Set<String> tenantIds,String ownerTenant){this.definitionId=definitionId;this.tenantIds=Set.copyOf(tenantIds);this.ownerTenant=ownerTenant==null?"":ownerTenant;}

        private void capture(Connection candidate)throws java.sql.SQLException {
            if(candidate.getAutoCommit()||!candidate.isReadOnly())throw new IllegalStateException("definition_impact_connection_not_read_only");
            if(connection!=null) {
                if(connection!=candidate)throw new IllegalStateException("definition_impact_requires_same_connection");
                return;
            }
            try(var query=candidate.prepareStatement("SELECT COALESCE(current_setting('app.tenant_id',true),''),COALESCE(current_setting('app.platform_admin',true),'false')");var result=query.executeQuery()) {
                if(!result.next())throw new IllegalStateException("definition_impact_context_missing");
                originalTenant=result.getString(1);originalPlatform=result.getString(2);
                if(!originalTenant.equals(ownerTenant))throw new IllegalStateException("definition_impact_cannot_replace_tenant_connection");
            }
            connection=candidate;
        }

        private void requireRead(Connection candidate,Invocation invocation,String tenant,List<AuthorizedSqlRule> rules)throws java.sql.SQLException {
            if(!TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("definition_impact_transaction_not_read_only");
            capture(candidate);
            var statement=(MappedStatement)invocation.getArgs()[0];
            String id=statement.getId();
            var bound=statement.getBoundSql(invocation.getArgs()[1]);
            String sql=bound==null?"":bound.getSql();
            int start=skipStatementPrefix(sql,0);
            if(!"query".equals(invocation.getMethod().getName()) || statement.getSqlCommandType()!=SqlCommandType.SELECT
                    || !keyword(sql,start,"select") || sql.indexOf(';')>=0 || MUTATION.matcher(sql).find())
                throw new IllegalStateException("definition_impact_business_write_forbidden");
            requireAllowed(rules, new AuthorizedSqlRule.Access(AuthorizedSqlRule.Window.DEFINITION_IMPACT,
                    id, true, changesSessionSettings(invocation), invocation.getArgs()[1], tenant,
                    definitionId, tenantIds, originalTenant, originalPlatform, null), changesSessionSettings(invocation)
                            ? "definition_impact_setting_not_allowed" : "definition_impact_statement_not_allowed");
        }

        private void restore() {
            if(connection==null)return;
            try(var query=connection.prepareStatement("SELECT set_config('app.tenant_id',?,true),set_config('app.platform_admin',?,true)")) {
                query.setString(1,originalTenant);query.setString(2,originalPlatform);query.execute();
            }catch(java.sql.SQLException failure){throw new IllegalStateException("definition_impact_context_restore_failed",failure);}
        }
    }

    private void bind(Connection connection, String tenantId, boolean local) throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, ?)")) {
            statement.setString(1, tenantId);
            statement.setBoolean(2, local);
            statement.execute();
        }
    }

    /**
     * 包装目标对象生成代理。
     *
     * @param target 被拦截的 MyBatis 组件
     * @return 包装后的代理对象
     */
    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }
}
