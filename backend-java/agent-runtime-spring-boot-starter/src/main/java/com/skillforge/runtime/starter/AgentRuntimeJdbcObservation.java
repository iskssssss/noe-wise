package com.skillforge.runtime.starter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** 按需诊断当前线程的 JDBC 往返；不采集 SQL、参数或凭据，不取得事务与连接关闭所有权。 */
final class AgentRuntimeJdbcObservation {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static volatile boolean enabled;
    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeJdbcObservation.class);
    private static final ThreadLocal<Map<String, WorkerSample>> WORKERS =
            ThreadLocal.withInitial(LinkedHashMap::new);

    private AgentRuntimeJdbcObservation() { }

    static Scope open() {
        var scope = new Scope(CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    /** 可选观察器开启时按后台来源聚合；无 SQL/参数，默认运行不创建 scope 或计数样本。 */
    static <T> T worker(String kind, Supplier<T> poll) {
        if (!enabled) return poll.get();
        try (var scope = open()) {
            try { return poll.get(); }
            finally { WORKERS.get().computeIfAbsent(kind, ignored -> new WorkerSample()).add(kind, scope); }
        }
    }

    private static final class WorkerSample {
        private long started = System.nanoTime();
        private long polls, connections, executions, acquireNanos, executeNanos, controls, controlNanos;

        void add(String kind, Scope scope) {
            polls++;
            connections += scope.connections;
            executions += scope.executions;
            acquireNanos += scope.connectionAcquireNanos;
            executeNanos += scope.jdbcExecutionNanos;
            controls += scope.transactionControls;
            controlNanos += scope.transactionControlNanos;
            long elapsed = System.nanoTime() - started;
            if (elapsed < java.time.Duration.ofSeconds(30).toNanos()) return;
            log.info("Runtime worker JDBC kind={} polls={} connections={} executions={} "
                    + "acquireNanos={} executeNanos={} transactionControls={} transactionControlNanos={} elapsedNanos={} scope=current-thread-inclusive",
                    kind, polls, connections, executions, acquireNanos, executeNanos, controls, controlNanos, elapsed);
            started = System.nanoTime();
            polls = connections = executions = acquireNanos = executeNanos = controls = controlNanos = 0;
        }
    }

    static DataSource observe(DataSource source) {
        enabled = true;
        return new ObservedDataSource(source);
    }

    /** Spring 停机仍关闭原池；计数装饰器不延长其生命周期。 */
    private static final class ObservedDataSource extends DelegatingDataSource implements AutoCloseable {
        private ObservedDataSource(DataSource source) { super(source); }
        @Override public Connection getConnection() throws java.sql.SQLException {
            return connection(super::getConnection);
        }
        @Override public Connection getConnection(String username, String password) throws java.sql.SQLException {
            return connection(() -> super.getConnection(username, password));
        }
        /** Boot派生Flyway数据源时须读原池属性，不能停留在无URL属性的观察装饰器。 */
        @Override public <T> T unwrap(Class<T> type) throws java.sql.SQLException {
            if (type == DataSource.class) return type.cast(obtainTargetDataSource());
            return super.unwrap(type);
        }
        @Override public void close() throws Exception {
            if (getTargetDataSource() instanceof AutoCloseable closeable) closeable.close();
        }
    }

    private static Connection connection(ConnectionSupplier supplier) throws java.sql.SQLException {
        long started = System.nanoTime();
        try {
            var actual = supplier.get();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("equals")) return proxy == args[0];
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (method.getName().equals("unwrap") && ((Class<?>) args[0]).isInstance(proxy)) return proxy;
                        if (method.getName().equals("isWrapperFor") && ((Class<?>) args[0]).isInstance(proxy)) return true;
                        // setAutoCommit(true) 可能隐式提交；单独计量连接事务控制，不把它混入SQL执行或CPU时间。
                        if (method.getName().equals("commit") || method.getName().equals("rollback")
                                || method.getName().equals("setAutoCommit")) {
                            long controlStarted = System.nanoTime();
                            try { return invoke(actual, method, args); }
                            finally { transactionControl(System.nanoTime() - controlStarted); }
                        }
                        Object value = invoke(actual, method, args);
                        if (value instanceof Statement statement && method.getReturnType().isInterface()) {
                            return Proxy.newProxyInstance(Statement.class.getClassLoader(),
                                    new Class<?>[]{method.getReturnType()}, (statementProxy, statementMethod, statementArgs) -> {
                                        if (statementMethod.getName().equals("equals")) return statementProxy == statementArgs[0];
                                        if (statementMethod.getName().equals("hashCode")) return System.identityHashCode(statementProxy);
                                        if (statementMethod.getName().equals("getConnection")) return proxy;
                                        if (statementMethod.getName().equals("unwrap") && ((Class<?>) statementArgs[0]).isInstance(statementProxy)) return statementProxy;
                                        if (statementMethod.getName().equals("isWrapperFor") && ((Class<?>) statementArgs[0]).isInstance(statementProxy)) return true;
                                        if (!statementMethod.getName().startsWith("execute")) return invoke(statement, statementMethod, statementArgs);
                                        long executionStarted = System.nanoTime();
                                        try { return invoke(statement, statementMethod, statementArgs); }
                                        finally { execution(System.nanoTime() - executionStarted); }
                                    });
                        }
                        return value;
                    });
        } finally {
            long elapsed = System.nanoTime() - started;
            for (var scope = CURRENT.get(); scope != null; scope = scope.parent) {
                scope.connections++;
                scope.connectionAcquireNanos += elapsed;
            }
        }
    }

    private static void execution(long nanos) {
        for (var scope = CURRENT.get(); scope != null; scope = scope.parent) {
            scope.executions++;
            scope.jdbcExecutionNanos += nanos;
        }
    }

    /** 统计成功与失败的原始事务控制调用，不新增提交、回滚或连接关闭行为。 */
    private static void transactionControl(long nanos) {
        for (var scope = CURRENT.get(); scope != null; scope = scope.parent) {
            scope.transactionControls++;
            scope.transactionControlNanos += nanos;
        }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private interface ConnectionSupplier { Connection get() throws java.sql.SQLException; }

    /** 嵌套观察各自含子区间，不能相加；跨线程 JDBC 不归入父线程样本。 */
    static final class Scope implements AutoCloseable {
        private final Scope parent;
        private long connections;
        private long connectionAcquireNanos;
        private long executions;
        private long jdbcExecutionNanos;
        private long transactionControls;
        private long transactionControlNanos;

        private Scope(Scope parent) { this.parent = parent; }

        Map<String, Object> fields() {
            var fields = new LinkedHashMap<String, Object>();
            fields.put("jdbcObserved", enabled);
            fields.put("jdbcScope", "current-thread-inclusive");
            if (!enabled) return fields;
            fields.put("connections", Long.toString(connections));
            fields.put("connectionAcquireNanos", Long.toString(connectionAcquireNanos));
            fields.put("jdbcExecutions", Long.toString(executions));
            fields.put("jdbcExecutionNanos", Long.toString(jdbcExecutionNanos));
            fields.put("transactionControls", Long.toString(transactionControls));
            fields.put("transactionControlNanos", Long.toString(transactionControlNanos));
            return fields;
        }

        @Override public void close() {
            if (CURRENT.get() != this) throw new IllegalStateException("jdbc_observation_scope_order_invalid");
            if (parent == null) CURRENT.remove(); else CURRENT.set(parent);
        }
    }
}
