package com.skillforge.runtime.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 每次命令拥有自己的进程树和输出采集器；仅终止保存的 ProcessHandle，不按名字全局杀进程。 */
final class DesktopCommandProcesses implements AutoCloseable {
    private static final int MAX_TIMEOUT_SECONDS = 600;
    private static final int MAX_COMMAND_CHARS = 32_768;
    private static final int MAX_STREAM_BYTES = 64_000;
    private static final int POLL_MILLIS = 25;
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(5);
    private static final Set<String> ENVIRONMENT_NAMES = Set.of(
            "PATH", "SYSTEMROOT", "WINDIR", "COMSPEC", "TEMP", "TMP", "TMPDIR", "HOME", "USER", "SHELL", "USERPROFILE",
            "APPDATA", "LOCALAPPDATA", "PATHEXT", "PSMODULEPATH", "LANG", "LC_ALL", "TERM",
            "SYSTEMDRIVE", "PROGRAMFILES", "PROGRAMFILES(X86)", "NUMBER_OF_PROCESSORS",
            "PROCESSOR_ARCHITECTURE");
    private final Path workspace;
    private final Object lifecycle = new Object();
    private final Map<String, RunningCommand> running = new ConcurrentHashMap<>();
    private boolean closed;

    DesktopCommandProcesses(Path workspace) {
        this.workspace = workspace;
    }

