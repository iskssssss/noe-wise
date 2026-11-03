package com.skillforge.core.agent.management;

import com.skillforge.core.agent.AgentService;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.AgentPublicationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.skillforge.core.agent.capabilitymount.CapabilityMountPersistenceService;
import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import com.skillforge.core.agent.capabilitymount.CapabilityMountStatus;
import com.skillforge.core.agent.capabilitymount.CreateCapabilityMountCommand;
import com.skillforge.core.agent.capabilitymount.DeleteCapabilityMountCommand;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.SkillActivationMode;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import com.skillforge.core.foundation.workflowtest.WorkflowTestRegistry;
import com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.http.HttpStatus;

/** 管理应用边界覆盖租户范围、不可变结果以及历史恢复的事务所有权。 */
class AgentManagementApplicationServiceTest {
    private final AgentTemplateService templates = mock(AgentTemplateService.class);
    private final AgentService agents = mock(AgentService.class);
    private final CapabilityMountPersistenceService mounts = mock(CapabilityMountPersistenceService.class);
    private final CapabilityCatalogDatabaseContext database = mock(CapabilityCatalogDatabaseContext.class);
    private final AgentPublicationService publications = mock(AgentPublicationService.class);
    private final WorkflowTestRegistry tests = mock(WorkflowTestRegistry.class);
    private final WorkflowTestIsolation isolation = mock(WorkflowTestIsolation.class);
    private final FoundationAccess access = mock(FoundationAccess.class);
    private final AgentManagementContracts.Caller caller = new AgentManagementContracts.Caller(7, false);
    private final AgentConfigurationPolicy configurations = mock(AgentConfigurationPolicy.class);
    private final AgentManagementApplicationService service = new AgentManagementApplicationService(
            templates, agents, mounts, database, tests, isolation, access, configurations, publications,
            mock(FoundationOrganizationService.class), mock(ResourceQuota.class), mock(FeatureGate.class));

