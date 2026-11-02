package com.skillforge.core.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.execution.WorkflowDefinitionContract;
import com.skillforge.core.workflow.mapper.*;
import com.skillforge.core.workflow.model.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** Workflow领域拥有源CAS、完整验证、私有副本和归档；宿主只编排公开资源回执。 */
@Service
public class WorkflowTestDefinitionService {
    private final WorkflowContainerMapper containers;
    private final WorkflowDraftRevisionMapper drafts;
    private final WorkflowDefinitionVersionMapper versions;
    private final WorkflowInstanceMapper instances;
    private final WorkflowDraftValidator validator;
    private final WorkflowDraftLifecycleService lifecycle;
    private final WorkflowBuilderInstallationService installer;
    public WorkflowTestDefinitionService(WorkflowContainerMapper containers,WorkflowDraftRevisionMapper drafts,
            WorkflowDefinitionVersionMapper versions,WorkflowInstanceMapper instances,WorkflowDraftValidator validator,
            WorkflowDraftLifecycleService lifecycle,WorkflowBuilderInstallationService installer) {
        this.containers=containers;this.drafts=drafts;this.versions=versions;this.instances=instances;this.validator=validator;
        this.lifecycle=lifecycle;this.installer=installer;
    }
    public record SourceSelection(String kind,Long draftRevision,String definitionVersionId) {}
    public record SourceSnapshot(long containerId,Map<String,Object> definition,String kind,String workflowId,
            Long draftRevision,String definitionVersionId,String hash) {}
    public record Copy(long containerId,long definitionVersionId,long capabilityVersionId,long instanceId,long installationRevision) {}
    @Transactional(readOnly=true)
    public SourceSnapshot readSource(long tenant,String workflow,SourceSelection selected) {return source(tenant,workflow,selected,false);}
    @Transactional(propagation=Propagation.MANDATORY)
    public SourceSnapshot lockSource(long tenant,String workflow,SourceSelection selected) {return source(tenant,workflow,selected,true);}
    @Transactional(propagation=Propagation.MANDATORY)
    public Copy createCopy(long tenant,String workflow,SourceSnapshot selected,Map<String,Object> config,WorkflowInstallationBindings bindings) {
        requireDefinition(selected.definition());lifecycle.create(tenant,workflow,selected.definition());
        var publication=lifecycle.publish(tenant,workflow,1);installer.installExact(tenant,workflow,config,bindings);
        var instance=instances.selectOne(new LambdaQueryWrapper<WorkflowInstanceDO>().eq(WorkflowInstanceDO::getTenantId,tenant)
                .eq(WorkflowInstanceDO::getInstanceId,workflow+"_"+tenant));
        if(instance==null)throw new IllegalStateException("workflow_test_installation_missing");
        return new Copy(lifecycle.containerId(tenant,workflow),publication.definitionVersionId(),publication.capabilityVersionId(),instance.getId(),instance.getInstallationRevision());
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void archiveCopy(long tenant,long containerId,long instanceId) {
        var container=containers.selectOne(new LambdaQueryWrapper<WorkflowContainerDO>().eq(WorkflowContainerDO::getTenantId,tenant).eq(WorkflowContainerDO::getId,containerId));
        var instance=instances.selectOne(new LambdaQueryWrapper<WorkflowInstanceDO>().eq(WorkflowInstanceDO::getTenantId,tenant).eq(WorkflowInstanceDO::getId,instanceId));
        if(container==null||instance==null)throw missing();
        container.setStatus("archived");containers.updateById(container);instance.setStatus("archived");instances.updateById(instance);
    }
    private SourceSnapshot source(long tenant,String workflowId,SourceSelection source,boolean lock) {
        if(source==null)throw invalid("workflow_test_source_invalid","必须指定已保存草稿或发布版本");
        WorkflowContainerDO container=lock?containers.lockContainer(tenant,workflowId):containers.selectOne(new LambdaQueryWrapper<WorkflowContainerDO>().eq(WorkflowContainerDO::getTenantId,tenant).eq(WorkflowContainerDO::getWorkflowId,workflowId));
        if(container==null)throw missing();if("archived".equals(container.getStatus()))throw conflict("workflow_test_source_archived","归档工作流不能创建新测试");
        Map<String,Object> definition;Long revision=null;String publication=null;
        if("draft".equals(source.kind())&&source.draftRevision()!=null&&source.definitionVersionId()==null) {
            if(!source.draftRevision().equals(container.getCurrentDraftRevision()))throw conflict("workflow_test_source_conflict","源草稿已变化，请先保存并刷新");
            var draft=drafts.selectOne(new LambdaQueryWrapper<WorkflowDraftRevisionDO>().eq(WorkflowDraftRevisionDO::getTenantId,tenant).eq(WorkflowDraftRevisionDO::getContainerId,container.getId()).eq(WorkflowDraftRevisionDO::getRevision,source.draftRevision()));
            if(draft==null)throw missing();definition=draft.getDefinition();revision=source.draftRevision();
        } else if("publication".equals(source.kind())&&source.draftRevision()==null&&source.definitionVersionId()!=null) {
            long versionId;try { versionId=Long.parseLong(source.definitionVersionId()); }catch(NumberFormatException bad){throw invalid("workflow_test_source_invalid","发布版本身份无效");}
            var version=versions.selectOne(new LambdaQueryWrapper<WorkflowDefinitionVersionDO>().eq(WorkflowDefinitionVersionDO::getTenantId,tenant).eq(WorkflowDefinitionVersionDO::getContainerId,container.getId()).eq(WorkflowDefinitionVersionDO::getId,versionId));
            if(version==null)throw missing();definition=version.getDefinition();publication=Long.toString(versionId);
        } else throw invalid("workflow_test_source_invalid","必须且只能指定一个源版本");
        return new SourceSnapshot(container.getId(),definition,source.kind(),workflowId,revision,publication,WorkflowDefinitionCanonicalizer.sha256(definition));
    }
    public void requireDefinition(Map<String,Object> definition) {
        for(var raw:WorkflowDefinitionContract.list(definition.getOrDefault("nodes",List.of()),"nodes")) {
            var node=WorkflowDefinitionContract.object(raw,"node");
            if(node.get("type") instanceof String type && Set.of("script","async_task","parallel","subgraph","agentic").contains(type))throw invalid("workflow_test_node_unsupported","测试首批不支持节点: "+node.get("type"));
        }
        var problems=validator.validate(definition);
        if(problems.stream().anyMatch(p->p.isError()))throw invalid("workflow_test_definition_invalid","源定义存在 error，不能测试："+problems.stream().filter(p->p.isError()).toList());
        var capability=WorkflowDefinitionContract.object(WorkflowDefinitionContract.object(definition.get("meta"),"meta").get("capability"),"capability");
        if(!Boolean.TRUE.equals(capability.get("user_invocable")))throw invalid("workflow_test_source_not_invocable","工作流未声明 user_invocable，不能从可信测试输入调用");
    }

    private static ApiException missing() {return new ApiException(HttpStatus.NOT_FOUND,"workflow_test_not_found","测试源版本不存在",false);}
    private static ApiException invalid(String code,String message) {return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,code,message,false);}
    private static ApiException conflict(String code,String message) {return new ApiException(HttpStatus.CONFLICT,code,message,false);}
}
