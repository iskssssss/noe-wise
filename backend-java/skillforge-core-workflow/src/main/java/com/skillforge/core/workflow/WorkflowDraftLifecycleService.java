package com.skillforge.core.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityProducerPublicationBridge;
import com.skillforge.core.capability.CapabilityProducerPublicationRequest;
import com.skillforge.core.capability.CapabilityProducerPublicationResult;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.dto.response.WorkflowDefinitionDiffEntry;
import com.skillforge.core.workflow.dto.response.WorkflowDraftDiffResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftListItemResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftListResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftValidationResponse;
import com.skillforge.core.workflow.dto.response.WorkflowPublishResponse;
import com.skillforge.core.workflow.dto.response.WorkflowPublicationHistoryResponse;
import com.skillforge.core.workflow.dto.response.WorkflowPublicationDetailResponse;
import com.skillforge.core.workflow.dto.response.WorkflowValidationProblem;
import com.skillforge.core.workflow.mapper.WorkflowContainerMapper;
import com.skillforge.core.workflow.mapper.WorkflowDefinitionVersionMapper;
import com.skillforge.core.workflow.mapper.WorkflowDraftRevisionMapper;
import com.skillforge.core.workflow.model.WorkflowContainerDO;
import com.skillforge.core.workflow.model.WorkflowDefinitionVersionDO;
import com.skillforge.core.workflow.model.WorkflowDraftRevisionDO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** 稳定工作流容器、可重复保存的工作草稿与不可变发布历史。 */
@Service
public class WorkflowDraftLifecycleService {

    private static final Pattern WORKFLOW_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");
    private static final int DRAFT_READ_BATCH_SIZE = 256;
    private final WorkflowContainerMapper containerMapper;
    private final WorkflowDraftRevisionMapper draftMapper;
    private final WorkflowDefinitionVersionMapper definitionVersionMapper;
    private final WorkflowDraftValidator validator;
    private final CapabilityProducerPublicationBridge capabilityPublicationBridge;

