-- 每个原Run仅一个明确系统执行责任；不是同租户默认授权，也不更新原准入身份。
CREATE TABLE public.nw_runtime_session_responsibilities (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    run_identity_data text NOT NULL CHECK (btrim(run_identity_data)<>''),
    authentication_ref text NOT NULL,
    actor_data text NOT NULL CHECK (btrim(actor_data)<>''),
    channel text NOT NULL CHECK (btrim(channel)<>''),
    source text NOT NULL CHECK (btrim(source)<>''),
    until_second bigint NOT NULL,
    until_nano integer NOT NULL CHECK (until_nano BETWEEN 0 AND 999999999),
    registered_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT skillforge_runtime_session_responsibilities_pkey PRIMARY KEY(tenant_id,run_id),
    FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id),
    FOREIGN KEY(tenant_id,authentication_ref) REFERENCES public.nw_runtime_authentication_facts(tenant_id,authentication_ref)
);
ALTER TABLE public.nw_runtime_session_responsibilities ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_responsibilities FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_session_responsibilities
    USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE FUNCTION public.agent_runtime_session_responsibility_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'session_responsibility_immutable'; END;
$$;
CREATE TRIGGER session_responsibility_immutable BEFORE UPDATE OR DELETE ON public.nw_runtime_session_responsibilities
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_responsibility_immutable();
COMMENT ON TABLE public.nw_runtime_session_responsibilities IS '受信宿主为精确原Run登记的不可变系统执行责任，逐次重验当前IAM';
COMMENT ON COLUMN public.nw_runtime_session_responsibilities.tenant_id IS '原Run租户';
COMMENT ON COLUMN public.nw_runtime_session_responsibilities.run_id IS '原Run标识，不用同owner其他Run责任兜底';
COMMENT ON COLUMN public.nw_runtime_session_responsibilities.run_identity_data IS '完整原RunIdentity规范快照';
COMMENT ON COLUMN public.nw_runtime_session_responsibilities.authentication_ref IS '固定系统认证引用，撤销或过期立即停止后续授权';
COMMENT ON COLUMN public.nw_runtime_session_responsibilities.actor_data IS '登记时冻结的完整系统VerifiedIdentity，后续重验当前权限但不更换verificationRef';
COMMENT ON COLUMN public.nw_runtime_session_responsibilities.channel IS '固定系统认证渠道';
COMMENT ON COLUMN public.nw_runtime_session_responsibilities.source IS '固定系统认证来源';
COMMENT ON COLUMN public.nw_runtime_session_responsibilities.until_second IS '责任截止epoch秒';
COMMENT ON COLUMN public.nw_runtime_session_responsibilities.until_nano IS '责任截止纳秒，保留精度';
COMMENT ON COLUMN public.nw_runtime_session_responsibilities.registered_at IS '受信登记数据库时间';
COMMENT ON INDEX public.skillforge_runtime_session_responsibilities_pkey IS '每个租户原Run只有一个不可覆写责任';
COMMENT ON FUNCTION public.agent_runtime_session_responsibility_immutable() IS '禁止覆写和删除历史执行责任';
