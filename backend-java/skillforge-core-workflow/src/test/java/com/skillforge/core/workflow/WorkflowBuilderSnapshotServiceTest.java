package com.skillforge.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.dto.response.WorkflowBuilderSnapshotResponse;
import com.skillforge.core.workflow.mapper.WorkflowBuilderSnapshotMapper;
import com.skillforge.core.workflow.mapper.WorkflowContainerMapper;
import com.skillforge.core.workflow.mapper.WorkflowDraftRevisionMapper;
import com.skillforge.core.workflow.model.WorkflowBuilderSnapshotDO;
import com.skillforge.core.workflow.model.WorkflowContainerDO;
import com.skillforge.core.workflow.model.WorkflowDraftRevisionDO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpStatus;

/** BuilderSnapshot 只绑定当前 draft revision，不能绕过发布版本只读约束。 */
class WorkflowBuilderSnapshotServiceTest {

    private final WorkflowContainerMapper containers = mock(WorkflowContainerMapper.class);
    private final WorkflowDraftRevisionMapper drafts = mock(WorkflowDraftRevisionMapper.class);
    private final WorkflowBuilderSnapshotMapper snapshots = mock(WorkflowBuilderSnapshotMapper.class);
    private final WorkflowBuilderSnapshotService service = new WorkflowBuilderSnapshotService(containers, drafts, snapshots);

    @BeforeEach
    void serializesLayoutWritesWithPublication() {
        when(containers.lockContainer(anyLong(), any())).thenAnswer(call -> containers.selectOne(null));
    }

    @Test
    void createsUpdatesAndReadsOnlyTheCurrentDraftSnapshot() {
        WorkflowContainerDO container = container("draft", 4L);
        when(containers.selectOne(any())).thenReturn(container);
        when(drafts.selectOne(any())).thenReturn(currentDraft());
        when(snapshots.selectOne(any())).thenReturn(null);
        Map<String, Object> layout = Map.of("nodes", List.of(Map.of("id", "start", "x", 24, "y", 40)));
        Map<String, Object> viewport = Map.of("zoom", 1.25, "panX", 12, "panY", 8);

        WorkflowBuilderSnapshotResponse created = service.save(7L, "flow", 4L, layout, viewport);

        assertEquals("flow", created.workflowId());
        assertEquals(4L, created.draftRevision());
        assertEquals(layout, created.layout());
        assertEquals(viewport, created.viewport());
        verify(snapshots).insert(any(WorkflowBuilderSnapshotDO.class));

        WorkflowBuilderSnapshotDO existing = new WorkflowBuilderSnapshotDO();
        existing.setId(88L);
        existing.setLayout(Map.of("nodes", List.of()));
        existing.setViewport(Map.of());
        when(snapshots.selectOne(any())).thenReturn(existing);
        WorkflowBuilderSnapshotResponse updated = service.save(7L, "flow", 4L, Map.of("nodes", List.of()), Map.of("zoom", 1));
        assertEquals(Map.of("zoom", 1), updated.viewport());
        verify(snapshots).updateById(existing);
        assertEquals(updated, service.getCurrent(7L, "flow"));
    }

    @Test
    void returnsEmptySnapshotBeforeLayoutHasBeenSaved() {
        when(containers.selectOne(any())).thenReturn(container("draft", 2L));
        when(drafts.selectOne(any())).thenReturn(currentDraft());
        when(snapshots.selectOne(any())).thenReturn(null);

        WorkflowBuilderSnapshotResponse empty = service.getCurrent(7L, "flow");

        assertEquals(2L, empty.draftRevision());
        assertEquals(Map.of(), empty.layout());
        assertEquals(Map.of(), empty.viewport());
    }

    @Test
    void rejectsStaleAndPublishedSnapshotWritesWithoutPersisting() {
        WorkflowContainerDO draft = container("draft", 4L);
        when(containers.selectOne(any())).thenReturn(draft);
        when(drafts.selectOne(any())).thenReturn(currentDraft());

        ApiException stale = assertThrows(ApiException.class,
                () -> service.save(7L, "flow", 3L, Map.of(), Map.of()));
        assertEquals(HttpStatus.CONFLICT, stale.getStatus());
        verify(snapshots, never()).insert(any(WorkflowBuilderSnapshotDO.class));
        verify(snapshots, never()).updateById(any(WorkflowBuilderSnapshotDO.class));

        WorkflowContainerDO published = container("published", 4L);
        when(containers.selectOne(any())).thenReturn(published);
        ApiException readonly = assertThrows(ApiException.class,
                () -> service.save(7L, "flow", 4L, Map.of(), Map.of()));
        assertEquals(HttpStatus.CONFLICT, readonly.getStatus());
        verify(snapshots, never()).insert(any(WorkflowBuilderSnapshotDO.class));
        verify(snapshots, never()).updateById(any(WorkflowBuilderSnapshotDO.class));
    }

    @Test
    void rejectsMalformedOrMissingWorkflowBeforeSnapshotLookup() {
        ApiException malformed = assertThrows(ApiException.class,
                () -> service.getCurrent(7L, "UPPERCASE"));
        assertEquals(HttpStatus.BAD_REQUEST, malformed.getStatus());
        when(containers.selectOne(any())).thenReturn(null);
        ApiException missing = assertThrows(ApiException.class,
                () -> service.getCurrent(7L, "missing"));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatus());
        verify(snapshots, never()).selectOne(any());
    }

    private static WorkflowContainerDO container(String status, long revision) {
        WorkflowContainerDO container = new WorkflowContainerDO();
        container.setId(42L);
        container.setTenantId(7L);
        container.setWorkflowId("flow");
        container.setStatus(status);
        container.setCurrentDraftRevision(revision);
        return container;
    }

    private static WorkflowDraftRevisionDO currentDraft() {
        WorkflowDraftRevisionDO draft = new WorkflowDraftRevisionDO();
        draft.setId(99L);
        draft.setContainerId(42L);
        return draft;
    }
}
