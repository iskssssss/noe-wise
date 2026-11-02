package com.skillforge.core.knowledge.integration.runtime.knowledge;

import com.skillforge.runtime.api.port.InvocationControl;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;

/** 只对当前关键词查询持有的 Statement 取消；SQL 执行和结果映射仍归 MyBatis。 */
@Intercepts(@Signature(type=StatementHandler.class,method="query",args={Statement.class,ResultHandler.class}))
final class KnowledgeQueryCancellation implements Interceptor {
    private static final System.Logger LOG=System.getLogger(KnowledgeQueryCancellation.class.getName());
    private final ThreadLocal<Query> active = new ThreadLocal<>();

    /** 在当前线程安装本次查询控制；返回 Mapper 结果，异常原样传播，退出时恢复外层控制。 */
    <T> T execute(InvocationControl control, Instant deadline, Supplier<T> query) {
        var previous=active.get();
        var current=new Query(control,deadline,UUID.randomUUID().toString());
        LOG.log(System.Logger.Level.DEBUG,"知识查询控制开始 queryTag={0}",current.queryTag());
        active.set(current);
        try { return query.get(); }
        finally {
            if(previous==null)active.remove();else active.set(previous);
            LOG.log(System.Logger.Level.DEBUG,"知识查询控制结束 queryTag={0}",current.queryTag());
        }
    }

    /** 只拦截已安装控制的查询；取消线程操作真实 Statement，退出前必须等待其静止。 */
    @Override public Object intercept(Invocation invocation) throws Throwable {
        var query=active.get();
        if(query==null)return invocation.proceed();
        query.check();
        var statement=(Statement)invocation.getArgs()[0];
        var done=new AtomicBoolean();
        LOG.log(System.Logger.Level.DEBUG,"知识查询执行开始 queryTag={0}",query.queryTag());
        Thread cancellation=Thread.ofVirtual().name("knowledge-read-cancellation").start(()->{
            // 轮询只观察单调取消与截止时间；仅记录首次取消和首次失败，不每轮输出日志。
            boolean cancellationLogged=false;
            boolean failureLogged=false;
            try {
                while(!done.get()) {
                    if(query.control().cancellationSignal().isCancellationRequested() || !Instant.now().isBefore(query.deadline())) {
                        if(!cancellationLogged) {
                            LOG.log(System.Logger.Level.DEBUG,"知识查询请求取消 queryTag={0}",query.queryTag());
                            cancellationLogged=true;
                        }
                        try { statement.cancel(); }
                        catch(SQLException failure) {
                            if(!failureLogged) {
                                LOG.log(System.Logger.Level.WARNING,"知识查询取消暂未确认 queryTag={0} code=statement_cancel_failed",query.queryTag());
                                failureLogged=true;
                            }
                        }
                    }
                    Thread.sleep(10);
                }
            } catch(InterruptedException finished) { Thread.currentThread().interrupt(); }
        });
        try {
            query.check();
            Object result=invocation.proceed();
            query.check();
            return result;
        } finally {
            done.set(true);
            cancellation.interrupt();
            // 取消线程退出后 MyBatis 才能释放 Statement，避免污染池中下一次查询。
            boolean interrupted=false;
            while(cancellation.isAlive()) {
                try { cancellation.join(); }
                catch(InterruptedException failure) { interrupted=true; }
            }
            LOG.log(System.Logger.Level.DEBUG,"知识查询执行结束 queryTag={0} cancellationStopped=true interrupted={1}",query.queryTag(),interrupted);
            if(interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalArgumentException("knowledge_query_cancelled");
            }
        }
    }

    private record Query(InvocationControl control,Instant deadline,String queryTag) {
        /** 在执行前后核验取消和最早截止时间；拒绝时只暴露稳定原因码，不返回迟到结果。 */
        void check() {
            LOG.log(System.Logger.Level.DEBUG,"知识查询控制核验开始 queryTag={0}",queryTag);
            try {
                if(control.cancellationSignal().isCancellationRequested())throw new IllegalArgumentException("knowledge_query_cancelled");
                if(!Instant.now().isBefore(deadline))throw new IllegalArgumentException("knowledge_query_deadline");
            } finally { LOG.log(System.Logger.Level.DEBUG,"知识查询控制核验结束 queryTag={0}",queryTag); }
        }
    }
}
