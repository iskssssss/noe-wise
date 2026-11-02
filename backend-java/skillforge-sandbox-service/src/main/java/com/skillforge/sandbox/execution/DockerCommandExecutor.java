package com.skillforge.sandbox.execution;

import com.skillforge.sandbox.config.SandboxProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 只以参数数组调用 Docker CLI，控制命令输出也设置硬上限。 */
@Component
public class DockerCommandExecutor {

    private static final int CONTROL_OUTPUT_LIMIT = 64 * 1024;

    private final String dockerBinary;

    public DockerCommandExecutor(SandboxProperties properties) {
        this.dockerBinary = properties.dockerBinary();
    }

    public CommandResult run(List<String> arguments, Duration timeout) {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(dockerBinary);
        command.addAll(arguments);
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException failure) {
            return new CommandResult(-1, "", safeMessage(failure), false);
        }
        BoundedCapture stdout = new BoundedCapture(CONTROL_OUTPUT_LIMIT);
        BoundedCapture stderr = new BoundedCapture(CONTROL_OUTPUT_LIMIT);
        try (var drains = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> stdoutDrain = drains.submit(() -> drain(stdout, process.getInputStream()));
            Future<?> stderrDrain = drains.submit(() -> drain(stderr, process.getErrorStream()));
            boolean exited = process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            await(stdoutDrain);
            await(stderrDrain);
            return new CommandResult(exited ? process.exitValue() : -1, stdout.text(), stderr.text(), !exited);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new CommandResult(-1, stdout.text(), "Docker 控制命令被中断", true);
        }
    }

    Process start(List<String> arguments) throws IOException {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(dockerBinary);
        command.addAll(arguments);
        return new ProcessBuilder(command).start();
    }

    private void drain(BoundedCapture target, java.io.InputStream input) {
        try {
            target.drain(input);
        } catch (IOException ignored) {
            // 进程被强制停止时管道关闭，退出状态仍由调用者单独判断。
        }
    }

    private void await(Future<?> future) throws InterruptedException {
        try {
            future.get();
        } catch (ExecutionException ignored) {
            // drain 已将 IOException 转为受控结果。
        }
    }

    private String safeMessage(IOException failure) {
        String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName() : message.replaceAll("[\\r\\n\\p{Cntrl}]", " ");
    }

    public record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean succeeded() {
            return exitCode == 0 && !timedOut;
        }
    }
}
