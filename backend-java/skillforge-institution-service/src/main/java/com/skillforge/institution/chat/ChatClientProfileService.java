package com.skillforge.institution.chat;

import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.application.EndUserService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 登记显式本机身份；同一租户、渠道和 client_ref 的并发请求只创建一个终端用户。 */
@Service
public class ChatClientProfileService {
    private final ChatClientProfileMapper mapper;
    private final EndUserService users;
    private final ChatEndUserTokens tokens;

    public ChatClientProfileService(ChatClientProfileMapper mapper, EndUserService users, ChatEndUserTokens tokens) {
        this.mapper = mapper;
        this.users = users;
        this.tokens = tokens;
    }

    /** 事务锁覆盖查询、用户创建与映射写入，失败整体回滚，重试保留首次登记昵称。 */
    @Transactional
    public Profile register(String clientRef, String displayName, String identityToken) {
        long tenant = requireContext(TenantContext.getTenantId());
        long channel = requireContext(ChannelContext.getChannelId());
        String ref;
        try {
            ref = UUID.fromString(clientRef).toString();
            if (!ref.equalsIgnoreCase(clientRef)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "client_ref 必须为 UUID");
        }
        if (displayName == null || displayName.isBlank() || displayName.trim().length() > 128) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "昵称须为 1 至 128 个字符");
        }
        if (!mapper.channelExists(tenant, channel)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "渠道不属于当前机构或已不可用");
        }
        mapper.lock("chat-client-profile:" + tenant + ":" + channel + ":" + ref);
        Long userId = mapper.find(tenant, channel, ref);
        if (userId != null || identityToken != null && !identityToken.isBlank()) {
            var principal = tokens.verify(identityToken);
            if (!ref.equals(principal.clientRef()) || !java.util.Objects.equals(userId, Long.valueOf(principal.endUser())))
                throw ChatEndUserTokens.unauthorized();
        }
        var user = userId == null ? users.create(Long.toString(tenant), displayName.trim())
                : users.require(Long.toString(tenant), userId);
        if (!"active".equals(user.getStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "当前聊天身份已停用");
        }
        if (userId == null) mapper.insert(tenant, channel, ref, user.getId());
        return new Profile(user.getId().toString(), user.getDisplayName(), Long.toString(channel),
                tokens.issue(tenant, channel, user.getId(), ref));
    }

    private static long requireContext(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // 认证缺失统一返回 401，不推断任何租户或渠道。
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "请先完成渠道认证");
    }

    public record Profile(String endUserId, String displayName, String channelId, String identityToken) {
        @Override public String toString() { return "ChatClientProfile[REDACTED]"; }
    }
}
