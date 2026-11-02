package com.skillforge.institution.team;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TeamTaskResultTest {

    @Test
    void textResultRequiresExplicitContentLimitations() {
        var source = new TeamTaskResult.Source("run_completion", "event:1", "invocation:1", "attempt:1", "run:1");
        assertThrows(IllegalArgumentException.class, () -> new TeamTaskResult(TeamTaskResult.SCHEMA_ID,
                "team-result:1", "task:1", "answer", "答复", "text", "succeeded", "摘要", "正文",
                List.of(), List.of(), null, source, Map.of()));
        assertDoesNotThrow(() -> new TeamTaskResult(TeamTaskResult.SCHEMA_ID,
                "team-result:1", "task:1", "answer", "答复", "text", "succeeded", "摘要", "正文",
                List.of(), List.of("纯文本不包含附件"), null, source, Map.of()));
    }

    @Test
    void artifactRepresentationRequiresVerifiedFileDigest() {
        assertThrows(IllegalArgumentException.class, () -> new TeamTaskResult.Representation(
                "源文件", "application/pdf", "artifact-1", "report.pdf", "source", ""));
        assertDoesNotThrow(() -> new TeamTaskResult.Representation(
                "源文件", "application/pdf", "artifact-1", "report.pdf", "source", "a".repeat(64)));
    }
}
