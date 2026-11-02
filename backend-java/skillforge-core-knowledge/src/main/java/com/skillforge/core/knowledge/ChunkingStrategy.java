package com.skillforge.core.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * 段落感知文本分块器（对齐 Dify 分段语义，按字符计）。
 *
 * <p>切分优先级：空行分段 > 句末标点分句（标点保留句尾）> 单换行分句；
 * 句子按最大长度贪心合并，超长单句按长度硬切兜底；相邻分块按重叠长度
 * 回退到句子边界（避免从句子中间截断），无边界时退化为字符级重叠。
 */
public final class ChunkingStrategy {

    /** 句末终止符（保留在句尾），含中英文标点。 */
    private static final String SENTENCE_END = "。！？!?；;.";

    private ChunkingStrategy() {
    }

    /**
     * 按段落感知策略切分文本。
     *
     * @param text      原始文本，可为空
     * @param maxLength 单块最大字符数，至少 1
     * @param overlap   相邻块重叠字符数，至少 0
     * @return 分块列表；空文本返回空列表
     */
    public static List<String> split(String text, int maxLength, int overlap) {
        int size = Math.max(1, maxLength);
        int ov = Math.max(0, overlap);
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return List.of();
        }
        if (normalized.length() <= size) {
            return List.of(normalized);
        }
        List<String> sentences = splitSentences(normalized);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (current.length() > 0 && current.length() + sentence.length() > size) {
                chunks.add(current.toString());
                current.setLength(0);
                current.append(overlapSuffix(chunks, ov));
            }
            current.append(sentence);
            // 超长单句：按最大长度硬切（保留现状兜底）
            while (current.length() > size) {
                String head = current.substring(0, size);
                chunks.add(head);
                String rest = current.substring(size);
                current.setLength(0);
                current.append(rest);
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /**
     * 文本归一化：去首尾空白、统一换行、压缩连续空白行。
     *
     * @param text 原始文本
     * @return 归一化文本
     */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        normalized = normalized.replaceAll("\n\\s*\n+", "\n\n");
        return normalized;
    }

    /**
     * 按空行 > 句末标点 > 换行的优先级展开为句子列表。
     *
     * @param text 归一化文本
     * @return 句子列表
     */
    private static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        for (String paragraph : text.split("\n\n", -1)) {
            if (paragraph.isBlank()) {
                continue;
            }
            StringBuilder sentence = new StringBuilder();
            for (int i = 0; i < paragraph.length(); i++) {
                char c = paragraph.charAt(i);
                sentence.append(c);
                if (SENTENCE_END.indexOf(c) >= 0) {
                    sentences.add(sentence.toString());
                    sentence.setLength(0);
                } else if (c == '\n') {
                    // 段落内单换行：句子已由终止符切分，换行归入当前句
                    if (sentence.length() > 1) {
                        sentences.add(sentence.toString());
                        sentence.setLength(0);
                    }
                }
            }
            if (sentence.length() > 0) {
                sentences.add(sentence.toString());
            }
        }
        return sentences;
    }

    /**
     * 取上一块的末尾重叠片段（优先从句子边界截取，退化字符级）。
     *
     * @param chunks 已完成的分块列表
     * @param overlap 重叠字符数
     * @return 重叠片段；无上一块或重叠为 0 时返回空串
     */
    private static String overlapSuffix(List<String> chunks, int overlap) {
        if (chunks.isEmpty() || overlap <= 0) {
            return "";
        }
        String last = chunks.get(chunks.size() - 1);
        if (last.length() <= overlap) {
            return last;
        }
        String suffix = last.substring(last.length() - overlap);
        // 从开头找第一个句子边界，从边界之后截取（终止符归上一句）
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (SENTENCE_END.indexOf(c) >= 0 || c == '\n') {
                return suffix.substring(i + 1);
            }
        }
        return suffix;
    }
}
