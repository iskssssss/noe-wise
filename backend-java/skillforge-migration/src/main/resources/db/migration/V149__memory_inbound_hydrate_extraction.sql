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
            source_execution_id, source_event_type, candidate_data)
        VALUES (NEW.tenant_id, NEW.event_id, 'memory-extractor-v1', memory_type,
            canonical_key, NEW.agent_id, NEW.end_user_id, NEW.session_id,
            NEW.execution_id, NEW.event_type, candidate)
        ON CONFLICT DO NOTHING;
        RETURN NEW;
    END IF;
    IF NEW.event_type <> 'inbound.message' THEN
        RETURN NEW;
    END IF;
    INSERT INTO public.agent_runtime_memory_extraction_jobs(
        tenant_id, source_event_id, extractor_version, memory_type,
        canonical_fact_key, agent_id, end_user_id, source_session_id,
        source_execution_id, source_event_type, candidate_data)
    VALUES (NEW.tenant_id, NEW.event_id, 'memory-extractor-v1', 'inbound-hydrate',
        'source', NEW.agent_id, NEW.end_user_id, NEW.session_id,
        NEW.execution_id, NEW.event_type, '{}'::jsonb)
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$;

COMMENT ON TRIGGER trg_agent_runtime_memory_extraction_enqueue ON public.agent_runtime_session_events IS
    'inbound.message 无 memoryCandidate 时登记 inbound-hydrate；显式候选与工具/工作流结果仍按原路径入队';
