package com.skillforge.institution.channel;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.channel.ChannelService;
import com.skillforge.core.channel.dto.request.CreateChannelRequest;
import com.skillforge.core.channel.dto.response.CreateChannelResponse;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 创建及绑定与不含凭据的回执共同提交。 */
@Service
public class ChannelOnboardingService {
    private final FoundationAccess access;
    private final ChannelService channels;
    private final ChannelCreateReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;
    private final ObjectMapper json;

    public ChannelOnboardingService(FoundationAccess access, ChannelService channels,
            ChannelCreateReceiptMapper receipts, CapabilityCatalogDatabaseContext context, ObjectMapper json) {
        this.access = access; this.channels = channels; this.receipts = receipts; this.context = context; this.json = json;
    }

    @Transactional
    public Result create(long tenant, Command command) {
        if (command == null || command.requestId() == null || command.channel() == null)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "渠道创建请求无效");
        access.require("foundation.channels.create");
        if (command.channel().agentBindings() != null && !command.channel().agentBindings().isEmpty())
            access.require("foundation.channels.update");
        var actor = subject(tenant);
        String key = command.requestId().toString();
        String fingerprint = fingerprint(command.channel());
        context.bindTenant(tenant);
        if (receipts.claim(tenant, key, actor, fingerprint) == 0) {
            var row = receipts.find(tenant, key);
            if (row == null || !actor.equals(row.actorRef()) || !fingerprint.equals(row.fingerprint()))
                throw new ApiException(HttpStatus.CONFLICT, "渠道创建请求标识已用于不同内容或操作人");
            return new Result(command.requestId(), row.channelId().toString(), null);
        }
        CreateChannelResponse created;
        try {
            created = channels.create(tenant, command.channel());
        } catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError() || failure.getStatus().value() == 408) throw failure;
            throw new ApiException(failure.getStatus(), "CHANNEL_ONBOARDING_REJECTED", String.valueOf(failure.getDetail()), false);
        }
        if (receipts.complete(tenant, key, created.id()) != 1)
            throw new IllegalStateException("渠道创建回执写入失败");
        return new Result(command.requestId(), created.id().toString(), created);
    }

    @Transactional(readOnly = true)
    public Result result(long tenant, UUID requestId) {
        access.require("foundation.channels.read");
        String actor = subject(tenant);
        context.bindTenant(tenant);
        var row = receipts.find(tenant, requestId.toString());
        if (row == null || !actor.equals(row.actorRef())) return null;
        return new Result(requestId, row.channelId().toString(), null);
    }

    private String subject(long tenant) {
        var subject = access.currentSubject();
        if (subject.platform() || !Long.toString(tenant).equals(subject.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "机构身份与当前租户不匹配");
        return subject.memberId();
    }

    private String fingerprint(CreateChannelRequest channel) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(channel)));
        } catch (NoSuchAlgorithmException | JsonProcessingException failure) {
            throw new IllegalStateException("渠道创建请求无法编码", failure);
        }
    }

    public record Command(@JsonProperty("request_id") UUID requestId, CreateChannelRequest channel) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(@JsonProperty("request_id") UUID requestId, @JsonProperty("channel_id") String channelId,
            CreateChannelResponse channel) { }
}
