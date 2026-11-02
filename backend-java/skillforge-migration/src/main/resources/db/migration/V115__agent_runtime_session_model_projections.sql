CREATE TABLE public.nw_runtime_session_model_projection_policies (
    policy_ref text CONSTRAINT skillforge_runtime_session_model_projection_policies_pkey PRIMARY KEY CHECK (btrim(policy_ref)<>''),
    policy_data text NOT NULL CHECK (btrim(policy_data)<>''),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE public.nw_runtime_session_model_projections (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    action_id text NOT NULL CHECK (btrim(action_id)<>''),
    invocation_id text NOT NULL CHECK (btrim(invocation_id)<>''),
    adopted_fact_ref text NOT NULL CHECK (btrim(adopted_fact_ref)<>''),
    projection_hash text NOT NULL CHECK (projection_hash ~ '^[a-f0-9]{64}$'),
    key_data text NOT NULL CHECK (btrim(key_data)<>''),
    policy_ref text NOT NULL REFERENCES public.nw_runtime_session_model_projection_policies(policy_ref),
    projection_data text NOT NULL CHECK (btrim(projection_data)<>''),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT skillforge_runtime_session_model_projections_pkey PRIMARY KEY(tenant_id,projection_hash),
    FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id),
    FOREIGN KEY(tenant_id,action_id) REFERENCES public.agent_runtime_actions(tenant_id,action_id)
);
ALTER TABLE public.nw_runtime_session_model_projections ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_model_projections FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_session_model_projections
    USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE FUNCTION public.agent_runtime_session_model_projection_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'session_model_projection_immutable'; END;
$$;
CREATE TRIGGER session_model_projection_immutable BEFORE UPDATE OR DELETE ON public.nw_runtime_session_model_projections
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_model_projection_immutable();
CREATE TRIGGER session_model_projection_policy_immutable BEFORE UPDATE OR DELETE ON public.nw_runtime_session_model_projection_policies
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_model_projection_immutable();
COMMENT ON TABLE public.nw_runtime_session_model_projection_policies IS '受信装配登记的全局转换版本和资源预算，不含租户用户内容';
COMMENT ON COLUMN public.nw_runtime_session_model_projection_policies.policy_ref IS '全局不可变策略引用';
COMMENT ON COLUMN public.nw_runtime_session_model_projection_policies.policy_data IS '完整规范策略字节，版本及预算不可同ref替换';
COMMENT ON COLUMN public.nw_runtime_session_model_projection_policies.recorded_at IS '首次受信登记数据库时间';
COMMENT ON TABLE public.nw_runtime_session_model_projections IS '原模型Completed winner的不可变安全投影journal';
COMMENT ON COLUMN public.nw_runtime_session_model_projections.tenant_id IS '原Run租户';
COMMENT ON COLUMN public.nw_runtime_session_model_projections.run_id IS '原Run标识';
COMMENT ON COLUMN public.nw_runtime_session_model_projections.action_id IS '原模型Action标识';
COMMENT ON COLUMN public.nw_runtime_session_model_projections.invocation_id IS '原模型逻辑调用标识';
COMMENT ON COLUMN public.nw_runtime_session_model_projections.adopted_fact_ref IS '实际resolved-index采用的Completed事实引用';
COMMENT ON COLUMN public.nw_runtime_session_model_projections.projection_hash IS '完整原key规范字节的域分离SHA256，读取仍全值比较';
COMMENT ON COLUMN public.nw_runtime_session_model_projections.key_data IS '完整RunIdentity和adopted fact key';
COMMENT ON COLUMN public.nw_runtime_session_model_projections.policy_ref IS '独立登记的全局转换策略引用';
COMMENT ON COLUMN public.nw_runtime_session_model_projections.projection_data IS '完整准备、原fact、策略与投影envelope规范字节，不含bearer';
COMMENT ON COLUMN public.nw_runtime_session_model_projections.recorded_at IS '首次冻结数据库时间';
COMMENT ON INDEX public.skillforge_runtime_session_model_projection_policies_pkey IS '全局策略引用唯一';
COMMENT ON INDEX public.skillforge_runtime_session_model_projections_pkey IS '每租户原投影key唯一';
COMMENT ON FUNCTION public.agent_runtime_session_model_projection_immutable() IS '拒绝覆盖或删除原投影及策略';
