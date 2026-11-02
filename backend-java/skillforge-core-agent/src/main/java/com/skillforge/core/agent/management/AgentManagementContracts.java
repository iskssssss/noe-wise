package com.skillforge.core.agent.management;

import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import com.skillforge.core.agent.enums.AgentStatus;
import com.skillforge.core.agent.model.AgentDelegationConfig;
import com.skillforge.core.agent.model.AgentTemplateDefinition;
import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Agent 管理的应用命令与只读结果；不承载认证或持久化框架对象。 */
public final class AgentManagementContracts {
    private AgentManagementContracts() { }

    /** 身份只由已认证宿主构造，external 不从请求正文读取。 */
    public record Caller(long tenantId, boolean external) {
        public Caller {
            if (tenantId <= 0) throw new IllegalArgumentException("tenantId 必须为正数");
        }
    }

    public record Create(String agentKey, String name, String systemPrompt, String modelConfigId,
            Map<String, Object> config) {
        public Create { config = immutableConfig(config); }
    }

    /** 沿用当前 HTTP 更新的存在性语义，null 表示本次不修改该字段。 */
    public record Update(String name, AgentStatus status, String systemPrompt, String roleDescription,
            AgentExecutionConfig executionConfig, String modelConfigId, Map<String, Object> config,
            AgentDelegationConfig delegationConfig, String iconUrl) {
        public Update { config = immutableConfig(config); }
    }

    /** 工作草稿或历史发布的管理投影；可变持久对象不会跨越应用边界。 */
    public record Agent(long id, long tenantId, String agentKey, String name, Long templateId,
            String templateName, AgentStatus status, Instant createdAt, String systemPrompt,
            String roleDescription, AgentExecutionConfig executionConfig, Long modelConfigId,
            Map<String, Object> config, Instant publishedAt, AgentDelegationConfig delegationConfig,
            String iconUrl, int revision, Long publishedRevisionId, boolean hasDraftChanges) {
        public Agent { config = immutableConfig(config); }
    }

    /** 同一保存发布命令的不可变成功回执，不表示当前发布指针仍指向此修订。 */
    public record SaveAndPublishReceipt(java.util.UUID requestId, long agentId, long publicationId,
            int revision, Instant publishedAt) { }

    public record Detail(Agent agent, CapabilityMountSetResult mountSet) { }

    public record Template(long id, String templateKey, String name, String version, String description,
            String iconUrl, AgentTemplateDefinition definition) { }

    public record Revision(long id, int revision, Instant publishedAt, String name, boolean current) { }

    /** 开放业务配置保留原值和空值，递归隔离 JSON 对象与列表的可变引用。 */
    static Map<String, Object> immutableConfig(Map<String, Object> value) {
        if (value == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(key, immutableValue(item)));
        return Collections.unmodifiableMap(result);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(key, immutableValue(item)));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(immutableValue(item)));
            return Collections.unmodifiableList(result);
        }
        return value;
    }
}
