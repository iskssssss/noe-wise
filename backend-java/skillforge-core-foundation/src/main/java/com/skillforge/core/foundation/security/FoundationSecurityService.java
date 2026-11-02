package com.skillforge.core.foundation.security;

import cn.dev33.satoken.stp.SaLoginModel;
import cn.dev33.satoken.stp.StpUtil;
import com.skillforge.common.context.TenantContext;
import com.skillforge.common.time.BusinessTime;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.dto.auth.AuthResponse;
import com.skillforge.core.foundation.dto.auth.MeResponse;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.messaging.application.NotificationService;
import com.skillforge.core.foundation.messaging.contract.NotificationCommands;
import com.skillforge.core.foundation.security.RefreshTokens;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.foundation.application.CryptoService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 宿主安全权威入口：数据库会话同时约束 access/refresh，当前账号状态和密码指纹实时检查。
 * MFA、恢复码与邮件凭据消费使用数据库锁/CAS；失败不重新开放旧凭据。
 */
@Service
public class FoundationSecurityService {
    /** JWT只携带会话引用，权限和MFA事实以数据库当前状态为准。 */
    public static final String SESSION_CLAIM="security_session_id";
    private static final Duration STEP_UP=Duration.ofMinutes(5);
    private static final DefaultRedisScript<Long> ATTEMPT=new DefaultRedisScript<>("""
            local count=redis.call('INCR',KEYS[1])
            if count == 1 then redis.call('EXPIRE',KEYS[1],ARGV[1]) end
            return count
            """,Long.class);
    private final SecurityMapper mapper;
    private final FoundationAccess access;
    private final PasswordEncoder passwords;
    private final CryptoService crypto;
    private final SkillForgeProperties properties;
    private final StringRedisTemplate redis;
    private final NotificationService notifications;
    private final AuditService audit;

    /** 注入正式持久化、授权、凭据原语、投递和审计；不提供测试专用认证路径。 */
    public FoundationSecurityService(SecurityMapper mapper,FoundationAccess access,PasswordEncoder passwords,
            CryptoService crypto,SkillForgeProperties properties,StringRedisTemplate redis,
            NotificationService notifications,AuditService audit) {
        this.mapper=mapper;this.access=access;this.passwords=passwords;this.crypto=crypto;
        this.properties=properties;this.redis=redis;this.notifications=notifications;this.audit=audit;
    }

    /** 密码验证后默认创建完整会话；仅自愿绑定或显式全员策略进入MFA受限会话。 */
    @Transactional
    public AuthResponse login(boolean platform,String tenantSlug,String username,String password) {
        TenantContext.clear();
        String attempt="login:"+digest((platform?"platform":tenantSlug)+":"+username);
        limit(attempt,10,900);
        Long tenantId=platform?null:mapper.tenantBySlug(tenantSlug);
        if (!platform && tenantId==null) throw badCredentials(null,null);
        bind(platform,tenantId);
        SecurityRows.Account account=mapper.accountByName(platform,tenantId,username);
        if (account==null || !"active".equals(account.status()) || account.passwordHash()==null
                || password==null || !passwords.matches(password,account.passwordHash()))
            throw badCredentials(tenantId,account==null?null:account.id());
        redis.delete("foundation:security:attempt:"+attempt);
        var subject=subject(platform,tenantId,account.id());
        var snapshot=access.snapshot(subject);
        SecurityRows.Mfa mfa=mapper.mfa(scope(subject),account.id(),false);
        String state=mfa!=null && mfa.enabled()?"challenge":required(subject,snapshot)?"enrollment":"full";
        Instant now=now();
        SecurityRows.Session session=new SecurityRows.Session(SnowflakeIds.nextId(),scope(subject),account.id(),
                digest(account.passwordHash()),null,state,deviceLabel(),now,
                now.plus("full".equals(state)?Duration.ofDays(properties.getRefreshTokenExpireDays()):Duration.ofMinutes(10)),null,null);
        mapper.insertSession(session);
        event(subject,"auth.login","success",Long.toString(session.id()));
        return issue(account,session,snapshot);
    }

