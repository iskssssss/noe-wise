package com.skillforge.institution.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeliverablePreviewPolicyTest {
    private static final List<String> FRAME_ORIGINS = List.of("http://platform.localhost:3000");
    private static final List<String> LOCAL_DIRECTORY = List.of("http://preview.localhost:8091/p/ticket/");

    @Test void cssReferencesSharePolicySyntaxAndResolveRelativeDecodedPathsWithinRepresentation() {
        var references = DeliverablePreviewPolicy.cssResourceReferences(
                "/* url(missing-comment.png) */ @import '../base.css?v=1'; .a{background:url('../images/a%20b.svg#icon')} .b{background:url(data:image/png;base64,AA)}");
        assertTrue(references.contains("../base.css?v=1"));
        assertTrue(references.contains("../images/a%20b.svg#icon"));
        assertFalse(references.contains("missing-comment.png"));
        var paths = java.util.Set.of("base.css", "images/a b.svg", "css/style.css");
        for (String reference : references) DeliverableService.requireWebsiteResource(reference, "css/style.css", paths);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> DeliverableService.requireWebsiteResource("?v=2#x", "css/style.css", paths));
        for (String reference : List.of("../../escape.png", "%2e%2e/%2e%2e/escape.png", "https://remote.invalid/a.png", "//remote.invalid/a.png", "/root.png", "missing.png"))
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> DeliverableService.requireWebsiteResource(reference, "css/style.css", paths), reference);
    }

    @Test
    void hashesInlineScriptsUsingBrowserCrLfNormalizationAndBuildsRestrictedCsp() throws Exception {
        String script = "const title = '报告';\r\nconsole.log(title);";
        var result = DeliverablePreviewPolicy.validateSingleFileHtml("<script>" + script + "</script>",
                FRAME_ORIGINS, LOCAL_DIRECTORY);

        String expected = "sha256-" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(script.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8)));
        assertTrue(result.accepted());
        assertEquals(List.of(expected), result.inlineScriptHashes());
        assertTrue(result.contentSecurityPolicy().contains("script-src '" + expected + "'"));
        assertTrue(result.contentSecurityPolicy().contains("script-src-attr 'none'"));
        assertTrue(result.contentSecurityPolicy().contains("connect-src 'none'"));
        assertFalse(result.contentSecurityPolicy().contains("script-src 'unsafe-inline'"));
        assertFalse(result.contentSecurityPolicy().contains("'unsafe-eval'"));
    }

    @Test
    void acceptsOrdinaryFunctionsAndUsesCspToBlockDynamicStringExecution() {
        String script = "function update() { return 1; } const select = function() { return update(); }; "
                + "document.addEventListener('click', function() { select(); }); "
                + "const example = 'eval(123) and new Function(123)';";
        var result = DeliverablePreviewPolicy.validateSingleFileHtml("<script>" + script + "</script>",
                FRAME_ORIGINS, LOCAL_DIRECTORY);
        assertTrue(result.accepted());
        assertEquals(1, result.inlineScriptHashes().size());
        assertFalse(result.contentSecurityPolicy().contains("unsafe-eval"));
        assertTrue(result.contentSecurityPolicy().contains("sandbox allow-scripts"));
    }

    @Test
    void rejectsInlineEventsJavascriptUrlsAndExternalResourcesInSingleFileMode() {
        var result = DeliverablePreviewPolicy.validateSingleFileHtml("""
                <button onclick="alert(1)">执行</button>
                <a href="javascript:alert(1)">链接</a>
                <link rel="stylesheet" href="https://cdn.example/app.css">
                <script src="https://cdn.example/app.js"></script>
                """, FRAME_ORIGINS, LOCAL_DIRECTORY);

        assertFalse(result.accepted());
        assertTrue(result.violations().stream().anyMatch(v -> v.code().equals("inline_event_handler")));
        assertTrue(result.violations().stream().anyMatch(v -> v.code().equals("javascript_url")));
        assertTrue(result.violations().stream().anyMatch(v -> v.code().equals("external_resource")));
        assertTrue(result.violations().stream().anyMatch(v -> v.code().equals("external_script")));
    }

    @Test
    void acceptsOnlySameRepresentationRelativeResourcesInExplicitMultiFileMode() {
        var result = DeliverablePreviewPolicy.validateMultiFileHtml("""
                <link rel="stylesheet" href="assets/site.css">
                <script src="scripts/app.js"></script>
                <img src="assets/chart.png">
                """, FRAME_ORIGINS, LOCAL_DIRECTORY);

        assertTrue(result.accepted());
        assertTrue(result.contentSecurityPolicy().contains("script-src http://preview.localhost:8091/p/ticket/"));
    }

    @Test
    void rejectsActiveSvgAndExternalNetworkPaths() {
        var result = DeliverablePreviewPolicy.validateSingleFileHtml("""
                <svg><foreignObject><div>active</div></foreignObject><script>alert(1)</script></svg>
                <img src="//cdn.example/chart.png">
                """, FRAME_ORIGINS, LOCAL_DIRECTORY);

        assertFalse(result.accepted());
        assertTrue(result.violations().stream().anyMatch(v -> v.code().equals("svg_script")));
        assertTrue(result.violations().stream().anyMatch(v -> v.code().equals("svg_foreign_object")));
        assertTrue(result.violations().stream().anyMatch(v -> v.code().equals("external_resource")));
    }
}
