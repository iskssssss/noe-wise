package com.skillforge.institution.artifact;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.foundation.access.FoundationAccess;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 票据绑定成员或认证渠道；每次使用复核身份、Session 归属、渠道绑定和产物保留期。 */
@Service
public class ArtifactAccess {
    private final ArtifactStorage storage;
    private final ObjectMapper json;
    private final AuditService audit;
    private final StudioArtifactProjection studioArtifacts;
    private final com.skillforge.core.chat.access.OpenPlatformSessionAccessService openPlatform;
    private final FoundationAccess foundation;
    private final com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation tests;
    private final String key;
    private final String previewOrigin;
    private final String platformOrigin;
    private final String downloadOrigin;
    public ArtifactAccess(ArtifactStorage storage,ObjectMapper json,AuditService audit,
            StudioArtifactProjection studioArtifacts,
            com.skillforge.core.chat.access.OpenPlatformSessionAccessService openPlatform,
            FoundationAccess foundation,
            @Value("${skillforge.code-execution.ticket-key:}") String key,
            @Value("${skillforge.code-execution.preview-origin:}") String previewOrigin,
            @Value("${skillforge.code-execution.platform-origin:}") String platformOrigin,
            @Value("${skillforge.code-execution.download-origin:}") String downloadOrigin,com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation tests) {
        this.storage=storage;this.json=json;this.audit=audit;
        this.openPlatform=Objects.requireNonNull(openPlatform);
        this.foundation=Objects.requireNonNull(foundation);this.tests=Objects.requireNonNull(tests);
        this.studioArtifacts=Objects.requireNonNull(studioArtifacts);this.key=key;
        this.previewOrigin=previewOrigin;this.platformOrigin=platformOrigin;
        this.downloadOrigin=downloadOrigin==null||downloadOrigin.isBlank()?platformOrigin:downloadOrigin;
    }
    public String tenant(){if(ChannelContext.getChannelId()==null&&com.skillforge.common.context.ServicePrincipalContext.get()==null)StpUtil.checkLogin();String tenant=TenantContext.getTenantId();if(tenant==null)throw ArtifactStorage.unavailable();return tenant;}
    public void authorizeCurrent(String tenant,String session) {
        if(tests.isolated(Long.parseLong(tenant),session))throw com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation.denied("workflow_test_artifacts_denied","测试会话不允许上传附件、工作区或成果发布");
        var principal=com.skillforge.common.context.ServicePrincipalContext.get();
        if(principal!=null) {
            if(!tenant.equals(principal.tenantId()))throw ArtifactStorage.unavailable();
            String scope=principal.scopes().contains("sessions.write")?"sessions.write":
                    principal.scopes().contains("sessions.read")?"sessions.read":"artifacts.read";
            openPlatform.session(openPlatform.require(principal,scope),session);
            return;
        }
        String channel=ChannelContext.getChannelId();
        if(channel!=null)authorizeChannel(tenant,channel,session);else foundation.requireSession(currentMember(tenant),session,"read");
    }
    /** 上传输入需要当前写权限和会话所有权；外部主体复用其独立认证协议。 */
    public void authorizeWritableCurrent(String tenant,String session) {
        if(tests.isolated(Long.parseLong(tenant),session))throw com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation.denied("workflow_test_artifacts_denied","测试会话不允许上传附件、工作区或成果发布");
        var principal=com.skillforge.common.context.ServicePrincipalContext.get();
        if(principal!=null){if(!tenant.equals(principal.tenantId()))throw ArtifactStorage.unavailable();openPlatform.session(openPlatform.require(principal,"sessions.write"),session);return;}
        String channel=ChannelContext.getChannelId();
        if(channel!=null)authorizeChannel(tenant,channel,session);
        else foundation.requireSession(currentMember(tenant),session,"write");
    }
    /** 后台任务只使用入口冻结的请求者或渠道身份，不读取工作线程认证上下文。 */
    public void authorizeBound(String tenant,String session,String requester,String channel) {
        if(tests.isolated(Long.parseLong(tenant),session))throw com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation.denied("workflow_test_artifacts_denied","测试会话不允许上传附件、工作区或成果发布");
        if(channel!=null&&!channel.isBlank())authorizeChannel(tenant,channel,session);
        else authorize(tenant,requester,session);
    }
    private void authorizeChannel(String tenant,String channel,String session) {
        if(!storage.canChannelAccess(tenant,channel,session))throw ArtifactStorage.unavailable();
        com.skillforge.institution.chat.ChatEndUserTokens.requireEndUser(storage.sessionEndUser(tenant,session));
    }
    String channelEndUser(String tenant,String session) {
        String user=storage.sessionEndUser(tenant,session);
        if(user==null)throw ArtifactStorage.unavailable();
        return user;
    }
    void authorizeEndUserRequester(String tenant,String session,String channel,String endUser) {
        if(endUser==null||!endUser.equals(storage.sessionEndUser(tenant,session)))throw ArtifactStorage.unavailable();
        authorizeChannel(tenant,channel,session);
    }
    public String member(){StpUtil.checkLogin();return StpUtil.getLoginIdAsString();}
    /** 当前后台身份必须来自成员JWT或已验证后台绑定，不能从TenantContext推断账号体系。 */
    private FoundationAccess.Subject currentMember(String tenant){var subject=foundation.currentSubject();if(subject.platform()||!tenant.equals(subject.tenantId()))throw ArtifactStorage.unavailable();return subject;}
    public void authorize(String tenant,String member,String session) {
        if(tests.isolated(Long.parseLong(tenant),session))throw com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation.denied("workflow_test_artifacts_denied","测试会话不允许上传附件、工作区或成果发布");
        foundation.requireSession(new FoundationAccess.Subject(false,tenant,member),session,"read");
    }
    /** 用户清单聚合当前产品会话和仍获授权的工作室成员子 Run，并隐藏内部 child Session。 */
    public List<ArtifactMetadata> listCurrent(String tenant,String session) {
        authorizeCurrent(tenant,session);
        var sessions=studioArtifacts.visibleSessions(tenant,session);
        return storage.listSessions(tenant,sessions).stream().map(artifact -> new ArtifactMetadata(
                artifact.id(),session,artifact.runId(),artifact.invocationId(),artifact.type(),artifact.name(),
                artifact.size(),artifact.sha256(),artifact.expiresAt(),artifact.status(),artifact.files(),
                artifact.artifactInputs())).toList();
    }
    void auditCurrent(String tenant,String action,String artifact,String description,Map<String,Object> details) {
        String channel=ChannelContext.getChannelId();
        var principal=com.skillforge.common.context.ServicePrincipalContext.get();
        String actor=principal!=null?"integration:"+principal.integrationId():channel==null?member():"channel:"+channel;
        audit.record(tenant,actor,action,"artifact",artifact,description,details);
    }
    void auditBound(String tenant,String member,String channel,String action,String revision,Map<String,Object> details) {
        audit.record(tenant,channel==null?member:"channel:"+channel,action,"deliverable",revision,"成果文件访问",details);
    }
    public String ticket(String id,String kind,String path) {
        String tenant=tenant(), channel=ChannelContext.getChannelId();
        String member=channel==null?member():null;var row=storage.require(tenant,id);
        storage.requirePublicInspection(row);
        String session=productSession(tenant,row);
        authorizeCurrent(tenant,session);
        if("preview".equals(kind)&&!"website".equals(row.type()))throw ArtifactStorage.unavailable();
        if("file".equals(kind)&&storage.metadata(row).files().stream().noneMatch(file->file.path().equals(path)))throw ArtifactStorage.unavailable();
        requireConfiguration();
        try {
            var claim=new Claim(tenant,member,channel,id,kind,path,Instant.now().plusSeconds(120).getEpochSecond(),UUID.randomUUID().toString(),
                    channel==null?null:channelEndUser(tenant,session));
            String body=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claim));
            String signed=body+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(sign(body));
            audit.record(tenant,channel==null?member:"channel:"+channel,"artifact.ticket","artifact",id,"签发产物访问票据",Map.of("kind",kind));
            return "preview".equals(kind)?previewOrigin+"/p/"+signed+"/index.html":downloadOrigin+"/api/v1/chat/artifact-content/detail?ticket="+signed;
        } catch(Exception failure){throw new IllegalStateException("artifact_ticket_failed",failure);}
    }
    public Access verify(String ticket,String expectedKind) {
        requireConfiguration();
        try {
            if(ticket.length()>4096)throw ArtifactStorage.unavailable();
            String[] parts=ticket.split("\\.",-1);
            if(parts.length!=2||!MessageDigest.isEqual(sign(parts[0]),Base64.getUrlDecoder().decode(parts[1])))throw ArtifactStorage.unavailable();
            var claim=json.readValue(Base64.getUrlDecoder().decode(parts[0]),Claim.class);
            if(claim.expiresAt()<=Instant.now().getEpochSecond()||!Set.of("file","zip","preview").contains(claim.kind())
                    ||(expectedKind!=null&&!expectedKind.equals(claim.kind())))throw ArtifactStorage.unavailable();
            String previous=TenantContext.getTenantId();
            try {
                TenantContext.setTenantId(claim.tenant());
                var row=storage.require(claim.tenant(),claim.artifact());
                storage.requirePublicInspection(row);
                if((claim.channel()==null)==(claim.member()==null))throw ArtifactStorage.unavailable();
                String session=productSession(claim.tenant(),row);
                if(claim.channel()!=null)authorizeEndUserRequester(claim.tenant(),session,claim.channel(),claim.endUser());
                else authorize(claim.tenant(),claim.member(),session);
                if(!"preview".equals(claim.kind()))audit.record(claim.tenant(),claim.channel()==null?claim.member():"channel:"+claim.channel(),"artifact.download","artifact",row.id(),"读取产物",Map.of("kind",claim.kind()));
                return new Access(claim,row);
            }finally{if(previous==null)TenantContext.clear();else TenantContext.setTenantId(previous);}
        }catch(Exception rejected){throw ArtifactStorage.unavailable();}
    }
    public void requireConfiguration() {
        if(key.length()<32)throw new IllegalStateException("artifact_ticket_key_missing");
        URI preview=origin(previewOrigin), platform=origin(platformOrigin);origin(downloadOrigin);
        if(preview.getHost().equalsIgnoreCase(platform.getHost()))throw new IllegalStateException("artifact_preview_requires_separate_host");
    }
    String authorizeArtifactCurrent(String tenant,ArtifactRow row) {
        String session=productSession(tenant,row);
        authorizeCurrent(tenant,session);
        return session;
    }
    void authorizeRequester(String tenant,String session,String member,String channel) {
        if((member==null)==(channel==null))throw ArtifactStorage.unavailable();
        if(channel!=null)authorizeChannel(tenant,channel,session);else authorize(tenant,member,session);
    }
    void authorizeArtifactRequester(String tenant,ArtifactRow row,String member,String channel) {
        authorizeRequester(tenant,productSession(tenant,row),member,channel);
    }
    /** 正式成果展示复用同一精确父子关系；不供 Agent 工具扩大自身 Session 读域。 */
    List<String> deliverableSessions(String tenant,String productSession) {
        return studioArtifacts.visibleSessions(tenant,productSession);
    }
    Optional<StudioArtifactProjection.Route> deliverableRoute(String tenant,String session,String run) {
        return studioArtifacts.route(tenant,session,run);
    }
    private String productSession(String tenant,ArtifactRow row) {
        return studioArtifacts.route(tenant,row.sessionId(),row.runId())
                .map(StudioArtifactProjection.Route::productSession).orElse(row.sessionId());
    }
    static URI origin(String value) {
        URI uri=URI.create(value);
        if(!Set.of("https","http").contains(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null
                ||uri.getRawFragment()!=null||!uri.getPath().isEmpty())throw new IllegalStateException("artifact_origin_invalid");
        return uri;
    }
    private byte[] sign(String value)throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }
    public record Claim(String tenant,String member,String channel,String artifact,String kind,String path,long expiresAt,String nonce,String endUser) {
        public Claim(String tenant,String member,String channel,String artifact,String kind,String path,long expiresAt,String nonce) {
            this(tenant,member,channel,artifact,kind,path,expiresAt,nonce,null);
        }
        @Override public String toString(){return "ArtifactClaim[REDACTED]";}
    }
    public record Access(Claim claim,ArtifactRow row) { }
}
