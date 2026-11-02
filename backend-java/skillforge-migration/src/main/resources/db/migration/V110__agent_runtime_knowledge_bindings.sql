-- 将V77/V89已发布来源及当时可用原chunk冻结为独立只读副本；原知识管理后续编辑不改变本次来源。
CREATE TABLE public.nw_runtime_knowledge_bindings (
    tenant_id text NOT NULL CHECK (btrim(tenant_id)<>''),
    binding_id text NOT NULL CHECK (binding_id ~ '^[0-9a-f]{64}$'),
    target_digest text NOT NULL CHECK (target_digest ~ '^[0-9a-f]{64}$'),
    handle text NOT NULL CHECK (handle LIKE 'knowledge:_%'),
    descriptor_id text NOT NULL CHECK (descriptor_id ~ '^[1-9][0-9]*$'),
    descriptor_revision bigint NOT NULL CHECK (descriptor_revision>0),
    capability_id text NOT NULL CHECK (btrim(capability_id)<>''),
    version_id text NOT NULL CHECK (btrim(version_id)<>''),
    descriptor_json text NOT NULL CHECK (jsonb_typeof(descriptor_json::jsonb)='object'),
    source_count integer NOT NULL CHECK (source_count>0),
    published_at timestamptz NOT NULL,
    frozen_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    freeze_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    PRIMARY KEY(tenant_id,binding_id),
    UNIQUE(tenant_id,handle,descriptor_id,descriptor_revision,capability_id,version_id)
);
CREATE TABLE public.nw_runtime_knowledge_sources (
    tenant_id text NOT NULL,
    binding_id text NOT NULL,
    source_id text NOT NULL CHECK (btrim(source_id)<>''),
    source_version text NOT NULL CHECK (btrim(source_version)<>''),
    chunk_count bigint NOT NULL CHECK (chunk_count>0),
    PRIMARY KEY(tenant_id,binding_id,source_id,source_version),
    FOREIGN KEY(tenant_id,binding_id) REFERENCES public.nw_runtime_knowledge_bindings(tenant_id,binding_id)
);
CREATE TABLE public.nw_runtime_knowledge_chunks (
    tenant_id text NOT NULL,
    binding_id text NOT NULL,
    source_id text NOT NULL,
    source_version text NOT NULL,
    document_id text NOT NULL CHECK (btrim(document_id)<>''),
    chunk_id text NOT NULL CHECK (btrim(chunk_id)<>''),
    chunk_index integer NOT NULL CHECK (chunk_index>=0),
    content text NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY(tenant_id,binding_id,chunk_id),
    FOREIGN KEY(tenant_id,binding_id,source_id,source_version)
        REFERENCES public.nw_runtime_knowledge_sources(tenant_id,binding_id,source_id,source_version)
);
CREATE INDEX knowledge_snapshot_source ON public.nw_runtime_knowledge_chunks
    (tenant_id,binding_id,source_id,source_version,document_id,chunk_index,chunk_id);
DO $$ DECLARE name text; BEGIN
    FOREACH name IN ARRAY ARRAY['nw_runtime_knowledge_bindings','nw_runtime_knowledge_sources','nw_runtime_knowledge_chunks'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',name);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY',name);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (tenant_id=current_setting(''app.tenant_id'',true)) WITH CHECK (tenant_id=current_setting(''app.tenant_id'',true))',name);
    END LOOP;