    public WorkflowDraftLifecycleService(
            WorkflowContainerMapper containerMapper,
            WorkflowDraftRevisionMapper draftMapper,
            WorkflowDefinitionVersionMapper definitionVersionMapper,
            WorkflowDraftValidator validator,
            CapabilityProducerPublicationBridge capabilityPublicationBridge) {
        this.containerMapper = containerMapper;
        this.draftMapper = draftMapper;
        this.definitionVersionMapper = definitionVersionMapper;
        this.validator = validator;
        this.capabilityPublicationBridge = capabilityPublicationBridge;
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkflowDraftResponse create(long tenantId, String workflowId, Map<String, Object> definition) {
        validateWorkflowId(workflowId);
        if (findContainer(tenantId, workflowId) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "Workflow 容器已存在: " + workflowId);
        }
        WorkflowContainerDO container = new WorkflowContainerDO();
        container.setId(SnowflakeIds.nextId());
        container.setTenantId(tenantId);
        container.setWorkflowId(workflowId);
        container.setStatus("draft");
        container.setCurrentDraftRevision(1L);
        containerMapper.insert(container);
        WorkflowDraftRevisionDO draft = insertDraft(container, 1L, definition);
        return response(container, draft);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkflowDraftResponse getCurrentDraft(long tenantId, String workflowId) {
        WorkflowContainerDO container = requireContainer(tenantId, workflowId);
        return response(container, requireCurrentDraft(container));
    }

    /** 返回当前租户的 Builder 容器；列表只携带展示元数据与 revision 游标。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkflowDraftListResponse list(long tenantId) {
        return list(tenantId, null);
    }

    /** 管理层先限定容器身份再批读草稿，不在返回结果后过滤。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkflowDraftListResponse list(long tenantId, List<Long> permittedContainerIds) {
        if (permittedContainerIds != null && permittedContainerIds.isEmpty()) return new WorkflowDraftListResponse(0, List.of());
        List<WorkflowContainerDO> containers = containerMapper.selectList(
                new LambdaQueryWrapper<WorkflowContainerDO>()
                        .eq(WorkflowContainerDO::getTenantId, tenantId)
                        .apply("NOT EXISTS (SELECT 1 FROM nw_workflow_test_sessions wt WHERE wt.tenant_id=nw_workflow_containers.tenant_id AND wt.container_id=nw_workflow_containers.id)")
                        .in(permittedContainerIds != null, WorkflowContainerDO::getId, permittedContainerIds)
                        .orderByDesc(WorkflowContainerDO::getUpdatedAt));
        List<WorkflowDraftListItemResponse> items = new ArrayList<>(containers.size());
        for (int offset = 0; offset < containers.size(); offset += DRAFT_READ_BATCH_SIZE) {
            List<WorkflowContainerDO> batch = containers.subList(offset,
                    Math.min(offset + DRAFT_READ_BATCH_SIZE, containers.size()));
            // 按首次读取的精确游标批查；不重新读取当前指针或选择最大 revision。
            List<WorkflowDraftRevisionDO> drafts = draftMapper.selectListMetadata(
                    new LambdaQueryWrapper<WorkflowDraftRevisionDO>()
                            .eq(WorkflowDraftRevisionDO::getTenantId, tenantId)
                            .and(pairs -> batch.forEach(container -> pairs.or(pair -> pair
                                    .eq(WorkflowDraftRevisionDO::getContainerId, container.getId())
                                    .eq(WorkflowDraftRevisionDO::getRevision, container.getCurrentDraftRevision())))));
            Map<DraftIdentity, WorkflowDraftRevisionDO> byIdentity = new LinkedHashMap<>();
            drafts.forEach(draft -> byIdentity.put(
                    new DraftIdentity(draft.getContainerId(), draft.getRevision()), draft));
            for (WorkflowContainerDO container : batch) {
                WorkflowDraftRevisionDO draft = byIdentity.get(
                        new DraftIdentity(container.getId(), container.getCurrentDraftRevision()));
                if (draft == null) {
                    throw missingCurrentDraft(container);
                }
                items.add(listItem(container, draft));
            }
        }
        return new WorkflowDraftListResponse(items.size(), List.copyOf(items));
    }

    /** 管理层通过语义 API 解析稳定工作流对应的真实同租户资源主键。 */
    public long containerId(long tenantId, String workflowId) {
        return requireContainer(tenantId, workflowId).getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkflowDraftResponse save(long tenantId, String workflowId, long expectedRevision,
            Map<String, Object> definition) {
        WorkflowContainerDO container = requireLockedContainer(tenantId, workflowId);
        assertRevision(container, expectedRevision);
        assertNotArchived(container);
        long nextRevision = expectedRevision + 1;
        if (containerMapper.advanceDraftRevision(container.getId(), tenantId, expectedRevision, nextRevision) != 1) {
            throw conflict(workflowId, expectedRevision);
        }
        container.setCurrentDraftRevision(nextRevision);
        container.setStatus("draft");
        return response(container, replaceDraft(container, expectedRevision, nextRevision, definition));
    }

    /**
     * 将当前发布版本复制到唯一工作草稿，推进并发保存游标。
     *
     * <p>以当前 revision 做 CAS，且 SQL 额外要求
     * 容器仍为 {@code published}，因此重复点击或并发编辑不会覆盖他人的新草稿。
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkflowDraftResponse forkPublishedDraft(long tenantId, String workflowId, long expectedRevision) {
        WorkflowContainerDO container = requireLockedContainer(tenantId, workflowId);
        assertRevision(container, expectedRevision);
        if (!"published".equals(container.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "仅已发布版本可以创建编辑草稿，请刷新当前工作流状态");
        }
        WorkflowDefinitionVersionDO active = activeDefinition(tenantId, container);
        if (active == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "已发布工作流缺少 active DefinitionVersion: " + workflowId);
        }
        long nextRevision = expectedRevision + 1;
        if (containerMapper.forkPublishedDraft(container.getId(), tenantId, expectedRevision, nextRevision) != 1) {
            throw conflict(workflowId, expectedRevision);
        }
        container.setCurrentDraftRevision(nextRevision);
        container.setStatus("draft");
        return response(container, replaceDraft(container, expectedRevision, nextRevision, active.getDefinition()));
    }

    /** 历史只包含发布快照；保存工作草稿仅推进并发游标。 */
    public WorkflowPublicationHistoryResponse history(long tenantId, String workflowId) {
        WorkflowContainerDO container = requireContainer(tenantId, workflowId);
        var versions = definitionVersionMapper.selectList(new LambdaQueryWrapper<WorkflowDefinitionVersionDO>()
                .select(WorkflowDefinitionVersionDO::getId, WorkflowDefinitionVersionDO::getDefinitionVersion,
                        WorkflowDefinitionVersionDO::getCapabilityVersionId, WorkflowDefinitionVersionDO::getCreatedAt)
                .eq(WorkflowDefinitionVersionDO::getTenantId, tenantId)
                .eq(WorkflowDefinitionVersionDO::getContainerId, container.getId())
                .orderByDesc(WorkflowDefinitionVersionDO::getDefinitionVersion));
        return new WorkflowPublicationHistoryResponse(workflowId, container.getActiveDefinitionVersionId(),
                versions.stream().map(version -> new WorkflowPublicationHistoryResponse.Item(version.getId(),
                        version.getDefinitionVersion(), version.getCapabilityVersionId(), version.getCreatedAt(),
                        Objects.equals(container.getActiveDefinitionVersionId(), version.getId()))).toList());
    }

    /** 按租户、稳定容器和请求版本精确读取，不以当前发布指针替换历史版本。 */
    public WorkflowPublicationDetailResponse publicationDetail(long tenantId, String workflowId, long definitionVersionId) {
        WorkflowContainerDO container = requireContainer(tenantId, workflowId);
        var version = definitionVersionMapper.selectOne(new LambdaQueryWrapper<WorkflowDefinitionVersionDO>()
                .eq(WorkflowDefinitionVersionDO::getTenantId, tenantId)
                .eq(WorkflowDefinitionVersionDO::getContainerId, container.getId())
                .eq(WorkflowDefinitionVersionDO::getId, definitionVersionId));
        if (version == null) throw new ApiException(HttpStatus.NOT_FOUND, "Workflow 发布修订不存在");
        return new WorkflowPublicationDetailResponse(workflowId, version.getId(), version.getDefinitionVersion(),
                version.getCapabilityVersionId(), version.getCreatedAt(),
                Objects.equals(container.getActiveDefinitionVersionId(), version.getId()), copyDefinition(version.getDefinition()));
    }

    /** 恢复只覆盖工作草稿；需要发布后才能影响下一次执行。 */
    @Transactional(rollbackFor = Exception.class)
    public WorkflowDraftResponse restore(long tenantId, String workflowId, long expectedRevision,
            long definitionVersionId) {
        WorkflowContainerDO container = requireLockedContainer(tenantId, workflowId);
        assertRevision(container, expectedRevision);
        assertNotArchived(container);
        WorkflowDefinitionVersionDO version = definitionVersionMapper.selectOne(
                new LambdaQueryWrapper<WorkflowDefinitionVersionDO>()
                        .eq(WorkflowDefinitionVersionDO::getTenantId, tenantId)
                        .eq(WorkflowDefinitionVersionDO::getContainerId, container.getId())
                        .eq(WorkflowDefinitionVersionDO::getId, definitionVersionId));
        if (version == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Workflow 发布修订不存在");
        }
        return save(tenantId, workflowId, expectedRevision, version.getDefinition());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkflowDraftValidationResponse validate(long tenantId, String workflowId) {
        WorkflowContainerDO container = requireContainer(tenantId, workflowId);
        WorkflowDraftRevisionDO draft = requireCurrentDraft(container);
        return new WorkflowDraftValidationResponse(draft.getRevision(), validator.validate(draft.getDefinition()));
    }

    /** 校验尚未保存的定义，不修改草稿、布局或发布状态。 */
    public WorkflowDraftValidationResponse validateNewContent(Map<String, Object> definition) {
        return new WorkflowDraftValidationResponse(0, validator.validate(definition));
    }

    /** 已有工作流按可见草稿修订校验当前输入；旧基数不得被当成当前结果。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkflowDraftValidationResponse validateContent(long tenantId, String workflowId, long expectedRevision,
            Map<String, Object> definition) {
        WorkflowContainerDO container = requireContainer(tenantId, workflowId);
        assertRevision(container, expectedRevision);
        assertNotArchived(container);
        return new WorkflowDraftValidationResponse(expectedRevision, validator.validate(definition));
    }

    /**
     * 返回当前草稿相对 active DefinitionVersion 的结构化差异。
     *
     * <p>差异只读，按 JSON Pointer 路径字典序/数组下标稳定输出；它既不写草稿也不触碰运行定义。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkflowDraftDiffResponse diff(long tenantId, String workflowId) {
        WorkflowContainerDO container = requireContainer(tenantId, workflowId);
        WorkflowDraftRevisionDO draft = requireCurrentDraft(container);
        WorkflowDefinitionVersionDO active = activeDefinition(tenantId, container);
        if (active == null) {
            return new WorkflowDraftDiffResponse(draft.getRevision(), null, List.of());
        }
        List<WorkflowDefinitionDiffEntry> changes = new ArrayList<>();
        collectDiff("", active.getDefinition(), draft.getDefinition(), changes);
        return new WorkflowDraftDiffResponse(draft.getRevision(), active.getDefinitionVersion(), List.copyOf(changes));
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkflowPublishResponse publish(long tenantId, String workflowId, long expectedRevision) {
        WorkflowContainerDO container = requireLockedContainer(tenantId, workflowId);
        assertRevision(container, expectedRevision);
        if ("archived".equals(container.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "归档工作流不能发布");
        }
        WorkflowDraftRevisionDO draft = requireCurrentDraft(container);
        if ("published".equals(container.getStatus())) {
            var existing = activeDefinition(tenantId, container);
            if (existing == null) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Workflow 当前发布修订不存在");
            }
            return publishResponse(workflowId, draft, existing);
        }
        List<WorkflowValidationProblem> problems = validator.validate(draft.getDefinition());
        if (problems.stream().anyMatch(WorkflowValidationProblem::isError)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Map.of(
                    "message", "存在 error 级校验问题，不能发布",
                    "problems", problems));
        }
        Map<String, Object> publishedDefinition = freezeDefinition(
                draft.getDefinition());
        long definitionVersion = definitionVersionMapper.selectCount(
                new LambdaQueryWrapper<WorkflowDefinitionVersionDO>()
                        .eq(WorkflowDefinitionVersionDO::getContainerId, container.getId())) + 1L;
        CapabilityProducerPublicationResult capability = capabilityPublicationBridge.publish(
                publicationRequest(tenantId, workflowId, publishedDefinition, definitionVersion));
        if (capability == null || capability.version() == null || capability.version().id() == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Workflow Producer 未返回已发布 Catalog CapabilityVersion: " + workflowId);
        }
        WorkflowDefinitionVersionDO version = new WorkflowDefinitionVersionDO();
        version.setId(SnowflakeIds.nextId());
        version.setTenantId(tenantId);
        version.setContainerId(container.getId());
        version.setSourceDraftId(draft.getId());
        version.setDefinitionVersion(definitionVersion);
        version.setCapabilityVersionId(capability.version().id());
        version.setDefinition(publishedDefinition);
        definitionVersionMapper.insert(version);
        if (containerMapper.activateDefinitionVersion(container.getId(), tenantId, expectedRevision, version.getId()) != 1) {
            throw conflict(workflowId, expectedRevision);
        }
        return publishResponse(workflowId, draft, version);
    }

    private WorkflowDraftRevisionDO insertDraft(WorkflowContainerDO container, long revision,
            Map<String, Object> definition) {
        WorkflowDraftRevisionDO draft = new WorkflowDraftRevisionDO();
        draft.setId(SnowflakeIds.nextId());
        draft.setContainerId(container.getId());
        draft.setTenantId(container.getTenantId());
        draft.setRevision(revision);
        draft.setDefinition(freezeDefinition(definition));
        draftMapper.insert(draft);
        return draft;
    }

    private WorkflowDraftRevisionDO replaceDraft(WorkflowContainerDO container, long expectedRevision,
            long nextRevision, Map<String, Object> definition) {
        WorkflowDraftRevisionDO draft = draftMapper.selectOne(new LambdaQueryWrapper<WorkflowDraftRevisionDO>()
                .eq(WorkflowDraftRevisionDO::getTenantId, container.getTenantId())
                .eq(WorkflowDraftRevisionDO::getContainerId, container.getId())
                .eq(WorkflowDraftRevisionDO::getRevision, expectedRevision));
        if (draft == null) {
            throw missingCurrentDraft(container);
        }
        draft.setRevision(nextRevision);
        draft.setDefinition(freezeDefinition(definition));
        if (draftMapper.updateById(draft) != 1) {
            throw conflict(container.getWorkflowId(), expectedRevision);
        }
        containerMapper.advanceBuilderSnapshot(container.getId(), container.getTenantId(), expectedRevision, nextRevision);
        return draft;
    }

    private WorkflowDraftResponse response(WorkflowContainerDO container, WorkflowDraftRevisionDO draft) {
        Map<String, Object> definition = copyDefinition(draft.getDefinition());
        return new WorkflowDraftResponse(container.getWorkflowId(), container.getStatus(), draft.getRevision(),
                definition, validator.validate(definition), container.getActiveDefinitionVersionId());
    }

    @SuppressWarnings("unchecked")
    private static WorkflowDraftListItemResponse listItem(
            WorkflowContainerDO container, WorkflowDraftRevisionDO draft) {
        Map<String, Object> definition = draft.getDefinition() == null ? Map.of() : draft.getDefinition();
        Object rawMeta = definition.get("meta");
        Map<String, Object> meta = rawMeta instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        String name = displayValue(meta.get("display_name"),
                displayValue(meta.get("name"), container.getWorkflowId()));
        return new WorkflowDraftListItemResponse(container.getWorkflowId(), container.getStatus(),
                draft.getRevision(), container.getActiveDefinitionVersionId(), name,
                displayValue(meta.get("description"), ""));
    }

    private static String displayValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private WorkflowContainerDO requireContainer(long tenantId, String workflowId) {
        validateWorkflowId(workflowId);
        WorkflowContainerDO container = findContainer(tenantId, workflowId);
        if (container == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Workflow 容器不存在: " + workflowId);
        }
        return container;
    }

    private WorkflowContainerDO requireLockedContainer(long tenantId, String workflowId) {
        validateWorkflowId(workflowId);
        var container = containerMapper.lockContainer(tenantId, workflowId);
        if (container == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Workflow 容器不存在: " + workflowId);
        }
        return container;
    }

    private WorkflowContainerDO findContainer(long tenantId, String workflowId) {
        return containerMapper.selectOne(new LambdaQueryWrapper<WorkflowContainerDO>()
                .eq(WorkflowContainerDO::getTenantId, tenantId)
                .eq(WorkflowContainerDO::getWorkflowId, workflowId));
    }

    private WorkflowDraftRevisionDO requireCurrentDraft(WorkflowContainerDO container) {
        WorkflowDraftRevisionDO draft = draftMapper.selectOne(new LambdaQueryWrapper<WorkflowDraftRevisionDO>()
                .eq(WorkflowDraftRevisionDO::getContainerId, container.getId())
                .eq(WorkflowDraftRevisionDO::getRevision, container.getCurrentDraftRevision()));
        if (draft == null) {
            throw missingCurrentDraft(container);
        }
        return draft;
    }

    private static ApiException missingCurrentDraft(WorkflowContainerDO container) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Workflow 草稿游标未找到对应 revision: " + container.getWorkflowId());
    }

    private record DraftIdentity(Long containerId, Long revision) { }

    private WorkflowDefinitionVersionDO activeDefinition(long tenantId, WorkflowContainerDO container) {
        if (container.getActiveDefinitionVersionId() == null) {
            return null;
        }
        return definitionVersionMapper.selectOne(new LambdaQueryWrapper<WorkflowDefinitionVersionDO>()
                .eq(WorkflowDefinitionVersionDO::getId, container.getActiveDefinitionVersionId())
                .eq(WorkflowDefinitionVersionDO::getContainerId, container.getId())
                .eq(WorkflowDefinitionVersionDO::getTenantId, tenantId));
    }

    private static void collectDiff(String path, Object before, Object after,
            List<WorkflowDefinitionDiffEntry> changes) {
        if (Objects.equals(before, after)) {
            return;
        }
        if (before instanceof Map<?, ?> beforeMap && after instanceof Map<?, ?> afterMap) {
            Set<String> keys = new TreeSet<>();
            beforeMap.keySet().forEach(key -> keys.add(String.valueOf(key)));
            afterMap.keySet().forEach(key -> keys.add(String.valueOf(key)));
            for (String key : keys) {
                boolean hadBefore = beforeMap.containsKey(key);
                boolean hasAfter = afterMap.containsKey(key);
                String childPath = pointer(path, key);
                if (!hadBefore) {
                    changes.add(new WorkflowDefinitionDiffEntry("add", childPath, null, afterMap.get(key)));
                } else if (!hasAfter) {
                    changes.add(new WorkflowDefinitionDiffEntry("remove", childPath, beforeMap.get(key), null));
                } else {
                    collectDiff(childPath, beforeMap.get(key), afterMap.get(key), changes);
                }
            }
            return;
        }
        if (before instanceof List<?> beforeList && after instanceof List<?> afterList) {
            int shared = Math.min(beforeList.size(), afterList.size());
            for (int index = 0; index < shared; index++) {
                collectDiff(pointer(path, Integer.toString(index)), beforeList.get(index), afterList.get(index), changes);
            }
            for (int index = shared; index < beforeList.size(); index++) {
                changes.add(new WorkflowDefinitionDiffEntry("remove", pointer(path, Integer.toString(index)),
                        beforeList.get(index), null));
            }
            for (int index = shared; index < afterList.size(); index++) {
                changes.add(new WorkflowDefinitionDiffEntry("add", pointer(path, Integer.toString(index)), null,
                        afterList.get(index)));
            }
            return;
        }
        changes.add(new WorkflowDefinitionDiffEntry(before == null ? "add" : after == null ? "remove" : "replace",
                path.isEmpty() ? "/" : path, before, after));
    }

    private static String pointer(String parent, String token) {
        String escaped = token.replace("~", "~0").replace("/", "~1");
        return parent + "/" + escaped;
    }

    private void assertRevision(WorkflowContainerDO container, long expectedRevision) {
        if (expectedRevision <= 0 || container.getCurrentDraftRevision() != expectedRevision) {
            throw conflict(container.getWorkflowId(), expectedRevision);
        }
    }

    /** 归档为只读终态；必须在同一容器锁内核验，不能经保存或历史恢复重新激活。 */
    private static void assertNotArchived(WorkflowContainerDO container) {
        if ("archived".equals(container.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "归档工作流只读，不能修改草稿");
        }
    }

    private static ApiException conflict(String workflowId, long expectedRevision) {
        return new ApiException(HttpStatus.CONFLICT, Map.of(
                "message", "草稿 revision 已变化，请刷新、比较或另存草稿",
                "workflow_id", workflowId,
                "expected_revision", expectedRevision));
    }

    private static void validateWorkflowId(String workflowId) {
        if (workflowId == null || !WORKFLOW_ID.matcher(workflowId).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "workflow_id 必须为 1-128 位小写字母、数字或连字符，且不能以连字符开头");
        }
    }

    /**
     * 复制运行定义并剥离仅属于 Builder 设计态的布局信息。
     *
     * <p>同包的模板导入器复用此规则，确保任何进入 DefinitionVersion 的内容都不含画布布局。
     */
    static Map<String, Object> copyDefinition(Map<String, Object> definition) {
        if (definition == null) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>(definition);
        Object rawNodes = definition.get("nodes");
        if (rawNodes instanceof List<?> nodes) {
            List<Object> sanitizedNodes = new ArrayList<>(nodes.size());
            for (Object rawNode : nodes) {
                if (rawNode instanceof Map<?, ?> node) {
                    Map<String, Object> sanitized = new LinkedHashMap<>();
                    node.forEach((key, value) -> sanitized.put(String.valueOf(key), value));
                    // layout 只能写入 WorkflowBuilderSnapshot；运行定义和发布物都不得携带它。
                    sanitized.remove("layout");
                    sanitizedNodes.add(sanitized);
                } else {
                    sanitizedNodes.add(rawNode);
                }
            }
            copy.put("nodes", sanitizedNodes);
        }
        return copy;
    }

    private static Map<String, Object> freezeDefinition(
            Map<String, Object> definition) {
        try {
            return WorkflowDefinitionResources.freeze(
                    copyDefinition(definition));
        } catch (IllegalArgumentException rejected) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    rejected.getMessage());
        }
    }

