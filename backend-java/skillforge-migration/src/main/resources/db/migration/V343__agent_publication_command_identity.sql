-- 保存并发布命令身份附着于原不可变发布事实；旧独立发布仍无此命令身份。
ALTER TABLE public.nw_agent_publications
    ADD COLUMN request_id uuid,
    ADD COLUMN request_fingerprint varchar(64),
    ADD COLUMN request_actor_ref varchar(128),
    ADD CONSTRAINT skillforge_agent_publications_request_complete
        CHECK (num_nonnulls(request_id, request_fingerprint, request_actor_ref) IN (0, 3)),
    ADD CONSTRAINT skillforge_agent_publications_request_fingerprint
        CHECK (request_fingerprint IS NULL OR request_fingerprint ~ '^[0-9a-f]{64}$');

CREATE UNIQUE INDEX skillforge_agent_publications_request_identity
    ON public.nw_agent_publications(tenant_id, agent_id, request_id)
    WHERE request_id IS NOT NULL;

COMMENT ON COLUMN public.nw_agent_publications.request_id IS
    '保存并发布命令UUID，同机构同Agent内唯一；NULL表示独立发布或没有命令身份的既有发布';
COMMENT ON COLUMN public.nw_agent_publications.request_fingerprint IS
    '保存并发布原命令参数的小写SHA-256摘要；NULL仅与其余命令身份字段同时为空';
COMMENT ON COLUMN public.nw_agent_publications.request_actor_ref IS
    '首次保存并发布的可信操作者引用，重投及只读回执核对原操作者；NULL仅表示无命令身份的发布';