END $$;
CREATE FUNCTION public.agent_runtime_knowledge_snapshot_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'knowledge_snapshot_immutable'; END;
$$;
CREATE TRIGGER knowledge_binding_immutable BEFORE UPDATE OR DELETE ON public.nw_runtime_knowledge_bindings
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_knowledge_snapshot_immutable();
CREATE TRIGGER knowledge_source_immutable BEFORE UPDATE OR DELETE ON public.nw_runtime_knowledge_sources
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_knowledge_snapshot_immutable();
CREATE TRIGGER knowledge_chunk_immutable BEFORE UPDATE OR DELETE ON public.nw_runtime_knowledge_chunks
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_knowledge_snapshot_immutable();
CREATE FUNCTION public.agent_runtime_knowledge_snapshot_same_transaction() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM public.nw_runtime_knowledge_bindings b WHERE b.tenant_id=NEW.tenant_id
        AND b.binding_id=NEW.binding_id AND b.freeze_xid=pg_current_xact_id()) THEN
        RAISE EXCEPTION 'knowledge_snapshot_already_sealed';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER knowledge_source_freeze_transaction BEFORE INSERT ON public.nw_runtime_knowledge_sources
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_knowledge_snapshot_same_transaction();
CREATE TRIGGER knowledge_chunk_freeze_transaction BEFORE INSERT ON public.nw_runtime_knowledge_chunks
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_knowledge_snapshot_same_transaction();
CREATE FUNCTION public.agent_runtime_knowledge_snapshot_complete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.freeze_xid<>pg_current_xact_id()
       OR NEW.source_count<>(SELECT count(*) FROM public.nw_runtime_knowledge_sources s WHERE s.tenant_id=NEW.tenant_id AND s.binding_id=NEW.binding_id)
       OR EXISTS(SELECT 1 FROM public.nw_runtime_knowledge_sources s WHERE s.tenant_id=NEW.tenant_id AND s.binding_id=NEW.binding_id
          AND s.chunk_count<>(SELECT count(*) FROM public.nw_runtime_knowledge_chunks c WHERE c.tenant_id=s.tenant_id AND c.binding_id=s.binding_id
                 AND c.source_id=s.source_id AND c.source_version=s.source_version)) THEN
        RAISE EXCEPTION 'knowledge_snapshot_incomplete';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER knowledge_snapshot_complete AFTER INSERT ON public.nw_runtime_knowledge_bindings
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_knowledge_snapshot_complete();
COMMENT ON TABLE public.nw_runtime_knowledge_bindings IS '原Knowledge revision descriptor与完整target的不可变生产绑定，不证明Session成员授权';
COMMENT ON COLUMN public.nw_runtime_knowledge_bindings.tenant_id IS '原KB、revision与Capability共同租户';
COMMENT ON COLUMN public.nw_runtime_knowledge_bindings.binding_id IS '完整挂载/descriptor/revision/capability身份的稳定摘要';
COMMENT ON COLUMN public.nw_runtime_knowledge_bindings.target_digest IS '完整冻结target含manifest、operators与timeout的摘要';
COMMENT ON COLUMN public.nw_runtime_knowledge_bindings.handle IS '原knowledge:<mountId>挂载';
COMMENT ON COLUMN public.nw_runtime_knowledge_bindings.descriptor_id IS '原knowledge_bases.id十进制字符串';
COMMENT ON COLUMN public.nw_runtime_knowledge_bindings.descriptor_revision IS '原knowledge_base_revisions.revision，非表主键或CapabilityVersion';
COMMENT ON COLUMN public.nw_runtime_knowledge_bindings.capability_id IS '原capability_versions.definition_id';
COMMENT ON COLUMN public.nw_runtime_knowledge_bindings.version_id IS '原revision的capability_version_id';
COMMENT ON COLUMN public.nw_runtime_knowledge_bindings.descriptor_json IS '原发布七字段descriptor完整文本';
COMMENT ON COLUMN public.nw_runtime_knowledge_bindings.source_count IS '完整manifest精确版本对数量，提交时强制齐全';
COMMENT ON COLUMN public.nw_runtime_knowledge_bindings.published_at IS '原revision首次发布时间，支持asOf边界';
COMMENT ON COLUMN public.nw_runtime_knowledge_bindings.frozen_at IS '副本形成的数据库时间';
COMMENT ON COLUMN public.nw_runtime_knowledge_bindings.freeze_xid IS '只允许同一发布事务插入完整来源/chunk，提交后封闭';
COMMENT ON TABLE public.nw_runtime_knowledge_sources IS '完整来源manifest及每版本实际chunk数量';
COMMENT ON COLUMN public.nw_runtime_knowledge_sources.tenant_id IS '同绑定租户';
COMMENT ON COLUMN public.nw_runtime_knowledge_sources.binding_id IS '原绑定摘要';
COMMENT ON COLUMN public.nw_runtime_knowledge_sources.source_id IS '原KB id';
COMMENT ON COLUMN public.nw_runtime_knowledge_sources.source_version IS '原精确source_version，无latest或legacy回退';
COMMENT ON COLUMN public.nw_runtime_knowledge_sources.chunk_count IS '发布快照实际chunk数量，必须非空并与副本一致';
COMMENT ON TABLE public.nw_runtime_knowledge_chunks IS '从原ready文档同租户同KB同版本复制的不可变只读取证副本';
COMMENT ON COLUMN public.nw_runtime_knowledge_chunks.tenant_id IS '原chunk租户';
COMMENT ON COLUMN public.nw_runtime_knowledge_chunks.binding_id IS '原绑定摘要';
COMMENT ON COLUMN public.nw_runtime_knowledge_chunks.source_id IS '原chunk KB id';
COMMENT ON COLUMN public.nw_runtime_knowledge_chunks.source_version IS '原chunk与document共同版本';
COMMENT ON COLUMN public.nw_runtime_knowledge_chunks.document_id IS '原document id';
COMMENT ON COLUMN public.nw_runtime_knowledge_chunks.chunk_id IS '原chunk id';
COMMENT ON COLUMN public.nw_runtime_knowledge_chunks.chunk_index IS '原文档内分段顺序';
COMMENT ON COLUMN public.nw_runtime_knowledge_chunks.content IS '原chunk完整正文，仅授权取证读取';
COMMENT ON COLUMN public.nw_runtime_knowledge_chunks.created_at IS '原chunk创建时间，不用副本时间伪造新鲜度';
COMMENT ON INDEX public.knowledge_snapshot_source IS '同绑定同精确版本的稳定出处排序';
COMMENT ON FUNCTION public.agent_runtime_knowledge_snapshot_immutable() IS '拒绝修改删除冻结来源';
COMMENT ON FUNCTION public.agent_runtime_knowledge_snapshot_same_transaction() IS '拒绝提交后补入额外来源或内容';
COMMENT ON FUNCTION public.agent_runtime_knowledge_snapshot_complete() IS '拒绝缺失manifest或缺失chunk的部分副本提交';
