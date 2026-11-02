package com.skillforge.institution.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.systemagent.SystemAgentTypes;
import com.skillforge.core.billing.export.PlatformUsageContracts;
import com.skillforge.core.chat.contract.ChatContent;
import com.skillforge.institution.group.GroupContracts;
import com.skillforge.institution.group.GroupMemoryExtractionPolicy;
import com.skillforge.institution.group.GroupMemorySources;
import com.skillforge.institution.group.GroupMessageContracts;
import com.skillforge.institution.memory.MemoryExtractionWorker;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;

/** 真实 LlmService 的群后台提取 UNKNOWN→供应商回执→正式用量链；模型服务仅使用本地合成响应。 */
final class GroupMemoryUsageScenario {
    private static final String GROUPS="/api/v1/open-platform/groups";
    private static final String SYSTEM_AGENT="900000000000000003";
    private final ObjectMapper json;
    private final GroupBusinessWorkflowScenario.Machine machine;
    private final ManagedApi platform,institution;
    private final JdbcTemplate database;
    private final MemoryExtractionWorker worker;

    GroupMemoryUsageScenario(ObjectMapper json,GroupBusinessWorkflowScenario.Machine machine,ManagedApi platform,
            ManagedApi institution,JdbcTemplate database,MemoryExtractionWorker worker) {
        this.json=json;this.machine=machine;this.platform=platform;this.institution=institution;this.database=database;this.worker=worker;
    }

