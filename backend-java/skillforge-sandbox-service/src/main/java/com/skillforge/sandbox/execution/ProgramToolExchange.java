package com.skillforge.sandbox.execution;

import com.skillforge.execution.ExecutionLimits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.sandbox.api.SandboxConflictException;
import com.skillforge.sandbox.api.SandboxRequestException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在任务私有共享卷上交换有界工具消息。目录内容始终按不跟随链接方式读取，响应使用同目录原子改名冻结。
 */
@Component
public class ProgramToolExchange {

    static final int MAXIMUM_CALLS = ExecutionLimits.PROGRAM_CALLS;
    static final int MAXIMUM_MESSAGE_BYTES = ExecutionLimits.PROGRAM_MESSAGE_BYTES;
    static final int MAXIMUM_HANDLE_LENGTH = 128;
    private static final Pattern REQUEST = Pattern.compile("request-([0-9]+)\\.json");
    private static final Pattern RESPONSE = Pattern.compile("response-([0-9]+)\\.json");
    private static final Pattern TEMPORARY = Pattern.compile("\\.(?:request|response)-([0-9]+)-[0-9a-fA-F-]+\\.tmp");
    private static final Set<PosixFilePermission> READ_ONLY_FILE = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);

    private final ObjectMapper json;
    private final TaskWorkspace workspace;

    public ProgramToolExchange(ObjectMapper json, TaskWorkspace workspace) {
        this.json = json;
        this.workspace = workspace;
    }

    public List<ToolCall> pending(String taskId) {
        Path directory = programDirectory(taskId, false);
        if (directory == null) {
            return List.of();
        }
        Map<Integer, Path> requests = new HashMap<>();
        Set<Integer> responses = new HashSet<>();
        try (var files = Files.list(directory)) {
            var entries = files.limit(MAXIMUM_CALLS * 3L + 1).toList();
            if (entries.size() > MAXIMUM_CALLS * 3) throw invalidMessage("program 消息文件数量超限");
            for (Path file : entries) {
                BasicFileAttributes attributes = regularFile(file);
                String name = file.getFileName().toString();
                Matcher request = REQUEST.matcher(name);
                Matcher response = RESPONSE.matcher(name);
                if (request.matches()) {
                    int ordinal = ordinal(request.group(1));
                    ensureMessageSize(attributes, name);
                    requests.put(ordinal, file);
                } else if (response.matches()) {
                    int ordinal = ordinal(response.group(1));
                    ensureMessageSize(attributes, name);
                    validateResult(readJson(file, name));
                    responses.add(ordinal);
                } else {
                    Matcher temporary = TEMPORARY.matcher(name);
                    if (temporary.matches()) {
                        ordinal(temporary.group(1));
                    } else {
                        throw invalidMessage("program 目录包含非法文件");
                    }
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取 program 工具消息", failure);
        }

        int maximumOrdinal = requests.keySet().stream().max(Integer::compareTo).orElse(0);
        for (int expected = 1; expected <= maximumOrdinal; expected++) {
            if (!requests.containsKey(expected)) {
                throw invalidMessage("工具请求 ordinal 不连续");
            }
            if (expected > 1 && !responses.contains(expected - 1)) {
                throw invalidMessage("前一工具请求尚未响应，拒绝并发工具调用");
            }
        }
        if (!requests.keySet().containsAll(responses)) {
            throw invalidMessage("工具响应没有对应请求");
        }

        List<ToolCall> pending = new ArrayList<>();
        requests.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ToolCall call = readRequest(entry.getValue(), entry.getKey());
            if (!responses.contains(entry.getKey())) {
                pending.add(call);
            }
        });
        return List.copyOf(pending);
    }

    public synchronized void complete(String taskId, int ordinal, JsonNode result) {
        if (ordinal < 1 || ordinal > MAXIMUM_CALLS) {
            throw new SandboxRequestException("INVALID_TOOL_ORDINAL", "ordinal 必须位于 1.." + MAXIMUM_CALLS);
        }
        validateResult(result);
        Path directory = programDirectory(taskId, true);
        Path request = directory.resolve("request-" + ordinal + ".json");
        if (!Files.exists(request, LinkOption.NOFOLLOW_LINKS)) {
            throw new SandboxConflictException("TOOL_CALL_NOT_PENDING", "工具请求不存在或尚未提交");
        }
        readRequest(request, ordinal);

        byte[] encoded = writeJson(result);
        if (encoded.length > MAXIMUM_MESSAGE_BYTES) {
            throw new SandboxRequestException("TOOL_MESSAGE_TOO_LARGE", "单条工具消息不得超过 64 KiB");
        }
        Path response = directory.resolve("response-" + ordinal + ".json");
        if (Files.exists(response, LinkOption.NOFOLLOW_LINKS)) {
            acceptSameResponse(response, result);
            return;
        }
        if (ordinal > 1) {
            Path previous = directory.resolve("response-" + (ordinal - 1) + ".json");
            if (!Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                throw new SandboxConflictException("TOOL_CALL_NOT_PENDING", "前一工具请求尚未响应");
            }
            validateResult(readJson(previous, previous.getFileName().toString()));
        }

        Path temporary = directory.resolve(".response-" + ordinal + "-" + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, encoded, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.move(temporary, response, StandardCopyOption.ATOMIC_MOVE);
            setReadOnly(response);
        } catch (FileAlreadyExistsException raced) {
            acceptSameResponse(response, result);
        } catch (IOException failure) {
            throw new IllegalStateException("无法冻结 program 工具响应", failure);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // 临时文件名不会被容器消费，后续读取仅把合法临时名视为未提交消息。
            }
        }
    }

    private Path programDirectory(String taskId, boolean required) {
        Path directory = workspace.program(taskId);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (!required) {
                return null;
            }
            throw new SandboxConflictException("TOOL_CALL_NOT_PENDING", "program 工具目录尚未就绪");
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                throw invalidMessage("program 路径不是安全目录");
            }
            return directory;
        } catch (IOException failure) {
            throw new IllegalStateException("无法检查 program 工具目录", failure);
        }
    }

    private ToolCall readRequest(Path file, int expectedOrdinal) {
        JsonNode message = readJson(file, file.getFileName().toString());
        if (!message.isObject() || message.size() != 3
                || !message.has("ordinal") || !message.has("handle") || !message.has("arguments")) {
            throw invalidMessage("工具请求字段非法");
        }
        JsonNode ordinal = message.get("ordinal");
        JsonNode handle = message.get("handle");
        JsonNode arguments = message.get("arguments");
        if (!ordinal.canConvertToInt() || ordinal.intValue() != expectedOrdinal
                || !handle.isTextual() || !validHandle(handle.textValue()) || !arguments.isObject()) {
            throw invalidMessage("工具请求内容非法");
        }
        return new ToolCall(expectedOrdinal, handle.textValue(), arguments.deepCopy());
    }

    private boolean validHandle(String handle) {
        if (handle.isBlank() || handle.codePointCount(0, handle.length()) > MAXIMUM_HANDLE_LENGTH) {
            return false;
        }
        return handle.codePoints().noneMatch(value -> Character.isISOControl(value));
    }

    private void validateResult(JsonNode result) {
        if (result == null || !result.isObject()) {
            throw new SandboxRequestException("INVALID_TOOL_RESULT", "result 必须是对象");
        }
    }

    private void acceptSameResponse(Path response, JsonNode expected) {
        JsonNode existing = readJson(response, response.getFileName().toString());
        validateResult(existing);
        if (!existing.equals(expected)) {
            throw new SandboxConflictException("TOOL_RESULT_ALREADY_SET", "工具结果已冻结为不同值");
        }
    }

    private JsonNode readJson(Path file, String name) {
        byte[] bytes = readBounded(file, name);
        try {
            return json.readTree(bytes);
        } catch (IOException invalid) {
            throw invalidMessage("工具消息不是合法 JSON");
        }
    }

    private byte[] readBounded(Path file, String name) {
        BasicFileAttributes attributes = regularFile(file);
        ensureMessageSize(attributes, name);
        try (var input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAXIMUM_MESSAGE_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAXIMUM_MESSAGE_BYTES) {
                throw invalidMessage("工具消息大小非法");
            }
            return bytes;
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取 program 工具消息", failure);
        }
    }

    private BasicFileAttributes regularFile(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw invalidMessage("program 目录只允许普通消息文件");
            }
            return attributes;
        } catch (IOException failure) {
            throw new IllegalStateException("无法检查 program 工具消息", failure);
        }
    }

    private void ensureMessageSize(BasicFileAttributes attributes, String name) {
        if (attributes.size() <= 0 || attributes.size() > MAXIMUM_MESSAGE_BYTES) {
            throw invalidMessage("工具消息大小非法: " + name);
        }
    }

    private int ordinal(String value) {
        try {
            int ordinal = Integer.parseInt(value);
            if (ordinal < 1 || ordinal > MAXIMUM_CALLS || !value.equals(Integer.toString(ordinal))) {
                throw invalidMessage("工具消息 ordinal 必须使用 1.." + MAXIMUM_CALLS + " 标准十进制");
            }
            return ordinal;
        } catch (NumberFormatException invalid) {
            throw invalidMessage("工具消息 ordinal 非法");
        }
    }

    private byte[] writeJson(JsonNode value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (IOException failure) {
            throw new IllegalStateException("无法序列化 program 工具响应", failure);
        }
    }

    private void setReadOnly(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, READ_ONLY_FILE);
        } catch (UnsupportedOperationException ignored) {
            file.toFile().setReadOnly();
        }
    }

    private SandboxConflictException invalidMessage(String message) {
        return new SandboxConflictException("INVALID_TOOL_MESSAGE", message);
    }

    public record ToolCall(int ordinal, String handle, JsonNode arguments) {
    }
}
