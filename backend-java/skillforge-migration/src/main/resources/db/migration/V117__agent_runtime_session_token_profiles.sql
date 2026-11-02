CREATE TABLE public.agent_runtime_session_token_profiles (
    tenant_id text NOT NULL CHECK (btrim(tenant_id)<>''),
    profile_ref text NOT NULL CHECK (btrim(profile_ref)<>''),
    profile_data text NOT NULL CHECK (btrim(profile_data)<>''),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,profile_ref)
);
ALTER TABLE public.agent_runtime_session_token_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_token_profiles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_token_profiles
    USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE FUNCTION public.agent_runtime_session_token_profile_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'session_token_profile_immutable'; END;
$$;
CREATE TRIGGER session_token_profile_immutable BEFORE UPDATE OR DELETE ON public.agent_runtime_session_token_profiles
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_token_profile_immutable();
COMMENT ON TABLE public.agent_runtime_session_token_profiles IS '受信登记的不可变tokenizer配置，完整target仍须由准备阶段真实binding解析验证';
COMMENT ON COLUMN public.agent_runtime_session_token_profiles.tenant_id IS '固定registry租户，必须与完整target binding租户一致';
COMMENT ON COLUMN public.agent_runtime_session_token_profiles.profile_ref IS '本租户唯一原配置引用，配置更新须使用新ref';
COMMENT ON COLUMN public.agent_runtime_session_token_profiles.profile_data IS '完整Profile规范字节，包含版本、编码、原target、角色开销、framing与资源预算';
COMMENT ON COLUMN public.agent_runtime_session_token_profiles.recorded_at IS '首次受信登记数据库时间';
COMMENT ON INDEX public.agent_runtime_session_token_profiles_pkey IS '租户内原profile引用唯一';
COMMENT ON FUNCTION public.agent_runtime_session_token_profile_immutable() IS '拒绝覆盖或删除已登记tokenizer配置';
