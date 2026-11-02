-- 遗忘来源抑制跨任务、提取器版本及候选ID持续有效；本人恢复后也保留原不可变遗忘依据。
CREATE OR REPLACE FUNCTION public.agent_runtime_memory_history_unprotected(
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
        AND candidate.revision<>candidate.content_revision AND candidate.status<>'FORGOTTEN' AND current.status='ARCHIVED'
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
