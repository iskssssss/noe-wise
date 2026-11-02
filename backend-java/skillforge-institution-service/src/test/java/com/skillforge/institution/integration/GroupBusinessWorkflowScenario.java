package com.skillforge.institution.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.skillforge.core.chat.contract.ChatContent;
import com.skillforge.core.billing.export.PlatformUsageContracts;
import com.skillforge.institution.group.GroupContracts;
import com.skillforge.institution.group.GroupControlContracts;
import com.skillforge.institution.group.GroupMessageContracts;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;

/** 群正式消息、控制、HITL、真实 MCP 与外部业务回执的组合消费者；不制造执行或发布事实。 */
final class GroupBusinessWorkflowScenario {
    private static final String GROUPS="/api/v1/open-platform/groups";
    private static final String APPOINTMENT_CREATE="appointment_create",BUSINESS_RECEIPT="business_receipt";
    private static final String WRITE_SCOPE="business_write";
    private final ObjectMapper json;
    private final Machine machine;
    private final Business business;
    private final JdbcTemplate database;
    private final ControlledBusinessMcpProxy proxy;

    GroupBusinessWorkflowScenario(ObjectMapper json,Machine machine,Business business,JdbcTemplate database,
            ControlledBusinessMcpProxy proxy) {
        this.json=json;this.machine=machine;this.business=business;this.database=database;this.proxy=proxy;
    }

