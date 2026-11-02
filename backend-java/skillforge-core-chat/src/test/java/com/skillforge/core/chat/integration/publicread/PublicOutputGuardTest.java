package com.skillforge.core.chat.integration.publicread;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PublicOutputGuardTest {
    @Test void apiKeyAssignmentsAreRejectedInBothCompleteAndStreamingOutputAcrossEverySplit() {
        var guard = new PublicOutputGuard("private-artifacts").capture();
        for (String field : List.of("api_key", "api-key", "apikey", "OPENAI_API_KEY", "x-api-key")) {
            String assignment = field + "=\"real-secret-value\" ";
            assertTrue(guard.rejects(assignment), field);
            for (int split = 0; split <= assignment.length(); split++) {
                String accumulated = "可公开说明 ".repeat(50) + assignment.substring(0, split);
                var first = guard.streamingContent(accumulated);
                String firstPublic = accumulated.substring(0, first.safePrefixCharacters());
                assertFalse(firstPublic.contains("real-secret-value"), field + " split=" + split);
                accumulated += assignment.substring(split) + "后续普通文本 ".repeat(100);
                var second = guard.streamingContent(accumulated);
                assertTrue(second.rejection().isPresent(), field + " split=" + split);
                assertEquals(0, second.safePrefixCharacters());
            }
        }
    }

    @Test void longUnclosedApiKeyIsHeldFromItsFieldStartUntilItCanBeRejected() {
        var guard = new PublicOutputGuard("private-artifacts").capture();
        String prefix = "公开说明 ".repeat(100);
        String accumulated = prefix + "api_key=\"" + "secret".repeat(200);
        var checked = guard.streamingContent(accumulated);
        assertTrue(checked.safePrefixCharacters() <= prefix.length());
        assertFalse(accumulated.substring(0, checked.safePrefixCharacters()).contains("secret"));
        assertTrue(guard.streamingContent(accumulated + "\" ").rejection().isPresent());
    }

    @Test void apiKeyFieldDocumentationAndExplicitPlaceholdersStayPublic() {
        var guard = new PublicOutputGuard("private-artifacts").capture();
        for (String text : List.of("配置 api_key 字段", "api_key=example", "api-key=your-api-key", "apikey=${API_KEY}"))
            assertFalse(guard.rejects(text), text);
    }
}
