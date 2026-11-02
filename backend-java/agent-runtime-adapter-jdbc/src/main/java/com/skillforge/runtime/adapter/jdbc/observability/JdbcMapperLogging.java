package com.skillforge.runtime.adapter.jdbc.observability;

import java.util.UUID;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/** 在 Mapper 执行边界记录起止与耗时；不记录 SQL、绑定参数、结果载荷或异常正文。 */
@Intercepts({
    @Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}),
    @Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class})
})
public final class JdbcMapperLogging implements Interceptor {
    private static final System.Logger LOG=System.getLogger(JdbcMapperLogging.class.getName());

    /** 调用标识仅关联本次访问的入口与出口；失败保留原异常，交由最终处理边界记录诊断。 */
    @Override public Object intercept(Invocation invocation) throws Throwable {
        if(!LOG.isLoggable(System.Logger.Level.DEBUG))return invocation.proceed();
        String operation=((MappedStatement)invocation.getArgs()[0]).getId();
        String accessTag=UUID.randomUUID().toString();
        long started=System.nanoTime();
        LOG.log(System.Logger.Level.DEBUG,"数据库访问开始 operation={0} accessTag={1}",operation,accessTag);
        try {
            Object result=invocation.proceed();
            LOG.log(System.Logger.Level.DEBUG,"数据库访问完成 operation={0} accessTag={1} elapsedMicros={2}",operation,accessTag,(System.nanoTime()-started)/1_000);
            return result;
        } catch(Throwable failure) {
            LOG.log(System.Logger.Level.DEBUG,"数据库访问退出 operation={0} accessTag={1} outcome=failed elapsedMicros={2}",operation,accessTag,(System.nanoTime()-started)/1_000);
            throw failure;
        }
    }
}
