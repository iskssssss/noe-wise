package com.skillforge.runtime.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** POSIX 监督进程先建立独立进程组，再执行命令；清理证明只涵盖此进程组。 */
final class DesktopPosixCommandGroup implements DesktopCommandProcesses.Supervisor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCRIPT_FILE = "supervisor.cjs";
    private static final String COMMAND_FILE = "command.txt";
    private static final String CANCEL_FILE = "cancel";
    private static final String RECEIPT_FILE = "receipt.json";
    private final Path directory;
    private final String nonce = UUID.randomUUID().toString();

    DesktopPosixCommandGroup(String command) throws IOException {
        directory = Files.createTempDirectory("noewise-command-").toRealPath();
        try {
            Files.writeString(directory.resolve(COMMAND_FILE), command, StandardCharsets.UTF_8);
            try (InputStream resource = DesktopPosixCommandGroup.class.getResourceAsStream("/desktop/command-supervisor.cjs")) {
                if (resource == null) {
                    throw new IOException("The packaged POSIX command supervisor resource is missing.");
                }
                Files.copy(resource, directory.resolve(SCRIPT_FILE));
            }
        } catch (IOException failure) {
            try {
                close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Override
    public List<String> launchCommand() {
        String executable = System.getenv("NOEWISE_NODE_EXECUTABLE");
        if (executable == null || executable.isBlank()) {
            executable = "node";
        }
        return List.of(executable, directory.resolve(SCRIPT_FILE).toString(), directory.toString(), nonce);
    }

    @Override
    public void configureEnvironment(Map<String, String> environment) {
        // 打包后复用 Electron 的 Node 模式；业务子进程的环境由脚本重新限制。
        environment.put("ELECTRON_RUN_AS_NODE", "1");
    }

    @Override
    public void requestStop() throws IOException {
        Files.writeString(directory.resolve(CANCEL_FILE), nonce, StandardCharsets.UTF_8);
    }

    @Override
    public boolean cleanupConfirmed(int supervisorExitCode) {
        try {
            Path receipt = directory.resolve(RECEIPT_FILE);
            if (!Files.isRegularFile(receipt) || Files.size(receipt) > 2048) {
                return false;
            }
            JsonNode json = JSON.readTree(Files.readString(receipt, StandardCharsets.UTF_8));
            return json != null && json.isObject() && nonce.equals(json.path("nonce").asText())
                    && json.path("processGroupGone").isBoolean() && json.path("processGroupGone").booleanValue()
                    && json.path("exitCode").isIntegralNumber() && json.path("exitCode").intValue() == supervisorExitCode
                    && json.path("started").isBoolean();
        } catch (IOException failure) {
            return false;
        }
    }

    @Override
    public String tracking() {
        return "posix_process_group";
    }

    @Override
    public void close() throws IOException {
        for (String name : List.of(SCRIPT_FILE, COMMAND_FILE, CANCEL_FILE, RECEIPT_FILE)) {
            Files.deleteIfExists(directory.resolve(name));
        }
        Files.deleteIfExists(directory);
    }
}