    /** 刷新凭据带明确账号体系前缀；验证账号、MFA和会话后CAS轮换，竞争失败不签发。 */
    @Transactional
    public AuthResponse refresh(boolean platform,String token) {
        Parsed parsed=parse(platform,token);
        bind(platform,parsed.tenantId());
        SecurityRows.Session session=mapper.refresh(parsed.scope(),digest(parsed.raw()));
        if(session==null) throw unauthorized();
        var subject=subject(platform,parsed.tenantId(),session.memberId());
        SecurityRows.Account account=validate(subject,session);
        var snapshot=access.snapshot(subject);
        if(!"full".equals(state(subject,session,snapshot))) throw mfaRequired();
        return issue(account,session,snapshot);
    }

    /** 当前会话验证不信任JWT中的role、MFA或授权版本。 */
    public SecurityRows.Session currentSession() {
        var subject=access.currentSubject();
        Object claim=StpUtil.getExtra(SESSION_CLAIM);
        if(claim==null) throw unauthorized();
        SecurityRows.Session session=mapper.session(scope(subject),id(claim.toString()));
        validate(subject,session);
        return session;
    }

    /** 当前登录身份投影用于两个后台的直接消费者。 */
    public MeResponse me() {
        var subject=access.currentSubject();var session=currentSession();var snapshot=access.snapshot(subject);
        var account=mapper.accountById(subject.platform(),tenant(subject),Long.parseLong(subject.memberId()));
        return new MeResponse(subject.memberId(),subject.tenantId(),account.username(),snapshot.roles(),snapshot.permissions(),
                snapshot.authorizationVersion(),state(subject,session,snapshot),Long.toString(session.id()));
    }

    /** 在业务入口限制尚未完成MFA的访问；策略发布或管理员授权变化立即生效。 */
    public void requireFull() {
        var subject=access.currentSubject();var session=currentSession();
        if(!"full".equals(state(subject,session,access.snapshot(subject)))) throw mfaRequired();
    }

    /** 当前HTTP入口仅从JWT解析会话引用，在短读取事务中验证同一主体及当前认证事实。 */
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public SecurityRows.Session verifyCurrentSession(FoundationAccess.Subject subject,boolean full,boolean stepUp) {
        if(!subject.equals(access.currentSubject())) throw unauthorized();
        Object claim=StpUtil.getExtra(SESSION_CLAIM);
        if(claim==null) throw unauthorized();
        return verifySession(subject,id(claim.toString()),full,stepUp);
    }

    /** 异步派发每次开启短读取事务，使用首次请求可信主体和会话引用重新认证。 */
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public void verifyTrustedSession(FoundationAccess.Subject subject,long sessionId,boolean full,boolean stepUp) {
        verifySession(subject,sessionId,full,stepUp);
    }

    /** 事实仅在本次认证中共用；不绑定全线程主体，也不把查询结果沿用到异步派发。 */
    private SecurityRows.Session verifySession(FoundationAccess.Subject subject,long sessionId,boolean full,boolean stepUp) {
        String previous=TenantContext.getTenantId();
        try {
            bind(subject.platform(),tenant(subject));
            var session=mapper.session(scope(subject),sessionId);validate(subject,session);
            var snapshot=access.snapshot(subject);
            var mfa=full || stepUp?mapper.mfa(scope(subject),session.memberId(),false):null;
            if(full && !"full".equals(state(subject,session,snapshot,mfa))) throw mfaRequired();
            if(stepUp && mfa!=null && mfa.enabled() && (session.verifiedAt()==null || !session.verifiedAt().plus(STEP_UP).isAfter(now())))
                throw new ApiException(HttpStatus.FORBIDDEN,"MFA_STEP_UP_REQUIRED","请先完成二次验证",false);
            return session;
        } finally {
            if(previous==null) TenantContext.clear();else TenantContext.setTenantId(previous);
        }
    }

    /** 账号显式启用MFA后，高风险管理写入要求最近五分钟TOTP或恢复码；默认未启用不增加步骤。 */
    public void requireStepUp() {
        requireFull();var subject=access.currentSubject();
        if(!mfaEnabled(subject)) return;
        SecurityRows.Session session=currentSession();
        if(session.verifiedAt()==null || !session.verifiedAt().plus(STEP_UP).isAfter(now()))
            throw new ApiException(HttpStatus.FORBIDDEN,"MFA_STEP_UP_REQUIRED","请先完成二次验证",false);
    }

