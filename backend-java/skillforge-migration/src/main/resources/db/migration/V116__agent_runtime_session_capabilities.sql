CREATE TABLE public.nw_runtime_session_capabilities (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    input_id text NOT NULL CHECK (btrim(input_id)<>''),
    snapshot_id text NOT NULL CHECK (snapshot_id=input_id||':capability-mount-snapshot'),
    generation bigint NOT NULL CHECK (generation>0),
    snapshot_data text NOT NULL CHECK (btrim(snapshot_data)<>''),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,agent_id,session_id,input_id),
    CONSTRAINT session_capabilities_owner_fkey FOREIGN KEY(tenant_id,agent_id,session_id,end_user_id)
        REFERENCES public.agent_runtime_session_owners(tenant_id,agent_id,session_id,end_user_id)
);
ALTER TABLE public.nw_runtime_session_capabilities ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_capabilities FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_session_capabilities
    USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE FUNCTION public.skillforge_runtime_session_capabilities_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'session_capability_snapshot_immutable'; END;
$$;
CREATE TRIGGER session_capabilities_immutable BEFORE UPDATE OR DELETE ON public.nw_runtime_session_capabilities
    FOR EACH ROW EXECUTE FUNCTION public.skillforge_runtime_session_capabilities_immutable();
COMMENT ON TABLE public.nw_runtime_session_capabilities IS '输入准入前按当前IAM与原发布mount冻结的完整能力来源，不代替当前调用权限';
COMMENT ON COLUMN public.nw_runtime_session_capabilities.tenant_id IS '原Session租户';
COMMENT ON COLUMN public.nw_runtime_session_capabilities.session_id IS '原Session标识';
COMMENT ON COLUMN public.nw_runtime_session_capabilities.agent_id IS '原Session Agent';
COMMENT ON COLUMN public.nw_runtime_session_capabilities.end_user_id IS '原Session终端用户';
COMMENT ON COLUMN public.nw_runtime_session_capabilities.input_id IS '原输入身份，允许在V95受理之前冻结';
COMMENT ON COLUMN public.nw_runtime_session_capabilities.snapshot_id IS '由原输入派生的稳定快照标识';
COMMENT ON COLUMN public.nw_runtime_session_capabilities.generation IS '持有原mount-set行锁时读取的正数装配代';
COMMENT ON COLUMN public.nw_runtime_session_capabilities.snapshot_data IS '完整owner、原verification、授权audit、类型化mount目标与发生时间的规范私有快照';
COMMENT ON COLUMN public.nw_runtime_session_capabilities.recorded_at IS '数据库首次冻结时间';
COMMENT ON INDEX public.nw_runtime_session_capabilities_pkey IS '每租户Session输入只能冻结一份原快照';
COMMENT ON CONSTRAINT session_capabilities_owner_fkey ON public.nw_runtime_session_capabilities IS '完整原Session归属必须已存在，输入受理不是快照前置条件';
COMMENT ON FUNCTION public.skillforge_runtime_session_capabilities_immutable() IS '拒绝改写或删除已冻结输入能力事实';
