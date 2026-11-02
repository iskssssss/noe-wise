package com.skillforge.platform.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.shared.exception.ApiException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 模型草稿和原请求回执同事务提交；不保存请求正文或凭据。 */
@Service
public class PlatformModelCreationService {
    private final FoundationAccess access;
    private final PlatformModelCatalogService models;
    private final PlatformModelCreateReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;
    private final ObjectMapper json;
    public PlatformModelCreationService(FoundationAccess access, PlatformModelCatalogService models,
            PlatformModelCreateReceiptMapper receipts, CapabilityCatalogDatabaseContext context, ObjectMapper json) {
        this.access = access; this.models = models; this.receipts = receipts; this.context = context; this.json = json;
    }
    @Transactional
    public Result create(Command command) {
        if (command == null || command.requestId() == null || command.payload() == null)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "模型创建请求无效");
        access.require("foundation.models.create");
        String actor = subject(), key = command.requestId().toString(), fingerprint = fingerprint(command.payload());
        context.bindPlatform();
        if (receipts.claim(key, actor, fingerprint) == 0) {
            var row = receipts.find(key);
            if (row == null || !actor.equals(row.actorRef()) || !fingerprint.equals(row.fingerprint()))
                throw new ApiException(HttpStatus.CONFLICT, "模型创建请求标识已用于不同内容或操作人");
            return new Result(command.requestId(), row.modelId());
        }
        var created = models.create(command.payload(), actor);
        if (receipts.complete(key, created.id()) != 1) throw new IllegalStateException("模型创建回执写入失败");
        return new Result(command.requestId(), created.id());
    }
    @Transactional(readOnly = true)
    public Result result(UUID requestId) {
        access.require("foundation.models.read");
        String actor = subject();
        context.bindPlatform();
        var row = receipts.find(requestId.toString());
        return row == null || !actor.equals(row.actorRef()) ? null : new Result(requestId, row.modelId());
    }
    private String subject() {
        var subject = access.currentSubject();
        if (!subject.platform()) throw new ApiException(HttpStatus.FORBIDDEN, "仅平台账号可创建模型");
        return subject.memberId();
    }
    private String fingerprint(ModelWrite payload) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(payload))); }
        catch (NoSuchAlgorithmException | JsonProcessingException failure) { throw new IllegalStateException("模型创建请求无法编码", failure); }
    }
    public record Command(@JsonProperty("request_id") UUID requestId, ModelWrite payload) { }
    public record Result(@JsonProperty("request_id") UUID requestId, @JsonProperty("model_id") String modelId) { }
}