    /** 查看本人状态，未完成MFA时仍允许读取并继续绑定。 */
    public SecurityViews.Status status() {
        var subject=access.currentSubject();var session=currentSession();var snapshot=access.snapshot(subject);
        var mfa=mapper.mfa(scope(subject),session.memberId(),false);
        return new SecurityViews.Status(mfa!=null && mfa.enabled(),required(subject,snapshot),state(subject,session,snapshot),
                mapper.remainingCodes(scope(subject),session.memberId()),session.verifiedAt());
    }

    /** 只为未绑定账号生成种子；已启用账号禁止用单因子会话替换验证器。 */
    @Transactional
    public SecurityViews.Enrollment enroll() {
        var subject=access.currentSubject();var session=currentSession();
        mapper.insertMfa(scope(subject),session.memberId());
        var mfa=mapper.mfa(scope(subject),session.memberId(),true);
        if(mfa.enabled()) throw new ApiException(HttpStatus.CONFLICT,"MFA_ALREADY_ENABLED","验证器已绑定",false);
        String secret=Totp.newSecret();
        if(mapper.seed(scope(subject),session.memberId(),crypto.encrypt(secret))!=1) throw unauthorized();
        event(subject,"security.mfa.enroll","success",Long.toString(session.id()));
        String label=subject.platform()?"NoeWise platform:"+subject.memberId():"NoeWise tenant "+subject.tenantId()+":"+subject.memberId();
        return new SecurityViews.Enrollment(secret,"otpauth://totp/"+URLEncoder.encode(label,StandardCharsets.UTF_8)
                +"?secret="+secret+"&issuer=NoeWise&algorithm=SHA1&digits=6&period=30");
    }

    /** 种子行锁保护TOTP确认与一次性恢复码创建，绑定成功时升级当前会话。 */
    @Transactional
    public SecurityViews.Enabled enable(String code) {
        var subject=access.currentSubject();var session=currentSession();
        proofLimit(subject);
        var mfa=mapper.mfa(scope(subject),session.memberId(),true);
        if(mfa==null || mfa.enabled() || mfa.secretEncrypted()==null) throw proofDenied(subject);
        long counter=Totp.match(crypto.decrypt(mfa.secretEncrypted()),code,now(),mfa.lastCounter());
        if(counter<0 || mapper.consumeTotp(scope(subject),session.memberId(),counter,true)!=1) throw proofDenied(subject);
        List<String> codes=createCodes(subject);
        redis.delete("foundation:security:attempt:proof:"+digest(scope(subject)+":"+subject.memberId()));
        mapper.verifySession(scope(subject),session.id());
        mapper.extendSession(scope(subject),session.id(),now().plus(Duration.ofDays(properties.getRefreshTokenExpireDays())));
        event(subject,"security.mfa.enable","success",Long.toString(session.id()));
        session=mapper.session(scope(subject),session.id());
        var account=validate(subject,session);
        return new SecurityViews.Enabled(codes,issue(account,session,access.snapshot(subject)));
    }

    /** 登录挑战及step-up共用验证；验证码计数器单调，恢复码DELETE必须命中一次。 */
    @Transactional
    public AuthResponse verify(String code) {
        var subject=access.currentSubject();var session=currentSession();proofLimit(subject);
        var mfa=mapper.mfa(scope(subject),session.memberId(),true);
        if(mfa==null || !mfa.enabled()) throw proofDenied(subject);
        boolean accepted=false;
        if(code!=null && code.matches("[0-9]{6}")) {
            long counter=Totp.match(crypto.decrypt(mfa.secretEncrypted()),code,now(),mfa.lastCounter());
            accepted=counter>=0 && mapper.consumeTotp(scope(subject),session.memberId(),counter,false)==1;
        } else if(code!=null && code.matches("[A-Za-z0-9_-]{43}"))
            accepted=mapper.consumeCode(scope(subject),session.memberId(),digest(code))==1;
        if(!accepted) throw proofDenied(subject);
        redis.delete("foundation:security:attempt:proof:"+digest(scope(subject)+":"+subject.memberId()));
        mapper.verifySession(scope(subject),session.id());
        // 受限会话升级到完整刷新期限，但不将旧JWT自身的exp延长。
        mapper.extendSession(scope(subject),session.id(),now().plus(Duration.ofDays(properties.getRefreshTokenExpireDays())));
        session=mapper.session(scope(subject),session.id());
        event(subject,"security.mfa.verify","success",Long.toString(session.id()));
        return issue(validate(subject,session),session,access.snapshot(subject));
    }

