package com.skillforge.institution.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.WorkflowDraftLifecycleService;
import com.skillforge.core.workflow.dto.response.WorkflowDraftResponse;
import com.skillforge.institution.common.TenantIds;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 创建草稿与请求回执在同一事务中提交。 */
@Service
public class WorkflowCreationService {
    private final WorkflowManagementService management;
    private final WorkflowDraftLifecycleService lifecycle;
    private final WorkflowCreateReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;
    private final FoundationAccess access;
    private final ObjectMapper json;

    public WorkflowCreationService(WorkflowManagementService management, WorkflowDraftLifecycleService lifecycle,
            WorkflowCreateReceiptMapper receipts, CapabilityCatalogDatabaseContext context, FoundationAccess access, ObjectMapper json) {
        this.management=management; this.lifecycle=lifecycle; this.receipts=receipts;
        this.context=context; this.access=access; this.json=json;
    }

    @Transactional(rollbackFor = Exception.class)
    public Result create(Command command) {
        if (command == null || command.requestId() == null || command.definition() == null)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "工作流创建请求无效");
        access.require("foundation.workflows.create");
        long tenant=Long.parseLong(TenantIds.requireTenantId());
        String actor=subject(tenant), requestId=command.requestId().toString(), fingerprint=fingerprint(command.definition());
        context.bindTenant(tenant);
        if (receipts.claim(requestId,tenant,actor,fingerprint)==0) {
            var row=receipts.find(requestId);
            if (row==null || !actor.equals(row.actorRef()) || !fingerprint.equals(row.fingerprint()))
                throw new ApiException(HttpStatus.CONFLICT,"WORKFLOW_CREATE_IDENTITY_CONFLICT","工作流创建请求标识已用于不同内容或操作人",false);
            management.require(tenant,row.workflowId(),"read");
            return new Result(command.requestId(),lifecycle.getCurrentDraft(tenant,row.workflowId()));
        }
        String workflowId="workflow-"+UUID.randomUUID();
        var definition=new LinkedHashMap<>(command.definition());
        Object originalMeta=definition.get("meta");
        Map<String,Object> meta=new LinkedHashMap<>();
        if (!(originalMeta instanceof Map<?,?> original))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"工作流元数据无效");
        original.forEach((key,value)->meta.put((String) key,value));
        meta.put("name",workflowId);
        definition.put("meta",meta);
        var created=management.create(tenant,workflowId,definition);
        if(receipts.complete(requestId,workflowId)!=1)throw new IllegalStateException("工作流创建回执写入失败");
        return new Result(command.requestId(),created);
    }

    @Transactional(readOnly=true)
    public Result result(UUID requestId) {
        access.require("foundation.workflows.read");
        long tenant=Long.parseLong(TenantIds.requireTenantId());
        String actor=subject(tenant);
        context.bindTenant(tenant);
        var row=receipts.find(requestId.toString());
        if(row==null || !actor.equals(row.actorRef()))return null;
        management.require(tenant,row.workflowId(),"read");
        return new Result(requestId,lifecycle.getCurrentDraft(tenant,row.workflowId()));
    }
    private String subject(long tenant) {
        var subject=access.currentSubject();
        if(subject.platform() || !Long.toString(tenant).equals(subject.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN,"工作流创建身份与当前机构不匹配");
        return subject.memberId();
    }
    private String fingerprint(Map<String,Object> definition) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(definition))); }
        catch (NoSuchAlgorithmException | JsonProcessingException failure) { throw new IllegalStateException("工作流创建请求无法编码",failure); }
    }
    public record Command(@JsonProperty("request_id") UUID requestId, Map<String,Object> definition) { }
    public record Result(@JsonProperty("request_id") UUID requestId, WorkflowDraftResponse draft) { }
}
