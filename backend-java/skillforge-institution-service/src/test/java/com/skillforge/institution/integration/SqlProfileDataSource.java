package com.skillforge.institution.integration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** 仅供性能 E2E 使用的 JDBC 分类计时器；不保留 SQL 参数或完整语句。 */
final class SqlProfileDataSource {

    private static final boolean ENABLED = "1".equals(System.getenv("SKILLFORGE_PERF_SQL"));
    private static final boolean CALLERS_ENABLED = "1".equals(System.getenv("SKILLFORGE_PERF_SQL_CALLERS"));
    private static final boolean ROLES_ENABLED = "1".equals(System.getenv("SKILLFORGE_PERF_SQL_ROLES"));
    private static final Pattern LEADING_COMMENTS = Pattern.compile(
            "(?s)\\A(?:\\s+|/\\*.*?\\*/|--[^\\r\\n]*(?:\\r?\\n|\\z))*");
    private static final Pattern OPERATION = Pattern.compile("(?i)^(SELECT|INSERT|UPDATE|DELETE)\\b");
    private static final Pattern SELECT_TABLE = Pattern.compile(
            "(?is)^SELECT\\b.*?\\bFROM\\s+(" + identifierPattern() + ")");
    private static final Pattern INSERT_TABLE = Pattern.compile(
            "(?i)^INSERT\\s+INTO\\s+(" + identifierPattern() + ")");
    private static final Pattern UPDATE_TABLE = Pattern.compile(
            "(?i)^UPDATE\\s+(" + identifierPattern() + ")");
    private static final Pattern DELETE_TABLE = Pattern.compile(
            "(?i)^DELETE(?:\\s+FROM)?\\s+(" + identifierPattern() + ")");
    private static final Pattern AUTHORIZATION_CURRENT = Pattern.compile(
            "(?i)\\bagent_runtime_authorization_current\\b");
    private static final AtomicBoolean SAMPLING = new AtomicBoolean();
    private static final ConcurrentHashMap<String, Counter> EXECUTES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Counter> TRANSACTIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Counter> CALLERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Counter> CONNECTION_CALLERS = new ConcurrentHashMap<>();
    private static final Counter CONNECTIONS = new Counter();
    private static final ConcurrentHashMap<ThreadRole, RoleCounters> ROLES = new ConcurrentHashMap<>();

    private SqlProfileDataSource() {
    }

