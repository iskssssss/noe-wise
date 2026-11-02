-- V79: Knowledge Runtime 写回提案治理队列。
-- 提案只是待治理事实，不直接修改 Memory 或机构知识。
CREATE TABLE public.nw_knowledge_write_proposals (
    id              bigint NOT NULL,
    tenant_id       bigint NOT NULL,
    proposal_id     varchar(128) NOT NULL,
    target          varchar(32) NOT NULL,
    source_event_id varchar(128) NOT NULL,
    bundle_id       varchar(128) NOT NULL,
    payload         jsonb NOT NULL DEFAULT '{}'::jsonb,
    status          varchar(32) NOT NULL DEFAULT 'pending_confirm',
    reviewer_id     varchar(128),
    review_note     text,
    created_at      timestamp with time zone NOT NULL DEFAULT now(),
    reviewed_at     timestamp with time zone,
    CONSTRAINT pk_skillforge_knowledge_write_proposals PRIMARY KEY (id),
    CONSTRAINT uq_skillforge_knowledge_write_proposals_tenant_proposal UNIQUE (tenant_id, proposal_id),
    CONSTRAINT ck_skillforge_knowledge_write_proposals_target
        CHECK (target IN ('memory', 'institutional_knowledge')),
    CONSTRAINT ck_skillforge_knowledge_write_proposals_status
        CHECK (status IN ('pending_confirm', 'approved', 'rejected')),
    CONSTRAINT fk_skillforge_knowledge_write_proposals_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id)
);

CREATE INDEX idx_skillforge_knowledge_write_proposals_pending
    ON public.nw_knowledge_write_proposals (tenant_id, status, created_at);

ALTER TABLE public.nw_knowledge_write_proposals FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_knowledge_write_proposals_tenant_isolation
    ON public.nw_knowledge_write_proposals
    USING (tenant_id::text = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id::text = COALESCE(current_setting('app.tenant_id', true), ''));

COMMENT ON TABLE public.nw_knowledge_write_proposals IS
    'Knowledge Runtime 写回提案；只经治理审批，不直接修改事实源';
