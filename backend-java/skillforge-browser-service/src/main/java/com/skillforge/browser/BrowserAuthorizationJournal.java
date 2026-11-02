package com.skillforge.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** 个人授权的脱敏观察账本；只记录官方公开设备摘要，不读写上游授权文件。 */
final class BrowserAuthorizationJournal {
    private final ObjectMapper json;

    BrowserAuthorizationJournal(ObjectMapper json) { this.json = json; }

    record Grant(String deviceTag, String browserTag, String expiresAt) {}
    record Snapshot(List<Grant> grants) {}
    record Event(String id, String observedAt, String requestId, String source, String operation,
                 String phase, String deviceTag, Integer upstreamStatus, List<Grant> grants,
                 List<String> added, List<String> removed) {}

    static String requestId(String supplied) {
        return supplied == null ? UUID.randomUUID().toString() : UUID.fromString(supplied).toString();
    }

    static String tag(String value) { return value == null ? null : BrowserEngine.hash(value); }

    void control(Path home, String requestId, String source, String operation, String phase,
                 String device, Integer upstreamStatus) throws IOException {
        append(home, new Event(UUID.randomUUID().toString(), Instant.now().toString(), requestId,
                source, operation, phase, tag(device), upstreamStatus, null, null, null));
    }

    /** 差量只证明两次观察之间变化；即使与控制请求相邻，也不臆测未观察到的删除者。 */
    void observe(Path home, JsonNode devices, String requestId, String source) throws IOException {
        if (!devices.isArray()) throw new IOException("browser_authorization_summary_invalid");
        List<Grant> grants = new ArrayList<>();
        for (JsonNode device : devices) {
            if (!device.path("device_id").isTextual() || !device.path("browser_id").isTextual()
                    || !device.path("expires_at").isTextual())
                throw new IOException("browser_authorization_summary_invalid");
            grants.add(new Grant(tag(device.path("device_id").asText()),
                    tag(device.path("browser_id").asText()), device.path("expires_at").asText()));
        }
        grants.sort(Comparator.comparing(Grant::deviceTag));
        var current = new Snapshot(List.copyOf(grants));
        Path state = home.resolve("authorization-audit/last-observation.json");
        Snapshot previous = Files.exists(state) ? json.readValue(state.toFile(), Snapshot.class) : null;
        if (current.equals(previous)) return;
        var before = previous == null ? List.<String>of() : previous.grants().stream().map(Grant::deviceTag).toList();
        var after = grants.stream().map(Grant::deviceTag).toList();
        append(home, new Event(UUID.randomUUID().toString(), Instant.now().toString(), requestId,
                source, "grants", previous == null ? "baseline" : "changed", null, null, grants,
                after.stream().filter(id -> !before.contains(id)).toList(),
                before.stream().filter(id -> !after.contains(id)).toList()));
        write(state, current);
    }

    private void append(Path home, Event event) throws IOException {
        write(home.resolve("authorization-audit/events/" + event.id() + ".json"), event);
    }

    private void write(Path destination, Object value) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), ".authorization-", ".tmp");
        try {
            Files.write(temporary, json.writeValueAsBytes(value));
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