    /** 只由已授权真实 API 生成群和角色，业务工具仍使用原门店 Connector 凭据。 */
    Result verify(JsonNode credential,String tenant,String integration,String agent,String user,String scope,String marker,String time,
            BusinessWorkflowModelProvider provider) throws Exception {
        JsonNode manager=machine.call(credential,"POST","/api/v1/open-platform/end-users/ensure",
                new EndUser(marker+"-manager","业务接管管理员"),marker+":manager",200);
        JsonNode observer=machine.call(credential,"POST","/api/v1/open-platform/end-users/ensure",
                new EndUser(marker+"-observer","普通业务观察成员"),marker+":observer",200);
        JsonNode group=machine.call(credential,"POST",GROUPS+"/ensure",new GroupContracts.Ensure("business-group",marker,"life",scope),marker+":group",200);
        String groupId=group.path("group_binding_id").asText(),session=group.path("session_id").asText();
        member(credential,groupId,user,"member",marker+":requester");
        member(credential,groupId,manager.path("end_user_id").asText(),"manager",marker+":manager-join");
        member(credential,groupId,observer.path("end_user_id").asText(),"member",marker+":observer-join");
        machine.call(credential,"POST",GROUPS+"/agents/update",new GroupContracts.UpdateAgent(groupId,Long.valueOf(agent),"active",0L,marker+":agent",1L),marker+":agent",200);

        String requestId=marker+"-controlled";
        var arguments=arguments(new Appointment(requestId,"patient-A",time,time));
        var script=provider.register(marker+"-controlled",List.of(arguments),List.of("created"),"群预约已确认创建");
        script.blockResponse(1);
        JsonNode source=source(credential,groupId,user,marker+":source",script.prompt());
        String run=trigger(credential,groupId,source,user,agent,marker+":trigger");
        assertThat(script.awaitBlocked(Duration.ofSeconds(20))).as("模型响应屏障必须早于业务工具派发").isTrue();
        try {
            control(credential,groupId,run,marker+":pause-before",0,"paused");
            script.releaseResponse();
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(()->{
                assertThat(receipt(requestId).path("status").asText()).isEqualTo("not_found");
                assertThat(toolAttempts(tenant,run)).isZero();
            });
        } finally { script.releaseResponse(); }
        control(credential,groupId,run,marker+":resume-before",1,"running");
        JsonNode card=waiting(credential,groupId,run);
        assertThat(receipt(requestId).path("status").asText()).isEqualTo("pending_confirmation");
        // 接管只改变自动写范围；不让普通成员借暂停状态代替当前人工确认权限。
        control(credential,groupId,run,marker+":takeover",2,"paused");
        String denied=marker+":denied-confirm";
        respond(credential,groupId,run,observer.path("end_user_id").asText(),card,denied,403);
        assertThat(receipt(requestId).path("submitted").asBoolean()).isFalse();
        String approval=marker+":manager-confirm";
        JsonNode accepted=respond(credential,groupId,run,manager.path("end_user_id").asText(),card,approval,200);
        assertThat(receipt(requestId).path("submitted").asBoolean()).isFalse();
        var dispatched=proxy.continuation(requestId,false);
        control(credential,groupId,run,marker+":resume-submit",3,"running");
        assertThat(dispatched.awaitEntered()).as("已提交的真实工具续调必须到达外部传输").isTrue();
        try {
            // 派发先胜：暂停不谎称撤销原在途调用，原回执仍由原 Attempt 收束。
            control(credential,groupId,run,marker+":pause-inflight",4,"paused");
        } finally { dispatched.release(); }
        assertThat(dispatched.awaitCommitted()).isTrue();
        completed(credential,groupId,run);
        var provenance=database.queryForObject("SELECT session_id,subject_kind,subject_id,requester_kind,requester_end_user_id,origin_ref FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?",
                (row,index)->new RunProvenance(row.getString("session_id"),row.getString("subject_kind"),row.getString("subject_id"),
                        row.getString("requester_kind"),row.getLong("requester_end_user_id"),row.getString("origin_ref")),tenant,run);
        assertThat(provenance).isNotNull();
        assertThat(provenance.sessionId()).isEqualTo(session);
        assertThat(provenance.subjectKind()).isEqualTo("GROUP");
        assertThat(provenance.subjectId()).isEqualTo(groupId);
        assertThat(provenance.requesterKind()).isEqualTo("END_USER");
        assertThat(provenance.requesterEndUserId()).isEqualTo(Long.parseLong(user));
        assertThat(provenance.originRef()).startsWith("group-trigger:");
        JsonNode original=receipt(requestId);
        assertThat(original.path("submitted").asBoolean()).isTrue();
        assertThat(dispatched.calls()).isEqualTo(1);
        assertThat(dispatched.upstreamStatus()).isEqualTo(200);
        long completedAttempts=toolAttempts(tenant,run);
        var completedUsage=usage(credential,groupId,run);
        verifyUsage(completedUsage,tenant,integration,groupId,session,source.path("source_message_id").asText(),user,agent);
        var completedFacts=usageFacts(completedUsage);
        JsonNode replay=respond(credential,groupId,run,manager.path("end_user_id").asText(),card,approval,200);
        // 回复幂等复用原输入身份，公开回执中的status仍表达原Run当前态。
        for(String identity:List.of("group_binding_id","session_id","run_id","input_id"))
            assertThat(replay.path(identity)).as("重复确认保留%s",identity).isEqualTo(accepted.path(identity));
        assertThat(replay.path("group_binding_id").asText()).isEqualTo(groupId);
        assertThat(replay.path("session_id").asText()).isEqualTo(session);
        assertThat(replay.path("run_id").asText()).isEqualTo(run);
        assertThat(replay.path("input_id").asText()).isEqualTo(approval);
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        assertThat(replay.path("status").asText()).isEqualTo("completed");
        control(credential,groupId,run,marker+":resume-complete",5,"running");
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(()->{
            assertThat(receipt(requestId)).isEqualTo(original);
            assertThat(dispatched.calls()).isEqualTo(1);
            assertThat(toolAttempts(tenant,run)).isEqualTo(completedAttempts);
            assertThat(usageFacts(usage(credential,groupId,run))).containsExactlyInAnyOrderElementsOf(completedFacts);
        });
        assertThat(original.path("store_ref").asText()).isEqualTo("store-A");
        assertThat(original.path("operator_ref").asText()).isEqualTo("operator-A");
        assertThat(original.path("tenant_ref").asText()).isEqualTo(tenant);

        String unknownRequest=marker+"-unknown";
        var unknownScript=provider.register(marker+"-unknown",List.of(arguments(new Appointment(unknownRequest,"patient-A",time,time))),List.of("created"),"不得凭未知回执自动重试");
        JsonNode unknownSource=source(credential,groupId,user,marker+":unknown-source",unknownScript.prompt());
        String unknownRun=trigger(credential,groupId,unknownSource,user,agent,marker+":unknown-trigger");
        JsonNode unknownCard=waiting(credential,groupId,unknownRun);
        var uncertain=proxy.continuation(unknownRequest,true);
        respond(credential,groupId,unknownRun,user,unknownCard,marker+":unknown-confirm",200);
        assertThat(uncertain.awaitEntered()).isTrue();uncertain.release();
        assertThat(uncertain.awaitCommitted()).isTrue();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(()->assertThat(unknownAttempts(tenant,unknownRun)).isEqualTo(1));
        JsonNode unknownReceipt=receipt(unknownRequest);
        assertThat(unknownReceipt.path("submitted").asBoolean()).as("回执丢失前外部业务已经真实提交").isTrue();
        long originalAttempts=toolAttempts(tenant,unknownRun);
        var unknownUsage=usage(credential,groupId,unknownRun);
        verifyUsage(unknownUsage,tenant,integration,groupId,session,unknownSource.path("source_message_id").asText(),user,agent);
        var originalUsageFacts=usageFacts(unknownUsage);
        control(credential,groupId,unknownRun,marker+":unknown-pause",6,"paused");
        control(credential,groupId,unknownRun,marker+":unknown-resume",7,"running");
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6)).untilAsserted(()->{
            assertThat(uncertain.calls()).isEqualTo(1);
            assertThat(unknownScript.calls()).isEqualTo(1);
            assertThat(unknownAttempts(tenant,unknownRun)).isEqualTo(1);
            assertThat(toolAttempts(tenant,unknownRun)).isEqualTo(originalAttempts);
            assertThat(receipt(unknownRequest)).isEqualTo(unknownReceipt);
            assertThat(usageFacts(usage(credential,groupId,unknownRun))).containsExactlyInAnyOrderElementsOf(originalUsageFacts);
        });
        return new Result(groupId,session,run,unknownRun,requestId,unknownRequest,dispatched.calls(),uncertain.calls(),completedUsage,unknownUsage);
    }

    /** 普通群Run按原请求者与integration责任归属；独立SYSTEM字段不从人工确认者补造。 */
    private static void verifyUsage(PlatformUsageContracts.Page page,String tenant,String integration,String group,String session,
            String source,String requester,String agent) {
        assertThat(page.hasMore()).isFalse();
        assertThat(page.items()).anySatisfy(item->assertThat(item.eventType()).isEqualTo("llm.chat"));
        assertThat(page.items()).anySatisfy(item->assertThat(item.eventType()).isEqualTo("tool.call"));
        for(var item:page.items()) {
            assertThat(item.tenantId()).isEqualTo(tenant);assertThat(item.integrationId()).isEqualTo(integration);
            assertThat(item.groupBindingId()).isEqualTo(group);assertThat(item.requesterKind()).isEqualTo("END_USER");
            assertThat(item.requesterRef()).isEqualTo("end_user:"+requester);assertThat(item.originSessionId()).isEqualTo(session);
            assertThat(item.originEventId()).isEqualTo(source);assertThat(item.agentId()).isEqualTo(agent);
            assertThat(item.responsibilityRef()).isEqualTo("end_user:"+requester);assertThat(item.responsibilityAgentId()).isNull();
            assertThat(item.systemAgentRunId()).isNull();
            assertThat(item.systemAgentConfigurationRef()).isNull();assertThat(item.systemAgentInvocationRef()).isNull();
        }
    }
    private PlatformUsageContracts.Page usage(JsonNode credential,String group,String run) throws Exception {
        return json.treeToValue(machine.call(credential,"GET","/api/v1/open-platform/usage/events?group_binding_id="+group+"&run_id="+run+"&limit=100",null,null,200),PlatformUsageContracts.Page.class);
    }
    private static List<UsageFact> usageFacts(PlatformUsageContracts.Page page) {
        return page.items().stream().map(item->new UsageFact(item.eventId(),item.attemptId(),item.kind(),item.revision(),item.quantity(),
                item.tokens(),item.promptTokens(),item.completionTokens(),item.amountStatus(),item.amountCents(),item.currency())).toList();
    }

    private Map<String,Object> arguments(Appointment value) { return json.convertValue(value,new TypeReference<>() { }); }
    private void member(JsonNode credential,String group,String user,String role,String key) throws Exception {
        machine.call(credential,"POST",GROUPS+"/members/update",new GroupContracts.UpdateMember(group,Long.valueOf(user),role,"active",0L,key,1L),key,200);
    }
    private JsonNode source(JsonNode credential,String group,String user,String key,String text) throws Exception {
        return machine.call(credential,"POST",GROUPS+"/messages",new GroupMessageContracts.MessageRecord(group,key,1L,
                new GroupMessageContracts.EndUserAuthor(Long.valueOf(user)),ChatContent.text(text)),key,200);
    }
    private String trigger(JsonNode credential,String group,JsonNode source,String user,String agent,String key) throws Exception {
        var receipt=machine.call(credential,"POST",GROUPS+"/triggers",new GroupMessageContracts.Trigger(group,source.path("source_message_id").asText(),Long.valueOf(user),Long.valueOf(agent)),key,202);
        var accepted=new AtomicReference<JsonNode>();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(()->{
            JsonNode current=machine.call(credential,"GET",GROUPS+"/triggers/detail?group_binding_id="+group+"&trigger_id="+receipt.path("trigger_id").asText(),null,null,200);
            assertThat(current.path("status").asText()).isEqualTo("accepted");accepted.set(current);
        });
        return accepted.get().path("run_id").asText();
    }
    private void control(JsonNode credential,String group,String run,String key,long revision,String state) throws Exception {
        machine.call(credential,"POST",GROUPS+"/controls",new GroupControlContracts.Command(key,group,run,revision,state,List.of(WRITE_SCOPE)),key,202);
    }
    private JsonNode detail(JsonNode credential,String group,String run) throws Exception {
        return machine.call(credential,"GET",GROUPS+"/runs/detail?group_binding_id="+group+"&run_id="+run,null,null,200);
    }
    private JsonNode waiting(JsonNode credential,String group,String run) {
        var result=new AtomicReference<JsonNode>();
        await().atMost(Duration.ofSeconds(60)).untilAsserted(()->{
            JsonNode current=detail(credential,group,run);assertThat(current.path("pending_interactions")).hasSize(1);
            result.set(current.path("pending_interactions").get(0));
        });return result.get();
    }
    private void completed(JsonNode credential,String group,String run) {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(()->assertThat(detail(credential,group,run).path("status").asText()).isEqualTo("completed"));
    }
    private JsonNode respond(JsonNode credential,String group,String run,String user,JsonNode card,String input,int expected) throws Exception {
        var reply=new Reply(run,card.path("run_revision").asText(),new ToolInput(new Wait(card.path("tool_wait").path("action_id").asText(),
                card.path("tool_wait").path("generation").asText()),Map.of("confirm_business",new ToolResponse("elicitation/create",new Payload("application/json","{\"action\":\"accept\",\"content\":{\"confirmed\":true}}")))));
        return machine.call(credential,"POST",GROUPS+"/runs/respond",new Respond(group,run,input,user,reply),input,expected);
    }
    private JsonNode receipt(String request) throws Exception { return business.call(BUSINESS_RECEIPT,Map.of("request_id",request),null,null,200).path("result").path("structuredContent"); }
    private long toolAttempts(String tenant,String run) { return database.queryForObject("SELECT count(*) FROM agent_runtime_execution_attempts t JOIN agent_runtime_execution_actions a USING(tenant_id,action_id,run_id) WHERE t.tenant_id=? AND t.run_id=? AND a.kind='TOOL'",Long.class,tenant,run); }
    private long unknownAttempts(String tenant,String run) { return database.queryForObject("SELECT count(*) FROM agent_runtime_execution_attempts t JOIN agent_runtime_execution_actions a USING(tenant_id,action_id,run_id) WHERE t.tenant_id=? AND t.run_id=? AND a.kind='TOOL' AND t.result_kind='UNKNOWN'",Long.class,tenant,run); }

    @FunctionalInterface interface Machine { JsonNode call(JsonNode credential,String method,String path,Object body,String key,int expected) throws Exception; }
    @FunctionalInterface interface Business { JsonNode call(String tool,Map<String,Object> arguments,String state,Map<String,Object> responses,int expected) throws Exception; }
    record Result(String groupBindingId,String sessionId,String completedRunId,String unknownRunId,String completedRequestId,String unknownRequestId,int completedContinuationCalls,int unknownContinuationCalls,
            @JsonProperty("usage") PlatformUsageContracts.Page usage,@JsonProperty("unknown_usage") PlatformUsageContracts.Page unknownUsage) { }
    private record EndUser(@JsonProperty("external_user_ref") String externalUserRef,@JsonProperty("display_name") String displayName) { }
    private record Appointment(@JsonProperty("request_id") String requestId,@JsonProperty("patient_ref") String patientRef,
            @JsonProperty("patient_time") String patientTime,@JsonProperty("appointment_time") String appointmentTime) { }
    private record Respond(@JsonProperty("group_binding_id") String group,@JsonProperty("run_id") String run,@JsonProperty("input_id") String input,
            @JsonProperty("responder_end_user_id") String user,@JsonProperty("hitl_response") Reply reply) { }
    private record Reply(@JsonProperty("execution_id") String run,@JsonProperty("run_revision") String revision,@JsonProperty("tool_input") ToolInput toolInput) { }
    private record ToolInput(@JsonProperty("wait") Wait waiting,Map<String,ToolResponse> responses) { }
    private record Wait(@JsonProperty("action_id") String actionId,String generation) { }
    private record ToolResponse(String method,Payload result) { }
    private record Payload(@JsonProperty("schema_id") String schema,@JsonProperty("canonical_json") String json) { }
    private record RunProvenance(String sessionId,String subjectKind,String subjectId,String requesterKind,long requesterEndUserId,String originRef) { }
    private record UsageFact(String eventId,String attemptId,String kind,long revision,Integer quantity,Long tokens,Long promptTokens,
            Long completionTokens,String amountStatus,String amountCents,String currency) { }
}
