-- 实际辅助模型请求与返回归core-model；不构造Runtime Action，不把正文放入用量或外调metadata。
CREATE TABLE public.nw_model_call_records (
    tenant_id bigint NOT NULL,
    invocation_id varchar(128) NOT NULL,
    attempt_id varchar(160) NOT NULL,
    request_schema varchar(128) NOT NULL,
    request_json text NOT NULL,
    outcome_schema varchar(128),
    outcome_json text,
    request_recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    outcome_recorded_at timestamptz,
    CONSTRAINT model_call_records_pk PRIMARY KEY(tenant_id,attempt_id),
    CONSTRAINT model_call_records_identity_uq UNIQUE(tenant_id,invocation_id,attempt_id),
    CONSTRAINT model_call_records_invocation_fk FOREIGN KEY(tenant_id,attempt_id)
        REFERENCES public.nw_external_invocations(tenant_id,attempt_id),
    CONSTRAINT model_call_records_request_ck CHECK(tenant_id>0 AND btrim(invocation_id)<>'' AND btrim(attempt_id)<>''
        AND invocation_id<>attempt_id AND request_schema='skillforge.model.actual-request.v1' AND btrim(request_json)<>''),
    CONSTRAINT model_call_records_outcome_ck CHECK(
        (outcome_schema IS NULL AND outcome_json IS NULL AND outcome_recorded_at IS NULL)
        OR (outcome_schema IS NOT NULL AND outcome_schema='skillforge.model.actual-outcome.v1'
            AND outcome_json IS NOT NULL AND btrim(outcome_json)<>'' AND outcome_recorded_at IS NOT NULL))
);

CREATE FUNCTION public.nw_model_call_record_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NOT EXISTS(SELECT 1 FROM public.nw_external_invocations invocation
            WHERE invocation.tenant_id=NEW.tenant_id AND invocation.invocation_id=NEW.invocation_id
                AND invocation.attempt_id=NEW.attempt_id AND invocation.operation_type='model.chat') THEN
            RAISE EXCEPTION 'model_call_invocation_mismatch';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.invocation_id IS DISTINCT FROM OLD.invocation_id
       OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id OR NEW.request_schema IS DISTINCT FROM OLD.request_schema
       OR NEW.request_json IS DISTINCT FROM OLD.request_json OR NEW.request_recorded_at IS DISTINCT FROM OLD.request_recorded_at
       OR OLD.outcome_schema IS NOT NULL AND (NEW.outcome_schema IS DISTINCT FROM OLD.outcome_schema
           OR NEW.outcome_json IS DISTINCT FROM OLD.outcome_json OR NEW.outcome_recorded_at IS DISTINCT FROM OLD.outcome_recorded_at) THEN
        RAISE EXCEPTION 'model_call_material_immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER model_call_records_immutable BEFORE INSERT OR UPDATE ON public.nw_model_call_records
    FOR EACH ROW EXECUTE FUNCTION public.nw_model_call_record_immutable();

ALTER TABLE public.nw_model_call_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_model_call_records FORCE ROW LEVEL SECURITY;
CREATE POLICY model_call_records_tenant_isolation ON public.nw_model_call_records
    USING(tenant_id::text=COALESCE(current_setting('app.tenant_id',true),''))
    WITH CHECK(tenant_id::text=COALESCE(current_setting('app.tenant_id',true),''));
GRANT SELECT,INSERT,UPDATE ON public.nw_model_call_records TO skillforge;

COMMENT ON TABLE public.nw_model_call_records IS 'core-model拥有的真实辅助调用材料；安全字段codec只保存实际Provider请求和返回，不保存凭据、端点或执行函数；跟随原外调证据保留，不从历史配置补造';
COMMENT ON COLUMN public.nw_model_call_records.tenant_id IS '真实调用机构，读取继承已授权会话或平台目标窗口';
COMMENT ON COLUMN public.nw_model_call_records.invocation_id IS '原外调账本的真实逻辑调用身份，与attempt共同精确匹配';
COMMENT ON COLUMN public.nw_model_call_records.attempt_id IS '原外调账本的真实物理尝试身份，不生成Runtime Action';
COMMENT ON COLUMN public.nw_model_call_records.request_schema IS 'ModelCallRecordCodec实际请求协议版本';
COMMENT ON COLUMN public.nw_model_call_records.request_json IS '发送前从实际Provider请求白名单采集的规范协议文本，保留消息及开放Schema/参数原文，不是HTTP原始字节';
COMMENT ON COLUMN public.nw_model_call_records.outcome_schema IS 'ModelCallRecordCodec实际返回协议版本，NULL表示返回未保存';
COMMENT ON COLUMN public.nw_model_call_records.outcome_json IS '业务裁决前采集的Completed/Rejected/OutcomeUnknown原返回字段，含推理、工具调用、结束原因及可空用量；不是HTTP原始字节';
COMMENT ON COLUMN public.nw_model_call_records.request_recorded_at IS '实际请求材料持久化时间，不冒充首token或发送完成';
COMMENT ON COLUMN public.nw_model_call_records.outcome_recorded_at IS '实际返回材料持久化时间，缺失不推断调用没有返回';
COMMENT ON CONSTRAINT model_call_records_pk ON public.nw_model_call_records IS '机构内物理尝试材料唯一';
COMMENT ON CONSTRAINT model_call_records_identity_uq ON public.nw_model_call_records IS '读取使用完整真实调用身份';
COMMENT ON CONSTRAINT model_call_records_invocation_fk ON public.nw_model_call_records IS '材料只能引用本机构已登记的真实外调尝试，清理须先处理引用';
COMMENT ON CONSTRAINT model_call_records_request_ck ON public.nw_model_call_records IS '实际请求身份与当前协议存在性约束';
COMMENT ON CONSTRAINT model_call_records_outcome_ck ON public.nw_model_call_records IS '返回材料版本、正文和记录时间同时存在或同时缺失';
COMMENT ON FUNCTION public.nw_model_call_record_immutable() IS '请求不可覆盖；原返回仅允许首次保存，不更新旧事实';
COMMENT ON TRIGGER model_call_records_immutable ON public.nw_model_call_records IS '禁止替换原请求及已保存返回';
COMMENT ON POLICY model_call_records_tenant_isolation ON public.nw_model_call_records IS '按已绑定真实机构强制隔离，平台不绕过RLS';
