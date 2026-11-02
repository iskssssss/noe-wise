package com.skillforge.runtime.adapter.jdbc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import javax.sql.DataSource;

/** 状态事件领取测试只统计应用显式 JDBC 执行，不把服务器事务或等待推算为 SQL。 */
public final class StateEventClaimJdbcCounter {
    private final DataSource delegate;
    private int connections;
    private int executions;

    public StateEventClaimJdbcCounter(DataSource delegate) {
        this.delegate = delegate;
    }

    public int connections() { return connections; }
    public int executions() { return executions; }

    public DataSource source() {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if (!method.getName().equals("getConnection")) {
                        return result;
                    }
                    connections++;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class}, (connection, call, values) -> {
                                Object statement = invoke(result, call, values);
                                if (!call.getName().equals("prepareStatement")) {
                                    return statement;
                                }
                                return Proxy.newProxyInstance(
                                        PreparedStatement.class.getClassLoader(),
                                        new Class<?>[] {PreparedStatement.class},
                                        (prepared, execution, parameters) -> {
                                            if (execution.getName().startsWith("execute")) {
                                                executions++;
                                            }
                                            return invoke(statement, execution, parameters);
                                        });
                            });
                });
    }

    private static Object invoke(Object target, Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
