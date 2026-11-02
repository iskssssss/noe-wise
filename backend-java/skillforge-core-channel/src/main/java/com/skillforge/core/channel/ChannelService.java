package com.skillforge.core.channel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.channel.dto.request.ChannelAgentBindingRequest;
import com.skillforge.core.channel.dto.request.CreateChannelRequest;
import com.skillforge.core.channel.dto.request.PatchChannelRequest;
import com.skillforge.core.channel.dto.response.ApiKeyDeleteResponse;
import com.skillforge.core.channel.dto.response.ApiKeyIssueResponse;
import com.skillforge.core.channel.dto.response.ChannelDeleteResponse;
import com.skillforge.core.channel.dto.response.ChannelListResponse;
import com.skillforge.core.channel.dto.response.ChannelResponse;
import com.skillforge.core.channel.dto.response.ChannelTokenResponse;
import com.skillforge.core.channel.dto.response.CreateChannelResponse;
import com.skillforge.core.channel.dto.response.UnbindAgentResponse;
import com.skillforge.core.channel.mapper.ChannelMapper;
import com.skillforge.core.channel.model.ChannelDO;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.channel.constant.ChannelConstants;
import com.skillforge.core.channel.dto.channel.BindResultResponse;
import com.skillforge.core.channel.enums.ChannelStatus;
import com.skillforge.core.channel.enums.ChannelType;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.foundation.catalog.entitlements.ResourceKind;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import com.skillforge.core.shared.security.TokenHash;
import com.skillforge.core.foundation.application.CryptoService;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 渠道管理与凭据生命周期的唯一应用入口；租户来自认证边界，凭据不进入业务配置。 */
@Service
public class ChannelService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ChannelMapper channels;
    private final ChannelAgentBindingService bindings;
    private final CryptoService crypto;
    private final FoundationAccess access;
    private final FeatureGate featureGate;
    private final ResourceQuota resourceQuota;

    public ChannelService(ChannelMapper channels, ChannelAgentBindingService bindings, CryptoService crypto,
            FoundationAccess access, FeatureGate featureGate, ResourceQuota resourceQuota) {
        this.channels = channels;
        this.bindings = bindings;
        this.crypto = crypto;
        this.access = access;
        this.featureGate = featureGate;
        this.resourceQuota = resourceQuota;
    }

    public ChannelListResponse list(long tenantId) {
        var rows = channels.selectList(new LambdaQueryWrapper<ChannelDO>()
                .eq(ChannelDO::getTenantId, tenantId).orderByDesc(ChannelDO::getCreatedAt));
        var summaries = bindingSummaries(tenantId, rows.stream().map(ChannelDO::getId).toList());
        return new ChannelListResponse(rows.size(), rows.stream()
                .map(row -> response(row, summaries.getOrDefault(row.getId(), List.of()))).toList());
    }

    @Transactional
    public CreateChannelResponse create(long tenantId, CreateChannelRequest request) {
        // 权限、租户功能与真实资源配额在同一创建事务内拒绝，凭据尚未签发。
        access.require("foundation.channels.create");
        if (request.agentBindings() != null && !request.agentBindings().isEmpty()) {
            access.require("foundation.channels.update");
        }
        featureGate.require(Long.toString(tenantId), "channels");
        resourceQuota.requireCapacity(Long.toString(tenantId), ResourceKind.CHANNEL, 1);
        requireBusinessConfig(request.config());
        var row = new ChannelDO();
        row.setId(SnowflakeIds.nextId());
        row.setTenantId(tenantId);
        row.setName(request.name() == null || request.name().isBlank() ? ChannelConstants.DEFAULT_NAME : request.name());
        row.setChannelType((request.channelType() == null ? ChannelType.WEB_WIDGET : request.channelType()).code());
        row.setConfig(request.config() == null ? Map.of() : request.config());
        row.setUiConfig(request.uiConfig() == null ? Map.of() : request.uiConfig());
        row.setStatus(ChannelStatus.ACTIVE.code());
        String token = token(ChannelConstants.TOKEN_PREFIX, 24);
        row.setChannelTokenHash(TokenHash.sha256Hex(token));
        row.setChannelTokenPrefix(token.substring(0, 8));
        channels.insert(row);
        if (request.agentBindings() != null && !request.agentBindings().isEmpty()) {
            bindings.replaceBindings(tenantId, row.getId(), request.agentBindings());
        }
        return new CreateChannelResponse(row.getId(), row.getName(), ChannelType.fromCode(row.getChannelType()),
                ChannelStatus.ACTIVE, token, row.getConfig(), row.getUiConfig());
    }

    public ChannelResponse get(long tenantId, long channelId) {
        return response(requireChannel(tenantId, channelId, false));
    }

    /** 配置更新与凭据轮换共享渠道行锁；不得用旧实体覆盖并发写入的凭据。 */
    @Transactional
    public ChannelResponse update(long tenantId, long channelId, PatchChannelRequest request) {
        requireBusinessConfig(request.config());
        var row = requireChannel(tenantId, channelId, true);
        if (request.name() != null) {
            row.setName(request.name());
        }
        if (request.config() != null) {
            var config = new LinkedHashMap<>(row.getConfig());
            config.putAll(request.config());
            row.setConfig(config);
        }
        if (request.uiConfig() != null) {
            row.setUiConfig(request.uiConfig());
        }
        if (request.status() != null) {
            row.setStatus(request.status().code());
        }
        channels.updateById(row);
        return response(row);
    }

    @Transactional
    public ChannelDeleteResponse disable(long tenantId, long channelId) {
        var row = requireChannel(tenantId, channelId, true);
        row.setStatus(ChannelStatus.DISABLED.code());
        channels.updateById(row);
        return new ChannelDeleteResponse(ChannelStatus.DISABLED, Long.toString(channelId));
    }

    @Transactional
    public ChannelTokenResponse rotateToken(long tenantId, long channelId) {
        var row = requireChannel(tenantId, channelId, true);
        String token = token(ChannelConstants.TOKEN_PREFIX, 24);
        row.setChannelTokenHash(TokenHash.sha256Hex(token));
        row.setChannelTokenPrefix(token.substring(0, 8));
        channels.updateById(row);
        return new ChannelTokenResponse(Long.toString(channelId), token);
    }

    public BindResultResponse bindAgents(long tenantId, long channelId, List<ChannelAgentBindingRequest> requests) {
        return new BindResultResponse(ChannelConstants.STATUS_OK, bindings.replaceBindings(tenantId, channelId, requests));
    }

    public UnbindAgentResponse unbindAgent(long tenantId, long channelId, long agentId) {
        bindings.unbind(tenantId, channelId, agentId);
        return new UnbindAgentResponse(ChannelConstants.STATUS_UNBOUND, Long.toString(channelId), Long.toString(agentId));
    }

    /** 签发和轮换均以新随机凭据替换旧凭据，明文只随本次响应返回。 */
    @Transactional
    public ApiKeyIssueResponse issueApiKey(long tenantId, long channelId) {
        var row = requireChannel(tenantId, channelId, true);
        if (!ChannelType.OPENAPI.code().equals(row.getChannelType())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "非 OpenAPI 渠道不支持 API Key");
        }
        String apiKey = token(ChannelConstants.API_KEY_PREFIX, 32);
        String secret = token("", 48);
        channels.updateApiCredentials(tenantId, channelId, TokenHash.sha256Hex(apiKey), crypto.encrypt(secret));
        return new ApiKeyIssueResponse(channelId, apiKey, secret);
    }

    @Transactional
    public ApiKeyDeleteResponse revokeApiKey(long tenantId, long channelId) {
        requireChannel(tenantId, channelId, true);
        channels.updateApiCredentials(tenantId, channelId, null, null);
        return new ApiKeyDeleteResponse(channelId, ChannelConstants.STATUS_REVOKED);
    }

    private ChannelDO requireChannel(long tenantId, long channelId, boolean lock) {
        if (lock && channels.lockOwnedChannel(tenantId, channelId) == null) {
            throw missing();
        }
        var row = channels.selectOne(new LambdaQueryWrapper<ChannelDO>()
                .eq(ChannelDO::getTenantId, tenantId).eq(ChannelDO::getId, channelId));
        if (row == null) {
            throw missing();
        }
        return row;
    }

    private static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "渠道不存在");
    }

    private static void requireBusinessConfig(Map<String, Object> config) {
        if (config != null && (config.containsKey("api_key_hash") || config.containsKey("api_secret_encrypted"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "渠道配置不能包含凭据字段");
        }
    }

    private ChannelResponse response(ChannelDO row) {
        return response(row, bindingSummaries(row.getTenantId(), List.of(row.getId())).getOrDefault(row.getId(), List.of()));
    }

    private static ChannelResponse response(ChannelDO row, List<ChannelResponse.BindingItem> agentBindings) {
        return new ChannelResponse(row.getId(), row.getName(), ChannelType.fromCode(row.getChannelType()),
                ChannelStatus.fromCode(row.getStatus()), row.getChannelTokenPrefix() + "***", row.getConfig(),
                row.getUiConfig(), agentBindings, row.getApiKeyHash() != null);
    }

    private Map<Long, List<ChannelResponse.BindingItem>> bindingSummaries(long tenantId, List<Long> ids) {
        Map<Long, List<ChannelResponse.BindingItem>> result = new HashMap<>();
        if (!ids.isEmpty()) {
            for (var item : channels.bindingSummaries(tenantId, ids)) {
                result.computeIfAbsent(item.channelId(), ignored -> new ArrayList<>())
                        .add(new ChannelResponse.BindingItem(item.agentId(), item.name(), item.priority(),
                                item.trafficPercentage(), item.isDefault()));
            }
        }
        return result;
    }

    private static String token(String prefix, int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return prefix + HexFormat.of().formatHex(value);
    }
}