    @BeforeAll
    static void metadata() {
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AgentDO.class);
    }

    @BeforeEach
    void identity() {
        TenantContext.setTenantId("7");
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "7", "70"));
    }

    @AfterEach
    void clearIdentity() { TenantContext.clear(); }

    @Test
    void patchCannotPublishOrWriteSiblingFieldsBeforeRejectingPublishedStatus() {
        assertThatThrownBy(() -> service.update(caller, "9", new AgentManagementContracts.Update(
                "must-not-change", com.skillforge.core.agent.enums.AgentStatus.PUBLISHED,
                "must-not-save", null, null, null, null, null, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("发布接口");

        org.mockito.Mockito.verifyNoInteractions(agents, publications, mounts, database);
    }

    @Test
    void dedicatedPublishRejectsManagedDefinitionBeforeWritingPublication() {
        var stored = agent("managed");
        when(agents.getOne(any(Wrapper.class))).thenReturn(stored);
        when(agents.lockVersion(7, 9)).thenReturn(stored);
        var denied = new ApiException(HttpStatus.CONFLICT, "AGENT_DEFINITION_MANAGED", "公共定义由平台管理", false);
        doThrow(denied).when(agents).requireIndependentDefinition(stored);

        assertThatThrownBy(() -> service.publish(caller, "9")).isSameAs(denied);

        org.mockito.Mockito.verifyNoInteractions(publications, mounts);
    }

    @Test
    void directApplicationReadAndHistoryCallsRejectCurrentResourceDenialBeforeLoadingAnything() {
        var denied = new ApiException(HttpStatus.FORBIDDEN, "resource_denied", "资源未授权", false);
        doThrow(denied).when(access).requireResource("agent", "9", "read");

        assertThatThrownBy(() -> service.get(caller, "9")).isSameAs(denied);
        assertThatThrownBy(() -> service.detail(caller, "9")).isSameAs(denied);
        assertThatThrownBy(() -> service.revisions(caller, "9")).isSameAs(denied);
        assertThatThrownBy(() -> service.revisionDetail(caller, "9", 1)).isSameAs(denied);

        org.mockito.Mockito.verifyNoInteractions(agents, templates, publications, mounts, database, tests);
    }

    @Test
    void directApplicationWritesRejectManageDenialBeforeDraftPublicationOrMountMutations() {
        var denied = new ApiException(HttpStatus.FORBIDDEN, "resource_denied", "资源未授权", false);
        doThrow(denied).when(access).requireResource("agent", "9", "manage");

        assertThatThrownBy(() -> service.update(caller, "9", new AgentManagementContracts.Update(
                "forbidden", null, null, null, null, null, null, null, null))).isSameAs(denied);
        assertThatThrownBy(() -> service.delete(caller, "9")).isSameAs(denied);
        assertThatThrownBy(() -> service.publish(caller, "9")).isSameAs(denied);
        assertThatThrownBy(() -> service.revise(caller, "9")).isSameAs(denied);
        assertThatThrownBy(() -> service.restoreRevision(caller, "9", 1)).isSameAs(denied);

        org.mockito.Mockito.verifyNoInteractions(agents, templates, publications, mounts, database, tests);
    }

    @Test
    void createWithoutKeyGeneratesDistinctServerIdentifiers() {
        var request = new AgentManagementContracts.Create(null, "客服助手", null, null, Map.of());
        var first = service.create(caller, request);
        var second = service.create(caller, request);
        assertThat(first.agentKey()).isNotBlank();
        assertThat(second.agentKey()).isNotBlank().isNotEqualTo(first.agentKey());
        var saved = ArgumentCaptor.forClass(AgentDO.class);
        verify(agents, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(AgentDO::getAgentKey)
                .containsExactly(first.agentKey(), second.agentKey());
    }

    @Test
    void directCreateAndReviseRequireCurrentCreatePermission() {
        var denied = new ApiException(HttpStatus.FORBIDDEN, "create_denied", "无创建权限", false);
        doThrow(denied).when(access).require("foundation.agents.create");

        assertThatThrownBy(() -> service.create(caller, new AgentManagementContracts.Create("new", "New", null, null, Map.of())))
                .isSameAs(denied);
        assertThatThrownBy(() -> service.revise(caller, "9")).isSameAs(denied);

        org.mockito.Mockito.verifyNoInteractions(agents, templates, publications, mounts, database, tests);
    }

    @Test
    void callerTenantMustMatchBothTrustedTenantAndCurrentMember() {
        assertThatThrownBy(() -> service.get(new AgentManagementContracts.Caller(8, false), "9"))
                .isInstanceOf(ApiException.class).hasMessageContaining("租户上下文与资源归属不匹配");
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "8", "80"));
        assertThatThrownBy(() -> service.get(caller, "9"))
                .isInstanceOf(ApiException.class).hasMessageContaining("租户上下文与当前成员不匹配");
        org.mockito.Mockito.verifyNoInteractions(agents, templates, publications, mounts, isolation, tests);
    }

    @Test
    void verifiedExternalCallerStillCannotManageWorkflowTestAgent() {
        var denied = WorkflowTestIsolation.denied("workflow_test_resource_managed", "测试资源只能通过其测试 Session 管理");
        doThrow(denied).when(isolation).requireOrdinary(7, "agent", "9");

        assertThatThrownBy(() -> service.publish(new AgentManagementContracts.Caller(7, true), "9")).isSameAs(denied);

        org.mockito.Mockito.verifyNoInteractions(agents, templates, publications, mounts, access);
    }

    @Test
    void restrictedListKeepsTenantResourceAndWorkflowTestIsolationInDomainQuery() {
        when(access.resourceFilter("agent", "read")).thenReturn(new FoundationAccess.ResourceFilter(false, List.of("9", "10")));
        var ordinary = agent("ordinary"); ordinary.setId(10L);
        when(agents.list(any(Wrapper.class))).thenReturn(List.of(agent("test"), ordinary));
        when(tests.ownedAgentIds(7, List.of(9L, 10L))).thenReturn(Set.of(9L));

        assertThat(service.list(caller))
                .extracting(AgentManagementContracts.Agent::id).containsExactly(10L);

        var query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(agents).list(query.capture());
        String sql = query.getValue().getSqlSegment();
        assertThat(sql).contains("tenant_id =", "id IN", "ORDER BY created_at DESC");
        assertThat(query.getValue().getParamNameValuePairs().values()).contains(7L, 9L, 10L);
        verify(tests).ownedAgentIds(7, List.of(9L, 10L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void managementViewDetachesNestedOpenConfigurationFromStoredDraft() {
        AgentDO stored = agent("current");
        var memory = new LinkedHashMap<String, Object>();
        memory.put("automatic_extraction", true);
        var labels = new ArrayList<>(List.of("first"));
        var config = new LinkedHashMap<String, Object>();
        config.put("memory", memory); config.put("labels", labels); config.put("nullable", null);
        stored.setConfig(config);
        when(agents.getOne(any(Wrapper.class))).thenReturn(stored);

        var result = service.get(caller, "9");
        stored.setName("changed"); memory.put("automatic_extraction", false); labels.add("second");

        assertThat(result.name()).isEqualTo("current");
        assertThat((Map<String, Object>) result.config().get("memory")).containsEntry("automatic_extraction", true);
        assertThat((List<String>) result.config().get("labels")).containsExactly("first");
        assertThat(result.config()).containsKey("nullable");
        assertThatThrownBy(() -> ((Map<String, Object>) result.config().get("memory")).put("automatic_extraction", false))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void restoringHistoryReplacesDraftMountsAndKeepsCurrentPublicationAndStableIdentity() {
        AgentDO stored = prepareRestoration();

        var result = service.restoreRevision(caller, "9", 1);

        assertThat(result.id()).isEqualTo(9);
        assertThat(result.name()).isEqualTo("historical");
        assertThat(result.publishedRevisionId()).isEqualTo(200L);
        assertThat(result.revision()).isEqualTo(2);
        assertThat(result.hasDraftChanges()).isTrue();
        assertThat(stored.getModelConfigId()).isNull();
        verify(database).bindTenant(7L);
        verify(mounts).delete(new DeleteCapabilityMountCommand(7L, 9L, "8001", 4L));
        verify(mounts).create(new CreateCapabilityMountCommand(7L, 9L, mount("8002", 1).capability(),
                mount("8002", 1).config(), 3, CapabilityMountStatus.DISABLED));
        verify(publications, never()).publish(any());
    }

    /** 挂载恢复失败必须回滚外层领域事务，不能提前提交草稿字段写入。 */
    @Test
    void mountRestorationFailureRollsBackOwningApplicationTransaction() {
        prepareRestoration();
        var failure = new IllegalStateException("mount restore failed");
        doThrow(failure).when(mounts).create(any());
        var transactions = mock(PlatformTransactionManager.class);
        var status = new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(status);
        var factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        var transactional = (AgentManagementApplicationService) factory.getProxy();

        assertThatThrownBy(() -> transactional.restoreRevision(caller, "9", 1)).isSameAs(failure);

        verify(transactions).rollback(status);
        verify(transactions, never()).commit(any());
        verify(publications, never()).publish(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"foundation.agents.update", "foundation.agents.publish"})
    void saveAndPublishChecksBothPermissionsBeforeReadingOrWriting(String missingPermission) {
        var denied = new IllegalStateException("missing action permission");
        doThrow(denied).when(access).require(missingPermission);
        assertThatThrownBy(() -> service.saveAndPublish(caller, "9", java.util.UUID.randomUUID(), publicationUpdate("new")))
                .isSameAs(denied);
        verify(access).require("foundation.agents.update");
        org.mockito.Mockito.verifyNoInteractions(agents, publications);
    }

    @Test
    void saveAndPublishRejectsStatusChangesBeforeReadingOrWriting() {
        var payload = new AgentManagementContracts.Update("new", com.skillforge.core.agent.enums.AgentStatus.DISABLED,
                null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.saveAndPublish(caller, "9", java.util.UUID.randomUUID(), payload))
                .isInstanceOf(ApiException.class);
        org.mockito.Mockito.verifyNoInteractions(agents, publications);
    }

    @Test
    void externalCallerCannotUseNewCommandOrReadItsReceipt() {
        var external = new AgentManagementContracts.Caller(7, true);
        assertThatThrownBy(() -> service.saveAndPublish(external, "9", java.util.UUID.randomUUID(), publicationUpdate("new")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.saveAndPublishResult(external, "9", java.util.UUID.randomUUID()))
                .isInstanceOf(ApiException.class);
        org.mockito.Mockito.verifyNoInteractions(agents, publications);
    }

    @Test
    void saveAndPublishReturnsOriginalReceiptWithoutUpdatingLaterDraftOrPointer() {
        var stored = agent("later draft"); stored.setRevision(8); stored.setPublishedRevisionId(800L);
        when(agents.getOne(any(Wrapper.class))).thenReturn(stored);
        when(agents.lockVersion(7, 9)).thenReturn(stored);
        var request = java.util.UUID.randomUUID(); var payload = publicationUpdate("original");
        when(publications.requestFingerprint(7, 9, "member:70", payload)).thenReturn("a".repeat(64));
        when(publications.findRequest(7, 9, request)).thenReturn(
                new com.skillforge.core.agent.mapper.AgentPublicationMapper.RequestRow(200L, 9L, 2, Instant.EPOCH,
                        request, "a".repeat(64), "member:70"));
        assertThat(service.saveAndPublish(caller, "9", request, payload)).isEqualTo(
                new AgentManagementContracts.SaveAndPublishReceipt(request, 9, 200, 2, Instant.EPOCH));
        assertThat(stored.getName()).isEqualTo("later draft");
        assertThat(stored.getPublishedRevisionId()).isEqualTo(800L);
        verify(agents, never()).update(any(Wrapper.class));
        verify(publications, never()).publish(any(), any());
    }

    @Test
    void changedPayloadOrActorConflictsWithoutMutatingDraft() {
        var stored = agent("current");
        when(agents.getOne(any(Wrapper.class))).thenReturn(stored);
        when(agents.lockVersion(7, 9)).thenReturn(stored);
        var request = java.util.UUID.randomUUID(); var payload = publicationUpdate("new");
        when(publications.requestFingerprint(7, 9, "member:70", payload)).thenReturn("a".repeat(64));
        for (var previous : List.of(
                new com.skillforge.core.agent.mapper.AgentPublicationMapper.RequestRow(200L, 9L, 2, Instant.EPOCH,
                        request, "b".repeat(64), "member:70"),
                new com.skillforge.core.agent.mapper.AgentPublicationMapper.RequestRow(200L, 9L, 2, Instant.EPOCH,
                        request, "a".repeat(64), "member:71"))) {
            when(publications.findRequest(7, 9, request)).thenReturn(previous);
            assertThatThrownBy(() -> service.saveAndPublish(caller, "9", request, payload))
                    .isInstanceOfSatisfying(ApiException.class, error ->
                            assertThat(error.getCode()).isEqualTo("AGENT_SAVE_PUBLISH_REQUEST_CONFLICT"));
        }
        verify(agents, never()).update(any(Wrapper.class));
        verify(publications, never()).publish(any(), any());
    }

    @Test
    void newCommandPublishesItsUpdatedDraftWithPersistentRequestIdentity() {
        var stored = agent("before");
        when(agents.getOne(any(Wrapper.class))).thenReturn(stored);
        when(agents.lockVersion(7, 9)).thenReturn(stored);
        var request = java.util.UUID.randomUUID(); var payload = publicationUpdate("after");
        when(publications.requestFingerprint(7, 9, "member:70", payload)).thenReturn("a".repeat(64));
        var identity = new AgentPublicationService.RequestIdentity(request, "a".repeat(64), "member:70");
        when(publications.publish(stored, identity)).thenAnswer(call -> {
            assertThat(stored.getName()).isEqualTo("after");
            return new AgentPublicationService.Publication(200L, 2, stored,
                    new CapabilityMountSetResult(7L, 9L, 1, List.of()), Instant.EPOCH);
        });
        assertThat(service.saveAndPublish(caller, "9", request, payload)).isEqualTo(
                new AgentManagementContracts.SaveAndPublishReceipt(request, 9, 200, 2, Instant.EPOCH));
        verify(configurations).requireReady(any());
        verify(agents).requireIndependentDefinition(stored);
        verify(agents).update(any(Wrapper.class));
    }

    @Test
    void resultLookupUsesReadPermissionAndOriginalActorWithoutWriting() {
        var stored = agent("current");
        when(agents.getOne(any(Wrapper.class))).thenReturn(stored);
        var request = java.util.UUID.randomUUID();
        assertThat(service.saveAndPublishResult(caller, "9", request)).isNull();
        when(publications.findRequest(7, 9, request)).thenReturn(
                new com.skillforge.core.agent.mapper.AgentPublicationMapper.RequestRow(200L, 9L, 2, Instant.EPOCH,
                        request, "a".repeat(64), "member:70"));
        assertThat(service.saveAndPublishResult(caller, "9", request).publicationId()).isEqualTo(200);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "7", "71"));
        assertThatThrownBy(() -> service.saveAndPublishResult(caller, "9", request)).isInstanceOf(ApiException.class);
        verify(access, never()).require("foundation.agents.publish");
        verify(access, never()).require("foundation.agents.update");
        verify(publications, never()).publish(any(), any());
        verify(agents, never()).lockVersion(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void publicationFailureRollsBackOwningSaveAndPublishTransaction() {
        var stored = agent("before");
        when(agents.getOne(any(Wrapper.class))).thenReturn(stored);
        when(agents.lockVersion(7, 9)).thenReturn(stored);
        var failed = new IllegalStateException("publication insert failed");
        doThrow(failed).when(publications).publish(any(), any());
        var transactions = mock(PlatformTransactionManager.class);
        var status = new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(status);
        var factory = new ProxyFactory(service); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        var transactional = (AgentManagementApplicationService) factory.getProxy();
        assertThatThrownBy(() -> transactional.saveAndPublish(caller, "9", java.util.UUID.randomUUID(), publicationUpdate("after")))
                .isSameAs(failed);
        verify(transactions).rollback(status); verify(transactions, never()).commit(any());
    }

    private AgentManagementContracts.Update publicationUpdate(String name) {
        return new AgentManagementContracts.Update(name, null, null, null, null, null, null, null, null);
    }

    private AgentDO prepareRestoration() {
        AgentDO stored = agent("current");
        stored.setRevision(2); stored.setPublishedRevisionId(200L); stored.setPublishedAt(Instant.EPOCH);
        stored.setModelConfigId(88L);
        AgentDO previous = agent("historical");
        var snapshot = new CapabilityMountSetResult(7L, 9L, 1L, List.of(mount("8002", 1)));
        when(agents.getOne(any(Wrapper.class))).thenReturn(stored);
        when(agents.lockVersion(7, 9)).thenReturn(stored);
        when(publications.revision(7, 9, 1)).thenReturn(new AgentPublicationService.Publication(100L, 1, previous, snapshot, Instant.EPOCH));
        when(mounts.read(7L, 9L)).thenReturn(new CapabilityMountSetResult(7L, 9L, 4L, List.of(mount("8001", 4))));
        return stored;
    }

    private AgentDO agent(String name) {
        AgentDO value = new AgentDO();
        value.setId(9L); value.setTenantId(7L); value.setAgentKey("stable-agent"); value.setName(name);
        value.setStatus("published"); value.setConfig(Map.of()); value.setSystemPrompt("prompt");
        return value;
    }

    private CapabilityMount mount(String id, long revision) {
        return new CapabilityMount(id, 7L, 9L, new CapabilityRef(CapabilityType.SKILL, "1001", "1002"),
                new CapabilityMount.SkillMountConfig(SkillActivationMode.ALWAYS, 2000L), 3, revision, false);
    }
}
