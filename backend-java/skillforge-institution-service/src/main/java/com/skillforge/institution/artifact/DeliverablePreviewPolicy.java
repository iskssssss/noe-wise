package com.skillforge.institution.artifact;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * HTML 成果预览的纯策略。宿主在落盘前调用校验，在响应预览页时使用返回的 CSP；
 * 本类不会修改用户 HTML，也不会从 HTML 中接受任何可信源。
 */
public final class DeliverablePreviewPolicy {
    private static final Pattern CSS_URL = Pattern.compile("(?is)url\\s*\\(\\s*(['\"]?)(.*?)\\1\\s*\\)");
    private static final Pattern CSS_IMPORT = Pattern.compile(
            "(?is)@import\\s+(?:url\\s*\\(\\s*)?(['\"]?)([^'\"\\s;)]+)\\1");
    private static final Set<String> RESOURCE_TAGS = Set.of("img", "audio", "video", "source", "track", "script");

    private DeliverablePreviewPolicy() {
    }

    /** 校验没有同表示层附属文件的单体 HTML；资源只能为 data URI 或显式受信任的本地票据目录 URI。 */
    public static Result validateSingleFileHtml(String html, Collection<String> trustedFrameOrigins,
            Collection<String> localTicketDirectories) {
        return validate(html, Mode.SINGLE_FILE, trustedFrameOrigins, localTicketDirectories);
    }

    /** 校验同一表示层可携带资源目录的多文件 HTML；相对资源由宿主的同表示层路径约束。 */
    public static Result validateMultiFileHtml(String html, Collection<String> trustedFrameOrigins,
            Collection<String> localTicketDirectories) {
        return validate(html, Mode.MULTI_FILE, trustedFrameOrigins, localTicketDirectories);
    }

    /** 计算浏览器 HTML 输入预处理后的每段内联脚本 CSP 哈希，顺序与文档中的脚本顺序一致。 */
    public static List<String> inlineScriptHashes(String html) {
        if (html == null) {
            throw new IllegalArgumentException("HTML 不能为空");
        }
        Document document = parseForBrowser(html);
        return inlineScriptHashes(document);
    }

    /**
     * 按已校验结果生成响应 CSP。调用方只能使用本策略产生的 {@link Result}，避免由 HTML 扩大预览权限。
     */
    public static String contentSecurityPolicy(Result result) {
        Objects.requireNonNull(result, "预览策略结果不能为空");
        if (!result.accepted()) {
            throw new IllegalArgumentException("拒绝的 HTML 不能生成预览 CSP");
        }
        return result.contentSecurityPolicy();
    }

    private static Result validate(String html, Mode mode, Collection<String> trustedFrameOrigins,
            Collection<String> localTicketDirectories) {
        List<Violation> violations = new ArrayList<>();
        List<String> frameOrigins = trustedOrigins(trustedFrameOrigins, violations);
        List<String> localDirectories = trustedDirectories(localTicketDirectories, violations);
        if (html == null) {
            violations.add(new Violation("html_missing", "HTML 不能为空"));
            return new Result(false, "", List.of(), List.copyOf(violations));
        }

        Document document = parseForBrowser(html);
        for (Element element : document.getAllElements()) {
            inspectElement(element, mode, localDirectories, violations);
        }
        List<String> hashes = inlineScriptHashes(document);
        String csp = buildCsp(mode, hashes, frameOrigins, localDirectories);
        return new Result(violations.isEmpty(), csp, hashes, List.copyOf(violations));
    }

    private static Document parseForBrowser(String html) {
        // HTML 标准的输入流预处理会把 CRLF 与孤立 CR 规范为 LF；CSP 脚本哈希以该文本为准。
        String browserNormalized = html.replace("\r\n", "\n").replace('\r', '\n');
        return Jsoup.parse(browserNormalized);
    }

