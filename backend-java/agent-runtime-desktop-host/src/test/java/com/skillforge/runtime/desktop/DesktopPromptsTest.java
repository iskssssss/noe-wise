package com.skillforge.runtime.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.spi.SessionPrompt;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class DesktopPromptsTest {
    @Test void classpathResourceOwnsWholeBodyAndContentVersionsFollowEffectivePolicy() throws Exception {
        var first = DesktopPrompts.prepare(DesktopPolicy.defaults());
        var smaller = DesktopPrompts.prepare(DesktopPolicy.defaults().withOutputTokens(4096));
        try (var resource = DesktopPrompts.class.getClassLoader().getResourceAsStream(DesktopPrompts.RESOURCE)) {
            var fixed = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(AgentExecutionCodec.digest(fixed), first.fixedBodySha256());
            assertEquals(first.fixedBodySha256(), smaller.fixedBodySha256());
        }
        assertEquals(DesktopPrompts.RESOURCE, first.resource());
        assertEquals(1, first.prompt().modules().size());
        assertEquals(SessionPrompt.Type.HOST, first.prompt().modules().getFirst().type());
        assertEquals(AgentExecutionCodec.digest(first.body()), first.effectiveBodySha256());
        assertNotEquals(first.effectiveBodySha256(), smaller.effectiveBodySha256());
        assertFalse(first.body().contains("{{"));
        assertFalse(smaller.body().contains("{{"));
        assertTrue(smaller.body().contains("4096 tokens"));
    }

    @Test void restorationUsesSavedBodyAndSourceWithoutReadingCurrentResource() {
        var body = "旧 Run 的完整系统正文，不能由新安装的资源替换。";
        var configuration = DesktopJson.object();
        configuration.putObject("prompt").put("resource", "retired/desktop-system.md")
                .put("fixedBodySha256", AgentExecutionCodec.digest("retired fixed resource"))
                .put("effectiveBodySha256", AgentExecutionCodec.digest(body));
        var prompt = DesktopPrompts.frozen(configuration, List.of(new ModelMessage("old-system", ModelRole.SYSTEM, body)));
        assertEquals(body, prompt.render());
        assertEquals(AgentExecutionCodec.digest(body), prompt.modules().getFirst().sourceVersion());
        assertEquals("retired/desktop-system.md", configuration.path("prompt").path("resource").asText());
    }

    @Test void missingSourceIsRejectedWithoutInventingProvenance() {
        var configuration = DesktopJson.object(); var body = "历史原始正文";
        assertEquals("desktop_prompt_frozen_source_invalid", assertThrows(IllegalStateException.class,
                () -> DesktopPrompts.frozen(configuration, List.of(new ModelMessage("system", ModelRole.SYSTEM, body)))).getMessage());
        assertFalse(configuration.has("prompt"));
    }

    @Test void mismatchedSourceAndMissingOrDuplicatedSystemAreRejected() {
        var prepared = DesktopPrompts.prepare(DesktopPolicy.defaults()); var configuration = DesktopJson.object();
        configuration.putObject("prompt").put("resource", prepared.resource()).put("fixedBodySha256", prepared.fixedBodySha256())
                .put("effectiveBodySha256", prepared.effectiveBodySha256());
        var original = new ModelMessage("system", ModelRole.SYSTEM, prepared.body());
        assertEquals("desktop_prompt_frozen_source_invalid", assertThrows(IllegalStateException.class,
                () -> DesktopPrompts.frozen(configuration, List.of(new ModelMessage("system", ModelRole.SYSTEM, "altered")))).getMessage());
        assertEquals("desktop_prompt_frozen_body_invalid", assertThrows(IllegalStateException.class,
                () -> DesktopPrompts.frozen(configuration, List.of())).getMessage());
        assertEquals("desktop_prompt_frozen_body_invalid", assertThrows(IllegalStateException.class,
                () -> DesktopPrompts.frozen(configuration, List.of(original, original))).getMessage());
    }

    @Test void missingMalformedAndUnresolvedClasspathResourcesFailWithoutFallback() throws Exception {
        assertResourceFailure(null, "desktop_prompt_resource_missing");
        assertResourceFailure(new byte[] {(byte) 0xc3, 0x28}, "desktop_prompt_resource_utf8_invalid");
        assertResourceFailure(" ".getBytes(StandardCharsets.UTF_8), "desktop_prompt_resource_invalid");
        assertResourceFailure("\uFEFF{{output_tokens}}".getBytes(StandardCharsets.UTF_8), "desktop_prompt_resource_invalid");
        assertResourceFailure("{{output_tokens}}\0".getBytes(StandardCharsets.UTF_8), "desktop_prompt_resource_invalid");
        assertResourceFailure("no variable".getBytes(StandardCharsets.UTF_8), "desktop_prompt_resource_variables_invalid");
        assertResourceFailure("{{output_tokens}}{{unknown}}".getBytes(StandardCharsets.UTF_8), "desktop_prompt_resource_variables_invalid");
        assertResourceFailure("{{output_tokens}}{{output_tokens}}".getBytes(StandardCharsets.UTF_8), "desktop_prompt_resource_variables_invalid");
        assertResourceFailure(new byte[64 * 1024 + 1], "desktop_prompt_resource_too_large");
    }

    /** 只替换本类的标准 classpath 资源，不为生产类添加测试专用构造或读取入口。 */
    private static void assertResourceFailure(byte[] resource, String code) throws Exception {
        var location = DesktopPrompts.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new URLClassLoader(new URL[] {location}, DesktopPrompts.class.getClassLoader()) {
            @Override public InputStream getResourceAsStream(String name) {
                if (name.equals(DesktopPrompts.RESOURCE)) return resource == null ? null : new ByteArrayInputStream(resource);
                return super.getResourceAsStream(name);
            }
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith(DesktopPrompts.class.getName())) return super.loadClass(name, resolve);
                var loaded = findLoadedClass(name);
                if (loaded == null) loaded = findClass(name);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }) {
            var prepare = loader.loadClass(DesktopPrompts.class.getName()).getDeclaredMethod("prepare", DesktopPolicy.class);
            prepare.setAccessible(true);
            var failure = assertThrows(InvocationTargetException.class, () -> prepare.invoke(null, DesktopPolicy.defaults()));
            assertEquals(code + ": " + DesktopPrompts.RESOURCE, failure.getCause().getMessage());
        }
    }
}