    /** 普通成员可撤销自愿绑定；当前全员策略启用时禁止关闭MFA。 */
    @Transactional
    public void disableMfa() {
        requireStepUp();var subject=access.currentSubject();
        if(required(subject,access.snapshot(subject))) throw new ApiException(HttpStatus.FORBIDDEN,"MFA_REQUIRED","此账号不能关闭二次验证",false);
        mapper.disableMfa(scope(subject),Long.parseLong(subject.memberId()));mapper.deleteCodes(scope(subject),Long.parseLong(subject.memberId()));
        mapper.revoke(scope(subject),Long.parseLong(subject.memberId()),null);event(subject,"security.mfa.disable","success",subject.memberId());
    }

    /** 管理员协助丢失验证器的成员恢复；撤销全部设备，后续绑定服从显式机构策略。 */
    @Transactional
    public void resetMemberMfa(String memberId) {
        access.require(SecurityPermissions.RESET_MFA);requireStepUp();var actor=access.currentSubject();long target=id(memberId);
        if(mapper.accountById(actor.platform(),tenant(actor),target)==null) throw new ApiException(HttpStatus.NOT_FOUND,"成员不存在");
        mapper.disableMfa(scope(actor),target);mapper.deleteCodes(scope(actor),target);mapper.revoke(scope(actor),target,null);
        event(actor,"security.mfa.admin_reset","success",memberId);
    }

    /** 更新恢复码需要已强化会话，旧码在同事务全部失效。 */
    @Transactional
    public List<String> regenerateCodes() {requireStepUp();var subject=access.currentSubject();if(!mfaEnabled(subject)) throw new ApiException(HttpStatus.CONFLICT,"MFA_NOT_ENABLED","请先启用验证器",false);return createCodes(subject);}

    /** 本人设备列表；不返回刷新凭据或密码指纹。 */
    public List<SecurityViews.Device> devices() {
        requireFull();var subject=access.currentSubject();var current=currentSession();
        return mapper.sessions(scope(subject),Long.parseLong(subject.memberId())).stream()
                .map(s->new SecurityViews.Device(Long.toString(s.id()),s.deviceLabel(),s.createdAt(),s.expiresAt(),s.verifiedAt(),s.id()==current.id())).toList();
    }

    /** 登出直接撤销权威会话，访问和刷新凭据从下一入口起失效。 */
    @Transactional
    public void logout() {var subject=access.currentSubject();var session=currentSession();mapper.revoke(scope(subject),session.memberId(),session.id());event(subject,"auth.logout","success",Long.toString(session.id()));}

    /** 本人撤销任意设备，不允许通过ID修改其他账号；不存在返回404。 */
    @Transactional
    public void revokeDevice(String sessionId) {
        requireFull();var subject=access.currentSubject();
        if(mapper.revoke(scope(subject),Long.parseLong(subject.memberId()),id(sessionId))!=1)
            throw new ApiException(HttpStatus.NOT_FOUND,"设备会话不存在");
        event(subject,"security.session.revoke","success",sessionId);
    }

    /** 管理员撤销同体系成员全部设备；动作权限必需，已启用MFA时还检查近期证明。 */
    @Transactional
    public void revokeMember(String memberId) {
        access.require(SecurityPermissions.REVOKE);requireStepUp();var actor=access.currentSubject();
        if(mapper.accountById(actor.platform(),tenant(actor),id(memberId))==null) throw new ApiException(HttpStatus.NOT_FOUND,"成员不存在");
        revokeSubject(actor.platform(),actor.tenantId(),memberId);
        event(actor,"security.member.revoke","success",memberId);
    }

