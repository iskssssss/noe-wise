package com.skillforge.institution.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.common.context.ChannelContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 票据绑定精确发布版本；签名和过期时间不能替代使用时的当前权限。 */
@Service
public final class DeliverableAccess {
    private final ArtifactAccess access; private final DeliverableReader reader; private final ObjectMapper json;
    private final String key,downloadOrigin,previewOrigin;
    private final long previewSeconds,downloadSeconds;
    public DeliverableAccess(ArtifactAccess access,DeliverableReader reader,ObjectMapper json,
            @Value("${skillforge.code-execution.ticket-key:}") String key,
            @Value("${skillforge.code-execution.download-origin:}") String downloadOrigin,
            @Value("${skillforge.code-execution.preview-origin:}") String previewOrigin,
            @Value("${skillforge.deliverables.preview-ticket-seconds:300}") long previewSeconds,
            @Value("${skillforge.deliverables.download-ticket-seconds:3600}") long downloadSeconds) {
        this.access=access;this.reader=reader;this.json=json;this.key=key;this.downloadOrigin=downloadOrigin;this.previewOrigin=previewOrigin;
        this.previewSeconds=previewSeconds;this.downloadSeconds=downloadSeconds;
    }
    public Map<String,String> ticket(String revision,String file,boolean preview) {
        access.requireConfiguration();String tenant=access.tenant();
        var grant=reader.file(tenant,revision,file);access.authorizeCurrent(tenant,grant.sessionId());access.authorizeArtifactCurrent(tenant,grant.artifact());
        String kind=preview?("html".equals(grant.format())?"html":"md".equals(grant.format())?"markdown":"text"):"file";
        String channel=ChannelContext.getChannelId(),member=channel==null?access.member():null;
        var claim=new Claim("deliverable.v1",tenant,member,channel,revision,file,grant.representationId(),kind,
                Instant.now().plusSeconds(preview?previewSeconds:downloadSeconds).getEpochSecond(),UUID.randomUUID().toString(),
                channel==null?null:access.channelEndUser(tenant,grant.sessionId()));
        String token=encode(claim);
        io.micrometer.core.instrument.Metrics.counter("skillforge.deliverable.tickets","status","issued","kind",kind).increment();
        String url="html".equals(kind)?previewOrigin+"/d/"+token+"/"+encodePath(grant.relativePath())
                :downloadOrigin+"/api/v1/chat/deliverable-content/detail?ticket="+token;
        access.auditCurrent(tenant,"deliverable.ticket",revision,"签发成果文件访问票据",Map.of("file_id",file,"kind",kind,"ip",requestIp()));
        return Map.of("url",url,"kind",kind);
    }
    public Granted verify(String token,String resourcePath) { return verify(token,resourcePath,requestIp()); }
    public Granted verify(String token,String resourcePath,String remoteIp) {
        try {
            if(key.length()<32 || token==null || token.length()>16384)throw ArtifactStorage.unavailable();
            String[] parts=token.split("\\.",-1);if(parts.length!=2)throw ArtifactStorage.unavailable();
            if(!MessageDigest.isEqual(sign(parts[0]),Base64.getUrlDecoder().decode(parts[1])))throw ArtifactStorage.unavailable();
            Claim claim=json.readValue(Base64.getUrlDecoder().decode(parts[0]),Claim.class);
            if(!"deliverable.v1".equals(claim.schema())
                    ||!Set.of("html","markdown","text","file").contains(claim.kind())||(claim.member()==null)==(claim.channel()==null))throw ArtifactStorage.unavailable();
            String old=TenantContext.getTenantId();TenantContext.setTenantId(claim.tenant());
            try {
                if(claim.expiresAt()<=Instant.now().getEpochSecond()) {
                    audit(claim,"expired",remoteIp);throw ArtifactStorage.unavailable();
                }
                var grant=resourcePath==null?reader.file(claim.tenant(),claim.revision(),claim.file()):reader.resource(claim.tenant(),claim.revision(),claim.representation(),resourcePath);
                if(resourcePath!=null&&!"html".equals(claim.kind()))throw ArtifactStorage.unavailable();
                if(!grant.representationId().equals(claim.representation()))throw ArtifactStorage.unavailable();
                try {
                    access.authorizeRequester(claim.tenant(),grant.sessionId(),claim.member(),claim.channel());
                    if(claim.channel()!=null)access.authorizeEndUserRequester(claim.tenant(),grant.sessionId(),claim.channel(),claim.endUser());
                    access.authorizeArtifactRequester(claim.tenant(),grant.artifact(),claim.member(),claim.channel());
                } catch(com.skillforge.core.shared.exception.ApiException|SecurityException revoked) {
                    audit(claim,"revoked",remoteIp);throw revoked;
                }
                audit(claim,"used",remoteIp);
                return new Granted(claim,grant);
            }finally{if(old==null)TenantContext.clear();else TenantContext.setTenantId(old);}
        }catch(Exception denied){throw ArtifactStorage.unavailable();}
    }
    public int fileCount(Granted grant){return reader.representationFileCount(grant.claim().tenant(),grant.file().representationId());}
    private void audit(Claim claim,String status,String ip) {
        io.micrometer.core.instrument.Metrics.counter("skillforge.deliverable.tickets","status",status,"kind",claim.kind()).increment();
        access.auditBound(claim.tenant(),claim.member(),claim.channel(),"deliverable.ticket."+status,claim.revision(),
                Map.of("file_id",claim.file(),"kind",claim.kind(),"nonce",claim.nonce(),"ip",ip));
    }
    private static String requestIp() {
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        return attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet?servlet.getRequest().getRemoteAddr():"unavailable";
    }
    private String encode(Claim claim){try{String body=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claim));return body+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(sign(body));}catch(Exception e){throw new IllegalStateException("deliverable_ticket_failed",e);}}
    private byte[] sign(String body)throws Exception{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return mac.doFinal(body.getBytes(StandardCharsets.US_ASCII));}
    private static String encodePath(String path){return Arrays.stream(path.split("/")).map(s->java.net.URLEncoder.encode(s,StandardCharsets.UTF_8).replace("+","%20")).collect(java.util.stream.Collectors.joining("/"));}
    public record Claim(String schema,String tenant,String member,String channel,String revision,String file,String representation,String kind,long expiresAt,String nonce,String endUser) {
        public Claim(String schema,String tenant,String member,String channel,String revision,String file,String representation,String kind,long expiresAt,String nonce) {
            this(schema,tenant,member,channel,revision,file,representation,kind,expiresAt,nonce,null);
        }
        @Override public String toString(){return "DeliverableClaim[REDACTED]";}
    }
    public record Granted(Claim claim,DeliverableReader.FileGrant file) { }
}
