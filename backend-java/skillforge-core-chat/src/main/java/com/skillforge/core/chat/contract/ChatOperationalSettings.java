package com.skillforge.core.chat.contract;

import com.skillforge.core.foundation.settings.OperationalSettingsContributor;
import com.skillforge.core.foundation.settings.OperationalSettingsRegistry.GroupDefinition;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.bool;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.group;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.integer;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.prompt;
import java.util.*;
import org.springframework.stereotype.Component;
import com.skillforge.prompts.PromptDefaults;

/** 由所属领域注册运营配置定义和校验，基础存储不认识业务字段。 */
@Component
public final class ChatOperationalSettings implements OperationalSettingsContributor {
    @Override
    public List<GroupDefinition> groups() {
        var groups = new ArrayList<GroupDefinition>();
        groups.add(group("memory", "记忆与画像",
                integer("session_ttl_minutes", "会话热数据保留时间", "分钟", 1, 525_600, 1_440),
                integer("profile_summary_idle_minutes", "画像摘要空闲阈值", "分钟", 1, 43_200, 60),
                bool("incremental_merge", "启用增量画像合并", true),
                integer("max_raw_summaries", "近期摘要窗口", "条", 1, 10_000, 20),
                integer("casual_threshold", "闲聊摘要消息阈值", "条", 1, 100_000, 30),
                integer("casual_counter_ttl_days", "闲聊计数保留时间", "天", 1, 3_650, 7)));
        groups.add(group("workspace", "会话工作区",
                bool("enabled", "启用会话工作区", false),
                integer("checkpoint_interval_seconds", "自动快照合并间隔", "秒", 1, 86_400, 300),
                integer("local_idle_hours", "本地副本闲置回收阈值", "小时", 1, 8_760, 24),
                integer("snapshot_history", "保留成功快照数量", "个", 1, 100, 10)));
        groups.add(group("prompts", "平台 Prompt",
                prompt("agent_decision_policy", "Agent 决策策略", PromptDefaults.AGENT_DECISION_POLICY),
                prompt("chat_summary_prompt", "会话画像摘要", PromptDefaults.CHAT_SUMMARY_PROMPT),
                prompt("profile_merge_prompt", "用户画像合并", PromptDefaults.PROFILE_MERGE_PROMPT)));
        return List.copyOf(groups);
    }
}
