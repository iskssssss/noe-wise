package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.json.RuntimeJson;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.json.CodecLimits;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * memory.written的SessionLog私有正文表示。before/fact/command的精确fact/ref/revision仍保持原物理字段；
 * 只复用大TypedPayload.canonicalJson字符串，公开读取恢复原v2完整字节，不重读current head。
 * marker只能由本地存储产生，外部Append请求携带marker直接拒绝。
 */
final class SessionMemoryEventStorage {
    private static final int MINIMUM_BODY_BYTES = 4096;
    private static final String TEXT_SCHEMA = "skillforge.runtime.session.memory-event-text.v1";
    public static final String EVENT_TYPE = "memory.written";
    public static final String SCHEMA = "skillforge.runtime.memory.written.v2";
    public static final String MARKER = "@runtime-session-content:";
    private SessionMemoryEventStorage() { }

    /** bodies值是原canonicalJson字符串本身的规范JSON编码，摘要包含不可变文本格式。 */
    public record Frozen(TypedPayload compact, Map<String, String> bodies, String originalDigest) {
        public Frozen { bodies = Map.copyOf(bodies); }
    }
    public static Frozen freeze(String eventType, TypedPayload payload) {
        if (!EVENT_TYPE.equals(eventType) || !SCHEMA.equals(payload.schemaId()))
            return new Frozen(payload, Map.of(), "");
        var bodies = new LinkedHashMap<String, String>();
        Object original = RuntimeJson.parseCanonical(payload.canonicalJson(), limits(payload.canonicalJson()));
        Object compact = transform(original, bodies, null);
        return new Frozen(new TypedPayload(payload.schemaId(), RuntimeJson.writeCanonical(compact)), bodies,
                SessionMemoryEventStorage.digest(payload));
    }
    public static TypedPayload restore(TypedPayload compact, Function<String, String> resolver, String originalDigest) {
        Object value = RuntimeJson.parseCanonical(compact.canonicalJson(), limits(compact.canonicalJson()));
        var result = new TypedPayload(compact.schemaId(), RuntimeJson.writeCanonical(transform(value, null, resolver)));
        if (!originalDigest.equals(SessionMemoryEventStorage.digest(result))) throw invalid();
        return result;
    }
    private static Object transform(Object value, Map<String, String> frozen, Function<String, String> resolver) {
        if (value instanceof Map<?, ?> map) {
            var copy = new TreeMap<String, Object>();
            for (var entry : map.entrySet()) copy.put((String) entry.getKey(), transform(entry.getValue(), frozen, resolver));
            if (map.keySet().equals(Set.of("schemaId", "canonicalJson")) && map.get("canonicalJson") instanceof String text) {
                if (text.startsWith(MARKER)) {
                    if (resolver == null) throw invalid();
                    String digest = text.substring(MARKER.length());
                    if (!digest.matches("[a-f0-9]{64}")) throw invalid();
                    String canonical = resolver.apply(digest);
                    if (canonical == null || !digest.equals(digest(canonical))) throw invalid();
                    Object source = RuntimeJson.parseCanonical(canonical, limits(canonical));
                    if (!(source instanceof String original)) throw invalid();
                    copy.put("canonicalJson", original);
                } else if (frozen != null && text.getBytes(StandardCharsets.UTF_8).length >= MINIMUM_BODY_BYTES) {
                    String canonical = RuntimeJson.writeCanonical(text);
                    String digest = digest(canonical);
                    String existing = frozen.putIfAbsent(digest, canonical);
                    if (existing != null && !existing.equals(canonical)) throw invalid();
                    copy.put("canonicalJson", MARKER + digest);
                }
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            var result = new ArrayList<Object>();
            for (var item : list) result.add(transform(item, frozen, resolver));
            return result;
        }
        return value;
    }
    public static String digest(String canonical) {
        return SessionMemoryEventStorage.digest(new TypedPayload(TEXT_SCHEMA, canonical));
    }
    private static String digest(TypedPayload payload) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(payload.schemaId().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return java.util.HexFormat.of().formatHex(digest.digest(payload.canonicalJson().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private static CodecLimits limits(String text) { return new CodecLimits(Math.max(1,text.length()),Math.max(1,text.length()),128); }
    private static IllegalStateException invalid() { return new IllegalStateException("session_memory_event_body_reference_rejected"); }
}
