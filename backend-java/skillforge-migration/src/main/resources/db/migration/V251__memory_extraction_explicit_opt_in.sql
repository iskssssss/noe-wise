-- 普通入站消息默认不调用记忆提取模型；明确候选仍使用原持久登记与治理链路。
CREATE OR REPLACE FUNCTION public.enqueue_agent_runtime_memory_extraction()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    candidate jsonb;
    memory_type text;
    canonical_key text;
BEGIN
    IF NEW.event_type NOT IN ('inbound.message','agent.tool-result','agent.workflow-result') THEN
        RETURN NEW;
    END IF;
    candidate := COALESCE(
        (NEW.payload_canonical_json::jsonb #> '{metadata,memoryCandidate}'),
        (NEW.payload_canonical_json::jsonb -> 'memoryCandidate'));
    IF candidate IS NOT NULL AND jsonb_typeof(candidate) = 'object' THEN
        memory_type := COALESCE(NULLIF(btrim(candidate ->> 'type'), ''), 'preference');
        canonical_key := md5(COALESCE(candidate ->> 'subject', '') || E'\n'
            || COALESCE(candidate ->> 'predicate', '') || E'\n'
            || COALESCE((candidate -> 'object')::text, 'null'));
        INSERT INTO public.agent_runtime_memory_extraction_jobs(
            tenant_id, source_event_id, extractor_version, memory_type,
            canonical_fact_key, agent_id, end_user_id, source_session_id,
            source_target_kind, source_target_id, source_execution_id,
            source_event_type, candidate_data)
        VALUES (NEW.tenant_id, NEW.event_id, 'memory-extractor-v1', memory_type,
            canonical_key, NEW.agent_id, NEW.end_user_id, NEW.session_id,
            NEW.target_kind, NEW.target_id, NEW.execution_id, NEW.event_type, candidate)
        ON CONFLICT DO NOTHING;
        RETURN NEW;
    END IF;
    IF NEW.event_type <> 'inbound.message' OR NOT EXISTS (
        SELECT 1 FROM public.nw_agents a
         WHERE a.tenant_id::text=NEW.tenant_id AND a.id::text=NEW.agent_id
           AND a.status='published'
           AND a.config #> '{memory,automatic_extraction}' = 'true'::jsonb
    ) THEN
        RETURN NEW;
    END IF;
    INSERT INTO public.agent_runtime_memory_extraction_jobs(
        tenant_id, source_event_id, extractor_version, memory_type,
        canonical_fact_key, agent_id, end_user_id, source_session_id,
        source_target_kind, source_target_id, source_execution_id,
        source_event_type, candidate_data)
    VALUES (NEW.tenant_id, NEW.event_id, 'memory-extractor-v1', 'inbound-hydrate',
        'source', NEW.agent_id, NEW.end_user_id, NEW.session_id,
        NEW.target_kind, NEW.target_id, NEW.execution_id, NEW.event_type, '{}'::jsonb)
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION public.enqueue_agent_runtime_memory_extraction() IS
    '显式memoryCandidate按原规则登记；普通入站仅在所属已发布Agent config.memory.automatic_extraction为JSON布尔true时自动登记，存量任务由Worker再次检查';
