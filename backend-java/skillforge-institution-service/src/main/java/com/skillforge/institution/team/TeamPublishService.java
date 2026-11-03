package com.skillforge.institution.team;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 机构组合发布保留原操作身份；保存、冻结发布和成功回执同事务提交。 */
@Service
public class TeamPublishService {
    private final FoundationAccess access;
    private final TeamService teams;
    private final TeamPublishReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final TransactionTemplate publication;

    public TeamPublishService(FoundationAccess access, TeamService teams, TeamPublishReceiptMapper receipts,
            CapabilityCatalogDatabaseContext context, ObjectMapper json, PlatformTransactionManager manager) {
        this.access = access; this.teams = teams; this.receipts = receipts; this.context = context; this.json = json;
        this.transactions = new TransactionTemplate(manager);
        this.publication = new TransactionTemplate(manager);
        this.publication.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Receipt publish(long tenant, Command command) {
        if (command == null || command.requestId() == null || command.payload() == null
                || (command.id() != null && command.id().isBlank()))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "团队发布请求参数无效");
        FoundationAccess.Subject subject;
        try {
            access.require(command.id() == null ? "foundation.teams.create" : "foundation.teams.update");
            access.require("foundation.teams.publish");
            subject = access.currentSubject();
            if (subject.platform() || !Long.toString(tenant).equals(subject.tenantId()))
                throw new ApiException(HttpStatus.FORBIDDEN, "机构身份与当前租户不匹配");
        } catch (ApiException failure) {
            if (failure.getStatus() != HttpStatus.UNAUTHORIZED && failure.getStatus() != HttpStatus.FORBIDDEN) throw failure;
            throw new ApiException(failure.getStatus(), TeamPublishController.PRE_DISPATCH_REJECTED,
                    String.valueOf(failure.getDetail()), false);
        }
        String actor = subject.memberId();
        String fingerprint = fingerprint(command);
        Receipt previous = transactions.execute(status -> {
            context.bindTenant(tenant);
            if (receipts.claim(tenant, command.requestId().toString(), actor, fingerprint) == 1) return null;
            var row = receipts.find(tenant, command.requestId().toString());
            if (row == null || !actor.equals(row.actorRef()) || !fingerprint.equals(row.fingerprint()))
                throw new ApiException(HttpStatus.CONFLICT, "团队发布请求标识已用于不同参数或操作人");
            return receipt(command.requestId(), row);
        });
        if (previous != null) return previous;
        try {
            return publication.execute(status -> {
                context.bindTenant(tenant);
                if (!"draft".equals(command.payload().status()) && !"published".equals(command.payload().status()))
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "停用状态或无效状态不能发布");
                if (command.id() == null && command.payload().revision() != 0)
                    throw new ApiException(HttpStatus.CONFLICT, "新团队版本必须为零");
                var definition = command.payload().definition();
                for (var member : definition.members()) {
                    try {
                        long agentId = Long.parseLong(member.agentId());
                        if (agentId <= 0 || !Long.toString(agentId).equals(member.agentId()))
                            throw new NumberFormatException();
                    } catch (NumberFormatException invalid) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "团队成员身份必须为正十进制字符串", invalid);
                    }
                }
                var saved = teams.save(Long.toString(tenant), command.id(), definition);
                var published = teams.publish(Long.toString(tenant), saved.id());
                var result = new Receipt(command.requestId(), "SUCCEEDED", published, null);
                complete(tenant, result);
                return result;
            });
        } catch (ApiException failure) {
            var result = new Receipt(command.requestId(), "REJECTED", null, String.valueOf(failure.getDetail()));
            transactions.executeWithoutResult(status -> { context.bindTenant(tenant); complete(tenant, result); });
            return result;
        }
    }

    /** 对已保存的精确修订发布；原请求与结果沿用团队发布回执。 */
    public Receipt publishSaved(long tenant, SavedCommand command) {
        if (command == null || command.requestId() == null || command.id() == null || command.id().isBlank()
                || command.expectedRevision() < 1)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "团队发布请求参数无效");
        FoundationAccess.Subject subject;
        try {
            access.require("foundation.teams.publish");
            subject = access.currentSubject();
            if (subject.platform() || !Long.toString(tenant).equals(subject.tenantId()))
                throw new ApiException(HttpStatus.FORBIDDEN, "机构身份与当前租户不匹配");
        } catch (ApiException failure) {
            if (failure.getStatus() != HttpStatus.UNAUTHORIZED && failure.getStatus() != HttpStatus.FORBIDDEN) throw failure;
            throw new ApiException(failure.getStatus(), TeamPublishController.PRE_DISPATCH_REJECTED,
                    String.valueOf(failure.getDetail()), false);
        }
        String actor=subject.memberId(), fingerprint=fingerprint(command);
        Receipt previous=transactions.execute(status -> {
            context.bindTenant(tenant);
            if (receipts.claim(tenant,command.requestId().toString(),actor,fingerprint)==1) return null;
            var row=receipts.find(tenant,command.requestId().toString());
            if (row==null || !actor.equals(row.actorRef()) || !fingerprint.equals(row.fingerprint()))
                throw new ApiException(HttpStatus.CONFLICT,"团队发布请求标识已用于不同参数或操作人");
            return receipt(command.requestId(),row);
        });
        if (previous != null) return previous;
        try {
            return publication.execute(status -> {
                context.bindTenant(tenant);
                var published=teams.publish(Long.toString(tenant),command.id(),command.expectedRevision());
                var result=new Receipt(command.requestId(),"SUCCEEDED",published,null);
                complete(tenant,result);
                return result;
            });
        } catch (ApiException failure) {
            var result=new Receipt(command.requestId(),"REJECTED",null,String.valueOf(failure.getDetail()));
            transactions.executeWithoutResult(status -> { context.bindTenant(tenant); complete(tenant,result); });
            return result;
        }
    }

    @Transactional(readOnly = true)
    public Receipt result(long tenant, UUID requestId) {
        access.require("foundation.teams.read");
        var subject = access.currentSubject();
        if (subject.platform() || !Long.toString(tenant).equals(subject.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "机构身份与当前租户不匹配");
        context.bindTenant(tenant);
        var row = receipts.find(tenant, requestId.toString());
        if (row == null || !subject.memberId().equals(row.actorRef())) return null;
        return receipt(requestId, row);
    }

    private void complete(long tenant, Receipt receipt) {
        if (receipts.complete(tenant, receipt.requestId().toString(), receipt.status(), encode(receipt)) != 1)
            throw new IllegalStateException("团队发布回执写入失败");
    }
    private Receipt receipt(UUID requestId, TeamPublishReceiptMapper.Row row) {
        if ("PROCESSING".equals(row.status())) return new Receipt(requestId, "PROCESSING", null, null);
        try { return json.readValue(row.responseJson(), Receipt.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("团队发布回执无法读取", failure); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("团队发布请求无法编码", failure); }
    }
    private String fingerprint(Object command) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(command))); }
        catch (NoSuchAlgorithmException | JsonProcessingException failure) { throw new IllegalStateException("团队发布请求无法编码", failure); }
    }

    public record Command(@JsonProperty("request_id") UUID requestId, String id, TeamController.SaveRequest payload) { }
    public record SavedCommand(@JsonProperty("request_id") UUID requestId, String id,
            @JsonProperty("expected_revision") @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.StrictLongDeserializer.class) long expectedRevision) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Receipt(@JsonProperty("request_id") UUID requestId, String status, TeamDefinition team,
            @JsonProperty("error_message") String errorMessage) { }
}
