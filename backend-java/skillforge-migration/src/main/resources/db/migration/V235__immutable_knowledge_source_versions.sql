-- V235: 文档仅保存当前来源版本指针；正文、元数据与完整分块按 source_version 不可变留存。
CREATE TABLE public.nw_knowledge_document_versions (
    id                bigint        NOT NULL,
    tenant_id         bigint        NOT NULL,
    kb_id             bigint        NOT NULL,
    document_id       bigint        NOT NULL,
    source_version    varchar(128)  NOT NULL,
    title             varchar(256)  NOT NULL,
    source_type       varchar(32)   NOT NULL,
    content           text,
    original_filename varchar(512),
    extension         varchar(32),
    size_bytes        bigint,
    parsed_by         varchar(32),
    status            varchar(32)   NOT NULL,
    chunk_count       integer       NOT NULL DEFAULT 0,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT skillforge_knowledge_document_versions_pkey PRIMARY KEY (id),
    CONSTRAINT uq_skillforge_knowledge_document_versions_identity
        UNIQUE (tenant_id, document_id, source_version),
    CONSTRAINT uq_skillforge_knowledge_document_versions_kb_source
        UNIQUE (tenant_id, kb_id, source_version),
    CONSTRAINT ck_skillforge_knowledge_document_versions_status
        CHECK (status IN ('processing', 'ready', 'failed')),
    CONSTRAINT ck_skillforge_knowledge_document_versions_source_type
        CHECK (source_type IN ('file', 'projection')),
    CONSTRAINT ck_skillforge_knowledge_document_versions_chunk_count CHECK (chunk_count >= 0),
    CONSTRAINT fk_skillforge_knowledge_document_versions_kb
        FOREIGN KEY (kb_id) REFERENCES public.nw_knowledge_bases(id) ON DELETE RESTRICT,
    CONSTRAINT fk_skillforge_knowledge_document_versions_document
        FOREIGN KEY (document_id) REFERENCES public.nw_knowledge_documents(id) ON DELETE RESTRICT,
    CONSTRAINT fk_skillforge_knowledge_document_versions_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id) ON DELETE RESTRICT
);

INSERT INTO public.nw_knowledge_document_versions (
    id, tenant_id, kb_id, document_id, source_version, title, source_type, content,
    original_filename, extension, size_bytes, parsed_by, status, chunk_count, created_at)
SELECT id, tenant_id, kb_id, id, source_version, title, source_type, content,
       original_filename, extension, size_bytes, parsed_by, status,
       (SELECT count(*) FROM public.nw_knowledge_chunks c
        WHERE c.tenant_id = d.tenant_id AND c.document_id = d.id
          AND c.source_version = d.source_version),
       created_at
FROM public.nw_knowledge_documents d;

ALTER TABLE public.nw_knowledge_documents
    ADD COLUMN current_source_version varchar(128),
    ADD COLUMN deleted_at timestamptz;

UPDATE public.nw_knowledge_documents
SET current_source_version = source_version;

DROP INDEX IF EXISTS public.idx_kd_source_version;

ALTER TABLE public.nw_knowledge_documents
    DROP CONSTRAINT IF EXISTS chk_kd_source_type,
    DROP COLUMN title,
    DROP COLUMN source_type,
    DROP COLUMN content,
    DROP COLUMN original_filename,
    DROP COLUMN extension,
    DROP COLUMN size_bytes,
    DROP COLUMN parsed_by,
    DROP COLUMN status,
    DROP COLUMN source_version;

CREATE UNIQUE INDEX uq_skillforge_knowledge_documents_tenant_identity
    ON public.nw_knowledge_documents (tenant_id, id);
CREATE INDEX idx_skillforge_knowledge_documents_current
    ON public.nw_knowledge_documents (tenant_id, kb_id, current_source_version)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_skillforge_knowledge_document_versions_kb_version
    ON public.nw_knowledge_document_versions (tenant_id, kb_id, source_version);

