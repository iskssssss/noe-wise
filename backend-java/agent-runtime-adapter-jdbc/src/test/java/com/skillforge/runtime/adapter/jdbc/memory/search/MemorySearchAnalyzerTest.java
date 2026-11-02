package com.skillforge.runtime.adapter.jdbc.memory.search;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MemorySearchAnalyzerTest {
    @Test
    void usesLuceneCjkBigramsAndWidthCaseNormalizationWithStableDistinctOrder() {
        assertEquals("lucene-cjk-9.12.3-v1", MemorySearchAnalyzer.VERSION);
        assertEquals(List.of("中华", "华人", "人民", "民共", "共和", "和国"),
                MemorySearchAnalyzer.tokens("中华人民共和国"));
        assertEquals(List.of("中文", "english", "abc"),
                MemorySearchAnalyzer.tokens("中文 English ＡＢＣ 中文 ENGLISH"));
    }

    @Test
    void doesNotInjectScenarioSynonymsOrRemoveDomainWords() {
        assertEquals(List.of("网页"), MemorySearchAnalyzer.tokens("网页"));
        assertEquals(List.of("网站"), MemorySearchAnalyzer.tokens("网站"));
        assertEquals(List.of("深色"), MemorySearchAnalyzer.tokens("深色"));
        assertEquals(List.of(), MemorySearchAnalyzer.tokens(" \n\t"));
        assertThrows(NullPointerException.class, () -> MemorySearchAnalyzer.tokens(null));
        assertThrows(UnsupportedOperationException.class, () -> MemorySearchAnalyzer.tokens("中文").add("外加词"));
    }

    @Test
    void quotesEveryPostgresLexemeWithoutTurningItsTextIntoOperators() {
        assertEquals("'中文' | 'o''reilly' | 'path\\\\name' | 'a|b' | '!excluded' | 'x:y'",
                MemorySearchAnalyzer.queryLexemes(List.of("中文", "o'reilly", "path\\name", "a|b", "!excluded", "x:y", "中文")));
        assertEquals("'''; drop table memory; --'", MemorySearchAnalyzer.queryLexemes(List.of("'; drop table memory; --")));
        assertEquals("", MemorySearchAnalyzer.queryLexemes(List.of()));
        assertThrows(IllegalArgumentException.class, () -> MemorySearchAnalyzer.queryLexemes(List.of("")));
        assertThrows(IllegalArgumentException.class, () -> MemorySearchAnalyzer.queryLexemes(List.of("a\0b")));
    }
}
