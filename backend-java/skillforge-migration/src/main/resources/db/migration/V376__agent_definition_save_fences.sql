-- 原保存结果无法确认时，仅原主体可封存该键；与保存使用同一事务锁。
CREATE TABLE public.nw_agent_definition_save_fences (
    tenant_id bigint NOT NULL REFERENCES public.nw_tenants(id),
    actor_ref varchar(160) NOT NULL CHECK (
        actor_ref ~ '^service_principal:[1-9][0-9]*$'
        OR actor_ref ~ ('^tenant:' || tenant_id::text || ':member:[1-9][0-9]*$')),
    operation varchar(16) NOT NULL CHECK (operation IN ('create','update')),
    object_scope varchar(64) NOT NULL,
    request_key varchar(256) NOT NULL CHECK (btrim(request_key)<>''),
    definition_id bigint REFERENCES public.nw_agent_templates(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,actor_ref,operation,object_scope,request_key),
    CHECK ((operation='create' AND object_scope='new' AND definition_id IS NULL)
        OR (operation='update' AND definition_id IS NOT NULL AND object_scope=definition_id::text))
);
ALTER TABLE public.nw_agent_definition_save_fences ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_agent_definition_save_fences FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_definition_save_fence_owner ON public.nw_agent_definition_save_fences
    USING (tenant_id::text=current_setting('app.tenant_id',true))
    WITH CHECK (tenant_id::text=current_setting('app.tenant_id',true)
        AND (definition_id IS NULL OR EXISTS (SELECT 1 FROM public.nw_agent_templates d
            WHERE d.id=nw_agent_definition_save_fences.definition_id AND d.owner_kind='tenant'
              AND d.owner_tenant_id=nw_agent_definition_save_fences.tenant_id)));
GRANT SELECT,INSERT ON public.nw_agent_definition_save_fences TO skillforge;
CREATE FUNCTION public.immutable_agent_definition_save_fence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'agent_definition_save_fence_immutable'; END $$;
CREATE TRIGGER agent_definition_save_fence_immutable BEFORE UPDATE OR DELETE ON public.nw_agent_definition_save_fences
    FOR EACH ROW EXECUTE FUNCTION public.immutable_agent_definition_save_fence();
COMMENT ON TABLE public.nw_agent_definition_save_fences IS '原主体确认停止的定义保存键；不表示原请求曾到达或被拒绝';
