package com.skillforge.institution.workspace;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.execution.WorkspaceBinding;
import com.skillforge.execution.WorkspaceCommand;
import com.skillforge.execution.WorkspacePolicy;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** HTTP假Sandbox聚焦业务拒绝与清理确认，避免未知结果重放。 */
class WorkspaceSandboxClientTest {
    @Test void revisionConflictIsCorrectableRejectionAndIsNotRetried()throws Exception {
        var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/workspaces/operate",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            byte[] bytes="{\"code\":\"workspace_revision_conflict\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(409,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var context=context();
            var command=new WorkspaceCommand("b".repeat(64),new WorkspaceBinding("a".repeat(64),"1","session",9,"session",
                    new WorkspacePolicy(1,300,24,10)),"write","a.txt",null,"hello",0L,null,null,null,null,context.deadlineAt());
            var client=new WorkspaceSandboxClient(new ObjectMapper().findAndRegisterModules(),"http://127.0.0.1:"+server.getAddress().getPort(),"synthetic-test-token-01234567890123456789");
            var result=client.operate(command,context);
            assertEquals(false,result.get("success"));assertEquals("workspace_revision_conflict",result.get("error"));assertEquals(1,calls.get());
        }finally{server.stop(0);}
    }

    @Test void pendingCloudDeletionDoesNotCompleteHostCleanup()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/workspaces/delete",exchange->{
            byte[] bytes="{\"success\":false,\"phase\":\"deleting\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var client=new WorkspaceSandboxClient(new ObjectMapper().findAndRegisterModules(),"http://127.0.0.1:"+server.getAddress().getPort(),"synthetic-test-token-01234567890123456789");
            assertFalse(client.delete("a".repeat(64)));
        }finally{server.stop(0);}
    }

    @Test void missingInitializedStateRemainsExplicitAndNeverRequestsCreation()throws Exception {
        var posted=new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var json=new ObjectMapper().findAndRegisterModules();
        server.createContext("/internal/workspaces/operate",exchange->{
            posted.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] bytes="{\"code\":\"WORKSPACE_STATE_MISSING\",\"message\":\"工作区本地状态缺失，禁止空建\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(409,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var context=context();
            var binding=new WorkspaceBinding("a".repeat(64),"1","session",9,"session",new WorkspacePolicy(1,300,24,10),false);
            var command=new WorkspaceCommand("b".repeat(64),binding,"read","a.txt",null,null,null,null,null,null,null,context.deadlineAt());
            var client=new WorkspaceSandboxClient(json,"http://127.0.0.1:"+server.getAddress().getPort(),"synthetic-test-token-01234567890123456789");
            var result=client.operate(command,context);
            assertEquals(false,result.get("success"));assertEquals("WORKSPACE_STATE_MISSING",result.get("error"));
            assertFalse(posted.get().path("workspace").path("initializeAllowed").asBoolean(true));
        }finally{server.stop(0);}
    }

    /** 测试用可信Context不包含真实会话、用户或凭据。 */
    private static PlatformToolExecutionContext context() {
        var tenant=new TenantId("1");var input=new InputId("input");
        var owner=SessionOwner.user(new SessionRef(tenant,"session"),new AgentId("7"),new EndUserId(9));
        var run=new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant,"run"), new TraceEnvelope("trace","span",input,Optional.empty()), new ResultRef("result"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var identity=new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant,owner.requireUser()),owner,Set.of(),1,new ResultRef("verification"));
        return new PlatformToolExecutionContext(new PortCallContext(run,identity,new InvocationControl(Instant.now().plusSeconds(10),()->false)),"invocation","attempt");
    }
}
