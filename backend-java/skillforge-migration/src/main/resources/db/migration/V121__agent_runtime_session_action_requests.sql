CREATE TABLE public.agent_runtime_session_action_requests (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    journal_ref text NOT NULL CHECK (btrim(journal_ref)<>''),
    request_kind text NOT NULL CHECK (request_kind IN('TOOL','WORKFLOW','KNOWLEDGE','DELEGATION')),
    key_data text NOT NULL CHECK (btrim(key_data)<>''),
    request_data text NOT NULL CHECK (btrim(request_data)<>''),
    recorded_at_epoch_second bigint NOT NULL,
    recorded_at_nano integer NOT NULL CHECK (recorded_at_nano BETWEEN 0 AND 999999999),
    PRIMARY KEY(tenant_id,journal_ref),
    UNIQUE(tenant_id,run_id,journal_ref),
    FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id)
);
ALTER TABLE public.agent_runtime_session_action_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_action_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_action_requests
    USING(tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE FUNCTION public.agent_runtime_session_action_request_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'session_action_request_immutable'; END;
$$;
CREATE TRIGGER session_action_request_immutable BEFORE UPDATE OR DELETE ON public.agent_runtime_session_action_requests
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_action_request_immutable();
COMMENT ON TABLE public.agent_runtime_session_action_requests IS '外部Agent动作规范决策与完整原Run作用域的不可变请求journal';
COMMENT ON COLUMN public.agent_runtime_session_action_requests.tenant_id IS '原Run租户及RLS分区';
COMMENT ON COLUMN public.agent_runtime_session_action_requests.run_id IS '原已提交Session Run';
COMMENT ON COLUMN public.agent_runtime_session_action_requests.journal_ref IS 'Session checkpoint持有的稳定请求引用';
COMMENT ON COLUMN public.agent_runtime_session_action_requests.request_kind IS 'Tool、Workflow、Knowledge或Delegation封闭类别';
COMMENT ON COLUMN public.agent_runtime_session_action_requests.key_data IS '完整RunIdentity、Loop、iteration、Action和invocation作用域';
COMMENT ON COLUMN public.agent_runtime_session_action_requests.request_data IS '规范决策、原model-result事件、可选本地结果引用及首次数据库时间，不含Provider许可';
COMMENT ON COLUMN public.agent_runtime_session_action_requests.recorded_at_epoch_second IS '首次冻结时间的Unix秒，无损恢复Instant';
COMMENT ON COLUMN public.agent_runtime_session_action_requests.recorded_at_nano IS '首次冻结时间的纳秒分量，无损恢复Instant';
COMMENT ON FUNCTION public.agent_runtime_session_action_request_immutable() IS '禁止覆写或删除原外部动作请求';
