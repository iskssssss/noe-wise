-- Workflow 外部等待只保存一次原恢复 envelope；该事实本身不授予 dispatch 或 Execution 写权。

CREATE TABLE public.nw_runtime_workflow_wakeups (
    tenant_id                    text NOT NULL,
    execution_id                 varchar(160) NOT NULL,
    execution_revision           bigint NOT NULL,
    session_id                   text NOT NULL,
    target_kind                  text NOT NULL,
    target_id                    text NOT NULL,
    agent_id                     text NOT NULL,
    end_user_id                  bigint NOT NULL,
    run_id                       varchar(160) NOT NULL,
    causation_input_id           varchar(160) NOT NULL,
    causation_identity_ref       text NOT NULL,
    checkpoint_schema_id         text NOT NULL,
    checkpoint_canonical_json    text NOT NULL,
    waiting_suspend_ref          text NOT NULL,
    waiting_action_key           text NOT NULL,
    input_id                     varchar(160) NOT NULL,
    event_id                     varchar(160) NOT NULL,
    source_ref                   text NOT NULL,
    envelope_fingerprint         varchar(64) NOT NULL,
    envelope_data                text NOT NULL,
    frozen_at                    timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT skillforge_runtime_workflow_wakeups_pkey
        PRIMARY KEY (tenant_id, execution_id, execution_revision),
    CONSTRAINT uq_agent_runtime_workflow_wakeup_input
        UNIQUE (tenant_id, input_id),
    CONSTRAINT agent_runtime_workflow_wakeup_execution_fkey
        FOREIGN KEY (tenant_id, execution_id)
        REFERENCES public.agent_runtime_executions
            (tenant_id, execution_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_agent_runtime_workflow_wakeup_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(execution_id) <> ''
        AND execution_revision > 0
        AND btrim(session_id) <> ''
        AND target_kind IN ('AGENT', 'TEAM')
        AND btrim(target_id) <> '' AND btrim(agent_id) <> ''
        AND end_user_id > 0 AND btrim(run_id) <> ''
        AND btrim(causation_input_id) <> ''
        AND btrim(causation_identity_ref) <> ''),
    CONSTRAINT ck_agent_runtime_workflow_wakeup_source CHECK (
        btrim(checkpoint_schema_id) <> ''
        AND btrim(checkpoint_canonical_json) <> ''
        AND btrim(waiting_suspend_ref) <> ''
        AND btrim(waiting_action_key) <> ''
        AND btrim(input_id) <> '' AND btrim(event_id) <> ''
        AND source_ref = 'workflow-wakeup:' || execution_id || ':'
            || execution_revision::text),
    CONSTRAINT ck_agent_runtime_workflow_wakeup_envelope CHECK (
        envelope_fingerprint ~ '^[0-9a-f]{64}$'
        AND btrim(envelope_data) <> ''
        AND jsonb_typeof(envelope_data::jsonb) = 'object')
);

ALTER TABLE public.nw_runtime_workflow_wakeups
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_workflow_wakeups
    FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_workflow_wakeup_tenant_isolation
    ON public.nw_runtime_workflow_wakeups
    USING (tenant_id = COALESCE(
        current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(
        current_setting('app.tenant_id', true), ''));

CREATE FUNCTION public.guard_agent_runtime_workflow_wakeup_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'workflow_wakeup_update_forbidden';
    END IF;
    RAISE EXCEPTION 'workflow_wakeup_delete_forbidden';
    RETURN OLD;
END;
$$;

CREATE TRIGGER guard_agent_runtime_workflow_wakeup_immutable
    BEFORE UPDATE OR DELETE
    ON public.nw_runtime_workflow_wakeups
    FOR EACH ROW
    EXECUTE FUNCTION public.guard_agent_runtime_workflow_wakeup_immutable();

COMMENT ON TABLE public.nw_runtime_workflow_wakeups IS
    'Workflow 外部等待已收敛后冻结的不可变 RECOVERY envelope，不授予新的执行权';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.tenant_id IS
    'Execution 租户及强制 RLS 隔离键';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.execution_id IS
    '产生外部等待的 Workflow Execution';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.execution_revision IS
    '首次冻结时持锁核验的 WAITING_EXTERNAL Execution revision';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.session_id IS
    'Execution 来源的 Session 标识';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.target_kind IS
    'Execution 来源的 Session target 类别';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.target_id IS
    'Execution 来源的 Session target 标识';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.agent_id IS
    '执行原 Workflow 的实际 Agent';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.end_user_id IS
    '原 Run 冻结的终端用户';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.run_id IS
    '首次冻结时最先持锁核验的 façade Run';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.causation_input_id IS
    '原 Run 的稳定因果输入';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.causation_identity_ref IS
    '原 Run 的调用方验证事实引用，必须与 RECOVERY envelope 一致';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.checkpoint_schema_id IS
    '首次冻结时 Execution checkpoint 的 schema';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.checkpoint_canonical_json IS
    '首次冻结时 Execution checkpoint 的完整规范 JSON';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.waiting_suspend_ref IS
    '首次冻结时 WAITING_EXTERNAL 的稳定 suspend 引用';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.waiting_action_key IS
    '首次冻结时 WAITING_EXTERNAL 的稳定 action 路由键';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.input_id IS
    '同租户唯一的恢复输入标识';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.event_id IS
    '冻结 envelope 的稳定事件标识';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.source_ref IS
    '由 Execution 标识与来源 revision 派生的稳定 DelayedInput ResultRef';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.envelope_fingerprint IS
    '完整原始 envelope 编码字节的独立 lowercase SHA-256';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.envelope_data IS
    '宿主 Codec 生成并保持原字节的完整 RECOVERY envelope JSON';
COMMENT ON COLUMN public.nw_runtime_workflow_wakeups.frozen_at IS
    '首次冻结 wakeup 的数据库审计时间';
COMMENT ON FUNCTION public.guard_agent_runtime_workflow_wakeup_immutable() IS
    '拒绝改写或删除已冻结的 Workflow wakeup';
