CREATE TABLE public.nw_workflow_review_decisions (
    id bigint PRIMARY KEY,
    template_id bigint NOT NULL REFERENCES public.nw_workflow_templates(id) ON DELETE RESTRICT,
    tenant_id bigint REFERENCES public.nw_tenants(id) ON DELETE RESTRICT,
    version varchar(32) NOT NULL,
    source_updated_at timestamptz NOT NULL,
    decision varchar(16) NOT NULL CHECK (decision IN ('approved', 'rejected')),
    reason varchar(1000),
    actor_id varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT workflow_review_reason_required CHECK
        ((decision = 'approved' AND reason IS NULL) OR (decision = 'rejected' AND reason IS NOT NULL AND length(btrim(reason)) > 0)),
    CONSTRAINT workflow_review_submission_unique UNIQUE(template_id, source_updated_at)
);
CREATE INDEX skillforge_workflow_review_decisions_latest ON public.nw_workflow_review_decisions(template_id, created_at DESC, id DESC);
ALTER TABLE public.nw_workflow_review_decisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_workflow_review_decisions FORCE ROW LEVEL SECURITY;
CREATE POLICY workflow_review_platform_only ON public.nw_workflow_review_decisions
    USING (current_setting('app.platform_admin', true) = 'true')
    WITH CHECK (current_setting('app.platform_admin', true) = 'true');

COMMENT ON TABLE public.nw_workflow_review_decisions IS '平台审核决定的追加审计事实，与模板状态事务一致；普通草稿不产生驳回记录';
COMMENT ON COLUMN public.nw_workflow_review_decisions.id IS '审核决定雪花 ID';
COMMENT ON COLUMN public.nw_workflow_review_decisions.template_id IS '审核模板，审计保留期间禁止删除模板';
COMMENT ON COLUMN public.nw_workflow_review_decisions.tenant_id IS '模板归属机构；平台模板为空，审核记录仅平台可读写';
COMMENT ON COLUMN public.nw_workflow_review_decisions.version IS '本次审核的模板版本快照';
COMMENT ON COLUMN public.nw_workflow_review_decisions.source_updated_at IS '审核所见模板修订时间，作为并发校验事实，不冒充独立提交时间';
COMMENT ON COLUMN public.nw_workflow_review_decisions.decision IS '审核决定：approved / rejected';
COMMENT ON COLUMN public.nw_workflow_review_decisions.reason IS '驳回理由，去首尾空格后必填且最多 1000 字符；通过为空';
COMMENT ON COLUMN public.nw_workflow_review_decisions.actor_id IS '从平台认证主体取得的操作者 ID';
COMMENT ON COLUMN public.nw_workflow_review_decisions.created_at IS '决定发生时间';
COMMENT ON INDEX public.skillforge_workflow_review_decisions_latest IS '查询每个模板的最近审核决定';
COMMENT ON CONSTRAINT workflow_review_submission_unique ON public.nw_workflow_review_decisions IS '每个模板提交只允许一个审核决定';
COMMENT ON CONSTRAINT workflow_review_reason_required ON public.nw_workflow_review_decisions IS '驳回必须有理由，通过不得混入驳回理由';

CREATE FUNCTION public.workflow_review_decision_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'workflow review decisions are append-only';
END;
$$;
CREATE TRIGGER workflow_review_decision_immutable BEFORE UPDATE OR DELETE ON public.nw_workflow_review_decisions
    FOR EACH ROW EXECUTE FUNCTION public.workflow_review_decision_immutable();
COMMENT ON FUNCTION public.workflow_review_decision_immutable() IS '阻止业务路径修改或删除已保存的审核决定';
