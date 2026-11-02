package com.skillforge.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.execution.browser.BrowserConnectionGrant;
import com.skillforge.execution.browser.BrowserConnectionGrant.Status;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 个人连接请求的配对摘要账本；只按原 Bearer 摘要关联，不从设备目录推断因果。 */
final class BrowserConnectionGrantStore {
    private final ObjectMapper json;
    private final Map<Path, Object> locks = new ConcurrentHashMap<>();

    /** 摘要绑定与状态原子替换；owner 由所在个人目录确定，不保存原凭据。 */
    record Binding(String requestId, String tokenDigest, Status status, String deviceId) {
        BrowserConnectionGrant view() { return new BrowserConnectionGrant(requestId, status, deviceId); }
    }

    BrowserConnectionGrantStore(ObjectMapper json) { this.json = json; }

    /** UUID 只用作连接请求身份，不接受能改变目录边界的路径。 */
    static String requestId(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("browser_connection_request_invalid");
        return UUID.fromString(value).toString();
    }

    private Path directory(Path home) { return home.resolve("connection-grants"); }
    private Path file(Path home, String id) { return directory(home).resolve(id + ".json"); }
    private Object lock(Path home) { return locks.computeIfAbsent(home, ignored -> new Object()); }

    /** 在配对串受控交付前保存摘要；同一个请求不能重新签发另一个配对意图。 */
    void bind(Path home, String requestId, String tokenDigest) throws IOException {
        String id = requestId(requestId);
        synchronized (lock(home)) {
            if (Files.exists(file(home, id))) throw new IllegalArgumentException("browser_connection_request_already_bound");
            write(file(home, id), new Binding(id, tokenDigest, Status.WAITING, null));
        }
    }

    /** 官方请求发出前持久化不确定窗口，确保成功关联写入失败时仍可观察 UNKNOWN。 */
    String beginExchange(Path home, String tokenDigest) throws IOException {
        synchronized (lock(home)) {
            Path directory = directory(home);
            if (!Files.exists(directory)) return null;
            try (var paths = Files.list(directory)) {
                for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                    Binding binding = read(path);
                    if (!binding.tokenDigest().equals(tokenDigest)) continue;
                    if (binding.status() != Status.PAIRED)
                        write(path, new Binding(binding.requestId(), binding.tokenDigest(), Status.UNKNOWN, null));
                    return binding.requestId();
                }
            }
            return null;
        }
    }

    /** 只接受官方响应的准确设备身份；重复同一成功结果幂等，冲突身份不覆盖既有授权。 */
    void finishExchange(Path home, String requestId, Integer status, String deviceId) throws IOException {
        synchronized (lock(home)) {
            Path path = file(home, requestId(requestId));
            Binding binding = read(path);
            boolean successful = status != null && status >= 200 && status < 300;
            if (binding.status() == Status.PAIRED) {
                if (!successful || binding.deviceId().equals(deviceId)) return;
                write(path, new Binding(binding.requestId(), binding.tokenDigest(), Status.UNKNOWN, null));
                return;
            }
            // 后续拒绝不能证明前一次未确认交换没有成功；不把 UNKNOWN 倒退为 WAITING。
            Status next = successful && deviceId != null && !deviceId.isBlank() ? Status.PAIRED : Status.UNKNOWN;
            write(path, new Binding(binding.requestId(), binding.tokenDigest(), next, next == Status.PAIRED ? deviceId : null));
        }
    }

    /** 查询只读当前 owner 的请求，不启动 daemon，也不扫描其他个人目录。 */
    BrowserConnectionGrant detail(Path home, String requestId) throws IOException {
        String id = requestId(requestId);
        synchronized (lock(home)) {
            Path path = file(home, id);
            return Files.exists(path) ? read(path).view() : new BrowserConnectionGrant(id, Status.NOT_FOUND, null);
        }
    }

    private Binding read(Path path) throws IOException { return json.readValue(Files.readAllBytes(path), Binding.class); }

    private void write(Path path, Binding binding) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".connection-", ".tmp");
        try {
            Files.write(temporary, json.writeValueAsBytes(binding));
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
