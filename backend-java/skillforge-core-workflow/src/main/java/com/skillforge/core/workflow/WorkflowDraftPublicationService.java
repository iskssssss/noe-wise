package com.skillforge.core.workflow;

import com.skillforge.core.workflow.dto.response.WorkflowPublishResponse;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 同一事务保存工作草稿及布局，并发布该草稿修订。 */
@Service
public class WorkflowDraftPublicationService {
    private final WorkflowDraftLifecycleService drafts;
    private final WorkflowBuilderSnapshotService snapshots;

    public WorkflowDraftPublicationService(WorkflowDraftLifecycleService drafts,
            WorkflowBuilderSnapshotService snapshots) {
        this.drafts = drafts;
        this.snapshots = snapshots;
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkflowPublishResponse saveAndPublish(long tenantId, String workflowId, long expectedRevision,
            Map<String, Object> definition, Map<String, Object> layout, Map<String, Object> viewport) {
        var saved = drafts.save(tenantId, workflowId, expectedRevision, definition);
        snapshots.save(tenantId, workflowId, saved.revision(), layout, viewport);
        return drafts.publish(tenantId, workflowId, saved.revision());
    }
}
