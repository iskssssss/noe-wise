package com.skillforge.core.capability.connector;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 以受限占位符语法渲染纯文本模板。 */
@Component
public class TemplateRenderToolHandler implements PlatformToolHandler {

    public static final int MAX_TEMPLATE_BYTES = 1024 * 1024;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9_.-]*)}}");

    @Override
    public String toolName() {
        return "template_render";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args, PlatformToolExecutionContext context) {
        String template = args == null || args.get("template") == null ? "" : String.valueOf(args.get("template"));
        if (template.getBytes(StandardCharsets.UTF_8).length > MAX_TEMPLATE_BYTES) {
            return Map.of("success", false, "error", "template UTF-8 正文不得超过 1MiB");
        }
        Object rawData = args == null ? null : args.get("data");
        Map<?, ?> data = rawData instanceof Map<?, ?> map ? map : Map.of();
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            Object value = resolve(data, matcher.group(1));
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(rendered);
        return Map.of("success", true, "rendered", rendered.toString());
    }

    private Object resolve(Map<?, ?> data, String path) {
        Object current = data;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else if (current instanceof List<?> list && part.matches("0|[1-9]\\d*")) {
                int index = Integer.parseInt(part);
                current = index < list.size() ? list.get(index) : null;
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }
}
