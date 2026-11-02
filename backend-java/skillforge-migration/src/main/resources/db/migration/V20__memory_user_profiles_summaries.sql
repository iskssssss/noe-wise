-- V20: 画像双字段（M1）—— user_profiles 增加 summaries 原始总结累积（jsonb 数组）
-- 权威设计：docs/记忆管理.md §2.2（分层/增量合并：summaries 近期明细窗口 + 旧 summary 合并，成本恒定）

ALTER TABLE public.nw_user_profiles ADD COLUMN summaries jsonb DEFAULT '[]'::jsonb NOT NULL;
-- jsonb 保留理由：固定键集的对象数组（{content, created_at}），随窗口裁剪整体读写，无需单列拆分

COMMENT ON COLUMN public.nw_user_profiles.summaries IS '原始总结累积（jsonb 数组，条目 {content, created_at}）：技能完成总结 / 闲聊总结追加，仅保留最近 max-raw-summaries 条窗口，超窗口由 LLM 合并进 summary 后弹出最老条目';
