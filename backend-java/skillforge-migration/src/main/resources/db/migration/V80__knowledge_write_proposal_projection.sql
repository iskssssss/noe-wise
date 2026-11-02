-- V80: Knowledge 写回提案审批后的事实源投影状态。
ALTER TABLE public.nw_knowledge_write_proposals
    ADD COLUMN projection_status varchar(32) NOT NULL DEFAULT 'pending',
    ADD COLUMN projection_ref bigint,
    ADD COLUMN projection_error text,
    ADD COLUMN projected_at timestamp with time zone;

ALTER TABLE public.nw_knowledge_write_proposals
    ADD CONSTRAINT ck_skillforge_knowledge_write_proposals_projection_status
        CHECK (projection_status IN ('pending', 'applied', 'failed'));

COMMENT ON COLUMN public.nw_knowledge_write_proposals.projection_status IS
    '审批后的事实源投影状态；approved 不等同于 applied';