    private static WorkflowPublishResponse publishResponse(String workflowId, WorkflowDraftRevisionDO draft,
            WorkflowDefinitionVersionDO version) {
        if (version.getCapabilityVersionId() == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Workflow DefinitionVersion 缺少已发布 Catalog CapabilityVersion: " + workflowId);
        }
        return new WorkflowPublishResponse(workflowId, draft.getRevision(), version.getId(),
                version.getDefinitionVersion(), version.getCapabilityVersionId());
    }

    private static CapabilityProducerPublicationRequest publicationRequest(
            long tenantId, String workflowId, Map<String, Object> definition, long definitionVersion) {
        Map<String, Object> meta = objectMap(definition.get("meta"));
        Map<String, Object> capability = objectMap(meta.get("capability"));
        Map<String, Object> descriptor = new LinkedHashMap<>();
        // The persisted descriptor uses the stable snake_case wire keys. The configured
        // Codec decodes those keys into its camelCase Java record components. Keep the
        // mapping explicit so no graph/node/environment value can supply governance.
        descriptor.put("model_invocable", capability.get("model_invocable"));
        descriptor.put("user_invocable", capability.get("user_invocable"));
        descriptor.put("input_schema", capability.get("input_schema"));
        descriptor.put("output_schema", capability.get("output_schema"));
        descriptor.put("supports_hitl", capability.get("supports_hitl"));
        descriptor.put("export_fields", capability.get("export_fields"));
        descriptor.put("timeout_ms", capability.get("timeout_ms"));
        descriptor.put("concurrency_safe", capability.get("concurrency_safe"));
        String name = firstText(meta.get("display_name"), meta.get("name"), workflowId);
        String description = meta.get("description") == null
                ? "" : String.valueOf(meta.get("description"));
        return new CapabilityProducerPublicationRequest(
                CapabilityCatalogScope.tenant(tenantId), CapabilityType.WORKFLOW,
                "workflow:" + workflowId, "definition:" + definitionVersion,
                name, description, WorkflowDefinitionCanonicalizer.sha256(definition), descriptor);
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String firstText(Object first, Object second, String fallback) {
        for (Object value : new Object[] {first, second, fallback}) {
            if (value != null && !String.valueOf(value).trim().isEmpty()) {
                return String.valueOf(value).trim();
            }
        }
        return fallback;
    }
}
