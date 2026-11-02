package com.skillforge.core.foundation.messaging.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.messaging.contract.NotificationCommands;
import com.skillforge.core.foundation.messaging.contract.NotificationRecipientDirectory;
import com.skillforge.core.foundation.messaging.contract.NotificationResults;
import com.skillforge.core.foundation.messaging.persistence.AnnouncementRow;
import com.skillforge.core.foundation.messaging.persistence.DeliveryRow;
import com.skillforge.core.foundation.messaging.persistence.MailboxRow;
import com.skillforge.core.foundation.messaging.persistence.MessageRow;
import com.skillforge.core.foundation.messaging.persistence.MessagingMapper;
import com.skillforge.core.foundation.messaging.persistence.TemplateRow;
import com.skillforge.core.foundation.application.CryptoService;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息领域公开应用入口。管理操作实时授权，内部enqueue只接受调用业务确认的作用域和邮箱。
 * 所有写入参与调用事务；加密冻结正文与outbox原子提交，受理返回值绝不表示SMTP已接受。
 */
public class NotificationService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(NotificationService.class);
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([a-z][a-z0-9_]*)}}");
    private final MessagingMapper mapper;
    private final FoundationAccess access;
    private final NotificationRecipientDirectory recipients;
    private final CryptoService crypto;
    private final ObjectMapper json;

    /** 通过宿主正式注入入口提供授权、身份目录、加密与事务持久化。 */
    public NotificationService(MessagingMapper mapper, FoundationAccess access,
            NotificationRecipientDirectory recipients, CryptoService crypto, ObjectMapper json) {
        this.mapper=mapper; this.access=access; this.recipients=recipients; this.crypto=crypto; this.json=json;
    }

    /** HTTP宿主必须与实时主体账号域一致，平台token不能消费机构接口。 */
    public void requireHost(boolean platform) {
        if (access.currentSubject().platform()!=platform) throw failure(HttpStatus.FORBIDDEN,"MESSAGING_REALM_MISMATCH","账号域不匹配");
    }

    /**
     * 公开可信内部通知入口；同事务写入与幂等回放，未配置SMTP仍保存blocked状态。
     * 安全模板禁止站内正文，防止一次性token通过管理查询泄漏。
     */
    @Transactional
    public String enqueue(NotificationCommands.Enqueue command) {
        validateScope(command.scopeKey());
        String key=text(command.deduplicationKey(),240,"幂等键");
        String templateKey=text(command.templateKey(),128,"模板键");
        if (!command.inApp() && !command.email()) throw invalid("至少选择一种通知渠道");
        if (command.inApp() && (command.recipientMemberId()==null || templateKey.startsWith("security."))) throw invalid("安全通知仅允许邮件投递");
        if (command.inApp()) id(command.recipientMemberId());
        if (command.email()) email(command.recipientEmail());
        validateVariables(command.variables());
        String fingerprint=hash(List.of(templateKey,command.recipientMemberId()==null?"":command.recipientMemberId(),
                command.recipientEmail()==null?"":command.recipientEmail(),command.inApp(),command.email(),new TreeMap<>(command.variables())));
        mapper.lockScope(command.scopeKey());
        mapper.ensureHistoryRetention(command.scopeKey());
        MessageRow existing=mapper.messageByKey(command.scopeKey(),key);
        if(existing!=null) return replay(existing.id(),existing.fingerprint(),fingerprint);
        TemplateRow template=mapper.template(command.scopeKey(),templateKey);
        if(template==null) template=builtin(command.scopeKey(),templateKey);
        if(template==null) throw invalid("模板尚未发布");
        Set<String> required=Set.copyOf(names(template.variableNames()));
        if(!required.equals(command.variables().keySet())) throw invalid("模板变量必须与声明一致");
        return persist(command.scopeKey(),command.recipientMemberId(),command.recipientEmail(),key,fingerprint,
                template.id(),render(template.subject(),command.variables()),render(template.body(),command.variables()),null,command.inApp(),command.email());
    }

    /** 发布公告和冻结全部收件人同事务提交；并发重发只产生一个公告。 */
    @Transactional
    public String publish(NotificationCommands.Publish command) {
        access.require("foundation.messaging.publish"); String scope=currentScope();
        String title=text(command.title(),240,"标题");noHeaders(title); String body=body(command.body());
        String key=text(command.deduplicationKey(),160,"幂等键");
        if(command.audience()==null || !Set.of("all","members","departments","roles").contains(command.audience())) throw invalid("公告范围非法");
        if(command.recipientIds().size()>1000 || (command.audience().equals("all") && !command.recipientIds().isEmpty())
                || (!command.audience().equals("all") && command.recipientIds().isEmpty())) throw invalid("请选择有效收件范围");
        String fingerprint=hash(List.of(title,body,command.audience(),command.recipientIds().stream().distinct().sorted().toList(),command.email()));
        mapper.lockScope(scope);mapper.ensureHistoryRetention(scope); AnnouncementRow existing=mapper.announcementByKey(scope,key);
        if(existing!=null) return replay(existing.id(),existing.fingerprint(),fingerprint);
        var targets=recipients.resolve(scope,command.audience(),command.recipientIds());
        if(targets.isEmpty() || targets.size()>10000) throw invalid("有效收件人数须在1至10000之间");
        if(targets.stream().map(NotificationRecipientDirectory.Recipient::memberId).distinct().count()!=targets.size()) throw new IllegalStateException("收件目录返回重复成员");
        String announcement=id(); Instant now=Instant.now();
        mapper.insertAnnouncement(new AnnouncementRow(announcement,scope,tenant(scope),title,crypto.encrypt(body),command.audience(),targets.size(),"published",key,fingerprint,access.currentSubject().memberId(),now,"text/plain;charset=utf-8",bodyDigest(body)));
        for(var target:targets) {
            boolean sendEmail=command.email() && target.email()!=null && !target.email().isBlank();
            if(sendEmail) email(target.email());
            persist(scope,target.memberId(),target.email(),"announcement:"+announcement+":"+target.memberId(),
                    fingerprint,null,title,body,announcement,true,sendEmail);
        }
        return announcement;
    }

    /** 撤回隐藏站内正文并取消未派发邮件；已接受/unknown事实保持历史意义。 */
    @Transactional
    public void withdraw(String announcementId) {
        access.require("foundation.messaging.publish"); String scope=currentScope(); id(announcementId);
        mapper.lockScope(scope);
        if(mapper.withdraw(scope,announcementId)!=1) throw conflict("公告不存在或已经撤回");
        mapper.cancelAnnouncement(scope,announcementId);
    }

    /** 仅当前成员自身站内消息；分页有界，禁止调用者传入他人成员身份。 */
    public NotificationResults.Page<NotificationResults.Inbox> inbox(String cursor,int limit,boolean unread) {
        access.require("foundation.messaging.inbox"); String scope=currentScope(); page(cursor,limit);
        var rows=mapper.inbox(scope,access.currentSubject().memberId(),cursor,limit+1,unread);
        return page(rows,limit,rows.stream().map(r->new NotificationResults.Inbox(r.id(),r.title(),r.announcementId()==null?r.body():crypto.decrypt(r.emailBodyCipher()),r.readAt(),r.createdAt())).toList(),MessageRow::id);
    }

    /** 当前成员实时未读数，不包括撤回公告。 */
    public long unread() { access.require("foundation.messaging.inbox"); return mapper.unread(currentScope(),access.currentSubject().memberId()); }

    /** 已读更新幂等且受同作用域同成员约束；跨租户ID与不存在ID均返回404。 */
    @Transactional
    public void markRead(String notificationId) {
        access.require("foundation.messaging.inbox"); id(notificationId);
        if(mapper.markRead(currentScope(),access.currentSubject().memberId(),notificationId)!=1) throw failure(HttpStatus.NOT_FOUND,"NOTIFICATION_NOT_FOUND","消息不存在");
    }

    /** 管理读取同作用域公告历史。 */
    public NotificationResults.Page<NotificationResults.Announcement> announcements(String cursor,int limit) {
        access.require("foundation.messaging.read"); page(cursor,limit);
        var rows=mapper.announcements(currentScope(),cursor,limit+1);
        return page(rows,limit,rows.stream().map(r->new NotificationResults.Announcement(r.id(),r.title(),r.bodyCipher()==null?null:crypto.decrypt(r.bodyCipher()),r.audience(),r.recipients(),r.status(),r.createdAt())).toList(),AnnouncementRow::id);
    }

    /** 完整发布模板版本；变量声明与所有占位符必须一致，不支持代码或表达式执行。 */
    @Transactional
    public String publishTemplate(NotificationCommands.Template command) {
        access.require("foundation.messaging.configure"); String scope=currentScope();
        String key=text(command.key(),128,"模板键");
        if(!key.matches("[a-z][a-z0-9_.-]*")) throw invalid("模板键格式非法");
        String subject=text(command.subject(),240,"标题模板"); noHeaders(subject); String body=body(command.body());
        var variables=command.variableNames();
        if(variables.size()>32 || variables.stream().anyMatch(v->v==null || !v.matches("[a-z][a-z0-9_]{0,63}")) || Set.copyOf(variables).size()!=variables.size()) throw invalid("变量名非法或重复");
        Set<String> declared=Set.copyOf(variables); var actual=new java.util.HashSet<String>();
        VARIABLE.matcher(subject+"\n"+body).results().forEach(m->actual.add(m.group(1)));
        if(!actual.equals(declared) || subject.replaceAll(VARIABLE.pattern(),"").contains("{{") || body.replaceAll(VARIABLE.pattern(),"").contains("{{")) throw invalid("模板占位符与变量声明不一致");
        mapper.lockScope(scope);mapper.ensureHistoryRetention(scope); mapper.retireTemplate(scope,key); String version=id();
        mapper.insertTemplate(new TemplateRow(version,scope,tenant(scope),key,subject,body,String.join(",",variables),true,Instant.now()));
        return version;
    }

    /** 当前模板查询仅返回定义，不返回受理变量。 */
    public List<NotificationResults.Template> templates() {
        access.require("foundation.messaging.configure");
        return mapper.templates(currentScope()).stream().map(r->new NotificationResults.Template(r.id(),r.templateKey(),r.subject(),r.body(),names(r.variableNames()),r.createdAt())).toList();
    }

    /** 完整发布邮箱新版本；密码只加密保存，启用前必须完整配置。 */
    @Transactional
    public void configureMailbox(NotificationCommands.Mailbox command) {
        access.require("foundation.messaging.configure"); String scope=currentScope(); mapper.lockScope(scope);mapper.ensureHistoryRetention(scope);
        MailboxRow previous=mapper.currentMailbox(scope);
        String password=command.password();
        if(password!=null && (password.isEmpty() || password.length()>4096)) throw invalid("SMTP密码长度非法");
        String cipher=password==null ? (previous==null?null:previous.passwordCipher()) : crypto.encrypt(password);
        if(command.enabled()) {
            text(command.host(),256,"SMTP主机"); text(command.username(),256,"SMTP账号"); noHeaders(command.host()); noHeaders(command.username());
            if(!command.host().matches("[a-zA-Z0-9][a-zA-Z0-9.-]{0,255}")) throw invalid("SMTP主机格式非法");
            if(command.port()==null || command.port()<1 || command.port()>65535) throw invalid("SMTP端口非法");
            email(command.fromAddress());
            if(command.tlsMode()==null || !Set.of("starttls","tls").contains(command.tlsMode()) || cipher==null) throw invalid("启用SMTP需要TLS模式和加密凭据");
        } else if(command.tlsMode()!=null && !Set.of("starttls","tls").contains(command.tlsMode())) throw invalid("TLS模式非法");
        mapper.retireMailbox(scope);
        mapper.insertMailbox(new MailboxRow(id(),scope,tenant(scope),command.host(),command.port(),command.username(),cipher,command.fromAddress(),command.tlsMode(),command.enabled(),true,Instant.now()));
    }

    /** 查询邮箱安全投影，永不回传密文或明文密码。 */
    public NotificationResults.Mailbox mailbox() {
        access.require("foundation.messaging.configure"); var row=mapper.currentMailbox(currentScope());
        return row==null ? new NotificationResults.Mailbox(false,false,null,null,null,null,null,false)
                : new NotificationResults.Mailbox(true,row.enabled(),row.host(),row.port(),row.username(),row.fromAddress(),row.tlsMode(),row.passwordCipher()!=null);
    }

    /** 邮件查询仅返回状态；accepted语义为SMTP接受，不能展示为已送达。 */
    public NotificationResults.Page<NotificationResults.Delivery> deliveries(String cursor,int limit) {
        access.require("foundation.messaging.operate"); page(cursor,limit); var rows=mapper.deliveries(currentScope(),cursor,limit+1);
        return page(rows,limit,rows.stream().map(r->new NotificationResults.Delivery(r.id(),r.notificationId(),r.status(),r.errorCode(),r.attempts(),r.updatedAt())).toList(),DeliveryRow::id);
    }

    /** 仅对确定未发送的blocked/rejected人工重试；unknown必须先核验。 */
    @Transactional
    public void retry(String deliveryId) {
        access.require("foundation.messaging.operate"); id(deliveryId); String scope=currentScope();
        var row=mapper.lockDelivery(scope,deliveryId); if(row==null) throw conflict("投递记录不存在");
        var mailbox=mapper.currentMailbox(scope); if(mailbox==null || !mailbox.enabled()) throw conflict("请先启用邮箱配置");
        if(mapper.retry(scope,deliveryId,mailbox.id())!=1) throw conflict("当前状态不允许重试；结果未知须先核验");
        mapper.readyScope(scope);
        mapper.attempt(id(),scope,tenant(scope),deliveryId,null,"pending","OPERATOR_RETRY",null);
    }

    /** unknown仅可根据实际服务端核验事实处置，证据必填，不能直接重新派发。 */
    @Transactional
    public void reconcile(String deliveryId,String status,String evidence) {
        access.require("foundation.messaging.operate"); id(deliveryId); String scope=currentScope();
        if(status==null || !Set.of("accepted","rejected").contains(status)) throw invalid("核验结果必须为SMTP已接受或明确未接受");
        text(evidence,1024,"核验证据"); mapper.lockDelivery(scope,deliveryId);
        if(mapper.reconcile(scope,deliveryId,status)!=1) throw conflict("仅结果未知的记录允许核验");
        mapper.attempt(id(),scope,tenant(scope),deliveryId,null,status,"OPERATOR_VERIFIED",evidence);
    }
    /** 查询同范围生命周期策略；首次登记已批准30日默认，显式关闭才为NULL。 */
    @Transactional
    public NotificationResults.Retention historyRetention(){access.require("foundation.messaging.configure");String scope=currentScope();mapper.lockScope(scope);mapper.ensureHistoryRetention(scope);return new NotificationResults.Retention(mapper.historyRetention(scope));}
    /** 发布显式终态期限；同scope锁与发布/退休共享，NULL关闭且不擅自删除幂等墓碑。 */
    @Transactional
    public NotificationResults.Retention setHistoryRetention(Integer days){access.require("foundation.messaging.configure");if(days!=null&&(days<1||days>36500))throw invalid("消息保留天数必须在1至36500之间，NULL表示关闭");String scope=currentScope();mapper.lockScope(scope);mapper.setHistoryRetention(scope,days);return new NotificationResults.Retention(days);}
    /** 调查保护绑定精确通知而不是摘要；退休后材料已不存在，拒绝伪称保护成功。 */
    @Transactional
    public void protectMessage(String notificationId,boolean protect){access.require("foundation.messaging.operate");id(notificationId);String scope=currentScope();mapper.lockScope(scope);if(mapper.protectMessage(scope,notificationId,protect)==0)throw failure(HttpStatus.NOT_FOUND,"NOTIFICATION_NOT_FOUND","消息不存在或材料已退休");}
    /** 有界退休只释放终态无引用材料；UNKNOWN及可重试状态始终保持正文和尝试事实。 */
    @Transactional
    public int cleanupHistory(String scope){validateScope(scope);mapper.lockScope(scope);int attempts=mapper.pruneAttempts(scope);int notifications=mapper.retireMessages(scope);int announcements=mapper.retireAnnouncements(scope);int released=mapper.releaseRetiredMailboxReferences(scope);int templates=mapper.pruneRetiredTemplates(scope);int mailboxes=mapper.pruneRetiredMailboxes(scope);int changed=attempts+notifications+announcements+released+templates+mailboxes;if(changed>0)log.info("消息材料回收 scope={} attempt_rows={} notification_rows={} announcement_rows={} mailbox_references={} template_rows={} mailbox_rows={}",scope,attempts,notifications,announcements,released,templates,mailboxes);return changed;}

    /** 冻结通知和精确正文引用；共享公告由公告持有一次，个性化邮件及安全变量仍加密独立保存。 */
    private String persist(String scope,String member,String email,String key,String fingerprint,String template,
            String title,String body,String announcement,boolean inApp,boolean sendEmail) {
        if(announcement==null){text(title,240,"通知标题");noHeaders(title);body(body);}
        String notification=id(); Instant now=Instant.now();
        mapper.insertMessage(new MessageRow(notification,scope,tenant(scope),member,email,key,fingerprint,template,
                announcement==null&&inApp?title:null,announcement==null&&inApp?body:null,announcement==null&&sendEmail?crypto.encrypt(title):null,announcement==null&&sendEmail?crypto.encrypt(body):null,
                announcement,inApp,null,now));
        if(sendEmail) {
            var mailbox=mapper.currentMailbox(scope); boolean enabled=mailbox!=null && mailbox.enabled();
            mapper.insertDelivery(new DeliveryRow(id(),scope,tenant(scope),notification,enabled?mailbox.id():null,
                    enabled?"pending":"blocked",enabled?null:"SMTP_NOT_CONFIGURED",0,null,null,now));
            if(enabled) mapper.readyScope(scope);
        }
        return notification;
    }

    /** 内置安全模板首次使用时追加到本租户，不跨租户借用平台定义。 */
    private TemplateRow builtin(String scope,String key) {
        String subject; String body;
        if(key.equals("security.invitation")) { subject="灵知 账号邀请"; body="{{username}}，您已受邀加入 {{tenant_slug}}。一次性激活码：{{token}}。有效期至 {{expires_at}}。"; }
        else if(key.equals("security.password_recovery")) { subject="灵知 密码恢复"; body="{{username}}，您申请恢复 {{tenant_slug}} 的账号。一次性恢复码：{{token}}。有效期至 {{expires_at}}。"; }
        else return null;
        var row=new TemplateRow(id(),scope,tenant(scope),key,subject,body,"username,token,expires_at,tenant_slug",true,Instant.now());
        mapper.insertTemplate(row); return row;
    }

    /** 管理作用域始终来自实时可信主体，不接受HTTP传入租户。 */
    private String currentScope() {
        var subject=access.currentSubject(); String scope=subject.platform()?"platform":"tenant:"+subject.tenantId();
        validateScope(scope); return scope;
    }
    /** 内部可信调用仍必须匹配RLS租户绑定，阻止跨租户受理。 */
    private static void validateScope(String scope) {
        if(scope==null || !(scope.equals("platform") || scope.matches("tenant:[1-9][0-9]{0,18}"))) throw invalid("通知作用域非法");
        if(!scope.equals("platform")) id(tenant(scope));
        if(!java.util.Objects.equals(tenant(scope),TenantContext.getTenantId())) throw failure(HttpStatus.FORBIDDEN,"MESSAGING_SCOPE_MISMATCH","通知作用域与当前租户不一致");
    }
    /** 平台scope无租户，机构scope携带可信十进制ID。 */
    static String tenant(String scope) { return scope.equals("platform")?null:scope.substring(7); }
    /** 所有业务ID以十进制字符串传输。 */
    private static String id() { return Long.toString(SnowflakeIds.nextId()); }
    /** 校验游标/对象ID，避免数据库转换异常及宽松类型。 */
    private static void id(String value) {
        if(value==null || !value.matches("[1-9][0-9]{0,18}")) throw invalid("ID必须为有效十进制字符串");
        try { Long.parseLong(value); } catch(NumberFormatException invalid) { throw invalid("ID超出范围"); }
    }
    /** 分页边界拒绝非法值，不夹紧。 */
    private static void page(String cursor,int limit) { if(cursor!=null) id(cursor); if(limit<1 || limit>100) throw invalid("分页大小必须在1至100之间"); }
    /** 构造真实hasMore投影，不暴露取回的探测行。 */
    private static <T,R> NotificationResults.Page<R> page(List<T> rows,int limit,List<R> views,java.util.function.Function<T,String> key) {
        boolean more=rows.size()>limit; int count=Math.min(rows.size(),limit);
        return new NotificationResults.Page<>(List.copyOf(views.subList(0,count)),more,more?key.apply(rows.get(count-1)):null);
    }
    /** 严格文本长度校验。 */
    private static String text(String value,int max,String field) { if(value==null || value.isBlank() || value.length()>max) throw invalid(field+"为空或超长"); return value; }
    /** 正文按UTF-8字节数与数据库一致限制。 */
    private static String body(String value) { text(value,131072,"正文"); if(value.getBytes(StandardCharsets.UTF_8).length>131072) throw invalid("正文过长"); return value; }
    /** 禁止邮箱/标题/SMTP身份包含协议换行。 */
    private static void noHeaders(String value) { if(value.indexOf('\r')>=0 || value.indexOf('\n')>=0 || value.indexOf('\0')>=0) throw invalid("字段含非法控制字符"); }
    /** 只允许一个纯邮箱地址，避免发件人列表与头注入。 */
    private static void email(String value) {
        text(value,256,"邮箱"); noHeaders(value);
        try { var addresses=InternetAddress.parse(value,true); if(addresses.length!=1 || !addresses[0].getAddress().equals(value)) throw invalid("邮箱格式非法"); addresses[0].validate(); }
        catch(AddressException error) { throw invalid("邮箱格式非法"); }
    }
    /** 模板变量封闭校验，敏感值不记录在错误文案。 */
    private static void validateVariables(Map<String,String> variables) {
        if(variables.size()>32 || variables.entrySet().stream().anyMatch(e->!e.getKey().matches("[a-z][a-z0-9_]{0,63}") || e.getValue().length()>16384)) throw invalid("模板变量非法或过长");
    }
    /** 仅做文本占位替换，无脚本求值或递归变量展开。 */
    private static String render(String template,Map<String,String> variables) {
        var matcher=VARIABLE.matcher(template); var result=new StringBuilder();
        while(matcher.find()) matcher.appendReplacement(result,java.util.regex.Matcher.quoteReplacement(variables.get(matcher.group(1))));
        matcher.appendTail(result); return result.toString();
    }
    /** 变量声明使用逗号分隔的固定标识符。 */
    private static List<String> names(String value) { return value.isEmpty()?List.of():List.of(value.split(",")); }
    /** 对规范化受理语义取摘要，幂等查询只比较摘要。 */
    private String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value))); }
        catch(JsonProcessingException | java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("消息摘要失败",error); }
    }
    /** 共享正文摘要按声明的UTF-8原字节计算，读取及投递不得从当前模板重建。 */
    static String bodyDigest(String body){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException("公告正文摘要失败",failure);}}
    /** 同幂等键仅相同语义可以回放。 */
    private static String replay(String id,String original,String incoming) { if(!original.equals(incoming)) throw conflict("相同幂等键已用于不同消息"); return id; }
    /** 明确领域校验错误不应自动重试。 */
    private static ApiException invalid(String message) { return failure(HttpStatus.BAD_REQUEST,"MESSAGING_INVALID_INPUT",message); }
    /** 状态竞争或幂等冲突由调用方重新读取事实。 */
    private static ApiException conflict(String message) { return failure(HttpStatus.CONFLICT,"MESSAGING_CONFLICT",message); }
    /** 使用既有API错误契约。 */
    private static ApiException failure(HttpStatus status,String code,String message) { return new ApiException(status,code,message,false); }
}
