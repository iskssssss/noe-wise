package com.skillforge.core.agent.management;

import com.skillforge.core.agent.AgentService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.agent.constant.AgentConstants;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.agent.model.AgentDelegationConfig;
import com.skillforge.core.foundation.runtimeconfig.AgentBudgetValidation;
import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.shared.exception.ApiException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 独立管理、模板安装和公共部署共同使用的 Agent 配置规则。 */
@Service
public class AgentConfigurationPolicy {
    private final AgentService agents;
    private final PlatformModelCatalogService models;
    private final com.skillforge.core.foundation.settings.OperationalSettingsService settings;

    public AgentConfigurationPolicy(AgentService agents, PlatformModelCatalogService models,
            com.skillforge.core.foundation.settings.OperationalSettingsService settings) {
        this.agents = agents;
        this.models = models;
        this.settings = settings;
    }

    public record ReadyConfiguration(long tenantId, String systemPrompt, Long modelConfigId,
            AgentExecutionConfig executionConfig, Map<String, Object> config) {
        public ReadyConfiguration { config = AgentManagementContracts.immutableConfig(config); }
    }

    public record DelegationSource(long tenantId, long agentId, AgentDelegationConfig previous) { }

    /** 启用前检查实际可运行配置，所有创建入口采用相同条件。 */
    public void requireReady(ReadyConfiguration config) {
        requireConfig(config.config());
        if (config.systemPrompt() == null || config.systemPrompt().isBlank())
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Agent 未配置 system prompt，请先配置后再发布或启用");
        var model = config.modelConfigId() == null ? models.resolveDefault(Long.toString(config.tenantId()), "LLM")
                : models.requireEnabled(Long.toString(config.modelConfigId()), "LLM");
        long timeout = Math.min(model.timeoutSeconds(), settings.read("calls").integer("chat_timeout_seconds"));
        AgentBudgetValidation.validateModelMinimum(config.executionConfig(), model.maxOutputTokens(), timeout);
    }

    public Long requireLlmModelId(String modelConfigId) {
        if (modelConfigId == null || modelConfigId.isBlank()) return null;
        try {
            long id = Long.parseLong(modelConfigId);
            if (id <= 0) throw new NumberFormatException("non-positive");
            models.requireEnabled(modelConfigId, "LLM");
            return id;
        } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "model_config_id 必须是正整数字符串");
        }
    }

    /** 启用时校验全部目标；撤销授权时允许保留停用旧目标，新增目标仍需校验。 */
    public AgentDelegationConfig requireDelegationConfig(DelegationSource source, AgentDelegationConfig config) {
        Set<Long> targetIds = new LinkedHashSet<>();
        for (String targetAgentId : config.targetAgentIds()) {
            long targetId = requireDelegationTargetId(targetAgentId);
            if (targetId == source.agentId())
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "delegation_config.target_agent_ids 不可包含当前 Agent");
            if (!targetIds.add(targetId))
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "delegation_config.target_agent_ids 不可包含重复 Agent");
        }
        Set<Long> targetsToValidate = new LinkedHashSet<>(targetIds);
        if (!config.enabled()) {
            List<String> previousTargets = source.previous().targetAgentIds();
            targetsToValidate.removeIf(id -> previousTargets.contains(Long.toString(id)));
        }
        if (!targetsToValidate.isEmpty()) {
            List<AgentDO> publishedTargets = agents.list(new LambdaQueryWrapper<AgentDO>()
                    .eq(AgentDO::getTenantId, source.tenantId())
                    .eq(AgentDO::getStatus, AgentConstants.STATUS_PUBLISHED)
                    .in(AgentDO::getId, targetsToValidate));
            if (publishedTargets.size() != targetsToValidate.size())
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "delegation_config.target_agent_ids 必须是同租户已发布的 Agent");
        }
        return config;
    }

    private long requireDelegationTargetId(String targetAgentId) {
        if (targetAgentId == null || !targetAgentId.matches("[1-9][0-9]*"))
            throw invalidDelegationId();
        try { return Long.parseLong(targetAgentId); }
        catch (NumberFormatException exception) { throw invalidDelegationId(); }
    }

    private ApiException invalidDelegationId() {
        return new ApiException(HttpStatus.BAD_REQUEST, "delegation_config.target_agent_ids 必须是正整数 ID");
    }

    /** 缺省关闭自动提取，只接受 JSON 布尔值。 */
    public static Map<String, Object> requireConfig(Map<String, Object> value) {
        if (value == null) return Map.of();
        if (value.containsKey("memory")) {
            if (!(value.get("memory") instanceof Map<?, ?> memory))
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "config.memory 必须是对象");
            if (memory.containsKey("automatic_extraction") && !(memory.get("automatic_extraction") instanceof Boolean))
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "config.memory.automatic_extraction 必须是布尔值");
        }
        return Map.copyOf(value);
    }
}
