package com.skillforge.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.execution.browser.BrowserParameters;
import com.skillforge.execution.browser.BrowserReceipt;
import com.skillforge.execution.browser.BrowserRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** wait-ms 的真实受理与授权链，执行器只捕获 argv，不启动子进程或连接设备。 */
class BrowserWaitMsMappingTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();

    /** 只提供本次等待所需的授权、绑定与结果；任何其他业务命令都会使测试失败。 */
    private static final class CapturingEngine extends BrowserEngine {
        final List<List<String>> calls = new ArrayList<>();
        final List<String> ownedSessions = new ArrayList<>();
        boolean granted = true;
        boolean bindingPresent = true;
        int daemonRequests;
        private final ObjectMapper fixture = new ObjectMapper();

        /** 数据全部写入 JUnit 私有临时目录，不复用旧任务或 Browser home。 */
        CapturingEngine(Path root) throws Exception {
            super(root.toString(), "unused-cli", "wss://fixture.invalid", new ObjectMapper().findAndRegisterModules());
        }

        /** 截断 daemon 启动边界，仅记真实受理链请求次数。 */
        @Override synchronized int daemon(Path home, Instant deadline) throws Exception {
            Files.createDirectories(home);
            daemonRequests++;
            return 52800;
        }

        /** 捕获正式参数，按固定上游结构提供响应，不调用 ProcessBuilder。 */
        @Override JsonNode run(Path home, List<String> args, int seconds) throws Exception {
            calls.add(List.copyOf(args));
            if (args.equals(List.of("--json", "daemon", "devices"))) {
                return fixture.readTree(granted ? "[{\"browser_id\":\"owned-browser\",\"expires_at\":\"2100-01-01T00:00:00Z\"}]" : "[]");
            }
            if (args.equals(List.of("--json", "session", "list"))) {
                return fixture.readTree(bindingPresent
                        ? "[{\"session_id\":\"owned-cli-session\",\"browser_instance_id\":\"owned-browser\",\"agent_window_id\":7}]" : "[]");
            }
            assertEquals(List.of("--json", "wait-ms", "120"), args, "Only the authorized wait command may reach this executor");
            return fixture.createObjectNode().put("waited_ms", 120);
        }

        /** 真实 finish 必须显式传入冻结绑定，而不能从不存在的 CLI flag 推导归属。 */
        @Override JsonNode runInSession(Path home, List<String> args, int seconds, String session) throws Exception {
            ownedSessions.add(session);
            return run(home, args, seconds);
        }

        /** 使用正式持久任务结构给出合法基线，授权仍由真实 BrowserEngine 判定。 */
        BrowserReceipt active() throws Exception {
            var task = new BrowserReceipt(null, "fixture-start", "start", UUID.randomUUID().toString(),
                    "owned-session", "owned-run", Instant.now().toString(), "active", null, "owned-browser",
                    new BrowserReceipt.BrowserBinding("owned-cli-session", "owned-browser", 7L), null);
            write(home("1", "2").resolve("tasks").resolve(task.task() + ".json"), task);
            return task;
        }
    }

    /** 成功等待仍绑定原任务/会话/Run，原 invocation 再读不产生第二次执行。 */
    @Test void authorizedWaitCapturesLegalArgvAndRetainsOwnedReceiptWithoutReplay() throws Exception {
        var engine = new CapturingEngine(root);
        var task = engine.active();
        var request = waitRequest("wait-owned", task.task(), "2");
        var result = engine.execute(request);
        assertEquals("succeeded", result.status());
        assertEquals(120, result.result().path("waited_ms").asLong());
        assertEquals(task.task(), result.task());
        assertEquals(task.binding(), result.binding());
        assertEquals("owned-session", result.session());
        assertEquals("owned-run", result.run());
        assertEquals(List.of(List.of("--json", "daemon", "devices"), List.of("--json", "session", "list"),
                List.of("--json", "daemon", "devices"), List.of("--json", "wait-ms", "120")), engine.calls);
        int calls = engine.calls.size();
        assertEquals(json.readTree(json.writeValueAsBytes(result)), json.readTree(json.writeValueAsBytes(engine.execute(request))));
        assertEquals(calls, engine.calls.size());
        assertEquals(1, engine.daemonRequests);
        assertEquals(List.of("owned-cli-session"), engine.ownedSessions);
    }

    /** 去掉 CLI session flag 后，缺任务、跨个人和非活动任务仍在启动边界之前拒绝。 */
    @Test void waitStillRejectsInvalidMissingForeignAndInactiveTasksBeforeExecutor() throws Exception {
        var engine = new CapturingEngine(root);
        var active = engine.active();
        var inactive = engine.active();
        engine.write(engine.home("1", "2").resolve("tasks").resolve(inactive.task() + ".json"), inactive.state("stopped", null));
        List<BrowserRequest> requests = List.of(waitRequest("invalid", "not-a-task", "2"),
                waitRequest("missing", UUID.randomUUID().toString(), "2"), waitRequest("foreign", active.task(), "3"),
                waitRequest("inactive", inactive.task(), "2"));
        List<String> errors = List.of("browser_task_invalid", "browser_task_not_found", "browser_task_not_found", "browser_task_not_active");
        for (int i = 0; i < requests.size(); i++) {
            var rejected = engine.execute(requests.get(i));
            assertEquals("rejected", rejected.status());
            assertEquals(errors.get(i), rejected.result().path("error").asText());
        }
        assertEquals(0, engine.daemonRequests);
        assertTrue(engine.calls.isEmpty());
        assertTrue(engine.ownedSessions.isEmpty());
    }

    /** 同属个人目录并不代替当前授权或完整冻结绑定，拒绝后不能执行等待。 */
    @Test void waitStillRequiresCurrentBrowserGrantAndExactSessionBinding() throws Exception {
        for (boolean revoke : List.of(true, false)) {
            var engine = new CapturingEngine(root.resolve(revoke ? "revoked" : "binding-lost"));
            var task = engine.active();
            engine.granted = !revoke;
            engine.bindingPresent = revoke;
            var result = engine.execute(waitRequest("denied-wait", task.task(), "2"));
            assertEquals(revoke ? "failed" : "rejected", result.status());
            assertEquals(revoke ? "browser_not_authorized" : "browser_task_binding_lost", result.result().path("error").asText());
            assertTrue(engine.calls.stream().noneMatch(args -> args.contains("wait-ms")));
            assertEquals(task.binding(), result.binding());
            assertTrue(engine.ownedSessions.isEmpty());
        }
    }

    /** 直接消费生产 key 与取消逻辑：等待只关闭原会话子进程，其他会话和 owner control 保持活动。 */
    @Test void waitProcessOwnershipSupportsExactSessionCancellationWithoutTouchingOtherCommands() throws Exception {
        var engine = new CapturingEngine(root);
        var task = engine.active();
        Path home = engine.home("1", "2");
        var keyMethod = BrowserEngine.class.getDeclaredMethod("commandKey", Path.class, List.class, String.class);
        keyMethod.setAccessible(true);
        var args = List.of("--json", "wait-ms", "120");
        String ownedKey = (String) keyMethod.invoke(engine, home, args, task.binding().sessionId());
        String otherKey = (String) keyMethod.invoke(engine, home, args, "other-cli-session");
        String controlKey = (String) keyMethod.invoke(engine, home, args, null);
        assertEquals(home + ":owned-cli-session", ownedKey);
        assertEquals(home + ":other-cli-session", otherKey);
        assertEquals(home + ":control", controlKey);
        var owned = new CapturedProcess();
        var other = new CapturedProcess();
        var control = new CapturedProcess();
        var commandsField = BrowserEngine.class.getDeclaredField("activeCommands");
        commandsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var commands = (Map<String, Set<Process>>) commandsField.get(engine);
        commands.put(ownedKey, Set.of(owned));
        commands.put(otherKey, Set.of(other));
        commands.put(controlKey, Set.of(control));
        var interrupt = BrowserEngine.class.getDeclaredMethod("interruptOwned", Path.class, BrowserReceipt.class);
        interrupt.setAccessible(true);
        interrupt.invoke(engine, home, task);
        assertTrue(owned.stdinClosed);
        assertFalse(other.stdinClosed);
        assertFalse(control.stdinClosed);
        assertEquals(0, owned.forcedStops + other.forcedStops + control.forcedStops);
        assertTrue(engine.calls.isEmpty());
    }

    /** 纯内存 Process 只观察精确 EOF 关闭，没有操作系统子进程或真实 daemon。 */
    private static final class CapturedProcess extends Process {
        boolean stdinClosed;
        int forcedStops;
        private final java.io.OutputStream stdin = new java.io.OutputStream() {
            @Override public void write(int value) { }
            @Override public void close() { stdinClosed = true; }
        };

        /** 返回可观察 EOF 的内存 stdin。 */
        @Override public java.io.OutputStream getOutputStream() { return stdin; }
        /** 本夹具没有 stdout，取消路径也不读取内容。 */
        @Override public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
        /** 本夹具没有 stderr。 */
        @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
        /** EOF 表示此内存命令已静止，不需要固定等待。 */
        @Override public int waitFor() { return 0; }
        /** 本夹具只表达正常取消退出。 */
        @Override public int exitValue() { return 0; }
        /** 清理只能改变此内存夹具。 */
        @Override public void destroy() { stdinClosed = true; }
        /** 记录强制终止，以便断言正常 EOF 取消未退化。 */
        @Override public Process destroyForcibly() { forcedStops++; stdinClosed = true; return this; }
        /** stdin EOF 是可观察的静止条件。 */
        @Override public boolean isAlive() { return !stdinClosed; }
    }

    /** 每个用例只使用新 invocation，身份来自可信信封，不进入模型参数。 */
    private BrowserRequest waitRequest(String invocation, String task, String user) {
        return new BrowserRequest("1", user, invocation, task, "wait-ms", new BrowserParameters.WaitMsParameters(120L),
                "owned-session", "owned-run", Instant.now().plusSeconds(120));
    }
}
