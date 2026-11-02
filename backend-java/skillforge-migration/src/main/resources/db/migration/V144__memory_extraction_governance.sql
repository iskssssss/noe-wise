CREATE TABLE public.agent_runtime_memory_extraction_jobs (
    tenant_id text NOT NULL,
    source_event_id text NOT NULL,
    extractor_version text NOT NULL,
    memory_type text NOT NULL,
    canonical_fact_key text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    source_session_id text NOT NULL,
    source_execution_id text,
    source_event_type text NOT NULL,
    candidate_data jsonb NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'READY',
    attempts integer NOT NULL DEFAULT 0,
    result_fact_id text,
    result_revision bigint,
    error_code text,
    available_at timestamptz NOT NULL DEFAULT now(),
    claimed_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_agent_runtime_memory_extraction_job PRIMARY KEY
        (tenant_id, source_event_id, extractor_version, memory_type, canonical_fact_key),
    CONSTRAINT fk_agent_runtime_memory_extraction_source FOREIGN KEY
        (tenant_id, agent_id, source_session_id, source_event_id)
        REFERENCES public.agent_runtime_session_events
        (tenant_id, agent_id, session_id, event_id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_runtime_memory_extraction_status CHECK
        (status IN ('READY','PROCESSING','COMPLETED','FAILED','SKIPPED')),
    CONSTRAINT ck_agent_runtime_memory_extraction_identity CHECK
        (btrim(tenant_id) <> '' AND btrim(source_event_id) <> ''
         AND btrim(extractor_version) <> '' AND btrim(memory_type) <> ''
         AND btrim(canonical_fact_key) <> '' AND btrim(agent_id) <> ''
         AND end_user_id > 0 AND btrim(source_session_id) <> ''
         AND btrim(source_event_type) <> '' AND attempts >= 0),
    CONSTRAINT ck_agent_runtime_memory_extraction_result CHECK
        ((result_fact_id IS NULL) = (result_revision IS NULL)
         AND (result_revision IS NULL OR result_revision > 0))
);

CREATE INDEX ix_agent_runtime_memory_extraction_ready
    ON public.agent_runtime_memory_extraction_jobs
    (tenant_id, status, available_at, created_at);

CREATE TABLE public.agent_runtime_memory_usage_projection (
    tenant_id text NOT NULL,
    fact_id text NOT NULL,
    candidate_count bigint NOT NULL DEFAULT 0,
    selected_count bigint NOT NULL DEFAULT 0,
    injected_count bigint NOT NULL DEFAULT 0,
    referenced_count bigint NOT NULL DEFAULT 0,
    last_referenced_at timestamptz,
    projected_decay_score numeric(8,7) NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_agent_runtime_memory_usage_projection PRIMARY KEY (tenant_id, fact_id),
    CONSTRAINT fk_agent_runtime_memory_usage_head FOREIGN KEY (tenant_id, fact_id)
        REFERENCES public.agent_runtime_memory_fact_heads (tenant_id, fact_id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_runtime_memory_usage_counts CHECK
        (candidate_count >= 0 AND selected_count >= 0 AND injected_count >= 0
         AND referenced_count >= 0),
    CONSTRAINT ck_agent_runtime_memory_usage_decay CHECK
        (projected_decay_score >= 0 AND projected_decay_score <= 1)
);

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
    IF candidate IS NULL OR jsonb_typeof(candidate) <> 'object' THEN
        RETURN NEW;
    END IF;
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
END;
$$;

CREATE TRIGGER trg_agent_runtime_memory_extraction_enqueue
AFTER INSERT ON public.agent_runtime_session_events
FOR EACH ROW EXECUTE FUNCTION public.enqueue_agent_runtime_memory_extraction();

CREATE OR REPLACE FUNCTION public.project_agent_runtime_memory_usage()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    payload jsonb;
    phase text;
    ids jsonb;
    fact text;
BEGIN
    IF NEW.event_type <> 'retrieval.performed' THEN RETURN NEW; END IF;
    payload := NEW.payload_canonical_json::jsonb;
    phase := payload ->> 'phase';
    ids := CASE phase
        WHEN 'CANDIDATE' THEN payload #> '{trace,candidateIds}'
        WHEN 'SELECTED' THEN payload #> '{trace,selectedIds}'
        WHEN 'INJECTED' THEN payload #> '{trace,injectedIds}'
        WHEN 'REFERENCED' THEN payload #> '{trace,referencedIds}'
        ELSE '[]'::jsonb END;
    IF ids IS NULL OR jsonb_typeof(ids) <> 'array' THEN RETURN NEW; END IF;
    FOR fact IN SELECT jsonb_array_elements_text(ids) LOOP
        INSERT INTO public.agent_runtime_memory_usage_projection(
            tenant_id,fact_id,candidate_count,selected_count,injected_count,
            referenced_count,last_referenced_at,projected_decay_score)
        VALUES (NEW.tenant_id,fact,
            CASE WHEN phase='CANDIDATE' THEN 1 ELSE 0 END,
            CASE WHEN phase='SELECTED' THEN 1 ELSE 0 END,
            CASE WHEN phase='INJECTED' THEN 1 ELSE 0 END,
            CASE WHEN phase='REFERENCED' THEN 1 ELSE 0 END,
            CASE WHEN phase='REFERENCED' THEN to_timestamp(NEW.occurred_at_epoch_second) ELSE NULL END,
            0)
        ON CONFLICT (tenant_id,fact_id) DO UPDATE SET
            candidate_count=agent_runtime_memory_usage_projection.candidate_count
                + CASE WHEN phase='CANDIDATE' THEN 1 ELSE 0 END,
            selected_count=agent_runtime_memory_usage_projection.selected_count
                + CASE WHEN phase='SELECTED' THEN 1 ELSE 0 END,
            injected_count=agent_runtime_memory_usage_projection.injected_count
                + CASE WHEN phase='INJECTED' THEN 1 ELSE 0 END,
            referenced_count=agent_runtime_memory_usage_projection.referenced_count
                + CASE WHEN phase='REFERENCED' THEN 1 ELSE 0 END,
            last_referenced_at=CASE WHEN phase='REFERENCED'
                THEN to_timestamp(NEW.occurred_at_epoch_second)
                ELSE agent_runtime_memory_usage_projection.last_referenced_at END,
            projected_decay_score=CASE WHEN phase='REFERENCED' THEN 0
                ELSE agent_runtime_memory_usage_projection.projected_decay_score END,
            updated_at=now();
    END LOOP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_agent_runtime_memory_usage_projection
AFTER INSERT ON public.agent_runtime_session_events
FOR EACH ROW EXECUTE FUNCTION public.project_agent_runtime_memory_usage();

ALTER TABLE public.agent_runtime_memory_extraction_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_memory_extraction_jobs FORCE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_memory_usage_projection ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_memory_usage_projection FORCE ROW LEVEL SECURITY;

CREATE POLICY agent_runtime_memory_extraction_tenant
    ON public.agent_runtime_memory_extraction_jobs
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));
CREATE POLICY agent_runtime_memory_usage_tenant
    ON public.agent_runtime_memory_usage_projection
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

COMMENT ON TABLE public.agent_runtime_memory_extraction_jobs IS
    'SessionLog 同事务登记的持久 Memory 提取任务；稳定五元键保证崩溃与重启幂等';
COMMENT ON TABLE public.agent_runtime_memory_usage_projection IS
    '由 retrieval.performed 使用事件重建的 Memory 使用与衰减投影，不改写事实 revision';
COMMENT ON TRIGGER trg_agent_runtime_memory_extraction_enqueue ON public.agent_runtime_session_events IS
    '只从允许来源中的显式结构化 memoryCandidate 原子登记提取任务';
COMMENT ON TRIGGER trg_agent_runtime_memory_usage_projection ON public.agent_runtime_session_events IS
    '按 CANDIDATE/SELECTED/INJECTED/REFERENCED 事件阶段更新可重建使用投影';
