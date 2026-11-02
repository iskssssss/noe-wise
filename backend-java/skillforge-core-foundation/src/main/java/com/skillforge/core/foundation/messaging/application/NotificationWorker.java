package com.skillforge.core.foundation.messaging.application;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.messaging.integration.NotificationMailTransport;
import com.skillforge.core.foundation.messaging.persistence.DeliveryRow;
import com.skillforge.core.foundation.messaging.persistence.MessagingMapper;
import com.skillforge.core.foundation.application.CryptoService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 有限租约邮件worker；领取与dispatching分别提交后才调用SMTP，处理结果通过token CAS写回。
 * 多宿主并行消费依赖数据库SKIP LOCKED；派发失租约转unknown不自动重发。
 */
public class NotificationWorker {
    private static final Logger LOG=LoggerFactory.getLogger(NotificationWorker.class);
    private final MessagingMapper mapper;
    private final NotificationMailTransport transport;
    private final CryptoService crypto;
    private final TransactionTemplate transactions;
    private final FoundationAccess access;

    /** 正式注入事务管理器，外部SMTP调用不占用业务数据库事务。 */
    public NotificationWorker(MessagingMapper mapper,NotificationMailTransport transport,CryptoService crypto,
            PlatformTransactionManager transactions,FoundationAccess access) {
        this.mapper=mapper; this.transport=transport; this.crypto=crypto; this.transactions=new TransactionTemplate(transactions); this.access=access;
    }

    /** 后台有界批次；未启用邮箱不会产生pending，无默认外发行为。 */
    @Scheduled(fixedDelayString="${skillforge.foundation.messaging.poll-interval-ms:5000}")
    public void poll() {
        String previous=TenantContext.getTenantId();
        try {
            TenantContext.clear();
            for(String scope:mapper.dueScopes()) {
                if(!scope.equals("platform") && !scope.matches("tenant:[1-9][0-9]{0,18}")) throw new IllegalStateException("非法投递作用域");
                TenantContext.setTenantId(NotificationService.tenant(scope));
                for(int count=0;count<10;count++) if(!processOne(scope)) break;
            }
        } catch(RuntimeException failure) {
            // 只记录异常类型；SQL/SMTP原因链可能携带秘密或正文，不进入普通日志。
            LOG.warn("消息投递批次失败，异常类型={}",failure.getClass().getSimpleName());
        } finally { if(previous==null) TenantContext.clear(); else TenantContext.setTenantId(previous); }
    }

    /**
     * 处理一条本作用域记录，供调度与正式集成验收复用；调用者必须绑定同租户上下文。
     * @return 是否领取记录，false表示当前暂无待处理记录
     */
    public boolean processOne(String scope) {
        if(!java.util.Objects.equals(NotificationService.tenant(scope),TenantContext.getTenantId())) throw new IllegalStateException("worker租户绑定不匹配");
        DeliveryRow row=transactions.execute(status->{ mapper.recoverExpired(scope); return mapper.claim(scope,UUID.randomUUID().toString(),60); });
        if(row==null) return false;
        var message=mapper.message(scope,row.notificationId()); var mailbox=mapper.mailbox(scope,row.mailboxId());
        var current=mapper.currentMailbox(scope);
        NotificationMailTransport.Result result;
        if(message==null || mailbox==null || current==null || !current.enabled() || !mailbox.enabled()) {
            result=new NotificationMailTransport.Result("blocked","SMTP_CONFIGURATION_DISABLED");
        } else {
            var dispatched=new java.util.concurrent.atomic.AtomicBoolean();
            try {
                String subject;String body;
                if(message.announcementId()!=null){
                    // 精确公告身份是共享正文所有者；不能用当前模板或仅摘要相同的他人正文替代。
                    var source=mapper.announcement(scope,message.announcementId());
                    if(source==null||!"text/plain;charset=utf-8".equals(source.bodyFormat())||source.bodyCipher()==null)throw new IllegalStateException("公告正文引用损坏");
                    subject=source.title();body=crypto.decrypt(source.bodyCipher());
                    if(!NotificationService.bodyDigest(body).equals(source.bodyDigest()))throw new IllegalStateException("公告正文摘要不一致");
                }else{subject=crypto.decrypt(message.emailSubjectCipher());body=crypto.decrypt(message.emailBodyCipher());}
                result=transport.deliver(mailbox,message.recipientEmail(),subject,body,()->{
                    // 公告派发沿用当前发布人授权；已受理正文冻结不能让撤权后的后台继续越权发送。
                    if(message.announcementId()!=null) {
                        var announcement=mapper.announcement(scope,message.announcementId());
                        if(announcement==null || !announcement.status().equals("published")) throw new IllegalStateException("公告已撤回");
                        access.require(new FoundationAccess.Subject(scope.equals("platform"),NotificationService.tenant(scope),announcement.createdBy()),"foundation.messaging.publish");
                    }
                    Boolean owned=transactions.execute(status->mapper.dispatch(scope,row.id(),row.leaseToken())==1);
                    if(!Boolean.TRUE.equals(owned)) throw new IllegalStateException("邮件租约已失效或公告已撤回");
                    dispatched.set(true);
                });
            } catch(RuntimeException failure) { result=new NotificationMailTransport.Result(dispatched.get()?"unknown":"rejected",dispatched.get()?"SMTP_RESULT_UNKNOWN":"SMTP_PRE_DISPATCH_REJECTED"); }
        }
        var completed=result;
        transactions.executeWithoutResult(status->{
            if(mapper.finish(scope,row.id(),row.leaseToken(),completed.status(),completed.errorCode())==1)
                mapper.attempt(Long.toString(SnowflakeIds.nextId()),scope,NotificationService.tenant(scope),row.id(),row.leaseToken(),completed.status(),completed.errorCode(),null);
        });
        return true;
    }
}
