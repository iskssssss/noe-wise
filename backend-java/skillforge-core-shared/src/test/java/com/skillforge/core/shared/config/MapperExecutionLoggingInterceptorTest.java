package com.skillforge.core.shared.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.InvocationTargetException;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Invocation;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** 证明结构化诊断不会输出 SQL 参数或异常载荷，也不会改变执行结果。 */
class MapperExecutionLoggingInterceptorTest {
    /** 正常与失败出口均可按执行编号关联，敏感载荷始终不进入日志。 */
    @Test
    void tracesBothOutcomesWithoutSensitiveParametersOrExceptionMessages() throws Throwable {
        Logger logger = (Logger) LoggerFactory.getLogger(MapperExecutionLoggingInterceptor.class);
        Level previous = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            Executor executor = mock(Executor.class);
            MappedStatement statement = mock(MappedStatement.class);
            when(statement.getId()).thenReturn("example.SafeMapper.write");
            when(statement.getSqlCommandType()).thenReturn(SqlCommandType.UPDATE);
            String secret = "private-session-body-and-token";
            when(executor.update(statement, secret)).thenReturn(1)
                    .thenThrow(new IllegalStateException(secret));
            var invocation = new Invocation(executor,
                    Executor.class.getMethod("update", MappedStatement.class, Object.class),
                    new Object[] {statement, secret});
            var interceptor = new MapperExecutionLoggingInterceptor();
            assertEquals(1, interceptor.intercept(invocation));
            assertThrows(InvocationTargetException.class, () -> interceptor.intercept(invocation));
            assertEquals(5, appender.list.size());
            assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN));
            assertTrue(appender.list.stream().allMatch(event -> event.getFormattedMessage().contains("execution=")));
            assertFalse(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains(secret)));
            assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }
}
