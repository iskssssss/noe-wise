-- USER 作用域：归属 agent_id 可空，来源 agent 仍在 fact_sources；USER_AGENT 缺 agent 仍非法。

ALTER TABLE public.agent_runtime_memory_fact_revisions
    ALTER COLUMN agent_id DROP NOT NULL;
ALTER TABLE public.agent_runtime_memory_fact_heads
    ALTER COLUMN agent_id DROP NOT NULL;

ALTER TABLE public.agent_runtime_memory_fact_revisions
    DROP CONSTRAINT ck_agent_runtime_memory_revision_owner,
    DROP CONSTRAINT ck_agent_runtime_memory_revision_scope,
    DROP CONSTRAINT ck_agent_runtime_memory_revision_session_scope;
ALTER TABLE public.agent_runtime_memory_fact_revisions
    ADD CONSTRAINT ck_agent_runtime_memory_revision_owner
        CHECK (end_user_id > 0 AND btrim(type_id) <> ''
            AND ((scope = 'USER' AND agent_id IS NULL)
                OR (scope IN ('SESSION', 'USER_AGENT')
                    AND agent_id IS NOT NULL AND btrim(agent_id) <> ''))),
    ADD CONSTRAINT ck_agent_runtime_memory_revision_scope
        CHECK (scope IN ('SESSION', 'USER_AGENT', 'USER')),
    ADD CONSTRAINT ck_agent_runtime_memory_revision_session_scope
        CHECK ((scope = 'SESSION' AND session_id = source_session_id AND agent_id IS NOT NULL)
            OR (scope = 'USER_AGENT' AND session_id IS NULL AND agent_id IS NOT NULL)
            OR (scope = 'USER' AND session_id IS NULL AND agent_id IS NULL));

ALTER TABLE public.agent_runtime_memory_fact_heads
    DROP CONSTRAINT ck_agent_runtime_memory_head_identity,
    DROP CONSTRAINT ck_agent_runtime_memory_head_scope;
ALTER TABLE public.agent_runtime_memory_fact_heads
    ADD CONSTRAINT ck_agent_runtime_memory_head_identity
        CHECK (current_revision > 0 AND end_user_id > 0 AND btrim(type_id) <> ''
            AND ((scope = 'USER' AND agent_id IS NULL)
                OR (scope IN ('SESSION', 'USER_AGENT')
                    AND agent_id IS NOT NULL AND btrim(agent_id) <> ''))),
    ADD CONSTRAINT ck_agent_runtime_memory_head_scope
        CHECK ((scope = 'SESSION' AND session_id IS NOT NULL AND btrim(session_id) <> ''
                AND agent_id IS NOT NULL)
            OR (scope = 'USER_AGENT' AND session_id IS NULL AND agent_id IS NOT NULL)
            OR (scope = 'USER' AND session_id IS NULL AND agent_id IS NULL));

COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.agent_id IS
    '事实归属 Agent；USER 为空，USER_AGENT/SESSION 必填';
COMMENT ON COLUMN public.agent_runtime_memory_fact_heads.agent_id IS
    '事实归属 Agent；USER 为空，USER_AGENT/SESSION 必填';
COMMENT ON COLUMN public.agent_runtime_memory_fact_sources.agent_id IS
    '原始来源会话的写入 Agent，与归属 agent 分离';
