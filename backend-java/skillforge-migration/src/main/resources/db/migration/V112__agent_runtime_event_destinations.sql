-- destinationId本身是不可变投递路由版本；更换端点/凭据必须分配新的destinationId。
CREATE TABLE public.nw_runtime_event_destinations (
    tenant_id text NOT NULL CHECK (btrim(tenant_id)<>''),
    destination_id text NOT NULL CHECK (btrim(destination_id)<>''),
    endpoint text NOT NULL CHECK (btrim(endpoint)<>''),
    encrypted_bearer text NOT NULL CHECK (btrim(encrypted_bearer)<>''),
    timeout text NOT NULL CHECK (btrim(timeout)<>''),
    protocol text NOT NULL CHECK (protocol='runtime-event-http-v1'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT skillforge_runtime_event_destinations_pkey PRIMARY KEY(tenant_id,destination_id)
);
CREATE TABLE public.nw_runtime_event_destination_revocations (
    tenant_id text NOT NULL,
    destination_id text NOT NULL,
    revoked_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT skillforge_runtime_event_destination_revocations_pkey PRIMARY KEY(tenant_id,destination_id),
    FOREIGN KEY(tenant_id,destination_id) REFERENCES public.nw_runtime_event_destinations(tenant_id,destination_id)
);
ALTER TABLE public.nw_runtime_event_destinations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_event_destinations FORCE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_event_destination_revocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_event_destination_revocations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_event_destinations
    USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));
CREATE POLICY tenant_isolation ON public.nw_runtime_event_destination_revocations
    USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));
CREATE FUNCTION public.agent_runtime_event_destination_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'runtime_event_destination_immutable'; END;
$$;
CREATE TRIGGER runtime_event_destination_immutable BEFORE UPDATE OR DELETE ON public.nw_runtime_event_destinations
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_event_destination_immutable();
CREATE TRIGGER runtime_event_destination_revocation_immutable BEFORE UPDATE OR DELETE ON public.nw_runtime_event_destination_revocations
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_event_destination_immutable();
COMMENT ON TABLE public.nw_runtime_event_destinations IS '每tenant/destinationId不可变HTTP事件接收配置，不用当前路由替换原投递';
COMMENT ON COLUMN public.nw_runtime_event_destinations.tenant_id IS '原投递所属租户';
COMMENT ON COLUMN public.nw_runtime_event_destinations.destination_id IS '不可变接收端身份，配置变更须使用新ID';
COMMENT ON COLUMN public.nw_runtime_event_destinations.endpoint IS '冻结绝对HTTP(S)端点，禁止URI内凭据和fragment';
COMMENT ON COLUMN public.nw_runtime_event_destinations.encrypted_bearer IS '冻结Bearer密文，不持久明文';
COMMENT ON COLUMN public.nw_runtime_event_destinations.timeout IS '冻结正数JDK Duration，保留纳秒精度';
COMMENT ON COLUMN public.nw_runtime_event_destinations.protocol IS '只支持runtime-event-http-v1确认和幂等协议';
COMMENT ON COLUMN public.nw_runtime_event_destinations.created_at IS '首次注册的数据库时间';
COMMENT ON TABLE public.nw_runtime_event_destination_revocations IS '独立不可变撤销标记，不删除历史路由，不允许恢复旧ID';
COMMENT ON COLUMN public.nw_runtime_event_destination_revocations.tenant_id IS '被撤销destination租户';
COMMENT ON COLUMN public.nw_runtime_event_destination_revocations.destination_id IS '被撤销的原不可变destination';
COMMENT ON COLUMN public.nw_runtime_event_destination_revocations.revoked_at IS '数据库首次撤销时间';
COMMENT ON INDEX public.skillforge_runtime_event_destinations_pkey IS '租户与原destinationId唯一定位历史投递配置';
COMMENT ON INDEX public.skillforge_runtime_event_destination_revocations_pkey IS '同接收端仅一个永久撤销事实';
COMMENT ON FUNCTION public.agent_runtime_event_destination_immutable() IS '禁止覆写或删除原配置与撤销事实';
