package com.skillforge.institution.integration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * 测试专用透明DataSource计数器，不读取SQL正文、参数或异常正文，不改变连接/事务设置。
 * 正常调用只计数和测量；执行失败最多记录100项类型、SQLState及每项20层应用栈。
 * 只在一个测量窗口开启时计数；并发下输出整个进程DataSource窗口指标，不冒充每个请求的SQL。
 */
final class RuntimeExecutionDataSourceProbe implements BeanPostProcessor {
    private final AtomicReference<Window> active = new AtomicReference<>();

    /** Bean完成原始配置后包装；仍委托同一真实连接池及底层Connection。 */
    @Override public Object postProcessAfterInitialization(Object bean, String name) {
        return bean instanceof DataSource source && !(source instanceof ObservedDataSource)
                ? new ObservedDataSource(source) : bean;
    }

    /** 开启窗口不清零全局事实；同一上下文一次只运行一组容量测试。 */
    Window begin() {
        var window = new Window();
        if (!active.compareAndSet(null, window)) throw new IllegalStateException("datasource_probe_already_active");
        return window;
    }

    /** 关闭采样后返回不可变快照，不访问数据库以免污染本窗口。 */
    Snapshot end(Window window) {
        if (!active.compareAndSet(window, null)) throw new IllegalStateException("datasource_probe_window_mismatch");
        return window.snapshot();
    }

    /** 在真实getConnection边界测量获取耗时，包含池等待及可能的驱动连接开销。 */
    private Connection acquire(DataSource source, String username, String password, boolean credentialed) throws SQLException {
        var window = active.get(); long started = System.nanoTime();
        try {
            Connection connection = credentialed ? source.getConnection(username, password) : source.getConnection();
            if (window != null) { window.connections.increment(); window.acquisitions.add(System.nanoTime() - started); }
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> method.getDeclaringClass() == Object.class
                            ? objectCall(proxy, connection, method, arguments) : connectionCall(proxy, connection, method, arguments));
        } catch (SQLException failed) {
            if (window != null) { window.acquireFailures.increment(); window.acquisitions.add(System.nanoTime() - started); }
            throw failed;
        }
    }

    /** 真实commit成功才增加显式commit数；不将setAutoCommit或方法调用误算为事务已提交。 */
    private Object connectionCall(Object connectionProxy, Connection connection, Method method, Object[] arguments) throws Throwable {
        var window = active.get();
        Object result = invoke(connection, method, arguments);
        if (window != null && method.getName().equals("commit")) window.explicitCommits.increment();
        if (window != null && method.getName().equals("rollback")) window.rollbacks.increment();
        if (result instanceof Statement statement && (method.getName().equals("createStatement")
                || method.getName().equals("prepareStatement") || method.getName().equals("prepareCall"))) {
            Class<?> kind = statement instanceof java.sql.CallableStatement ? java.sql.CallableStatement.class
                    : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
            return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{kind},
                    (proxy, execution, values) -> execution.getDeclaringClass() == Object.class
                            ? objectCall(proxy, statement, execution, values)
                            : statementCall(connectionProxy, statement, execution, values));
        }
        return result;
    }

    /** 只计实际execute边界；executeBatch结果中的元素数另外报告，不解析SQL或猜测自动提交。 */
    private Object statementCall(Object connectionProxy, Statement statement, Method method, Object[] arguments) throws Throwable {
        boolean execution = method.getName().equals("execute") || method.getName().equals("executeQuery")
                || method.getName().equals("executeUpdate") || method.getName().equals("executeLargeUpdate")
                || method.getName().equals("executeBatch") || method.getName().equals("executeLargeBatch");
        var window = execution ? active.get() : null;
        if (window != null) window.sqlExecuteCalls.increment();
        try {
            Object result = invoke(statement, method, arguments);
            if (method.getName().equals("getConnection")) return connectionProxy;
            if (window != null && result instanceof int[] results) window.batchResultElements.add(results.length);
            if (window != null && result instanceof long[] results) window.batchResultElements.add(results.length);
            return result;
        } catch (Throwable failed) {
            if (window != null) {
                window.sqlFailures.increment();
                if (window.failureCount.getAndIncrement() < 100) window.failures.add(failure(failed));
            }
            throw failed;
        }
    }

    /** 仅失败时采集有界类型、SQLState和应用栈；不读取SQL、参数或异常正文。 */
    private static Failure failure(Throwable failed) {
        String state = failed instanceof SQLException sql ? sql.getSQLState() : null;
        var callers = java.util.Arrays.stream(failed.getStackTrace())
                .filter(frame -> frame.getClassName().startsWith("com.skillforge."))
                .limit(20).map(StackTraceElement::toString).toList();
        return new Failure(failed.getClass().getName(), state, callers);
    }

    /** 去除反射包装，原SQLException/行为逐字传播给生产事务管理器。 */
    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException wrapper) { throw wrapper.getCause(); }
    }

    /** JDBC代理保持reflexive identity，不能把proxy传入delegate.equals破坏事务资源容器。 */
    private static Object objectCall(Object proxy, Object target, Method method, Object[] arguments) throws Throwable {
        return switch (method.getName()) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> invoke(target, method, arguments);
        };
    }

    /** 委托原DataSource的标准wrapper及生命周期，不创建第二个连接池。 */
    private final class ObservedDataSource extends DelegatingDataSource implements AutoCloseable {
        ObservedDataSource(DataSource source) { super(source); }
        @Override public Connection getConnection() throws SQLException { return acquire(obtainTargetDataSource(), null, null, false); }
        @Override public Connection getConnection(String username, String password) throws SQLException { return acquire(obtainTargetDataSource(), username, password, true); }
        @Override public void close() throws Exception { if (obtainTargetDataSource() instanceof AutoCloseable source) source.close(); }
    }

    /** 只包含本窗口观测，失败和连接获取样本均保留。 */
    static final class Window {
        final LongAdder connections = new LongAdder();
        final LongAdder acquireFailures = new LongAdder();
        final LongAdder sqlExecuteCalls = new LongAdder();
        final LongAdder batchResultElements = new LongAdder();
        final LongAdder sqlFailures = new LongAdder();
        final LongAdder explicitCommits = new LongAdder();
        final LongAdder rollbacks = new LongAdder();
        final ConcurrentLinkedQueue<Long> acquisitions = new ConcurrentLinkedQueue<>();
        final AtomicInteger failureCount = new AtomicInteger();
        final ConcurrentLinkedQueue<Failure> failures = new ConcurrentLinkedQueue<>();
        Snapshot snapshot() { return new Snapshot(connections.sum(), acquireFailures.sum(), sqlExecuteCalls.sum(), batchResultElements.sum(),
                sqlFailures.sum(), explicitCommits.sum(), rollbacks.sum(), List.copyOf(acquisitions), List.copyOf(failures),
                Math.max(0, failureCount.get() - 100)); }
    }

    /** 错误采证限定技术定位字段，保留实际SQLState，不使用消息反推未发生的业务结果。 */
    record Failure(String type, String sqlState, List<String> applicationCallers) { }

    /** 显式commit只计JDBC成功调用；数据库所有隐式/显式事务另由pg_stat_database窗口差值报告。 */
    record Snapshot(long connections, long acquireFailures, long sqlExecuteCalls, long batchResultElements, long sqlFailures,
            long successfulExplicitCommitCalls, long successfulRollbackCalls, List<Long> connectionAcquisitionNanos,
            List<Failure> failures, int omittedFailures) { }
}
