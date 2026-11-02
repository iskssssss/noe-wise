-- Memory 首版不可变正文、状态修订引用与自动提取来源支持关系。
-- 仅新建/独立受控库可直接应用。既有 Memory 行须另行批准离线转换及恢复方案；不兼容双读双写。
-- 规范二进制的 extraction_digest 由 Java 字段编码计算，不能用任意 SQL JSON 摘要代替。
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM public.agent_runtime_memory_fact_revisions) THEN
        RAISE EXCEPTION 'memory immutable content migration requires fresh storage or approved offline conversion';
    END IF;
END $$;

ALTER TABLE public.agent_runtime_memory_fact_revisions
    ALTER COLUMN fact_data DROP NOT NULL,
    ADD COLUMN content_revision bigint NOT NULL DEFAULT 1,
    ADD COLUMN fact_data_digest varchar(64),
    ADD COLUMN extraction_digest varchar(64),
    ADD COLUMN recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    ADD CONSTRAINT fk_agent_runtime_memory_content_revision FOREIGN KEY (tenant_id,fact_id,content_revision)
        REFERENCES public.agent_runtime_memory_fact_revisions(tenant_id,fact_id,revision)
        DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT ck_agent_runtime_memory_content_revision CHECK (content_revision=1 AND content_revision<=revision),
    ADD CONSTRAINT ck_agent_runtime_memory_content_owner CHECK (
        (revision=1 AND fact_data IS NOT NULL AND fact_data_digest IS NOT NULL AND extraction_digest IS NOT NULL
            AND fact_data_digest ~ '^[0-9a-f]{64}$' AND extraction_digest ~ '^[0-9a-f]{64}$')
        OR (revision>1 AND fact_data IS NULL AND fact_data_digest IS NULL AND extraction_digest IS NULL)),
    ADD CONSTRAINT ck_agent_runtime_memory_content_digest CHECK (
        fact_data IS NULL OR fact_data_digest=encode(sha256(fact_data),'hex'));

CREATE INDEX ix_agent_runtime_memory_extraction_content
    ON public.agent_runtime_memory_fact_revisions(tenant_id,extraction_digest,fact_id)
    WHERE revision=1;
ALTER TABLE public.agent_runtime_memory_fact_sources
    ADD CONSTRAINT ck_agent_runtime_memory_source_content_owner CHECK (revision=1);

-- 延迟来源守卫检查同事实的正文所属revision；状态修订复用首版来源，首版仍不得缺少已提交来源。
CREATE OR REPLACE FUNCTION public.assert_agent_runtime_memory_sources()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_memory_fact_sources s
         WHERE s.tenant_id = NEW.tenant_id AND s.fact_id = NEW.fact_id
           AND s.revision = NEW.content_revision) THEN
        RAISE EXCEPTION 'memory fact revision requires committed source events';
    END IF;
    RETURN NULL;
END;
$$;
COMMENT ON TRIGGER trg_agent_runtime_memory_sources_required ON public.agent_runtime_memory_fact_revisions IS
    '提交时保证每个事实修订的同tenant/fact正文所属revision至少绑定一个已提交SessionLog来源；状态修订复用首版来源';

COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.content_revision IS
    '同一tenant/fact首版不可变正文与来源revision；状态修订复用，不跨事实或scope寻址';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.fact_data IS
    '仅首版revision保存完整MemoryFact规范二进制；状态revision为NULL并组合状态列还原';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.fact_data_digest IS
    '首版规范二进制SHA-256；读取与数据库约束均核对精确字节';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.extraction_digest IS
    'Java规范长度前缀语义身份SHA-256；包括归属scope/owner/schema/审核/时限，摘要不能替代精确正文核验';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.recorded_at IS
    '该不可变状态revision首次记录的数据库时间；被替代历史30天从替代状态首次记录起算，不用业务validTo或原事实createdAt';
COMMENT ON CONSTRAINT fk_agent_runtime_memory_content_revision ON public.agent_runtime_memory_fact_revisions IS
    '任何状态revision保护同事实首版正文，不能先回收首版再保留状态历史';
COMMENT ON COLUMN public.agent_runtime_memory_operations.command_data IS
    '私有命令v3规范身份：完整调用上下文/操作参数和CREATE或SUPERSEDE草稿规范长度及SHA-256；草稿正文由result_fact_id/result_revision精确不可变事实拥有，命中仍逐字段核对';

