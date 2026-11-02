-- MemoryFact 按 scope + tenant/user/agent/session 归属；来源 Session target 位于 fact_sources。
-- V219 的通用 Session 列扩展误覆盖了事实与 head 表，这两表的 target 列没有领域或写入消费者。
-- 只移除同表误加的 target 触发器、检查和列，不删除事实、来源、操作回执，也不变更 RLS。
-- 使用 RESTRICT：存在额外外部依赖时迁移失败，不级联删除其他对象。
-- PL/pgSQL 对 NEW 字段的引用不形成列依赖，必须显式移除错误触发器才能继续更新 head。
DROP TRIGGER agent_runtime_session_target_immutable
    ON public.agent_runtime_memory_fact_revisions;
DROP TRIGGER agent_runtime_session_target_immutable
    ON public.agent_runtime_memory_fact_heads;

ALTER TABLE public.agent_runtime_memory_fact_revisions
    DROP CONSTRAINT agent_runtime_memory_fact_revisions_target_check,
    DROP COLUMN target_kind RESTRICT,
    DROP COLUMN target_id RESTRICT;

ALTER TABLE public.agent_runtime_memory_fact_heads
    DROP CONSTRAINT agent_runtime_memory_fact_heads_target_check,
    DROP COLUMN target_kind RESTRICT,
    DROP COLUMN target_id RESTRICT;
