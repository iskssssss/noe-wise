package com.skillforge.core.chat.profile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.core.chat.persistence.ChatMessageDO;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 记忆总结空转防护单元测试（docs/02-架构与设计/07-记忆管理.md §2.1）。
 *
 * <p>覆盖：system 消息过滤、空/纯标点消息过滤、有效消息条数与总字数下限。
 */
class MemorySummarizeGuardTest {

    /**
     * 构造消息。
     *
     * @param role    角色
     * @param content 内容
     * @return 消息实体
     */
    private ChatMessageDO message(String role, String content) {
        ChatMessageDO row = new ChatMessageDO();
        row.setRole(role);
        row.setContent(content);
        return row;
    }

    /**
     * 有效消息不足 2 条时跳过。
     */
    @Test
    void skipsWhenFewerThanTwoMeaningfulMessages() {
        List<ChatMessageDO> messages = List.of(
                message("user", "您好"));
        assertTrue(MemorySummarizeGuard.shouldSkip(messages));
    }

    /**
     * 有效消息总字数不足 10 时跳过。
     */
    @Test
    void skipsWhenTotalCharsBelowTen() {
        List<ChatMessageDO> messages = List.of(
                message("user", "在吗"),
                message("assistant", "在的"));
        assertTrue(MemorySummarizeGuard.shouldSkip(messages));
    }

    /**
     * system 消息不参与有效统计（画像注入片段不应污染画像）。
     */
    @Test
    void systemMessagesAreExcluded() {
        List<ChatMessageDO> messages = List.of(
                message("system", "知识库参考: 很长的一段系统注入内容……"),
                message("user", "我有糖尿病史"),
                message("assistant", "已记录，建议内分泌科随访"));
        assertFalse(MemorySummarizeGuard.shouldSkip(messages));
    }

    /**
     * 纯标点/空消息被过滤（空转防护不调用 LLM）。
     */
    @Test
    void punctuationOnlyMessagesAreExcluded() {
        List<ChatMessageDO> messages = List.of(
                message("user", "。。。。。"),
                message("assistant", "！？！？"),
                message("user", "你好"));
        assertTrue(MemorySummarizeGuard.shouldSkip(messages));
    }

    /**
     * 正常对话不跳过（有效条数与字数达标）。
     */
    @Test
    void meaningfulConversationIsNotSkipped() {
        List<ChatMessageDO> messages = List.of(
                message("user", "我想提交明天上午的服务申请"),
                message("assistant", "好的，明天上午 10:00 可以办理，确认吗？"),
                message("user", "确认，麻烦提交"));
        assertFalse(MemorySummarizeGuard.shouldSkip(messages));
    }
}