    Result verify(JsonNode credential,String tenant,String integration,String model,String scope,String marker,
            BusinessWorkflowModelProvider provider) throws Exception {
        SystemAgentTypes.View published=publishPurpose(json,platform,SYSTEM_AGENT,model);
        String agent=institution.call("POST","/api/v1/agents",new MemoryAgent(marker,"群后台提取责任Agent",
                "仅处理本群明确配置的记忆提取。",model,new AgentConfig(new MemoryConfig(true))),null,200).path("id").asText();
        institution.call("POST","/api/v1/agents/publish",new AgentPublish(agent),null,200);
        String manager=machine.call(credential,"POST","/api/v1/open-platform/end-users/ensure",
                new EndUser(marker+"-author","群来源真实作者"),marker+":user",200).path("end_user_id").asText();
        JsonNode group=machine.call(credential,"POST",GROUPS+"/ensure",new GroupContracts.Ensure("memory-usage",marker,"life",scope),marker+":group",200);
        String groupId=group.path("group_binding_id").asText(),session=group.path("session_id").asText();
        machine.call(credential,"POST",GROUPS+"/members/update",new GroupContracts.UpdateMember(groupId,Long.valueOf(manager),"manager","active",0L,marker+":join",1L),marker+":join",200);
        machine.call(credential,"POST",GROUPS+"/agents/update",new GroupContracts.UpdateAgent(groupId,Long.valueOf(agent),"active",0L,marker+":agent",1L),marker+":agent",200);
        var script=provider.registerExtraction(groupId);
        JsonNode source=machine.call(credential,"POST",GROUPS+"/messages",new GroupMessageContracts.MessageRecord(groupId,marker+":source",1L,
                new GroupMessageContracts.EndUserAuthor(Long.valueOf(manager)),ChatContent.text("本条仅验证群后台提取的正式计量来源，无需保存长期事实。")),marker+":source",200);
        String event=GroupMemorySources.eventId(source.path("source_message_id").asText(),1).value();
        machine.call(credential,"PUT",GROUPS+"/memories/extraction-policy",new GroupMemoryExtractionPolicy.Command(groupId,Long.valueOf(manager),true,Long.valueOf(agent),0L),null,200);
        var initialUsage=new AtomicReference<PlatformUsageContracts.Page>();
        try(var executor=Executors.newSingleThreadExecutor()) {
            var processing=executor.submit(worker::poll);
            try {
                assertThat(script.awaitEntered()).as("正式来源任务必须通过真实LlmService到达本地Provider").isTrue();
                var provisional=usage(credential,groupId);
                initialUsage.set(provisional);
                assertThat(provisional.items()).isNotEmpty();
                var unknown=provisional.items().stream().filter(item->"UNKNOWN".equals(item.usageSource())).toList();
                assertThat(unknown).as("供应商回执未到时保留原UNKNOWN，不提前填零").isNotEmpty();
                var job=job(tenant,event);
                assertThat(job.systemRunId()).isNotBlank();
                assertThat(job.configurationRef()).isEqualTo("system-agent-config:"+SYSTEM_AGENT+":"+published.publishedVersion());
                unknown.forEach(item->verifyAttribution(item,tenant,integration,groupId,session,event,manager,agent,job));
            } finally { script.release(); }
            processing.get(35,TimeUnit.SECONDS);
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(()->{
            JobReceipt job=job(tenant,event);assertThat(job.status()).isEqualTo("SKIPPED");
            assertThat(job.modelStatus()).isEqualTo("COMPLETED");
            assertThat(job.errorCode()).isEqualTo("memory_extraction_empty");
        });
        JobReceipt job=job(tenant,event);
        var measured=usage(credential,groupId);
        assertThat(measured.items()).anySatisfy(item->{
            assertThat(item.kind()).isEqualTo("measured");assertThat(item.promptTokens()).isEqualTo(13);
            assertThat(item.completionTokens()).isEqualTo(5);
        });
        measured.items().forEach(item->verifyAttribution(item,tenant,integration,groupId,session,event,manager,agent,job));
        assertThat(measured.items().stream().map(PlatformUsageContracts.Event::attemptId).distinct().toList()).hasSize(1);
        assertThat(script.calls()).isEqualTo(1);
        long count=database.queryForObject("SELECT count(*) FROM nw_usage_events WHERE tenant_id=?::bigint AND system_agent_run_id=?::bigint",Long.class,tenant,job.systemRunId());
        for(int index=0;index<3;index++)worker.poll();
        assertThat(script.calls()).isEqualTo(1);
        assertThat(database.queryForObject("SELECT count(*) FROM nw_usage_events WHERE tenant_id=?::bigint AND system_agent_run_id=?::bigint",Long.class,tenant,job.systemRunId())).isEqualTo(count);
        assertThat(usage(credential,groupId).items().stream().map(PlatformUsageContracts.Event::eventId).toList())
                .containsExactlyInAnyOrderElementsOf(measured.items().stream().map(PlatformUsageContracts.Event::eventId).toList());
        return new Result(groupId,session,event,agent,job.systemRunId(),job.configurationRef(),job.invocationRef(),script.calls(),count,
                initialUsage.get(),measured,null);
    }

    private PlatformUsageContracts.Page usage(JsonNode credential,String group) throws Exception {
        return json.treeToValue(machine.call(credential,"GET","/api/v1/open-platform/usage/events?group_binding_id="+group+"&limit=100",null,null,200),PlatformUsageContracts.Page.class);
    }
    /** 当前用途迁移仅建草稿；组合夹具须像平台管理员一样显式选择模型并发布。 */
    static SystemAgentTypes.View publishPurpose(ObjectMapper json,ManagedApi platform,String id,String model) throws Exception {
        SystemAgentTypes.View extractor=json.treeToValue(platform.call("GET","/api/v1/admin/system-agents/detail?agentId="+id,null,null,200),SystemAgentTypes.View.class);
        assertThat(extractor.executionMode()).isEqualTo("MODEL_TASK");
        var config=extractor.draft();
        SystemAgentTypes.View updated=json.treeToValue(platform.call("PUT","/api/v1/admin/system-agents/update",
                new SystemUpdate(extractor.id(),extractor.revision(),new SystemAgentTypes.Config(config.name(),config.description(),
                        config.systemPrompt(),model,30,1,List.of())),null,200),SystemAgentTypes.View.class);
        return json.treeToValue(platform.call("POST","/api/v1/admin/system-agents/publish",
                new SystemChange(updated.id(),updated.revision()),null,200),SystemAgentTypes.View.class);
    }
    private JobReceipt job(String tenant,String event) {
        return database.queryForObject("SELECT status,model_status,system_run_id,configuration_ref,invocation_ref,error_code FROM agent_runtime_memory_extraction_jobs WHERE tenant_id=? AND source_event_id=?",
                (row,index)->new JobReceipt(row.getString(1),row.getString(2),row.getString(3),row.getString(4),row.getString(5),row.getString(6)),tenant,event);
    }
    private static void verifyAttribution(PlatformUsageContracts.Event item,String tenant,String integration,String group,String session,
            String event,String author,String agent,JobReceipt job) {
        assertThat(item.tenantId()).isEqualTo(tenant);assertThat(item.integrationId()).isEqualTo(integration);
        assertThat(item.groupBindingId()).isEqualTo(group);assertThat(item.originSessionId()).isEqualTo(session);
        assertThat(item.originEventId()).isEqualTo(event);assertThat(item.endUserId()).isEqualTo(author);
        assertThat(item.requesterKind()).isEqualTo("SYSTEM");assertThat(item.requesterRef()).isEqualTo("system:system-agent:"+SYSTEM_AGENT);
        assertThat(item.actorRef()).isEqualTo(item.requesterRef());assertThat(item.agentId()).isEqualTo(SYSTEM_AGENT);
        assertThat(item.responsibilityAgentId()).isEqualTo(agent);assertThat(item.responsibilityRef()).isEqualTo("agent:"+agent);
        assertThat(item.systemAgentRunId()).isEqualTo(job.systemRunId());assertThat(item.systemAgentConfigurationRef()).isEqualTo(job.configurationRef());
        assertThat(item.systemAgentInvocationRef()).isEqualTo(job.invocationRef());assertThat(item.sourceOwnerKind()).isEqualTo("platform");
    }

    @FunctionalInterface interface ManagedApi { JsonNode call(String method,String path,Object body,String key,int status) throws Exception; }
    record Result(String groupBindingId,String sessionId,String sourceEventId,String responsibilityAgentId,String systemAgentRunId,
            String configurationRef,String invocationRef,int providerCalls,long usageEvents,
            @JsonProperty("unknown_usage") PlatformUsageContracts.Page unknownUsage,
            @JsonProperty("usage") PlatformUsageContracts.Page usage,
            @JsonProperty("headquarters_usage") PlatformUsageContracts.Page headquartersUsage) {
        Result withHeadquarters(PlatformUsageContracts.Page page) {
            return new Result(groupBindingId,sessionId,sourceEventId,responsibilityAgentId,systemAgentRunId,configurationRef,
                    invocationRef,providerCalls,usageEvents,unknownUsage,usage,page);
        }
    }
    private record JobReceipt(String status,String modelStatus,String systemRunId,String configurationRef,String invocationRef,String errorCode) { }
    private record SystemUpdate(@JsonProperty("agent_id") String agent,@JsonProperty("expected_revision") long revision,SystemAgentTypes.Config config) { }
    private record SystemChange(@JsonProperty("agent_id") String agent,@JsonProperty("expected_revision") long revision) { }
    private record MemoryAgent(String agentKey,String name,String systemPrompt,String modelConfigId,AgentConfig config) { }
    private record AgentConfig(MemoryConfig memory) { }
    private record MemoryConfig(@JsonProperty("automatic_extraction") boolean automaticExtraction) { }
    private record AgentPublish(@JsonProperty("agent_id") String agent) { }
    private record EndUser(@JsonProperty("external_user_ref") String externalUserRef,@JsonProperty("display_name") String displayName) { }
}