    static int defaultTimeoutSeconds() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac") ? 60 : 120;
    }

    DesktopTools.Result execute(JsonNode args, String operationId, BooleanSupplier cancelled) {
        String command = DesktopTools.requiredText(args, "command", MAX_COMMAND_CHARS, false);
        int timeoutSeconds = defaultTimeoutSeconds();
        if (args.has("timeoutSeconds")) {
            JsonNode timeout = args.get("timeoutSeconds");
            if (!timeout.isIntegralNumber() || !timeout.canConvertToInt()
                    || timeout.intValue() < 1 || timeout.intValue() > MAX_TIMEOUT_SECONDS) {
                throw new DesktopTools.ToolFailure("INVALID_ARGUMENT", "timeoutSeconds must be an integer from 1 to 600.");
            }
            timeoutSeconds = timeout.intValue();
        }
        RunningCommand execution;
        try {
            execution = new RunningCommand(command);
        } catch (IOException failure) {
            return DesktopTools.failure("COMMAND_START_FAILED", "The command supervisor could not be prepared.");
        }
        List<String> shell = execution.launchCommand();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        synchronized (lifecycle) {
            if (closed || cancelled.getAsBoolean()) {
                execution.releaseFiles();
                return DesktopTools.failure("CANCELLED_BEFORE_DISPATCH", "The command was not started.");
            }
            if (running.putIfAbsent(operationId, execution) != null) {
                execution.releaseFiles();
                return DesktopTools.failure("OPERATION_ALREADY_RUNNING", "This command operation is already running.");
            }
            try {
                ProcessBuilder builder = new ProcessBuilder(shell);
                builder.directory(workspace.toFile());
                // 模型凭据只在宿主内存；命令只继承明确列出的基础环境。
                Map<String, String> environment = builder.environment();
                environment.keySet().removeIf(key -> !ENVIRONMENT_NAMES.contains(key.toUpperCase(Locale.ROOT)));
                execution.supervisor.configureEnvironment(environment);
                execution.process = builder.start();
                execution.captureTree();
            } catch (IOException failure) {
                running.remove(operationId, execution);
                execution.releaseFiles();
                return DesktopTools.failure("COMMAND_START_FAILED", "The local shell could not start: " + failure.getClass().getSimpleName());
            }
        }

        ThreadPoolExecutor readers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2), Thread.ofPlatform().daemon().name("desktop-command-output-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        Future<CapturedOutput> stdout = readers.submit(() -> collect(execution.process.getInputStream()));
        Future<CapturedOutput> stderr = readers.submit(() -> collect(execution.process.getErrorStream()));
        String interruption = null;
        boolean treeStopped = false;
        boolean interrupted = false;
        try {
            execution.process.getOutputStream().close();
            while (true) {
                execution.captureTree();
                if (cancelled.getAsBoolean() || execution.cancelled.get()) {
                    interruption = "COMMAND_CANCELLED";
                    break;
                }
                if (System.nanoTime() >= deadline) {
                    interruption = "COMMAND_TIMEOUT";
                    break;
                }
                if (execution.process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    execution.captureTree();
                    break;
                }
            }
            treeStopped = execution.stopTree();
            CapturedOutput out = await(stdout);
            CapturedOutput err = await(stderr);
            boolean streamsComplete = out.complete() && err.complete();
            ObjectNode result = DesktopTools.object().put("operationId", operationId)
                    .put("cwd", workspace.toString()).put("stdout", out.text()).put("stderr", err.text())
                    .put("stdoutTruncated", out.truncated()).put("stderrTruncated", err.truncated())
                    .put("outputEncoding", "UTF-8").put("stdoutDecodingErrors", out.decodingErrors())
                    .put("stderrDecodingErrors", err.decodingErrors())
                    .put("outputComplete", streamsComplete).put("processTreeStopped", treeStopped)
                    .put("processTracking", execution.supervisor.tracking());
            if (!execution.process.isAlive()) {
                result.put("exitCode", execution.process.exitValue());
            }
            // close/cancel 可能在 waitFor 内完成监督清理；退出回执不能越过已发生的取消。
            if (interruption == null && (cancelled.getAsBoolean() || execution.cancelled.get())) {
                interruption = "COMMAND_CANCELLED";
            }
            if (interruption != null || !treeStopped || !streamsComplete) {
                result.put("code", interruption == null ? "COMMAND_CLEANUP_UNCONFIRMED" : interruption)
                        .put("message", "The command may have produced effects. Inspect the workspace before retrying.");
                return DesktopTools.result(DesktopTools.Status.UNKNOWN, result);
            }
            if (execution.process.exitValue() != 0) {
                result.put("code", "COMMAND_EXIT_NONZERO");
                return DesktopTools.result(DesktopTools.Status.FAILED, result);
            }
            return DesktopTools.success(result);
        } catch (InterruptedException failure) {
            interrupted = true;
            return DesktopTools.result(DesktopTools.Status.UNKNOWN,
                    DesktopTools.error("COMMAND_INTERRUPTED", "Execution was interrupted; inspect effects before retrying."));
        } catch (IOException failure) {
            return DesktopTools.result(DesktopTools.Status.UNKNOWN,
                    DesktopTools.error("COMMAND_IO_FAILED", "The started command did not produce a complete receipt."));
        } finally {
            if (!treeStopped) {
                execution.stopTree();
            }
            closeInput(execution.process.getInputStream());
            closeInput(execution.process.getErrorStream());
            readers.shutdownNow();
            try {
                if (!readers.awaitTermination(CLEANUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    execution.cancelled.set(true);
                }
            } catch (InterruptedException failure) {
                interrupted = true;
            }
            running.remove(operationId, execution);
            execution.releaseFiles();
            synchronized (execution) {
                execution.finished = true;
                execution.notifyAll();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void cancel(String operationId) {
        RunningCommand execution = running.get(operationId);
        if (execution != null) {
            execution.cancelled.set(true);
        }
    }

    @Override
    public void close() {
        List<RunningCommand> active;
        synchronized (lifecycle) {
            closed = true;
            active = List.copyOf(running.values());
            active.forEach(command -> command.cancelled.set(true));
        }
        boolean interrupted = false;
        for (RunningCommand command : active) {
            command.stopTree();
            long deadline = System.nanoTime() + CLEANUP_TIMEOUT.toNanos();
            synchronized (command) {
                while (!command.finished && System.nanoTime() < deadline) {
                    try {
                        TimeUnit.NANOSECONDS.timedWait(command, Math.max(1, deadline - System.nanoTime()));
                    } catch (InterruptedException failure) {
                        interrupted = true;
                        break;
                    }
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static CapturedOutput collect(InputStream stream) throws IOException {
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        boolean truncated = false;
        int count;
        while ((count = stream.read(buffer)) != -1) {
            int retain = Math.min(count, MAX_STREAM_BYTES - kept.size());
            if (retain > 0) {
                kept.write(buffer, 0, retain);
            }
            truncated |= retain < count;
        }
        byte[] bytes = kept.toByteArray();
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer decoded = CharBuffer.allocate(bytes.length);
        var decoding = decoder.decode(ByteBuffer.wrap(bytes), decoded, !truncated);
        boolean decodingErrors = decoding.isError();
        String text = decodingErrors ? new String(bytes, StandardCharsets.UTF_8) : decoded.flip().toString();
        return new CapturedOutput(text, truncated, true, decodingErrors);
    }

    private static CapturedOutput await(Future<CapturedOutput> output) throws InterruptedException {
        try {
            return output.get(CLEANUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException failure) {
            return new CapturedOutput("", false, false, false);
        }
    }

    private static void closeInput(InputStream stream) {
        try {
            stream.close();
        } catch (IOException failure) {
            // 进程已停止时关闭管道仍可能失败；完整性已由采集结果显式表达。
        }
    }

    private record CapturedOutput(String text, boolean truncated, boolean complete, boolean decodingErrors) { }

    private static final class RunningCommand {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Map<Long, ProcessHandle> descendants = new HashMap<>();
        private final Supervisor supervisor;
        private Process process;
        private boolean finished;

        RunningCommand(String command) throws IOException {
            supervisor = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                    ? new DesktopWindowsCommandJob(command) : new DesktopPosixCommandGroup(command);
        }

        List<String> launchCommand() {
            return supervisor.launchCommand();
        }

        void releaseFiles() {
            try {
                supervisor.close();
            } catch (IOException failure) {
                // 临时文件释放失败不会伪造进程清理回执。
                System.err.println("event=desktop_command_private_files_cleanup result=failed cause="
                        + failure.getClass().getSimpleName());
            }
        }

        synchronized void captureTree() {
            if (process != null) {
                process.descendants().forEach(child -> descendants.putIfAbsent(child.pid(), child));
            }
        }

        synchronized boolean stopTree() {
            if (process == null) {
                return true;
            }
            try {
                if (process.isAlive()) {
                    supervisor.requestStop();
                }
                if (process.waitFor(CLEANUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    return supervisor.cleanupConfirmed(process.exitValue());
                }
            } catch (IOException failure) {
                // 信号失败后仍清理拥有的进程，缺失监督回执时保持未知。
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
            captureTree();
            // 先停止 shell，防止继续派发；随后只处理已观察且仍存活的所属后代。
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            List<ProcessHandle> owned = new ArrayList<>(descendants.values());
            owned.sort(Comparator.comparingLong(ProcessHandle::pid).reversed());
            owned.forEach(child -> {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            });
            long deadline = System.nanoTime() + CLEANUP_TIMEOUT.toNanos();
            for (ProcessHandle handle : owned) {
                if (!awaitExit(handle, deadline)) {
                    return false;
                }
            }
            awaitExit(process.toHandle(), deadline);
            // 缺失 OS 归属回执时不能用进程枚举结果冒充完整清理。
            return false;
        }

        private static boolean awaitExit(ProcessHandle handle, long deadline) {
            if (!handle.isAlive()) {
                return true;
            }
            try {
                handle.onExit().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                return !handle.isAlive();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException | TimeoutException failure) {
                return false;
            }
        }
    }

    interface Supervisor extends AutoCloseable {
        List<String> launchCommand();
        void requestStop() throws IOException;
        boolean cleanupConfirmed(int supervisorExitCode);
        String tracking();
        default void configureEnvironment(Map<String, String> environment) { }
        @Override
        void close() throws IOException;
    }
}
