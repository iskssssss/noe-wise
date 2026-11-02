package com.skillforge.core.chat.integration.publicread;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.spi.SessionOutputContract.StreamingContent;
import com.skillforge.core.chat.message.ChatMessageRecord;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 公开输出检查宿主已知私有值与明确凭据格式；普通技术标识和字段说明可公开。 */
@Component
public final class PublicOutputGuard {
    public static final String REJECTION_CODE = "PUBLIC_OUTPUT_REJECTED";
    public static final String SAFE_MESSAGE = "内容未通过公开检查，本次交付未完成。";
    public static final String POLICY_VERSION = "public-output-v5";
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----begin (?:[a-z0-9]+ )?private key-----");
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "\\bauthorization[\\\"']?\\s*[:=]\\s*"
                    + "(?:\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"(?=$|[\\s`;,)\\]}])"
                    + "|'((?:\\\\.|[^'\\\\])*)'(?=$|[\\s`;,)\\]}])"
                    + "|([\\\"']?(?:bearer|basic|token|apikey)\\s+[^\\s`;,]+))");
    private static final Pattern AUTHORIZATION_VALUE = Pattern.compile(
            "(?:bearer|basic|token|apikey)\\s+(.+)", Pattern.DOTALL);
    private static final Pattern CREDENTIAL_ASSIGNMENT = Pattern.compile(
            "(?<![a-z0-9_])[a-z0-9_-]*(?:api[_-]?key|token|password|passwd|pwd)[\\\"']?\\s*[:=]\\s*"
                    + "(?:\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"(?=$|[\\s`;,)\\]}])"
                    + "|'((?:\\\\.|[^'\\\\])*)'(?=$|[\\s`;,)\\]}])|([^\\s`;,]+))");
    private static final Pattern ENVIRONMENT_PLACEHOLDER = Pattern.compile("\\$\\{[a-z_][a-z0-9_]*}");
    private static final Pattern STREAM_CREDENTIAL_HEAD = Pattern.compile(
            "(?<![a-z0-9_])[a-z0-9_-]*(?:api[_-]?key|token|password|passwd|pwd)[\\\"']?\\s*(?:([:=])\\s*|$)");
    private static final Pattern STREAM_AUTHORIZATION_HEAD = Pattern.compile(
            "\\bauthorization[\\\"']?\\s*(?:([:=])\\s*|$)");
    private static final Pattern STREAM_TRAILING_IDENTIFIER = Pattern.compile("[a-z0-9_-]+$");
    private static final Set<String> DOCUMENTATION_PLACEHOLDERS = Set.of(
            "example", "sample", "placeholder", "redacted", "changeme", "null", "undefined", "***", "[redacted]",
            "your_api_key", "your-api-key", "your_apikey", "<api_key>", "<api-key>", "<apikey>",
            "your_token", "your-token", "your_password", "your-password", "your_access_token", "your-access-token",
            "<token>", "<password>", "<access_token>", "<access-token>", "<channel_token>", "<channel-token>");
    private static final JsonFactory JSON = new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final String artifactRoot;

    public PublicOutputGuard(@Value("${skillforge.code-execution.artifact-root:./data/artifacts}") String artifactRoot) {
        this.artifactRoot = Path.of(artifactRoot).toAbsolutePath().normalize().toString();
    }

    /** 构造宿主已知私有路径范围；凭据格式独立检查，不扫描会话历史。 */
    public Scope capture(SessionOwner owner) {
        java.util.Objects.requireNonNull(owner, "owner");
        return capture();
    }

    /** 宿主已完成 Session 授权后使用同一当前公开规则检查持久消息。 */
    public Scope capture() { return new Scope(List.of(artifactRoot)); }

    public Scope capture(RunIdentity run) {
        return capture(run.owner());
    }

    public static final class Scope {
        private final Set<String> values;
        private final String digest;

        public Scope(Collection<String> values) {
            var normalized = new LinkedHashSet<String>();
            for (String value : values) if (value != null && !value.isBlank()) normalized.add(normalize(value));
            this.values = Set.copyOf(normalized);
            try {
                digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                        (POLICY_VERSION + "\n" + String.join("\n", normalized.stream().sorted().toList()))
                                .getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }

        public String digest() { return digest; }
        /** 流式文本检查保留足够的跨块原文，避免真实私有值被读取边界拆开。 */
        public int inspectionOverlapCharacters() {
            return Math.addExact(values.stream().mapToInt(String::length).max().orElse(0), 128);
        }
        public Scope including(Collection<String> extra) {
            var all = new LinkedHashSet<>(values); all.addAll(extra); return new Scope(all);
        }

        /** 持久主动助手消息也重新执行当前公开检查，不修改原消息身份或版本。 */
        public ChatMessageRecord protect(ChatMessageRecord message) {
            java.util.Objects.requireNonNull(message, "message");
            if (!"assistant".equals(message.role()) || !rejects(message.content())
                    && !rejects(String.valueOf(message.metadata())) && !rejects(String.valueOf(message.references()))) return message;
            var metadata = new LinkedHashMap<String, Object>();
            if (message.metadata() != null) {
                for (String key : List.of("run_id", "root_run_id", "root_input_id", "phase", "source_sequence",
                        "updated_sequence", "runtime_event_id", "team_answer_ref")) {
                    Object value = message.metadata().get(key);
                    if (value != null && !rejects(String.valueOf(value))) metadata.put(key, value);
                }
            }
            metadata.put("state", "failed");
            return new ChatMessageRecord(message.messageId(), message.role(), SAFE_MESSAGE, message.createdAt(),
                    java.util.Map.copyOf(metadata), message.messageVersion(), List.of());
        }

        /**
         * 只接收累计原文。已知私有值检查全文；尚未闭合的凭据从字段起保留，
         * 同时扣住原文与 NFKC 文本的跨块尾部，最终余文仍须在 Run 完成时整体检查。
         */
        public StreamingContent streamingContent(String accumulated) {
            java.util.Objects.requireNonNull(accumulated, "accumulated");
            String normalized = normalize(accumulated);
            String credentialText = normalizeCredential(accumulated);
            if (values.stream().anyMatch(normalized::contains) || PRIVATE_KEY.matcher(credentialText).find())
                return new StreamingContent(0, Optional.of(REJECTION_CODE));

            var nativeBooleans = nativeBooleanOffsets(accumulated, credentialText);
            int unsettled = unsettledCredentialStart(credentialText, nativeBooleans);
            // 不把尚在生成的 example 或环境变量占位示例误判为真实凭据。
            if (rejectsCredentials(credentialText.substring(0, unsettled), nativeBooleans))
                return new StreamingContent(0, Optional.of(REJECTION_CODE));
            int overlap = inspectionOverlapCharacters();
            int rawLimit = Math.max(0, accumulated.length() - overlap);
            int normalizedLimit = Math.max(0, Math.min(credentialText.length() - overlap, unsettled));
            return new StreamingContent(rawBoundary(accumulated, rawLimit, normalizedLimit), Optional.empty());
        }

        /** 检查已知私有值与明确凭据赋值；普通字段讨论、标识和占位示例可公开。 */
        public boolean rejects(String text) {
            if (text == null || text.isBlank()) return false;
            String content = normalize(text);
            for (String value : values) {
                if (content.contains(value)) return true;
            }
            // 路径归一化不应用于凭据语法，否则转义引号会失去原来的边界含义。
            String credentialText = normalizeCredential(text);
            return rejectsCredentials(credentialText, nativeBooleanOffsets(text, credentialText));
        }

        private static boolean rejectsCredentials(String credentialText, Set<Integer> nativeBooleans) {
            if (PRIVATE_KEY.matcher(credentialText).find()) return true;
            var authorization = AUTHORIZATION.matcher(credentialText);
            while (authorization.find()) {
                String value = capturedValue(authorization);
                boolean malformedQuote = authorization.group(3) != null && (value.startsWith("\"") || value.startsWith("'"));
                if (malformedQuote) value = value.substring(1);
                var scheme = AUTHORIZATION_VALUE.matcher(value);
                if (scheme.matches() && (malformedQuote || !documentationPlaceholder(scheme.group(1)))) return true;
            }
            var assignment = CREDENTIAL_ASSIGNMENT.matcher(credentialText);
            while (assignment.find()) {
                String value = capturedValue(assignment);
                // 只按解析器确认的原生布尔token位置识别类型，字符串和普通代码赋值不豁免。
                if (assignment.group(3) != null && nativeBooleans.contains(assignment.start(3))) continue;
                if (!documentationPlaceholder(value)) return true;
            }
            return false;
        }

        /** 只扫描确有布尔候选的JSON；完整解析、单根及重复字段检查先于类型豁免。 */
        private static Set<Integer> nativeBooleanOffsets(String raw, String normalized) {
            if (!jsonContainer(raw)) return Set.of();
            var assignments = CREDENTIAL_ASSIGNMENT.matcher(normalized);
            var candidates = new LinkedHashSet<Integer>();
            while (assignments.find()) if (assignments.group(3) != null && jsonBooleanBoundary(normalized, assignments.start(3))) {
                candidates.add(assignments.start(3));
            }
            if (candidates.isEmpty()) return Set.of();
            var positions = new LinkedHashSet<Integer>();
            try (var parser = JSON.createParser(raw)) {
                JsonToken root = parser.nextToken();
                if (root != JsonToken.START_OBJECT && root != JsonToken.START_ARRAY) return Set.of();
                int depth = 1;
                int rawCursor = 0, normalizedCursor = 0;
                boolean unchanged = raw.equals(normalized);
                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (depth == 0) return Set.of();
                    if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) depth++;
                    else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--;
                    else if (token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE) {
                        int rawOffset = Math.toIntExact(parser.currentTokenLocation().getCharOffset());
                        // 游标只前进；ASCII布尔token起点不会切开组合序列，每段原文至多规范化一次。
                        normalizedCursor = unchanged ? rawOffset
                                : normalizedCursor + normalizeCredential(raw.substring(rawCursor, rawOffset)).length();
                        rawCursor = rawOffset;
                        if (candidates.contains(normalizedCursor)) positions.add(normalizedCursor);
                    }
                }
                return depth == 0 ? Set.copyOf(positions) : Set.of();
            } catch (java.io.IOException | IllegalArgumentException invalid) {
                return Set.of();
            }
        }

        private static boolean jsonContainer(String text) {
            String value = text.stripLeading();
            return value.startsWith("{") || value.startsWith("[");
        }

        /** 必须见到真实结构分隔符；true-secret、trueSuffix或尚未收齐的true前缀不算布尔值。 */
        private static boolean jsonBooleanBoundary(String text, int start) {
            int end;
            if (text.startsWith("true", start)) end = start + 4;
            else if (text.startsWith("false", start)) end = start + 5;
            else return false;
            while (end < text.length() && Character.isWhitespace(text.charAt(end))) end++;
            return end < text.length() && ",}]".indexOf(text.charAt(end)) >= 0;
        }

        private static int unsettledCredentialStart(String text, Set<Integer> nativeBooleans) {
            int start = text.length();
            // 任意长度标识可能在后续变成 access_token；字段名尚未结束前不能提前释放其开头。
            var identifier = STREAM_TRAILING_IDENTIFIER.matcher(text);
            if (identifier.find()) start = identifier.start();
            var credentials = STREAM_CREDENTIAL_HEAD.matcher(text);
            while (credentials.find()) {
                if (credentials.group(1) == null || !closedCredentialValue(text, credentials.end(), false)
                        || jsonContainer(text) && jsonBooleanBoundary(text, credentials.end())
                            && !nativeBooleans.contains(credentials.end()))
                    start = Math.min(start, credentials.start());
            }
            var authorizations = STREAM_AUTHORIZATION_HEAD.matcher(text);
            while (authorizations.find()) {
                if (authorizations.group(1) == null || !closedCredentialValue(text, authorizations.end(), true))
                    start = Math.min(start, authorizations.start());
            }
            return start;
        }

        private static boolean closedCredentialValue(String text, int start, boolean authorization) {
            if (start >= text.length()) return false;
            char quote = text.charAt(start);
            if (quote == '"' || quote == '\'') {
                for (int cursor = start + 1; cursor < text.length(); cursor++) {
                    char value = text.charAt(cursor);
                    if (value == '\\') { cursor++; continue; }
                    if (value == quote)
                        return cursor + 1 < text.length() && quotedDelimiter(text.charAt(cursor + 1));
                }
                return false;
            }
            int cursor = start;
            if (authorization) {
                while (cursor < text.length() && !Character.isWhitespace(text.charAt(cursor))) cursor++;
                if (cursor == text.length()) return false;
                String scheme = text.substring(start, cursor);
                if (!Set.of("bearer", "basic", "token", "apikey").contains(scheme)) return true;
                while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++;
                if (cursor == text.length()) return false;
            }
            while (cursor < text.length()) {
                char value = text.charAt(cursor++);
                if (Character.isWhitespace(value) || value == 0x60 || value == ';' || value == ',') return true;
            }
            return false;
        }

        private static boolean quotedDelimiter(char value) {
            return Character.isWhitespace(value) || "\u0060;,) ]}".indexOf(value) >= 0;
        }

        /** 将规范化偏移收敛到原文下标；不能拆代理对或跨组合字符的 NFKC 边界。 */
        private static int rawBoundary(String raw, int rawLimit, int normalizedLimit) {
            int low = 0, high = rawLimit;
            while (low < high) {
                int middle = low + (high - low + 1) / 2;
                if (normalizeCredential(raw.substring(0, middle)).length() <= normalizedLimit) low = middle;
                else high = middle - 1;
            }
            if (low > 0 && low < raw.length() && Character.isHighSurrogate(raw.charAt(low - 1))
                    && Character.isLowSurrogate(raw.charAt(low))) low--;
            while (low > 0 && low < raw.length() && combining(raw.codePointAt(low)))
                low = raw.offsetByCodePoints(low, -1);
            String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
            while (low > 0 && !normalized.startsWith(Normalizer.normalize(raw.substring(0, low), Normalizer.Form.NFKC)))
                low = raw.offsetByCodePoints(low, -1);
            return low;
        }

        private static boolean combining(int codePoint) {
            int type = Character.getType(codePoint);
            return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK;
        }

        private static String normalizeCredential(String value) {
            return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        }

        private static boolean documentationPlaceholder(String value) {
            return value.isEmpty() || DOCUMENTATION_PLACEHOLDERS.contains(value)
                    || ENVIRONMENT_PLACEHOLDER.matcher(value).matches();
        }

        private static String capturedValue(java.util.regex.Matcher matcher) {
            return matcher.group(1) != null ? matcher.group(1) : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
        }

        private static String normalize(String value) {
            return Normalizer.normalize(value, Normalizer.Form.NFKC).replace('\\', '/').toLowerCase(Locale.ROOT);
        }
    }
}
