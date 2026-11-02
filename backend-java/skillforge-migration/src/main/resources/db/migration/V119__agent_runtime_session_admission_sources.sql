-- 新 Run 的显式初始化来源；Callback/Scheduler 延迟来源继续读取各自原账本。
CREATE TABLE public.nw_runtime_session_initial_runs (
    tenant_id text NOT NULL CHECK (btrim(tenant_id) <> ''),
    agent_id text NOT NULL CHECK (btrim(agent_id) <> ''),
    session_id text NOT NULL CHECK (btrim(session_id) <> ''),
    end_user_id bigint NOT NULL CHECK (end_user_id > 0),
    input_id text NOT NULL CHECK (btrim(input_id) <> ''),
    request_fingerprint text NOT NULL CHECK (btrim(request_fingerprint) <> ''),
    preparation_profile_ref text NOT NULL CHECK (btrim(preparation_profile_ref) <> ''),
    plan_data text NOT NULL CHECK (btrim(plan_data) <> ''),
    frozen_second bigint NOT NULL,
    frozen_nano integer NOT NULL CHECK (frozen_nano BETWEEN 0 AND 999999999),
    CONSTRAINT agent_runtime_session_initial_run_pk PRIMARY KEY (tenant_id,agent_id,session_id,input_id),
    CONSTRAINT agent_runtime_session_initial_run_owner_fk FOREIGN KEY (tenant_id,agent_id,session_id,end_user_id)
        REFERENCES public.agent_runtime_session_owners(tenant_id,agent_id,session_id,end_user_id) ON DELETE RESTRICT
);

CREATE FUNCTION public.guard_agent_runtime_session_initial_run() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' OR TG_OP = 'DELETE' THEN RAISE EXCEPTION 'session_initial_run_immutable'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_runtime_session_initial_run_guard BEFORE UPDATE OR DELETE ON public.nw_runtime_session_initial_runs
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_initial_run();

ALTER TABLE public.nw_runtime_session_initial_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_initial_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_session_initial_run_tenant ON public.nw_runtime_session_initial_runs
    USING (tenant_id=COALESCE(current_setting('app.tenant_id',true),''))
    WITH CHECK (tenant_id=COALESCE(current_setting('app.tenant_id',true),''));

COMMENT ON TABLE public.nw_runtime_session_initial_runs IS 'SessionAdmission 前显式冻结的新 Run budget、mode、目标、模型输入和准备 profile';
COMMENT ON COLUMN public.nw_runtime_session_initial_runs.request_fingerprint IS '绑定原 InboundEnvelope 的完整业务指纹';
COMMENT ON COLUMN public.nw_runtime_session_initial_runs.preparation_profile_ref IS '后续模型准备必须采用的不可变 profile 引用';
COMMENT ON COLUMN public.nw_runtime_session_initial_runs.plan_data IS '完整 SessionAdmissionFacts.InitialRun 规范快照';
COMMENT ON COLUMN public.nw_runtime_session_initial_runs.frozen_second IS '初始化配置首次冻结时间 epoch 秒，不在重放时刷新';
COMMENT ON COLUMN public.nw_runtime_session_initial_runs.frozen_nano IS '初始化配置首次冻结时间纳秒分量';