CREATE TABLE public.agent_runtime_memory_extraction_evidence (
    tenant_id text NOT NULL,
    extraction_digest varchar(64) NOT NULL,
    agent_id text NOT NULL,
    source_target_kind varchar(16) NOT NULL,
    source_target_id text NOT NULL,
    source_session_id text NOT NULL,
    source_event_id text NOT NULL,
    extractor_version text NOT NULL,
    fact_id text NOT NULL,
    revision bigint NOT NULL,
    access_audit_ref text NOT NULL,
    source_audit_ref text NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_agent_runtime_memory_extraction_evidence PRIMARY KEY
        (tenant_id,source_target_kind,source_target_id,source_session_id,source_event_id,extractor_version,extraction_digest),
    CONSTRAINT fk_agent_runtime_memory_extraction_evidence_fact FOREIGN KEY (tenant_id,fact_id,revision)
        REFERENCES public.agent_runtime_memory_fact_revisions(tenant_id,fact_id,revision) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_agent_runtime_memory_extraction_evidence_source FOREIGN KEY
        (tenant_id,source_target_kind,source_target_id,source_session_id,source_event_id)
        REFERENCES public.agent_runtime_session_events(tenant_id,target_kind,target_id,session_id,event_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_agent_runtime_memory_extraction_evidence_identity CHECK (
        btrim(tenant_id)<>'' AND btrim(agent_id)<>'' AND source_target_kind IN ('AGENT','TEAM')
        AND btrim(source_target_id)<>'' AND btrim(source_session_id)<>'' AND btrim(source_event_id)<>''
        AND btrim(extractor_version)<>'' AND btrim(fact_id)<>'' AND revision>0
        AND btrim(access_audit_ref)<>'' AND btrim(source_audit_ref)<>'' AND extraction_digest ~ '^[0-9a-f]{64}$')
);
CREATE INDEX ix_agent_runtime_memory_extraction_evidence_fact
    ON public.agent_runtime_memory_extraction_evidence(tenant_id,fact_id,revision);
ALTER TABLE public.agent_runtime_memory_extraction_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_memory_extraction_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_memory_extraction_evidence_tenant ON public.agent_runtime_memory_extraction_evidence
    USING (tenant_id=coalesce(current_setting('app.tenant_id',true),''))
    WITH CHECK (tenant_id=coalesce(current_setting('app.tenant_id',true),''));
CREATE TRIGGER trg_agent_runtime_memory_extraction_evidence_immutable
    BEFORE UPDATE OR DELETE ON public.agent_runtime_memory_extraction_evidence
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable();
COMMENT ON TABLE public.agent_runtime_memory_extraction_evidence IS
    '自动偏好精确复用的新增已授权来源支持；不改写原Fact，不伪装CREATE，不拥有原始来源正文';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_evidence.extraction_digest IS
    '同scope/owner精确语义身份；与来源稳定键组成幂等支持关系，不能跨权限复用';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_evidence.revision IS
    '首次复用接受的精确历史revision；后来状态变更不改写支持关系';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_evidence.access_audit_ref IS
    '本次CREATE访问策略的显式Allow审计引用；复用仍重新检查读权限';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_evidence.source_audit_ref IS
    '本次已提交原始来源的显式Allow审计引用';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_evidence.recorded_at IS
    '新增来源支持的数据库提交记录时间，不替代原事实业务有效时间';

-- M3：任务结果新增精确revision保护，防止把COMPLETED误视为解除引用。
ALTER TABLE public.agent_runtime_memory_extraction_jobs
    ADD CONSTRAINT fk_agent_runtime_memory_extraction_result FOREIGN KEY (tenant_id,result_fact_id,result_revision)
        REFERENCES public.agent_runtime_memory_fact_revisions(tenant_id,fact_id,revision)
        DEFERRABLE INITIALLY DEFERRED;

-- M2：按既有(tenant_id,fact_id)主键游标先选最多200个ACTIVE身份，再只计算这页。
-- updated_at只在实际分数变化时写入，不作为轮转游标；无需新增更新时间轮转索引。

-- 保留边界：现存revision/source/operation/conflict审计与本支持表继续禁止UPDATE/DELETE。
-- completed/skipped job仅压缩candidate_data，来源键、结果指针及可重试失败材料不删除。
-- 普通物理回收须等待批准业务cutoff与Runtime退役保护gate，不从终态/ACK推定可删。

-- 用户已批准被替代历史默认30天；允许延长，不把任何幂等墓碑改成30天窗口。
-- 判断真实Memory所有者ARCHIVED+superseded指针及关闭时间，不消费caller退役声明。
CREATE FUNCTION public.agent_runtime_memory_history_unprotected(
    requested_tenant text,requested_fact text,requested_revision bigint,cutoff timestamptz)
RETURNS boolean LANGUAGE plpgsql STABLE AS $$
DECLARE origin_session text;
BEGIN
    IF requested_tenant IS DISTINCT FROM nullif(current_setting('app.tenant_id',true),'')
        OR cutoff IS NULL OR cutoff>now()-interval '30 days' OR requested_revision<=1 THEN RETURN false; END IF;
    SELECT current.source_session_id INTO origin_session
    FROM public.agent_runtime_memory_fact_heads h
    JOIN public.agent_runtime_memory_fact_revisions current ON current.tenant_id=h.tenant_id
        AND current.fact_id=h.fact_id AND current.revision=h.current_revision
    JOIN public.agent_runtime_memory_fact_revisions candidate ON candidate.tenant_id=h.tenant_id
        AND candidate.fact_id=h.fact_id AND candidate.revision=requested_revision
    WHERE h.tenant_id=requested_tenant AND h.fact_id=requested_fact AND requested_revision<h.current_revision
        AND candidate.revision<>candidate.content_revision AND current.status='ARCHIVED'
        AND current.superseded_fact_id IS NOT NULL AND current.valid_to_epoch_second IS NOT NULL
        AND current.recorded_at<cutoff AND candidate.recorded_at<cutoff;
    IF origin_session IS NULL THEN RETURN false; END IF;
    -- 回执、来源及首次接受的提取结果保护精确revision；TRANSITION回放还需要前一revision。
    IF EXISTS(SELECT 1 FROM public.agent_runtime_memory_operations o WHERE o.tenant_id=requested_tenant
        AND o.result_fact_id=requested_fact AND (o.result_revision=requested_revision
            OR (o.command_kind='TRANSITION' AND o.result_revision=requested_revision+1))) THEN RETURN false; END IF;
    IF EXISTS(SELECT 1 FROM public.agent_runtime_memory_fact_sources s WHERE s.tenant_id=requested_tenant
        AND s.fact_id=requested_fact AND s.revision=requested_revision) THEN RETURN false; END IF;
    IF EXISTS(SELECT 1 FROM public.agent_runtime_memory_fact_revisions r WHERE r.tenant_id=requested_tenant
        AND r.fact_id=requested_fact AND r.content_revision=requested_revision) THEN RETURN false; END IF;
    IF EXISTS(SELECT 1 FROM public.agent_runtime_memory_extraction_jobs j WHERE j.tenant_id=requested_tenant
        AND j.result_fact_id=requested_fact AND j.result_revision=requested_revision) THEN RETURN false; END IF;
    IF EXISTS(SELECT 1 FROM public.agent_runtime_memory_extraction_evidence e WHERE e.tenant_id=requested_tenant
        AND e.fact_id=requested_fact AND e.revision=requested_revision) THEN RETURN false; END IF;
    -- 未终结/等待/UNKNOWN及仍持恢复checkpoint的来源Session，不能借Memory终态解除保护。
    IF EXISTS(SELECT 1 FROM public.agent_runtime_runs r WHERE r.tenant_id=requested_tenant
        AND r.session_id=origin_session AND r.status NOT IN ('COMPLETED','TERMINATED','FAILED','CANCELLED')) THEN RETURN false; END IF;
    IF EXISTS(SELECT 1 FROM public.agent_runtime_actions a WHERE a.tenant_id=requested_tenant
        AND a.session_id=origin_session AND a.state='OUTCOME_UNKNOWN') THEN RETURN false; END IF;
    IF EXISTS(SELECT 1 FROM public.agent_runtime_session_checkpoints c JOIN public.agent_runtime_runs r
        ON r.tenant_id=c.tenant_id AND r.run_id=c.run_id WHERE r.tenant_id=requested_tenant
        AND r.session_id=origin_session) THEN RETURN false; END IF;
    IF EXISTS(SELECT 1 FROM public.nw_external_invocations i JOIN public.agent_runtime_session_events e
        ON i.tenant_id::text=e.tenant_id AND (i.source_event_id=e.event_id OR i.input_id=e.input_id OR i.root_input_id=e.input_id)
        WHERE e.tenant_id=requested_tenant AND e.session_id=origin_session
            AND i.status IN ('STARTED','UNRESOLVED')) THEN RETURN false; END IF;
    -- 其他owner也可读取USER事实；必须扫描精确tenant内的已冻结记忆引用，不能只查原来源Session。
    -- 保护检查消费公开原文；私有body/ref不是可直接解析的冻结业务事实。
    -- helper未就绪或私有正文校验失败时本事务失败闭合，不能把未知恢复当成无引用。
    IF EXISTS(WITH memory_events AS MATERIALIZED (
        SELECT public.agent_runtime_session_event_payload(e.tenant_id,e.session_id,e.target_kind,e.target_id,
            e.end_user_id,e.run_id,e.event_id,e.event_type,e.payload_schema_id,e.payload_canonical_json)::jsonb AS payload
        FROM public.agent_runtime_session_events e WHERE e.tenant_id=requested_tenant
            AND e.event_type='memory.written')
        SELECT 1 FROM memory_events e WHERE (
            (e.payload#>>'{fact,ref,factId}'=requested_fact
                AND e.payload#>>'{fact,revision}'=requested_revision::text)
            OR (e.payload#>>'{before,ref,factId}'=requested_fact
                AND e.payload#>>'{before,revision}'=requested_revision::text)
            OR (coalesce(e.payload#>>'{command,fact,factId}',e.payload#>>'{command,current,factId}')=requested_fact
                AND e.payload#>>'{command,expectedRevision}'=requested_revision::text))) THEN RETURN false; END IF;
    IF EXISTS(WITH retrieval_events AS MATERIALIZED (
        SELECT public.agent_runtime_session_event_payload(e.tenant_id,e.session_id,e.target_kind,e.target_id,
            e.end_user_id,e.run_id,e.event_id,e.event_type,e.payload_schema_id,e.payload_canonical_json)::jsonb AS payload
        FROM public.agent_runtime_session_events e WHERE e.tenant_id=requested_tenant
            AND e.event_type='retrieval.performed')
        SELECT 1 FROM retrieval_events e CROSS JOIN LATERAL
            jsonb_array_elements(coalesce(e.payload->'facts','[]'::jsonb)) fact
        WHERE fact#>>'{ref,factId}'=requested_fact AND fact->>'revision'=requested_revision::text) THEN RETURN false; END IF;
    RETURN true;
END $$;

-- 普通写入仍不可删除历史。只有维护器绑定了事务级截止点并通过实际引用核验才允许状态行DELETE。
CREATE FUNCTION public.guard_agent_runtime_memory_history_delete()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE cutoff_text text;
BEGIN
    cutoff_text:=nullif(current_setting('app.memory_history_retention_cutoff',true),'');
    IF cutoff_text IS NULL OR NOT public.agent_runtime_memory_history_unprotected(
        OLD.tenant_id,OLD.fact_id,OLD.revision,cutoff_text::timestamptz) THEN
        RAISE EXCEPTION 'memory history is protected';
    END IF;
    RETURN OLD;
END $$;
DROP TRIGGER trg_agent_runtime_memory_revisions_immutable ON public.agent_runtime_memory_fact_revisions;
CREATE TRIGGER trg_agent_runtime_memory_revisions_immutable
    BEFORE UPDATE ON public.agent_runtime_memory_fact_revisions
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable();
CREATE TRIGGER trg_agent_runtime_memory_history_delete_guard
    BEFORE DELETE ON public.agent_runtime_memory_fact_revisions
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_memory_history_delete();
COMMENT ON FUNCTION public.agent_runtime_memory_history_unprotected(text,text,bigint,timestamptz) IS
    'Memory所有者实际替代后30天状态历史回收门禁：保护正文首版、当前head、回执及其前态、来源、提取结果、Runtime冻结/等待/UNKNOWN';
COMMENT ON FUNCTION public.guard_agent_runtime_memory_history_delete() IS
    '仅受控事务级cutoff且数据库实际无保护引用时允许状态历史删除；正文、来源与幂等墓碑不提前回收';