    static BeanPostProcessor beanPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!ENABLED || !(bean instanceof DataSource dataSource)
                        || bean instanceof ProfilingDataSource) {
                    return bean;
                }
                return new ProfilingDataSource(dataSource);
            }
        };
    }

    static void reset() {
        if (!ENABLED) {
            return;
        }
        SAMPLING.set(false);
        EXECUTES.clear();
        TRANSACTIONS.clear();
        CALLERS.clear();
        CONNECTION_CALLERS.clear();
        CONNECTIONS.reset();
        ROLES.clear();
        SAMPLING.set(true);
    }

    static void printSummary() {
        if (!ENABLED) {
            return;
        }
        SAMPLING.set(false);
        long executeCount = EXECUTES.values().stream().mapToLong(Counter::count).sum();
        long executeNanos = EXECUTES.values().stream().mapToLong(Counter::nanos).sum();
        long transactionCount = TRANSACTIONS.values().stream().mapToLong(Counter::count).sum();
        long transactionNanos = TRANSACTIONS.values().stream().mapToLong(Counter::nanos).sum();
        System.out.printf("SQL_PROFILE total_get_connection_count=%d total_get_connection_ms=%.3f "
                        + "total_execute_count=%d total_execute_ms=%.3f "
                        + "total_transaction_count=%d total_transaction_ms=%.3f%n",
                CONNECTIONS.count(), millis(CONNECTIONS.nanos()), executeCount, millis(executeNanos),
                transactionCount, millis(transactionNanos));
        // 审计次数是固定工作量的验收指标，不能因耗时跌出前二十项而漏报；直接读取已有计数。
        long auditInserts = EXECUTES.entrySet().stream()
                .filter(entry -> entry.getKey().equals("INSERT public.agent_runtime_security_audits"))
                .mapToLong(entry -> entry.getValue().count()).sum();
        System.out.printf("SQL_PROFILE audit_insert_count=%d%n", auditInserts);
        EXECUTES.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Counter>>comparingLong(entry -> entry.getValue().nanos())
                        .reversed().thenComparing(Map.Entry::getKey))
                .limit(20)
                .forEach(entry -> System.out.printf("SQL_PROFILE operation_table=%s count=%d total_ms=%.3f%n",
                        entry.getKey(), entry.getValue().count(), millis(entry.getValue().nanos())));
        TRANSACTIONS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> System.out.printf("SQL_PROFILE transaction=%s count=%d total_ms=%.3f%n",
                        entry.getKey(), entry.getValue().count(), millis(entry.getValue().nanos())));
        if (CALLERS_ENABLED) System.out.println(
                "CONNECTION_PROFILE attribution_only=true timing_comparable=false");
        CONNECTION_CALLERS.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Counter>>comparingLong(entry -> entry.getValue().count())
                        .reversed().thenComparing(Map.Entry::getKey))
                .limit(20)
                .forEach(entry -> System.out.printf("CONNECTION_PROFILE adapter=%s count=%d total_ms=%.3f%n",
                        entry.getKey(), entry.getValue().count(), millis(entry.getValue().nanos())));
        CALLERS.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Counter>>comparingLong(entry -> entry.getValue().count())
                        .reversed().thenComparing(Map.Entry::getKey))
                .limit(10)
                .forEach(entry -> System.out.printf("CALL_PROFILE callers=%s count=%d%n",
                        entry.getKey(), entry.getValue().count()));
        ROLES.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var counters = entry.getValue();
            System.out.printf("SQL_ROLE role=%s connections=%d executes=%d execute_ms=%.3f transactions=%d transaction_ms=%.3f%n",
                    entry.getKey(), counters.connections.count(), counters.executes.count(),
                    millis(counters.executes.nanos()), counters.transactions.count(), millis(counters.transactions.nanos()));
            counters.sql.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, Counter>>comparingLong(value -> value.getValue().nanos()).reversed())
                    .limit(10).forEach(value -> System.out.printf("SQL_ROLE_TABLE role=%s operation_table=%s count=%d total_ms=%.3f%n",
                            entry.getKey(), value.getKey(), value.getValue().count(), millis(value.getValue().nanos())));
        });
    }

    private static Connection profileConnection(Connection connection) {
        return (Connection) Proxy.newProxyInstance(SqlProfileDataSource.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if ("prepareStatement".equals(name) || "prepareCall".equals(name)) {
                        Object statement = invoke(method, connection, args);
                        String sql = sqlArgument(args);
                        return profileStatement((Statement) statement, category(sql), isAuthorizationCurrent(sql));
                    }
                    if ("createStatement".equals(name)) {
                        Object statement = invoke(method, connection, args);
                        return profileStatement((Statement) statement, "OTHER", false);
                    }
                    if (isTransactionLifecycle(name) && isSampling()) {
                        long startedAt = System.nanoTime();
                        try {
                            return invoke(method, connection, args);
                        } finally {
                            long elapsed = System.nanoTime() - startedAt;
                            TRANSACTIONS.computeIfAbsent(name, ignored -> new Counter())
                                    .add(elapsed);
                            if (ROLES_ENABLED) currentRole().transactions.add(elapsed);
                        }
                    }
                    return invoke(method, connection, args);
                });
    }

    private static Statement profileStatement(
            Statement statement, String preparedCategory, boolean preparedAuthorizationCurrent) {
        Class<?> statementType = statement instanceof CallableStatement ? CallableStatement.class
                : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(SqlProfileDataSource.class.getClassLoader(),
                new Class<?>[] {statementType}, (proxy, method, args) -> {
                    if (!method.getName().startsWith("execute")) {
                        return invoke(method, statement, args);
                    }
                    if (!isSampling()) {
                        return invoke(method, statement, args);
                    }
                    String sql = sqlArgument(args);
                    if (CALLERS_ENABLED && (sql == null ? preparedAuthorizationCurrent : isAuthorizationCurrent(sql))) {
                        recordAuthorizationCurrentCaller();
                    }
                    String category = sql == null ? preparedCategory : category(sql);
                    long startedAt = System.nanoTime();
                    try {
                        return invoke(method, statement, args);
                    } finally {
                        long elapsed = System.nanoTime() - startedAt;
                        EXECUTES.computeIfAbsent(category, ignored -> new Counter())
                                .add(elapsed);
                        if (ROLES_ENABLED) {
                            var role = currentRole();
                            role.executes.add(elapsed);
                            role.sql.computeIfAbsent(category, ignored -> new Counter()).add(elapsed);
                        }
                    }
                });
    }

    private static String sqlArgument(Object[] args) {
        return args != null && args.length > 0 && args[0] instanceof String sql ? sql : null;
    }

    // 仅使用已有执行器名称归类；不输出线程名、业务身份或逐语句调用栈。
    static ThreadRole threadRole(String name) {
        // Starter控制线程共用执行前缀，须先匹配具体控制池，避免把续租/补扫归到业务执行槽。
        if (name.startsWith("runtime-execution-control-")) return ThreadRole.CONTROL;
        if (name.startsWith("runtime-execution-")) return ThreadRole.EXECUTION;
        if (name.startsWith("agent-runtime-session-")) return ThreadRole.SESSION;
        if (name.startsWith("agent-runtime-tool-batch-")) return ThreadRole.TOOL;
        if (name.startsWith("model-provider-")) return ThreadRole.MODEL;
        if (name.equals("agent-runtime-workers")) return ThreadRole.WORKER;
        if (name.equals("agent-runtime-events") || name.equals("runtime-event-delivery")) return ThreadRole.EVENTS;
        if (name.equals("agent-runtime-liveness") || name.equals("agent-runtime-control-check")) return ThreadRole.CONTROL;
        if (name.equals("chat-runtime-follow") || name.equals("chat-event-subscription")) return ThreadRole.SSE;
        if (name.startsWith("http-nio-")) return ThreadRole.HTTP;
        return ThreadRole.OTHER;
    }

    private static RoleCounters currentRole() {
        return ROLES.computeIfAbsent(threadRole(Thread.currentThread().getName()), ignored -> new RoleCounters());
    }

    enum ThreadRole { EXECUTION, SESSION, TOOL, MODEL, WORKER, EVENTS, CONTROL, SSE, HTTP, OTHER }

    private static final class RoleCounters {
        final Counter connections = new Counter();
        final Counter executes = new Counter();
        final Counter transactions = new Counter();
        final ConcurrentHashMap<String, Counter> sql = new ConcurrentHashMap<>();
    }

    static String category(String sql) {
        if (sql == null || sql.isBlank()) {
            return "OTHER";
        }
        String statement = LEADING_COMMENTS.matcher(sql).replaceFirst("").trim();
        if (isAuthorizationCurrent(sql)) return "SELECT authorization_current";
        // 只匹配固定无表语句的开头，避免把普通业务查询中的函数误分类。
        String fixed = statement.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        if (fixed.matches("^select set_config\\s*\\(\\s*'app\\.tenant_id'.*"))
            return fixed.contains("'statement_timeout'") ? "SELECT tenant_and_timeout" : "SELECT tenant_bind";
        if (fixed.matches("^select set_config\\s*\\(\\s*'statement_timeout'.*")) return "SELECT statement_timeout";
        if (fixed.matches("^select clock_timestamp\\s*\\(\\s*\\).*")) return "SELECT clock";
        if (fixed.matches("^select (?:public\\.)?resolve_runtime_authentication_tenant\\s*\\(.*")) return "SELECT identity_tenant";
        if (fixed.matches("^select (?:public\\.)?pg_(?:try_)?advisory(?:_xact)?_(?:lock|unlock)\\s*\\(.*")) return "SELECT advisory_lock";
        var operationMatcher = OPERATION.matcher(statement);
        if (!operationMatcher.find()) {
            return "OTHER";
        }
        String operation = operationMatcher.group(1).toUpperCase(java.util.Locale.ROOT);
        String table = table(operation, statement);
        return table == null ? operation : operation + " " + table.toLowerCase(java.util.Locale.ROOT);
    }

    private static String table(String operation, String statement) {
        Pattern tablePattern = switch (operation) {
            case "SELECT" -> SELECT_TABLE;
            case "INSERT" -> INSERT_TABLE;
            case "UPDATE" -> UPDATE_TABLE;
            case "DELETE" -> DELETE_TABLE;
            default -> null;
        };
        if (tablePattern == null) {
            return null;
        }
        var tableMatcher = tablePattern.matcher(statement);
        return tableMatcher.find() ? tableMatcher.group(1) : null;
    }

    private static String identifierPattern() {
        return "[A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)*";
    }

    private static boolean isSampling() {
        return ENABLED && SAMPLING.get();
    }

    private static boolean isTransactionLifecycle(String method) {
        return "commit".equals(method) || "rollback".equals(method) || "setAutoCommit".equals(method)
                || "setTransactionIsolation".equals(method) || "close".equals(method);
    }

    private static boolean isAuthorizationCurrent(String sql) {
        return sql != null && AUTHORIZATION_CURRENT.matcher(sql).find();
    }

    private static void recordAuthorizationCurrentCaller() {
        String callers = StackWalker.getInstance().walk(frames -> frames
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName())
                .filter(name -> name.startsWith("com.skillforge.")
                        && !name.startsWith("com.skillforge.institution.integration.SqlProfileDataSource"))
                .distinct()
                .limit(12)
                .reduce((first, second) -> first + " <- " + second)
                .orElse("<none>"));
        CALLERS.computeIfAbsent(callers, ignored -> new Counter()).add(0L);
    }

    /** 取最靠近业务入口的具体 JDBC adapter 帧，合并内部 helper/lambda，避免整条调用栈碎片化。 */
    static String adapterCaller(List<StackTraceElement> frames) {
        return frames.stream()
                .filter(frame -> frame.getClassName().startsWith("com.skillforge.runtime.adapter.jdbc.")
                        && frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1).startsWith("Jdbc")
                        && !frame.getMethodName().startsWith("lambda$")
                        && !frame.getMethodName().startsWith("<"))
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName())
                .reduce((inner, outer) -> outer).orElse("<no-jdbc-adapter>");
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static final class ProfilingDataSource implements DataSource {
        private final DataSource delegate;

        private ProfilingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return timedConnection(() -> delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return timedConnection(() -> delegate.getConnection(username, password));
        }

        private Connection timedConnection(ConnectionSupplier supplier) throws SQLException {
            if (!isSampling()) {
                return supplier.get();
            }
            String caller = CALLERS_ENABLED ? StackWalker.getInstance().walk(frames ->
                    adapterCaller(frames.map(StackWalker.StackFrame::toStackTraceElement).toList())) : null;
            long startedAt = System.nanoTime();
            try {
                return profileConnection(supplier.get());
            } finally {
                long elapsed = System.nanoTime() - startedAt;
                CONNECTIONS.add(elapsed);
                if (ROLES_ENABLED) currentRole().connections.add(elapsed);
                if (caller != null) CONNECTION_CALLERS.computeIfAbsent(caller, ignored -> new Counter()).add(elapsed);
            }
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }

        @Override
        public java.io.PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
    }

    @FunctionalInterface
    private interface ConnectionSupplier {
        Connection get() throws SQLException;
    }

    private static final class Counter {
        private final LongAdder count = new LongAdder();
        private final LongAdder nanos = new LongAdder();

        private void add(long elapsedNanos) {
            count.increment();
            nanos.add(elapsedNanos);
        }

        private long count() {
            return count.sum();
        }

        private long nanos() {
            return nanos.sum();
        }

        private void reset() {
            count.reset();
            nanos.reset();
        }
    }
}
