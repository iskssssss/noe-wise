CREATE TABLE public.agent_runtime_session_action_preparations (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    journal_ref text NOT NULL CHECK (btrim(journal_ref)<>''),
    preparation_kind text NOT NULL CHECK (preparation_kind IN('TOOL','WORKFLOW','KNOWLEDGE')),
    key_data text NOT NULL CHECK (btrim(key_data)<>''),
    prepared_data text NOT NULL CHECK (btrim(prepared_data)<>''),
    frozen_at_epoch_second bigint NOT NULL,
    frozen_at_nano integer NOT NULL CHECK (frozen_at_nano BETWEEN 0 AND 999999999),
    PRIMARY KEY(tenant_id,journal_ref),
    UNIQUE(tenant_id,run_id,journal_ref),
    FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id),
    FOREIGN KEY(tenant_id,run_id,journal_ref)
        REFERENCES public.agent_runtime_session_action_requests(tenant_id,run_id,journal_ref)
);
ALTER TABLE public.agent_runtime_session_action_preparations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_action_preparations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_action_preparations
    USING(tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE FUNCTION public.agent_runtime_session_action_preparation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'session_action_preparation_immutable'; END;
$$;
CREATE TRIGGER session_action_preparation_immutable BEFORE UPDATE OR DELETE ON public.agent_runtime_session_action_preparations
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_action_preparation_immutable();
COMMENT ON TABLE public.agent_runtime_session_action_preparations IS '外部Agent动作规范调用与重试策略的不可变准备journal';
COMMENT ON COLUMN public.agent_runtime_session_action_preparations.tenant_id IS '原Run租户及RLS分区';
COMMENT ON COLUMN public.agent_runtime_session_action_preparations.run_id IS '必须与V121原请求相同的Run';
COMMENT ON COLUMN public.agent_runtime_session_action_preparations.journal_ref IS '与V121原决策请求相同的稳定引用';
COMMENT ON COLUMN public.agent_runtime_session_action_preparations.preparation_kind IS 'Tool、Workflow或Knowledge封闭类别';
COMMENT ON COLUMN public.agent_runtime_session_action_preparations.key_data IS '完整V121请求key的规范编码';
COMMENT ON COLUMN public.agent_runtime_session_action_preparations.prepared_data IS '规范invocation、调用策略和重试边界，不含lease、bearer或Provider结果';
COMMENT ON COLUMN public.agent_runtime_session_action_preparations.frozen_at_epoch_second IS '首次冻结时间Unix秒';
COMMENT ON COLUMN public.agent_runtime_session_action_preparations.frozen_at_nano IS '首次冻结时间纳秒分量';
COMMENT ON FUNCTION public.agent_runtime_session_action_preparation_immutable() IS '禁止覆写或删除原外部动作准备';
