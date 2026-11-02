package com.skillforge.platform.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.agent.systemagent.SystemAgentService;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 初始草稿和原请求回执同事务提交。 */
@Service
public class SystemAgentCreationService {
    private final FoundationAccess access;
    private final SystemAgentService agents;
    private final SystemAgentCreateReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;
    private final ObjectMapper json;
    public SystemAgentCreationService(FoundationAccess access, SystemAgentService agents,
            SystemAgentCreateReceiptMapper receipts, CapabilityCatalogDatabaseContext context, ObjectMapper json) {
        this.access = access; this.agents = agents; this.receipts = receipts; this.context = context; this.json = json;
    }
    @Transactional
    public Result create(Command command) {
        if (command == null || command.requestId() == null || command.payload() == null)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "系统智能体创建请求无效");
        access.require("foundation.system_agents.create");
        var payload = command.payload();
        if (payload.systemPrompt() != null || payload.modelConfigId() != null)
            access.require("foundation.system_agents.update");
        String actor = subject(), key = command.requestId().toString(), fingerprint = fingerprint(payload);
        context.bindPlatform();
        if (receipts.claim(key, actor, fingerprint) == 0) {
            var row = receipts.find(key);
            if (row == null || !actor.equals(row.actorRef()) || !fingerprint.equals(row.fingerprint()))
                throw new ApiException(HttpStatus.CONFLICT, "系统智能体创建请求标识已用于不同内容或操作人");
            return new Result(command.requestId(), row.agentId());
        }
        var created = agents.create(payload.agentKey(), payload.name(), payload.description(), payload.executionMode(),
                payload.systemPrompt(), payload.modelConfigId(), actor);
        if (receipts.complete(key, created.id()) != 1) throw new IllegalStateException("系统智能体创建回执写入失败");
        return new Result(command.requestId(), created.id());
    }
    @Transactional(readOnly = true)
    public Result result(UUID requestId) {
        access.require("foundation.system_agents.read");
        String actor = subject();
        context.bindPlatform();
        var row = receipts.find(requestId.toString());
        return row == null || !actor.equals(row.actorRef()) ? null : new Result(requestId, row.agentId());
    }
    private String subject() {
        var subject = access.currentSubject();
        if (!subject.platform()) throw new ApiException(HttpStatus.FORBIDDEN, "仅平台账号可创建系统智能体");
        return subject.memberId();
    }
    private String fingerprint(Payload payload) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(payload))); }
        catch (NoSuchAlgorithmException | JsonProcessingException failure) { throw new IllegalStateException("系统智能体创建请求无法编码", failure); }
    }
    public record Command(@JsonProperty("request_id") UUID requestId, Payload payload) { }
    public record Payload(@JsonProperty("agent_key") String agentKey, String name, String description,
            @JsonProperty("execution_mode") String executionMode, @JsonProperty("system_prompt") String systemPrompt,
            @JsonProperty("model_config_id") String modelConfigId) { }
    public record Result(@JsonProperty("request_id") UUID requestId, @JsonProperty("agent_id") String agentId) { }
}
