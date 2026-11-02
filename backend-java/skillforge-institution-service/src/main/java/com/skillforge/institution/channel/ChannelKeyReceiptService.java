package com.skillforge.institution.channel;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.channel.ChannelService;
import com.skillforge.core.channel.dto.response.ApiKeyIssueResponse;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 凭据签发与不含明文的回执同事务提交。 */
@Service
public class ChannelKeyReceiptService {
    private final FoundationAccess access;
    private final ChannelService channels;
    private final ChannelKeyReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;

    public ChannelKeyReceiptService(FoundationAccess access, ChannelService channels,
            ChannelKeyReceiptMapper receipts, CapabilityCatalogDatabaseContext context) {
        this.access = access; this.channels = channels; this.receipts = receipts; this.context = context;
    }

    @Transactional
    public Result issue(long tenant, UUID requestId, long channelId) {
        access.require("foundation.channels.update");
        String actor = subject(tenant);
        context.bindTenant(tenant);
        var existing = receipts.find(tenant, requestId.toString());
        if (existing != null) return replay(requestId, channelId, actor, existing);
        channels.get(tenant, channelId);
        if (receipts.claim(tenant, requestId.toString(), actor, channelId) == 0) {
            var original = receipts.find(tenant, requestId.toString());
            if (original == null) throw new IllegalStateException("渠道凭据原回执缺失");
            return replay(requestId, channelId, actor, original);
        }
        ApiKeyIssueResponse issued;
        try {
            issued = channels.issueApiKey(tenant, channelId);
        } catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError() || failure.getStatus().value() == 408) throw failure;
            throw new ApiException(failure.getStatus(), "CHANNEL_KEY_REJECTED", String.valueOf(failure.getDetail()), false);
        }
        return new Result(requestId, Long.toString(channelId), issued.apiKey(), issued.apiSecret());
    }

    @Transactional(readOnly = true)
    public Result result(long tenant, UUID requestId) {
        access.require("foundation.channels.read");
        String actor = subject(tenant);
        context.bindTenant(tenant);
        var original = receipts.find(tenant, requestId.toString());
        if (original == null || !actor.equals(original.actorRef())) return null;
        channels.get(tenant, original.channelId());
        return new Result(requestId, Long.toString(original.channelId()), null, null);
    }

    private Result replay(UUID requestId, long channelId, String actor, ChannelKeyReceiptMapper.Row original) {
        if (!actor.equals(original.actorRef()) || channelId != original.channelId())
            throw new ApiException(HttpStatus.CONFLICT, "渠道凭据请求标识已用于其他操作");
        return new Result(requestId, Long.toString(channelId), null, null);
    }

    private String subject(long tenant) {
        var subject = access.currentSubject();
        if (subject.platform() || !Long.toString(tenant).equals(subject.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "机构身份与当前租户不匹配");
        return subject.memberId();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(@JsonProperty("request_id") UUID requestId,
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("api_key") String apiKey, @JsonProperty("api_secret") String apiSecret) { }
}