ALTER TABLE public.nw_knowledge_documents
    ADD CONSTRAINT fk_skillforge_knowledge_documents_current_source_version
    FOREIGN KEY (tenant_id, id, current_source_version)
    REFERENCES public.nw_knowledge_document_versions (tenant_id, document_id, source_version)
    ON DELETE RESTRICT;

ALTER TABLE public.nw_knowledge_chunks
    DROP CONSTRAINT IF EXISTS uq_chunks_doc_chunk,
    DROP CONSTRAINT IF EXISTS fk_chunks_doc,
    DROP CONSTRAINT IF EXISTS skillforge_knowledge_chunks_document_id_fkey;

ALTER TABLE public.nw_knowledge_chunks
    ADD CONSTRAINT uq_skillforge_knowledge_chunks_source_position
        UNIQUE (tenant_id, document_id, source_version, chunk_index),
    ADD CONSTRAINT fk_skillforge_knowledge_chunks_document
        FOREIGN KEY (document_id) REFERENCES public.nw_knowledge_documents(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_skillforge_knowledge_chunks_source_version
        FOREIGN KEY (tenant_id, document_id, source_version)
        REFERENCES public.nw_knowledge_document_versions (tenant_id, document_id, source_version)
        ON DELETE RESTRICT;

ALTER TABLE public.nw_knowledge_document_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_knowledge_document_versions FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_knowledge_document_versions_tenant_isolation
    ON public.nw_knowledge_document_versions
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

CREATE OR REPLACE FUNCTION public.guard_ready_knowledge_source_version()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'ready' THEN
        RAISE EXCEPTION 'ready knowledge source version is immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER knowledge_document_version_ready_immutable
    BEFORE UPDATE OR DELETE ON public.nw_knowledge_document_versions
    FOR EACH ROW EXECUTE FUNCTION public.guard_ready_knowledge_source_version();

CREATE OR REPLACE FUNCTION public.guard_ready_knowledge_chunk()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') AND EXISTS (
        SELECT 1 FROM public.nw_knowledge_document_versions v
        WHERE v.tenant_id = OLD.tenant_id
          AND v.document_id = OLD.document_id
          AND v.source_version = OLD.source_version
          AND v.status = 'ready'
    ) THEN
        RAISE EXCEPTION 'ready knowledge source chunks are immutable';
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') AND EXISTS (
        SELECT 1 FROM public.nw_knowledge_document_versions v
        WHERE v.tenant_id = NEW.tenant_id
          AND v.document_id = NEW.document_id
          AND v.source_version = NEW.source_version
          AND v.status = 'ready'
    ) THEN
        RAISE EXCEPTION 'ready knowledge source chunks are immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER knowledge_chunk_ready_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON public.nw_knowledge_chunks
    FOR EACH ROW EXECUTE FUNCTION public.guard_ready_knowledge_chunk();

COMMENT ON TABLE public.nw_knowledge_documents IS
    '知识文档稳定身份容器；current_source_version 以 CAS 指向当前不可变来源版本，删除仅软删除容器';
COMMENT ON COLUMN public.nw_knowledge_documents.current_source_version IS
    '当前来源版本指针；所有写请求必须携带 expected_source_version 并以 CAS 切换';
COMMENT ON COLUMN public.nw_knowledge_documents.deleted_at IS
    '管理面软删除时间；历史已发布 source_version 与分块继续保留供冻结取证';
COMMENT ON TABLE public.nw_knowledge_document_versions IS
    '不可变知识来源版本；ready 后正文、标题、来源元数据与 chunk_count 均禁止原地改写';
COMMENT ON COLUMN public.nw_knowledge_document_versions.source_version IS
    '文档摄取不可变版本标识；同一文档的每次编辑、分块修改或重索引均生成新值';
COMMENT ON COLUMN public.nw_knowledge_document_versions.chunk_count IS
    '该版本完整分块集合大小；ready/publish 与 Runtime 冻结均校验实际分块数一致';
COMMENT ON COLUMN public.nw_knowledge_chunks.source_version IS
    '所属不可变来源版本；旧版本分块保留并与外部索引按 source_version 隔离';
