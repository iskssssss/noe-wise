-- V76: Knowledge source_version 版本钉扎。
ALTER TABLE public.nw_knowledge_documents
    ADD COLUMN source_version varchar(128) NOT NULL;

ALTER TABLE public.nw_knowledge_chunks
    ADD COLUMN source_version varchar(128) NOT NULL;

CREATE INDEX idx_kd_source_version
    ON public.nw_knowledge_documents (tenant_id, kb_id, source_version);
CREATE INDEX idx_chunks_source_version
    ON public.nw_knowledge_chunks (tenant_id, kb_id, source_version);

COMMENT ON COLUMN public.nw_knowledge_documents.source_version IS
    '进入知识检索索引的不可变版本标识；绑定可精确钉扎该值';
COMMENT ON COLUMN public.nw_knowledge_chunks.source_version IS
    '与所属文档一致的不可变 source_version，检索必须按此字段执行版本过滤';
