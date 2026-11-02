package com.skillforge.runtime.adapter.jdbc.callback;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 回调签名与载荷的冻结字节协议；不判断凭据、当前授权或时间窗口。 */
public final class CallbackPayloads {
    public static final String EXECUTION_SCHEMA = "skillforge.runtime.callback.execution-result.v1";
    public static final String SIGNATURE_POLICY = "hmac-sha256-callback.v1";
    private CallbackPayloads() { }

    /** 只接纳已注册 target 的严格 canonical UTF-8 数组；结果保持原载荷字节的语义，不补造结果。 */
    public static TypedPayload decode(CallbackRegistration registration, byte[] rawBody) {
        if (registration == null || rawBody == null) throw invalid();
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(rawBody)).toString();
        } catch (CharacterCodingException malformed) { throw invalid(); }
        var draft = registration.draft(); String schema = draft.payloadSchemaId();
        if (EXECUTION_SCHEMA.equals(schema) && draft.target() instanceof CallbackTarget.ExecutionTarget target) {
            var values = new Reader(json).read(7);
            if (!values.get(0).equals(draft.owner().sessionRef().tenantId().value())
                    || !values.get(0).equals(target.executionRef().tenantId().value())
                    || !values.get(0).equals(target.owningRunRef().tenantId().value())
                    || !values.get(1).equals(target.executionRef().executionId())
                    || !values.get(2).equals(target.owningRunRef().runId())
                    || !values.get(3).equals(target.actionKey())
                    || !values.get(4).equals(Long.toString(target.expectedExecutionRevision()))) throw invalid();
            payload(values.get(5), values.get(6));
        } else throw invalid();
        return new TypedPayload(schema, json);
    }

    /** 无分隔符歧义的签名输入；timestamp 必须为正 epoch 秒的唯一十进制表示。 */
    public static byte[] signingBytes(CallbackRef callback, CallbackDeliveryRef delivery, String timestamp, byte[] rawBody) {
        if (callback == null || delivery == null || timestamp == null || rawBody == null
                || !callback.tenantId().equals(delivery.tenantId())) throw invalid();
        try {
            long seconds = Long.parseLong(timestamp);
            if (seconds <= 0 || !Long.toString(seconds).equals(timestamp)) throw invalid();
        } catch (NumberFormatException malformed) { throw invalid(); }
        String bodyHash;
        try { bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBody)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("callback_sha256_unavailable", impossible); }
        return ("callback-v1\n" + encoded(callback.tenantId().value()) + "\n" + encoded(callback.callbackId()) + "\n"
                + encoded(delivery.deliveryId()) + "\n" + timestamp + "\n" + bodyHash).getBytes(StandardCharsets.UTF_8);
    }

    private static void payload(String schema, String json) {
        if (schema.isBlank() || json.isBlank()) throw invalid();
        // 内层业务结果按其注册 schema 验证；此边界只固定外层回调协议与原结果引用。
        new TypedPayload(schema, json);
    }

    /** 外层通过验证后取出业务结果，交给宿主已注册的业务 schema 校验器。 */
    public static TypedPayload businessPayload(CallbackRegistration registration, byte[] rawBody) {
        var outer = decode(registration, rawBody);
        int size = 7;
        var fields = new Reader(outer.canonicalJson()).read(size);
        return new TypedPayload(fields.get(size - 2), fields.get(size - 1));
    }

    private static String encoded(String value) {
        try {
            var bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
            var content = new byte[bytes.remaining()]; bytes.get(content);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(content);
        } catch (CharacterCodingException malformed) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("callback_payload_invalid"); }

    /** 有界字段数量的浅层解析器，只接受 canonical writer 实际输出的字符串转义。 */
    private static final class Reader {
        final String text; int position;
        Reader(String text) { this.text = Objects.requireNonNull(text); }
        List<String> read(int size) {
            need('['); var fields = new ArrayList<String>(size);
            for (int i = 0; i < size; i++) { if (i > 0) need(','); fields.add(string()); }
            need(']'); if (position != text.length()) throw invalid(); return fields;
        }
        String string() {
            need('"'); var value = new StringBuilder();
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') return value.toString();
                if (c < 32 || Character.isLowSurrogate(c)) throw invalid();
                if (Character.isHighSurrogate(c)) {
                    if (position == text.length() || !Character.isLowSurrogate(text.charAt(position))) throw invalid();
                    value.append(c).append(text.charAt(position++));
                } else if (c == '\\') {
                    if (position == text.length()) throw invalid();
                    char escaped = text.charAt(position++); int shortEscape = "btnfr".indexOf(escaped);
                    if (shortEscape >= 0) value.append("\b\t\n\f\r".charAt(shortEscape));
                    else if (escaped == '"' || escaped == '\\') value.append(escaped);
                    else if (escaped == 'u') {
                        if (position + 4 > text.length()) throw invalid();
                        String hex = text.substring(position, position + 4);
                        if (!hex.matches("00[01][0-9a-f]")) throw invalid();
                        char control = (char) Integer.parseInt(hex, 16);
                        if ("\b\t\n\f\r".indexOf(control) >= 0) throw invalid();
                        value.append(control); position += 4;
                    } else throw invalid();
                } else value.append(c);
            }
            throw invalid();
        }
        void need(char expected) {
            if (position == text.length() || text.charAt(position++) != expected) throw invalid();
        }
    }
}
