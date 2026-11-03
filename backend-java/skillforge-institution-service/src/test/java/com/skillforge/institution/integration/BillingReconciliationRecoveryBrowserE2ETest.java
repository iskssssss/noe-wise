package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.reconciliation.TenantUsageReconciliationConfigService;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 隔离计费配置浏览器旅程和同租户真实 Runtime。 */
class BillingReconciliationRecoveryBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final int ADMIN_PORT=availablePort();
    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins",()->"http://127.0.0.1:"+ADMIN_PORT);
    }
    @Autowired private TenantUsageReconciliationConfigService reconciliation;

    @Test void committedConfigurationWithLostResponseCannotBeAttributed() throws Exception {
        postSse(chatPayload("请简述计费用量记录"));
        assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=? AND agent_id=? AND status='COMPLETED' AND phase='FINISHED'",tenantId,sessionId,agentId));
        awaitRuntimeCondition("same session measured usage", () -> countRuntime("SELECT count(*) FROM nw_usage_events u JOIN agent_runtime_execution_runs r ON r.tenant_id=u.tenant_id::text AND r.run_id=u.run_id WHERE r.tenant_id=? AND r.session_id=? AND u.usage_event_kind='measured'",tenantId,sessionId)>0);
        String secret="isolated-"+UUID.randomUUID();
        Path admin=repoRoot().resolve("frontend-admin"),cli=admin.resolve("node_modules/@playwright/test/cli.js");
        assertTrue(Files.isRegularFile(cli));
        Path evidence=Path.of("build/runtime-e2e/billing-reconciliation-browser-"+SnowflakeIds.nextId()).toAbsolutePath();Files.createDirectories(evidence);
        var command=new ProcessBuilder(nodeExecutable(),cli.toString(),"test","--config=playwright.billing-reconciliation-recovery.config.ts");
        command.directory(admin.toFile());command.redirectErrorStream(true);command.redirectOutput(evidence.resolve("playwright-vite.log").toFile());
        var env=command.environment();env.remove("E2E_BASE_URL");env.remove("SDK4_LIVE_MODEL_KEY");env.remove("E2E_LIVE_MODEL");
        env.put("E2E_FRONTEND_PORT",Integer.toString(ADMIN_PORT));env.put("E2E_BACKEND_URL","http://127.0.0.1:"+port);
        env.put("E2E_TENANT_SLUG",fixtureTenantSlug());env.put("E2E_PASSWORD",PASSWORD);env.put("E2E_SYNTHETIC_SECRET",secret);env.put("E2E_ARTIFACT_DIR",evidence.toString());
        Process process=command.start();
        try(var owned=new OwnedBrowserProcesses(process.toHandle())) {
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);boolean finished=false;
            while(System.nanoTime()<deadline){owned.capture();if(process.waitFor(200,TimeUnit.MILLISECONDS)){finished=true;break;}}
            owned.capture();assertTrue(finished,"浏览器旅程超出5分钟；证据="+evidence);
            String output=Files.readString(evidence.resolve("playwright-vite.log"),StandardCharsets.UTF_8);
            assertFalse(output.contains(secret),"日志不得包含测试凭据");
            assertEquals(0,process.exitValue(),"浏览器失败；证据="+evidence+"\n"+output.substring(Math.max(0,output.length()-7000)));
        }
        var receipt=objectMapper.readTree(Files.readString(evidence.resolve("browser-receipt.json"),StandardCharsets.UTF_8));
        String mode=System.getenv().getOrDefault("E2E_BILLING_RECONCILIATION_MODE","candidate");
        assertEquals(mode,receipt.path("mode").asText());assertEquals(1,receipt.path("writes").asInt());
        assertEquals("baseline".equals(mode)?500:200,receipt.path("upstreamStatus").asInt());
        assertTrue(receipt.path("unchangedAfterUnsupported").asBoolean());
        var saved=TenantContext.callWithTenant(tenantId,()->reconciliation.resolveForProvider(tenantId));
        boolean savedSecretMatches=secret.equals(saved.apiKey());
        assertEquals(!"baseline".equals(mode),savedSecretMatches,"凭据保存状态不符合模式");
        if("baseline".equals(mode))assertNull(saved.provider());else assertEquals("http",saved.provider());
        Files.writeString(evidence.resolve("database-evidence.json"),objectMapper.writeValueAsString(Map.of("sessionId",sessionId,"modelRunCount",1,"savedSecretMatches",savedSecretMatches,"mode",mode)),StandardCharsets.UTF_8);
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
