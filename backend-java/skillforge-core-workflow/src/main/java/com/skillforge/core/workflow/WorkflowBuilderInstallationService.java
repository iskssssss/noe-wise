package com.skillforge.core.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.workflow.constant.WorkflowConstants;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.dto.response.WorkflowInstallResponse;
import com.skillforge.core.workflow.mapper.WorkflowInstallationMapper;
import com.skillforge.core.workflow.mapper.WorkflowInstanceMapper;
import com.skillforge.core.workflow.model.WorkflowInstallationDO;
import com.skillforge.core.workflow.model.WorkflowInstanceDO;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 每次显式安装/升级原子创建完整修订；旧执行继续读取原修订。 */
@Service
public class WorkflowBuilderInstallationService {
    private final WorkflowInstanceMapper instances;
    private final WorkflowInstallationDefinitionService definitions;
    private final WorkflowInstallationMapper installations;
    private final WorkflowInstallationValidator validator;
    private final ObjectMapper json;

    public WorkflowBuilderInstallationService(WorkflowInstanceMapper instances,
            WorkflowInstallationDefinitionService definitions, WorkflowInstallationMapper installations,
            WorkflowInstallationValidator validator, ObjectMapper json) {
        this.instances = instances; this.definitions = definitions; this.installations = installations;
        this.validator = validator; this.json = json;
    }

    /** 安装键事务锁与指针 CAS 共同避免并发覆盖；校验或修订插入失败全部回滚。 */
    @Transactional(rollbackFor = Exception.class)
    public WorkflowInstallResponse installOrUpgrade(long tenant, String workflowId,
            Map<String, Object> config, String customPrompt, WorkflowInstallationBindings bindings) {
        return install(tenant,workflowId,config,customPrompt,bindings,true);
    }

    /** 后台隔离测试使用调用方已核验的精确版本；绝不隐式追随当前发布头。 */
    @Transactional(rollbackFor=Exception.class)
    public WorkflowInstallResponse installExact(long tenant,String workflowId,Map<String,Object> config,
            WorkflowInstallationBindings bindings) {
        return install(tenant,workflowId,config,"",bindings,false);
    }

    /** 组合操作锁定发布身份与安装身份，复用或显式更新，并返回精确资源身份。 */
    @Transactional(rollbackFor = Exception.class)
    public SelectedInstallation selectForMount(long tenant, String workflowId, long expectedDefinition,
            String mode, long expectedRevision, Map<String, Object> config, String customPrompt,
            WorkflowInstallationBindings bindings) {
        installations.lock(tenant, workflowId);
        long currentDefinition = definitions.lockPublishedDefinitionVersion(tenant, workflowId);
        if (currentDefinition != expectedDefinition)
            throw new ApiException(HttpStatus.CONFLICT, "工作流发布版本已变化，请刷新后重试");
        String businessId = workflowId + "_" + tenant;
        var existing = instances.selectOne(new LambdaQueryWrapper<WorkflowInstanceDO>()
                .eq(WorkflowInstanceDO::getInstanceId, businessId).eq(WorkflowInstanceDO::getTenantId, tenant));
        if (existing != null && existing.getInstallationRevision() == null)
            throw new ApiException(HttpStatus.CONFLICT, "工作流安装修订不可用");
        long currentRevision = existing == null ? 0 : existing.getInstallationRevision();
        if (currentRevision != expectedRevision || ("INSTALL".equals(mode) && existing != null)
                || (!"INSTALL".equals(mode) && existing == null))
            throw new ApiException(HttpStatus.CONFLICT, "工作流安装修订已变化，请刷新后重试");
        if ("REUSE".equals(mode)) {
            var saved = installations.find(tenant, existing.getId(), currentRevision);
            if (saved == null) throw new ApiException(HttpStatus.CONFLICT, "工作流安装修订不可用");
            var definitionBody = definitions.definition(tenant, currentDefinition);
            try { WorkflowInstallationConfiguration.resolve(definitionBody, saved.getConfig()); }
            catch (IllegalArgumentException invalid) { throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, invalid.getMessage()); }
            var savedBindings = json.convertValue(saved.getBindings(), WorkflowInstallationBindings.class);
            validator.validateForExecution(tenant, existing.getId(), definitionBody, validator.currentBindings(tenant, savedBindings));
        } else {
            install(tenant, workflowId, config, customPrompt, bindings, true);
            existing = instances.selectOne(new LambdaQueryWrapper<WorkflowInstanceDO>()
                    .eq(WorkflowInstanceDO::getInstanceId, businessId).eq(WorkflowInstanceDO::getTenantId, tenant));
        }
        var target = installations.currentTarget(tenant, existing.getId());
        if (target == null || target.definitionId() != expectedDefinition)
            throw new ApiException(HttpStatus.CONFLICT, "工作流当前安装不可用或发布版本已变化");
        return new SelectedInstallation(existing.getId(), target.installationRevision(), target.capabilityId());
    }

    public record SelectedInstallation(long instanceId, long installationRevision, long capabilityDefinitionId) {}

    private WorkflowInstallResponse install(long tenant,String workflowId,Map<String,Object> config,String customPrompt,
            WorkflowInstallationBindings bindings,boolean followCurrent) {
        installations.lock(tenant, workflowId);
        long definition = definitions.requirePublishedDefinitionVersion(tenant, workflowId);
        String businessId = workflowId + "_" + tenant;
        var instance = instances.selectOne(new LambdaQueryWrapper<WorkflowInstanceDO>()
                .eq(WorkflowInstanceDO::getInstanceId, businessId).eq(WorkflowInstanceDO::getTenantId, tenant));
        boolean create = instance == null;
        if (create) {
            instance = new WorkflowInstanceDO();
            instance.setId(SnowflakeIds.nextId()); instance.setInstanceId(businessId);
            instance.setTenantId(tenant); instance.setInstallationRevision(0L);
            instance.setStatus("draft"); instance.setDefinitionVersionId(definition);
        }
        var requested = bindings == null ? WorkflowInstallationBindings.empty() : bindings;
        var chosen = followCurrent ? validator.currentBindings(tenant,requested) : requested;
        var definitionBody = definitions.definition(tenant, definition);
        Map<String, Object> resolvedConfig;
        try { resolvedConfig = WorkflowInstallationConfiguration.resolve(definitionBody, config); }
        catch (IllegalArgumentException invalid) { throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, invalid.getMessage()); }
        validator.validateForExecution(tenant, instance.getId(), definitionBody, chosen);
        if (create && instances.insert(instance) != 1) throw new IllegalStateException("workflow_instance_insert_failed");
        long previous = instance.getInstallationRevision();
        var revision = new WorkflowInstallationDO();
        revision.setTenantId(tenant); revision.setInstanceId(instance.getId());
        revision.setInstallationRevision(Math.addExact(previous, 1)); revision.setDefinitionVersionId(definition);
        revision.setConfig(resolvedConfig);
        revision.setCustomPrompt(customPrompt == null ? "" : customPrompt);
        revision.setBindings(json.convertValue(chosen, new TypeReference<Map<String, Object>>() {}));
        revision.setCreatedAt(Instant.now());
        if (installations.insert(revision) != 1 || installations.activate(tenant, instance.getId(), previous,
                revision.getInstallationRevision(), definition) != 1)
            throw new ApiException(HttpStatus.CONFLICT, "工作流安装版本已变化，请刷新后重试");
        return new WorkflowInstallResponse(WorkflowConstants.STATUS_INSTALLED, workflowId, Long.toString(tenant),
                businessId, revision.getInstallationRevision());
    }
}
