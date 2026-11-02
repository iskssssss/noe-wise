package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.common.context.TenantContext;
import com.skillforge.institution.runtime.AgentRuntimeExecutionWorker;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.annotation.DirtiesContext;

/** 真实HTTP/PG/Provider执行进程的OS强杀与重启；单独启用，不将组件重建视为进程恢复。 */
@Tag("runtime-process-restart")
@Tag("runtime-latency")
@EnabledIfEnvironmentVariable(named = "SKILLFORGE_RUNTIME_PROCESS_RESTART", matches = "1")
@EnabledIfEnvironmentVariable(named = "SKILLFORGE_S03_EXTERNAL_JDBC_URL", matches = ".+")
// 每个用例会永久关闭父Host并停用其调度；子进程收尾后销毁上下文，不能让下一用例复用已关闭Bean。
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RuntimeExecutionProcessRestartE2ETest extends ChatExecutionChainE2ETest {
    private final List<Child> children = new ArrayList<>();
    private Path evidence;

    /** 两种边界各使用新会话、新脚本和两个不同子PID；父上下文只建fixture和读取事实。 */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void realJvmKillRecoversSavedResultAndQuiescesUncertainDispatchWithoutResending() throws Exception {
        assertEquals(21, Runtime.version().feature(), "验收必须使用项目Java21运行时");
        String suppliedEvidence = System.getenv("SKILLFORGE_RUNTIME_PROCESS_RESTART_EVIDENCE");
        Path reports = Files.createDirectories((suppliedEvidence == null
                ? Path.of("build", "reports") : Path.of(suppliedEvidence)).toAbsolutePath());
        evidence = Files.createTempDirectory(reports, "runtime-execution-process-restart-");
        var report = new LinkedHashMap<String, Object>();
        var scenarios = new ArrayList<Map<String, Object>>();
        report.put("outcome", "FAILED"); report.put("parentPid", ProcessHandle.current().pid());
        report.put("javaVersion", System.getProperty("java.version")); report.put("scenarios", scenarios);
        report.put("scope", "OS destroyForcibly + distinct JVM; real HTTP, PostgreSQL transactions and production OpenAI adapter");
        try {
            // 父进程不能领取任何场景Run；关闭实际Worker并在其close返回后才启动子进程。
            applicationContext.getBean(AgentRuntimeExecutionWorker.class).close();
            login(); mountGenericTemplateTool();
            savedResult(scenarios);
            unknownResult(scenarios);
            report.put("outcome", "PASS");
        } catch (Exception | AssertionError failure) {
            report.put("failureType", failure.getClass().getName());
            throw failure;
        } finally {
            boolean reaped = true;
            for (var child : children) {
                try { child.close(); } catch (Exception failure) { reaped = false; }
            }
            report.put("allOwnedChildrenReaped", reaped);
            report.put("processes", children.stream().map(Child::facts).toList());
            if (!reaped) report.put("outcome", "FAILED");
            write(evidence.resolve("report.json"), report);
            System.out.println("RUNTIME_EXECUTION_PROCESS_RESTART_REPORT " + evidence.resolve("report.json"));
            assertTrue(reaped, "本次创建的所有子进程必须完成退出，证据位于 " + evidence);
        }
    }

    /** 原HUMAN等待跨真实A/B进程保留；一次准确回复推进，重复输入不产生第三个模型请求。 */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void realJvmRestartResumesPersistedHumanWaitExactlyOnce() throws Exception {
        waitingCase("human-wait", (directory, sample) -> {
            try (var provider = new RuntimeExecutionWorkloadProvider(objectMapper)) {
                provider.start(); configureModel(provider.endpoint());
                var script = provider.register("restart-human-" + id(), RuntimeExecutionWorkloadProvider.Workload.HUMAN_WAIT);
                runId = "restart-human-" + id(); String actualRun = "run:" + runId;
                var first = launch(directory,"first","resume",actualRun);
                String firstToken = login(first); createSession(first,firstToken); submit(first,firstToken,script.prompt(),actualRun);
                var originalWait = awaitWait(actualRun,"HUMAN");
                var original = persistedFacts(actualRun);
                var budgetBefore = budgetFacts(actualRun);
                var publicWaiting = replay(first,firstToken); var card = hitl(publicWaiting);
                assertEquals(script.question(),card.path("question").asText()); assertEquals(1,script.snapshot().requestCount());
                first.kill(); var budgetStopped = budgetFacts(actualRun); assertStableBudgetUnchanged(budgetBefore,budgetStopped);
                var second = launch(directory,"second","resume",actualRun);
                assertNotEquals(first.process.pid(),second.process.pid());
                assertEquals(originalWait,waitFact(actualRun,"HUMAN")); assertOriginalRequestsUnchanged(original,persistedFacts(actualRun));
                var budgetRestored = budgetFacts(actualRun); assertBudgetUnchangedBeforeResponse(budgetStopped,budgetRestored);
                assertEquals(1,script.snapshot().requestCount(),"B就绪不能重发原模型或伪造人类回答");
                String token = login(second); var restoredCard = hitl(replay(second,token));
                assertEquals(card,restoredCard,"原持久公开卡片在新JVM读取保持一致");
                String responseInput = "human-response-" + runId;
                var reply = chatPayload(script.humanReply()); reply.put("client_message_id",responseInput);
                reply.put("hitl_response",Map.of("execution_id",actualRun,"run_revision",restoredCard.path("run_revision").asText(),
                        "human_input",Map.of("wait",Map.of("action_id",restoredCard.path("tool_wait").path("action_id").asText(),
                                "generation",restoredCard.path("tool_wait").path("generation").asText()),"text",script.humanReply())));
                var accepted = request(second,"/api/v1/chat","POST",reply,token); assertEquals(202,accepted.statusCode(),accepted.body());
                assertFalse(successData(accepted).path("replayed").asBoolean());
                awaitCompleted(actualRun); var beforeReplay = persistedFacts(actualRun); var budgetCompleted = budgetFacts(actualRun);
                assertFinalBudget(budgetCompleted,Map.of(actualRun,new ExpectedBudgetCalls(2,0)));
                var duplicate = request(second,"/api/v1/chat","POST",reply,token); assertEquals(202,duplicate.statusCode(),duplicate.body());
                assertTrue(successData(duplicate).path("replayed").asBoolean()); assertEquals(beforeReplay,persistedFacts(actualRun));
                var budgetDuplicate = budgetFacts(actualRun); assertBudgetUnchangedAfterDuplicate(budgetCompleted,budgetDuplicate);
                assertEquals(2,script.snapshot().requestCount()); assertEquals(1,script.snapshot().verifiedHumanReplies());
                assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_waits WHERE tenant_id=? AND run_id=? AND action_id=? AND generation=? AND input_id=? AND consumed_revision>accepted_revision AND continuation_attempt IS NULL",
                        tenantId,actualRun,originalWait.get("action_id"),originalWait.get("generation"),responseInput));
                assertEquals(2,countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? AND adopted AND result_kind='SUCCEEDED'",tenantId,actualRun));
                assertCompletionOnce(second,token,actualRun,script.finalAnswer());
                sample.put("run",actualRun); sample.put("originalWait",originalWait); sample.put("beforeKill",original);
                sample.put("afterResume",persistedFacts(actualRun)); sample.put("provider",script.snapshot());
                sample.put("budgetBeforeKill",budgetBefore); sample.put("budgetAfterAExit",budgetStopped); sample.put("budgetRestored",budgetRestored); sample.put("budgetCompleted",budgetCompleted); sample.put("budgetAfterDuplicate",budgetDuplicate);
                sample.put("firstPid",first.process.pid()); sample.put("secondPid",second.process.pid()); second.close();
            }
        });
    }

    /** 生产Browser连接扫描只在B进程运行；准确设备选择恢复原TOOL/Attempt，原启动只真实发送一次。 */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void realJvmRestartResumesPersistedBrowserWaitExactlyOnce() throws Exception {
        waitingCase("browser-wait", (directory, sample) -> {
            mountRestartTool("browser_start");
            try (var browser = new RuntimeExecutionBrowserServer(); var model = new BrowserConnectionIntegrationSupport.BrowserModel(objectMapper)) {
                configureModel(model.endpoint()); browser.devices(tenantId,endUserId.toString(),List.of(),null);
                runId = "restart-browser-" + id(); String actualRun = "run:" + runId;
                var external = Map.<String,Object>of("BROWSER_SERVICE_URL",browser.endpoint(),"BROWSER_SERVICE_TOKEN",RuntimeExecutionBrowserServer.TOKEN);
                var first = launch(directory,"first","resume",actualRun,external);
                String firstToken = login(first); createSession(first,firstToken); submit(first,firstToken,"启动个人浏览器，连接完成后回答。",actualRun);
                var originalWait = awaitWait(actualRun,"TOOL");
                await("A持久绑定准确Browser等待",Duration.ofSeconds(45),() -> browserConnection(actualRun).get("wait_snapshot") != null);
                var connection = browserConnection(actualRun); String requestId = connection.get("request_id").toString();
                assertEquals(originalWait.get("action_id"),connection.get("invocation_id"));
                assertEquals("WAITING",connection.get("status")); assertTrue(browser.executions(tenantId,endUserId.toString()).isEmpty());
                assertEquals(1,model.requests().size()); var original = persistedFacts(actualRun);
                var budgetBefore = budgetFacts(actualRun);
                first.kill(); var budgetStopped = budgetFacts(actualRun); assertStableBudgetUnchanged(budgetBefore,budgetStopped);
                var second = launch(directory,"second","resume",actualRun,external);
                assertNotEquals(first.process.pid(),second.process.pid());
                assertEquals(originalWait,waitFact(actualRun,"TOOL")); assertEquals(connection,browserConnection(actualRun));
                assertOriginalRequestsUnchanged(original,persistedFacts(actualRun)); assertEquals(1,model.requests().size());
                var budgetRestored = budgetFacts(actualRun); assertBudgetUnchangedBeforeResponse(budgetStopped,budgetRestored);
                assertTrue(browser.executions(tenantId,endUserId.toString()).isEmpty(),"B就绪不能重做初始browser_start");
                String token = login(second);
                var device = new com.skillforge.execution.browser.BrowserDevices.Device("restart-device","restart-browser","恢复浏览器","2099-01-01T00:00:00Z",true);
                browser.devices(tenantId,endUserId.toString(),List.of(device),null);
                var selected = request(second,"/api/v1/chat/browser/select","POST",Map.of("session_id",sessionId,"connection_request_id",requestId,"device_id",device.deviceId(),"make_default",false),token);
                assertEquals(200,selected.statusCode(),selected.body());
                // 后续仅观察PG；没有SSE、history或连接状态请求触发恢复。
                awaitCompleted(actualRun);
                await("B确认准确连接已恢复",Duration.ofSeconds(45),() -> "RESUMED".equals(browserConnection(actualRun).get("status")));
                var sends = browser.executions(tenantId,endUserId.toString()); assertEquals(1,sends.size());
                assertEquals("start",sends.getFirst().operation()); assertEquals(connection.get("invocation_id"),sends.getFirst().invocation());
                assertEquals(actualRun,sends.getFirst().run()); assertEquals(sessionId,sends.getFirst().session());
                assertEquals(2,model.requests().size()); assertTrue(browser.failures().isEmpty(),browser.failures().toString());
                assertEquals(1,countRuntime("SELECT count(*) FROM nw_browser_call_receipts WHERE tenant_id=?::bigint AND run_id=? AND invocation_id=? AND operation='start' AND status='succeeded'",tenantId,actualRun,connection.get("invocation_id")));
                assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_waits WHERE tenant_id=? AND run_id=? AND action_id=? AND generation=? AND input_id=? AND continuation_attempt IS NOT NULL",
                        tenantId,actualRun,originalWait.get("action_id"),originalWait.get("generation"),"browser-connection:" + requestId));
                assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? AND action_id=? AND continuation_generation=?",tenantId,actualRun,originalWait.get("action_id"),originalWait.get("generation")));
                var before = persistedFacts(actualRun); var budgetCompleted = budgetFacts(actualRun);
                assertFinalBudget(budgetCompleted,Map.of(actualRun,new ExpectedBudgetCalls(2,2)));
                var duplicate = request(second,"/api/v1/chat/browser/select","POST",Map.of("session_id",sessionId,"connection_request_id",requestId,"device_id",device.deviceId(),"make_default",false),token);
                assertEquals(200,duplicate.statusCode(),duplicate.body()); assertEquals(before,persistedFacts(actualRun));
                var budgetDuplicate = budgetFacts(actualRun); assertBudgetUnchangedAfterDuplicate(budgetCompleted,budgetDuplicate);
                assertEquals(1,browser.executions(tenantId,endUserId.toString()).size()); assertEquals(2,model.requests().size());
                assertCompletionOnce(second,token,actualRun,"浏览器等待处理完成");
                sample.put("run",actualRun); sample.put("originalWait",originalWait); sample.put("connectionBeforeKill",connection);
                sample.put("connectionAfterResume",browserConnection(actualRun)); sample.put("beforeKill",original); sample.put("afterResume",persistedFacts(actualRun));
                sample.put("browserRequests",sends); sample.put("model",model.diagnostic()); sample.put("firstPid",first.process.pid()); sample.put("secondPid",second.process.pid()); second.close();
                sample.put("budgetBeforeKill",budgetBefore); sample.put("budgetAfterAExit",budgetStopped); sample.put("budgetRestored",budgetRestored); sample.put("budgetCompleted",budgetCompleted); sample.put("budgetAfterDuplicate",budgetDuplicate);
            }
        });
    }

    /** Direct委派父dependency与子HUMAN等待一起跨A/B进程；B只推进原child并本地唤醒原parent。 */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void realJvmRestartResumesDelegatedChildHumanWaitWithoutRedispatch() throws Exception {
        waitingCase("delegation-wait", (directory, sample) -> {
            try (var provider = new RuntimeExecutionCompositionProvider(objectMapper,RuntimeExecutionCompositionProvider.Scenario.DELEGATION,"restart-delegate-" + id())) {
                provider.start(); configureModel(provider.endpoint()); configureRestartDelegation(provider);
                runId = "restart-delegate-" + id(); String actualRun = "run:" + runId;
                var first = launch(directory,"first","resume",actualRun);
                String firstToken = login(first); createSession(first,firstToken); submit(first,firstToken,provider.prompt(),actualRun);
                var parentWait = awaitWait(actualRun,"DELEGATION");
                var childRows = queryRows(jdbc,tenantId,"SELECT delegation_id,child_run_id FROM agent_runtime_execution_wait_children WHERE tenant_id=? AND action_id=? AND generation=?",tenantId,parentWait.get("action_id"),parentWait.get("generation"));
                assertEquals(1,childRows.size()); String childRun = childRows.getFirst().get("child_run_id").toString(); String delegation = childRows.getFirst().get("delegation_id").toString();
                assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_delegations WHERE tenant_id=? AND parent_run_id=? AND delegation_id=? AND child_run_id=?",tenantId,actualRun,delegation,childRun));
                var childWait = awaitWait(childRun,"HUMAN"); var parentBefore = persistedFacts(actualRun); var childBefore = persistedFacts(childRun);
                var budgetBefore = budgetFacts(actualRun); assertStableBudgetUnchanged(budgetBefore,budgetFacts(childRun));
                assertEquals(1,provider.parentCalls()); assertEquals(1,provider.childCalls());
                first.kill(); var budgetStopped = budgetFacts(actualRun); assertStableBudgetUnchanged(budgetBefore,budgetStopped);
                var second = launch(directory,"second","resume",actualRun);
                assertNotEquals(first.process.pid(),second.process.pid());
                assertEquals(parentWait,waitFact(actualRun,"DELEGATION")); assertEquals(childWait,waitFact(childRun,"HUMAN"));
                assertOriginalRequestsUnchanged(parentBefore,persistedFacts(actualRun)); assertOriginalRequestsUnchanged(childBefore,persistedFacts(childRun));
                var budgetRestored = budgetFacts(actualRun); assertBudgetUnchangedBeforeResponse(budgetStopped,budgetRestored);
                assertStableBudgetUnchanged(budgetRestored,budgetFacts(childRun));
                assertEquals(1,provider.parentCalls()); assertEquals(1,provider.childCalls());
                String token = login(second); var response = request(second,"/api/v1/chat/work?session_id=" + sessionId + "&work_id=" + runId,"GET",null,token);
                assertEquals(200,response.statusCode(),response.body()); var tasks = successData(response).path("tasks"); assertEquals(1,tasks.size());
                var task = tasks.get(0); assertEquals(childRun,task.path("run_id").asText()); assertEquals(delegation,task.path("task_id").asText());
                var pending = task.path("pending"); assertEquals(provider.question(),pending.path("prompt").asText());
                assertEquals(childWait.get("action_id").toString(),pending.path("tool_wait").path("action_id").asText());
                assertEquals(childWait.get("generation").toString(),pending.path("tool_wait").path("generation").asText());
                var control = Map.<String,Object>of("session_id",sessionId,"work_id",runId,"task_id",delegation,"operation","reply_input","input_id","restart-child-human-" + runId,
                        "pending_id",pending.path("pending_id").asText(),"parameters_version",pending.path("parameters_version").asText(),"content",provider.reply());
                var accepted = request(second,"/api/v1/chat/work/controls","POST",control,token); assertEquals(200,accepted.statusCode(),accepted.body());
                awaitCompleted(childRun); awaitCompleted(actualRun);
                var parentAfter = persistedFacts(actualRun); var childAfter = persistedFacts(childRun);
                var budgetCompleted = budgetFacts(actualRun); assertFinalBudget(budgetCompleted,Map.of(actualRun,new ExpectedBudgetCalls(2,1),childRun,new ExpectedBudgetCalls(2,0)));
                var duplicate = request(second,"/api/v1/chat/work/controls","POST",control,token); assertEquals(200,duplicate.statusCode(),duplicate.body());
                assertEquals(parentAfter,persistedFacts(actualRun)); assertEquals(childAfter,persistedFacts(childRun));
                var budgetDuplicate = budgetFacts(actualRun); assertBudgetUnchangedAfterDuplicate(budgetCompleted,budgetDuplicate);
                assertEquals(budgetDuplicate,budgetFacts(childRun));
                assertEquals(2,provider.parentCalls()); assertEquals(2,provider.childCalls()); assertEquals(1,provider.snapshot().get("verifiedReplies")); assertEquals(1,provider.snapshot().get("verifiedResults"));
                assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? AND action_id=?",tenantId,actualRun,parentWait.get("action_id")));
                assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_waits WHERE tenant_id=? AND run_id=? AND action_id=? AND generation=? AND input_id=? AND consumed_revision>accepted_revision AND continuation_attempt IS NULL",
                        tenantId,childRun,childWait.get("action_id"),childWait.get("generation"),"restart-child-human-" + runId));
                assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_waits WHERE tenant_id=? AND run_id=? AND action_id=? AND generation=? AND input_id IS NOT NULL AND consumed_revision>accepted_revision AND continuation_attempt IS NULL",
                        tenantId,actualRun,parentWait.get("action_id"),parentWait.get("generation")));
                assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_budget_runs c JOIN agent_runtime_budget_runs p ON p.tenant_id=c.tenant_id AND p.root_id=c.root_id WHERE c.tenant_id=? AND c.run_id=? AND c.role='MEMBER' AND p.run_id=? AND p.role='PRIMARY'",tenantId,childRun,actualRun));
                assertCompletionOnce(second,token,actualRun,provider.finalAnswer()); assertTrue(((List<?>)provider.snapshot().get("failures")).isEmpty());
                sample.put("run",actualRun); sample.put("childRun",childRun); sample.put("parentWait",parentWait); sample.put("childWait",childWait);
                sample.put("parentAfter",parentAfter); sample.put("childAfter",childAfter); sample.put("provider",provider.snapshot()); sample.put("firstPid",first.process.pid()); sample.put("secondPid",second.process.pid()); second.close();
                sample.put("budgetBeforeKill",budgetBefore); sample.put("budgetAfterAExit",budgetStopped); sample.put("budgetRestored",budgetRestored); sample.put("budgetCompleted",budgetCompleted); sample.put("budgetAfterDuplicate",budgetDuplicate);
            }
        });
    }

    /** 单一selector独占自己的资源与两PID，父仅建立资源和读PG，全部定时任务先销毁再创建原Run。 */
    private void waitingCase(String name, WaitingCase test) throws Exception {
        assertEquals(21,Runtime.version().feature());
        String supplied = System.getenv("SKILLFORGE_RUNTIME_PROCESS_RESTART_EVIDENCE");
        Path reports = Files.createDirectories((supplied == null ? Path.of("build","reports") : Path.of(supplied)).toAbsolutePath());
        evidence = Files.createTempDirectory(reports,"runtime-execution-process-restart-");
        var report = new LinkedHashMap<String,Object>(); report.put("outcome","FAILED"); report.put("parentPid",ProcessHandle.current().pid()); report.put("scenario",name);
        try {
            applicationContext.getBean(com.skillforge.runtime.starter.AgentRuntimeProperties.class).getWorkers().setAutoStartup(false);
            applicationContext.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class).destroy();
            assertTrue(applicationContext.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().isEmpty());
            applicationContext.getBean(AgentRuntimeExecutionWorker.class).close();
            applicationContext.getBean(com.skillforge.institution.runtime.AgentRuntimeExecutionActivity.class).close();
            applicationContext.getBean(com.skillforge.runtime.starter.ExecutionHostScheduler.class).close();
            assertTrue(applicationContext.getBean(com.skillforge.runtime.starter.ExecutionHostWorker.class).isClosed());
            assertTrue(applicationContext.getBean(com.skillforge.runtime.starter.ExecutionHostScheduler.class).controls().isTerminated());
            report.put("parentScheduledTasks",0); report.put("parentExecutionWorkerClosed",true); report.put("parentExecutionControlTerminated",true);
            login(); test.run(Files.createDirectory(evidence.resolve(name)),report); report.put("outcome","PASS");
        } catch (Exception | AssertionError failure) { report.put("failureType",failure.getClass().getName()); throw failure; }
        finally {
            boolean reaped = true; for (var child : children) try { child.close(); } catch (Exception failure) { reaped = false; }
            report.put("allOwnedChildrenReaped",reaped); report.put("processes",children.stream().map(Child::facts).toList()); if (!reaped) report.put("outcome","FAILED");
            write(evidence.resolve("report.json"),report); System.out.println("RUNTIME_EXECUTION_PROCESS_RESTART_REPORT " + evidence.resolve("report.json")); assertTrue(reaped);
        }
    }

    /** 保存当前等待的原Action、source Attempt与代数；不选择历史等待或最新Run。 */
    private Map<String,Object> waitFact(String actualRun,String kind) {
        var rows = queryRows(jdbc,tenantId,"SELECT w.action_id,w.generation,w.source_attempt,w.kind,w.business_wait_id,w.input_id,w.response_ref,w.accepted_revision,w.continuation_attempt,w.consumed_revision "
                + "FROM agent_runtime_execution_runs r JOIN agent_runtime_execution_waits w ON w.tenant_id=r.tenant_id AND w.run_id=r.run_id AND w.action_id=r.waiting_action_id AND w.generation=r.waiting_generation "
                + "WHERE r.tenant_id=? AND r.run_id=? AND r.phase='WAITING' AND r.status='SUSPENDED' AND r.lease_claim_id IS NULL AND w.kind=? AND w.input_id IS NULL",tenantId,actualRun,kind);
        assertTrue(rows.size() <= 1); return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 只等已提交业务边界，失败后不重新提交初始输入。 */
    private Map<String,Object> awaitWait(String actualRun,String kind) throws Exception {
        await("原Run已保存" + kind + "等待",Duration.ofSeconds(45),() -> waitFact(actualRun,kind) != null); return waitFact(actualRun,kind);
    }

    /** 只统计原准确Run，完成事件与SSE在提交后独立读取。 */
    private void awaitCompleted(String actualRun) throws Exception {
        await("原Run完成 " + actualRun,Duration.ofSeconds(45),() -> countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED' AND lease_claim_id IS NULL",tenantId,actualRun) == 1);
    }

    /** 有限公开重放仅用于产品结果核验，不触发执行或代替正式完成事实。 */
    private void assertCompletionOnce(Child child,String token,String actualRun,String text) throws Exception {
        assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='execution.completed'",tenantId,actualRun));
        String events = replay(child,token); assertTrue(events.contains(text),events);
        assertTrue(events.contains("event:execution.completed") || events.contains("event: execution.completed"),events);
        assertFalse(events.contains("event:error") || events.contains("event: error"),events);
    }

    /** 浏览器连接业务表仍保留原request/invocation，恢复权威Wait和Attempt归typed执行存储。 */
    private Map<String,Object> browserConnection(String actualRun) {
        var rows = queryRows(jdbc,tenantId,"SELECT request_id,invocation_id,status,wait_snapshot,device_id,browser_id,pair_dispatched FROM nw_browser_connection_requests WHERE tenant_id=?::bigint AND run_id=?",tenantId,actualRun);
        assertEquals(1,rows.size()); return rows.getFirst();
    }

    /** 原公开SSE的卡片正文，不能用PG伪造等待响应版本。 */
    private JsonNode hitl(String events) throws Exception {
        String event = ""; for (String line : events.split("\\R")) {
            if (line.startsWith("event:")) event = line.substring(6).trim();
            else if (line.startsWith("data:") && event.equals("hitl.required")) return objectMapper.readTree(line.substring(5).trim());
        }
        throw new AssertionError("persisted_hitl_card_missing:" + events);
    }

    /** 唯一实际平台工具经发布目录、部署与Agent挂载进入模型，不安装测试Handler。 */
    private void mountRestartTool(String tool) throws Exception {
        var revised = send("/api/v1/agents/revise","POST",Map.of("agent_id",agentId),accessToken); assertEquals(200,revised.statusCode(),revised.body()); agentId = successData(revised).path("id").asText();
        JsonNode definition = null; for (var value : successData(get("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED"))) if (("tool:" + tool).equals(value.path("capability_key").asText())) definition = value;
        assertNotNull(definition); long definitionId = definition.path("id").asLong();
        String versionKey = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream().filter(value -> tool.equals(value.name())).findFirst().orElseThrow().publishedVersionKey();
        long version = 0; for (var value : successData(get("/api/v1/capabilities/definitions/versions?status=PUBLISHED&definitionId=" + definitionId))) if (versionKey.equals(value.path("version_key").asText())) version = value.path("id").asLong(); assertTrue(version > 0);
        String deployment = "restart-" + tool + "-" + id();
        var created = send("/api/v1/capabilities/deployments","POST",Map.of("deployment_key",deployment,"tool_version_id",version,"provider_type","PLATFORM","provider_ref",tool,"status","ACTIVE","environment","TEST"),accessToken); assertEquals(201,created.statusCode(),created.body());
        var mounted = send("/api/v1/agents/capability-mounts","POST",Map.of("agent_id",agentId,"payload",Map.of("capability_type","TOOL","capability_definition_id",Long.toString(definitionId),"capability_version_id",Long.toString(version),"tool_config",Map.of("tool_deployment_id",deployment,"tool_deployment_revision",successData(created).path("revision").asLong()),"priority",0)),accessToken); assertEquals(201,mounted.statusCode(),mounted.body());
        var published = send("/api/v1/agents/publish","POST",Map.of("agent_id",agentId),accessToken); assertEquals(200,published.statusCode(),published.body());
    }

    /** 直接委派配置经真实发布进入原冻结范围，子身份仍由生产准入创建。 */
    private void configureRestartDelegation(RuntimeExecutionCompositionProvider provider) throws Exception {
        var created = send("/api/v1/agents","POST",Map.of("agent_key","restart-child-" + id(),"name","恢复成员","system_prompt",provider.childSystem(),"config",Map.of()),accessToken); assertEquals(200,created.statusCode(),created.body());
        String child = successData(created).path("id").asText(); var childPublished = send("/api/v1/agents/publish","POST",Map.of("agent_id",child),accessToken); assertEquals(200,childPublished.statusCode(),childPublished.body());
        var revised = send("/api/v1/agents/revise","POST",Map.of("agent_id",agentId),accessToken); assertEquals(200,revised.statusCode(),revised.body()); agentId = successData(revised).path("id").asText();
        var updated = send("/api/v1/agents/update","PATCH",Map.of("agent_id",agentId,"payload",Map.of("delegation_config",Map.of("enabled",true,"target_agent_ids",List.of(child)))),accessToken); assertEquals(200,updated.statusCode(),updated.body());
        var published = send("/api/v1/agents/publish","POST",Map.of("agent_id",agentId),accessToken); assertEquals(200,published.statusCode(),published.body());
    }

    /** 每个场景拥有自己的进程外Provider及浏览器协议端，所有进程最终由外层收割。 */
    @FunctionalInterface private interface WaitingCase { void run(Path directory,Map<String,Object> sample) throws Exception; }

    /** 一个root的完整计费事实稳定排序；NULL reported原样保存，活动区间作为独立原始时间证据。 */
    private Map<String,Object> budgetFacts(String actualRun) {
        var own = queryRows(jdbc,tenantId,"SELECT root_id FROM agent_runtime_budget_runs WHERE tenant_id=? AND run_id=?",tenantId,actualRun);
        assertEquals(1,own.size()); String root = own.getFirst().get("root_id").toString();
        var facts = new LinkedHashMap<String,Object>();
        facts.put("root",queryRows(jdbc,tenantId,"SELECT tenant_id,root_id,scenario,source_version,active_limit_seconds,closing_active_seconds,closing_run_id,phase FROM agent_runtime_budget_roots WHERE tenant_id=? AND root_id=? ORDER BY root_id",tenantId,root));
        facts.put("runs",queryRows(jdbc,tenantId,"SELECT tenant_id,run_id,root_id,role,source_version,active_limit_seconds FROM agent_runtime_budget_runs WHERE tenant_id=? AND root_id=? ORDER BY run_id",tenantId,root));
        facts.put("balances",queryRows(jdbc,tenantId,"SELECT b.tenant_id,b.scope_kind,b.scope_id,b.dimension,b.maximum,b.consumed,b.reserved FROM agent_runtime_budget_balances b WHERE b.tenant_id=? AND "
                + "((b.scope_kind IN ('ROOT','CLOSING') AND b.scope_id=?) OR (b.scope_kind IN ('RUN','STAGE_BASE','STAGE','CLOSING_RUN') AND b.scope_id IN (SELECT run_id FROM agent_runtime_budget_runs WHERE tenant_id=? AND root_id=?))) ORDER BY b.scope_kind,b.scope_id,b.dimension",tenantId,root,tenantId,root));
        facts.put("attempts",queryRows(jdbc,tenantId,"SELECT a.tenant_id,a.attempt_id,a.run_id,a.fingerprint,a.category,a.closing,a.state,a.result_reference FROM agent_runtime_budget_attempts a JOIN agent_runtime_budget_runs r ON r.tenant_id=a.tenant_id AND r.run_id=a.run_id WHERE r.tenant_id=? AND r.root_id=? ORDER BY a.run_id,a.attempt_id",tenantId,root));
        facts.put("amounts",queryRows(jdbc,tenantId,"SELECT m.tenant_id,m.attempt_id,m.dimension,m.reserved,m.reported,m.settled FROM agent_runtime_budget_attempt_amounts m JOIN agent_runtime_budget_attempts a ON a.tenant_id=m.tenant_id AND a.attempt_id=m.attempt_id JOIN agent_runtime_budget_runs r ON r.tenant_id=a.tenant_id AND r.run_id=a.run_id WHERE r.tenant_id=? AND r.root_id=? ORDER BY m.attempt_id,m.dimension",tenantId,root));
        facts.put("receipts",queryRows(jdbc,tenantId,"SELECT p.tenant_id,p.attempt_id,p.result_reference FROM agent_runtime_budget_result_receipts p JOIN agent_runtime_budget_attempts a ON a.tenant_id=p.tenant_id AND a.attempt_id=p.attempt_id JOIN agent_runtime_budget_runs r ON r.tenant_id=a.tenant_id AND r.run_id=a.run_id WHERE r.tenant_id=? AND r.root_id=? ORDER BY p.attempt_id,p.result_reference",tenantId,root));
        facts.put("closingTargets",queryRows(jdbc,tenantId,"SELECT t.tenant_id,t.run_id,t.target_handle FROM agent_runtime_budget_closing_targets t JOIN agent_runtime_budget_runs r ON r.tenant_id=t.tenant_id AND r.run_id=t.run_id WHERE r.tenant_id=? AND r.root_id=? ORDER BY t.run_id,t.target_handle",tenantId,root));
        facts.put("activity",queryRows(jdbc,tenantId,"SELECT a.tenant_id,a.run_id,a.interval_id,a.started_at,a.confirmed_through FROM agent_runtime_budget_activity a JOIN agent_runtime_budget_runs r ON r.tenant_id=a.tenant_id AND r.run_id=a.run_id WHERE r.tenant_id=? AND r.root_id=? ORDER BY a.run_id,a.interval_id",tenantId,root));
        return facts;
    }

    /** 无业务响应边界内七类稳定计费事实全值一致，不将活动时间变化混作资源余额。 */
    private void assertStableBudgetUnchanged(Map<String,Object> before,Map<String,Object> after) {
        for (String key : List.of("root","runs","balances","attempts","amounts","receipts","closingTargets")) assertEquals(before.get(key),after.get(key),"restart budget " + key);
    }

    /** baseline在A已退出后采集；B可新增真实领取活动，但不能重写已结束PID的原区间。 */
    private void assertBudgetUnchangedBeforeResponse(Map<String,Object> before,Map<String,Object> after) {
        assertStableBudgetUnchanged(before,after);
        var current = budgetRows(after,"activity");
        for (var original : budgetRows(before,"activity")) {
            var matching = current.stream().filter(row -> row.get("run_id").equals(original.get("run_id")) && row.get("interval_id").equals(original.get("interval_id"))).toList();
            assertEquals(1,matching.size()); assertEquals(original,matching.getFirst(),"原PID已关闭的活动区间不能由B更换或重写");
        }
    }

    /** 完成后重复响应不得新增scope/Attempt/结算或任何活动区间，所有原始账本字段全等。 */
    private void assertBudgetUnchangedAfterDuplicate(Map<String,Object> before,Map<String,Object> after) {
        assertEquals(before,after,"重复控制输入不能重新登记、预留、结算或启动计时");
    }

    /** 正常完成的root与每个Run按实际生产分类计数；Tool缺报Token仍留在原amounts中，不把NULL改写为零。 */
    private void assertFinalBudget(Map<String,Object> facts,Map<String,ExpectedBudgetCalls> expected) {
        var roots = budgetRows(facts,"root"); assertEquals(1,roots.size()); String root = roots.getFirst().get("root_id").toString();
        var runs = budgetRows(facts,"runs"); assertEquals(expected.keySet(),runs.stream().map(row -> row.get("run_id").toString()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(1,runs.stream().filter(row -> row.get("role").equals("PRIMARY")).count());
        for (var run : runs) { assertEquals(root,run.get("root_id")); assertEquals(run.get("run_id").equals(root) ? "PRIMARY" : "MEMBER",run.get("role")); }
        var balances = budgetRows(facts,"balances"); var dimensions = java.util.Arrays.stream(com.skillforge.runtime.api.port.ExecutionBudgetPort.Dimension.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet());
        var scopes = new java.util.TreeSet<String>(); scopes.add("ROOT:" + root); scopes.add("CLOSING:" + root);
        for (String run : expected.keySet()) for (String kind : List.of("RUN","STAGE_BASE","STAGE","CLOSING_RUN")) scopes.add(kind + ":" + run);
        assertEquals(scopes,balances.stream().map(row -> row.get("scope_kind") + ":" + row.get("scope_id")).collect(java.util.stream.Collectors.toSet()));
        assertEquals(scopes.size() * dimensions.size(),balances.size());
        for (String scope : scopes) assertEquals(dimensions,balances.stream().filter(row -> (row.get("scope_kind") + ":" + row.get("scope_id")).equals(scope)).map(row -> row.get("dimension").toString()).collect(java.util.stream.Collectors.toSet()));
        long allModels = expected.values().stream().mapToLong(ExpectedBudgetCalls::models).sum(); long allTools = expected.values().stream().mapToLong(ExpectedBudgetCalls::tools).sum();
        assertBudgetConsumed(balances,"ROOT",root,new ExpectedBudgetCalls(allModels,allTools));
        for (var run : expected.entrySet()) assertBudgetConsumed(balances,"RUN",run.getKey(),run.getValue());
        var attempts = budgetRows(facts,"attempts"); var amounts = budgetRows(facts,"amounts"); var receipts = budgetRows(facts,"receipts");
        assertEquals(allModels + allTools,attempts.size()); assertEquals(attempts.size() * dimensions.size(),amounts.size());
        for (var run : expected.entrySet()) {
            assertEquals(run.getValue().models(),attempts.stream().filter(row -> row.get("run_id").equals(run.getKey()) && row.get("category").equals("BUSINESS_MODEL")).count());
            assertEquals(run.getValue().tools(),attempts.stream().filter(row -> row.get("run_id").equals(run.getKey()) && row.get("category").equals("TOOL")).count());
        }
        for (var attempt : attempts) {
            assertTrue(List.of("RESULT_SAVED","SETTLED").contains(attempt.get("state"))); assertNotNull(attempt.get("result_reference"));
            String id = attempt.get("attempt_id").toString(); var values = amounts.stream().filter(row -> row.get("attempt_id").equals(id)).toList();
            assertEquals(dimensions,values.stream().map(row -> row.get("dimension").toString()).collect(java.util.stream.Collectors.toSet()));
            assertTrue(receipts.stream().anyMatch(row -> row.get("attempt_id").equals(id) && row.get("result_reference").equals(attempt.get("result_reference"))));
            boolean model = attempt.get("category").equals("BUSINESS_MODEL");
            for (String dimension : List.of("BUSINESS_MODELS","AUXILIARY_MODELS","TOOLS","KNOWLEDGE")) {
                var amount = values.stream().filter(row -> row.get("dimension").equals(dimension)).findFirst().orElseThrow();
                long sent = dimension.equals(model ? "BUSINESS_MODELS" : "TOOLS") ? 1 : 0;
                assertEquals(sent,((Number)amount.get("reported")).longValue()); assertEquals(Boolean.TRUE,amount.get("settled"));
            }
            if (model) for (String dimension : List.of("INPUT_TOKENS","OUTPUT_TOKENS","TOTAL_TOKENS")) {
                var amount = values.stream().filter(row -> row.get("dimension").equals(dimension)).findFirst().orElseThrow();
                long reported = dimension.equals("INPUT_TOKENS") ? 11 : dimension.equals("OUTPUT_TOKENS") ? 7 : 18;
                assertEquals(reported,((Number)amount.get("reported")).longValue()); assertEquals(Boolean.TRUE,amount.get("settled"));
            } else for (String dimension : List.of("INPUT_TOKENS","OUTPUT_TOKENS","TOTAL_TOKENS")) {
                var amount = values.stream().filter(row -> row.get("dimension").equals(dimension)).findFirst().orElseThrow();
                assertEquals(null,amount.get("reported"),"生产Tool未报告Token，不能为完成断言补零");
                assertEquals(0,((Number)amount.get("reserved")).longValue()); assertEquals(Boolean.FALSE,amount.get("settled"));
            }
        }
    }

    /** 七维消费均来自真实确定Provider用量及已发送次数；完成后没有在途资源预留。 */
    private void assertBudgetConsumed(List<Map<String,Object>> balances,String kind,String scope,ExpectedBudgetCalls calls) {
        for (var row : balances) if (row.get("scope_kind").equals(kind) && row.get("scope_id").equals(scope)) {
            long expected = switch(row.get("dimension").toString()) {
                case "BUSINESS_MODELS" -> calls.models(); case "TOOLS" -> calls.tools();
                case "INPUT_TOKENS" -> calls.models() * 11; case "OUTPUT_TOKENS" -> calls.models() * 7; case "TOTAL_TOKENS" -> calls.models() * 18;
                case "AUXILIARY_MODELS","KNOWLEDGE" -> 0; default -> throw new AssertionError("budget_dimension_unknown");
            };
            assertEquals(expected,((Number)row.get("consumed")).longValue(),kind + ":" + scope + ":" + row.get("dimension"));
            assertEquals(0,((Number)row.get("reserved")).longValue(),"完成后不能残留已知消费的预留");
        }
    }

    /** 只转换本测试固定读取的行集合，不转写NULL、数值或任何预算事实。 */
    @SuppressWarnings("unchecked") private List<Map<String,Object>> budgetRows(Map<String,Object> facts,String key) { return (List<Map<String,Object>>) facts.get(key); }

    /** @param models 实际BUSINESS_MODEL发送次数 @param tools 实际TOOL分类派发次数，含INPUT_REQUIRED/continuation各次派发 */
    private record ExpectedBudgetCalls(long models,long tools) { }

    /** 重启可能合法推进领取revision/fence；原Action/Attempt及请求结果引用必须完全保持。 */
    private void assertOriginalRequestsUnchanged(Map<String,Object> before,Map<String,Object> after) {
        assertEquals(before.get("actions"),after.get("actions")); assertEquals(before.get("attempts"),after.get("attempts"));
    }

    private void savedResult(List<Map<String, Object>> scenarios) throws Exception {
        var sample = scenario(scenarios, "saved-result");
        Path directory = Files.createDirectory(evidence.resolve("saved-result"));
        try (var provider = new RuntimeExecutionWorkloadProvider(objectMapper)) {
            provider.start();
            var script = provider.register("restart-saved-" + id(), RuntimeExecutionWorkloadProvider.Workload.ORDINARY);
            try (var proxy = new GateProxy(provider.endpoint(), false)) {
                proxy.start(); configureModel(proxy.endpoint());
                runId = "restart-saved-" + id(); String actualRun = "run:" + runId;
                sample.put("run", actualRun);
                var first = launch(directory, "first", "saved-result", actualRun);
                String token = login(first);
                createSession(first, token);
                submit(first, token, script.prompt(), actualRun);
                await("首个真实Provider请求抵达代理", Duration.ofSeconds(30), () -> proxy.entered.getCount() == 0);
                disconnectSse(first, token, directory.resolve("disconnected-sse.json"));
                proxy.beforeForward.countDown();
                await("工具正式事务提交后的测试边界", Duration.ofSeconds(45), () -> Files.isRegularFile(first.committed));
                var committed = objectMapper.readTree(Files.readString(first.committed));
                assertEquals(first.process.pid(), committed.path("pid").asLong());
                assertTrue(committed.path("advanced").asBoolean());
                var original = actionAttempt(actualRun, committed.path("action").asText());
                assertEquals(committed.path("attempt").asText(), original.get("attempt_id"));
                assertEquals(committed.path("resultRef").asText(), original.get("result_ref"));
                assertEquals("SUCCEEDED", original.get("result_kind")); assertEquals(true, original.get("adopted"));
                assertEquals(1, script.snapshot().requestCount(), "强杀边界前只进行第一次模型调用");
                assertEquals(1, toolInvocations(directory).size());
                var beforeKill = runFacts(actualRun);
                var frozenBeforeKill = frozenAdmissionFacts(actualRun);
                assertChildLease(first, beforeKill);
                write(directory.resolve("before-kill.json"), Map.of("run", beforeKill, "originalActionAttempt", original));
                first.kill();
                var second = launch(directory, "second", "resume", actualRun);
                assertNotEquals(first.process.pid(), second.process.pid());
                await("新JVM继续原工具结果并完成第二次模型", Duration.ofSeconds(45), () -> countRuntime(
                        "SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED'",
                        tenantId, actualRun) == 1);
                assertTypedExecutionPersistence(actualRun, script.expectedToolResult(1));
                assertEquals(frozenBeforeKill, frozenAdmissionFacts(actualRun), "新JVM必须保留原输入、身份、授权验证和冻结Profile材料");
                assertEquals(original, actionAttempt(actualRun, committed.path("action").asText()), "不能重建Action/Attempt或替换原结果材料");
                await("Provider完整响应边界记录", Duration.ofSeconds(5), () -> script.snapshot().requests().size() == 2);
                assertEquals(2, proxy.received.get()); assertEquals(2, script.snapshot().requestCount());
                assertEquals(1, script.snapshot().verifiedToolResults());
                var tools = toolInvocations(directory);
                assertEquals(1, tools.size(), "真实工具在两个PID中总共只进入一次调用边界");
                assertEquals(first.process.pid(), tools.getFirst().path("pid").asLong());
                String secondToken = login(second);
                var status = status(second, secondToken, actualRun);
                assertEquals("completed", successData(status).path("status").asText());
                write(directory.resolve("public-status.json"), objectMapper.readTree(status.body()));
                var replay = replay(second, secondToken);
                Files.writeString(directory.resolve("replay.sse.txt"), replay, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                assertTrue(replay.contains("event:execution.completed") || replay.contains("event: execution.completed"), replay);
                assertTrue(replay.contains(script.finalAnswer()), replay);
                write(directory.resolve("after-restart.json"), persistedFacts(actualRun));
                sample.put("provider", script.snapshot()); sample.put("proxyRequests", proxy.received.get());
                sample.put("frozenAdmission", frozenBeforeKill);
                sample.put("actualToolInvocations", tools); sample.put("firstPid", first.process.pid()); sample.put("secondPid", second.process.pid());
                second.close(); // 后一场景只能由其自己的两个子PID领取，不能被本场景幸存Worker抢占。
                sample.put("outcome", "PASS");
            }
        }
    }

    private void unknownResult(List<Map<String, Object>> scenarios) throws Exception {
        var sample = scenario(scenarios, "unknown-result");
        Path directory = Files.createDirectory(evidence.resolve("unknown-result"));
        try (var provider = new RuntimeExecutionWorkloadProvider(objectMapper)) {
            provider.start();
            var script = provider.register("restart-unknown-" + id(), RuntimeExecutionWorkloadProvider.Workload.ORDINARY);
            try (var proxy = new GateProxy(provider.endpoint(), true)) {
                proxy.start(); configureModel(proxy.endpoint());
                runId = "restart-unknown-" + id(); String actualRun = "run:" + runId;
                sample.put("run", actualRun);
                var first = launch(directory, "first", "unknown-result", actualRun);
                String token = login(first); createSession(first, token);
                submit(first, token, script.prompt(), actualRun);
                await("首个真实Provider请求进入", Duration.ofSeconds(30), () -> proxy.entered.getCount() == 0);
                disconnectSse(first, token, directory.resolve("disconnected-sse.json"));
                proxy.beforeForward.countDown();
                await("真实Provider已生成响应但尚未发回执行JVM", Duration.ofSeconds(30), () -> proxy.responseHeld.getCount() == 0);
                var beforeKill = runFacts(actualRun); assertChildLease(first, beforeKill);
                var frozenBeforeKill = frozenAdmissionFacts(actualRun);
                assertEquals("AWAITING_RESULT", beforeKill.get("phase"));
                var original = actionAttempt(actualRun, beforeKill.get("current_action_id").toString());
                assertEquals("MODEL", original.get("kind")); assertEquals(null, original.get("result_kind"));
                assertEquals(null, original.get("result_ref")); assertEquals(false, original.get("adopted"));
                write(directory.resolve("before-kill.json"), Map.of("run", beforeKill, "originalActionAttempt", original));
                first.kill();
                proxy.returnResponse.countDown();
                var second = launch(directory, "second", "resume", actualRun);
                assertNotEquals(first.process.pid(), second.process.pid());
                await("重启领取原无结果派发并持久化不确定状态", Duration.ofSeconds(45), () -> countRuntime(
                        "SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='SUSPENDED' AND phase='UNCERTAIN' "
                                + "AND lease_claim_id IS NULL AND next_due_second IS NULL", tenantId, actualRun) == 1);
                // 覆盖唯一Worker至少一个完整5秒兜底周期；持续读取事实和外调计数，不将延时本身当作成功。
                long until = System.nanoTime() + Duration.ofSeconds(6).toNanos();
                do {
                    assertUnknownUnchanged(actualRun, original);
                    assertEquals(1, proxy.received.get()); assertEquals(1, script.snapshot().requestCount());
                    Thread.sleep(100);
                } while (System.nanoTime() < until);
                assertTrue(toolInvocations(directory).isEmpty());
                assertEquals(frozenBeforeKill, frozenAdmissionFacts(actualRun), "UNKNOWN恢复不得重建原输入、身份、授权验证或冻结Profile");
                String secondToken = login(second);
                var status = status(second, secondToken, actualRun);
                assertEquals("suspended", successData(status).path("status").asText());
                write(directory.resolve("public-status.json"), objectMapper.readTree(status.body()));
                var replay = replay(second, secondToken);
                Files.writeString(directory.resolve("replay.sse.txt"), replay, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                assertFalse(replay.contains("event:execution.completed") || replay.contains("event: execution.completed"), replay);
                assertFalse(replay.contains(script.finalAnswer()), replay);
                write(directory.resolve("after-restart.json"), persistedFacts(actualRun));
                sample.put("provider", script.snapshot()); sample.put("proxyRequests", proxy.received.get());
                sample.put("frozenAdmission", frozenBeforeKill);
                sample.put("actualToolInvocations", toolInvocations(directory)); sample.put("firstPid", first.process.pid());
                sample.put("secondPid", second.process.pid()); sample.put("unknownObservationNanos", Duration.ofSeconds(6).toNanos());
                second.close();
                sample.put("outcome", "PASS");
            }
        }
    }

    /** 原Attempt仍无确定结果；UNKNOWN只写Action/Run状态，不伪造结果正文或补零用量。 */
    private void assertUnknownUnchanged(String actualRun, Map<String, Object> original) {
        var after = actionAttempt(actualRun, original.get("action_id").toString());
        for (String column : List.of("action_id", "attempt_id", "request_ref", "fingerprint", "attempt_request_ref", "attempt_fingerprint", "result_kind", "result_ref", "uncertainty_ref", "adopted"))
            assertEquals(original.get(column), after.get(column), column);
        assertEquals("UNKNOWN", after.get("status")); assertEquals(null, after.get("selected_attempt"));
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
        assertEquals(0, countRuntime("SELECT count(*) FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='execution.completed'", tenantId, actualRun));
    }

    private Map<String, Object> scenario(List<Map<String, Object>> scenarios, String name) {
        var sample = new LinkedHashMap<String, Object>(); sample.put("scenario", name); sample.put("outcome", "FAILED"); scenarios.add(sample); return sample;
    }

    private void configureModel(String endpoint) {
        TenantContext.setTenantId(tenantId);
        try { configureTenantModel(endpoint); } finally { TenantContext.clear(); }
    }


    private Map<String, Object> runFacts(String actualRun) {
        var rows = queryRows(jdbc, tenantId, "SELECT run_id,status,phase,revision,fence,decisions,tool_calls,current_action_id,lease_worker_id,lease_claim_id,next_due_second "
                + "FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", tenantId, actualRun);
        assertEquals(1, rows.size()); return rows.getFirst();
    }

    /** 原准入引用和材料逐值比较；恢复不能按当前配置重建输入、身份与授权范围。 */
    private Map<String, Object> frozenAdmissionFacts(String actualRun) {
        var rows = queryRows(jdbc, tenantId, "SELECT session_id,target_kind,target_id,end_user_id,agent_id,input_id,root_input_id,"
                + "identity_ref,actor_verification_ref,configuration_ref,input_ref,initial_context_ref,admission_fingerprint "
                + "FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", tenantId, actualRun);
        assertEquals(1, rows.size());
        var materials = queryRows(jdbc, tenantId, "SELECT m.reference,m.schema_id,m.canonical_json FROM agent_runtime_execution_materials m "
                + "JOIN agent_runtime_execution_runs r ON r.tenant_id=m.tenant_id "
                + "AND m.reference IN (r.input_ref,r.initial_context_ref) "
                + "WHERE r.tenant_id=? AND r.run_id=? ORDER BY m.reference", tenantId, actualRun);
        var expectedReferences = new LinkedHashSet<>();
        for (String column : List.of("input_ref", "initial_context_ref"))
            expectedReferences.add(rows.getFirst().get(column));
        assertEquals(expectedReferences, new LinkedHashSet<>(materials.stream().map(row -> row.get("reference")).toList()), "所有原冻结材料必须真实存在");
        var verifications = queryRows(jdbc, tenantId, "SELECT v.verification_ref,v.authentication_ref,v.scopes_snapshot::text,v.identity_snapshot::text "
                + "FROM nw_runtime_identity_verifications v JOIN agent_runtime_execution_runs r ON v.tenant_id::text=r.tenant_id "
                + "AND v.verification_ref IN (r.identity_ref,r.actor_verification_ref) WHERE r.tenant_id=? AND r.run_id=? ORDER BY v.verification_ref", tenantId, actualRun);
        assertEquals(new LinkedHashSet<>(List.of(rows.getFirst().get("identity_ref"), rows.getFirst().get("actor_verification_ref"))),
                new LinkedHashSet<>(verifications.stream().map(row -> row.get("verification_ref")).toList()));
        var profiles = queryRows(jdbc, tenantId, "SELECT p.profile_ref,p.profile_data::text FROM agent_runtime_session_preparation_profiles p "
                + "JOIN agent_runtime_execution_runs r ON p.tenant_id=r.tenant_id AND p.profile_ref=r.configuration_ref "
                + "WHERE r.tenant_id=? AND r.run_id=?", tenantId, actualRun);
        assertEquals(1, profiles.size(), "原冻结Profile必须存在于其正式存储");
        return Map.of("admission", rows.getFirst(), "materials", materials, "verifications", verifications, "profiles", profiles);
    }

    private Map<String, Object> actionAttempt(String actualRun, String action) {
        var rows = queryRows(jdbc, tenantId, "SELECT a.action_id,a.kind,a.status,a.current_attempt,a.selected_attempt,a.request_ref,a.fingerprint,t.attempt_id,"
                + "t.request_ref AS attempt_request_ref,t.fingerprint AS attempt_fingerprint,t.result_kind,t.result_ref,t.uncertainty_ref,t.adopted "
                + "FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id "
                + "AND t.run_id=a.run_id AND t.action_id=a.action_id AND t.attempt_id=a.current_attempt WHERE a.tenant_id=? AND a.run_id=? AND a.action_id=?",
                tenantId, actualRun, action);
        assertEquals(1, rows.size()); return rows.getFirst();
    }

    private Map<String, Object> persistedFacts(String actualRun) {
        return Map.of("run", runFacts(actualRun), "actions", queryRows(jdbc, tenantId,
                "SELECT action_id,action_ordinal,kind,status,current_attempt,selected_attempt,request_ref,fingerprint FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? ORDER BY action_ordinal", tenantId, actualRun),
                "attempts", queryRows(jdbc, tenantId, "SELECT attempt_id,action_id,fence,request_ref,fingerprint,result_kind,result_ref,uncertainty_ref,adopted FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? ORDER BY action_id,attempt_id", tenantId, actualRun));
    }

    private void assertChildLease(Child child, Map<String, Object> run) {
        assertEquals(child.ready.path("workerId").asText(), run.get("lease_worker_id"));
        assertNotNull(run.get("lease_claim_id"));
    }

    private String login(Child child) throws Exception {
        var response = request(child, "/api/v1/auth/login", "POST", Map.of("tenant_slug", tenantSlug, "username", "runtime-e2e-admin", "password", PASSWORD), null);
        assertEquals(200, response.statusCode(), response.body()); String token = successData(response).path("access_token").asText();
        assertFalse(token.isBlank()); return token;
    }

    private void createSession(Child child, String token) throws Exception {
        var response = request(child, "/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), token);
        assertEquals(200, response.statusCode(), response.body()); sessionId = successData(response).path("session_id").asText(); assertFalse(sessionId.isBlank());
    }

    private void submit(Child child, String token, String prompt, String actualRun) throws Exception {
        var response = request(child, "/api/v1/chat", "POST", chatPayload(prompt), token);
        assertEquals(202, response.statusCode(), response.body()); assertEquals(actualRun, successData(response).path("execution_id").asText());
    }

    private HttpResponse<String> request(Child child, String path, String method, Map<String, Object> body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(child.baseUrl() + path)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .header("Accept", path.startsWith("/api/v1/chat/replay") ? "text/event-stream" : "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return httpClient.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> status(Child child, String token, String actualRun) throws Exception {
        return request(child, "/api/v1/chat/runs/status?session_id=" + sessionId + "&run_id=" + actualRun, "GET", null, token);
    }

    private String replay(Child child, String token) throws Exception {
        var response = request(child, "/api/v1/chat/replay?session_id=" + sessionId, "GET", null, token);
        assertEquals(200, response.statusCode(), response.body()); assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        assertTrue(response.body().contains("replay.completed"), response.body()); return response.body();
    }

    /** 真实订阅已得到HTTP响应且body关闭后才允许Provider执行，后续推进不能归因于客户端订阅。 */
    private void disconnectSse(Child child, String token, Path target) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(child.baseUrl() + "/api/v1/chat/events?session_id=" + sessionId))
                .timeout(Duration.ofSeconds(25)).header("Authorization", "Bearer " + token).header("Accept", "text/event-stream").GET().build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            assertEquals(200, response.statusCode()); assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        }
        write(target, Map.of("pid", child.process.pid(), "httpStatus", response.statusCode(), "clientBodyClosed", true, "closedAt", Instant.now().toString()));
    }

    private Child launch(Path directory, String name, String mode, String actualRun) throws Exception {
        return launch(directory,name,mode,actualRun,Map.of());
    }

    /** 仅本场景显式的进程外协议地址进入子配置，不继承业务库或浏览器秘密。 */
    private Child launch(Path directory,String name,String mode,String actualRun,Map<String,Object> external) throws Exception {
        String classpath = runtimeClasspath();
        Path args = directory.resolve(name + ".args"); Path ready = directory.resolve(name + ".ready.json");
        Path committed = directory.resolve(name + ".committed.json");
        String mockito = Path.of(org.mockito.Mockito.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        assertTrue(mockito.endsWith(".jar"), "Mockito必须由明确的test runtime jar加载");
        Files.writeString(args, "-Xmx512m\n" + quote("-javaagent:" + mockito) + "\n-cp\n" + quote(classpath) + "\n"
                + RuntimeExecutionRestartChild.class.getName() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        var config = new LinkedHashMap<String, Object>();
        var environment = applicationContext.getEnvironment();
        for (String key : List.of("spring.datasource.url", "spring.datasource.username", "spring.datasource.password", "spring.data.redis.host", "spring.data.redis.port", "skillforge.secret-key", "skillforge.management-jwt-secret-key"))
            config.put(key, environment.getRequiredProperty(key));
        // 父测试通过DynamicPropertySource提供独立密钥；子进程不能回读根环境的SECRET_KEY占位符。
        config.put("sa-token.jwt-secret-key", environment.getRequiredProperty("skillforge.secret-key"));
        for (String key : List.of("spring.data.redis.database", "spring.data.redis.username", "spring.data.redis.password")) {
            String value = environment.getProperty(key); if (value != null && !value.isBlank()) config.put(key, value);
        }
        config.put("server.port", 0); config.put("spring.flyway.enabled", false); config.put("spring.main.banner-mode", "off");
        config.put("spring.datasource.hikari.maximum-pool-size", 16); config.put("spring.datasource.hikari.minimum-idle", 1);
        config.put("agent-runtime.workers.auto-startup", true); config.put("agent-runtime.worker-id", "restart-" + id());
        config.put("agent-runtime.worker-lease-duration", "4s");
        config.put("skillforge.code-execution.artifact-root", directory.resolve(name + "-artifacts").toString());
        config.put("logging.level.root", "WARN"); config.put("restart-test.ready", ready.toString());
        config.put("restart-test.committed", committed.toString()); config.put("restart-test.tools", directory.resolve(name + ".tools.jsonl").toString());
        config.put("restart-test.mode", mode); config.put("restart-test.run", actualRun);
        config.putAll(external);
        String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java";
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "@" + args);
        // 凭据只进入子进程环境；argfile、marker、测试证据和命令均不保存配置JSON。
        builder.environment().put("SPRING_APPLICATION_JSON", objectMapper.writeValueAsString(config));
        builder.redirectErrorStream(true).redirectOutput(directory.resolve(name + ".log").toFile());
        var child = new Child(builder.start(), ready, committed, directory.resolve(name + ".exit.json")); children.add(child);
        await("子JVM正式应用ready marker", Duration.ofSeconds(90), () -> {
            assertTrue(child.process.isAlive(), "子JVM在ready前已退出，查看 " + directory.resolve(name + ".log")); return Files.isRegularFile(ready);
        });
        child.ready = objectMapper.readTree(Files.readString(ready));
        assertEquals(child.process.pid(), child.ready.path("pid").asLong()); assertEquals(21, child.ready.path("javaVersion").asInt());
        assertTrue(child.ready.path("port").asInt() > 0); return child;
    }

    private List<JsonNode> toolInvocations(Path directory) throws Exception {
        var values = new ArrayList<JsonNode>();
        for (String name : List.of("first.tools.jsonl", "second.tools.jsonl")) {
            var file = directory.resolve(name); if (!Files.exists(file)) continue;
            for (String line : Files.readAllLines(file)) if (!line.isBlank()) values.add(objectMapper.readTree(line));
        }
        return values;
    }

    /** Gradle显式文件优先；ConsoleLauncher取其实际应用classpath和URLClassLoader入口。 */
    private String runtimeClasspath() throws Exception {
        String supplied = System.getProperty("skillforge.test.runtime-classpath-file");
        if (supplied != null) { var value = Files.readString(Path.of(supplied)).trim(); assertFalse(value.isBlank()); return value; }
        var entries = new LinkedHashSet<String>();
        for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) if (!entry.isBlank()) entries.add(entry);
        for (ClassLoader loader = Thread.currentThread().getContextClassLoader(); loader != null; loader = loader.getParent())
            if (loader instanceof URLClassLoader urls) for (var url : urls.getURLs()) if ("file".equals(url.getProtocol())) entries.add(Path.of(url.toURI()).toString());
        assertFalse(entries.isEmpty()); return String.join(java.io.File.pathSeparator, entries);
    }

    private static String quote(String value) { return "\"" + value.replace('\\', '/').replace("\"", "\\\"") + "\""; }
    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private void write(Path target, Object value) throws Exception { Files.writeString(target, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW); }

    private static void await(String description, Duration limit, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + limit.toNanos();
        while (!condition.getAsBoolean()) { assertTrue(System.nanoTime() < until, description + " 超过观察上限"); Thread.sleep(100); }
    }

    /** 仅拥有当前测试创建的一个OS进程，退出code、PID、强杀动作和静止状态形成独立证据。 */
    private final class Child implements AutoCloseable {
        private final Process process; private final Path marker, committed, exit; private JsonNode ready;
        private boolean killed; private Integer exitCode;
        private Child(Process process, Path marker, Path committed, Path exit) { this.process = process; this.marker = marker; this.committed = committed; this.exit = exit; }
        private String baseUrl() { return "http://127.0.0.1:" + ready.path("port").asInt(); }
        private void kill() throws Exception {
            assertTrue(process.isAlive()); killed = true; process.destroyForcibly();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "强杀子JVM必须得到退出信号"); assertFalse(process.toHandle().isAlive());
            exitCode = process.exitValue(); assertNotEquals(0, exitCode); write(exit, facts());
        }
        private Map<String, Object> facts() {
            var data = new LinkedHashMap<String, Object>(); data.put("pid", process.pid()); data.put("alive", process.isAlive());
            data.put("destroyForcibly", killed); data.put("exitCode", exitCode); data.put("readyMarker", marker.toString()); return data;
        }
        @Override public void close() throws Exception {
            if (process.isAlive()) { process.destroy(); if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); assertTrue(process.waitFor(15, TimeUnit.SECONDS)); } }
            assertFalse(process.toHandle().isAlive()); exitCode = process.exitValue(); if (!Files.exists(exit)) write(exit, facts());
        }
    }

    /** 透明HTTP代理只放置派发/响应屏障；正文、工具调用及结果均来自真实Provider和产品。 */
    private static final class GateProxy implements AutoCloseable {
        private final HttpServer server; private final java.util.concurrent.ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        private final String endpoint; private final boolean holdResponse;
        private final AtomicInteger received = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1), beforeForward = new CountDownLatch(1), responseHeld = new CountDownLatch(1), returnResponse = new CountDownLatch(1);
        private GateProxy(String endpoint, boolean holdResponse) throws IOException {
            this.endpoint = endpoint; this.holdResponse = holdResponse;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(handlers); server.createContext("/chat/completions", this::forward);
        }
        private void start() { server.start(); }
        private String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        private void forward(HttpExchange exchange) throws IOException {
            try (exchange) {
                int ordinal = received.incrementAndGet(); byte[] body = exchange.getRequestBody().readAllBytes();
                if (ordinal == 1) { entered.countDown(); if (!beforeForward.await(40, TimeUnit.SECONDS)) throw new IOException("restart_provider_dispatch_gate_timeout"); }
                var builder = HttpRequest.newBuilder(URI.create(endpoint + "/chat/completions")).timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofByteArray(body));
                for (String name : List.of("Content-Type", "Authorization")) { var value = exchange.getRequestHeaders().getFirst(name); if (value != null) builder.header(name, value); }
                var actual = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (actual.statusCode() != 200) throw new IOException("restart_actual_provider_rejected:" + actual.statusCode());
                if (holdResponse && ordinal == 1) { responseHeld.countDown(); if (!returnResponse.await(60, TimeUnit.SECONDS)) throw new IOException("restart_provider_response_gate_timeout"); }
                actual.headers().firstValue("Content-Type").ifPresent(value -> exchange.getResponseHeaders().set("Content-Type", value));
                exchange.sendResponseHeaders(actual.statusCode(), actual.body().length); exchange.getResponseBody().write(actual.body());
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("restart_proxy_interrupted", interrupted); }
        }
        @Override public void close() throws InterruptedException {
            beforeForward.countDown(); returnResponse.countDown(); server.stop(0); handlers.shutdownNow();
            assertTrue(handlers.awaitTermination(10, TimeUnit.SECONDS), "本次代理线程必须静止"); client.close();
        }
    }
}
