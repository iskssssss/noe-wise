package com.skillforge.core.shared.config;

import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Mapper 方法的结构化执行日志，不读取 SQL、参数或返回数据。
 *
 * <p>抽象 Mapper 没有方法体，统一在执行器边界记录入口和出口。执行编号仅用于日志关联，
 * 不充当业务标识；异常只记录类型，避免数据库错误消息泄露参数。
 */
@Component
@Intercepts({
    @Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                    CacheKey.class, BoundSql.class}),
    @Signature(type = Executor.class, method = "queryCursor",
            args = {MappedStatement.class, Object.class, RowBounds.class}),
    @Signature(type = Executor.class, method = "update",
            args = {MappedStatement.class, Object.class})
})
public final class MapperExecutionLoggingInterceptor implements Interceptor {
    private static final Logger LOG = LoggerFactory.getLogger(MapperExecutionLoggingInterceptor.class);
    private static final AtomicLong EXECUTIONS = new AtomicLong();

    /**
     * 在当前调用线程记录 Mapper 执行，不改变事务、结果或异常。
     *
     * @param invocation MyBatis 执行器调用，参数包含已解析的 Mapper 语句
     * @return 原执行器结果；游标返回只代表游标创建完成，不代表消费完成
     * @throws Throwable 原执行异常，不附带敏感参数重新包装
     */
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
        long execution = EXECUTIONS.incrementAndGet();
        long started = System.nanoTime();
        String outcome = "failed";
        LOG.debug("Mapper执行进入 execution={} statement={} command={}",
                execution, statement.getId(), statement.getSqlCommandType());
        try {
            Object result = invocation.proceed();
            outcome = "returned";
            return result;
        } catch (Throwable failure) {
            LOG.warn("Mapper执行失败 execution={} statement={} errorType={}",
                    execution, statement.getId(), failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("Mapper执行退出 execution={} statement={} outcome={} elapsedNanos={}",
                    execution, statement.getId(), outcome, System.nanoTime() - started);
        }
    }
}
