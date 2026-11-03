package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实浏览器任务操作与同会话 Runtime 的隔离验收。 */
class ConversationTaskRecoveryBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final int ADMIN_PORT=availablePort();
    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins",()->"http://127.0.0.1:"+ADMIN_PORT);
    }
    @Autowired private ConversationTaskPort tasks;
    @Autowired private SessionLogPort log;

    @Test void realTaskOperationResponseLossUsesOriginalReceipt() throws Exception {
        postSse(chatPayload("请简述任务处理流程"));
        assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=? AND agent_id=? AND status='COMPLETED' AND phase='FINISHED'",tenantId,sessionId,agentId));
        assertTrue(countRuntime("SELECT count(*) FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_runs r ON r.tenant_id=a.tenant_id AND r.run_id=a.run_id WHERE r.tenant_id=? AND r.session_id=? AND a.kind='MODEL' AND a.status='SUCCEEDED'",tenantId,sessionId)>0);
        var owner=SessionOwner.user(new SessionRef(new TenantId(tenantId),sessionId),new AgentId(agentId),new EndUserId(endUserId));
        var now=Instant.now(); var input=new InputId("task-browser-source"); var sourceId=new EventId("task-browser-source");
        var source=new SessionEventDraft(sourceId,"inbound.message","1","task-browser-source",new TraceEnvelope("task-browser","task-browser",input,Optional.empty()),new TypedPayload("user-message.v1","{}"),now);
        var target=new SessionProjectionTarget(owner,input,Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty());
        TenantContext.runWithTenant(tenantId,()->assertInstanceOf(SessionLogPort.AppendResult.Appended.class,log.append(new SessionLogPort.AppendSessionEvent(target,source))));
        var task=new ConversationTask("task-browser",owner.sessionRef().tenantId(),owner.requireUser(),owner.target().requireAgent(),1,ConversationTask.Status.OPEN,"核对资料","等待资料",new TypedPayload(ConversationTask.STATE_SCHEMA,"{}"),List.of(new ConversationTask.Source(owner,sourceId)),List.of(),now);
        var event=new SessionEventDraft(new EventId("task-browser-created"),"agent.task-updated","1","task-browser-created",new TraceEnvelope("task-browser","task-browser",input,Optional.empty()),new TypedPayload("skillforge.conversation-task.snapshot.v2",com.skillforge.institution.runtime.SessionSubjectJson.configure(objectMapper.copy()).setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.LOWER_CAMEL_CASE).writeValueAsString(task)),now);
        var append=new SessionLogPort.AppendSessionEvent(new SessionProjectionTarget(owner,input,Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty()),event);
        TenantContext.runWithTenant(tenantId,()->assertInstanceOf(ConversationTaskPort.Result.Committed.class,tasks.commit(new ConversationTaskPort.Change(owner,task,0,append,false,Optional.empty()))));
        Path admin=repoRoot().resolve("frontend-admin"),cli=admin.resolve("node_modules/@playwright/test/cli.js");
        assertTrue(Files.isRegularFile(cli));
        Path evidence=Path.of("build/runtime-e2e/task-recovery-browser-"+SnowflakeIds.nextId()).toAbsolutePath();Files.createDirectories(evidence);
        var command=new ProcessBuilder(nodeExecutable(),cli.toString(),"test","--config=playwright.conversation-task-recovery.config.ts");
        command.directory(admin.toFile());command.redirectErrorStream(true);command.redirectOutput(evidence.resolve("playwright-vite.log").toFile());
        var env=command.environment();env.remove("E2E_BASE_URL");env.put("E2E_FRONTEND_PORT",Integer.toString(ADMIN_PORT));env.put("E2E_BACKEND_URL","http://127.0.0.1:"+port);
        env.put("E2E_TENANT_SLUG",fixtureTenantSlug());env.put("E2E_USERNAME","runtime-e2e-admin");env.put("E2E_PASSWORD",PASSWORD);env.put("E2E_SESSION_ID",sessionId);env.put("E2E_ARTIFACT_DIR",evidence.toString());
        Process process=command.start();
        try(var owned=new OwnedBrowserProcesses(process.toHandle())) {
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);boolean finished=false;
            while(System.nanoTime()<deadline){owned.capture();if(process.waitFor(200,TimeUnit.MILLISECONDS)){finished=true;break;}}
            owned.capture();assertTrue(finished,"浏览器旅程超出5分钟；证据="+evidence);
            String output=Files.readString(evidence.resolve("playwright-vite.log"),StandardCharsets.UTF_8);
            assertEquals(0,process.exitValue(),"浏览器失败；证据="+evidence+"\n"+output.substring(Math.max(0,output.length()-7000)));
        }
        var receipt=objectMapper.readTree(Files.readString(evidence.resolve("browser-receipt.json"),StandardCharsets.UTF_8));
        assertEquals(sessionId,receipt.path("sessionId").asText());assertEquals(1,receipt.path("updatePosts").asInt());
        assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_conversation_tasks WHERE tenant_id=? AND task_id=? AND revision=2",tenantId,"task-browser"));
        Files.writeString(evidence.resolve("database-evidence.json"),objectMapper.writeValueAsString(Map.of("sessionId",sessionId,"taskId","task-browser","revision",2,"modelRunCount",1)),StandardCharsets.UTF_8);
    }
    private static String nodeExecutable(){String configured=System.getenv("NODE_BINARY");return configured==null||configured.isBlank()?"node":configured;}
    private static Path repoRoot(){Path path=Path.of("").toAbsolutePath();while(path!=null){if(Files.isRegularFile(path.resolve("frontend-admin/package.json")))return path;path=path.getParent();}throw new IllegalStateException("repository_root_missing");}
    private static int availablePort(){try(var socket=new ServerSocket(0)){return socket.getLocalPort();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
    /** 只保存本次直接创建的进程与其已确认后代，绝不按名称或全局 PID 扩大清理。 */
    private static final class OwnedBrowserProcesses implements AutoCloseable {
        private final Set<ProcessHandle> owned=new LinkedHashSet<>();
        private final Set<Long> snapshotWarnings=new HashSet<>();
        OwnedBrowserProcesses(ProcessHandle root){owned.add(root);}
        void capture(){
            for(var handle:List.copyOf(owned)) {
                if(!handle.isAlive())continue;
                try(var descendants=handle.descendants()){descendants.forEach(owned::add);}
                catch(RuntimeException failure){
                    // Windows 的进程退出竞态可能让 Toolhelp 快照暂不可用；下一次轮询重读。
                    if(!"snapshot not available".equals(failure.getMessage()))throw failure;
                    if(snapshotWarnings.add(handle.pid()))System.err.println("Owned browser process snapshot unavailable; pid="+handle.pid()+"; subsequent polling retries capture");
                }
            }
        }
        @Override public void close(){
            var failures=new ArrayList<RuntimeException>();captureForCleanup(failures);
            destroy(false,failures);awaitExit(5,failures);destroy(true,failures);awaitExit(10,failures);
            var alive=owned.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList();
            if(!alive.isEmpty()||!failures.isEmpty()) {
                var failure=new AssertionError("验收自有子树清理未完成；仍存活PID="+alive);
                failures.forEach(failure::addSuppressed);throw failure;
            }
        }
        private void destroy(boolean force,List<RuntimeException> failures){
            for(var handle:List.copyOf(owned).reversed())if(handle.isAlive())try {
                if(force)handle.destroyForcibly();else handle.destroy();
            }catch(RuntimeException failure){failures.add(failure);}
        }
        private void captureForCleanup(List<RuntimeException> failures){
            try{capture();}catch(RuntimeException failure){
                if(failures.stream().noneMatch(previous->previous.getClass()==failure.getClass()
                        &&Objects.equals(previous.getMessage(),failure.getMessage())))failures.add(failure);
            }
        }
        private void awaitExit(int seconds,List<RuntimeException> failures){
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
            while(owned.stream().anyMatch(ProcessHandle::isAlive)&&System.nanoTime()<end){captureForCleanup(failures);LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));}
        }
    }
}
