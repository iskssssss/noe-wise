package com.skillforge.core.chat.profile;

import com.skillforge.core.chat.persistence.ChatMessageDO;
import java.util.List;

/**
 * 记忆总结输入防护（docs/02-架构与设计/07-记忆管理.md §2.1 空转防护底线）。
 *
 * <p>工作流完成总结与闲聊总结共用：过滤 {@code role=system} 与空/纯标点消息；
 * 有效消息不足或总字数过少时跳过 LLM 调用，节约 Token 且避免无意义画像。
 */
public final class MemorySummarizeGuard {

    /** 有效消息条数下限（低于则跳过总结）。 */
    private static final int MIN_MEANINGFUL_MESSAGES = 2;

    /** 有效消息总字数下限（低于则跳过总结）。 */
    private static final int MIN_MEANINGFUL_CHARS = 10;

    private MemorySummarizeGuard() {
    }

    /**
     * 判断输入消息是否值得调用 LLM 总结（空转防护）。
     *
     * @param messages 会话消息（原始）
     * @return true=应跳过总结
     */
    public static boolean shouldSkip(List<ChatMessageDO> messages) {
        List<ChatMessageDO> meaningful = meaningful(messages);
        if (meaningful.size() < MIN_MEANINGFUL_MESSAGES) {
            return true;
        }
        int chars = meaningful.stream()
                .mapToInt(message -> message.getContent() == null ? 0 : message.getContent().length())
                .sum();
        return chars < MIN_MEANINGFUL_CHARS;
    }

    /**
     * 过滤出值得总结的消息：排除 system 角色与空/纯标点消息。
     *
     * @param messages 会话消息（原始）
     * @return 有效消息列表
     */
    public static List<ChatMessageDO> meaningful(List<ChatMessageDO> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .filter(message -> message.getContent() != null && !message.getContent().isBlank())
                .filter(message -> !"system".equals(message.getRole()))
                .filter(message -> !isPunctuationOnly(message.getContent()))
                .toList();
    }

    /**
     * 纯标点判定：剔除空白与标点符号后无剩余字符即为纯标点。
     *
     * @param content 消息内容
     * @return 是否纯标点
     */
    private static boolean isPunctuationOnly(String content) {
        return content.replaceAll("[\\p{P}\\p{S}\\s]", "").isEmpty();
    }
}
