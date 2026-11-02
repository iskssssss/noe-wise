-- 独立受理事务固定治理身份与时间；原事实与Runtime journal继续分别拥有内容和结果。
CREATE TABLE public.nw_runtime_memory_governance_receipts (
    tenant_id text NOT NULL,
    request_key text NOT NULL,
    fact_id text NOT NULL,
    expected_revision bigint NOT NULL,
    operation text NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    accepted_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT memory_governance_receipts_pk PRIMARY KEY (tenant_id,request_key),
    CONSTRAINT memory_governance_receipts_revision_fk
        FOREIGN KEY (tenant_id,fact_id,expected_revision)
        REFERENCES public.agent_runtime_memory_fact_revisions (tenant_id,fact_id,revision)
        ON DELETE RESTRICT,
    CONSTRAINT memory_governance_receipts_identity_ck CHECK (
        btrim(tenant_id)<>'' AND btrim(request_key)<>'' AND btrim(fact_id)<>''
        AND expected_revision>0 AND btrim(operation)<>''),
    CONSTRAINT memory_governance_receipts_fingerprint_ck
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$')
);
CREATE INDEX memory_governance_receipts_revision_idx
    ON public.nw_runtime_memory_governance_receipts (tenant_id,fact_id,expected_revision);

ALTER TABLE public.nw_runtime_memory_governance_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_memory_governance_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_memory_governance_receipts
    USING (tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK (tenant_id=current_setting('app.tenant_id',true));
CREATE TRIGGER memory_governance_receipts_immutable
    BEFORE UPDATE OR DELETE ON public.nw_runtime_memory_governance_receipts
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable();
GRANT SELECT,INSERT ON public.nw_runtime_memory_governance_receipts TO skillforge;

COMMENT ON TABLE public.nw_runtime_memory_governance_receipts IS '个人记忆治理原请求的不可变受理身份与时间；Runtime结果由原journal唯一维护';
COMMENT ON COLUMN public.nw_runtime_memory_governance_receipts.tenant_id IS '可信当前机构，与原事实revision及RLS保持同租户';
COMMENT ON COLUMN public.nw_runtime_memory_governance_receipts.request_key IS '客户端原幂等键，缺省时按完整请求摘要稳定派生';
COMMENT ON COLUMN public.nw_runtime_memory_governance_receipts.fact_id IS '治理请求所针对的原事实身份';
COMMENT ON COLUMN public.nw_runtime_memory_governance_receipts.expected_revision IS '原请求冻结的精确事实revision，不能由当前head替换';
COMMENT ON COLUMN public.nw_runtime_memory_governance_receipts.operation IS '具体治理动作，由所属服务按公开请求固定';
COMMENT ON COLUMN public.nw_runtime_memory_governance_receipts.request_fingerprint IS '完整具体请求的规范SHA-256；同key异摘要冲突';
COMMENT ON COLUMN public.nw_runtime_memory_governance_receipts.accepted_at IS '首次受理时数据库采样的微秒绝对时刻；重投原样复用';
COMMENT ON CONSTRAINT memory_governance_receipts_pk ON public.nw_runtime_memory_governance_receipts IS '机构内原请求唯一，不覆盖已有受理事实';
COMMENT ON CONSTRAINT memory_governance_receipts_revision_fk ON public.nw_runtime_memory_governance_receipts IS '原治理请求精确关联同机构不可变事实revision';
COMMENT ON CONSTRAINT memory_governance_receipts_identity_ck ON public.nw_runtime_memory_governance_receipts IS '治理身份、动作非空且预期revision为正数';
COMMENT ON CONSTRAINT memory_governance_receipts_fingerprint_ck ON public.nw_runtime_memory_governance_receipts IS '规范请求摘要固定为小写SHA-256';
COMMENT ON INDEX public.memory_governance_receipts_revision_idx IS '支持原revision关联读取及删除引用检查';
