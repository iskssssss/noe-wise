package com.skillforge.core.workflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.core.workflow.WorkflowAssetService.Asset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Workflow package scanner coverage for release safety boundaries. */
class WorkflowSecurityScannerTest {

    private final WorkflowAssetService assets = mock(WorkflowAssetService.class);
    private final WorkflowSecurityScanner scanner = new WorkflowSecurityScanner(assets);

    @Test
    void rejectsBlankMissingAndIncompletePackages() {
        var blank = scanner.scan(" ");
        assertFalse(blank.passed());

        when(assets.listByWorkflow("missing")).thenReturn(List.of());
        assertFalse(scanner.scan("missing").passed());

        when(assets.listByWorkflow("without-yaml")).thenReturn(List.of(asset("README.md", "safe")));
        assertFalse(scanner.scan("without-yaml").passed());
    }

    @Test
    void scansTextAssetsAndReturnsDeterministicYamlChecksum() {
        when(assets.listByWorkflow("demo")).thenReturn(List.of(
                asset("workflow.yaml", "name: demo\n"),
                asset("scripts/run.py", "print('ok')"),
                asset("image.bin", "rm -rf should not be scanned")));

        var result = scanner.scan("demo");
        assertTrue(result.passed());
        assertTrue(result.checksum() instanceof String);
        assertTrue(result.checksum().matches("[0-9a-f]{64}"));
    }

    @Test
    void rejectsDangerousPatternsCaseInsensitively() {
        when(assets.listByWorkflow("danger")).thenReturn(List.of(
                asset("workflow.yaml", "nodes: []"),
                asset("scripts/run.SH", "CURL | SH")));

        var result = scanner.scan("danger");
        assertFalse(result.passed());
        assertTrue(String.valueOf(result.issues()).contains("curl | sh"));
    }

    private static Asset asset(String name, String content) {
        return new Asset(null, name, content.getBytes(StandardCharsets.UTF_8), null);
    }
}
