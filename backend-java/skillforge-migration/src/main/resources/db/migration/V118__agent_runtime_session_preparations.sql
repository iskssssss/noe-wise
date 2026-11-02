CREATE TABLE public.agent_runtime_session_preparation_profiles (
    tenant_id text NOT NULL CHECK (btrim(tenant_id)<>''),
    profile_ref text NOT NULL CHECK (btrim(profile_ref)<>''),
    profile_data text NOT NULL CHECK (btrim(profile_data)<>''),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,profile_ref)
);
CREATE TABLE public.nw_runtime_session_preparations (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    preparation_hash text NOT NULL CHECK (preparation_hash ~ '^[a-f0-9]{64}$'),
    key_data text NOT NULL CHECK (btrim(key_data)<>''),
    profile_ref text NOT NULL,
    payload_data bytea NOT NULL CHECK (octet_length(payload_data) > 0),
    prepared_data text NOT NULL CHECK (btrim(prepared_data)<>''),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT skillforge_runtime_session_preparations_pkey PRIMARY KEY(tenant_id,preparation_hash),
    FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id),
    FOREIGN KEY(tenant_id,profile_ref) REFERENCES public.agent_runtime_session_preparation_profiles(tenant_id,profile_ref)
);
ALTER TABLE public.agent_runtime_session_preparation_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_preparation_profiles FORCE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_preparations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_preparations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_preparation_profiles USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE POLICY tenant_isolation ON public.nw_runtime_session_preparations USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE FUNCTION public.agent_runtime_session_preparation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'session_preparation_immutable'; END;
$$;
CREATE TRIGGER session_preparation_immutable BEFORE UPDATE OR DELETE ON public.nw_runtime_session_preparations FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_preparation_immutable();
CREATE TRIGGER session_preparation_profile_immutable BEFORE UPDATE OR DELETE ON public.agent_runtime_session_preparation_profiles FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_preparation_immutable();
COMMENT ON TABLE public.agent_runtime_session_preparation_profiles IS '受信登记的原输入渲染与模型准备配置，不从候选自授权';
COMMENT ON COLUMN public.agent_runtime_session_preparation_profiles.tenant_id IS '配置所属租户，必须匹配binding';
COMMENT ON COLUMN public.agent_runtime_session_preparation_profiles.profile_ref IS '本租户原配置引用';
COMMENT ON COLUMN public.agent_runtime_session_preparation_profiles.profile_data IS '完整原配置规范字节，版本、历史窗口、预算和模型选择不可覆盖';
COMMENT ON COLUMN public.agent_runtime_session_preparation_profiles.recorded_at IS '受信首次登记数据库时间';
COMMENT ON TABLE public.nw_runtime_session_preparations IS '绑定原Run版本与不可变输入、事件、能力、tokenizer及发布model版本的准备锚点';
COMMENT ON COLUMN public.nw_runtime_session_preparations.tenant_id IS '原Run租户';
COMMENT ON COLUMN public.nw_runtime_session_preparations.run_id IS '原Run标识';
COMMENT ON COLUMN public.nw_runtime_session_preparations.preparation_hash IS '完整key规范字节域分离SHA256，读取仍全值比较';
COMMENT ON COLUMN public.nw_runtime_session_preparations.key_data IS '完整RunIdentity、loop和iteration';
COMMENT ON COLUMN public.nw_runtime_session_preparations.profile_ref IS '独立登记原配置引用';
COMMENT ON COLUMN public.nw_runtime_session_preparations.prepared_data IS '冻结来源与计量元数据，sourceRun checkpoint 为不可变引用，正文保存于 payload_data';
COMMENT ON COLUMN public.nw_runtime_session_preparations.recorded_at IS '首次冻结数据库时间';
COMMENT ON INDEX public.agent_runtime_session_preparation_profiles_pkey IS '本租户profile引用唯一';
COMMENT ON INDEX public.skillforge_runtime_session_preparations_pkey IS '本租户原准备key唯一';
COMMENT ON FUNCTION public.agent_runtime_session_preparation_immutable() IS '拒绝覆盖或删除原准备及配置';

COMMENT ON COLUMN public.nw_runtime_session_preparations.payload_data IS '独立保存一次的模型准备正文规范二进制；checkpoint 只保存准备 key';
