-- Runtime Session 私有正文引用与受控展示历史退役；仅面向新建或明确批准重建的目标 schema。
-- 既有数据的转换、恢复与应用切换属于独立环境操作，不由本迁移自动执行。
-- Every write uses the existing Session/Run transaction and app.tenant_id, no independent commit.
CREATE TABLE public.agent_runtime_session_checkpoint_bodies (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    content_hash varchar(64) NOT NULL,
    identity_data text NOT NULL,
    schema_id varchar(128) NOT NULL,
    payload_data bytea NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT session_checkpoint_bodies_pk PRIMARY KEY (tenant_id, run_id, content_hash),
    CONSTRAINT session_checkpoint_bodies_hash CHECK (content_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT session_checkpoint_bodies_payload CHECK (octet_length(payload_data) > 0),
    CONSTRAINT session_checkpoint_bodies_identity CHECK(btrim(tenant_id)<>'' AND btrim(run_id)<>'' AND btrim(identity_data)<>'' AND btrim(schema_id)<>''),
    CONSTRAINT session_checkpoint_bodies_run_fk FOREIGN KEY (tenant_id, run_id)
        REFERENCES public.agent_runtime_runs(tenant_id, run_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE public.agent_runtime_session_checkpoint_body_refs (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    checkpoint_hash varchar(64) NOT NULL,
    body_hash varchar(64) NOT NULL,
    CONSTRAINT session_checkpoint_body_refs_pk PRIMARY KEY (tenant_id, run_id, checkpoint_hash, body_hash),
    CONSTRAINT session_checkpoint_body_refs_checkpoint_fk FOREIGN KEY (tenant_id, run_id, checkpoint_hash)
        REFERENCES public.agent_runtime_session_checkpoints(tenant_id, run_id, content_hash) ON DELETE CASCADE,
    CONSTRAINT session_checkpoint_body_refs_body_fk FOREIGN KEY (tenant_id, run_id, body_hash)
        REFERENCES public.agent_runtime_session_checkpoint_bodies(tenant_id, run_id, content_hash)
);
CREATE INDEX session_checkpoint_body_refs_body_idx
    ON public.agent_runtime_session_checkpoint_body_refs(tenant_id, run_id, body_hash);
ALTER TABLE public.agent_runtime_session_checkpoint_bodies ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_checkpoint_bodies FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_checkpoint_bodies
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
ALTER TABLE public.agent_runtime_session_checkpoint_body_refs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_checkpoint_body_refs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_checkpoint_body_refs
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
CREATE TRIGGER session_checkpoint_bodies_immutable BEFORE UPDATE ON public.agent_runtime_session_checkpoint_bodies
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();
COMMENT ON TABLE public.agent_runtime_session_checkpoint_bodies IS '同租户原Run内的大型不可变checkpoint文本正文；schema和规范字符串摘要精确复用';
COMMENT ON COLUMN public.agent_runtime_session_checkpoint_bodies.tenant_id IS '原Run所属租户，强制RLS隔离';
COMMENT ON COLUMN public.agent_runtime_session_checkpoint_bodies.run_id IS '原Run身份，不跨Run复用';
COMMENT ON COLUMN public.agent_runtime_session_checkpoint_bodies.content_hash IS 'schema与规范JSON文本的SHA-256域分离摘要';
COMMENT ON COLUMN public.agent_runtime_session_checkpoint_bodies.identity_data IS '完整RunIdentity规范编码，读取必须匹配原身份';
COMMENT ON COLUMN public.agent_runtime_session_checkpoint_bodies.schema_id IS '不可变文本格式版本';
COMMENT ON COLUMN public.agent_runtime_session_checkpoint_bodies.payload_data IS 'TypedPayload规范二进制正文，包含完整JSON字符串';
COMMENT ON COLUMN public.agent_runtime_session_checkpoint_bodies.recorded_at IS '首次冻结的数据库时间，不随重复引用更新';
COMMENT ON TABLE public.agent_runtime_session_checkpoint_body_refs IS 'checkpoint到文本正文的完整引用；与正文和checkpoint共享事务';
COMMENT ON COLUMN public.agent_runtime_session_checkpoint_body_refs.tenant_id IS '引用所属租户';
COMMENT ON COLUMN public.agent_runtime_session_checkpoint_body_refs.run_id IS '同Run引用边界';
COMMENT ON COLUMN public.agent_runtime_session_checkpoint_body_refs.checkpoint_hash IS '精确checkpoint摘要，checkpoint整体回收后解除引用';
COMMENT ON COLUMN public.agent_runtime_session_checkpoint_body_refs.body_hash IS '受保护不可变正文摘要，不允许先删正文';
-- payload_data保存原非checkpoint正文，或带独立RFC6901文本引用路径清单的checkpoint-storage.v2私有封套。
COMMENT ON COLUMN public.agent_runtime_session_checkpoints.payload_data IS '原TypedPayload或checkpoint-storage.v2引用表示的规范二进制；恢复必须验证原schema、身份及完整checkpoint摘要';

CREATE TABLE public.agent_runtime_session_output_chunks (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    end_user_id bigint NOT NULL,
    run_id text NOT NULL,
    message_id text NOT NULL,
    content_hash varchar(64) NOT NULL,
    canonical_content text NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT session_output_chunks_pk PRIMARY KEY (tenant_id, run_id, message_id, content_hash),
    CONSTRAINT session_output_chunks_hash CHECK (content_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT session_output_chunks_content CHECK (left(canonical_content, 1) = '"' AND right(canonical_content, 1) = '"'),
    CONSTRAINT session_output_chunks_identity CHECK(btrim(tenant_id)<>'' AND btrim(session_id)<>'' AND target_kind IN('AGENT','TEAM')
        AND btrim(target_id)<>'' AND end_user_id>0 AND btrim(run_id)<>'' AND btrim(message_id)<>''),
    CONSTRAINT session_output_chunks_owner_fk FOREIGN KEY(tenant_id,target_kind,target_id,session_id,end_user_id)
        REFERENCES public.agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,end_user_id),
    CONSTRAINT session_output_chunks_run_fk FOREIGN KEY (tenant_id,target_kind,target_id,session_id,run_id)
        REFERENCES public.agent_runtime_runs(tenant_id,target_kind,target_id,session_id,run_id) DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE public.agent_runtime_session_output_chunks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_output_chunks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_output_chunks
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
CREATE TRIGGER session_output_chunks_immutable BEFORE UPDATE ON public.agent_runtime_session_output_chunks
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();
COMMENT ON TABLE public.agent_runtime_session_output_chunks IS '已确认助手前缀的固定2048码点不可变块；100ms事件仍保留原cursor和身份，按同来源精确引用恢复';
COMMENT ON COLUMN public.agent_runtime_session_output_chunks.tenant_id IS '输出所属租户，强制RLS隔离';
COMMENT ON COLUMN public.agent_runtime_session_output_chunks.session_id IS '原Session身份';
COMMENT ON COLUMN public.agent_runtime_session_output_chunks.target_kind IS '原Session目标类型';
COMMENT ON COLUMN public.agent_runtime_session_output_chunks.target_id IS '原Session目标身份';
COMMENT ON COLUMN public.agent_runtime_session_output_chunks.end_user_id IS '原Session终端用户身份';
COMMENT ON COLUMN public.agent_runtime_session_output_chunks.run_id IS '原Run身份';
COMMENT ON COLUMN public.agent_runtime_session_output_chunks.message_id IS '原模型operation和attempt确定的公开消息身份';
COMMENT ON COLUMN public.agent_runtime_session_output_chunks.content_hash IS 'answer-chunk.v1格式和规范JSON字符串的域分离SHA-256';
COMMENT ON COLUMN public.agent_runtime_session_output_chunks.canonical_content IS '固定块的规范JSON字符串，保存完整转义字节，不把NUL转换为PostgreSQL文本字符';
COMMENT ON COLUMN public.agent_runtime_session_output_chunks.recorded_at IS '首次冻结的数据库时间';

-- SECURITY INVOKER + FORCE RLS. No arbitrary source lookup, no supplier request, no current-state reconstruction.
-- Private content-prefix manifest is an immutable storage envelope inside the existing canonical protocol column.
-- It keeps ordered duplicate chunk references. No per-prefix N-squared relationship rows are introduced.
CREATE FUNCTION public.agent_runtime_session_output_payload(
    requested_tenant text, requested_session text, requested_target_kind text, requested_target text,
    requested_end_user bigint, requested_run text, requested_schema text, stored text)
RETURNS text LANGUAGE plpgsql STABLE SECURITY INVOKER AS $$
DECLARE
    envelope jsonb;
    template text;
    prefix text;
    tail text;
    restored text;
    resolved_count bigint;
    requested_count bigint;
BEGIN
    IF requested_schema <> 'skillforge.runtime.agent.model-answer.v2'
       OR NOT starts_with(stored, '{"$storageType":"session-answer-prefix.v1",') THEN RETURN stored; END IF;
    envelope := stored::jsonb;
    IF (SELECT count(*) FROM jsonb_object_keys(envelope)) <> 7
       OR NOT envelope ?& ARRAY['$storageType','schemaId','messageId','template','chunks','tail','digest']
       OR envelope->>'schemaId' IS DISTINCT FROM requested_schema
       OR jsonb_typeof(envelope->'chunks') <> 'array' THEN
        RAISE EXCEPTION 'session_answer_prefix_reference_rejected';
    END IF;
    requested_count := jsonb_array_length(envelope->'chunks');
    SELECT string_agg(substring(body.canonical_content FROM 2 FOR length(body.canonical_content)-2), '' ORDER BY ref.ordinality), count(*)
      INTO prefix, resolved_count
      FROM jsonb_array_elements_text(envelope->'chunks') WITH ORDINALITY ref(content_hash, ordinality)
      JOIN public.agent_runtime_session_output_chunks body
        ON body.tenant_id=requested_tenant AND body.session_id=requested_session
       AND body.target_kind=requested_target_kind AND body.target_id=requested_target
       AND body.end_user_id=requested_end_user AND body.run_id=requested_run
       AND body.message_id=envelope->>'messageId' AND body.content_hash=ref.content_hash;
    IF requested_count = 0 OR resolved_count <> requested_count THEN
        RAISE EXCEPTION 'session_answer_prefix_reference_rejected';
    END IF;
    template := envelope->>'template';
    tail := envelope->>'tail';
    IF template IS NULL OR tail IS NULL OR template::jsonb->>'messageId' IS DISTINCT FROM envelope->>'messageId'
       OR template::jsonb->>'content' IS DISTINCT FROM '' OR left(tail,1) <> '"' OR right(tail,1) <> '"' THEN
        RAISE EXCEPTION 'session_answer_prefix_reference_rejected';
    END IF;
    restored := replace(template, '"content":""', '"content":"' || prefix || substring(tail FROM 2 FOR length(tail)-2) || '"');
    IF encode(sha256(convert_to(requested_schema,'UTF8') || decode('00','hex') || convert_to(restored,'UTF8')), 'hex')
       IS DISTINCT FROM envelope->>'digest' THEN RAISE EXCEPTION 'session_answer_prefix_reference_rejected'; END IF;
    RETURN restored;
END;
$$;
COMMENT ON FUNCTION public.agent_runtime_session_output_payload IS '同租户Session/Run/message内逐块恢复原规范公开事件；缺失或摘要不符拒绝，恢复后原指纹与游标不变';
-- Normal Runtime consumers select through this function. Direct baseline data reads are not a public decoding API.
-- Existing DB rollout requires a reviewed additive migration and application activation together; no data conversion has been run.
-- Rollback must retain the reader function/body tables until all compact rows are restored to full canonical payload under a separately authorized conversion.
-- Do not drop body tables while any checkpoint/body-ref or output-prefix envelope references them.

-- Candidate maintenance scans use the existing composite PKs as reliable keyset cursors.
-- Owner retirement is NOT automatic: nonterminal Run, CLAIMED/WAITING_INPUT Action, OUTCOME_UNKNOWN,
-- memory_fact_sources/all revisions, extraction_jobs/all states and extraction_evidence protect history.
-- pure diagnostic DELETE also excludes causal, publication and all three exact memory-source references.
-- Ordinary SessionLog/checkpoint/journal/receipt retention still needs an approved Owner-retirement fact and explicit policy.

CREATE TABLE public.agent_runtime_session_history_retirements (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    end_user_id bigint NOT NULL,
    source_session_revision bigint NOT NULL,
    closed_epoch_second bigint NOT NULL,
    closed_nano integer NOT NULL,
    scan_after_sequence bigint NOT NULL DEFAULT 0,
    retired_through_sequence bigint NOT NULL DEFAULT 0,
    retired_event_count bigint NOT NULL DEFAULT 0,
    retired_bytes bigint NOT NULL DEFAULT 0,
    retired_answer_count bigint NOT NULL DEFAULT 0,
    retired_reasoning_count bigint NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'RETIRING',
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT session_history_retirements_pk PRIMARY KEY(tenant_id,target_kind,target_id,session_id),
    CONSTRAINT session_history_retirements_owner_fk FOREIGN KEY(tenant_id,target_kind,target_id,session_id,end_user_id)
        REFERENCES public.agent_runtime_sessions(tenant_id,target_kind,target_id,session_id,end_user_id),
    CONSTRAINT session_history_retirements_identity CHECK(btrim(tenant_id)<>'' AND btrim(session_id)<>'' AND target_kind IN('AGENT','TEAM')
        AND btrim(target_id)<>'' AND end_user_id>0),
    CONSTRAINT session_history_retirements_revision CHECK(source_session_revision>0 AND closed_nano BETWEEN 0 AND 999999999),
    CONSTRAINT session_history_retirements_progress CHECK(scan_after_sequence>=0 AND retired_through_sequence BETWEEN 0 AND scan_after_sequence
        AND retired_event_count>=0 AND retired_bytes>=0 AND retired_answer_count>=0 AND retired_reasoning_count>=0
        AND retired_event_count=retired_answer_count+retired_reasoning_count),
    CONSTRAINT session_history_retirements_status CHECK(status IN ('RETIRING','RETIRED'))
);
CREATE TABLE public.agent_runtime_session_event_tombstones (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    end_user_id bigint NOT NULL,
    event_id text NOT NULL,
    session_sequence bigint NOT NULL,
    event_digest varchar(64) NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT session_event_tombstones_pk PRIMARY KEY(tenant_id,target_kind,target_id,session_id,event_id),
    CONSTRAINT session_event_tombstones_sequence UNIQUE(tenant_id,target_kind,target_id,session_id,session_sequence),
    CONSTRAINT session_event_tombstones_retirement_fk FOREIGN KEY(tenant_id,target_kind,target_id,session_id)
        REFERENCES public.agent_runtime_session_history_retirements(tenant_id,target_kind,target_id,session_id),
    CONSTRAINT session_event_tombstones_digest CHECK(event_digest ~ '^[a-f0-9]{64}$' AND session_sequence>0)
);
ALTER TABLE public.agent_runtime_session_history_retirements ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_history_retirements FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_history_retirements
    USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
ALTER TABLE public.agent_runtime_session_event_tombstones ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_event_tombstones FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_event_tombstones
    USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE TRIGGER session_event_tombstones_immutable BEFORE UPDATE ON public.agent_runtime_session_event_tombstones
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();
CREATE INDEX session_closed_history_scan ON public.agent_runtime_sessions(tenant_id,target_kind,target_id,session_id)
    WHERE status='CLOSED';
CREATE INDEX session_answer_message_bounds ON public.agent_runtime_session_events
    (tenant_id,target_kind,target_id,session_id,run_id,(substring(payload_canonical_json FROM '"messageId":"([^"]+)"')),session_sequence)
    WHERE event_type IN ('agent.model-answer','agent.model-answer-adopted');
COMMENT ON TABLE public.agent_runtime_session_history_retirements IS '由真实CLOSED Session修订及关闭时刻登记的普通展示历史退役事实与有界续接水位；Run恢复/重放账本不退役';
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.tenant_id IS 'Owner租户，强制RLS';
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.session_id IS '精确Runtime Session身份';
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.target_kind IS '精确Owner目标类型';
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.target_id IS '精确Owner目标身份';
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.end_user_id IS '关闭事实中的终端用户身份';
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.source_session_revision IS '权威CLOSED Session修订，来源guard强制匹配';
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.closed_epoch_second IS '原关闭事实UTC秒';
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.closed_nano IS '原关闭事实纳秒，不降精度';
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.scan_after_sequence IS '已处理展示事件水位，受保护事件也推进；单批有界';
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.retired_through_sequence IS '曾退休展示流的最高处理水位，原sequence不再分配';
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.retired_event_count IS '真实DELETE RETURNING累计行数；墓碑仍保存完整幂等身份摘要';
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.retired_bytes IS '真实删除事件物理正文bytes，输出块另外计量';
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.retired_answer_count IS '已退休的中间助手快照数，首/末片与adoption保留';
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.retired_reasoning_count IS '已退休的推理展示delta数，读API必须声明不完整';
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.status IS 'RETIRING继续本Owner水位；RETIRED表示普通展示扫描完成，不声明全部恢复引用已解除';
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.created_at IS '首次域内退休判断的数据库时刻';
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.updated_at IS '只有真实进度或结束变化更新，不随空轮询变化';
COMMENT ON TABLE public.agent_runtime_session_event_tombstones IS '普通展示退休后的原event身份/seq及全Append命令摘要；同键完整原请求可精确重放，不凭当前正文补造';
COMMENT ON COLUMN public.agent_runtime_session_event_tombstones.tenant_id IS '事件Owner租户';
COMMENT ON COLUMN public.agent_runtime_session_event_tombstones.session_id IS '原Session';
COMMENT ON COLUMN public.agent_runtime_session_event_tombstones.target_kind IS '原Owner目标类型';
COMMENT ON COLUMN public.agent_runtime_session_event_tombstones.target_id IS '原Owner目标身份';
COMMENT ON COLUMN public.agent_runtime_session_event_tombstones.end_user_id IS '原Owner终端用户身份';
COMMENT ON COLUMN public.agent_runtime_session_event_tombstones.event_id IS '原事件幂等身份，永久阻止新body同键绑定';
COMMENT ON COLUMN public.agent_runtime_session_event_tombstones.session_sequence IS '原确认sequence，重放不分配新序号';
COMMENT ON COLUMN public.agent_runtime_session_event_tombstones.event_digest IS '全Append命令加原sequence规范二进制SHA-256，含格式/body/目标/trace/时间/指纹';
COMMENT ON COLUMN public.agent_runtime_session_event_tombstones.recorded_at IS '首次退休并冻结墓碑的数据库时刻';

-- S03 F1 precise unused-upload gate; fresh target schema only.
-- Approved 2026-10-14; no existing environment conversion is performed by this migration.
-- Fresh V284 target schema only: real pin/Run/source protocol is authoritative.
-- Namespace accepted by parent:
-- agent_runtime_chat_unused_media_owner_gate(text,text,text,uuid) -> boolean.
-- true means ONLY this root's closed-owner prerequisites and exact protections passed;
-- it is never an all-owner retirement marker and never deletes replay facts.
--
-- LOCK ORDER:
-- Runtime intake/lifecycle -> owner root NO KEY UPDATE -> real Session FOR UPDATE.
-- F1 gate uses the same target_kind,target_id,session_id order, then media domain:
-- tenants FOR UPDATE -> root/descendant metadata ORDER BY uuid FOR UPDATE -> body GC.
-- Each root uses one independent Spring transaction. No next root gate is entered
-- while the previous root's tenant lock is retained. No Run FOR UPDATE is added.
--
-- PRODUCERS / PROTECTED NAMESPACES:
-- 1. chat_session_attachments: (tenant_id,session_id product,runtime_session_id,id),
--    referenced_at,media_run_id,source_media_id,body_format. upload/register creates
--    source-v1 + normalize-v1 without pin; registerForRun ALWAYS sets media_run_id and
--    pins. read/reference/visible/requireCurrent/resolveContent/prepare/crop/findRegistered
--    share tenant lock and pin before returning frozen/used ref. Descendants and shared
--    chat_attachment_bodies(tenant_id,sha256,size,format) FK are checked again after gate.
-- 2. ChatInputReferenceResolver.freeze -> resolveContent(reference/pin) happens before
--    envelope -> studio primary/admission plan -> Runtime.accept. New legitimate
--    agent_runtime_input_ledger.command_data/receipt_data images are already pinned.
--    Namespace: input.v1 content[].type=image, media:{owner:{tenantId,sessionId},id,
--    sha256,mimeType,byteLength,width,height}; raw contentRequests[].mediaId selectors
--    are NOT a full authoritative MediaRef. Current Gateway stores only the resolved,
--    pinned payload; it does not persist an unpinned resumable raw selector before freeze.
-- 3. agent_runtime_session_inbox.entry_data inherits original input identity/payload.
--    agent_runtime_runs.record_data may embed original exact input/continuation.
--    agent_runtime_session_initial_runs.plan_data contains frozen admission payload.
--    agent_runtime_session_mutations.command_data and agent_runtime_sessions.create_command_data
--    usually carry lifecycle identities only, but any actual typed media ref is protected.
-- 4. agent_runtime_session_commit_journals.command_data and checkpoint references:
--    agent_runtime_session_checkpoints(schema_id,identity_data,payload_data), and
--    agent_runtime_session_checkpoint_body_refs -> checkpoint_bodies(payload_data).
--    These are downstream of frozen input or MediaPort.prepare/read. No current legal
--    producer can persist such an image before pin. Their mere existence does not retain
--    an unrelated never-pinned upload. Do not add a generic opaque-body decoder here.
-- 5. agent_runtime_session_preparations.prepared_data/payload_data,
--    session_action_preparations.prepared_data and *_model/tool/workflow_operations.command_data.
--    Original input images, prepared model image variants and read_image/browser results
--    carry exact MediaRef; tool-result output is pinned and/or Run-bound before commit.
-- 6. SessionLog: agent_runtime_session_events(payload_schema_id,payload_canonical_json),
--    event_body_refs -> event_bodies(canonical_content), output envelopes -> output chunks.
--    read_image/browser output has already pinned/Run-bound its image. Publication,
--    delivery/cause refs, extraction/source evidence and investigation protecting an
--    actual image inherit this non-clearable pin. They are not cleared by this gate.
-- 7. Existing before/V255 media has no referenced_at. V284 rejects a nonempty attachment
--    table; no historical conversion is authorized. No blanket inference for old rows.
--    A selected fresh-schema UUID tombstone must survive late same-key upload/variant retry;
--    mapper INSERT ON CONFLICT cannot rebind it; outer Service transaction rolls back body.
--
-- CURRENT REFERENCE PROOF / CENTRAL GUARDS:
-- There is no legal fresh-schema checkpoint/journal/input/media-read producer bypassing
-- pin/Run. All explicit image references reside in chat_session_attachments.source_media_id,
-- media_run_id,referenced_at and the body composite FK. Immutable downstream facts inherit
-- that durable pin. Thus inspect the full explicit family and enforce monotonic pin/Run
-- below; do not treat unrelated opaque histories as image refs or install an empty helper.
-- Any future new producer must join the same tenant lock + pin/Run protocol before freezing.
-- V284's refusal of nonempty legacy attachments is part of this proof. No live conversion.

CREATE OR REPLACE FUNCTION public.agent_runtime_chat_unused_media_owner_gate(
    requested_tenant text, requested_product_session text, requested_runtime_session text, requested_root_media uuid)
RETURNS boolean LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE owner record; closed_session record; seen_product boolean:=false; seen_runtime boolean:=false;
        threshold timestamptz:=clock_timestamp()-interval '30 days'; cutoff_second bigint; cutoff_nano integer;
        media_ids uuid[];
BEGIN
    IF requested_tenant IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RETURN false; END IF;
    IF NOT EXISTS(SELECT 1 FROM public.nw_chat_session_attachments root
        WHERE root.tenant_id::text=requested_tenant AND root.session_id=requested_product_session
          AND root.runtime_session_id=requested_runtime_session AND root.id=requested_root_media
          AND root.mime_type IS NOT NULL AND root.media_transform='source-v1' AND root.source_media_id IS NULL
          AND root.media_run_id IS NULL AND root.referenced_at IS NULL AND root.body_format IS NOT NULL
          AND root.created_at<=threshold) THEN RETURN false; END IF;
    cutoff_second:=floor(extract(epoch FROM threshold))::bigint;
    cutoff_nano:=floor((extract(epoch FROM threshold)-cutoff_second)*1000000000)::integer;
    FOR owner IN SELECT root.target_kind,root.target_id,root.session_id,root.end_user_id
        FROM public.agent_runtime_session_owners root WHERE root.tenant_id=requested_tenant
          AND root.session_id IN(requested_product_session,requested_runtime_session)
        ORDER BY root.target_kind,root.target_id,root.session_id FOR NO KEY UPDATE OF root LOOP
        seen_product:=seen_product OR owner.session_id=requested_product_session;
        seen_runtime:=seen_runtime OR owner.session_id=requested_runtime_session;
        SELECT session.status,session.updated_epoch_second,session.updated_nano INTO closed_session
          FROM public.agent_runtime_sessions session WHERE session.tenant_id=requested_tenant
            AND session.target_kind=owner.target_kind AND session.target_id=owner.target_id
            AND session.session_id=owner.session_id AND session.end_user_id=owner.end_user_id FOR UPDATE;
        IF NOT FOUND OR closed_session.status<>'CLOSED'
          OR (closed_session.updated_epoch_second,closed_session.updated_nano)>(cutoff_second,cutoff_nano) THEN RETURN false; END IF;
    END LOOP;
    -- Absence of Runtime owner is not a terminal fact; preserve this branch.
    IF NOT seen_product OR NOT seen_runtime THEN RETURN false; END IF;
    IF EXISTS(SELECT 1 FROM public.agent_runtime_runs run WHERE run.tenant_id=requested_tenant
        AND run.session_id IN(requested_product_session,requested_runtime_session)
        AND run.status NOT IN('COMPLETED','TERMINATED','FAILED','CANCELLED'))
      OR EXISTS(SELECT 1 FROM public.agent_runtime_actions action WHERE action.tenant_id=requested_tenant
        AND action.session_id IN(requested_product_session,requested_runtime_session)
        AND action.state IN('CLAIMED','WAITING_INPUT','OUTCOME_UNKNOWN')) THEN RETURN false; END IF;
    WITH RECURSIVE family AS (
        SELECT id FROM public.nw_chat_session_attachments WHERE tenant_id::text=requested_tenant
          AND runtime_session_id=requested_runtime_session AND id=requested_root_media
        UNION
        SELECT child.id FROM public.nw_chat_session_attachments child JOIN family parent ON child.source_media_id=parent.id
          WHERE child.tenant_id::text=requested_tenant AND child.runtime_session_id=requested_runtime_session
    ) SELECT array_agg(id ORDER BY id) INTO media_ids FROM family;
    IF media_ids IS NULL THEN RETURN false; END IF;
    IF EXISTS(SELECT 1 FROM public.nw_chat_session_attachments media WHERE media.tenant_id::text=requested_tenant
        AND media.id=ANY(media_ids) AND media.body_format IS NOT NULL
        AND (media.referenced_at IS NOT NULL OR media.media_run_id IS NOT NULL
          OR media.session_id<>requested_product_session OR media.runtime_session_id<>requested_runtime_session
          OR (media.id<>requested_root_media AND (media.source_media_id<>requested_root_media
            OR media.media_transform<>'normalize-v1')))) THEN RETURN false; END IF;
    -- Media caller now takes tenant/metadata locks and rechecks exact pin/Run/descendant
    -- closure on a fresh READ COMMITTED statement. No replay fact or receipt is removed.
    RETURN true;
END;
$$;

-- Keep the media reference authority durable even if another legitimate maintenance
-- writer is introduced later. This is a production-schema request, not installed here.
CREATE OR REPLACE FUNCTION public.chat_media_reference_monotonic() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.mime_type IS NULL AND NEW.mime_type IS NOT NULL THEN
        RAISE EXCEPTION 'attachment identity cannot be converted into media';
    END IF;
    IF OLD.mime_type IS NOT NULL THEN
        IF (NEW.id,NEW.tenant_id,NEW.session_id,NEW.runtime_session_id,NEW.source_media_id,
            NEW.media_transform,NEW.mime_type,NEW.pixel_width,NEW.pixel_height,NEW.sha256,NEW.size)
          IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.session_id,OLD.runtime_session_id,OLD.source_media_id,
            OLD.media_transform,OLD.mime_type,OLD.pixel_width,OLD.pixel_height,OLD.sha256,OLD.size) THEN
            RAISE EXCEPTION 'media owner and immutable identity cannot be rebound';
        END IF;
        IF OLD.referenced_at IS NOT NULL AND NEW.referenced_at IS DISTINCT FROM OLD.referenced_at THEN
            RAISE EXCEPTION 'media pin is immutable after first reference';
        END IF;
        IF OLD.media_run_id IS NOT NULL AND NEW.media_run_id IS DISTINCT FROM OLD.media_run_id THEN
            RAISE EXCEPTION 'media Run binding cannot return to unused';
        END IF;
        IF OLD.body_format IS NULL AND NEW.body_format IS NOT NULL THEN
            RAISE EXCEPTION 'retired media UUID cannot be rebound';
        END IF;
        IF OLD.body_format IS NULL AND NEW.referenced_at IS DISTINCT FROM OLD.referenced_at THEN
            RAISE EXCEPTION 'retired media cannot acquire a new reference';
        END IF;
        IF OLD.body_format IS NULL AND NEW.media_run_id IS DISTINCT FROM OLD.media_run_id THEN
            RAISE EXCEPTION 'retired media cannot acquire a new Run binding';
        END IF;
        IF NEW.body_format IS NULL AND (OLD.referenced_at IS NOT NULL OR OLD.media_run_id IS NOT NULL) THEN
            RAISE EXCEPTION 'used or Run-bound media body cannot be released';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER chat_media_reference_monotonic BEFORE UPDATE ON public.nw_chat_session_attachments
    FOR EACH ROW EXECUTE FUNCTION public.chat_media_reference_monotonic();

-- memory.written private physical storage only replaces large TypedPayload.canonicalJson strings.
-- Physical before/fact/command ref/revision remain visible to Memory retirement guards.
-- Synthetic Memory RunIdentity is valid: bodies are owned by the complete SessionOwner, without a Run FK.
CREATE TABLE public.agent_runtime_session_event_bodies (
    tenant_id text NOT NULL, target_kind text NOT NULL, target_id text NOT NULL, session_id text NOT NULL,
    end_user_id bigint NOT NULL, content_hash varchar(64) NOT NULL, canonical_content text NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT session_event_bodies_pk PRIMARY KEY(tenant_id,target_kind,target_id,session_id,content_hash),
    CONSTRAINT session_event_bodies_owner_fk FOREIGN KEY(tenant_id,target_kind,target_id,session_id,end_user_id)
        REFERENCES public.agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,end_user_id),
    CONSTRAINT session_event_bodies_identity CHECK(btrim(tenant_id)<>'' AND btrim(session_id)<>'' AND target_kind IN('AGENT','TEAM')
        AND btrim(target_id)<>'' AND end_user_id>0),
    CONSTRAINT session_event_bodies_hash CHECK(content_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT session_event_bodies_content CHECK(left(canonical_content,1)='"' AND right(canonical_content,1)='"')
);
CREATE TABLE public.agent_runtime_session_event_body_refs (
    tenant_id text NOT NULL, target_kind text NOT NULL, target_id text NOT NULL, session_id text NOT NULL,
    event_id text NOT NULL, content_hash varchar(64) NOT NULL, original_payload_digest varchar(64) NOT NULL,
    CONSTRAINT session_event_body_refs_pk PRIMARY KEY(tenant_id,target_kind,target_id,session_id,event_id,content_hash),
    CONSTRAINT session_event_body_refs_event_fk FOREIGN KEY(tenant_id,target_kind,target_id,session_id,event_id)
        REFERENCES public.agent_runtime_session_events(tenant_id,target_kind,target_id,session_id,event_id) ON DELETE CASCADE,
    CONSTRAINT session_event_body_refs_body_fk FOREIGN KEY(tenant_id,target_kind,target_id,session_id,content_hash)
        REFERENCES public.agent_runtime_session_event_bodies(tenant_id,target_kind,target_id,session_id,content_hash),
    CONSTRAINT session_event_body_refs_digests CHECK(content_hash ~ '^[a-f0-9]{64}$' AND original_payload_digest ~ '^[a-f0-9]{64}$')
);
CREATE INDEX session_event_body_refs_body_idx ON public.agent_runtime_session_event_body_refs
    (tenant_id,target_kind,target_id,session_id,content_hash);
ALTER TABLE public.agent_runtime_session_event_bodies ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_event_bodies FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_event_bodies
    USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
ALTER TABLE public.agent_runtime_session_event_body_refs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_event_body_refs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_event_body_refs
    USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE TRIGGER session_event_bodies_immutable BEFORE UPDATE ON public.agent_runtime_session_event_bodies
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();
CREATE TRIGGER session_event_body_refs_immutable BEFORE UPDATE ON public.agent_runtime_session_event_body_refs
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();
COMMENT ON TABLE public.agent_runtime_session_event_bodies IS '完整SessionOwner范围内按格式摘要保存一次的Memory事件大正文；公共before/fact引用及版本不隐藏';
COMMENT ON COLUMN public.agent_runtime_session_event_bodies.tenant_id IS '冻结事件的租户，强制RLS';
COMMENT ON COLUMN public.agent_runtime_session_event_bodies.target_kind IS '精确事件Owner目标类型';
COMMENT ON COLUMN public.agent_runtime_session_event_bodies.target_id IS '精确事件Owner目标身份';
COMMENT ON COLUMN public.agent_runtime_session_event_bodies.session_id IS '精确事件Owner Session，不跨Session按摘要授权';
COMMENT ON COLUMN public.agent_runtime_session_event_bodies.end_user_id IS '原Owner终端用户，读取逐项匹配';
COMMENT ON COLUMN public.agent_runtime_session_event_bodies.content_hash IS 'checkpoint-text.v1规范JSON字符串域分离SHA-256';
COMMENT ON COLUMN public.agent_runtime_session_event_bodies.canonical_content IS '原TypedPayload.canonicalJson字符串的完整规范JSON编码，不是当前Memory head';
COMMENT ON COLUMN public.agent_runtime_session_event_bodies.recorded_at IS '首次冻结数据库时刻';
COMMENT ON TABLE public.agent_runtime_session_event_body_refs IS '同事务SessionEvent到大正文的精确保护引用；原schema及整体payload摘要在读侧闭合';
COMMENT ON COLUMN public.agent_runtime_session_event_body_refs.tenant_id IS '原事件租户';
COMMENT ON COLUMN public.agent_runtime_session_event_body_refs.target_kind IS '原事件Owner目标类型';
COMMENT ON COLUMN public.agent_runtime_session_event_body_refs.target_id IS '原事件Owner目标身份';
COMMENT ON COLUMN public.agent_runtime_session_event_body_refs.session_id IS '原事件Owner Session';
COMMENT ON COLUMN public.agent_runtime_session_event_body_refs.event_id IS '精确原不可变事件身份';
COMMENT ON COLUMN public.agent_runtime_session_event_body_refs.content_hash IS '受保护正文摘要，回收必须确认所有事件引用解除';
COMMENT ON COLUMN public.agent_runtime_session_event_body_refs.original_payload_digest IS '原public schema及完整canonicalJson域分离SHA-256；正文不能从当前状态补造';
CREATE FUNCTION public.agent_runtime_session_event_payload(requested_tenant text, requested_session text,
    requested_target_kind text, requested_target text, requested_end_user bigint, requested_run text,
    requested_event text, requested_type text, requested_schema text, stored text)
RETURNS text LANGUAGE plpgsql STABLE SECURITY INVOKER AS $$
DECLARE reference record; restored text := stored; expected_digest text; resolved integer := 0; token text;
BEGIN
    IF requested_type<>'memory.written' OR requested_schema<>'skillforge.runtime.memory.written.v1'
       OR NOT stored ~ '"canonicalJson":"@runtime-session-content:[a-f0-9]{64}"' THEN
        RETURN public.agent_runtime_session_output_payload(requested_tenant,requested_session,requested_target_kind,
            requested_target,requested_end_user,requested_run,requested_schema,stored);
    END IF;
    FOR reference IN SELECT body.content_hash,body.canonical_content,ref.original_payload_digest
        FROM public.agent_runtime_session_event_body_refs ref JOIN public.agent_runtime_session_event_bodies body
            ON (body.tenant_id,body.target_kind,body.target_id,body.session_id,body.content_hash)
                =(ref.tenant_id,ref.target_kind,ref.target_id,ref.session_id,ref.content_hash)
        WHERE ref.tenant_id=requested_tenant AND ref.target_kind=requested_target_kind AND ref.target_id=requested_target
            AND ref.session_id=requested_session AND ref.event_id=requested_event AND body.end_user_id=requested_end_user LOOP
        IF expected_digest IS NOT NULL AND expected_digest<>reference.original_payload_digest THEN
            RAISE EXCEPTION 'session_event_body_reference_rejected';
        END IF;
        expected_digest := reference.original_payload_digest;
        IF encode(sha256(convert_to('skillforge.runtime.session.checkpoint-text.v1','UTF8')||decode('00','hex')||convert_to(reference.canonical_content,'UTF8')),'hex')
           <>reference.content_hash THEN RAISE EXCEPTION 'session_event_body_reference_rejected'; END IF;
        token := '"canonicalJson":"@runtime-session-content:'||reference.content_hash||'"';
        IF strpos(restored,token)=0 THEN RAISE EXCEPTION 'session_event_body_reference_rejected'; END IF;
        restored := replace(restored,token,'"canonicalJson":'||reference.canonical_content);
        resolved := resolved+1;
    END LOOP;
    IF resolved=0 OR restored ~ '"canonicalJson":"@runtime-session-content:[a-f0-9]{64}"'
       OR encode(sha256(convert_to(requested_schema,'UTF8')||decode('00','hex')||convert_to(restored,'UTF8')),'hex')
          IS DISTINCT FROM expected_digest THEN RAISE EXCEPTION 'session_event_body_reference_rejected'; END IF;
    RETURN restored;
END;
$$;
COMMENT ON FUNCTION public.agent_runtime_session_event_payload IS '同Owner/event的SessionLog物理格式读取入口：Memory大正文还原原v1并验证全payload摘要，助手前缀委托原输出块解码；私有引用不对外返回';

-- S03 R5 controlled display retirement, complete source protection and exact event tombstones.
-- S03 R5 production request, SOURCE_READY_NOT_RUN. Central V286 writer integrates this after its canonical decoders.
-- No trigger disable, no caller/GUC retention declaration. Existing event UPDATE stays forbidden.
-- Runtime history/diagnostic minimum age is the approved 30 days; app configuration may only extend it.
ALTER TABLE public.agent_runtime_session_history_retirements
    ADD COLUMN resume_after_sequence bigint NOT NULL DEFAULT 0,
    ADD COLUMN recheck_after_sequence bigint,
    ADD COLUMN retired_diagnostic_count bigint NOT NULL DEFAULT 0,
    ADD COLUMN retired_diagnostic_bytes bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT session_history_private_scan CHECK(resume_after_sequence BETWEEN 0 AND scan_after_sequence
        AND (recheck_after_sequence IS NULL OR recheck_after_sequence BETWEEN 0 AND scan_after_sequence)
        AND retired_diagnostic_count>=0 AND retired_diagnostic_bytes>=0);
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.resume_after_sequence IS '私有本轮分页位置，受保护引用解除后可回到原位置；公开水位不回退';
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.recheck_after_sequence IS '本轮最早受保护事件前序号，NULL无待重扫引用；完整扫轮结束后清空并重置私有resume';
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.retired_diagnostic_count IS '诊断真实DELETE计数，保留v2全请求墓碑；不冒充普通展示退休计数';
COMMENT ON COLUMN public.agent_runtime_session_history_retirements.retired_diagnostic_bytes IS '诊断真实删除物理正文bytes，与普通展示计量独立';
COMMENT ON COLUMN public.agent_runtime_session_event_tombstones.event_digest IS 'event-tombstone.v2固定字段顺序、UTF8网络序int32长度前缀及NULL=-1的全Append+原sequence SHA256';

-- REPLACES the earlier V286 function AND trigger block; remove that old block before appending this request.
-- Registration/progress itself requires the real old CLOSED source, including diagnostic-only counters.
-- References may remain blocked while private resume rewinds; only public watermarks/counters are monotonic.
CREATE OR REPLACE FUNCTION public.agent_runtime_session_history_owner_guard() RETURNS trigger
LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE source public.agent_runtime_sessions; root bigint;
BEGIN
 IF NEW.tenant_id IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_history_tenant_required'; END IF;
 SELECT o.end_user_id INTO root FROM public.agent_runtime_session_owners o
    WHERE ROW(o.tenant_id,o.session_id,o.target_kind,o.target_id,o.end_user_id)=
        ROW(NEW.tenant_id,NEW.session_id,NEW.target_kind,NEW.target_id,NEW.end_user_id) FOR NO KEY UPDATE NOWAIT;
 SELECT s.* INTO source FROM public.agent_runtime_sessions s
    WHERE ROW(s.tenant_id,s.session_id,s.target_kind,s.target_id,s.end_user_id)=
        ROW(NEW.tenant_id,NEW.session_id,NEW.target_kind,NEW.target_id,NEW.end_user_id) FOR UPDATE NOWAIT;
 IF root IS NULL OR source.status IS DISTINCT FROM 'CLOSED'
    OR ROW(source.session_revision,source.updated_epoch_second,source.updated_nano) IS DISTINCT FROM
        ROW(NEW.source_session_revision,NEW.closed_epoch_second,NEW.closed_nano)
    OR source.updated_epoch_second::numeric+source.updated_nano::numeric/1000000000 > extract(epoch FROM clock_timestamp()-interval '30 days')
    THEN RAISE EXCEPTION 'session_history_retirement_source_changed'; END IF;
 IF TG_OP='UPDATE' THEN
    IF ROW(NEW.tenant_id,NEW.session_id,NEW.target_kind,NEW.target_id,NEW.end_user_id,
            NEW.source_session_revision,NEW.closed_epoch_second,NEW.closed_nano,NEW.created_at) IS DISTINCT FROM
        ROW(OLD.tenant_id,OLD.session_id,OLD.target_kind,OLD.target_id,OLD.end_user_id,
            OLD.source_session_revision,OLD.closed_epoch_second,OLD.closed_nano,OLD.created_at)
        THEN RAISE EXCEPTION 'session_history_retirement_source_changed'; END IF;
    IF NEW.scan_after_sequence<OLD.scan_after_sequence OR NEW.retired_through_sequence<OLD.retired_through_sequence
        OR NEW.retired_event_count<OLD.retired_event_count OR NEW.retired_bytes<OLD.retired_bytes
        OR NEW.retired_answer_count<OLD.retired_answer_count OR NEW.retired_reasoning_count<OLD.retired_reasoning_count
        OR NEW.retired_diagnostic_count<OLD.retired_diagnostic_count OR NEW.retired_diagnostic_bytes<OLD.retired_diagnostic_bytes
        THEN RAISE EXCEPTION 'session_history_public_progress_regressed'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER session_history_owner_guard BEFORE INSERT OR UPDATE ON public.agent_runtime_session_history_retirements
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_history_owner_guard();

CREATE FUNCTION public.agent_runtime_session_history_field(value text) RETURNS bytea
LANGUAGE sql IMMUTABLE SECURITY INVOKER AS $$
    SELECT CASE WHEN value IS NULL THEN int4send(-1)
        ELSE int4send(octet_length(convert_to(value,'UTF8')))||convert_to(value,'UTF8') END
$$;

-- Same 25 scalar fields as SessionHistoryDigest; no class names, JSON reconstruction or loss of nanoseconds.
CREATE FUNCTION public.agent_runtime_session_event_digest(event public.agent_runtime_session_events)
RETURNS text LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE canonical text; value text; bytes bytea := ''::bytea;
BEGIN
    canonical := public.agent_runtime_session_event_payload(event.tenant_id,event.session_id,event.target_kind,event.target_id,
        event.end_user_id,event.run_id,event.event_id,event.event_type,event.payload_schema_id,event.payload_canonical_json);
    FOREACH value IN ARRAY ARRAY['skillforge.runtime.session.event-tombstone.v2',event.tenant_id,event.session_id,
        event.target_kind,event.target_id,event.end_user_id::text,event.agent_id,event.input_id,event.run_id,event.execution_id,
        event.action_id,event.delegation_id,event.event_id,event.event_type,event.event_version,event.event_fingerprint,
        event.trace_id,event.span_id,event.root_input_id,event.caused_by_event_id,event.payload_schema_id,canonical,
        event.occurred_at_epoch_second::text,event.occurred_at_nano::text,event.session_sequence::text]
    LOOP bytes := bytes || public.agent_runtime_session_history_field(value); END LOOP;
    RETURN encode(sha256(bytes),'hex');
END $$;

CREATE FUNCTION public.agent_runtime_session_history_owner_protection(t text,s text,k text,id text,u bigint)
RETURNS text[] LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE reasons text[];
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_history_tenant_required'; END IF;
 SELECT COALESCE(array_agg(reason),'{}'::text[]) INTO reasons FROM (
    SELECT 'ACTIVE_OR_WAITING_RUN' AS reason WHERE EXISTS(SELECT 1 FROM public.agent_runtime_runs r
        WHERE (r.tenant_id,r.session_id,r.target_kind,r.target_id,r.end_user_id)=(t,s,k,id,u)
          AND r.status NOT IN('COMPLETED','TERMINATED','FAILED','CANCELLED'))
    UNION ALL SELECT 'UNKNOWN_EFFECT' WHERE EXISTS(SELECT 1 FROM public.agent_runtime_actions a
        WHERE a.tenant_id=t AND a.session_id=s AND a.state='OUTCOME_UNKNOWN')
    UNION ALL SELECT 'ACTIVE_OR_WAITING_ACTION' WHERE EXISTS(SELECT 1 FROM public.agent_runtime_actions a
        WHERE a.tenant_id=t AND a.session_id=s AND a.state IN('CLAIMED','WAITING_INPUT'))
    UNION ALL SELECT 'MEMORY_SOURCE_REF' WHERE EXISTS(SELECT 1 FROM public.agent_runtime_memory_fact_sources a
        WHERE (a.tenant_id,a.source_session_id,a.source_target_kind,a.source_target_id)=(t,s,k,id))
    UNION ALL SELECT 'MEMORY_EXTRACTION_JOB_REF' WHERE EXISTS(SELECT 1 FROM public.agent_runtime_memory_extraction_jobs a
        WHERE (a.tenant_id,a.source_session_id,a.source_target_kind,a.source_target_id)=(t,s,k,id))
    UNION ALL SELECT 'MEMORY_EXTRACTION_EVIDENCE_REF' WHERE EXISTS(SELECT 1 FROM public.agent_runtime_memory_extraction_evidence a
        WHERE (a.tenant_id,a.source_session_id,a.source_target_kind,a.source_target_id)=(t,s,k,id))
    UNION ALL SELECT 'PUBLICATION_NOT_ACKNOWLEDGED' WHERE EXISTS(SELECT 1 FROM public.agent_runtime_run_state_publications p
        JOIN public.agent_runtime_runs r ON r.tenant_id=p.tenant_id AND r.run_id=p.source_id
        WHERE (r.tenant_id,r.session_id,r.target_kind,r.target_id,r.end_user_id)=(t,s,k,id,u) AND p.status<>'ACKNOWLEDGED')
    UNION ALL SELECT 'EXECUTION_PUBLICATION_NOT_ACKNOWLEDGED' WHERE EXISTS(SELECT 1 FROM public.agent_runtime_execution_state_publications p
        JOIN public.agent_runtime_executions e ON e.tenant_id=p.tenant_id AND e.execution_id=p.source_id
        WHERE e.tenant_id=t AND e.session_id=s AND p.status<>'ACKNOWLEDGED')
    UNION ALL SELECT 'ACTION_PUBLICATION_NOT_ACKNOWLEDGED' WHERE EXISTS(SELECT 1 FROM public.agent_runtime_action_state_publications p
        JOIN public.agent_runtime_actions a ON a.tenant_id=p.tenant_id AND a.action_id=p.source_id
        WHERE a.tenant_id=t AND a.session_id=s AND p.status<>'ACKNOWLEDGED')
    UNION ALL SELECT 'EVENT_DELIVERY_NOT_SETTLED' WHERE EXISTS(SELECT 1 FROM public.agent_runtime_event_deliveries d
        JOIN public.agent_runtime_session_events e ON e.tenant_id=d.tenant_id AND e.event_id=d.event_id
        WHERE (e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id)=(t,s,k,id,u) AND d.state<>'DELIVERED')
    UNION ALL SELECT 'SESSION_OUTBOX_NOT_SETTLED' WHERE EXISTS(SELECT 1 FROM public.agent_runtime_session_outbox o
        WHERE (o.tenant_id,o.session_id,o.target_kind,o.target_id,o.end_user_id)=(t,s,k,id,u) AND o.status<>'SENT')
 ) protected;
 RETURN reasons;
END $$;

-- Host identity is bigint. Arbitrary SDK TenantId text cannot own these rows; avoid evaluating host bigint RLS for such tenants.
CREATE FUNCTION public.agent_runtime_session_event_host_referenced(t text,s text,eid text)
RETURNS boolean LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE referenced boolean;
BEGIN
 IF t !~ '^[1-9][0-9]{0,18}$' OR (length(t)=19 AND t>'9223372036854775807') THEN RETURN false; END IF;
 SELECT EXISTS(SELECT 1 FROM public.nw_chat_session_outbox a WHERE a.tenant_id::text=t AND a.session_id=s
    AND (a.source_event_id=eid OR a.caused_by_event_id=eid))
 OR EXISTS(SELECT 1 FROM public.nw_chat_session_outbox_sequences a WHERE a.tenant_id::text=t AND a.session_id=s
    AND (a.projection_source_event_id=eid OR a.projection_checkpoint_event_id=eid))
 OR EXISTS(SELECT 1 FROM public.nw_external_invocations a WHERE a.tenant_id::text=t
    AND (a.source_event_id=eid OR a.caused_by_event_id=eid))
 OR EXISTS(SELECT 1 FROM public.nw_knowledge_write_proposals a WHERE a.tenant_id::text=t AND a.source_event_id=eid)
 OR EXISTS(SELECT 1 FROM public.nw_handoff_reply_requests a WHERE a.tenant_id::text=t AND a.session_id=s AND a.source_event_id=eid)
 INTO referenced;
 RETURN referenced;
END $$;

-- Authoritative FK/source closure. Internal event_body_refs are CASCADE ownership, never an independent business source.
-- Terminal/ACK does not release any of these source references; deletion waits for its owning domain to retire it.
-- Only this native Task schema: shield its three non-source string fields in a temporary metadata projection.
-- The original JSON syntax is validated by cast, but no JSON field operator sees its NUL-capable body strings.
-- All sources/maintenance/identity fields remain byte-for-byte in the projection, with no escape replacement.
CREATE FUNCTION public.agent_runtime_session_task_source_fields(snapshot text) RETURNS json
LANGUAGE plpgsql IMMUTABLE SECURITY INVOKER AS $$
DECLARE i integer:=1; j integer; token_end integer; value_end integer; depth integer:=0; state_depth integer;
    copied_from integer:=1; token text; value_token text; isolated text:=''; data json;
BEGIN
 IF snapshot IS NULL THEN RAISE EXCEPTION 'session_history_task_snapshot_invalid'; END IF;
 PERFORM snapshot::json;
 WHILE i<=length(snapshot) LOOP
    IF substring(snapshot FROM i FOR 1)='"' THEN
        token_end:=i+1;
        WHILE token_end<=length(snapshot) LOOP
            IF substring(snapshot FROM token_end FOR 1)=chr(92) THEN token_end:=token_end+2;
            ELSIF substring(snapshot FROM token_end FOR 1)='"' THEN EXIT;
            ELSE token_end:=token_end+1; END IF;
        END LOOP;
        IF token_end>length(snapshot) THEN RAISE EXCEPTION 'session_history_task_snapshot_invalid'; END IF;
        token:=substring(snapshot FROM i FOR token_end-i+1);j:=token_end+1;
        WHILE substring(snapshot FROM j FOR 1) IN(' ',chr(9),chr(10),chr(13)) LOOP j:=j+1; END LOOP;
        IF substring(snapshot FROM j FOR 1)=':' THEN
            j:=j+1;
            WHILE substring(snapshot FROM j FOR 1) IN(' ',chr(9),chr(10),chr(13)) LOOP j:=j+1; END LOOP;
            IF depth=1 AND token='"state"' THEN
                IF substring(snapshot FROM j FOR 1)<>'{' THEN RAISE EXCEPTION 'session_history_task_snapshot_invalid'; END IF;
                state_depth:=2;
            END IF;
            IF (depth=1 AND token IN('"title"','"summary"'))
                OR (depth=2 AND state_depth=2 AND token='"canonicalJson"') THEN
                IF substring(snapshot FROM j FOR 1)<>'"' THEN RAISE EXCEPTION 'session_history_task_snapshot_invalid'; END IF;
                value_end:=j+1;
                WHILE value_end<=length(snapshot) LOOP
                    IF substring(snapshot FROM value_end FOR 1)=chr(92) THEN value_end:=value_end+2;
                    ELSIF substring(snapshot FROM value_end FOR 1)='"' THEN EXIT;
                    ELSE value_end:=value_end+1; END IF;
                END LOOP;
                IF value_end>length(snapshot) THEN RAISE EXCEPTION 'session_history_task_snapshot_invalid'; END IF;
                value_token:=substring(snapshot FROM j FOR value_end-j+1);
                isolated:=isolated||substring(snapshot FROM copied_from FOR j-copied_from)||to_json(value_token)::text;
                copied_from:=value_end+1;i:=value_end+1;CONTINUE;
            END IF;
        END IF;
        i:=token_end+1;CONTINUE;
    ELSIF substring(snapshot FROM i FOR 1) IN('{','[') THEN depth:=depth+1;
    ELSIF substring(snapshot FROM i FOR 1) IN('}',']') THEN
        IF depth=state_depth THEN state_depth:=NULL; END IF;
        depth:=depth-1;
    END IF;
    i:=i+1;
 END LOOP;
 data:=(isolated||substring(snapshot FROM copied_from))::json;
 IF json_typeof(data) IS DISTINCT FROM 'object'
    OR (SELECT count(*) FROM json_object_keys(data))<>12 OR (SELECT count(DISTINCT key) FROM json_object_keys(data) key)<>12
    OR EXISTS(SELECT 1 FROM json_object_keys(data) key WHERE key NOT IN
        ('taskId','tenantId','endUserId','agentId','revision','status','title','summary','state','sources','maintenance','updatedAt'))
    OR json_typeof(data->'title') IS DISTINCT FROM 'string' OR json_typeof(data->'summary') IS DISTINCT FROM 'string'
    OR json_typeof(data->'state') IS DISTINCT FROM 'object'
    OR json_typeof(data#>'{state,schemaId}') IS DISTINCT FROM 'string'
    OR json_typeof(data#>'{state,canonicalJson}') IS DISTINCT FROM 'string'
    THEN RAISE EXCEPTION 'session_history_task_snapshot_invalid'; END IF;
 RETURN data;
END $$;

CREATE FUNCTION public.agent_runtime_session_task_snapshot_referenced(snapshot text,t text,s text,k text,id text,u bigint,eid text,seq bigint)
RETURNS boolean LANGUAGE plpgsql IMMUTABLE SECURITY INVOKER AS $$
DECLARE data json; item json;
BEGIN
 data := public.agent_runtime_session_task_source_fields(snapshot);
 IF json_typeof(data)<>'object' OR json_typeof(data->'sources') IS DISTINCT FROM 'array'
    OR json_typeof(data->'maintenance') IS DISTINCT FROM 'array' THEN RETURN true; END IF;
 FOR item IN SELECT value FROM json_array_elements(data->'sources') LOOP
    IF item#>>'{owner,sessionRef,tenantId,value}' IS NULL OR item#>>'{owner,sessionRef,sessionId}' IS NULL OR item#>>'{owner,target,kind}' IS NULL
        OR item#>>'{owner,target,id}' IS NULL OR item#>>'{owner,endUserId,value}' IS NULL OR item#>>'{eventId,value}' IS NULL
        OR item#>>'{agentId,value}' IS NULL
        THEN RETURN true; END IF;
    IF ROW(item#>>'{owner,sessionRef,tenantId,value}',item#>>'{owner,sessionRef,sessionId}',item#>>'{owner,target,kind}',
        item#>>'{owner,target,id}',item#>>'{owner,endUserId,value}',item#>>'{eventId,value}') =
        ROW(t,s,k,id,u::text,eid) THEN RETURN true; END IF;
 END LOOP;
 FOR item IN SELECT value FROM json_array_elements(data->'maintenance') LOOP
    IF item#>>'{owner,sessionRef,tenantId,value}' IS NULL OR item#>>'{owner,sessionRef,sessionId}' IS NULL
        OR item#>>'{owner,target,kind}' IS NULL OR item#>>'{owner,target,id}' IS NULL
        OR item#>>'{owner,endUserId,value}' IS NULL OR item#>>'{agentId,value}' IS NULL
        OR json_typeof(item->'sourceEventIds') IS DISTINCT FROM 'array'
        OR (item->>'throughSequence') IS NULL OR (item->>'throughSequence') !~ '^(0|[1-9][0-9]*)$'
        THEN RETURN true; END IF;
    IF ROW(item#>>'{owner,sessionRef,tenantId,value}',item#>>'{owner,sessionRef,sessionId}',item#>>'{owner,target,kind}',
        item#>>'{owner,target,id}',item#>>'{owner,endUserId,value}') = ROW(t,s,k,id,u::text)
        AND (EXISTS(SELECT 1 FROM json_array_elements(item->'sourceEventIds') ref WHERE ref->>'value'=eid)
            OR (item->>'throughSequence')::bigint=seq) THEN RETURN true; END IF;
 END LOOP;
 RETURN false;
EXCEPTION WHEN invalid_text_representation OR invalid_parameter_value OR numeric_value_out_of_range OR untranslatable_character OR raise_exception THEN RETURN true;
END $$;

CREATE FUNCTION public.agent_runtime_session_event_referenced(t text,s text,k text,id text,u bigint,eid text)
RETURNS boolean LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE referenced boolean; source_sequence bigint; source_run text;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_history_tenant_required'; END IF;
 SELECT e.session_sequence,e.run_id INTO source_sequence,source_run FROM public.agent_runtime_session_events e
    WHERE ROW(e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id,e.event_id)=ROW(t,s,k,id,u,eid);
 SELECT EXISTS(SELECT 1 FROM public.agent_runtime_session_events a WHERE a.tenant_id=t AND a.caused_by_event_id=eid)
 OR EXISTS(SELECT 1 FROM public.agent_runtime_session_outbox a
    WHERE (a.tenant_id,a.session_id,a.target_kind,a.target_id,a.end_user_id,a.event_id)=(t,s,k,id,u,eid))
 OR EXISTS(SELECT 1 FROM public.agent_runtime_conversation_task_operations a
    WHERE (a.tenant_id,a.session_id,a.target_kind,a.target_id,a.event_id)=(t,s,k,id,eid))
 OR EXISTS(SELECT 1 FROM public.agent_runtime_conversation_tasks a WHERE a.tenant_id=t AND a.end_user_id=u
    AND (a.schema_id<>'skillforge.conversation-task.snapshot.v2'
        OR public.agent_runtime_session_task_snapshot_referenced(a.snapshot,t,s,k,id,u,eid,source_sequence)))
 OR EXISTS(SELECT 1 FROM public.agent_runtime_session_events a WHERE a.tenant_id=t AND a.end_user_id=u
    AND a.payload_schema_id='skillforge.conversation-task.snapshot.v2'
    AND public.agent_runtime_session_task_snapshot_referenced(a.payload_canonical_json,t,s,k,id,u,eid,source_sequence))
 OR EXISTS(SELECT 1 FROM public.agent_runtime_session_tool_projections a
    WHERE a.tenant_id=t AND a.run_id=source_run AND a.program_event_id=eid)
 OR EXISTS(SELECT 1 FROM public.agent_runtime_memory_fact_sources a
    WHERE (a.tenant_id,a.source_session_id,a.source_target_kind,a.source_target_id,a.source_event_id)=(t,s,k,id,eid))
 OR EXISTS(SELECT 1 FROM public.agent_runtime_memory_extraction_jobs a
    WHERE (a.tenant_id,a.source_session_id,a.source_target_kind,a.source_target_id,a.source_event_id)=(t,s,k,id,eid))
 OR EXISTS(SELECT 1 FROM public.agent_runtime_memory_extraction_evidence a
    WHERE (a.tenant_id,a.source_session_id,a.source_target_kind,a.source_target_id,a.source_event_id)=(t,s,k,id,eid))
 OR EXISTS(SELECT 1 FROM public.agent_runtime_run_state_publications a WHERE a.tenant_id=t AND a.event_id=eid)
 OR EXISTS(SELECT 1 FROM public.agent_runtime_execution_state_publications a WHERE a.tenant_id=t AND a.event_id=eid)
 OR EXISTS(SELECT 1 FROM public.agent_runtime_action_state_publications a WHERE a.tenant_id=t AND a.event_id=eid)
 OR EXISTS(SELECT 1 FROM public.agent_runtime_event_deliveries a WHERE a.tenant_id=t AND a.event_id=eid)
 OR EXISTS(SELECT 1 FROM public.agent_runtime_event_conflict_audits a WHERE a.tenant_id=t AND a.event_id=eid)
 OR EXISTS(SELECT 1 FROM public.agent_runtime_delegations a WHERE a.tenant_id=t AND a.parent_caused_by_event_id=eid)
 OR EXISTS(SELECT 1 FROM public.agent_runtime_delegation_waits a WHERE a.tenant_id=t AND a.parent_caused_by_event_id=eid)
 OR public.agent_runtime_session_event_host_referenced(t,s,eid)
 INTO referenced;
 RETURN referenced;
END $$;

-- Non-FK source writers hold the original event KEY SHARE until their transaction commits.
-- A retired logical identity cannot acquire a new source reference after its body has been released.
CREATE FUNCTION public.agent_runtime_session_source_reference_guard() RETURNS trigger
LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE data jsonb := to_jsonb(NEW); column_name text; event_key text; tenant_key text := data->>'tenant_id';
BEGIN
 IF tenant_key IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_history_tenant_required'; END IF;
 FOREACH column_name IN ARRAY TG_ARGV LOOP
    event_key := data->>column_name;
    IF event_key IS NULL THEN CONTINUE; END IF;
    PERFORM 1 FROM public.agent_runtime_session_events e WHERE e.tenant_id=tenant_key AND e.event_id=event_key
        ORDER BY e.target_kind,e.target_id,e.session_id FOR KEY SHARE;
    IF EXISTS(SELECT 1 FROM public.agent_runtime_session_event_tombstones tomb
        WHERE tomb.tenant_id=tenant_key AND tomb.event_id=event_key) THEN
        RAISE EXCEPTION 'session_history_retired_source_reference';
    END IF;
 END LOOP;
 RETURN NEW;
END $$;

CREATE TRIGGER session_history_causal_reference BEFORE INSERT ON public.agent_runtime_session_events
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_source_reference_guard('caused_by_event_id');
CREATE TRIGGER session_history_run_publication_reference BEFORE INSERT ON public.agent_runtime_run_state_publications
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_source_reference_guard('event_id');
CREATE TRIGGER session_history_execution_publication_reference BEFORE INSERT ON public.agent_runtime_execution_state_publications
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_source_reference_guard('event_id');
CREATE TRIGGER session_history_action_publication_reference BEFORE INSERT ON public.agent_runtime_action_state_publications
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_source_reference_guard('event_id');
CREATE TRIGGER session_history_delivery_reference BEFORE INSERT ON public.agent_runtime_event_deliveries
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_source_reference_guard('event_id');
CREATE TRIGGER session_history_conflict_reference BEFORE INSERT ON public.agent_runtime_event_conflict_audits
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_source_reference_guard('event_id');
CREATE TRIGGER session_history_delegation_reference BEFORE INSERT ON public.agent_runtime_delegations
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_source_reference_guard('parent_caused_by_event_id');
CREATE TRIGGER session_history_wait_reference BEFORE INSERT ON public.agent_runtime_delegation_waits
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_source_reference_guard('parent_caused_by_event_id');
CREATE TRIGGER session_history_chat_outbox_reference BEFORE INSERT OR UPDATE OF source_event_id,caused_by_event_id ON public.nw_chat_session_outbox
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_source_reference_guard('source_event_id','caused_by_event_id');
CREATE TRIGGER session_history_chat_projection_reference BEFORE INSERT OR UPDATE OF projection_source_event_id,projection_checkpoint_event_id ON public.nw_chat_session_outbox_sequences
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_source_reference_guard('projection_source_event_id','projection_checkpoint_event_id');
CREATE TRIGGER session_history_invocation_reference BEFORE INSERT OR UPDATE OF source_event_id,caused_by_event_id ON public.nw_external_invocations
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_source_reference_guard('source_event_id','caused_by_event_id');
CREATE TRIGGER session_history_knowledge_proposal_reference BEFORE INSERT OR UPDATE OF source_event_id ON public.nw_knowledge_write_proposals
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_source_reference_guard('source_event_id');
CREATE TRIGGER session_history_handoff_reference BEFORE INSERT OR UPDATE OF source_event_id ON public.nw_handoff_reply_requests
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_source_reference_guard('source_event_id');

-- Complete task snapshots are actual persistent consumers, including old task.changed facts after the head advances.
CREATE FUNCTION public.agent_runtime_session_task_reference_guard() RETURNS trigger
LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE source json; item json; reference json; content text; tenant_key text;
BEGIN
 IF TG_TABLE_NAME='agent_runtime_session_events' THEN
    IF NEW.payload_schema_id<>'skillforge.conversation-task.snapshot.v2' THEN RETURN NEW; END IF;
    content:=NEW.payload_canonical_json; tenant_key:=NEW.tenant_id;
 ELSE
    IF NEW.schema_id<>'skillforge.conversation-task.snapshot.v2' THEN RAISE EXCEPTION 'session_history_task_snapshot_unknown'; END IF;
    content:=NEW.snapshot; tenant_key:=NEW.tenant_id;
 END IF;
 IF tenant_key IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_history_tenant_required'; END IF;
 source:=public.agent_runtime_session_task_source_fields(content);
 IF json_typeof(source->'sources') IS DISTINCT FROM 'array' OR json_typeof(source->'maintenance') IS DISTINCT FROM 'array'
    OR json_array_length(source->'sources') NOT BETWEEN 1 AND 256 OR json_array_length(source->'maintenance')>256
    THEN RAISE EXCEPTION 'session_history_task_snapshot_invalid'; END IF;
 FOR item IN SELECT value FROM json_array_elements(source->'sources') UNION ALL SELECT value FROM json_array_elements(source->'maintenance') LOOP
    IF item#>>'{owner,sessionRef,tenantId,value}' IS DISTINCT FROM tenant_key
        OR item#>>'{owner,sessionRef,sessionId}' IS NULL OR item#>>'{owner,target,kind}' IS NULL
        OR item#>>'{owner,target,id}' IS NULL OR item#>>'{owner,endUserId,value}' IS NULL OR item#>>'{agentId,value}' IS NULL
        THEN RAISE EXCEPTION 'session_history_task_source_invalid'; END IF;
    FOR reference IN SELECT item->'eventId' WHERE item->'eventId' IS NOT NULL
        UNION ALL SELECT value FROM json_array_elements(COALESCE(item->'sourceEventIds','[]'::json)) LOOP
        IF reference->>'value' IS NULL THEN RAISE EXCEPTION 'session_history_task_source_invalid'; END IF;
        PERFORM 1 FROM public.agent_runtime_session_events e
            WHERE ROW(e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id::text,e.event_id)=
                ROW(tenant_key,item#>>'{owner,sessionRef,sessionId}',item#>>'{owner,target,kind}',
                    item#>>'{owner,target,id}',item#>>'{owner,endUserId,value}',reference->>'value')
                AND e.agent_id=item#>>'{agentId,value}' FOR KEY SHARE;
        IF NOT FOUND THEN RAISE EXCEPTION 'session_history_task_source_missing_or_retired'; END IF;
    END LOOP;
    IF item->'sourceEventIds' IS NOT NULL THEN
        IF (item->>'throughSequence') IS NULL OR (item->>'throughSequence') !~ '^(0|[1-9][0-9]*)$'
            THEN RAISE EXCEPTION 'session_history_task_source_invalid'; END IF;
        IF (item->>'throughSequence')::bigint>0 THEN
            PERFORM 1 FROM public.agent_runtime_session_events e
                WHERE ROW(e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id::text,e.session_sequence)=
                    ROW(tenant_key,item#>>'{owner,sessionRef,sessionId}',item#>>'{owner,target,kind}',
                        item#>>'{owner,target,id}',item#>>'{owner,endUserId,value}',(item->>'throughSequence')::bigint) FOR KEY SHARE;
            IF NOT FOUND THEN RAISE EXCEPTION 'session_history_task_cursor_missing_or_retired'; END IF;
        END IF;
    END IF;
 END LOOP;
 RETURN NEW;
END $$;
CREATE TRIGGER session_history_task_event_reference BEFORE INSERT ON public.agent_runtime_session_events
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_task_reference_guard();
CREATE TRIGGER session_history_task_projection_reference BEFORE INSERT OR UPDATE OF snapshot,schema_id ON public.agent_runtime_conversation_tasks
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_task_reference_guard();

-- Validate Java canonical string lexical form without extracting NUL into PostgreSQL text.
CREATE FUNCTION public.agent_runtime_session_history_string(raw text) RETURNS text
LANGUAGE plpgsql IMMUTABLE SECURITY INVOKER AS $$
DECLARE i integer := 2; ch text; escaped text; hex text; code integer;
BEGIN
 IF raw IS NULL OR length(raw)<2 OR left(raw,1)<>'"' OR right(raw,1)<>'"'
    THEN RAISE EXCEPTION 'session_history_payload_invalid'; END IF;
 WHILE i<length(raw) LOOP
    ch := substring(raw FROM i FOR 1);
    IF ch=chr(92) THEN
        escaped := substring(raw FROM i+1 FOR 1);
        IF escaped='u' THEN
            hex := substring(raw FROM i+2 FOR 4);
            IF hex !~ '^[0-9a-f]{4}$' THEN RAISE EXCEPTION 'session_history_payload_invalid'; END IF;
            code := ('x'||hex)::bit(16)::integer;
            IF code>31 OR code IN(8,9,10,12,13) THEN RAISE EXCEPTION 'session_history_payload_invalid'; END IF;
            i := i+6;
        ELSIF escaped IN('"',chr(92),'b','f','n','r','t') THEN i := i+2;
        ELSE RAISE EXCEPTION 'session_history_payload_invalid'; END IF;
    ELSE
        IF ascii(ch)<32 OR ch='"' THEN RAISE EXCEPTION 'session_history_payload_invalid'; END IF;
        i := i+1;
    END IF;
 END LOOP;
 IF i<>length(raw) THEN RAISE EXCEPTION 'session_history_payload_invalid'; END IF;
 RETURN raw;
END $$;

-- Scan one exact canonical string token without asking PostgreSQL JSON to expand Unicode escapes.
-- Escaped quotes/backslashes are consumed as pairs; literal "\\u0000" cannot masquerade as NUL "\u0000".
CREATE FUNCTION public.agent_runtime_session_history_string_token(raw text,start_at integer) RETURNS text
LANGUAGE plpgsql IMMUTABLE SECURITY INVOKER AS $$
DECLARE i integer:=start_at+1; ch text; lexeme text;
BEGIN
 IF start_at<1 OR substring(raw FROM start_at FOR 1)<>'"' THEN RAISE EXCEPTION 'session_history_payload_invalid'; END IF;
 WHILE i<=length(raw) LOOP
    ch:=substring(raw FROM i FOR 1);
    IF ch='"' THEN
        lexeme:=substring(raw FROM start_at FOR i-start_at+1);
        RETURN public.agent_runtime_session_history_string(lexeme);
    ELSIF ch=chr(92) THEN i:=i+2;
    ELSE i:=i+1;
    END IF;
 END LOOP;
 RAISE EXCEPTION 'session_history_payload_invalid';
END $$;

-- The native schemas have fixed ordered string-only fields. Enforce the WHOLE original lexical object first.
-- Only content is transported as a string holding its original quoted JSON lexeme, via to_json(lexeme).
-- PostgreSQL may then inspect metadata safely; ->>'content' returns the exact original escaped token, not decoded body.
-- No global replacement/sentinel, duplicate/extra keys, whitespace normalization, or reconstructed business body.
CREATE FUNCTION public.agent_runtime_session_history_native_fields(raw text,names text[]) RETURNS json
LANGUAGE plpgsql IMMUTABLE SECURITY INVOKER AS $$
DECLARE cursor_position integer:=2; ordinal integer:=0; name text; prefix text; lexeme text; metadata text:='{';
BEGIN
 IF raw IS NULL OR left(raw,1)<>'{' OR names IS NULL OR cardinality(names)=0
    THEN RAISE EXCEPTION 'session_history_payload_invalid'; END IF;
 FOREACH name IN ARRAY names LOOP
    prefix:=CASE WHEN ordinal=0 THEN '' ELSE ',' END||'"'||name||'":';
    IF substring(raw FROM cursor_position FOR length(prefix)) IS DISTINCT FROM prefix
        THEN RAISE EXCEPTION 'session_history_payload_invalid'; END IF;
    cursor_position:=cursor_position+length(prefix);
    lexeme:=public.agent_runtime_session_history_string_token(raw,cursor_position);
    cursor_position:=cursor_position+length(lexeme);
    metadata:=metadata||prefix||CASE WHEN name='content' THEN to_json(lexeme)::text ELSE lexeme END;
    ordinal:=ordinal+1;
 END LOOP;
 IF substring(raw FROM cursor_position) IS DISTINCT FROM '}' THEN RAISE EXCEPTION 'session_history_payload_invalid'; END IF;
 RETURN (metadata||'}')::json;
END $$;

CREATE FUNCTION public.agent_runtime_session_history_snapshot(event public.agent_runtime_session_events)
RETURNS json LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE raw text; data json; name text; operation text; attempt text; version text; expected text;
BEGIN
 IF event.event_type<>'agent.model-answer' OR event.event_version<>'2'
    OR event.payload_schema_id<>'skillforge.runtime.agent.model-answer.v2' OR event.run_id IS NULL OR event.action_id IS NULL
    THEN RAISE EXCEPTION 'session_history_answer_source_invalid'; END IF;
 raw := public.agent_runtime_session_event_payload(event.tenant_id,event.session_id,event.target_kind,event.target_id,
    event.end_user_id,event.run_id,event.event_id,event.event_type,event.payload_schema_id,event.payload_canonical_json);
 data := public.agent_runtime_session_history_native_fields(raw,
    ARRAY['attemptId','content','messageId','messageVersion','operationKey','phase','sourceSequenceThrough']);
 operation := data->>'operationKey'; attempt := data->>'attemptId'; version := data->>'messageVersion';
 IF btrim(operation)='' OR btrim(attempt)='' OR version !~ '^[1-9][0-9]*$'
    OR (data->>'sourceSequenceThrough') !~ '^[1-9][0-9]*$' OR (data->>'phase') NOT IN('pending','commentary','final')
    OR event.action_id IS DISTINCT FROM 'model-action:'||operation
    OR data->>'messageId' IS DISTINCT FROM 'final-answer:'||encode(sha256(convert_to(
        'skillforge.runtime.final-answer-identity.v1'||chr(10)||'['||to_json(operation)::text||','||to_json(attempt)::text||']','UTF8')),'hex')
    OR event.event_id IS DISTINCT FROM 'model-answer:'||encode(sha256(convert_to(
        'skillforge.runtime.agent.model-answer-batch.v1'||chr(10)||'{"attemptId":'||to_json(attempt)::text||
        ',"batchOrdinal":'||to_json(version)::text||',"operationKey":'||to_json(operation)::text||'}','UTF8')),'hex')
    THEN RAISE EXCEPTION 'session_history_answer_source_invalid'; END IF;
 PERFORM version::bigint,(data->>'sourceSequenceThrough')::bigint;
 expected := '{"attemptId":'||to_json(attempt)::text||',"content":'||(data->>'content')||
    ',"messageId":'||to_json(data->>'messageId')::text||',"messageVersion":'||to_json(version)::text||
    ',"operationKey":'||to_json(operation)::text||',"phase":'||to_json(data->>'phase')::text||
    ',"sourceSequenceThrough":'||to_json(data->>'sourceSequenceThrough')::text||'}';
 IF raw IS DISTINCT FROM expected OR (data->>'content')='""' THEN RAISE EXCEPTION 'session_history_answer_source_invalid'; END IF;
 RETURN data;
END $$;

-- Ordinary display deletion requires actual first/middle/last/adoption, original source identity and complete prefixes.
CREATE FUNCTION public.agent_runtime_session_history_answer_guard(event public.agent_runtime_session_events) RETURNS void
LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE first public.agent_runtime_session_events; last public.agent_runtime_session_events; adopted public.agent_runtime_session_events;
    a json; b json; c json; d json; raw text; operation text; attempt text; name text;
BEGIN
 b := public.agent_runtime_session_history_snapshot(event);
 SELECT e.* INTO first FROM public.agent_runtime_session_events e
    WHERE (e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id,e.run_id) =
        (event.tenant_id,event.session_id,event.target_kind,event.target_id,event.end_user_id,event.run_id)
      AND e.event_type='agent.model-answer' AND substring(e.payload_canonical_json FROM '"messageId":"([^"]+)"')=b->>'messageId'
    ORDER BY e.session_sequence LIMIT 1;
 SELECT e.* INTO last FROM public.agent_runtime_session_events e
    WHERE (e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id,e.run_id) =
        (event.tenant_id,event.session_id,event.target_kind,event.target_id,event.end_user_id,event.run_id)
      AND e.event_type='agent.model-answer' AND substring(e.payload_canonical_json FROM '"messageId":"([^"]+)"')=b->>'messageId'
    ORDER BY e.session_sequence DESC LIMIT 1;
 SELECT e.* INTO adopted FROM public.agent_runtime_session_events e
    WHERE (e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id,e.run_id) =
        (event.tenant_id,event.session_id,event.target_kind,event.target_id,event.end_user_id,event.run_id)
      AND e.event_type='agent.model-answer-adopted' AND substring(e.payload_canonical_json FROM '"messageId":"([^"]+)"')=b->>'messageId'
    ORDER BY e.session_sequence LIMIT 1;
 IF first.event_id IS NULL OR last.event_id IS NULL OR adopted.event_id IS NULL
    OR NOT (first.session_sequence<event.session_sequence AND event.session_sequence<last.session_sequence
        AND last.session_sequence<adopted.session_sequence) THEN RAISE EXCEPTION 'session_history_answer_endpoints_required'; END IF;
 IF ROW(first.agent_id,first.input_id,first.run_id,first.execution_id,first.action_id,first.delegation_id,first.trace_id,first.span_id,first.root_input_id,first.caused_by_event_id)
    IS DISTINCT FROM ROW(event.agent_id,event.input_id,event.run_id,event.execution_id,event.action_id,event.delegation_id,event.trace_id,event.span_id,event.root_input_id,event.caused_by_event_id)
    OR ROW(last.agent_id,last.input_id,last.run_id,last.execution_id,last.action_id,last.delegation_id,last.trace_id,last.span_id,last.root_input_id,last.caused_by_event_id)
    IS DISTINCT FROM ROW(event.agent_id,event.input_id,event.run_id,event.execution_id,event.action_id,event.delegation_id,event.trace_id,event.span_id,event.root_input_id,event.caused_by_event_id)
    OR ROW(adopted.agent_id,adopted.input_id,adopted.run_id,adopted.execution_id,adopted.action_id,adopted.delegation_id,adopted.trace_id,adopted.span_id,adopted.root_input_id,adopted.caused_by_event_id)
    IS DISTINCT FROM ROW(event.agent_id,event.input_id,event.run_id,event.execution_id,event.action_id,event.delegation_id,event.trace_id,event.span_id,event.root_input_id,event.caused_by_event_id)
    THEN RAISE EXCEPTION 'session_history_answer_source_mismatch'; END IF;
 a := public.agent_runtime_session_history_snapshot(first); c := public.agent_runtime_session_history_snapshot(last);
 raw := public.agent_runtime_session_event_payload(adopted.tenant_id,adopted.session_id,adopted.target_kind,adopted.target_id,
    adopted.end_user_id,adopted.run_id,adopted.event_id,adopted.event_type,adopted.payload_schema_id,adopted.payload_canonical_json);
 d := public.agent_runtime_session_history_native_fields(raw,
    ARRAY['adoptedFactRef','attemptId','content','messageId','modelResultEventId','operationKey','phase']);
 IF adopted.event_version<>'2' OR adopted.payload_schema_id<>'skillforge.runtime.agent.model-answer-adopted.v2'
    THEN RAISE EXCEPTION 'session_history_adoption_source_invalid'; END IF;
 operation := d->>'operationKey'; attempt := d->>'attemptId';
 IF btrim(d->>'adoptedFactRef')='' OR btrim(d->>'modelResultEventId')='' OR (d->>'phase') NOT IN('commentary','final')
    OR adopted.event_id IS DISTINCT FROM 'model-answer-adopted:'||encode(sha256(convert_to(
        'skillforge.runtime.agent.model-answer-adopted.v2'||chr(10)||'{"attemptId":'||to_json(attempt)::text||
        ',"operationKey":'||to_json(operation)::text||'}','UTF8')),'hex')
    OR a->>'messageId' IS DISTINCT FROM b->>'messageId' OR b->>'messageId' IS DISTINCT FROM c->>'messageId'
    OR c->>'messageId' IS DISTINCT FROM d->>'messageId'
    OR a->>'operationKey' IS DISTINCT FROM operation OR b->>'operationKey' IS DISTINCT FROM operation OR c->>'operationKey' IS DISTINCT FROM operation
    OR a->>'attemptId' IS DISTINCT FROM attempt OR b->>'attemptId' IS DISTINCT FROM attempt OR c->>'attemptId' IS DISTINCT FROM attempt
    OR NOT ((a->>'messageVersion')::bigint<(b->>'messageVersion')::bigint AND (b->>'messageVersion')::bigint<(c->>'messageVersion')::bigint)
    OR (a->>'sourceSequenceThrough')::bigint>(b->>'sourceSequenceThrough')::bigint OR (b->>'sourceSequenceThrough')::bigint>(c->>'sourceSequenceThrough')::bigint
    OR NOT starts_with(left((b->>'content'),length((b->>'content'))-1),left((a->>'content'),length((a->>'content'))-1))
    OR NOT starts_with(left((c->>'content'),length((c->>'content'))-1),left((b->>'content'),length((b->>'content'))-1))
    OR NOT starts_with(left((d->>'content'),length((d->>'content'))-1),left((c->>'content'),length((c->>'content'))-1))
    OR (a->>'phase'<>'pending' AND a->>'phase' IS DISTINCT FROM b->>'phase')
    OR (b->>'phase'<>'pending' AND b->>'phase' IS DISTINCT FROM c->>'phase')
    OR (c->>'phase'<>'pending' AND c->>'phase' IS DISTINCT FROM d->>'phase')
    THEN RAISE EXCEPTION 'session_history_adoption_source_mismatch'; END IF;
 IF raw IS DISTINCT FROM '{"adoptedFactRef":'||to_json(d->>'adoptedFactRef')::text||',"attemptId":'||to_json(attempt)::text||
    ',"content":'||(d->>'content')||',"messageId":'||to_json(d->>'messageId')::text||
    ',"modelResultEventId":'||to_json(d->>'modelResultEventId')::text||',"operationKey":'||to_json(operation)::text||
    ',"phase":'||to_json(d->>'phase')::text||'}' THEN RAISE EXCEPTION 'session_history_adoption_source_invalid'; END IF;
END $$;

CREATE FUNCTION public.agent_runtime_session_history_closed_guard(event public.agent_runtime_session_events) RETURNS void
LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE session public.agent_runtime_sessions; retired public.agent_runtime_session_history_retirements; root bigint; threshold numeric;
BEGIN
 IF event.tenant_id IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_history_tenant_required'; END IF;
 -- Direct DELETE already owns the event row: NOWAIT prevents inversion against canonical root->Session->event maintenance.
 SELECT end_user_id INTO root FROM public.agent_runtime_session_owners o
    WHERE (o.tenant_id,o.session_id,o.target_kind,o.target_id,o.end_user_id)=
        (event.tenant_id,event.session_id,event.target_kind,event.target_id,event.end_user_id) FOR NO KEY UPDATE NOWAIT;
 IF root IS NULL THEN RAISE EXCEPTION 'session_history_owner_required'; END IF;
 SELECT s.* INTO session FROM public.agent_runtime_sessions s
    WHERE (s.tenant_id,s.session_id,s.target_kind,s.target_id,s.end_user_id)=
        (event.tenant_id,event.session_id,event.target_kind,event.target_id,event.end_user_id) FOR UPDATE NOWAIT;
 SELECT r.* INTO retired FROM public.agent_runtime_session_history_retirements r
    WHERE (r.tenant_id,r.session_id,r.target_kind,r.target_id,r.end_user_id)=
        (event.tenant_id,event.session_id,event.target_kind,event.target_id,event.end_user_id) FOR UPDATE NOWAIT;
 threshold := extract(epoch FROM clock_timestamp()-interval '30 days');
 IF session.status IS DISTINCT FROM 'CLOSED' OR retired.source_session_revision IS NULL
    OR session.session_revision IS DISTINCT FROM retired.source_session_revision
    OR ROW(session.updated_epoch_second,session.updated_nano) IS DISTINCT FROM ROW(retired.closed_epoch_second,retired.closed_nano)
    OR session.updated_epoch_second::numeric+session.updated_nano::numeric/1000000000 > threshold
    OR cardinality(public.agent_runtime_session_history_owner_protection(event.tenant_id,event.session_id,event.target_kind,event.target_id,event.end_user_id))>0
    THEN RAISE EXCEPTION 'session_history_closed_source_or_protection_required'; END IF;
 IF public.agent_runtime_session_event_referenced(event.tenant_id,event.session_id,event.target_kind,event.target_id,event.end_user_id,event.event_id)
    THEN RAISE EXCEPTION 'session_history_event_reference_protected'; END IF;
END $$;

-- Validate every exact source field through the canonical decoder and SHA, not only 64-hex shape.
CREATE FUNCTION public.agent_runtime_session_history_tombstone_guard() RETURNS trigger
LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE source public.agent_runtime_session_events;
BEGIN
 SELECT e.* INTO source FROM public.agent_runtime_session_events e
    WHERE (e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id,e.event_id,e.session_sequence)=
        (NEW.tenant_id,NEW.session_id,NEW.target_kind,NEW.target_id,NEW.end_user_id,NEW.event_id,NEW.session_sequence) FOR UPDATE NOWAIT;
 IF source.event_id IS NULL OR source.event_type NOT IN('agent.model-answer','agent.model-reasoning','agent.runtime-diagnostic')
    THEN RAISE EXCEPTION 'session_history_tombstone_source_required'; END IF;
 PERFORM public.agent_runtime_session_history_closed_guard(source);
 IF NEW.event_digest IS DISTINCT FROM public.agent_runtime_session_event_digest(source)
    THEN RAISE EXCEPTION 'session_history_tombstone_digest_mismatch'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER session_history_tombstone_source BEFORE INSERT ON public.agent_runtime_session_event_tombstones
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_history_tombstone_guard();
CREATE TRIGGER session_history_tombstone_delete_forbidden BEFORE DELETE ON public.agent_runtime_session_event_tombstones
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();

CREATE OR REPLACE FUNCTION public.reject_agent_runtime_session_event_mutation()
RETURNS trigger LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE canonical text; data json; name text; operation text; expected text;
BEGIN
 IF TG_OP<>'DELETE' THEN RAISE EXCEPTION 'agent runtime session event is append-only'; END IF;
 IF OLD.event_type NOT IN('agent.model-answer','agent.model-reasoning','agent.runtime-diagnostic')
    THEN RAISE EXCEPTION 'session_history_event_type_forbidden'; END IF;
 PERFORM public.agent_runtime_session_history_closed_guard(OLD);
 IF NOT EXISTS(SELECT 1 FROM public.agent_runtime_session_event_tombstones t
    WHERE (t.tenant_id,t.session_id,t.target_kind,t.target_id,t.end_user_id,t.event_id,t.session_sequence)=
        (OLD.tenant_id,OLD.session_id,OLD.target_kind,OLD.target_id,OLD.end_user_id,OLD.event_id,OLD.session_sequence)
      AND t.event_digest=public.agent_runtime_session_event_digest(OLD)) THEN RAISE EXCEPTION 'session_history_verified_tombstone_required'; END IF;
 canonical := public.agent_runtime_session_event_payload(OLD.tenant_id,OLD.session_id,OLD.target_kind,OLD.target_id,
    OLD.end_user_id,OLD.run_id,OLD.event_id,OLD.event_type,OLD.payload_schema_id,OLD.payload_canonical_json);
 IF OLD.event_type='agent.model-answer' THEN
    PERFORM public.agent_runtime_session_history_answer_guard(OLD);
 ELSIF OLD.event_type='agent.model-reasoning' THEN
    data:=public.agent_runtime_session_history_native_fields(canonical,
        ARRAY['attemptId','batchOrdinal','content','sourceSequenceFrom','sourceSequenceThrough']);
    IF OLD.event_version<>'1' OR OLD.payload_schema_id<>'skillforge.runtime.agent.model-reasoning.v2'
        OR OLD.run_id IS NULL OR OLD.action_id IS NULL OR NOT starts_with(OLD.action_id,'model-action:')
        THEN RAISE EXCEPTION 'session_history_reasoning_source_invalid'; END IF;
    IF btrim(data->>'attemptId')='' OR (data->>'content')='""' OR (data->>'batchOrdinal') !~ '^[1-9][0-9]*$'
        OR (data->>'sourceSequenceFrom') !~ '^[1-9][0-9]*$' OR (data->>'sourceSequenceThrough') !~ '^[1-9][0-9]*$'
        OR (data->>'sourceSequenceFrom')::bigint>(data->>'sourceSequenceThrough')::bigint
        THEN RAISE EXCEPTION 'session_history_reasoning_source_invalid'; END IF;
    operation := substring(OLD.action_id FROM length('model-action:')+1);
    expected := 'model-reasoning:'||encode(sha256(convert_to(
        'skillforge.runtime.agent.model-reasoning-batch-identity.v1'||chr(10)||'{"attemptId":'||to_json(data->>'attemptId')::text||
        ',"batchOrdinal":'||to_json(data->>'batchOrdinal')::text||',"operationKey":'||to_json(operation)::text||'}','UTF8')),'hex');
    IF OLD.event_id IS DISTINCT FROM expected OR canonical IS DISTINCT FROM
        '{"attemptId":'||to_json(data->>'attemptId')::text||',"batchOrdinal":'||to_json(data->>'batchOrdinal')::text||
        ',"content":'||(data->>'content')||',"sourceSequenceFrom":'||to_json(data->>'sourceSequenceFrom')::text||
        ',"sourceSequenceThrough":'||to_json(data->>'sourceSequenceThrough')::text||'}'
        THEN RAISE EXCEPTION 'session_history_reasoning_source_invalid'; END IF;
 ELSE
    data:=canonical::json;
    IF OLD.event_version<>'1' OR OLD.payload_schema_id<>'skillforge.runtime.agent.runtime-diagnostic.v1'
        OR OLD.run_id IS NULL OR OLD.execution_id IS NOT NULL OR OLD.action_id IS NOT NULL OR OLD.delegation_id IS NOT NULL
        OR NOT starts_with(OLD.event_id,'runtime-diagnostic:') OR json_typeof(data)<>'object'
        OR (SELECT count(*) FROM json_object_keys(data))<>3
        OR EXISTS(SELECT 1 FROM json_object_keys(data) key WHERE key NOT IN('kind','metrics','operationKey'))
        OR json_typeof(data->'metrics')<>'object'
        OR OLD.occurred_at_epoch_second::numeric+OLD.occurred_at_nano::numeric/1000000000 > extract(epoch FROM clock_timestamp()-interval '30 days')
        THEN RAISE EXCEPTION 'session_history_diagnostic_source_invalid'; END IF;
    PERFORM public.agent_runtime_session_history_string((data->'kind')::text),public.agent_runtime_session_history_string((data->'operationKey')::text);
    IF btrim(data->>'kind')='' OR btrim(data->>'operationKey')='' OR OLD.event_fingerprint IS DISTINCT FROM
        encode(sha256(convert_to(OLD.payload_schema_id||chr(10)||canonical,'UTF8')),'hex')
        OR canonical IS DISTINCT FROM '{"kind":'||to_json(data->>'kind')::text||',"metrics":'||(data->'metrics')::text||
        ',"operationKey":'||to_json(data->>'operationKey')::text||'}'
        THEN RAISE EXCEPTION 'session_history_diagnostic_source_invalid'; END IF;
 END IF;
 RETURN OLD;
END $$;
COMMENT ON FUNCTION public.reject_agent_runtime_session_event_mutation IS 'UPDATE永拒；DELETE逐项核真实CLOSED满批准30日、原修订/纳秒关闭源、完整owner/event引用闭包、canonical恢复及全Append+seq v2墓碑摘要，首/末/adoption保留，诊断独立严格形状与age';

-- Independent 25-field/298-byte golden also asserted by SessionHistoryTombstoneTest; this is a migration-time codec assertion.
DO $golden$
DECLARE source public.agent_runtime_session_events;
BEGIN
 source := jsonb_populate_record(NULL::public.agent_runtime_session_events,jsonb_build_object(
    'tenant_id','t','session_id','s','target_kind','AGENT','target_id','a','end_user_id',1,'agent_id','a','input_id','input',
    'run_id','run','execution_id','execution','action_id','action','delegation_id','delegation','event_id','event',
    'event_type','agent.model-reasoning','event_version','1','event_fingerprint','fingerprint','trace_id','trace','span_id','span',
    'root_input_id','root','caused_by_event_id','cause','payload_schema_id','reasoning.v1','payload_canonical_json','{"text":"原文😀"}',
    'occurred_at_epoch_second',1780000000::bigint,'occurred_at_nano',123456789,'session_sequence',17));
 IF public.agent_runtime_session_event_digest(source)<>'974ab814c641ec95106623832b9bc514e7c290a41475937e131c8e540250f4d1'
    THEN RAISE EXCEPTION 'session_history_digest_v2_golden_failed'; END IF;
END $golden$;
