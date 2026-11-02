CREATE TABLE public.nw_runtime_session_knowledge_observations (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    journal_ref text NOT NULL CHECK (btrim(journal_ref)<>''),
    fact_ref text NOT NULL CHECK (btrim(fact_ref)<>''),
    key_data text NOT NULL CHECK (btrim(key_data)<>''),
    observation_data text NOT NULL CHECK (btrim(observation_data)<>''),
    occurred_at_epoch_second bigint NOT NULL,
    occurred_at_nano integer NOT NULL CHECK (occurred_at_nano BETWEEN 0 AND 999999999),
    PRIMARY KEY(tenant_id,journal_ref,fact_ref),
    FOREIGN KEY(tenant_id,run_id,journal_ref)
        REFERENCES public.agent_runtime_session_action_preparations(tenant_id,run_id,journal_ref),
    FOREIGN KEY(tenant_id,fact_ref) REFERENCES public.agent_runtime_action_attempt_facts(tenant_id,fact_ref),
    FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id)
);
ALTER TABLE public.nw_runtime_session_knowledge_observations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_knowledge_observations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_session_knowledge_observations
    USING(tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE FUNCTION public.agent_runtime_session_knowledge_observation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'session_knowledge_observation_immutable'; END;
$$;
CREATE TRIGGER session_knowledge_observation_immutable BEFORE UPDATE OR DELETE ON public.nw_runtime_session_knowledge_observations
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_knowledge_observation_immutable();
COMMENT ON TABLE public.nw_runtime_session_knowledge_observations IS 'Knowledge采用事实对应的安全Observation不可变journal';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_observations.tenant_id IS '原Run租户及RLS分区';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_observations.run_id IS '必须与V122准备相同的Run';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_observations.journal_ref IS '与V122准备相同的稳定请求引用';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_observations.fact_ref IS 'V99中已采用的Knowledge结果事实';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_observations.key_data IS '请求key与采用fact引用的规范编码';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_observations.observation_data IS 'Core规范编码的FrozenKnowledgeObservation';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_observations.occurred_at_epoch_second IS '首次冻结时间Unix秒';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_observations.occurred_at_nano IS '首次冻结时间纳秒分量';
COMMENT ON FUNCTION public.agent_runtime_session_knowledge_observation_immutable() IS '禁止覆写或删除Knowledge安全Observation';
