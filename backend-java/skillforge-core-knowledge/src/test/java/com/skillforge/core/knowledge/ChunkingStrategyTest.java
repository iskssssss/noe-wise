package com.skillforge.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 段落感知分块器单元测试。
 */
class ChunkingStrategyTest {

    @Test
    void shortTextReturnsSingleChunk() {
        List<String> chunks = ChunkingStrategy.split("你好，世界。", 500, 50);
        assertEquals(1, chunks.size());
        assertEquals("你好，世界。", chunks.get(0));
    }

    @Test
    void emptyTextReturnsEmptyList() {
        assertTrue(ChunkingStrategy.split("", 500, 50).isEmpty());
        assertTrue(ChunkingStrategy.split("   \n\n  ", 500, 50).isEmpty());
        assertTrue(ChunkingStrategy.split(null, 500, 50).isEmpty());
    }

    @Test
    void sentencesMergeWithoutCrossingParagraphs() {
        String text = "第一句内容。第二句内容。\n\n第三段开头。第三段结尾。";
        List<String> chunks = ChunkingStrategy.split(text, 15, 0);
        assertEquals(2, chunks.size());
        assertEquals("第一句内容。第二句内容。", chunks.get(0));
        assertEquals("第三段开头。第三段结尾。", chunks.get(1));
    }

    @Test
    void longSentenceIsHardSplit() {
        String sentence = "甲".repeat(600);
        List<String> chunks = ChunkingStrategy.split(sentence, 500, 0);
        assertEquals(2, chunks.size());
        assertEquals(500, chunks.get(0).length());
        assertEquals(100, chunks.get(1).length());
    }

    @Test
    void overlapAppliesAtSentenceBoundary() {
        String text = "句子一。句子二。句子三。句子四。句子五。";
        List<String> chunks = ChunkingStrategy.split(text, 12, 6);
        assertTrue(chunks.size() >= 2);
        // 重叠块以句子开头（跳过句号），且与上一块末尾存在真实重叠
        String second = chunks.get(1);
        assertTrue(second.startsWith("句子"));
        String firstTail = chunks.get(0);
        assertTrue(firstTail.endsWith("。") || second.contains(firstTail.substring(firstTail.length() - 2)));
    }

    @Test
    void englishParagraphSplitsOnSentenceEnds() {
        String text = "First sentence. Second sentence. Third sentence.";
        List<String> chunks = ChunkingStrategy.split(text, 20, 0);
        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.get(0).endsWith("."));
    }

    @Test
    void crlfAndBlankLinesNormalized() {
        String text = "甲。\r\n\r\n乙。";
        List<String> chunks = ChunkingStrategy.split(text, 3, 0);
        assertEquals(2, chunks.size());
        assertEquals("甲。", chunks.get(0));
        assertEquals("乙。", chunks.get(1));
    }
}