    /** access提交后生命周期使用的可信入口；新事务确保afterCommit撤销事实实际提交。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void revokeSubject(boolean platform,String tenantId,String memberId) {
        var subject=new FoundationAccess.Subject(platform,tenantId,memberId);
        String previous=TenantContext.getTenantId();
        try {bind(platform,tenant(subject));mapper.revoke(scope(subject),Long.parseLong(memberId),null);}
        finally {if(previous==null) TenantContext.clear();else TenantContext.setTenantId(previous);}
    }

    /** 当前密码匹配后更新BCrypt并撤销所有设备；新登录保留账号显式启用的MFA规则。 */
    @Transactional
    public void changePassword(String oldPassword,String newPassword) {
        requireFull();var subject=access.currentSubject();var session=currentSession();
        requireStepUp();
        var account=validate(subject,session);
        if(oldPassword==null || !passwords.matches(oldPassword,account.passwordHash())) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"当前密码不正确");
        validatePassword(newPassword);
        if(mapper.password(subject.platform(),tenant(subject),account.id(),passwords.encode(newPassword),false)!=1) throw unauthorized();
        mapper.revoke(scope(subject),account.id(),null);event(subject,"auth.password.change","success",subject.memberId());
    }

    /** 管理员可发布全员策略，默认不开启；显式启用后当前会话实时重新判定。 */
    @Transactional
    public SecurityViews.Policy setPolicy(boolean requireAll) {access.require(SecurityPermissions.POLICY);requireStepUp();var subject=access.currentSubject();mapper.policy(scope(subject),requireAll);event(subject,"security.policy.change","success",null);return new SecurityViews.Policy(requireAll);}
    /** 策略读取需要管理权限。 */
    public SecurityViews.Policy policy() {access.require(SecurityPermissions.POLICY);return new SecurityViews.Policy(Boolean.TRUE.equals(mapper.requireAll(scope(access.currentSubject()))));}
    /** 读取已批准90日默认及显式覆盖；NULL只表示管理员主动关闭。 */
    public SecurityViews.SessionRetention sessionRetention(){access.require(SecurityPermissions.POLICY);return new SecurityViews.SessionRetention(mapper.sessionRetention(scope(access.currentSubject())));}
    /** 发布调查保留期；权威撤销或到期后等待该期限，MFA强化及安全审计保持原边界。 */
    @Transactional
    public SecurityViews.SessionRetention setSessionRetention(Integer days){access.require(SecurityPermissions.POLICY);requireStepUp();if(days!=null&&(days<1||days>36500))throw new ApiException(HttpStatus.BAD_REQUEST,"会话保留天数必须在1至36500之间，NULL表示关闭");var subject=access.currentSubject();if(mapper.setSessionRetention(scope(subject),days)>0)event(subject,"security.session.retention.change","success",null);return new SecurityViews.SessionRetention(days);}
    /** 授权调查保护与清理锁定同一会话；不存在含跨范围均拒绝，解除保护保留安全审计事实。 */
    @Transactional
    public void protectSession(String sessionId,boolean protect){access.require(SecurityPermissions.POLICY);requireStepUp();var subject=access.currentSubject();int changed=mapper.protectSession(scope(subject),id(sessionId),protect);if(changed==0)throw new ApiException(HttpStatus.NOT_FOUND,"设备会话不存在");if(changed==1)event(subject,protect?"security.session.protect":"security.session.protection.release","success",sessionId);}
    /** 后台按RLS作用域有界回收；无显式策略时SQL无候选，不触及认证仍有效或调查保护会话。 */
    @Transactional
    public int cleanupSessions(String cleanupScope){String expected=TenantContext.getTenantId()==null?"platform":"tenant:"+TenantContext.getTenantId();if(!expected.equals(cleanupScope))throw new IllegalStateException("安全清理作用域未绑定");return mapper.cleanupSessions(cleanupScope,100);}

    /** 恢复请求不透露账号存在性，只向账号已登记邮箱投递一次性凭据。 */
    @Transactional
    public void requestRecovery(boolean platform,String tenantSlug,String username) {
        TenantContext.clear();
        limit("recovery:"+digest((platform?"platform":tenantSlug)+":"+username),3,900);
        Long tenantId=platform?null:mapper.tenantBySlug(tenantSlug);
        if(!platform && tenantId==null) return;
        bind(platform,tenantId);var account=mapper.accountByName(platform,tenantId,username);
        if(account==null || !"active".equals(account.status()) || account.email()==null || account.email().isBlank()) return;
        mailToken(subject(platform,tenantId,account.id()),account,"recovery",Duration.ofMinutes(30));
    }

    /** 邀请仅用于当前pending/invited成员，停用与活跃账号不能被邀请凭据接管。 */
    @Transactional
    public void invite(String memberId) {
        access.require(SecurityPermissions.INVITE);requireStepUp();var actor=access.currentSubject();
        var account=mapper.accountById(actor.platform(),tenant(actor),id(memberId));
        if(account==null || account.email()==null || account.email().isBlank()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"成员必须有已登记邮箱");
        if(!java.util.Set.of("pending","invited").contains(account.status())) throw new ApiException(HttpStatus.CONFLICT,"只有待激活成员可以发送邀请");
        if(mapper.invite(actor.platform(),tenant(actor),account.id())!=1) throw new ApiException(HttpStatus.CONFLICT,"成员状态已变化，请重新读取");
        mapper.revoke(scope(actor),account.id(),null);
        mailToken(subject(actor.platform(),tenant(actor),account.id()),account,"invitation",Duration.ofDays(2));
        event(actor,"security.invitation.create","success",memberId);
    }

    /** 邮件凭据原子消费与密码更新同事务，密码恢复不能清除或绕过已有MFA。 */
    @Transactional
    public void completeRecovery(boolean platform,String purpose,String token,String password) {
        validatePassword(password);Parsed parsed=parse(platform,token);bind(platform,parsed.tenantId());
        var recovery=mapper.consumeRecovery(parsed.scope(),purpose,digest(parsed.raw()));
        if(recovery==null) throw unauthorized();
        var subject=subject(platform,parsed.tenantId(),recovery.memberId());
        var account=mapper.accountById(platform,parsed.tenantId(),recovery.memberId());
        if(account==null || ("invitation".equals(purpose)?!"invited".equals(account.status()):!"active".equals(account.status()))) throw unauthorized();
        if(mapper.password(platform,parsed.tenantId(),account.id(),passwords.encode(password),"invitation".equals(purpose))!=1) throw unauthorized();
        mapper.revoke(scope(subject),account.id(),null);mapper.deleteRecovery(scope(subject),account.id());
        event(subject,"auth.password."+purpose,"success",subject.memberId());
    }

    /** 冻结邮件正文经messaging凭据边界加密；API从不返回恢复token。 */
    private void mailToken(FoundationAccess.Subject subject,SecurityRows.Account account,String purpose,Duration lifetime) {
        String raw=RefreshTokens.create();Instant expires=now().plus(lifetime);
        mapper.deleteRecovery(scope(subject),account.id());
        mapper.insertRecovery(scope(subject),account.id(),purpose,digest(raw),expires);
        String token=prefix(subject)+"."+raw;
        String slug=subject.platform()?"platform":mapper.tenantSlug(tenant(subject));
        notifications.enqueue(new NotificationCommands.Enqueue(scope(subject),null,account.email(),"security:"+purpose+":"+digest(raw),
                "invitation".equals(purpose)?"security.invitation":"security.password_recovery",
                Map.of("username",account.username(),"token",token,"expires_at",expires.toString(),"tenant_slug",slug),false,true));
    }

    /** 任何会话读都重查账号当前密码和停用状态；撤权不依赖JWT到期。 */
    private SecurityRows.Account validate(FoundationAccess.Subject subject,SecurityRows.Session session) {
        if(session==null || session.memberId()!=Long.parseLong(subject.memberId()) || !scope(subject).equals(session.scopeKey())
                || session.revokedAt()!=null || !session.expiresAt().isAfter(now())) throw unauthorized();
        var account=mapper.accountById(subject.platform(),tenant(subject),session.memberId());
        if(account==null || !"active".equals(account.status()) || account.passwordHash()==null
                || !MessageDigest.isEqual(digest(account.passwordHash()).getBytes(StandardCharsets.US_ASCII),session.credentialHash().getBytes(StandardCharsets.US_ASCII)))
            throw unauthorized();
        return account;
    }

    /** DB当前MFA要求与会话认证级别联合判定，旧full状态不能绕过新策略。 */
    private String state(FoundationAccess.Subject subject,SecurityRows.Session session,FoundationAccess.Snapshot snapshot) {
        var mfa=mapper.mfa(scope(subject),session.memberId(),false);
        return state(subject,session,snapshot,mfa);
    }
    /** 同次入口认证的完整级别和step-up共用已读取的MFA事实。 */
    private String state(FoundationAccess.Subject subject,SecurityRows.Session session,FoundationAccess.Snapshot snapshot,SecurityRows.Mfa mfa) {
        if(required(subject,snapshot) && (mfa==null || !mfa.enabled())) return "enrollment";
        if(mfa!=null && mfa.enabled() && session.verifiedAt()==null) return "challenge";
        return session.state();
    }
    /** MFA默认关闭，仅机构显式全员策略要求绑定；用户自愿绑定独立生效。 */
    private boolean required(FoundationAccess.Subject subject,FoundationAccess.Snapshot snapshot) {
        return Boolean.TRUE.equals(mapper.requireAll(scope(subject)));
    }
    /** 敏感写的step-up只在账号显式启用MFA后生效。 */
    private boolean mfaEnabled(FoundationAccess.Subject subject) {
        var mfa=mapper.mfa(scope(subject),Long.parseLong(subject.memberId()),false);
        return mfa!=null && mfa.enabled();
    }
    /** 原子轮换刷新摘要后签发JWT，签发失败由事务回滚；无旧key双写路径。 */
    private AuthResponse issue(SecurityRows.Account account,SecurityRows.Session session,FoundationAccess.Snapshot snapshot) {
        boolean full="full".equals(session.state());String raw=full?RefreshTokens.create():null;
        if(full && mapper.rotate(session.scopeKey(),session.id(),session.refreshHash(),digest(raw))!=1) throw unauthorized();
        int seconds=full?properties.getAccessTokenExpireMinutes()*60:Math.min(properties.getAccessTokenExpireMinutes()*60,600);
        var model=new SaLoginModel().setIsShare(false).setIsConcurrent(true).setTimeout(seconds).setExtra(SESSION_CLAIM,Long.toString(session.id()))
                .setExtra("username",account.username()).setExtra("role",account.role());
        if(account.tenantId()!=null) model.setExtra("tenant_id",account.tenantId().toString());
        StpUtil.login(Long.toString(account.id()),model);
        String token=StpUtil.getTokenValue();
        return new AuthResponse(token,"bearer",seconds,raw==null?null:(account.tenantId()==null?"platform":account.tenantId())+"."+raw,
                account.tenantId()==null?null:account.tenantId().toString(),account.username(),snapshot.roles(),snapshot.permissions(),snapshot.authorizationVersion(),session.state(),Long.toString(session.id()));
    }
    /** 创建十个256位恢复码，只保存SHA-256摘要。 */
    private List<String> createCodes(FoundationAccess.Subject subject) {
        mapper.deleteCodes(scope(subject),Long.parseLong(subject.memberId()));List<String> codes=new ArrayList<>();
        for(int i=0;i<10;i++){String code=RefreshTokens.create();codes.add(code);mapper.insertCode(scope(subject),Long.parseLong(subject.memberId()),digest(code));}
        event(subject,"security.recovery_codes.replace","success",subject.memberId());return List.copyOf(codes);
    }
    /** 固定失败窗口限制验证猜测；Redis不可用时失败关闭。 */
    private void proofLimit(FoundationAccess.Subject subject) {limit("proof:"+digest(scope(subject)+":"+subject.memberId()),8,600);}
    /** 首次自增原子设置TTL，避免并发创建永久限流key。 */
    private void limit(String key,int maximum,int seconds) {
        Long count=redis.execute(ATTEMPT,List.of("foundation:security:attempt:"+key),Integer.toString(seconds));
        if(count==null || count<1) throw new IllegalStateException("认证限流不可用");
        if(count>maximum) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"AUTH_ATTEMPTS_LIMITED","尝试过于频繁，请稍后再试",false);
    }
    /** 严格解析账号体系前缀，不接受跨issuer刷新或恢复凭据。 */
    private Parsed parse(boolean platform,String token) {
        if(token==null || token.length()>256) throw unauthorized();int dot=token.indexOf('.');
        if(dot<=0 || dot==token.length()-1) throw unauthorized();String prefix=token.substring(0,dot),raw=token.substring(dot+1);
        if(!raw.matches("[A-Za-z0-9_-]{43}")) throw unauthorized();
        if(platform) {if(!"platform".equals(prefix)) throw unauthorized();return new Parsed(null,"platform",raw);}
        long tenantId=id(prefix);return new Parsed(tenantId,"tenant:"+tenantId,raw);
    }
    /** 恢复凭据解析结果仅在安全应用内部存在。 */
    private record Parsed(Long tenantId,String scope,String raw) { }
    /** BCrypt拒绝超过72 UTF-8字节，避免密码截断造成等价凭据。 */
    private static void validatePassword(String password) {
        if(password==null || password.codePointCount(0,password.length())<12 || password.getBytes(StandardCharsets.UTF_8).length>72
                || !password.matches("(?s)^(?=.*[A-Za-z])(?=.*[0-9]).+$")) throw new ApiException(HttpStatus.BAD_REQUEST,"密码需12位以上且包含字母数字，UTF-8不超过72字节");
    }
    /** 元数据不写密码、验证码、种子或令牌。 */
    private void event(FoundationAccess.Subject subject,String action,String outcome,String resource) {
        audit.recordSecurity(subject.tenantId(),subject.memberId(),action,"security_session",resource,outcome,"安全认证操作",Map.of());
    }
    private ApiException proofDenied(FoundationAccess.Subject subject) {audit.recordDeniedSecurity(subject.tenantId(),subject.memberId(),"security.mfa.verify","security_session",null,"denied","二次验证凭据无效",Map.of());return new ApiException(HttpStatus.UNAUTHORIZED,"MFA_PROOF_INVALID","验证码或恢复码无效",false);}
    private ApiException badCredentials(Long tenantId,Long memberId) {audit.recordDeniedSecurity(tenantId==null?null:tenantId.toString(),memberId==null?null:memberId.toString(),"auth.login","account",null,"denied","登录凭据无效",Map.of());return new ApiException(HttpStatus.UNAUTHORIZED,"AUTH_CREDENTIALS_INVALID","用户名或密码错误",false);}
    private static ApiException unauthorized() {return new ApiException(HttpStatus.UNAUTHORIZED,"AUTH_SESSION_INVALID","凭据无效、会话已撤销或账号已失效",false);}
    private static ApiException mfaRequired() {return new ApiException(HttpStatus.FORBIDDEN,"MFA_REQUIRED","请先完成验证器绑定或二次验证",false);}
    private static FoundationAccess.Subject subject(boolean platform,Long tenant,long member) {return new FoundationAccess.Subject(platform,tenant==null?null:tenant.toString(),Long.toString(member));}
    private static String scope(FoundationAccess.Subject subject) {return subject.platform()?"platform":"tenant:"+subject.tenantId();}
    private static String prefix(FoundationAccess.Subject subject) {return subject.platform()?"platform":subject.tenantId();}
    private static Long tenant(FoundationAccess.Subject subject) {return subject.platform()?null:Long.valueOf(subject.tenantId());}
    private static void bind(boolean platform,Long tenant) {if(platform) TenantContext.clear();else TenantContext.setTenantId(tenant.toString());}
    private static Instant now() {return BusinessTime.clock().instant();}
    private static long id(String value) {try {if(value==null || !value.matches("[1-9][0-9]*")) throw new NumberFormatException();return Long.parseLong(value);}catch(NumberFormatException invalid){throw unauthorized();}}
    /** 单向摘要用于256位随机凭据与密码指纹；密码本身仍由BCrypt负责。 */
    static String digest(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.GeneralSecurityException invalid){throw new IllegalStateException(invalid);}}
    /** 设备描述仅采集受限元数据，超过存储长度以通用描述替代。 */
    private static String deviceLabel() {
        var attrs=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if(attrs instanceof org.springframework.web.context.request.ServletRequestAttributes servlet){String ua=servlet.getRequest().getHeader("User-Agent");if(ua!=null && !ua.isBlank() && ua.codePointCount(0,ua.length())<=256) return ua;}
        return "Web browser";
    }
}
