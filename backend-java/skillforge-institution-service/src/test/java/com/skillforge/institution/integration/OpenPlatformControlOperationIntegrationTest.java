package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.foundation.contract.ControlOperationItem;
import com.skillforge.core.foundation.contract.ControlOperationStatus;
import com.skillforge.core.capability.contract.ResourceType;
import com.skillforge.core.foundation.persistence.ControlOperationMapper;
import com.skillforge.core.channel.persistence.OpenPlatformChannelBindingMapper;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.capability.persistence.ResourceMappingMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.ControlOperationDO;
import com.skillforge.core.channel.persistence.OpenPlatformChannelBindingDO;
import com.skillforge.core.channel.OpenPlatformChannelBindingService;
import com.skillforge.core.capability.persistence.ResourceMappingDO;
import com.skillforge.core.foundation.application.ControlOperationService;
import com.skillforge.core.foundation.application.ControlOperationWorker;
import com.skillforge.core.capability.application.OpenPlatformResourceLifecycleService;
import com.skillforge.core.capability.application.ResourceMappingService;
import com.skillforge.core.capability.application.ResourceRevisionConflictException;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.institution.controller.OpenPlatformSessionController;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformActionRequest;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformCallbackRequest;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformCommandRequest;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformCreateSessionRequest;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformMessageRequest;
import com.skillforge.core.shared.exception.ApiException;
import java.util.Map;
import java.util.Set;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Open Platform ControlOperation 受理账本的 PostgreSQL/RLS/幂等集成验证。 */
class OpenPlatformControlOperationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private PlanMapper planMapper;

    @Autowired
    private ControlOperationMapper operations;

    @Autowired
    private ControlOperationService service;

    @Autowired
    private ControlOperationWorker worker;

    @Autowired
    private ResourceMappingMapper resourceMappings;

    @Autowired
    private ResourceMappingService resourceMappingService;

    @Autowired
    private OpenPlatformResourceLifecycleService resourceLifecycle;
    @Autowired private com.skillforge.core.capability.persistence.OpenPlatformResourceRevisionMapper catalogRevisions;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate catalogJdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager catalogTransactions;
    @Autowired
    private OpenPlatformChannelBindingService channelBindingService;

    @Autowired
    private OpenPlatformChannelBindingMapper channelBindings;

    @Autowired
    private com.skillforge.core.capability.persistence.OpenPlatformDeploymentMapper deploymentRows;

    @Autowired
    private OpenPlatformSessionController openPlatformSessions;

    @Autowired
    private SessionService sessions;

    @Autowired
    private EndUserService endUsers;

    @Autowired
    private ServicePrincipalService servicePrincipals;

    @Autowired
    private AgentMapper agents;

    private String tenantId;

    @BeforeEach
    void setUp() {
        tenantId = createTenant(tenantMapper, planMapper, "control-op-" + SnowflakeIds.nextId());
        TenantContext.setTenantId(tenantId);
        var fixture=servicePrincipals.ensureInternal(tenantId,"integration-1",Set.of("resources.write"));
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(fixture.principalId(),tenantId,"integration-1",fixture.credentialVersion(),Set.of("resources.write")));
    }

    @AfterEach
    void tearDown() {
        ServicePrincipalContext.clear();
        TenantContext.clear();
    }

    @Test
    void persistsAcceptedOperationAndReplaysItWithoutCreatingASecondRow() {
        ControlOperationItem item = new ControlOperationItem(
                "item-1", "workflow-1", ControlOperationStatus.SUCCEEDED, "revision-1", null, false);
        ControlOperationService.Acceptance first = service.accept(
                tenantId, "integration-1", "op-1", null, "publish", "idem-1", "sha256:req", List.of(item));
        ControlOperationService.Acceptance duplicate = service.accept(
                tenantId, "integration-1", "op-other", "root-other", "publish", "idem-1", "sha256:req", List.of(item));

        assertEquals(true, first.created());
        assertEquals(false, duplicate.created());
        assertEquals("op-1", duplicate.operation().operationId());
        assertEquals(1L, operations.selectCount(new LambdaQueryWrapper<ControlOperationDO>()
                .eq(ControlOperationDO::getTenantId, Long.parseLong(tenantId))));
        assertThrows(IllegalArgumentException.class, () -> service.accept(
                tenantId, "integration-1", "op-2", null, "publish", "idem-1", "sha256:other", List.of(item)));
    }

    @Test
    void transitionsOperationWithPostgresJsonbAndTenantCas() {
        ControlOperationItem accepted = new ControlOperationItem(
                "item-1", "workflow-1", ControlOperationStatus.ACCEPTED, null, null, false);
        service.accept(tenantId, "integration-1", "op-transition", null, "publish", "idem-transition",
                "sha256:transition", List.of(accepted));

        var processing = service.transition(tenantId, "integration-1", "op-transition",
                ControlOperationStatus.ACCEPTED, ControlOperationStatus.PROCESSING, null);
        var completed = service.transition(tenantId, "integration-1", "op-transition",
                ControlOperationStatus.PROCESSING, ControlOperationStatus.SUCCEEDED, List.of(
                        new ControlOperationItem("item-1", "workflow-1", ControlOperationStatus.SUCCEEDED,
                                "revision-1", null, false)));

        assertEquals(ControlOperationStatus.PROCESSING, processing.status());
        assertEquals(ControlOperationStatus.SUCCEEDED, completed.status());
        assertEquals("revision-1", service.find(tenantId, "integration-1", "op-transition")
                .items().getFirst().resultRef());
        assertEquals(1L, operations.selectCount(new LambdaQueryWrapper<ControlOperationDO>()
                .eq(ControlOperationDO::getTenantId, Long.parseLong(tenantId))
                .eq(ControlOperationDO::getOperationId, "op-transition")));
        assertThrows(IllegalStateException.class, () -> service.transition(tenantId, "integration-1",
                "op-transition", ControlOperationStatus.PROCESSING, ControlOperationStatus.FAILED, null));
    }

    @Test
    void workerClaimsAcceptedOperationAndFailsClosedWithoutResourceAdapter() {
        service.accept(tenantId, "integration-1", "op-worker", null, "publish", "idem-worker",
                "sha256:worker", List.of(new ControlOperationItem(
                        "item-1", "workflow-1", ControlOperationStatus.ACCEPTED, null, null, false)));

        var result = worker.processAccepted(tenantId, "integration-1", "op-worker");

        assertEquals(ControlOperationStatus.FAILED, result.status());
        assertEquals("NO_EXECUTOR", result.items().getFirst().errorCode());
        assertEquals(ControlOperationStatus.FAILED,
                service.find(tenantId, "integration-1", "op-worker").status());
    }

    @Test
    void resourceMappingUsesPostgresRevisionAndEtagCas() {
        var created = resourceMappingService.upsert(tenantId, "integration-1", ResourceType.WORKFLOW,
                "external-workflow-1", "workflow-1", "sha256:a", null, null);
        var updated = resourceMappingService.upsert(tenantId, "integration-1", ResourceType.WORKFLOW,
                "external-workflow-1", "workflow-2", "sha256:b", created.getRevision(), created.getEtag());

        assertEquals(1L, created.getRevision());
        assertEquals(2L, updated.getRevision());
        assertEquals("workflow-2", resourceMappingService.find(tenantId, "integration-1",
                ResourceType.WORKFLOW, "external-workflow-1").getResourceId());
        assertThrows(ResourceRevisionConflictException.class, () -> resourceMappingService.upsert(
                tenantId, "integration-1", ResourceType.WORKFLOW, "external-workflow-1",
                "workflow-3", "sha256:c", 1L, created.getEtag()));
        assertEquals(1L, resourceMappings.selectCount(new LambdaQueryWrapper<ResourceMappingDO>()
                .eq(ResourceMappingDO::getTenantId, Long.parseLong(tenantId))));
    }

    @Test
    void resourceRevisionDeploymentAndChannelBindingUsePostgresCas() {
        var draft = resourceLifecycle.createDraft(tenantId, ResourceType.AGENT, "agent-open-1",
                java.util.Map.of("name", "公开 Agent", "version", 1));
        var ready = resourceLifecycle.markReady(tenantId, ResourceType.AGENT, "agent-open-1",
                draft.getRevision(), draft.getEtag());
        var published = resourceLifecycle.publish(tenantId, ResourceType.AGENT, "agent-open-1",
                ready.getRevision(), ready.getEtag());
        var deployment = resourceLifecycle.deploy(tenantId, "deployment-open-1", ResourceType.AGENT,
                "agent-open-1", published.getRevision(), "production", null, null);
        var binding = channelBindingService.bindChannel(
                tenantId, 987654L, deployment.getDeploymentId(), null, null);

        assertEquals("published", published.getStatus());
        assertEquals(1L, deployment.getDeploymentRevision());
        assertEquals(1L, binding.bindingRevision());

        var nextDraft = resourceLifecycle.createDraft(tenantId, ResourceType.AGENT, "agent-open-1",
                java.util.Map.of("name", "公开 Agent v2", "version", 2));
        var nextReady = resourceLifecycle.markReady(tenantId, ResourceType.AGENT, "agent-open-1",
                nextDraft.getRevision(), nextDraft.getEtag());
        var nextPublished = resourceLifecycle.publish(tenantId, ResourceType.AGENT, "agent-open-1",
                nextReady.getRevision(), nextReady.getEtag());
        var redeployed = deploymentRows.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.skillforge.core.capability.persistence.OpenPlatformDeploymentDO>()
                .eq("tenant_id", Long.parseLong(tenantId)).eq("deployment_id", "deployment-open-1"));
        var rebound = channelBindings.selectOne(new LambdaQueryWrapper<OpenPlatformChannelBindingDO>()
                .eq(OpenPlatformChannelBindingDO::getTenantId, Long.parseLong(tenantId))
                .eq(OpenPlatformChannelBindingDO::getChannelId, 987654L));
        assertEquals(deployment.getId(), redeployed.getId());
        assertEquals(binding.id(), rebound.getId());
        assertEquals(nextPublished.getRevision(), redeployed.getResourceRevision());
        assertEquals(nextPublished.getRevision(), rebound.getResourceRevision());
        assertEquals(2L, redeployed.getDeploymentRevision());
        assertEquals(2L, rebound.getBindingRevision());
        assertEquals("公开 Agent", resourceLifecycle.revision(tenantId, ResourceType.AGENT, "agent-open-1", 1).getContent().get("name"));
        assertEquals(1L, channelBindings.selectCount(new LambdaQueryWrapper<OpenPlatformChannelBindingDO>()
                .eq(OpenPlatformChannelBindingDO::getTenantId, Long.parseLong(tenantId))));
        assertThrows(ResourceRevisionConflictException.class, () -> resourceLifecycle.deploy(
                tenantId, "deployment-open-1", ResourceType.AGENT, "agent-open-1",
                nextPublished.getRevision(), "production", 1L, deployment.getEtag()));
    }

    /** 同输入完整历史为负控；目录只返回归并后的元数据，不加载不可变正文。 */
    @Test
    void resourceCatalogReadsBoundedDistinctSummariesWithoutBodiesAndPreservesStatusAndRls() {
        Map<String,Object> content=Map.of("name","受控目录材料","body","catalog-body-".repeat(2048));
        var a1=resourceLifecycle.createDraft(tenantId,ResourceType.AGENT,"a-catalog",content);
        var ready1=resourceLifecycle.markReady(tenantId,ResourceType.AGENT,"a-catalog",a1.getRevision(),a1.getEtag());
        resourceLifecycle.publish(tenantId,ResourceType.AGENT,"a-catalog",ready1.getRevision(),ready1.getEtag());
        var a2=resourceLifecycle.createDraft(tenantId,ResourceType.AGENT,"a-catalog",content);
        var ready2=resourceLifecycle.markReady(tenantId,ResourceType.AGENT,"a-catalog",a2.getRevision(),a2.getEtag());
        resourceLifecycle.publish(tenantId,ResourceType.AGENT,"a-catalog",ready2.getRevision(),ready2.getEtag());
        resourceLifecycle.createDraft(tenantId,ResourceType.AGENT,"a-catalog",content);
        var b1=resourceLifecycle.createDraft(tenantId,ResourceType.WORKFLOW,"b-catalog",content);
        var readyB=resourceLifecycle.markReady(tenantId,ResourceType.WORKFLOW,"b-catalog",b1.getRevision(),b1.getEtag());
        resourceLifecycle.publish(tenantId,ResourceType.WORKFLOW,"b-catalog",readyB.getRevision(),readyB.getEtag());
        TenantContext.clear();String other=createTenant(tenantMapper,planMapper,"catalog-other-"+SnowflakeIds.nextId());
        TenantContext.setTenantId(other);resourceLifecycle.createDraft(other,ResourceType.AGENT,"foreign-catalog",content);
        String reader="resource_catalog_"+SnowflakeIds.nextId();
        catalogJdbc.execute("CREATE ROLE "+reader+" NOLOGIN NOSUPERUSER NOBYPASSRLS");
        catalogJdbc.execute("GRANT USAGE ON SCHEMA public TO "+reader);
        catalogJdbc.execute("GRANT SELECT ON nw_open_platform_resource_revisions TO "+reader);
        var tx=new org.springframework.transaction.support.TransactionTemplate(catalogTransactions);
        TenantContext.setTenantId(tenantId);
        tx.executeWithoutResult(status->{
            catalogJdbc.execute("SET LOCAL ROLE "+reader);
            assertFalse(catalogJdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class));
            var full=catalogRevisions.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.skillforge.core.capability.persistence.OpenPlatformResourceRevisionDO>()
                    .eq("tenant_id",Long.parseLong(tenantId)).orderByAsc("resource_type","resource_id").orderByDesc("revision"));
            assertEquals(4,full.size());assertTrue(full.stream().allMatch(row->content.equals(row.getContent())));
            long fullBytes=catalogJdbc.queryForObject("SELECT sum(octet_length(content::text)) FROM nw_open_platform_resource_revisions WHERE tenant_id=?",Long.class,Long.parseLong(tenantId));
            var summaries=resourceLifecycle.listLatest(tenantId,null,null,2);
            assertEquals(List.of("a-catalog","b-catalog"),summaries.stream().map(com.skillforge.core.capability.persistence.OpenPlatformResourceRevisionDO::getResourceId).toList());
            assertEquals(List.of(3L,1L),summaries.stream().map(com.skillforge.core.capability.persistence.OpenPlatformResourceRevisionDO::getRevision).toList());
            for(var summary:summaries) {
                assertNull(summary.getContent());
                var original=full.stream().filter(row->row.getResourceType().equals(summary.getResourceType())
                        &&row.getResourceId().equals(summary.getResourceId())&&row.getRevision().equals(summary.getRevision())).findFirst().orElseThrow();
                assertEquals(original.getResourceType(),summary.getResourceType());
                assertEquals(original.getResourceId(),summary.getResourceId());assertEquals(original.getRevision(),summary.getRevision());
                assertEquals(original.getStatus(),summary.getStatus());assertEquals(original.getContentHash(),summary.getContentHash());
                assertEquals(original.getEtag(),summary.getEtag());assertEquals(original.getCreatedAt(),summary.getCreatedAt());
                assertEquals(original.getUpdatedAt(),summary.getUpdatedAt());assertEquals(original.getPublishedAt(),summary.getPublishedAt());
            }
            var matchingPublished=resourceLifecycle.listLatest(tenantId,null,"published",2);
            assertEquals(List.of("a-catalog","b-catalog"),matchingPublished.stream().map(com.skillforge.core.capability.persistence.OpenPlatformResourceRevisionDO::getResourceId).toList());
            assertEquals(List.of(2L,1L),matchingPublished.stream().map(com.skillforge.core.capability.persistence.OpenPlatformResourceRevisionDO::getRevision).toList());
            assertEquals(List.of("a-catalog"),resourceLifecycle.listLatest(tenantId,null,null,1).stream()
                    .map(com.skillforge.core.capability.persistence.OpenPlatformResourceRevisionDO::getResourceId).toList());
            assertEquals(List.of("b-catalog"),resourceLifecycle.listLatest(tenantId,ResourceType.WORKFLOW,null,2).stream()
                    .map(com.skillforge.core.capability.persistence.OpenPlatformResourceRevisionDO::getResourceId).toList());
            assertEquals(content,resourceLifecycle.revision(tenantId,ResourceType.AGENT,"a-catalog",1).getContent());
            assertEquals(content,resourceLifecycle.latest(tenantId,ResourceType.AGENT,"a-catalog").getContent());
            assertTrue(resourceLifecycle.listLatest(other,null,null,2).isEmpty());
            assertTrue(fullBytes>0);
            System.out.printf("resource-catalog same-input: fullHistoryRows=%d summaryRows=%d completeContentUtf8Bytes=%d summaryContentUtf8Bytes=0%n",full.size(),summaries.size(),fullBytes);
        });
        TenantContext.setTenantId(other);
        tx.executeWithoutResult(status->{
            catalogJdbc.execute("SET LOCAL ROLE "+reader);
            // 原tenant参数与当前RLS租户不同，不能仅凭SQL tenant条件通过隔离检查。
            assertTrue(resourceLifecycle.listLatest(tenantId,null,null,2).isEmpty());
            assertEquals(1,resourceLifecycle.listLatest(other,null,null,2).size());
        });
    }

    @Test
    void resourceCatalogReturnsLatestPublishedRevisionForEverySupportedResourceType() {
        for (ResourceType type : ResourceType.values()) {
            String resourceId = type.value() + "-catalog";
            var draft = resourceLifecycle.createDraft(tenantId, type, resourceId,
                    Map.of("resource_type", type.value(), "version", 1));
            var ready = resourceLifecycle.markReady(tenantId, type, resourceId,
                    draft.getRevision(), draft.getEtag());
            resourceLifecycle.publish(tenantId, type, resourceId, ready.getRevision(), ready.getEtag());
        }

        var catalog = resourceLifecycle.listLatest(tenantId, null, "published", 200);

        assertEquals(ResourceType.values().length, catalog.size());
        assertEquals(ResourceType.values().length,
                catalog.stream().map(row -> row.getResourceType()).distinct().count());
        var workflow = resourceLifecycle.revision(tenantId, ResourceType.WORKFLOW,
                "workflow-catalog", 1L);
        assertEquals("published", workflow.getStatus());
        assertEquals("workflow", workflow.getContent().get("resource_type"));
    }

    @Test
    void openPlatformInboundFailsClosedAndCloseCommandRemainsIdempotent() {
        Long endUserId = endUsers.ensureExternal(tenantId, "crm", "external-user-1", "Open Platform 用户").getId();
        var trusted = servicePrincipals.ensureInternal(tenantId, "crm",
                Set.of(OpenPlatformSessionController.SESSION_WRITE_SCOPE));
        Long agentId = insertAgent();
        // 直调控制器需提供 HTTP 过滤器通常建立的追踪上下文，不能用 inputId 补造 trace。
        RequestContext.setTraceId("open-platform-control-test-" + java.util.UUID.randomUUID());
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                trusted.principalId(), tenantId, trusted.integrationId(), trusted.credentialVersion(),
                Set.of(OpenPlatformSessionController.SESSION_WRITE_SCOPE)));
        try {
            var created = openPlatformSessions.create(new OpenPlatformCreateSessionRequest(
                    null, "external-user-1", "external-conversation-1", agentId.toString(), endUserId.toString()));
            String sessionId = created.data().sessionId();
            assertNotNull(sessionId);
            var persisted = sessions.require(tenantId, sessionId);
            assertEquals("crm", persisted.getIntegrationId());
            assertEquals("open-platform:crm", persisted.getScopeType());
            assertEquals("external-conversation-1", persisted.getScopeId());
            assertEquals("external-user-1", persisted.getMetadata().get("external_user_ref"));
            assertEquals(endUserId, persisted.getEndUserId());
            assertEquals(agentId, persisted.getAgentId());
            assertEquals("external",persisted.getOriginKind());
            assertNull(persisted.getBusinessScopeId());

            ApiException messageError = assertThrows(ApiException.class, () ->
                    openPlatformSessions.message(sessionId, "message-1",
                            new OpenPlatformMessageRequest("hello")));
            assertTrue(messageError.getStatus().is4xxClientError()
                    || messageError.getStatus().is5xxServerError());
            assertThrows(ApiException.class, () -> openPlatformSessions.action(sessionId, "action-1",
                    new OpenPlatformActionRequest("execution-1", "approve", Map.of("choice", "yes"))));
            var completionRejected = assertThrows(ApiException.class, () -> openPlatformSessions.callback(sessionId, "callback-completion",
                    new OpenPlatformCallbackRequest("callback-1", "external.completed",
                            null, "workflow-1", Map.of("external_id", "result-1"))));
            assertEquals(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, completionRejected.getStatus());
            assertEquals("callback_completion_requires_registered_delivery", completionRejected.getCode());
            // business_event使用同一可信integration受理；未配置执行模型仍须诚实失败，外部ID不授予完成权。
            assertThrows(ApiException.class, () -> openPlatformSessions.callback(sessionId, "callback-business-event",
                    new OpenPlatformCallbackRequest("callback-1", "business_event",
                            "external-execution-1", "external-workflow-1", Map.of("external_id", "result-1"))));
            var replyRejected = assertThrows(ApiException.class, () -> openPlatformSessions.callback(sessionId, "callback-reply",
                    new OpenPlatformCallbackRequest("callback-2", "business_event", null, null,
                            Map.of("business", Map.of("workflow_input", Map.of("response", "forged"))))));
            assertEquals("callback_completion_requires_registered_delivery", replyRejected.getCode());

            var closed = openPlatformSessions.command(sessionId, "command-1",
                    new OpenPlatformCommandRequest(OpenPlatformSessionController.CLOSE_SESSION_COMMAND,
                            null, "finished"));
            assertEquals("closed", closed.status());
            assertTrue(sessions.isOpenPlatformClosed(sessions.require(tenantId, sessionId)));
            var replay = openPlatformSessions.command(sessionId, "command-1",
                    new OpenPlatformCommandRequest(OpenPlatformSessionController.CLOSE_SESSION_COMMAND,
                            null, "finished"));
            assertEquals(closed.inputId(), replay.inputId());
            assertEquals("closed", replay.status());
        } finally {
            ServicePrincipalContext.clear();
            RequestContext.clear();
        }
    }

    private Long insertAgent() {
        AgentDO agent = new AgentDO();
        agent.setId(SnowflakeIds.nextId());
        agent.setTenantId(Long.parseLong(tenantId));
        agent.setAgentKey("agent-" + agent.getId());
        agent.setName("Open Platform Agent");
        agent.setTemplateVersion("1.0");
        agent.setSystemPrompt("你是测试助手");
        agent.setRoleDescription("测试");
        // 关闭命令仍核验当前已发布 Agent 的归属；执行失败夹具仅省略模型和发布快照。
        agent.setStatus("published");
        agent.setPublishedAt(java.time.Instant.now());
        agents.insert(agent);
        return agent.getId();
    }
}