    private static void inspectElement(Element element, Mode mode, List<String> localDirectories,
            List<Violation> violations) {
        String tag = element.tagName().toLowerCase(Locale.ROOT);
        if (Set.of("object", "embed", "base", "iframe").contains(tag)) {
            violations.add(new Violation("active_element", "不允许 " + tag + " 元素"));
        }
        if ("form".equals(tag)) {
            violations.add(new Violation("form_forbidden", "不允许表单提交"));
        }
        if ("meta".equals(tag) && "refresh".equalsIgnoreCase(element.attr("http-equiv").trim())) {
            violations.add(new Violation("meta_refresh", "不允许 meta refresh"));
        }
        if ("script".equals(tag)) {
            inspectScript(element, mode, localDirectories, violations);
        }
        if ("svg".equals(tag)) {
            inspectSvg(element, violations);
        }
        if ("foreignobject".equals(tag)) {
            violations.add(new Violation("svg_foreign_object", "不允许 SVG foreignObject"));
        }
        for (Attribute attribute : element.attributes()) {
            String key = attribute.getKey().toLowerCase(Locale.ROOT);
            String value = attribute.getValue().trim();
            if (key.startsWith("on")) {
                violations.add(new Violation("inline_event_handler", "不允许事件处理属性 " + attribute.getKey()));
            }
            if (isJavascriptUrl(value)) {
                violations.add(new Violation("javascript_url", "不允许 javascript URL"));
            }
            if ("style".equals(key)) {
                inspectCss(value, mode, localDirectories, violations);
            }
        }
        if ("style".equals(tag)) {
            inspectCss(element.data(), mode, localDirectories, violations);
        }
        inspectResourceAttributes(element, tag, mode, localDirectories, violations);
    }

    private static void inspectScript(Element script, Mode mode, List<String> localDirectories,
            List<Violation> violations) {
        if (script.hasAttr("src")) {
            if (mode == Mode.SINGLE_FILE) {
                violations.add(new Violation("external_script", "单体 HTML 不允许外部脚本"));
            } else {
                inspectResource(script.attr("src"), "script", mode, localDirectories, violations);
            }
            return;
        }
        // 内联脚本由精确内容 hash 授权，动态字符串执行由不含 unsafe-eval 的 CSP 阻止。
        // 字面关键字不能判断 JavaScript 语义，也不能用于拒绝合法 function 或示例文本。
    }

    private static void inspectSvg(Element svg, List<Violation> violations) {
        if (!svg.select("script").isEmpty()) {
            violations.add(new Violation("svg_script", "不允许 SVG 脚本"));
        }
        if (!svg.select("[onload], [onclick], [onerror], [onbegin], [onend]").isEmpty()) {
            violations.add(new Violation("svg_event_handler", "不允许 SVG 事件处理属性"));
        }
        for (Element resource : svg.select("image, use, feImage")) {
            String reference = resource.hasAttr("href") ? resource.attr("href") : resource.attr("xlink:href");
            if (!reference.isBlank() && !reference.startsWith("#") && !reference.startsWith("data:")) {
                violations.add(new Violation("svg_external_resource", "不允许 SVG 引用外部资源"));
            }
        }
    }

