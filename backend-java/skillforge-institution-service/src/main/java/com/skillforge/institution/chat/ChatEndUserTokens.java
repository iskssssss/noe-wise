package com.skillforge.institution.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.institution.config.ExternalAuthPaths;
import com.skillforge.institution.security.HmacSignatureVerifier;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 渠道凭证只确定渠道；终端用户身份必须由独立、限期且不可伪造的持有者凭证确定。 */
@Service
public class ChatEndUserTokens {
    public static final String HEADER = "X-Chat-User-Token";
    private static final String ATTRIBUTE = ChatEndUserTokens.class.getName();
    private static final String SCHEMA = "skillforge.chat-user.v1";
    private static final long LIFETIME_SECONDS = 30L * 24 * 60 * 60;
    private final ObjectMapper json;
    private final String key;
    private final ChatClientProfileMapper profiles;
    private final EndUserService users;

    public ChatEndUserTokens(ObjectMapper json, SkillForgeProperties properties,
            ChatClientProfileMapper profiles, EndUserService users) {
        this.json = json;
        this.key = HmacSignatureVerifier.sign(properties.getSecretKey(), SCHEMA + ":signing-key");
        this.profiles = profiles;
        this.users = users;
    }

    public String issue(long tenant, long channel, long user, String ref) {
        try {
            var claim = new Principal(SCHEMA, Long.toString(tenant), Long.toString(channel),
                    Long.toString(user), ref, Instant.now().plusSeconds(LIFETIME_SECONDS).getEpochSecond());
            String body = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claim));
            return body + "." + HmacSignatureVerifier.sign(key, SCHEMA + "\n" + body);
        } catch (Exception failure) {
            throw new IllegalStateException("chat_user_token_issue_failed", failure);
        }
    }

    @Transactional(readOnly = true)
    public Principal verify(String token) {
        Principal claim;
        long tenant, channel, user;
        try {
            if (token == null || token.length() > 4096) throw unauthorized();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || !HmacSignatureVerifier.verify(key, SCHEMA + "\n" + parts[0], parts[1]))
                throw unauthorized();
            claim = json.readValue(Base64.getUrlDecoder().decode(parts[0]), Principal.class);
            if (!SCHEMA.equals(claim.schema()) || claim.expiresAt() <= Instant.now().getEpochSecond()
                    || !Objects.equals(TenantContext.getTenantId(), claim.tenant())
                    || !Objects.equals(ChannelContext.getChannelId(), claim.channel())) throw unauthorized();
            tenant = Long.parseLong(claim.tenant()); channel = Long.parseLong(claim.channel());
            user = Long.parseLong(claim.endUser());
            if (tenant <= 0 || channel <= 0 || user <= 0) throw unauthorized();
        } catch (Exception invalid) {
            throw unauthorized();
        }
        // 存储不可用必须保留为服务故障，不能误报401导致客户端丢弃有效身份。
        if (!profiles.channelExists(tenant, channel)
                || !Objects.equals(profiles.find(tenant, channel, claim.clientRef()), user)
                || !"active".equals(users.require(claim.tenant(), user).getStatus())) throw unauthorized();
        return claim;
    }

    public void authenticate(HttpServletRequest request) {
        request.setAttribute(ATTRIBUTE, verify(request.getHeader(HEADER)));
    }

    /** 没有新ThreadLocal；认证事实只存于当前请求，SSE交接由原入口先完成授权。 */
    public static Principal current() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) return null;
        HttpServletRequest request = servlet.getRequest();
        if (!ExternalAuthPaths.SOURCE_CHANNEL.equals(request.getAttribute(ExternalAuthPaths.ATTR_AUTH_SOURCE))) return null;
        Object principal = request.getAttribute(ATTRIBUTE);
        if (!(principal instanceof Principal value)) throw unauthorized();
        return value;
    }

    public static void requireEndUser(String endUser) {
        Principal principal = current();
        if (principal != null && !principal.endUser().equals(endUser))
            throw new ApiException(HttpStatus.FORBIDDEN, "会话不属于当前终端用户");
    }

    public static void requireClientRef(String ref) {
        Principal principal = current();
        if (principal != null && !principal.clientRef().equals(ref))
            throw new ApiException(HttpStatus.FORBIDDEN, "客户端不属于当前终端用户");
    }

    public static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "CHAT_USER_IDENTITY_REQUIRED", "聊天身份无效或已过期，请重新登记", false);
    }

    public record Principal(String schema, String tenant, String channel, String endUser,
            String clientRef, long expiresAt) {
        @Override public String toString() { return "ChatEndUserPrincipal[REDACTED]"; }
    }
}
