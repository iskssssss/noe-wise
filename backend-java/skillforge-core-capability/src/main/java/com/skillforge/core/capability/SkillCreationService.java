package com.skillforge.core.capability;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 技能身份、初始草稿与原请求回执在同一事务内提交。 */
@Service
public class SkillCreationService {
    private final SkillDraftService drafts;
    private final SkillCreateReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;
    private final FoundationAccess access;
    private final ObjectMapper json;

    public SkillCreationService(SkillDraftService drafts, SkillCreateReceiptMapper receipts,
            CapabilityCatalogDatabaseContext context, FoundationAccess access, ObjectMapper json) {
        this.drafts = drafts; this.receipts = receipts; this.context = context; this.access = access; this.json = json;
    }

    @Transactional
    public Result create(CapabilityCatalogScope scope, Command command) {
        if (command == null || command.requestId() == null || command.payload() == null)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "技能创建请求无效");
        access.require("foundation.capabilities.create");
        access.require("foundation.skills.create");
        String actor = subject(scope), requestId = command.requestId().toString();
        String fingerprint = fingerprint(command.payload());
        bind(scope);
        if (receipts.claim(requestId, scope.tenantId(), actor, fingerprint) == 0) {
            var row = receipts.find(requestId);
            if (row == null || !actor.equals(row.actorRef()) || !fingerprint.equals(row.fingerprint()))
                throw new ApiException(HttpStatus.CONFLICT, "SKILL_CREATE_IDENTITY_CONFLICT", "技能创建请求标识已用于不同内容或操作人", false);
            return result(command.requestId(), row);
        }
        var payload = command.payload();
        var draft = drafts.createSkill(scope, payload.capabilityKey(), payload.name(), payload.description());
        if (receipts.complete(requestId, draft.definitionId(), draft.id(), draft.revision()) != 1)
            throw new IllegalStateException("技能创建回执写入失败");
        return new Result(command.requestId(), draft.definitionId().toString(), draft.id().toString(), draft.revision());
    }

    @Transactional(readOnly = true)
    public Result result(CapabilityCatalogScope scope, UUID requestId) {
        access.require("foundation.skills.read");
        String actor = subject(scope);
        bind(scope);
        var row = receipts.find(requestId.toString());
        return row == null || !actor.equals(row.actorRef()) ? null : result(requestId, row);
    }

    private Result result(UUID requestId, SkillCreateReceiptMapper.Row row) {
        return new Result(requestId, row.definitionId(), row.draftId(), row.revision());
    }
    private String subject(CapabilityCatalogScope scope) {
        var subject = access.currentSubject();
        if (scope.isPlatform() != subject.platform() || (!scope.isPlatform() && !scope.tenantId().toString().equals(subject.tenantId())))
            throw new ApiException(HttpStatus.FORBIDDEN, "技能创建身份与当前作用域不匹配");
        return subject.memberId();
    }
    private void bind(CapabilityCatalogScope scope) {
        if (scope.isPlatform()) context.bindPlatform(); else context.bindTenant(scope.tenantId());
    }
    private String fingerprint(Payload payload) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(payload))); }
        catch (NoSuchAlgorithmException | JsonProcessingException failure) { throw new IllegalStateException("技能创建请求无法编码", failure); }
    }
    public record Command(@JsonProperty("request_id") UUID requestId, Payload payload) { }
    public record Payload(@JsonProperty("capability_key") String capabilityKey, String name, String description) { }
    public record Result(@JsonProperty("request_id") UUID requestId, @JsonProperty("definition_id") String definitionId,
            @JsonProperty("draft_id") String draftId, long revision) { }
}