    private static boolean isJavascriptUrl(String value) {
        StringBuilder compact = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint) && !Character.isISOControl(codePoint)) {
                compact.appendCodePoint(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        return compact.toString().regionMatches(true, 0, "javascript:", 0, "javascript:".length());
    }

    private static void inspectResourceAttributes(Element element, String tag, Mode mode,
            List<String> localDirectories, List<Violation> violations) {
        if (RESOURCE_TAGS.contains(tag) && element.hasAttr("src") && !"script".equals(tag)) {
            inspectResource(element.attr("src"), tag, mode, localDirectories, violations);
        }
        if ("link".equals(tag) && element.hasAttr("href")) {
            inspectResource(element.attr("href"), "style", mode, localDirectories, violations);
        }
        if (element.hasAttr("poster")) {
            inspectResource(element.attr("poster"), "media", mode, localDirectories, violations);
        }
        if (element.hasAttr("srcset")) {
            for (String candidate : element.attr("srcset").split(",")) {
                String url = candidate.trim().split("\\s+", 2)[0];
                inspectResource(url, "img", mode, localDirectories, violations);
            }
        }
    }

    /** 同一CSS资源语法供冻结时核验本地表示集合；不把文件内容当授权或指令。 */
    static List<String> cssResourceReferences(String css) {
        StringBuilder clean = new StringBuilder();
        char quote = 0;
        for (int index = 0; index < css.length(); index++) {
            char current = css.charAt(index);
            if (current == '\\' && index + 1 < css.length()) {
                clean.append(current).append(css.charAt(++index)); continue;
            }
            if (quote != 0) { clean.append(current); if (current == quote) quote = 0; continue; }
            if (current == '\'' || current == '"') { quote = current; clean.append(current); continue; }
            if (current == '/' && index + 1 < css.length() && css.charAt(index + 1) == '*') {
                int end = css.indexOf("*/", index + 2);
                if (end < 0) break;
                clean.append(' '); index = end + 1; continue;
            }
            clean.append(current);
        }
        var references = new LinkedHashSet<String>();
        Matcher urls = CSS_URL.matcher(clean);
        while (urls.find()) references.add(decodeCssReference(urls.group(2).trim()));
        Matcher imports = CSS_IMPORT.matcher(clean);
        while (imports.find()) references.add(decodeCssReference(imports.group(2).trim()));
        return List.copyOf(references);
    }

    private static String decodeCssReference(String reference) {
        var escape = Pattern.compile("\\\\([0-9a-fA-F]{1,6})\\s?|\\\\(.)", Pattern.DOTALL).matcher(reference);
        var value = new StringBuilder();
        while (escape.find()) {
            String replacement = escape.group(1) == null ? escape.group(2)
                    : new String(Character.toChars(Integer.parseInt(escape.group(1), 16)));
            escape.appendReplacement(value, Matcher.quoteReplacement(replacement));
        }
        escape.appendTail(value);
        return value.toString();
    }

    private static void inspectCss(String css, Mode mode, List<String> localDirectories, List<Violation> violations) {
        Matcher urls = CSS_URL.matcher(css);
        while (urls.find()) {
            inspectResource(urls.group(2).trim(), "css", mode, localDirectories, violations);
        }
        Matcher imports = CSS_IMPORT.matcher(css);
        while (imports.find()) {
            inspectResource(imports.group(2).trim(), "style", mode, localDirectories, violations);
        }
    }

    private static void inspectResource(String reference, String kind, Mode mode, List<String> localDirectories,
            List<Violation> violations) {
        if (reference.isBlank() || reference.startsWith("#")) {
            return;
        }
        if (reference.regionMatches(true, 0, "data:", 0, 5)) {
            if (Set.of("img", "audio", "video", "source", "track", "media", "css", "svg").contains(kind)) {
                return;
            }
            violations.add(new Violation("resource_scheme", "不允许 " + kind + " 使用 data URI"));
            return;
        }
        URI uri;
        try {
            uri = URI.create(reference);
        } catch (IllegalArgumentException invalid) {
            violations.add(new Violation("resource_url_invalid", "资源 URL 非法"));
            return;
        }
        if (!uri.isAbsolute() && uri.getRawAuthority() == null) {
            if (mode == Mode.MULTI_FILE) {
                return;
            }
            violations.add(new Violation("relative_resource", "单体 HTML 不允许相对资源"));
            return;
        }
        if (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                || !withinTrustedDirectory(uri, localDirectories)) {
            violations.add(new Violation("external_resource", "资源不在宿主指定的本地票据目录内"));
        }
    }

    private static List<String> inlineScriptHashes(Document document) {
        List<String> hashes = new ArrayList<>();
        for (Element script : document.select("script:not([src])")) {
            hashes.add(sha256(script.data()));
        }
        return List.copyOf(hashes);
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return "sha256-" + java.util.Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("缺少 SHA-256 算法", impossible);
        }
    }

    private static List<String> trustedOrigins(Collection<String> candidates, List<Violation> violations) {
        LinkedHashSet<String> origins = new LinkedHashSet<>();
        if (candidates != null) {
            for (String candidate : candidates) {
                try {
                    URI uri = URI.create(candidate);
                    if (!isHttpOrigin(uri) || !uri.getRawPath().isEmpty()) {
                        throw new IllegalArgumentException();
                    }
                    origins.add(uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getAuthority());
                } catch (RuntimeException invalid) {
                    violations.add(new Violation("trusted_origin_invalid", "宿主传入的 frame origin 非法"));
                }
            }
        }
        return List.copyOf(origins);
    }

    private static List<String> trustedDirectories(Collection<String> candidates, List<Violation> violations) {
        LinkedHashSet<String> directories = new LinkedHashSet<>();
        if (candidates != null) {
            for (String candidate : candidates) {
                try {
                    URI uri = URI.create(candidate);
                    String path = uri.getRawPath();
                    if (!isHttpOrigin(uri) || path.isEmpty() || !path.endsWith("/") || path.contains("/../")
                            || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                        throw new IllegalArgumentException();
                    }
                    directories.add(uri.toString());
                } catch (RuntimeException invalid) {
                    violations.add(new Violation("trusted_directory_invalid", "宿主传入的本地票据目录非法"));
                }
            }
        }
        return List.copyOf(directories);
    }

    private static boolean isHttpOrigin(URI uri) {
        return uri.getScheme() != null && Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                && uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                && uri.getRawFragment() == null;
    }

    private static boolean withinTrustedDirectory(URI resource, List<String> directories) {
        URI normalized = resource.normalize();
        return directories.stream().map(URI::create).anyMatch(directory ->
                normalized.getScheme().equalsIgnoreCase(directory.getScheme())
                        && normalized.getHost().equalsIgnoreCase(directory.getHost())
                        && normalized.getPort() == directory.getPort()
                        && normalized.getPath().startsWith(directory.getPath()));
    }

    private static String buildCsp(Mode mode, List<String> hashes, List<String> frameOrigins,
            List<String> localDirectories) {
        String scriptSources = hashes.isEmpty()
                ? (mode == Mode.MULTI_FILE && !localDirectories.isEmpty() ? String.join(" ", localDirectories) : "'none'")
                : quoteHashes(hashes);
        if (!hashes.isEmpty() && mode == Mode.MULTI_FILE && !localDirectories.isEmpty()) {
            scriptSources += " " + String.join(" ", localDirectories);
        }
        String localSources = localDirectories.isEmpty() ? "" : " " + String.join(" ", localDirectories);
        String frameAncestors = frameOrigins.isEmpty() ? "'none'" : String.join(" ", frameOrigins);
        return "default-src 'none'; script-src " + scriptSources
                + "; script-src-attr 'none'; style-src 'unsafe-inline'" + localSources
                + "; img-src data:" + localSources + "; font-src data:" + localSources
                + "; media-src data:" + localSources
                + "; connect-src 'none'; worker-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'"
                + "; form-action 'none'; sandbox allow-scripts; frame-ancestors " + frameAncestors;
    }

    private static String quoteHashes(List<String> hashes) {
        return hashes.stream().map(hash -> "'" + hash + "'").collect(java.util.stream.Collectors.joining(" "));
    }

    public enum Mode {
        SINGLE_FILE,
        MULTI_FILE
    }

    public record Violation(String code, String message) {
    }

    /** 校验结果保留哈希和 CSP，宿主应在 {@link #accepted()} 为 false 时拒绝该表示层。 */
    public record Result(boolean accepted, String contentSecurityPolicy, List<String> inlineScriptHashes,
            List<Violation> violations) {
    }
}
