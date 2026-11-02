CREATE TABLE public.nw_runtime_knowledge_results (
    tenant_id text NOT NULL CHECK (btrim(tenant_id)<>''),
    action_id text NOT NULL CHECK (btrim(action_id)<>''),
    invocation_id text NOT NULL CHECK (btrim(invocation_id)<>''),
    attempt_id text NOT NULL CHECK (btrim(attempt_id)<>''),
    call_fingerprint text NOT NULL CHECK (btrim(call_fingerprint)<>''),
    result_ref text NOT NULL CHECK (btrim(result_ref)<>''),
    bundle_data text NOT NULL CHECK (length(bundle_data) BETWEEN 1 AND 5592406),
    bundle_json text NOT NULL CHECK (length(bundle_json) BETWEEN 2 AND 8388608 AND jsonb_typeof(bundle_json::jsonb)='object'),
    CONSTRAINT knowledge_result_key PRIMARY KEY (tenant_id,action_id,invocation_id,attempt_id),
    CONSTRAINT knowledge_result_ref UNIQUE (tenant_id,result_ref),
    CONSTRAINT knowledge_result_action FOREIGN KEY (tenant_id,action_id,invocation_id)
        REFERENCES public.agent_runtime_actions(tenant_id,action_id,invocation_id) ON DELETE RESTRICT,
    CONSTRAINT knowledge_result_permit FOREIGN KEY (tenant_id,action_id,attempt_id)
        REFERENCES public.nw_runtime_action_permit_consumptions(tenant_id,action_id,attempt_id) ON DELETE RESTRICT
);
ALTER TABLE public.nw_runtime_knowledge_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_knowledge_results FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_knowledge_results
    USING (tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK (tenant_id=current_setting('app.tenant_id',true));
CREATE FUNCTION public.agent_runtime_knowledge_result_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'knowledge_result_immutable';
END;
$$;
CREATE TRIGGER knowledge_result_immutable BEFORE UPDATE OR DELETE ON public.nw_runtime_knowledge_results
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_knowledge_result_immutable();
CREATE FUNCTION public.agent_runtime_knowledge_result_permit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.nw_runtime_action_permit_consumptions p
        JOIN public.agent_runtime_action_attempt_facts f
          ON f.tenant_id=p.tenant_id AND f.action_id=p.action_id AND f.attempt_id=p.attempt_id
            AND f.fact_ref=p.started_fact_ref
        WHERE p.tenant_id=NEW.tenant_id AND p.action_id=NEW.action_id
          AND p.invocation_id=NEW.invocation_id AND p.attempt_id=NEW.attempt_id AND p.status='CONSUMED'
          AND f.invocation_id=NEW.invocation_id AND f.call_kind='KNOWLEDGE_EXECUTE'
          AND f."authorization"='EXECUTE' AND f.status='STARTED'
    ) THEN
        RAISE EXCEPTION 'knowledge_result_original_consumed_permit_missing';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER knowledge_result_original_permit AFTER INSERT ON public.nw_runtime_knowledge_results
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_knowledge_result_permit();
COMMENT ON TABLE public.nw_runtime_knowledge_results IS 'Knowledge原attempt不可变证据结果；不替代Core的Session Observation提交';
COMMENT ON COLUMN public.nw_runtime_knowledge_results.tenant_id IS '原Action所属租户，强制RLS';
COMMENT ON COLUMN public.nw_runtime_knowledge_results.action_id IS '原Knowledge Action身份';
COMMENT ON COLUMN public.nw_runtime_knowledge_results.invocation_id IS '原调用身份，与Action外键闭合';
COMMENT ON COLUMN public.nw_runtime_knowledge_results.attempt_id IS '已消费Knowledge EXECUTE attempt身份';
COMMENT ON COLUMN public.nw_runtime_knowledge_results.call_fingerprint IS '原完整Provider调用指纹，重放必须一致';
COMMENT ON COLUMN public.nw_runtime_knowledge_results.result_ref IS '实际已提交EvidenceBundle结果引用，不是Session EvidenceRef';
COMMENT ON COLUMN public.nw_runtime_knowledge_results.bundle_data IS '有界规范二进制编码的完整EvidenceBundle，URL Base64无padding';
COMMENT ON COLUMN public.nw_runtime_knowledge_results.bundle_json IS '完整EvidenceBundle规范JSON审计投影，读取与二进制原件交叉验证';
COMMENT ON FUNCTION public.agent_runtime_knowledge_result_immutable() IS '禁止覆盖或删除原attempt证据结果';
COMMENT ON FUNCTION public.agent_runtime_knowledge_result_permit() IS '提交时验证原Knowledge STARTED事实与已消费许可，不授予新的执行权';
COMMENT ON CONSTRAINT knowledge_result_key ON public.nw_runtime_knowledge_results IS '一个原attempt只保留一份结果';
COMMENT ON CONSTRAINT knowledge_result_ref ON public.nw_runtime_knowledge_results IS '结果引用在租户内唯一';
COMMENT ON CONSTRAINT knowledge_result_action ON public.nw_runtime_knowledge_results IS '原Action和invocation必须存在，禁止级联删除结果';
COMMENT ON CONSTRAINT knowledge_result_permit ON public.nw_runtime_knowledge_results IS '原attempt必须已签发许可，消费状态由提交触发器核验';
