package com.skillforge.runtime.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.spi.SessionPrompt;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 桌面正文归宿主资源；新任务组装一次，恢复仅使用原任务已保存的完整正文。 */
final class DesktopPrompts {
    static final String RESOURCE = "com/skillforge/runtime/desktop/prompts/desktop-system.md";
    private static final String OUTPUT_TOKENS = "{{output_tokens}}";
    private static final int MAX_RESOURCE_BYTES = 64 * 1024;

    record Prepared(String resource, String fixedBodySha256, SessionPrompt prompt) {
        String effectiveBodySha256() { return prompt.modules().getFirst().sourceVersion(); }
        String body() { return prompt.render(); }
    }

    private DesktopPrompts() { }

    static Prepared prepare(DesktopPolicy policy) {
        var fixed = read();
        var body = fixed.replace(OUTPUT_TOKENS, Integer.toString(policy.outputTokens()));
        return new Prepared(RESOURCE, AgentExecutionCodec.digest(fixed), prompt(body));
    }

    /** 输入材料拥有最终正文；只核验原来源，不拿新资源替换已冻结正文。 */
    static SessionPrompt frozen(JsonNode configuration, List<ModelPort.ModelMessage> messages) {
        var system = messages.stream().filter(message -> message.role() == ModelPort.ModelRole.SYSTEM).toList();
        if (system.size() != 1 || system.getFirst().textContent().isBlank())
            throw new IllegalStateException("desktop_prompt_frozen_body_invalid");
        var body = system.getFirst().textContent();
        var source = configuration.path("prompt");
        if (!source.isObject() || !source.path("resource").isTextual() || source.path("resource").textValue().isBlank()
                    || !sha256(source.path("fixedBodySha256")) || !sha256(source.path("effectiveBodySha256"))
                    || !source.path("effectiveBodySha256").textValue().equals(AgentExecutionCodec.digest(body)))
            throw new IllegalStateException("desktop_prompt_frozen_source_invalid");
        return prompt(body);
    }

    private static SessionPrompt prompt(String body) {
        return SessionPrompt.single("desktop-system", SessionPrompt.Type.HOST, AgentExecutionCodec.digest(body), body);
    }

    private static boolean sha256(JsonNode value) {
        return value.isTextual() && value.textValue().matches("[0-9a-f]{64}");
    }

    private static String read() {
        try (var input = DesktopPrompts.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("desktop_prompt_resource_missing: " + RESOURCE);
            var bytes = input.readNBytes(MAX_RESOURCE_BYTES + 1);
            if (bytes.length > MAX_RESOURCE_BYTES) throw new IllegalStateException("desktop_prompt_resource_too_large: " + RESOURCE);
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            var text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
            if (text.isBlank() || text.charAt(0) == '\uFEFF'
                    || text.codePoints().anyMatch(code -> Character.isISOControl(code) && code != '\n' && code != '\r' && code != '\t'))
                throw new IllegalStateException("desktop_prompt_resource_invalid: " + RESOURCE);
            int variable = text.indexOf(OUTPUT_TOKENS);
            if (variable < 0 || variable != text.lastIndexOf(OUTPUT_TOKENS)
                    || text.replace(OUTPUT_TOKENS, "").contains("{{") || text.replace(OUTPUT_TOKENS, "").contains("}}"))
                throw new IllegalStateException("desktop_prompt_resource_variables_invalid: " + RESOURCE);
            return text;
        } catch (CharacterCodingException invalid) {
            throw new IllegalStateException("desktop_prompt_resource_utf8_invalid: " + RESOURCE, invalid);
        } catch (IOException failure) {
            throw new IllegalStateException("desktop_prompt_resource_read_failed: " + RESOURCE, failure);
        }
    }
}
