package com.skillforge.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.skillforge.core.capability.CapabilityProducerPublicationBridge;
import com.skillforge.core.capability.CapabilityProducerPublicationResult;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.CapabilityVersionView;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.dto.response.WorkflowDraftResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftDiffResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftListResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDefinitionDiffEntry;
import com.skillforge.core.workflow.dto.response.WorkflowPublishResponse;
import com.skillforge.core.workflow.dto.response.WorkflowValidationProblem;
import com.skillforge.core.workflow.mapper.WorkflowContainerMapper;
import com.skillforge.core.workflow.mapper.WorkflowDefinitionVersionMapper;
import com.skillforge.core.workflow.mapper.WorkflowDraftRevisionMapper;
import com.skillforge.core.workflow.model.WorkflowContainerDO;
import com.skillforge.core.workflow.model.WorkflowDefinitionVersionDO;
import com.skillforge.core.workflow.model.WorkflowDraftRevisionDO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/** Builder 草稿生命周期的单元测试，覆盖 revision CAS、校验阻断与不可变发布。 */
class WorkflowDraftLifecycleServiceTest {

    @BeforeAll
    static void initTableInfo() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WorkflowContainerDO.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowDraftRevisionDO.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowDefinitionVersionDO.class);
    }

    private final WorkflowContainerMapper containers = mock(WorkflowContainerMapper.class);
    private final WorkflowDraftRevisionMapper drafts = mock(WorkflowDraftRevisionMapper.class);
    private final WorkflowDefinitionVersionMapper versions = mock(WorkflowDefinitionVersionMapper.class);
    private final CapabilityProducerPublicationBridge capabilityBridge =
            mock(CapabilityProducerPublicationBridge.class);
    private final WorkflowDraftLifecycleService service = new WorkflowDraftLifecycleService(
            containers, drafts, versions, new WorkflowDraftValidator(), capabilityBridge);

    @BeforeEach
    void locksTheSameContainerForPublication() {
        when(containers.lockContainer(anyLong(), any())).thenAnswer(call -> containers.selectOne(null));
        when(drafts.updateById(any(WorkflowDraftRevisionDO.class))).thenReturn(1);
    }

    @Test
    void publicationHistorySelectsOnlySummaryAndDetailKeepsTheRequestedVersion() {
        var container = container(2L);
        container.setActiveDefinitionVersionId(902L);
        when(containers.selectOne(any())).thenReturn(container);
        var old = new WorkflowDefinitionVersionDO();
        old.setId(901L); old.setDefinitionVersion(1L); old.setDefinition(validDefinition());
        when(versions.selectList(any())).thenReturn(List.of(old));
        when(versions.selectOne(any())).thenReturn(old);

        assertEquals(false, service.history(7L, "flow").items().getFirst().current());
        ArgumentCaptor<LambdaQueryWrapper<WorkflowDefinitionVersionDO>> summaryQuery = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(versions).selectList(summaryQuery.capture());
        assertEquals("id,definition_version,capability_version_id,created_at", summaryQuery.getValue().getSqlSelect());
        var detail = service.publicationDetail(7L, "flow", 901L);
        assertEquals(901L, detail.definitionVersionId());
        assertEquals(validDefinition(), detail.definition());
        assertEquals(false, detail.current());
        ArgumentCaptor<LambdaQueryWrapper<WorkflowDefinitionVersionDO>> detailQuery = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(versions).selectOne(detailQuery.capture());
        assertTrue(detailQuery.getValue().getSqlSegment().contains("container_id"));
        assertEquals(java.util.Set.of(7L, 42L, 901L), java.util.Set.copyOf(detailQuery.getValue().getParamNameValuePairs().values()));
        when(versions.selectOne(any())).thenReturn(null);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> service.publicationDetail(7L, "flow", 999L)).getStatus());
    }

    @Test
    void createsOneWorkingDraftAndUpdatesItWithACasToken() {
        when(containers.selectOne(any())).thenReturn(null);
        WorkflowDraftResponse created = service.create(7L, "new-flow", Map.of());
        assertEquals(1L, created.revision());
        assertTrue(created.problems().stream().anyMatch(problem -> problem.code().equals("WF_SCHEMA_ROOT")));
        verify(containers).insert(any(WorkflowContainerDO.class));
        verify(drafts).insert(any(WorkflowDraftRevisionDO.class));

        WorkflowContainerDO container = container(4L);
        when(containers.selectOne(any())).thenReturn(container);
        when(drafts.selectOne(any())).thenReturn(draft(4L, Map.of()));
        when(containers.advanceDraftRevision(container.getId(), 7L, 4L, 5L)).thenReturn(1);
        WorkflowDraftResponse saved = service.save(7L, "flow", 4L, validDefinition());
        assertEquals(5L, saved.revision());
        assertEquals("draft", saved.status());
        verify(drafts).updateById(any(WorkflowDraftRevisionDO.class));
        verify(drafts, org.mockito.Mockito.times(1)).insert(any(WorkflowDraftRevisionDO.class));
    }

    @Test
    void listsOnlyTenantContainersWithDraftDisplayMetadata() {
        WorkflowContainerDO container = container(2L);
        container.setWorkflowId("request");
        container.setActiveDefinitionVersionId(88L);
        when(containers.selectList(any())).thenReturn(List.of(container));
        when(drafts.selectListMetadata(any())).thenReturn(List.of(draft(2L, Map.of(
                "meta", Map.of("display_name", "申请办理", "description", "可办理事项"),
                "nodes", List.of(), "edges", List.of()))));

        WorkflowDraftListResponse result = service.list(7L);

        assertEquals(1, result.total());
        assertEquals("request", result.items().getFirst().workflowId());
        assertEquals("申请办理", result.items().getFirst().name());
        assertEquals("可办理事项", result.items().getFirst().description());
        assertEquals(88L, result.items().getFirst().activeDefinitionVersionId());
        verify(drafts, never()).selectOne(any());
    }

    @Test
    void batchDraftReadKeepsContainerOrderExactRevisionsAndNameFallbacks() {
        WorkflowContainerDO first = container(2L);
        WorkflowContainerDO second = container(4L);
        second.setId(43L);
        second.setWorkflowId("second-flow");
        WorkflowDraftRevisionDO firstDraft = draft(2L,
                Map.of("meta", Map.of("display_name", " ", "name", " 名称回退 ")));
        WorkflowDraftRevisionDO secondDraft = draft(4L,
                Map.of("meta", Map.of("display_name", " ", "name", " ")));
        secondDraft.setContainerId(43L);
        when(containers.selectList(any())).thenReturn(List.of(first, second));
        when(drafts.selectListMetadata(any())).thenReturn(List.of(secondDraft, firstDraft));

        var result = service.list(7L);

        assertEquals(List.of("flow", "second-flow"), result.items().stream()
                .map(item -> item.workflowId()).toList());
        assertEquals(List.of("名称回退", "second-flow"), result.items().stream()
                .map(item -> item.name()).toList());
        assertEquals(List.of(2L, 4L), result.items().stream().map(item -> item.revision()).toList());
        ArgumentCaptor<LambdaQueryWrapper<WorkflowDraftRevisionDO>> query =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(drafts).selectListMetadata(query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("tenant_id"));
        assertTrue(sql.contains("container_id") && sql.contains("revision") && sql.contains(" OR "));
        assertEquals(List.of(7L, 42L, 2L, 43L, 4L), query.getValue().getParamNameValuePairs()
                .entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList());
        verify(drafts, never()).selectOne(any());
    }

    @Test
    void emptyListDoesNotReadDraftsAndMissingExactRevisionKeepsTheOriginalError() {
        when(containers.selectList(any())).thenReturn(List.of());
        assertEquals(0, service.list(7L).total());
        verify(drafts, never()).selectListMetadata(any());

        when(containers.selectList(any())).thenReturn(List.of(container(2L)));
        when(drafts.selectListMetadata(any())).thenReturn(List.of(draft(3L, Map.of())));
        ApiException error = assertThrows(ApiException.class, () -> service.list(7L));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, error.getStatus());
        assertEquals("Workflow 草稿游标未找到对应 revision: flow", error.getMessage());
    }

    @Test
    void largeListsUseBoundedDraftQueriesInsteadOfOneQueryPerContainer() {
        List<WorkflowContainerDO> rows = new ArrayList<>();
        for (int index = 0; index < 257; index++) {
            var row = container(index + 1L);
            row.setId(index + 100L);
            row.setWorkflowId("flow-" + index);
            rows.add(row);
        }
        when(containers.selectList(any())).thenReturn(rows);
        when(drafts.selectListMetadata(any())).thenReturn(batchDrafts(rows.subList(0, 256)),
                batchDrafts(rows.subList(256, 257)));

        var result = service.list(7L);

        assertEquals(257, result.total());
        assertEquals("flow-256", result.items().getLast().workflowId());
        verify(drafts, org.mockito.Mockito.times(2)).selectListMetadata(any());
        verify(drafts, never()).selectOne(any());
    }

    private static List<WorkflowDraftRevisionDO> batchDrafts(List<WorkflowContainerDO> containers) {
        return containers.stream().map(container -> {
            var draft = draft(container.getCurrentDraftRevision(), Map.of());
            draft.setContainerId(container.getId());
            return draft;
        }).toList();
    }

    @Test
    void rejectsStaleRevisionWithoutAWrite() {
        when(containers.selectOne(any())).thenReturn(container(4L));

        ApiException error = assertThrows(ApiException.class,
                () -> service.save(7L, "flow", 3L, validDefinition()));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(containers, never()).advanceDraftRevision(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void rejectsArchivedDraftSaveBeforeAnyRevisionOrSnapshotWrite() {
        WorkflowContainerDO archived = container(4L);
        archived.setStatus("archived");
        archived.setActiveDefinitionVersionId(88L);
        when(containers.selectOne(any())).thenReturn(archived);

        ApiException error = assertThrows(ApiException.class,
                () -> service.save(7L, "flow", 4L, validDefinition()));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("归档工作流只读，不能修改草稿", error.getMessage());
        verify(containers).lockContainer(7L, "flow");
        assertArchivedUnchangedWithoutWrites(archived);
    }

    @Test
    void rejectsArchivedHistoricalRestoreBeforeReadingPublishedDefinition() {
        WorkflowContainerDO archived = container(4L);
        archived.setStatus("archived");
        archived.setActiveDefinitionVersionId(88L);
        when(containers.selectOne(any())).thenReturn(archived);

        ApiException error = assertThrows(ApiException.class,
                () -> service.restore(7L, "flow", 4L, 77L));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("归档工作流只读，不能修改草稿", error.getMessage());
        verify(containers).lockContainer(7L, "flow");
        verify(versions, never()).selectOne(any());
        assertArchivedUnchangedWithoutWrites(archived);
    }

    @Test
    void restoresPublishedHistoryIntoAnEditableDraftWithoutChangingTheActivePublication() {
        WorkflowContainerDO container = container(4L);
        container.setActiveDefinitionVersionId(88L);
        when(containers.selectOne(any())).thenReturn(container);
        when(drafts.selectOne(any())).thenReturn(draft(4L, Map.of()));
        WorkflowDefinitionVersionDO historical = new WorkflowDefinitionVersionDO();
        historical.setId(77L);
        historical.setDefinition(validDefinition());
        when(versions.selectOne(any())).thenReturn(historical);
        when(containers.advanceDraftRevision(42L, 7L, 4L, 5L)).thenReturn(1);

        WorkflowDraftResponse restored = service.restore(7L, "flow", 4L, 77L);

        assertEquals(5L, restored.revision());
        assertEquals("draft", restored.status());
        assertEquals(88L, restored.activeDefinitionVersionId());
        assertEquals(validDefinition(), restored.definition());
        verify(containers).advanceDraftRevision(42L, 7L, 4L, 5L);
        verify(containers).advanceBuilderSnapshot(42L, 7L, 4L, 5L);
        verify(drafts).updateById(any(WorkflowDraftRevisionDO.class));
        verify(containers, never()).activateDefinitionVersion(anyLong(), anyLong(), anyLong(), anyLong());
        verify(versions, never()).insert(any(WorkflowDefinitionVersionDO.class));
        verify(versions, never()).updateById(any(WorkflowDefinitionVersionDO.class));
        verify(capabilityBridge, never()).publish(any());
    }

    @Test
    void keepsArchivedCreateForkAndPublishRejectedWithoutMutation() {
        WorkflowContainerDO archived = container(4L);
        archived.setStatus("archived");
        archived.setActiveDefinitionVersionId(88L);
        when(containers.selectOne(any())).thenReturn(archived);

        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> service.create(7L, "flow", validDefinition())).getStatus());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> service.forkPublishedDraft(7L, "flow", 4L)).getStatus());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> service.publish(7L, "flow", 4L)).getStatus());

        verify(containers, never()).insert(any(WorkflowContainerDO.class));
        verify(containers, never()).forkPublishedDraft(anyLong(), anyLong(), anyLong(), anyLong());
        verify(containers, never()).activateDefinitionVersion(anyLong(), anyLong(), anyLong(), anyLong());
        assertArchivedUnchangedWithoutWrites(archived);
    }

    private void assertArchivedUnchangedWithoutWrites(WorkflowContainerDO archived) {
        assertEquals("archived", archived.getStatus());
        assertEquals(4L, archived.getCurrentDraftRevision());
        assertEquals(88L, archived.getActiveDefinitionVersionId());
        verify(containers, never()).advanceDraftRevision(anyLong(), anyLong(), anyLong(), anyLong());
        verify(containers, never()).advanceBuilderSnapshot(anyLong(), anyLong(), anyLong(), anyLong());
        verify(drafts, never()).selectOne(any());
        verify(drafts, never()).insert(any(WorkflowDraftRevisionDO.class));
        verify(drafts, never()).updateById(any(WorkflowDraftRevisionDO.class));
        verify(versions, never()).insert(any(WorkflowDefinitionVersionDO.class));
        verify(versions, never()).updateById(any(WorkflowDefinitionVersionDO.class));
        verify(capabilityBridge, never()).publish(any());
    }

    @Test
    void savesPublishedConfigurationIntoTheSameWorkingDraftWithoutMutatingItsPublication() {
        WorkflowContainerDO published = container(4L);
        published.setStatus("published");
        published.setActiveDefinitionVersionId(88L);
        when(containers.selectOne(any())).thenReturn(published);

        when(drafts.selectOne(any())).thenReturn(draft(4L, Map.of()));
        when(containers.advanceDraftRevision(42L, 7L, 4L, 5L)).thenReturn(1);
        var saved = service.save(7L, "flow", 4L, validDefinition());
        assertEquals(5L, saved.revision());
        assertEquals("draft", saved.status());
        assertEquals(88L, saved.activeDefinitionVersionId());
        verify(versions, never()).updateById(any(WorkflowDefinitionVersionDO.class));
    }

    @Test
    void forksThePublishedDefinitionIntoTheSameWorkingDraft() {
        WorkflowContainerDO published = container(4L);
        published.setStatus("published");
        published.setActiveDefinitionVersionId(88L);
        when(containers.selectOne(any())).thenReturn(published);
        when(drafts.selectOne(any())).thenReturn(draft(4L, Map.of()));

        WorkflowDefinitionVersionDO active = new WorkflowDefinitionVersionDO();
        active.setId(88L);
        active.setDefinition(validDefinition());
        when(versions.selectOne(any())).thenReturn(active);
        when(containers.forkPublishedDraft(42L, 7L, 4L, 5L)).thenReturn(1);

        WorkflowDraftResponse forked = service.forkPublishedDraft(7L, "flow", 4L);

        assertEquals(5L, forked.revision());
        assertEquals("draft", forked.status());
        assertEquals(validDefinition(), forked.definition());
        verify(drafts).updateById(any(WorkflowDraftRevisionDO.class));
        verify(drafts, never()).insert(any(WorkflowDraftRevisionDO.class));
    }

    @Test
    void blocksInvalidPublishAndPublishesAValidDraftExactlyOnce() {
        WorkflowContainerDO container = container(2L);
        when(containers.selectOne(any())).thenReturn(container);
        when(drafts.selectOne(any())).thenReturn(draft(2L, Map.of(
                "entry", "legacy", "nodes", List.of(Map.of("id", "legacy", "type", "http")), "edges", List.of())));

        ApiException error = assertThrows(ApiException.class, () -> service.publish(7L, "flow", 2L));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatus());
        verify(versions, never()).insert(any(WorkflowDefinitionVersionDO.class));

        when(drafts.selectOne(any())).thenReturn(draft(2L, validDefinition()));
        when(versions.selectOne(any())).thenReturn(null);
        when(versions.selectCount(any())).thenReturn(0L);
        when(capabilityBridge.publish(any())).thenReturn(publication(700L));
        when(containers.activateDefinitionVersion(anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(1);
        WorkflowPublishResponse published = service.publish(7L, "flow", 2L);
        assertEquals(1L, published.definitionVersion());
        assertEquals(700L, published.capabilityVersionId());
        verify(versions).insert(any(WorkflowDefinitionVersionDO.class));
    }

    @Test
    void returnsStableProblemsForSchemaGraphAndCapabilityViolations() {
        List<WorkflowValidationProblem> problems = new WorkflowDraftValidator().validate(Map.of(
                "memory", Map.of(), "entry", "missing",
                "nodes", List.of(
                        Map.of("id", "dup", "type", "tool"),
                        Map.of("id", "dup", "type", "knowledge_query", "args", Map.of()),
                        Map.of("id", "legacy", "type", "http")),
                "edges", List.of(Map.of("from", "dup", "to", "dup"),
                        Map.of("from", "missing", "to", "dup"))));

        assertTrue(problems.stream().map(WorkflowValidationProblem::code).toList().containsAll(List.of(
                "WF_POLICY_MEMORY", "WF_CAPABILITY_BINDING_CONTRACT", "WF_GRAPH_DUPLICATE_NODE",
                "WF_CAPABILITY_TOOL_SLOT", "WF_NODE_HTTP_REMOVED", "WF_GRAPH_ENTRY",
                "WF_GRAPH_SELF_LOOP", "WF_GRAPH_EDGE_ENDPOINT")));
    }

    @Test
    void comparesDraftAgainstPublishedVersionWithStableJsonPointerChanges() {
        WorkflowContainerDO container = container(4L);
        container.setActiveDefinitionVersionId(88L);
        when(containers.selectOne(any())).thenReturn(container);
        when(drafts.selectOne(any())).thenReturn(draft(4L, Map.of(
                "entry", "start",
                "nodes", List.of(Map.of("id", "start", "type", "start"),
                        Map.of("id", "knowledge", "type", "knowledge_query", "name", "new"),
                        Map.of("id", "output", "type", "output")),
                "meta", Map.of("key/with~escape", "new", "added", true),
                "added", "yes")));
        WorkflowDefinitionVersionDO active = new WorkflowDefinitionVersionDO();
        active.setDefinitionVersion(3L);
        active.setDefinition(Map.of(
                "entry", "start",
                "nodes", List.of(Map.of("id", "start", "type", "start"),
                        Map.of("id", "knowledge", "type", "knowledge_query", "name", "old")),
                "meta", Map.of("key/with~escape", "old"),
                "obsolete", true));
        when(versions.selectOne(any())).thenReturn(active);

        WorkflowDraftDiffResponse diff = service.diff(7L, "flow");

        assertEquals(4L, diff.draftRevision());
        assertEquals(3L, diff.baseDefinitionVersion());
        assertTrue(diff.changes().contains(new WorkflowDefinitionDiffEntry("add", "/added", null, "yes")));
        assertTrue(diff.changes().contains(new WorkflowDefinitionDiffEntry("replace", "/meta/key~1with~0escape", "old", "new")));
        assertTrue(diff.changes().contains(new WorkflowDefinitionDiffEntry("add", "/meta/added", null, true)));
        assertTrue(diff.changes().contains(new WorkflowDefinitionDiffEntry("replace", "/nodes/1/name", "old", "new")));
        assertTrue(diff.changes().contains(new WorkflowDefinitionDiffEntry("add", "/nodes/2", null,
                Map.of("id", "output", "type", "output"))));
        assertTrue(diff.changes().contains(new WorkflowDefinitionDiffEntry("remove", "/obsolete", true, null)));
    }

    @Test
    void comparesMissingPublishedBaselineAndRemovedArrayItems() {
        WorkflowContainerDO container = container(2L);
        when(containers.selectOne(any())).thenReturn(container);
        when(drafts.selectOne(any())).thenReturn(draft(2L, validDefinition()));
        WorkflowDraftDiffResponse firstDraft = service.diff(7L, "flow");
        assertEquals(null, firstDraft.baseDefinitionVersion());
        assertTrue(firstDraft.changes().isEmpty());

        container.setActiveDefinitionVersionId(88L);
        when(drafts.selectOne(any())).thenReturn(draft(2L, Map.of("items", List.of("first"))));
        WorkflowDefinitionVersionDO active = new WorkflowDefinitionVersionDO();
        active.setDefinitionVersion(1L);
        active.setDefinition(Map.of("items", List.of("first", "second")));
        when(versions.selectOne(any())).thenReturn(active);

        WorkflowDraftDiffResponse removed = service.diff(7L, "flow");
        assertEquals(List.of(new WorkflowDefinitionDiffEntry("remove", "/items/1", "second", null)), removed.changes());
    }

    @Test
    void stripsCanvasLayoutFromDraftAndPublishedDefinitions() {
        when(containers.selectOne(any())).thenReturn(null);
        WorkflowDraftResponse created = service.create(7L, "layout-free", Map.of(
                "entry", "start",
                "nodes", List.of(Map.of("id", "start", "type", "start", "layout", Map.of("x", 10, "y", 20))),
                "edges", List.of()));

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) created.definition().get("nodes");
        assertTrue(!nodes.get(0).containsKey("layout"));
    }

    @Test
    void publishesTheExactCanonicalScriptResourceSnapshot() {
        byte[] python = "print('申请')\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] javascript = "console.log('ok');\n".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> definition = scriptDefinition(List.of(
                resource("scripts/z.py", python),
                resource("scripts/a.js", javascript)));
        WorkflowContainerDO container = container(3L);
        when(containers.selectOne(any())).thenReturn(container);
        when(drafts.selectOne(any())).thenReturn(draft(3L, definition));
        when(versions.selectOne(any())).thenReturn(null);
        when(versions.selectCount(any())).thenReturn(0L);
        when(capabilityBridge.publish(any())).thenReturn(publication(700L));
        when(containers.activateDefinitionVersion(
                anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(1);

        service.publish(7L, "flow", 3L);

        ArgumentCaptor<WorkflowDefinitionVersionDO> captured =
                ArgumentCaptor.forClass(WorkflowDefinitionVersionDO.class);
        verify(versions).insert(captured.capture());
        List<?> resources = (List<?>) captured.getValue().getDefinition()
                .get("resources");
        assertEquals("scripts/a.js",
                ((Map<?, ?>) resources.get(0)).get("path"));
        assertEquals("scripts/z.py",
                ((Map<?, ?>) resources.get(1)).get("path"));
        assertEquals(Base64.getEncoder().encodeToString(python),
                ((Map<?, ?>) resources.get(1)).get("content_base64"));
        assertEquals(sha256(python),
                ((Map<?, ?>) resources.get(1)).get("sha256"));
    }

    @Test
    void rejectsInvalidScriptResourceAtDraftImportWithoutWriting() {
        when(containers.selectOne(any())).thenReturn(null);
        byte[] content = "print('ok')\n".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> invalid = scriptDefinition(List.of(Map.of(
                "path", "scripts/z.py",
                "content_base64", Base64.getEncoder().encodeToString(content),
                "sha256", "0".repeat(64))));

        ApiException rejected = assertThrows(ApiException.class,
                () -> service.create(7L, "script-flow", invalid));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.getStatus());
        verify(drafts, never()).insert(any(WorkflowDraftRevisionDO.class));
    }

    private static WorkflowContainerDO container(long revision) {
        WorkflowContainerDO container = new WorkflowContainerDO();
        container.setId(42L);
        container.setTenantId(7L);
        container.setWorkflowId("flow");
        container.setStatus("draft");
        container.setCurrentDraftRevision(revision);
        return container;
    }

    private static WorkflowDraftRevisionDO draft(long revision, Map<String, Object> definition) {
        WorkflowDraftRevisionDO draft = new WorkflowDraftRevisionDO();
        draft.setId(99L);
        draft.setTenantId(7L);
        draft.setContainerId(42L);
        draft.setRevision(revision);
        draft.setDefinition(definition);
        return draft;
    }

    private static Map<String, Object> validDefinition() {
        return Map.of("entry", "start",
                "nodes", List.of(Map.of("id", "start", "type", "start"),
                        Map.of("id", "knowledge", "type", "knowledge_query",
                                "requirement", "knowledge_slot", "query", "question",
                                "result_to", "state.knowledge",
                                "budget", Map.of("max_items", 5, "max_tokens", 1_000,
                                        "max_characters", 4_000, "timeout_ms", 3_000,
                                        "max_sources", 2))),
                "edges", List.of(Map.of("from", "start", "to", "knowledge")),
                "meta", Map.of(
                        "capability_requirements", List.of(Map.of(
                                "slot", "knowledge_slot", "type", "knowledge_source",
                                "capability_id", "knowledge", "version_constraint", "1.0.0")),
                        "capability", Map.of(
                                "model_invocable", true,
                                "user_invocable", false,
                                "input_schema", Map.of(),
                                "output_schema", Map.of(),
                                "supports_hitl", false,
                                "export_fields", List.of(),
                                "timeout_ms", 30_000,
                                "concurrency_safe", true)));
    }

    private static Map<String, Object> scriptDefinition(
            List<Map<String, Object>> resources) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("entry", "script");
        definition.put("nodes", List.of(
                Map.of("id", "script", "type", "script", "result_to", "state",
                        "args", Map.of("script", "scripts/z.py")),
                Map.of("id", "end", "type", "end")));
        definition.put("edges", List.of(Map.of("from", "script", "to", "end")));
        definition.put("resources", resources);
        definition.put("meta", Map.of("capability", Map.of(
                "model_invocable", true,
                "user_invocable", false,
                "input_schema", Map.of(),
                "output_schema", Map.of(),
                "supports_hitl", false,
                "export_fields", List.of(),
                "timeout_ms", 30_000,
                "concurrency_safe", true)));
        return definition;
    }

    private static Map<String, Object> resource(String path, byte[] content) {
        return Map.of(
                "path", path,
                "content_base64", Base64.getEncoder().encodeToString(content),
                "sha256", sha256(content));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static CapabilityProducerPublicationResult publication(long capabilityVersionId) {
        CapabilityVersionView version = new CapabilityVersionView(capabilityVersionId, 701L, 7L,
                CapabilityType.WORKFLOW, "definition:1", "Flow", "", "TENANT", Map.of(),
                "hash", "PUBLISHED", null, null);
        return new CapabilityProducerPublicationResult(null, version, false);
    }
}
