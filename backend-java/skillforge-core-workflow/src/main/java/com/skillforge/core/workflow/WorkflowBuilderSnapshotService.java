package com.skillforge.core.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.dto.response.WorkflowBuilderSnapshotResponse;
import com.skillforge.core.workflow.mapper.WorkflowBuilderSnapshotMapper;
import com.skillforge.core.workflow.mapper.WorkflowContainerMapper;
import com.skillforge.core.workflow.mapper.WorkflowDraftRevisionMapper;
import com.skillforge.core.workflow.model.WorkflowBuilderSnapshotDO;
import com.skillforge.core.workflow.model.WorkflowContainerDO;
import com.skillforge.core.workflow.model.WorkflowDraftRevisionDO;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** BuilderSnapshot 只保存一个 draft revision 的布局与视口，绝不改写运行定义。 */
@Service
public class WorkflowBuilderSnapshotService {

    private static final Pattern WORKFLOW_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");
    private final WorkflowContainerMapper containerMapper;
    private final WorkflowDraftRevisionMapper draftMapper;
    private final WorkflowBuilderSnapshotMapper snapshotMapper;

    public WorkflowBuilderSnapshotService(
            WorkflowContainerMapper containerMapper,
            WorkflowDraftRevisionMapper draftMapper,
            WorkflowBuilderSnapshotMapper snapshotMapper) {
        this.containerMapper = containerMapper;
        this.draftMapper = draftMapper;
        this.snapshotMapper = snapshotMapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkflowBuilderSnapshotResponse getCurrent(long tenantId, String workflowId) {
        WorkflowContainerDO container = requireCurrentDraft(tenantId, workflowId, false);
        WorkflowBuilderSnapshotDO snapshot = findSnapshot(tenantId, container);
        return response(container, snapshot);
    }

    /**
     * 覆盖同一 draft revision 的布局与视口。If-Match 只确认客户端未跨越到另一份草稿；
     * 发布态和历史 revision 都不能写入。
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkflowBuilderSnapshotResponse save(long tenantId, String workflowId, long expectedRevision,
            Map<String, Object> layout, Map<String, Object> viewport) {
        WorkflowContainerDO container = requireCurrentDraft(tenantId, workflowId, true);
        if (expectedRevision <= 0 || container.getCurrentDraftRevision() != expectedRevision) {
            throw conflict(workflowId, expectedRevision);
        }
        if (!"draft".equals(container.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "已发布版本只读；不能保存 BuilderSnapshot");
        }
        WorkflowBuilderSnapshotDO snapshot = findSnapshot(tenantId, container);
        if (snapshot == null) {
            snapshot = new WorkflowBuilderSnapshotDO();
            snapshot.setId(SnowflakeIds.nextId());
            snapshot.setTenantId(tenantId);
            snapshot.setContainerId(container.getId());
            snapshot.setDraftRevision(expectedRevision);
            snapshot.setLayout(copy(layout));
            snapshot.setViewport(copy(viewport));
            snapshotMapper.insert(snapshot);
        } else {
            snapshot.setLayout(copy(layout));
            snapshot.setViewport(copy(viewport));
            snapshotMapper.updateById(snapshot);
        }
        return response(container, snapshot);
    }

    private WorkflowContainerDO requireCurrentDraft(long tenantId, String workflowId, boolean lock) {
        if (workflowId == null || !WORKFLOW_ID.matcher(workflowId).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "workflow_id 必须为 1-128 位小写字母、数字或连字符，且不能以连字符开头");
        }
        WorkflowContainerDO container = lock ? containerMapper.lockContainer(tenantId, workflowId)
                : containerMapper.selectOne(new LambdaQueryWrapper<WorkflowContainerDO>()
                .eq(WorkflowContainerDO::getTenantId, tenantId)
                .eq(WorkflowContainerDO::getWorkflowId, workflowId));
        if (container == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Workflow 容器不存在: " + workflowId);
        }
        WorkflowDraftRevisionDO current = draftMapper.selectOne(new LambdaQueryWrapper<WorkflowDraftRevisionDO>()
                .eq(WorkflowDraftRevisionDO::getContainerId, container.getId())
                .eq(WorkflowDraftRevisionDO::getRevision, container.getCurrentDraftRevision()));
        if (current == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Workflow 草稿游标未找到对应 revision: " + workflowId);
        }
        return container;
    }

    private WorkflowBuilderSnapshotDO findSnapshot(long tenantId, WorkflowContainerDO container) {
        return snapshotMapper.selectOne(new LambdaQueryWrapper<WorkflowBuilderSnapshotDO>()
                .eq(WorkflowBuilderSnapshotDO::getTenantId, tenantId)
                .eq(WorkflowBuilderSnapshotDO::getContainerId, container.getId())
                .eq(WorkflowBuilderSnapshotDO::getDraftRevision, container.getCurrentDraftRevision()));
    }

    private static WorkflowBuilderSnapshotResponse response(WorkflowContainerDO container,
            WorkflowBuilderSnapshotDO snapshot) {
        return new WorkflowBuilderSnapshotResponse(container.getWorkflowId(), container.getCurrentDraftRevision(),
                snapshot == null ? Map.of() : copy(snapshot.getLayout()),
                snapshot == null ? Map.of() : copy(snapshot.getViewport()));
    }

    private static Map<String, Object> copy(Map<String, Object> value) {
        return value == null ? Map.of() : new LinkedHashMap<>(value);
    }

    private static ApiException conflict(String workflowId, long expectedRevision) {
        return new ApiException(HttpStatus.CONFLICT, Map.of(
                "message", "草稿 revision 已变化，请刷新后再保存 BuilderSnapshot",
                "workflow_id", workflowId,
                "expected_revision", expectedRevision));
    }
}
