package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.skillforge.institution.service.AgentManagementService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 恢复原请求与草稿挂载的同事务、并发去重和只读核对。 */
class AgentRevisionRestoreReceiptHttpIntegrationTest extends ChatExecutionChainE2ETest {
    private static final String PATH="/api/v1/agents/restore-revision";
    @MockitoSpyBean AgentManagementService restoreAgents;

    @Test void sameHttpIdempotencyHeaderReachesOriginalReceiptWithoutRepeatingRestore() throws Exception {
        login();
        String identity=UUID.randomUUID().toString();
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(baseUrl()+PATH))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Content-Type","application/json")
                .header("Authorization","Bearer "+accessToken)
                .header("Idempotency-Key",identity)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                        Map.of("request_id",identity,"agent_id",agentId,"revision",1)))).build();
        clearInvocations(restoreAgents);
        var first=httpClient.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,first.statusCode(),first.body());
        var after=successData(get("/api/v1/agents/capability-mounts?agentId="+agentId));
        var replay=httpClient.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,replay.statusCode(),replay.body());
        assertEquals(successData(first),successData(replay));
        assertEquals(after,successData(get("/api/v1/agents/capability-mounts?agentId="+agentId)));
        verify(restoreAgents,times(1)).restoreRevision(agentId,1);
    }

    @Test void concurrentOriginalRequestRestoresOnlyOnceAndReadDoesNotChangeMounts() throws Exception {
        login();
        String request=UUID.randomUUID().toString();
        var command=Map.of("request_id",request,"agent_id",agentId,"revision",1);
        clearInvocations(restoreAgents);
        assertEquals(204,get(PATH+"/result?agentId="+agentId+"&requestId="+request).statusCode());
        var first=CompletableFuture.supplyAsync(()->postRestore(command));
        var second=CompletableFuture.supplyAsync(()->postRestore(command));
        assertEquals(first.get(),second.get());
        verify(restoreAgents,times(1)).restoreRevision(agentId,1);
        var mounts=successData(get("/api/v1/agents/capability-mounts?agentId="+agentId));
        var receipt=get(PATH+"/result?agentId="+agentId+"&requestId="+request);
        assertEquals(200,receipt.statusCode(),receipt.body());
        assertEquals(1,successData(receipt).path("revision").asInt());
        assertEquals(agentId,successData(receipt).path("agent_id").asText());
        assertEquals(successData(receipt),postRestore(command));
        assertEquals(mounts,successData(get("/api/v1/agents/capability-mounts?agentId="+agentId)));
        verify(restoreAgents,times(1)).restoreRevision(agentId,1);
        assertEquals(1,countRuntime("SELECT count(*) FROM nw_agent_revision_restore_receipts WHERE tenant_id=CAST(? AS bigint) AND request_id=CAST(? AS uuid)",tenantId,request));
        var conflict=send(PATH,"POST",Map.of("request_id",request,"agent_id",agentId,"revision",2),accessToken);
        assertEquals(409,conflict.statusCode(),conflict.body());
        assertEquals("AGENT_RESTORE_IDENTITY_CONFLICT",objectMapper.readTree(conflict.body()).path("code").asText());
    }

    @Test void failureAfterDraftAndMountRestoreRollsBackOriginalReceipt() throws Exception {
        login();
        var before=successData(get("/api/v1/agents/capability-mounts?agentId="+agentId));
        String request=UUID.randomUUID().toString();
        doAnswer(invocation->{ invocation.callRealMethod(); throw new IllegalStateException("restore_receipt_rollback_fixture"); })
                .when(restoreAgents).restoreRevision(agentId,1);
        try {
            var response=send(PATH,"POST",Map.of("request_id",request,"agent_id",agentId,"revision",1),accessToken);
            assertEquals(500,response.statusCode(),response.body());
            assertEquals(0,countRuntime("SELECT count(*) FROM nw_agent_revision_restore_receipts WHERE tenant_id=CAST(? AS bigint) AND request_id=CAST(? AS uuid)",tenantId,request));
            assertEquals(before,successData(get("/api/v1/agents/capability-mounts?agentId="+agentId)));
            assertEquals(204,get(PATH+"/result?agentId="+agentId+"&requestId="+request).statusCode());
        } finally {
            doCallRealMethod().when(restoreAgents).restoreRevision(agentId,1);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode postRestore(Map<String,?> command) {
        try {
            var response=send(PATH,"POST",new java.util.LinkedHashMap<String,Object>(command),accessToken);
            assertEquals(200,response.statusCode(),response.body());
            return successData(response);
        } catch(Exception failure) { throw new RuntimeException(failure); }
    }
}
