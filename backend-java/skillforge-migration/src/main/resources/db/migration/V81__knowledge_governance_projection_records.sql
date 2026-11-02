-- V81: 审核提案到机构知识的下游投影幂等记录。
CREATE TABLE public.nw_knowledge_governance_projections (
    id              bigint NOT NULL,
    tenant_id       bigint NOT NULL,
    proposal_id     varchar(128) NOT NULL,
    projection_type varchar(32) NOT NULL,
    status          varchar(16) NOT NULL DEFAULT 'processing',
    details         jsonb NOT NULL DEFAULT '{}'::jsonb,
    error           text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_skillforge_knowledge_governance_projections PRIMARY KEY (id),
    CONSTRAINT uq_skillforge_knowledge_governance_projections_proposal UNIQUE (tenant_id, proposal_id),
    CONSTRAINT ck_skillforge_knowledge_governance_projections_type
        CHECK (projection_type = 'institutional_knowledge'),
    CONSTRAINT ck_skillforge_knowledge_governance_projections_status
        CHECK (status IN ('processing', 'applied', 'failed')),
    CONSTRAINT fk_skillforge_knowledge_governance_projections_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id)
);

CREATE INDEX idx_skillforge_knowledge_governance_projections_tenant_status
    ON public.nw_knowledge_governance_projections (tenant_id, status, updated_at);

ALTER TABLE public.nw_knowledge_governance_projections ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_knowledge_governance_projections FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_knowledge_governance_projections_tenant_isolation
    ON public.nw_knowledge_governance_projections
    USING (tenant_id::text = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id::text = COALESCE(current_setting('app.tenant_id', true), ''));

COMMENT ON TABLE public.nw_knowledge_governance_projections IS
    '机构知识提案审批后的 Knowledge Base revision 发布记录；proposalId 在租户内幂等';
COMMENT ON COLUMN public.nw_knowledge_governance_projections.details IS
    '机构知识文档、来源版本与已发布 revision 的审计引用';
