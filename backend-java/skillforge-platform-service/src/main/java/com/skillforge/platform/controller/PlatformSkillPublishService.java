package com.skillforge.platform.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.skillforge.core.capability.*;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 原操作身份先持久化；内容保存、发布与成功回执在同一事务提交。 */
@Service
public class PlatformSkillPublishService {
    private final FoundationAccess access;
    private final SkillDraftService drafts;
    private final PlatformSkillPublishReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public PlatformSkillPublishService(FoundationAccess access, SkillDraftService drafts, PlatformSkillPublishReceiptMapper receipts,
            CapabilityCatalogDatabaseContext context, ObjectMapper json, PlatformTransactionManager manager) {
        this.access = access; this.drafts = drafts; this.receipts = receipts; this.context = context; this.json = json;
        this.transactions = new TransactionTemplate(manager);
    }

    public Receipt publish(Command command) {
        if (command == null || command.requestId() == null || command.draftId() == null || command.draftId() <= 0
                || command.revision() <= 0 || command.definitionRevision() <= 0
                || (command.requiredCapabilities() != null && command.requiredCapabilities().stream()
                        .anyMatch(value -> value == null || value.type() == null || value.capabilityId() == null || value.capabilityId().isBlank())))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, PlatformSkillPublishController.PRE_DISPATCH_REJECTED,
                    "技能发布请求参数无效", false);
        FoundationAccess.Subject subject;
        try {
            access.require("foundation.skills.update");
            access.require("foundation.skills.publish");
            subject = access.currentSubject();
            if (!subject.platform())
                throw new ApiException(HttpStatus.FORBIDDEN, "仅平台账号可发布公共技能");
        } catch (ApiException failure) {
            if (failure.getStatus() != HttpStatus.UNAUTHORIZED && failure.getStatus() != HttpStatus.FORBIDDEN) throw failure;
            throw new ApiException(failure.getStatus(), PlatformSkillPublishController.PRE_DISPATCH_REJECTED,
                    String.valueOf(failure.getDetail()), false);
        }
        String actor = subject.memberId();
        String fingerprint = fingerprint(command);
        Receipt previous = transactions.execute(status -> {
            context.bindPlatform();
            if (receipts.claim(command.requestId().toString(), actor, fingerprint) == 1) return null;
            var row = receipts.find(command.requestId().toString());
            if (row == null || !actor.equals(row.actorRef()) || !fingerprint.equals(row.fingerprint()))
                throw new ApiException(HttpStatus.CONFLICT, "技能发布请求标识已用于不同参数或操作人");
            return receipt(command.requestId(), row);
        });
        if (previous != null) return previous;
        try {
            return transactions.execute(status -> {
                context.bindPlatform();
                var result = drafts.saveAndPublishSelected(CapabilityCatalogScope.platform(), command.draftId(),
                        command.revision(), command.definitionRevision(), command.content(), command.selections());
                var saved = new Receipt(command.requestId(), "SUCCEEDED", result.draftId(), result.definitionId(),
                        result.revision(), result.publication(), result.validation(), null, null);
                complete(saved);
                return saved;
            });
        } catch (SkillDraftService.ValidationFailure failure) {
            return reject(command.requestId(), "SKILL_PACKAGE_INVALID", failure.getMessage(), failure.validation());
        } catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError()) throw failure;
            return reject(command.requestId(), failure.getCode(), String.valueOf(failure.getDetail()), null);
        }
    }

    @Transactional(readOnly = true)
    public Receipt result(UUID requestId) {
        access.require("foundation.skills.read");
        var subject = access.currentSubject();
        if (!subject.platform())
            throw new ApiException(HttpStatus.FORBIDDEN, "仅平台账号可发布公共技能");
        context.bindPlatform();
        var row = receipts.find(requestId.toString());
        if (row == null || !subject.memberId().equals(row.actorRef())) return null;
        return receipt(requestId, row);
    }

    private Receipt reject(UUID requestId, String code, String message, SkillDraftValidation validation) {
        var result = new Receipt(requestId, "REJECTED", null, null, null, null, validation, code, message);
        transactions.executeWithoutResult(status -> { context.bindPlatform(); complete(result); });
        return result;
    }
    private void complete(Receipt receipt) {
        if (receipts.complete(receipt.requestId().toString(), receipt.status(), encode(receipt)) != 1)
            throw new IllegalStateException("技能发布回执写入失败");
    }
    private Receipt receipt(UUID requestId, PlatformSkillPublishReceiptMapper.Row row) {
        if ("PROCESSING".equals(row.status())) return new Receipt(requestId, "PROCESSING", null, null, null, null, null, null, null);
        try { return json.readValue(row.responseJson(), Receipt.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("技能发布回执无法读取", failure); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("技能发布请求无法编码", failure); }
    }
    private String fingerprint(Command command) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(command))); }
        catch (NoSuchAlgorithmException | JsonProcessingException failure) { throw new IllegalStateException("技能发布请求无法编码", failure); }
    }

    public record Command(@JsonProperty("request_id") UUID requestId,
            @JsonProperty("draft_id") Long draftId, long revision,
            @JsonProperty("definition_revision") long definitionRevision,
            @JsonProperty("version_key") String versionKey,
            @JsonProperty("files") @JsonDeserialize(using = SkillDraftFilesDeserializer.class) Map<String, String> files,
            @JsonProperty("model_invocable") boolean modelInvocable,
            @JsonProperty("user_invocable") boolean userInvocable,
            @JsonProperty("activation_mode") SkillActivationMode activationMode,
            @JsonProperty("conflict_group") String conflictGroup,
            @JsonProperty("required_capabilities") List<SkillDependencySelection> requiredCapabilities) {
        SkillDraftContent content() {
            return new SkillDraftContent(versionKey, files, modelInvocable, userInvocable, activationMode, conflictGroup, List.of());
        }
        List<SkillDependencySelection> selections() { return requiredCapabilities == null ? List.of() : requiredCapabilities; }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Receipt(@JsonProperty("request_id") UUID requestId, String status,
            @JsonProperty("draft_id") @JsonSerialize(using = ToStringSerializer.class) Long draftId,
            @JsonProperty("definition_id") @JsonSerialize(using = ToStringSerializer.class) Long definitionId,
            Long revision, SkillDraftPublication publication, SkillDraftValidation validation,
            @JsonProperty("error_code") String errorCode, @JsonProperty("error_message") String errorMessage) { }
}
