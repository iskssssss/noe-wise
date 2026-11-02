-- Session 所有权与实际执行 Agent 分列。现有直连 Agent 行原样回填为 AGENT target；不删除业务数据。
-- 身份列回填不是业务状态迁移，不能触发旧账本的不可变守卫或生成新的业务日志。
-- 仅在本次 Flyway 事务内暂停待回填表的用户 UPDATE 触发器；外键触发器保持启用。
-- 保存原启用模式，在回填结束后逐个恢复；迁移失败时 DDL 与数据一起回滚。
CREATE TEMP TABLE session_target_backfill_triggers ON COMMIT DROP AS
SELECT relation.relname AS table_name, trigger_row.tgname AS trigger_name,
       trigger_row.tgenabled AS enabled_mode
  FROM pg_trigger trigger_row
  JOIN pg_class relation ON relation.oid = trigger_row.tgrelid
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
 WHERE namespace.nspname = 'public'
   AND NOT trigger_row.tgisinternal
   AND (trigger_row.tgtype::integer & 16) <> 0
   AND (relation.relname IN (
           'nw_runtime_authentication_facts',
           'agent_runtime_memory_fact_sources',
           'agent_runtime_memory_extraction_jobs',
           'agent_runtime_callback_registrations',
           'agent_runtime_run_mutations')
        OR ((relation.relname LIKE 'agent_runtime_%' OR relation.relname LIKE 'nw_runtime_%')
            AND EXISTS (SELECT 1 FROM information_schema.columns column_row
                         WHERE column_row.table_schema = 'public'
                           AND column_row.table_name = relation.relname
                           AND column_row.column_name = 'session_id')
            AND EXISTS (SELECT 1 FROM information_schema.columns column_row
                         WHERE column_row.table_schema = 'public'
                           AND column_row.table_name = relation.relname
                           AND column_row.column_name = 'agent_id')));

DO $$
DECLARE
    saved_trigger record;
BEGIN
    FOR saved_trigger IN SELECT * FROM session_target_backfill_triggers
    LOOP
        EXECUTE format('ALTER TABLE public.%I DISABLE TRIGGER %I',
            saved_trigger.table_name, saved_trigger.trigger_name);
    END LOOP;
END $$;

DO $$
DECLARE
    runtime_table record;
BEGIN
    FOR runtime_table IN
        SELECT table_name
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND column_name = 'session_id'
           AND (table_name LIKE 'agent_runtime_%' OR table_name LIKE 'nw_runtime_%')
           AND table_name <> 'nw_runtime_authentication_facts'
           AND EXISTS (
               SELECT 1 FROM information_schema.columns agent_column
                WHERE agent_column.table_schema = 'public'
                  AND agent_column.table_name = information_schema.columns.table_name
                  AND agent_column.column_name = 'agent_id')
    LOOP
        EXECUTE format('ALTER TABLE public.%I ADD COLUMN IF NOT EXISTS target_kind text', runtime_table.table_name);
        EXECUTE format('ALTER TABLE public.%I ADD COLUMN IF NOT EXISTS target_id text', runtime_table.table_name);
        EXECUTE format('UPDATE public.%I SET target_kind = ''AGENT'', target_id = agent_id WHERE target_kind IS NULL OR target_id IS NULL', runtime_table.table_name);
        EXECUTE format('ALTER TABLE public.%I ALTER COLUMN target_kind SET NOT NULL, ALTER COLUMN target_id SET NOT NULL', runtime_table.table_name);
        EXECUTE format('ALTER TABLE public.%I ADD CONSTRAINT %I CHECK (target_kind IN (''AGENT'', ''TEAM'') AND btrim(target_id) <> '''')',
            runtime_table.table_name, CASE WHEN octet_length(runtime_table.table_name || '_target_check') <= 63 THEN runtime_table.table_name || '_target_check' ELSE left(runtime_table.table_name || '_target_check',52) || '_' || left(encode(sha256(convert_to(runtime_table.table_name || '_target_check','UTF8')),'hex'),10) END);
    END LOOP;
END $$;

ALTER TABLE public.nw_runtime_authentication_facts
    ADD COLUMN target_kind text,
    ADD COLUMN target_id text;
UPDATE public.nw_runtime_authentication_facts
   SET target_kind = 'AGENT', target_id = agent_id;
ALTER TABLE public.nw_runtime_authentication_facts
    ALTER COLUMN target_kind SET NOT NULL,
    ALTER COLUMN target_id SET NOT NULL,
    ALTER COLUMN agent_id DROP NOT NULL,
    ADD CONSTRAINT agent_runtime_authentication_target_check
        CHECK (target_kind IN ('AGENT', 'TEAM') AND btrim(target_id) <> '');

-- 先移除依赖旧 owner/stream Agent 键的外键，再以 target 键重建。
DO $$
DECLARE
    dependency record;
BEGIN
    FOR dependency IN
        SELECT child.relname AS table_name, constraint_row.conname AS constraint_name
          FROM pg_constraint constraint_row
          JOIN pg_class child ON child.oid = constraint_row.conrelid
         WHERE constraint_row.contype = 'f'
           AND constraint_row.confrelid IN (
               'public.agent_runtime_session_owners'::regclass,
               'public.agent_runtime_session_streams'::regclass)
    LOOP
        EXECUTE format('ALTER TABLE public.%I DROP CONSTRAINT %I',
            dependency.table_name, dependency.constraint_name);
    END LOOP;
END $$;

ALTER TABLE public.agent_runtime_session_outbox
    DROP CONSTRAINT agent_runtime_session_outbox_event_fkey;
ALTER TABLE public.agent_runtime_conversation_task_operations
    DROP CONSTRAINT fk_conversation_task_operation_event;
ALTER TABLE public.agent_runtime_memory_fact_sources
    DROP CONSTRAINT fk_agent_runtime_memory_source_session_event,
    ADD COLUMN source_target_kind text,
    ADD COLUMN source_target_id text;
UPDATE public.agent_runtime_memory_fact_sources
   SET source_target_kind = 'AGENT', source_target_id = agent_id;
ALTER TABLE public.agent_runtime_memory_fact_sources
    ALTER COLUMN source_target_kind SET NOT NULL,
    ALTER COLUMN source_target_id SET NOT NULL;
ALTER TABLE public.agent_runtime_memory_extraction_jobs
    DROP CONSTRAINT fk_agent_runtime_memory_extraction_source,
    ADD COLUMN source_target_kind text,
    ADD COLUMN source_target_id text;
UPDATE public.agent_runtime_memory_extraction_jobs
   SET source_target_kind = 'AGENT', source_target_id = agent_id;
ALTER TABLE public.agent_runtime_memory_extraction_jobs
    ALTER COLUMN source_target_kind SET NOT NULL,
    ALTER COLUMN source_target_id SET NOT NULL;

-- V149 创建的触发函数必须把来源 Session target 一并写入新任务；实际执行 Agent 仍取事件 agent_id。
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
    IF NEW.event_type <> 'inbound.message' THEN
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

ALTER TABLE public.agent_runtime_session_events
    DROP CONSTRAINT agent_runtime_session_events_pkey,
    DROP CONSTRAINT uq_agent_runtime_session_event_identity,
    DROP CONSTRAINT uq_agent_runtime_session_event_outbox_reference;
ALTER TABLE public.agent_runtime_session_streams
    DROP CONSTRAINT agent_runtime_session_streams_pkey,
    DROP CONSTRAINT uq_agent_runtime_session_stream_owner,
    ALTER COLUMN agent_id DROP NOT NULL;
ALTER TABLE public.agent_runtime_session_owners
    DROP CONSTRAINT agent_runtime_session_owners_pkey,
    DROP CONSTRAINT uq_agent_runtime_session_owner,
    ALTER COLUMN agent_id DROP NOT NULL;

ALTER TABLE public.agent_runtime_session_owners
    ADD CONSTRAINT agent_runtime_session_owners_pkey
        PRIMARY KEY (tenant_id, target_kind, target_id, session_id),
    ADD CONSTRAINT uq_agent_runtime_session_owner
        UNIQUE (tenant_id, target_kind, target_id, session_id, end_user_id);
ALTER TABLE public.agent_runtime_session_streams
    ADD CONSTRAINT agent_runtime_session_streams_pkey
        PRIMARY KEY (tenant_id, target_kind, target_id, session_id),
    ADD CONSTRAINT uq_agent_runtime_session_stream_owner
        UNIQUE (tenant_id, target_kind, target_id, session_id, end_user_id),
    ADD CONSTRAINT agent_runtime_session_stream_owner_root_fkey
        FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id)
        REFERENCES public.agent_runtime_session_owners
            (tenant_id, target_kind, target_id, session_id, end_user_id)
        ON DELETE RESTRICT;
ALTER TABLE public.agent_runtime_session_events
    ADD CONSTRAINT agent_runtime_session_events_pkey
        PRIMARY KEY (tenant_id, target_kind, target_id, session_id, session_sequence),
    ADD CONSTRAINT uq_agent_runtime_session_event_identity
        UNIQUE (tenant_id, target_kind, target_id, session_id, event_id),
    ADD CONSTRAINT uq_agent_runtime_session_event_outbox_reference
        UNIQUE (tenant_id, target_kind, target_id, session_id, agent_id, end_user_id,
                session_sequence, event_id),
    ADD CONSTRAINT agent_runtime_session_event_owner_fkey
        FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id)
        REFERENCES public.agent_runtime_session_streams
            (tenant_id, target_kind, target_id, session_id, end_user_id)
        ON DELETE RESTRICT;

ALTER TABLE public.agent_runtime_session_outbox
    ADD CONSTRAINT agent_runtime_session_outbox_event_fkey
        FOREIGN KEY (tenant_id, target_kind, target_id, session_id, agent_id, end_user_id,
                     session_sequence, event_id)
        REFERENCES public.agent_runtime_session_events
            (tenant_id, target_kind, target_id, session_id, agent_id, end_user_id,
             session_sequence, event_id)
        ON DELETE RESTRICT;
ALTER TABLE public.agent_runtime_conversation_task_operations
    ADD CONSTRAINT fk_conversation_task_operation_event
        FOREIGN KEY (tenant_id, target_kind, target_id, session_id, event_id)
        REFERENCES public.agent_runtime_session_events
            (tenant_id, target_kind, target_id, session_id, event_id)
        ON DELETE RESTRICT;
ALTER TABLE public.agent_runtime_memory_fact_sources
    ADD CONSTRAINT fk_agent_runtime_memory_source_session_event
        FOREIGN KEY (tenant_id, source_target_kind, source_target_id,
                     source_session_id, source_event_id)
        REFERENCES public.agent_runtime_session_events
            (tenant_id, target_kind, target_id, session_id, event_id)
        DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE public.agent_runtime_memory_extraction_jobs
    ADD CONSTRAINT fk_agent_runtime_memory_extraction_source
        FOREIGN KEY (tenant_id, source_target_kind, source_target_id,
                     source_session_id, source_event_id)
        REFERENCES public.agent_runtime_session_events
            (tenant_id, target_kind, target_id, session_id, event_id)
        ON DELETE RESTRICT;

-- 先释放依赖旧业务唯一键的 adapter-private 外键，随后一次性按 target 重建。
ALTER TABLE public.agent_runtime_session_mutations
    DROP CONSTRAINT agent_runtime_session_mutation_session_fkey;
ALTER TABLE public.agent_runtime_session_inbox
    DROP CONSTRAINT agent_runtime_session_inbox_stream_fkey;
ALTER TABLE public.agent_runtime_inbox_mutations
    DROP CONSTRAINT agent_runtime_inbox_mutation_entry_fkey;
ALTER TABLE public.agent_runtime_session_commit_journals
    DROP CONSTRAINT agent_runtime_session_commit_run_fkey;
ALTER TABLE public.agent_runtime_executions
    DROP CONSTRAINT agent_runtime_execution_run_fkey;
ALTER TABLE public.agent_runtime_execution_mutations
    DROP CONSTRAINT agent_runtime_execution_mutation_execution_fkey;
ALTER TABLE public.agent_runtime_callback_registrations
    DROP CONSTRAINT fk_agent_runtime_callback_action_binding_run,
    ADD COLUMN action_target_kind text,
    ADD COLUMN action_target_id text;
UPDATE public.agent_runtime_callback_registrations registration
   SET action_target_kind = run.target_kind,
       action_target_id = run.target_id
  FROM public.agent_runtime_runs run
 WHERE registration.action_run_id IS NOT NULL
   AND run.tenant_id = registration.tenant_id
   AND run.agent_id = registration.action_agent_id
   AND run.session_id = registration.action_session_id
   AND run.end_user_id = registration.action_end_user_id
   AND run.run_id = registration.action_run_id;
ALTER TABLE public.agent_runtime_callback_registrations
    ADD CONSTRAINT ck_agent_runtime_callback_action_target
        CHECK ((target_kind = 'OTHER' AND action_target_kind IS NULL
                AND action_target_id IS NULL)
            OR (target_kind = 'ACTION'
                AND action_target_kind IS NOT NULL
                AND action_target_id IS NOT NULL
                AND action_target_kind IN ('AGENT', 'TEAM')
                AND btrim(action_target_id) <> ''));
ALTER TABLE public.agent_runtime_run_mutations
    DROP CONSTRAINT agent_runtime_run_mutation_run_fkey,
    ADD COLUMN target_kind text,
    ADD COLUMN target_id text,
    ADD COLUMN agent_id text,
    ADD COLUMN end_user_id bigint;
UPDATE public.agent_runtime_run_mutations mutation
   SET target_kind = run.target_kind,
       target_id = run.target_id,
       agent_id = run.agent_id,
       end_user_id = run.end_user_id
  FROM public.agent_runtime_runs run
 WHERE run.tenant_id = mutation.tenant_id
   AND run.session_id = mutation.session_id
   AND run.run_id = mutation.run_id;

-- 最后一项身份回填完成后，恢复已有触发器的原模式，不改变管理员原有的停用设置。
DO $$
DECLARE
    saved_trigger record;
BEGIN
    FOR saved_trigger IN SELECT * FROM session_target_backfill_triggers
    LOOP
        EXECUTE format('ALTER TABLE public.%I %s TRIGGER %I',
            saved_trigger.table_name,
            CASE saved_trigger.enabled_mode
                WHEN 'O' THEN 'ENABLE'
                WHEN 'A' THEN 'ENABLE ALWAYS'
                WHEN 'R' THEN 'ENABLE REPLICA'
                WHEN 'D' THEN 'DISABLE'
            END,
            saved_trigger.trigger_name);
    END LOOP;
END $$;

ALTER TABLE public.agent_runtime_run_mutations
    ALTER COLUMN target_kind SET NOT NULL,
    ALTER COLUMN target_id SET NOT NULL,
    ALTER COLUMN agent_id SET NOT NULL,
    ALTER COLUMN end_user_id SET NOT NULL,
    ADD CONSTRAINT agent_runtime_run_mutation_target_check
        CHECK (target_kind IN ('AGENT', 'TEAM') AND btrim(target_id) <> ''
               AND btrim(agent_id) <> '' AND end_user_id > 0);
ALTER TABLE public.nw_runtime_session_outbox_operations
    DROP CONSTRAINT agent_runtime_session_outbox_operation_entry_fkey;

-- input ledger 的旧 Agent owner 唯一键被多张表引用；统一释放后按 target+actual Agent 重建。
DO $$
DECLARE
    dependency record;
BEGIN
    FOR dependency IN
        SELECT child.relname AS table_name, constraint_row.conname AS constraint_name
          FROM pg_constraint constraint_row
          JOIN pg_class child ON child.oid = constraint_row.conrelid
         WHERE constraint_row.contype = 'f'
           AND constraint_row.confrelid = 'public.agent_runtime_input_ledger'::regclass
    LOOP
        EXECUTE format('ALTER TABLE public.%I DROP CONSTRAINT %I',
            dependency.table_name, dependency.constraint_name);
    END LOOP;
END $$;

-- owner-only 表不再要求伪造执行 Agent；其主键统一改为 Session target。
ALTER TABLE public.agent_runtime_session_input_streams
    DROP CONSTRAINT agent_runtime_session_input_streams_pkey,
    DROP CONSTRAINT uq_agent_runtime_session_input_stream_owner,
    ALTER COLUMN agent_id DROP NOT NULL,
    ADD CONSTRAINT agent_runtime_session_input_streams_pkey
        PRIMARY KEY (tenant_id, target_kind, target_id, session_id),
    ADD CONSTRAINT uq_agent_runtime_session_input_stream_owner
        UNIQUE (tenant_id, target_kind, target_id, session_id, end_user_id);

ALTER TABLE public.agent_runtime_sessions
    DROP CONSTRAINT agent_runtime_sessions_pkey,
    DROP CONSTRAINT uq_agent_runtime_session_full_owner,
    ALTER COLUMN agent_id DROP NOT NULL,
    ADD CONSTRAINT agent_runtime_sessions_pkey
        PRIMARY KEY (tenant_id, target_kind, target_id, session_id),
    ADD CONSTRAINT uq_agent_runtime_session_full_owner
        UNIQUE (tenant_id, target_kind, target_id, session_id, end_user_id);
ALTER TABLE public.agent_runtime_session_mutations
    DROP CONSTRAINT agent_runtime_session_mutations_pkey,
    DROP CONSTRAINT uq_agent_runtime_session_mutation_output,
    ALTER COLUMN agent_id DROP NOT NULL,
    ADD CONSTRAINT agent_runtime_session_mutations_pkey
        PRIMARY KEY (tenant_id, target_kind, target_id, session_id,
                     expected_status, target_status, expected_revision, causation_input_id),
    ADD CONSTRAINT agent_runtime_session_mutation_session_fkey
        FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id)
        REFERENCES public.agent_runtime_sessions
            (tenant_id, target_kind, target_id, session_id, end_user_id),
    ADD CONSTRAINT uq_agent_runtime_session_mutation_output
        UNIQUE (tenant_id, target_kind, target_id, session_id, end_user_id, output_revision);

-- 每个输入只属于一个 target，但其准入、实际 Run 与事件仍显式保存执行 Agent。
ALTER TABLE public.agent_runtime_input_ledger
    DROP CONSTRAINT agent_runtime_input_ledger_pkey,
    DROP CONSTRAINT uq_agent_runtime_input_ledger_owner,
    ADD CONSTRAINT agent_runtime_input_ledger_pkey
        PRIMARY KEY (tenant_id, target_kind, target_id, session_id, input_id),
    ADD CONSTRAINT uq_agent_runtime_input_ledger_owner
        UNIQUE (tenant_id, target_kind, target_id, session_id, end_user_id, input_id),
    ADD CONSTRAINT uq_agent_runtime_input_ledger_execution
        UNIQUE (tenant_id, target_kind, target_id, session_id, agent_id, end_user_id, input_id);

ALTER TABLE public.agent_runtime_session_inbox
    DROP CONSTRAINT agent_runtime_session_inbox_pkey,
    DROP CONSTRAINT uq_agent_runtime_session_inbox_sequence,
    ADD CONSTRAINT agent_runtime_session_inbox_pkey
        PRIMARY KEY (tenant_id, target_kind, target_id, session_id, input_id),
    ADD CONSTRAINT uq_agent_runtime_session_inbox_sequence
        UNIQUE (tenant_id, target_kind, target_id, session_id, inbox_sequence),
    ADD CONSTRAINT agent_runtime_session_inbox_stream_fkey
        FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id)
        REFERENCES public.agent_runtime_session_input_streams
            (tenant_id, target_kind, target_id, session_id, end_user_id),
    ADD CONSTRAINT agent_runtime_session_inbox_input_fkey
        FOREIGN KEY (tenant_id, target_kind, target_id, session_id, agent_id,
                     end_user_id, input_id)
        REFERENCES public.agent_runtime_input_ledger
            (tenant_id, target_kind, target_id, session_id, agent_id,
             end_user_id, input_id);
ALTER TABLE public.agent_runtime_inbox_mutations
    DROP CONSTRAINT agent_runtime_inbox_mutations_pkey,
    ADD CONSTRAINT agent_runtime_inbox_mutations_pkey
        PRIMARY KEY (tenant_id, target_kind, target_id, session_id, input_id, mutation_key),
    ADD CONSTRAINT agent_runtime_inbox_mutation_entry_fkey
        FOREIGN KEY (tenant_id, target_kind, target_id, session_id, input_id)
        REFERENCES public.agent_runtime_session_inbox
            (tenant_id, target_kind, target_id, session_id, input_id);

ALTER TABLE public.nw_runtime_session_workflow_resumes
    ADD CONSTRAINT agent_runtime_session_workflow_resume_input_fkey
        FOREIGN KEY (tenant_id, target_kind, target_id, session_id, agent_id,
                     end_user_id, input_id)
        REFERENCES public.agent_runtime_input_ledger
            (tenant_id, target_kind, target_id, session_id, agent_id,
             end_user_id, input_id)
        ON DELETE RESTRICT;
ALTER TABLE public.nw_runtime_session_tool_input_responses
    DROP CONSTRAINT uq_agent_runtime_session_tool_input_response_input,
    ADD CONSTRAINT uq_agent_runtime_session_tool_input_response_input
        UNIQUE (tenant_id, target_kind, target_id, agent_id, session_id, input_id),
    ADD CONSTRAINT fk_agent_runtime_session_tool_input_response_input
        FOREIGN KEY (tenant_id, target_kind, target_id, session_id, agent_id,
                     end_user_id, input_id)
        REFERENCES public.agent_runtime_input_ledger
            (tenant_id, target_kind, target_id, session_id, agent_id,
             end_user_id, input_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE public.agent_runtime_runs
    DROP CONSTRAINT uq_agent_runtime_run_owner,
    DROP CONSTRAINT uq_agent_runtime_run_full_owner,
    ADD CONSTRAINT uq_agent_runtime_run_owner
        UNIQUE (tenant_id, target_kind, target_id, session_id, run_id),
    ADD CONSTRAINT uq_agent_runtime_run_full_owner
        UNIQUE (tenant_id, target_kind, target_id, agent_id, session_id, end_user_id, run_id),
    ADD CONSTRAINT agent_runtime_run_input_fkey
        FOREIGN KEY (tenant_id, target_kind, target_id, session_id, agent_id,
                     end_user_id, causation_input_id)
        REFERENCES public.agent_runtime_input_ledger
            (tenant_id, target_kind, target_id, session_id, agent_id,
             end_user_id, input_id);
ALTER TABLE public.agent_runtime_run_mutations
    ADD CONSTRAINT agent_runtime_run_mutation_run_fkey
        FOREIGN KEY (tenant_id, target_kind, target_id, agent_id, session_id,
                     end_user_id, run_id)
        REFERENCES public.agent_runtime_runs
            (tenant_id, target_kind, target_id, agent_id, session_id,
             end_user_id, run_id)
        ON DELETE RESTRICT;
ALTER TABLE public.agent_runtime_callback_registrations
    ADD CONSTRAINT fk_agent_runtime_callback_action_binding_run
        FOREIGN KEY (tenant_id, action_target_kind, action_target_id,
                     action_agent_id, action_session_id, action_end_user_id,
                     action_run_id)
        REFERENCES public.agent_runtime_runs
            (tenant_id, target_kind, target_id, agent_id, session_id,
             end_user_id, run_id)
        ON DELETE RESTRICT;

CREATE OR REPLACE FUNCTION public.assert_agent_runtime_callback_action_binding()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.target_kind = 'ACTION' AND NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_actions action
          JOIN public.agent_runtime_runs run
            ON run.tenant_id = action.tenant_id
           AND run.target_kind = action.target_kind
           AND run.target_id = action.target_id
           AND run.agent_id = action.agent_id
           AND run.session_id = action.session_id
           AND run.end_user_id = action.end_user_id
           AND run.run_id = action.run_id
         WHERE action.tenant_id = NEW.tenant_id
           AND action.target_kind = NEW.action_target_kind
           AND action.target_id = NEW.action_target_id
           AND action.agent_id = NEW.action_agent_id
           AND action.session_id = NEW.action_session_id
           AND action.end_user_id = NEW.action_end_user_id
           AND action.run_id = NEW.action_run_id
           AND action.action_id = NEW.action_id
           AND action.invocation_id = NEW.action_invocation_id
           AND action.execute_call_kind = 'TOOL_INVOKE'
           AND action.state = 'CLAIMED'
           AND action."authorization" = 'EXECUTE'
           AND action.action_revision = NEW.registered_action_revision
           AND action.lease_action_revision = action.action_revision
           AND action.lease_until_epoch_second IS NOT NULL
           AND (action.lease_until_epoch_second::numeric
                + action.lease_until_nano::numeric / 1000000000)
               > extract(epoch FROM clock_timestamp())
           AND run.cancel_request_id IS NULL
           AND run.status NOT IN
               ('COMPLETED', 'TERMINATED', 'FAILED', 'CANCELLED')) THEN
        RAISE EXCEPTION 'callback action binding source mismatch';
    END IF;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION public.guard_agent_runtime_callback_action_binding()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.target_kind, NEW.action_target_kind, NEW.action_target_id,
        NEW.action_agent_id, NEW.action_session_id, NEW.action_end_user_id,
        NEW.action_run_id, NEW.action_id, NEW.action_invocation_id,
        NEW.registered_action_revision)
       IS DISTINCT FROM
       (OLD.target_kind, OLD.action_target_kind, OLD.action_target_id,
        OLD.action_agent_id, OLD.action_session_id, OLD.action_end_user_id,
        OLD.action_run_id, OLD.action_id, OLD.action_invocation_id,
        OLD.registered_action_revision) THEN
        RAISE EXCEPTION 'callback action binding is immutable';
    END IF;
    RETURN NEW;
END;
$$;

ALTER TABLE public.agent_runtime_executions
    DROP CONSTRAINT uq_agent_runtime_execution_full_owner,
    ADD CONSTRAINT uq_agent_runtime_execution_full_owner
        UNIQUE (tenant_id, execution_id, target_kind, target_id, agent_id,
                session_id, end_user_id, run_id),
    ADD CONSTRAINT agent_runtime_execution_run_fkey
        FOREIGN KEY (tenant_id, target_kind, target_id, agent_id, session_id,
                     end_user_id, run_id)
        REFERENCES public.agent_runtime_runs
            (tenant_id, target_kind, target_id, agent_id, session_id,
             end_user_id, run_id)
        ON DELETE RESTRICT;
ALTER TABLE public.agent_runtime_execution_mutations
    ADD CONSTRAINT agent_runtime_execution_mutation_execution_fkey
        FOREIGN KEY (tenant_id, execution_id, target_kind, target_id, agent_id,
                     session_id, end_user_id, run_id)
        REFERENCES public.agent_runtime_executions
            (tenant_id, execution_id, target_kind, target_id, agent_id,
             session_id, end_user_id, run_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE public.agent_runtime_session_admission_prepares
    DROP CONSTRAINT agent_runtime_session_admission_prepares_pkey,
    ADD CONSTRAINT agent_runtime_session_admission_prepares_pkey
        PRIMARY KEY (tenant_id, target_kind, target_id, session_id, input_id);
ALTER TABLE public.agent_runtime_session_commit_journals
    ADD CONSTRAINT agent_runtime_session_commit_run_fkey
        FOREIGN KEY (tenant_id, target_kind, target_id, agent_id, session_id,
                     end_user_id, run_id)
        REFERENCES public.agent_runtime_runs
            (tenant_id, target_kind, target_id, agent_id, session_id,
             end_user_id, run_id)
        ON DELETE RESTRICT;

ALTER TABLE public.nw_runtime_session_initial_runs
    DROP CONSTRAINT agent_runtime_session_initial_run_pk,
    ADD CONSTRAINT skillforge_runtime_session_initial_runs_pkey
        PRIMARY KEY (tenant_id, target_kind, target_id, session_id, input_id);
ALTER TABLE public.nw_runtime_session_capabilities
    DROP CONSTRAINT nw_runtime_session_capabilities_pkey,
    ADD CONSTRAINT skillforge_runtime_session_capabilities_pkey
        PRIMARY KEY (tenant_id, target_kind, target_id, session_id, input_id);

-- Outbox 以 target 排序和领取，event.agent_id 只保留事件的真实执行者。
ALTER TABLE public.agent_runtime_session_outbox
    DROP CONSTRAINT agent_runtime_session_outbox_pkey,
    DROP CONSTRAINT uq_agent_runtime_session_outbox_sequence,
    ADD CONSTRAINT agent_runtime_session_outbox_pkey
        PRIMARY KEY (tenant_id, target_kind, target_id, session_id, event_id),
    ADD CONSTRAINT uq_agent_runtime_session_outbox_sequence
        UNIQUE (tenant_id, target_kind, target_id, session_id, session_sequence);
ALTER TABLE public.nw_runtime_session_outbox_operations
    DROP CONSTRAINT skillforge_runtime_session_outbox_operations_pkey,
    ADD CONSTRAINT skillforge_runtime_session_outbox_operations_pkey
        PRIMARY KEY (tenant_id, target_kind, target_id, session_id, event_id,
                     claim_id, fencing_token, expected_revision, mutation_kind),
    ADD CONSTRAINT agent_runtime_session_outbox_operation_entry_fkey
        FOREIGN KEY (tenant_id, target_kind, target_id, session_id, event_id)
        REFERENCES public.agent_runtime_session_outbox
            (tenant_id, target_kind, target_id, session_id, event_id)
        ON DELETE RESTRICT;

-- 所有其余包含完整 owner 列的 Runtime 表都重新引用 target owner root。
DO $$
DECLARE
    runtime_table record;
BEGIN
    FOR runtime_table IN
        SELECT columns.table_name
          FROM information_schema.columns columns
         WHERE columns.table_schema = 'public'
           AND columns.column_name = 'target_kind'
           AND (columns.table_name LIKE 'agent_runtime_%' OR columns.table_name LIKE 'nw_runtime_%')
           AND columns.table_name NOT IN ('agent_runtime_session_owners',
                'agent_runtime_session_streams', 'agent_runtime_session_events',
                'nw_runtime_authentication_facts', 'nw_runtime_host_owners')
           AND EXISTS (SELECT 1 FROM information_schema.columns candidate
                WHERE candidate.table_schema = 'public' AND candidate.table_name = columns.table_name
                  AND candidate.column_name = 'target_id')
           AND EXISTS (SELECT 1 FROM information_schema.columns candidate
                WHERE candidate.table_schema = 'public' AND candidate.table_name = columns.table_name
                  AND candidate.column_name = 'end_user_id')
    LOOP
        EXECUTE format('ALTER TABLE public.%I ADD CONSTRAINT %I FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_owners (tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT',
            runtime_table.table_name, CASE WHEN octet_length(runtime_table.table_name || '_target_owner_fkey') <= 63 THEN runtime_table.table_name || '_target_owner_fkey' ELSE left(runtime_table.table_name || '_target_owner_fkey',52) || '_' || left(encode(sha256(convert_to(runtime_table.table_name || '_target_owner_fkey','UTF8')),'hex'),10) END);
    END LOOP;
END $$;

-- SessionLog 分配器和不可变检查改用 target；event.agent_id 只表示真实执行 Agent。
CREATE OR REPLACE FUNCTION public.guard_agent_runtime_session_stream()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.next_sequence <> 1 THEN
            RAISE EXCEPTION 'new agent runtime session stream sequence must start at one';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'agent runtime session stream is immutable'; END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.session_id IS DISTINCT FROM OLD.session_id
        OR NEW.target_kind IS DISTINCT FROM OLD.target_kind
        OR NEW.target_id IS DISTINCT FROM OLD.target_id
        OR NEW.end_user_id IS DISTINCT FROM OLD.end_user_id
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'agent runtime session owner is immutable';
    END IF;
    IF NEW.next_sequence <> OLD.next_sequence + 1 THEN
        RAISE EXCEPTION 'agent runtime session sequence must advance by one';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.agent_runtime_session_events
        WHERE tenant_id = OLD.tenant_id AND target_kind = OLD.target_kind
          AND target_id = OLD.target_id AND session_id = OLD.session_id
          AND session_sequence = OLD.next_sequence) THEN
        RAISE EXCEPTION 'agent runtime session advance requires its event';
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION public.guard_agent_runtime_session_event_append()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE stream_next_sequence bigint;
BEGIN
    SELECT next_sequence INTO stream_next_sequence
      FROM public.agent_runtime_session_streams
     WHERE tenant_id = NEW.tenant_id AND target_kind = NEW.target_kind
       AND target_id = NEW.target_id AND session_id = NEW.session_id
     FOR UPDATE;
    IF stream_next_sequence IS NULL OR NEW.session_sequence <> stream_next_sequence THEN
        RAISE EXCEPTION 'agent runtime session event must use next sequence';
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION public.assert_agent_runtime_session_event_advanced()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE stream_next_sequence bigint;
BEGIN
    SELECT next_sequence INTO stream_next_sequence
      FROM public.agent_runtime_session_streams
     WHERE tenant_id = NEW.tenant_id AND target_kind = NEW.target_kind
       AND target_id = NEW.target_id AND session_id = NEW.session_id;
    IF stream_next_sequence IS NULL OR stream_next_sequence <= NEW.session_sequence THEN
        RAISE EXCEPTION 'agent runtime session event was not advanced';
    END IF;
    RETURN NULL;
END $$;

CREATE INDEX agent_runtime_session_events_target_read
    ON public.agent_runtime_session_events
        (tenant_id, target_kind, target_id, session_id, end_user_id, session_sequence);

-- ConversationTask 仍按实际 Agent 私有；会话操作和当前选择另外保存 owner target。
ALTER TABLE public.agent_runtime_conversation_task_operations
    DROP CONSTRAINT pk_conversation_task_operations,
    ADD CONSTRAINT pk_conversation_task_operations
        PRIMARY KEY (tenant_id, target_kind, target_id, session_id, agent_id, event_id);
ALTER TABLE public.agent_runtime_conversation_task_bindings
    DROP CONSTRAINT pk_conversation_task_bindings,
    ADD CONSTRAINT pk_conversation_task_bindings
        PRIMARY KEY (tenant_id, target_kind, target_id, session_id, agent_id);

COMMENT ON COLUMN public.agent_runtime_session_owners.target_kind IS 'Session 服务目标类型 AGENT 或 TEAM';
COMMENT ON COLUMN public.agent_runtime_session_owners.target_id IS 'Session 服务目标标识，不代表实际执行 Agent';
COMMENT ON COLUMN public.agent_runtime_session_events.agent_id IS '产生事件的实际执行 Agent';

-- 旧 trigger function 改为 target owner 语义。
CREATE OR REPLACE FUNCTION public.guard_agent_runtime_session_target_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.target_kind, NEW.target_id)
       IS DISTINCT FROM (OLD.target_kind, OLD.target_id) THEN
        RAISE EXCEPTION 'agent runtime session target is immutable';
    END IF;
    RETURN NEW;
END;
$$;

DO $$
DECLARE
    runtime_table record;
BEGIN
    FOR runtime_table IN
        SELECT columns.table_name
          FROM information_schema.columns columns
         WHERE columns.table_schema = 'public'
           AND columns.column_name = 'target_kind'
           AND (columns.table_name LIKE 'agent_runtime_%' OR columns.table_name LIKE 'nw_runtime_%')
           AND EXISTS (SELECT 1 FROM information_schema.columns candidate
                WHERE candidate.table_schema = 'public'
                  AND candidate.table_name = columns.table_name
                  AND candidate.column_name = 'target_id')
    LOOP
        EXECUTE format(
            'CREATE TRIGGER agent_runtime_session_target_immutable BEFORE UPDATE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_target_immutable()',
            runtime_table.table_name);
    END LOOP;
END $$;

CREATE OR REPLACE FUNCTION public.guard_agent_runtime_session_input_stream()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime input stream is not deletable';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.tenant_id, NEW.session_id, NEW.target_kind, NEW.target_id, NEW.end_user_id)
             IS DISTINCT FROM (OLD.tenant_id, OLD.session_id, OLD.target_kind, OLD.target_id, OLD.end_user_id)
            OR NEW.next_sequence <> OLD.next_sequence + 1 THEN
            RAISE EXCEPTION 'agent runtime input stream may only advance by one';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.guard_agent_runtime_session_inbox()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime inbox entry is not deletable';
    END IF;
    IF (NEW.tenant_id, NEW.session_id, NEW.target_kind, NEW.target_id, NEW.agent_id, NEW.end_user_id,
        NEW.input_id, NEW.inbox_sequence, NEW.created_epoch_second, NEW.created_nano)
       <> (OLD.tenant_id, OLD.session_id, OLD.target_kind, OLD.target_id, OLD.agent_id, OLD.end_user_id,
        OLD.input_id, OLD.inbox_sequence, OLD.created_epoch_second, OLD.created_nano) THEN
        RAISE EXCEPTION 'agent runtime inbox immutable identity changed';
    END IF;
    IF OLD.status IN ('COMPLETED', 'FAILED') THEN
        RAISE EXCEPTION 'terminal agent runtime inbox entry is immutable';
    END IF;
    IF NEW.inbox_revision = OLD.inbox_revision THEN
        IF OLD.status <> 'PROCESSING' OR NEW.status <> 'PROCESSING'
           OR NEW.attempts <> OLD.attempts
           OR (NEW.available_epoch_second, NEW.available_nano)
                IS DISTINCT FROM (OLD.available_epoch_second, OLD.available_nano)
           OR NEW.result_ref IS DISTINCT FROM OLD.result_ref
           OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
           OR (NEW.last_requested_epoch_second, NEW.last_requested_nano)
                IS DISTINCT FROM (OLD.last_requested_epoch_second, OLD.last_requested_nano)
           OR NEW.lease_claim_id IS DISTINCT FROM OLD.lease_claim_id
           OR NEW.lease_worker_id IS DISTINCT FROM OLD.lease_worker_id
           OR NEW.lease_fencing_token IS DISTINCT FROM OLD.lease_fencing_token
           OR (NEW.lease_until_epoch_second, NEW.lease_until_nano)
                <= (OLD.lease_until_epoch_second, OLD.lease_until_nano) THEN
            RAISE EXCEPTION 'inbox revision may stay unchanged only for renewal';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.inbox_revision <> OLD.inbox_revision + 1 THEN
        RAISE EXCEPTION 'agent runtime inbox revision must advance by one';
    END IF;
    IF NEW.status = 'PROCESSING' THEN
        IF OLD.status NOT IN ('QUEUED', 'PROCESSING')
           OR NEW.attempts <> OLD.attempts + 1
           OR NEW.last_requested_epoch_second IS NULL
           OR (NEW.available_epoch_second, NEW.available_nano)
                IS DISTINCT FROM (OLD.available_epoch_second, OLD.available_nano)
           OR NEW.result_ref IS DISTINCT FROM OLD.result_ref
           OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
           OR (OLD.status = 'PROCESSING'
               AND NEW.lease_fencing_token <= OLD.lease_fencing_token) THEN
            RAISE EXCEPTION 'invalid agent runtime inbox claim';
        END IF;
    ELSIF OLD.status = 'PROCESSING'
          AND NEW.status IN ('QUEUED', 'COMPLETED', 'FAILED') THEN
        IF NEW.attempts <> OLD.attempts
           OR (NEW.last_requested_epoch_second, NEW.last_requested_nano)
                IS DISTINCT FROM (OLD.last_requested_epoch_second, OLD.last_requested_nano) THEN
            RAISE EXCEPTION 'inbox mutation must preserve attempts';
        END IF;
    ELSE
        RAISE EXCEPTION 'illegal agent runtime inbox state transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.guard_agent_runtime_run()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime run is not deletable';
    END IF;
    IF (NEW.tenant_id, NEW.session_id, NEW.target_kind, NEW.target_id, NEW.agent_id, NEW.end_user_id,
        NEW.run_id, NEW.causation_input_id, NEW.created_epoch_second, NEW.created_nano)
       <> (OLD.tenant_id, OLD.session_id, OLD.target_kind, OLD.target_id, OLD.agent_id, OLD.end_user_id,
        OLD.run_id, OLD.causation_input_id, OLD.created_epoch_second, OLD.created_nano) THEN
        RAISE EXCEPTION 'agent runtime run immutable identity changed';
    END IF;
    IF OLD.status IN ('COMPLETED', 'TERMINATED', 'FAILED', 'CANCELLED') THEN
        RAISE EXCEPTION 'terminal agent runtime run is immutable';
    END IF;
    IF NEW.run_revision = OLD.run_revision THEN
        IF NEW.status <> OLD.status
           OR NEW.cancel_request_id IS DISTINCT FROM OLD.cancel_request_id
           OR NEW.cancel_requested_epoch_second IS DISTINCT FROM OLD.cancel_requested_epoch_second
           OR NEW.cancel_requested_nano IS DISTINCT FROM OLD.cancel_requested_nano
           OR NOT (
               (OLD.lease_claim_id IS NULL AND NEW.lease_claim_id IS NOT NULL
                AND NEW.last_requested_epoch_second IS NOT NULL)
               OR (OLD.lease_claim_id IS NOT NULL AND NEW.lease_claim_id IS NULL
                   AND (NEW.last_requested_epoch_second, NEW.last_requested_nano)
                       IS NOT DISTINCT FROM
                           (OLD.last_requested_epoch_second, OLD.last_requested_nano))
               OR (OLD.lease_claim_id IS NOT NULL AND NEW.lease_claim_id IS NOT NULL
                   AND ((NEW.lease_claim_id, NEW.lease_worker_id,
                         NEW.lease_fencing_token)
                            IS NOT DISTINCT FROM
                                (OLD.lease_claim_id, OLD.lease_worker_id,
                                 OLD.lease_fencing_token)
                        AND (NEW.lease_until_epoch_second, NEW.lease_until_nano)
                            > (OLD.lease_until_epoch_second, OLD.lease_until_nano)
                        AND (NEW.last_requested_epoch_second, NEW.last_requested_nano)
                            IS NOT DISTINCT FROM
                                (OLD.last_requested_epoch_second,
                                 OLD.last_requested_nano))
                       OR (NEW.lease_fencing_token > OLD.lease_fencing_token
                           AND NEW.last_requested_epoch_second IS NOT NULL))) THEN
            RAISE EXCEPTION 'run revision may stay unchanged only for lease mutation';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.run_revision <> OLD.run_revision + 1 THEN
        RAISE EXCEPTION 'agent runtime run revision must advance by one';
    END IF;
    IF (NEW.last_requested_epoch_second, NEW.last_requested_nano)
        IS DISTINCT FROM (OLD.last_requested_epoch_second, OLD.last_requested_nano) THEN
        RAISE EXCEPTION 'run domain mutation must preserve last lease request';
    END IF;
    IF OLD.status = NEW.status THEN
        IF NEW.cancel_request_id IS NOT DISTINCT FROM OLD.cancel_request_id
           AND OLD.status <> 'RUNNING' THEN
            RAISE EXCEPTION 'same-state run revision requires progress or cancel';
        END IF;
    ELSIF NOT (
        (OLD.status = 'QUEUED' AND NEW.status IN ('RUNNING', 'FAILED', 'CANCELLED'))
        OR (OLD.status = 'RUNNING' AND NEW.status IN
            ('SUSPENDED', 'COMPLETED', 'TERMINATED', 'FAILED', 'CANCELLED'))
        OR (OLD.status = 'SUSPENDED' AND NEW.status IN
            ('RESUMING', 'FAILED', 'CANCELLED'))
        OR (OLD.status = 'RESUMING' AND NEW.status IN
            ('RUNNING', 'SUSPENDED', 'COMPLETED', 'TERMINATED', 'FAILED', 'CANCELLED'))) THEN
        RAISE EXCEPTION 'illegal agent runtime run state transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.guard_agent_runtime_session()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'CREATED' OR NEW.session_revision <> 1
           OR (NEW.updated_epoch_second, NEW.updated_nano)
                IS DISTINCT FROM (NEW.created_epoch_second, NEW.created_nano) THEN
            RAISE EXCEPTION 'new agent runtime session must start CREATED at revision one';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime session is not deletable';
    END IF;
    IF (NEW.tenant_id, NEW.session_id, NEW.target_kind, NEW.target_id, NEW.end_user_id,
        NEW.channel, NEW.scope_key, NEW.persona_version,
        NEW.create_fingerprint, NEW.create_command_data,
        NEW.created_epoch_second, NEW.created_nano)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.session_id, OLD.target_kind, OLD.target_id, OLD.end_user_id,
        OLD.channel, OLD.scope_key, OLD.persona_version,
        OLD.create_fingerprint, OLD.create_command_data,
        OLD.created_epoch_second, OLD.created_nano) THEN
        RAISE EXCEPTION 'agent runtime session immutable facts changed';
    END IF;
    IF NEW.session_revision <> OLD.session_revision + 1
       OR NOT ((OLD.status = 'CREATED' AND NEW.status = 'ACTIVE')
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'CLOSED')) THEN
        RAISE EXCEPTION 'illegal agent runtime session lifecycle transition';
    END IF;
    IF (NEW.updated_epoch_second, NEW.updated_nano)
        < (OLD.updated_epoch_second, OLD.updated_nano) THEN
        RAISE EXCEPTION 'agent runtime session updated time moved backwards';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.assert_agent_runtime_session_mutation_journaled()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM public.agent_runtime_session_mutations mutation
         WHERE mutation.tenant_id = NEW.tenant_id
           AND mutation.target_kind = NEW.target_kind
           AND mutation.target_id = NEW.target_id
           AND mutation.session_id = NEW.session_id
           AND mutation.end_user_id = NEW.end_user_id
           AND mutation.expected_status = OLD.status
           AND mutation.target_status = NEW.status
           AND mutation.expected_revision = OLD.session_revision
           AND mutation.output_status = NEW.status
           AND mutation.output_revision = NEW.session_revision
           AND (mutation.output_updated_epoch_second,
                mutation.output_updated_nano)
               = (NEW.updated_epoch_second, NEW.updated_nano)) THEN
        RAISE EXCEPTION 'session lifecycle transition must commit its journal';
    END IF;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION public.assert_agent_runtime_session_mutation_applied()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM public.agent_runtime_sessions session_record
         WHERE session_record.tenant_id = NEW.tenant_id
           AND session_record.target_kind = NEW.target_kind
           AND session_record.target_id = NEW.target_id
           AND session_record.session_id = NEW.session_id
           AND session_record.end_user_id = NEW.end_user_id
           AND session_record.status = NEW.output_status
           AND session_record.session_revision = NEW.output_revision
           AND (session_record.updated_epoch_second,
                session_record.updated_nano)
               = (NEW.output_updated_epoch_second, NEW.output_updated_nano)) THEN
        RAISE EXCEPTION 'session mutation journal must match committed lifecycle output';
    END IF;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION public.guard_agent_runtime_session_outbox()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING'
            OR NEW.outbox_revision <> 1
            OR NEW.attempts <> 0
            OR NEW.initial_available_epoch_second <> NEW.available_epoch_second
            OR NEW.initial_available_nano <> NEW.available_nano
            OR NEW.last_requested_epoch_second IS NOT NULL
            OR NEW.last_requested_nano IS NOT NULL
            OR NEW.reason_code IS NOT NULL
            OR NEW.diagnostic_ref IS NOT NULL THEN
            RAISE EXCEPTION 'new agent runtime session outbox entry must be initial PENDING';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime session outbox entry cannot be deleted';
    END IF;

    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.session_id IS DISTINCT FROM OLD.session_id
        OR NEW.target_kind IS DISTINCT FROM OLD.target_kind
        OR NEW.target_id IS DISTINCT FROM OLD.target_id
        OR NEW.agent_id IS DISTINCT FROM OLD.agent_id
        OR NEW.end_user_id IS DISTINCT FROM OLD.end_user_id
        OR NEW.session_sequence IS DISTINCT FROM OLD.session_sequence
        OR NEW.event_id IS DISTINCT FROM OLD.event_id
        OR NEW.destination IS DISTINCT FROM OLD.destination
        OR NEW.initial_available_epoch_second IS DISTINCT FROM OLD.initial_available_epoch_second
        OR NEW.initial_available_nano IS DISTINCT FROM OLD.initial_available_nano
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'agent runtime session outbox identity and original append are immutable';
    END IF;
    IF NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'agent runtime session outbox updated_at cannot move backwards';
    END IF;
    IF OLD.status IN ('SENT', 'DEAD_LETTER') THEN
        RAISE EXCEPTION 'terminal agent runtime session outbox entry is immutable';
    END IF;

    IF NEW.outbox_revision = OLD.outbox_revision THEN
        IF OLD.status <> 'DELIVERING' OR NEW.status <> 'DELIVERING'
            OR NEW.attempts <> OLD.attempts
            OR NEW.available_epoch_second <> OLD.available_epoch_second
            OR NEW.available_nano <> OLD.available_nano
            OR NEW.lease_claim_id IS DISTINCT FROM OLD.lease_claim_id
            OR NEW.lease_worker_id IS DISTINCT FROM OLD.lease_worker_id
            OR NEW.lease_fencing_token IS DISTINCT FROM OLD.lease_fencing_token
            OR (NEW.lease_until_epoch_second, NEW.lease_until_nano)
                <= (OLD.lease_until_epoch_second, OLD.lease_until_nano)
            OR NEW.last_requested_epoch_second IS DISTINCT FROM OLD.last_requested_epoch_second
            OR NEW.last_requested_nano IS DISTINCT FROM OLD.last_requested_nano
            OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
            OR NEW.diagnostic_ref IS DISTINCT FROM OLD.diagnostic_ref THEN
            RAISE EXCEPTION 'outbox revision may stay unchanged only for lease renewal';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.outbox_revision <> OLD.outbox_revision + 1 THEN
        RAISE EXCEPTION 'agent runtime session outbox revision must advance by one';
    END IF;

    IF OLD.status = 'PENDING' AND NEW.status = 'DELIVERING' THEN
        IF NEW.attempts <> OLD.attempts + 1
            OR NEW.available_epoch_second <> OLD.available_epoch_second
            OR NEW.available_nano <> OLD.available_nano
            OR NEW.last_requested_epoch_second IS NULL
            OR NEW.last_requested_nano IS NULL
            OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
            OR NEW.diagnostic_ref IS DISTINCT FROM OLD.diagnostic_ref THEN
            RAISE EXCEPTION 'pending outbox entry may only be claimed';
        END IF;
    ELSIF OLD.status = 'DELIVERING' AND NEW.status = 'DELIVERING' THEN
        IF NEW.attempts <> OLD.attempts + 1
            OR NEW.available_epoch_second <> OLD.available_epoch_second
            OR NEW.available_nano <> OLD.available_nano
            OR NEW.lease_fencing_token <= OLD.lease_fencing_token
            OR NEW.last_requested_epoch_second IS NULL
            OR NEW.last_requested_nano IS NULL
            OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
            OR NEW.diagnostic_ref IS DISTINCT FROM OLD.diagnostic_ref THEN
            RAISE EXCEPTION 'delivering outbox entry may only be reclaimed with a higher fence';
        END IF;
    ELSIF OLD.status = 'DELIVERING' AND NEW.status = 'SENT' THEN
        IF NEW.attempts <> OLD.attempts
            OR NEW.available_epoch_second <> OLD.available_epoch_second
            OR NEW.available_nano <> OLD.available_nano
            OR NEW.last_requested_epoch_second IS DISTINCT FROM OLD.last_requested_epoch_second
            OR NEW.last_requested_nano IS DISTINCT FROM OLD.last_requested_nano
            OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
            OR NEW.diagnostic_ref IS DISTINCT FROM OLD.diagnostic_ref THEN
            RAISE EXCEPTION 'delivering outbox entry may only be marked sent without fact drift';
        END IF;
    ELSIF OLD.status = 'DELIVERING' AND NEW.status = 'PENDING' THEN
        IF NEW.attempts <> OLD.attempts
            OR NEW.last_requested_epoch_second IS DISTINCT FROM OLD.last_requested_epoch_second
            OR NEW.last_requested_nano IS DISTINCT FROM OLD.last_requested_nano
            OR NEW.reason_code IS NULL OR NEW.diagnostic_ref IS NULL THEN
            RAISE EXCEPTION 'retry must preserve attempts and record its diagnostic';
        END IF;
    ELSIF OLD.status = 'DELIVERING' AND NEW.status = 'DEAD_LETTER' THEN
        IF NEW.attempts <> OLD.attempts
            OR NEW.available_epoch_second <> OLD.available_epoch_second
            OR NEW.available_nano <> OLD.available_nano
            OR NEW.last_requested_epoch_second IS DISTINCT FROM OLD.last_requested_epoch_second
            OR NEW.last_requested_nano IS DISTINCT FROM OLD.last_requested_nano
            OR NEW.reason_code IS NULL OR NEW.diagnostic_ref IS NULL THEN
            RAISE EXCEPTION 'dead letter must preserve attempts and record its diagnostic';
        END IF;
    ELSE
        RAISE EXCEPTION 'illegal agent runtime session outbox state transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.guard_agent_runtime_session_outbox_operation()
RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    current_revision bigint;
    current_status text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'agent runtime session outbox operation is immutable';
    END IF;

    SELECT outbox_revision, status
      INTO current_revision, current_status
     FROM public.agent_runtime_session_outbox
     WHERE tenant_id = NEW.tenant_id
       AND target_kind = NEW.target_kind
       AND target_id = NEW.target_id
       AND agent_id = NEW.agent_id
       AND session_id = NEW.session_id
       AND event_id = NEW.event_id;
    IF current_revision IS NULL
        OR NEW.output_revision > current_revision THEN
        RAISE EXCEPTION 'outbox operation output revision exceeds current entry revision';
    END IF;
    IF NEW.output_revision = current_revision
        AND NEW.output_status <> current_status THEN
        RAISE EXCEPTION 'outbox operation output status does not match current entry';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.assert_agent_runtime_session_outbox_mutation_journaled()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'DELIVERING'
        AND NEW.status IN ('PENDING', 'SENT', 'DEAD_LETTER')
        AND NOT EXISTS (
            SELECT 1
             FROM public.nw_runtime_session_outbox_operations operation
             WHERE operation.tenant_id = NEW.tenant_id
               AND operation.target_kind = NEW.target_kind
               AND operation.target_id = NEW.target_id
               AND operation.agent_id = NEW.agent_id
               AND operation.session_id = NEW.session_id
               AND operation.event_id = NEW.event_id
               AND operation.claim_id = OLD.lease_claim_id
               AND operation.fencing_token = OLD.lease_fencing_token
               AND operation.expected_revision = OLD.outbox_revision
               AND operation.output_revision = NEW.outbox_revision
               AND operation.output_status = NEW.status) THEN
        RAISE EXCEPTION 'outbox mutation must commit its operation journal';
    END IF;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION public.assert_agent_runtime_session_workflow_resume_sources()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_input_ledger i
         WHERE i.tenant_id=NEW.tenant_id AND i.target_kind=NEW.target_kind
           AND i.target_id=NEW.target_id AND i.agent_id=NEW.agent_id
           AND i.session_id=NEW.session_id AND i.end_user_id=NEW.end_user_id
           AND i.input_id=NEW.input_id AND i.run_id=NEW.run_id
           AND i.decision_kind='ACCEPTED_EXISTING_RUN' AND i.receipt_status='ACCEPTED') THEN
        RAISE EXCEPTION 'session workflow resume requires accepted ExistingRun input';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_session_action_preparations p
         WHERE p.tenant_id=NEW.tenant_id AND p.run_id=NEW.run_id
           AND p.journal_ref=NEW.original_journal_ref AND p.preparation_kind='WORKFLOW') THEN
        RAISE EXCEPTION 'session workflow resume requires original Workflow preparation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION public.assert_agent_runtime_session_cancellation_request()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_runs r
          JOIN public.nw_runtime_identity_verifications v
            ON v.tenant_id=r.tenant_id AND v.verification_ref=NEW.requester_verification_ref
         WHERE r.tenant_id=NEW.tenant_id AND r.target_kind=NEW.target_kind
           AND r.target_id=NEW.target_id AND r.agent_id=NEW.agent_id
           AND r.session_id=NEW.session_id AND r.end_user_id=NEW.end_user_id
           AND r.run_id=NEW.run_id AND r.run_revision=NEW.expected_revision
           AND r.cancel_request_id IS NULL
           AND r.cancel_requested_epoch_second IS NULL AND r.cancel_requested_nano IS NULL
           AND r.status NOT IN('COMPLETED','TERMINATED','FAILED','CANCELLED')
           AND r.record_data=NEW.source_run_data) THEN
        RAISE EXCEPTION 'session cancellation source mismatch';
    END IF;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION public.assert_agent_runtime_session_terminal_failure()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_runs r
         WHERE r.tenant_id=NEW.tenant_id AND r.target_kind=NEW.target_kind
           AND r.target_id=NEW.target_id AND r.agent_id=NEW.agent_id
           AND r.session_id=NEW.session_id AND r.end_user_id=NEW.end_user_id
           AND r.run_id=NEW.run_id AND r.run_revision=NEW.source_run_revision
           AND r.cancel_request_id IS NULL
           AND r.cancel_requested_epoch_second IS NULL AND r.cancel_requested_nano IS NULL
           AND r.status NOT IN('COMPLETED','TERMINATED','FAILED','CANCELLED')
           AND r.record_data=NEW.source_run_data) THEN
        RAISE EXCEPTION 'session terminal failure source mismatch';
    END IF;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION public.assert_agent_runtime_session_tool_input_response_closed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM public.agent_runtime_input_ledger i
          JOIN public.agent_runtime_session_inbox inbox
            ON inbox.tenant_id = i.tenant_id
           AND inbox.target_kind = i.target_kind
           AND inbox.target_id = i.target_id
           AND inbox.agent_id = i.agent_id
           AND inbox.session_id = i.session_id
           AND inbox.end_user_id = i.end_user_id
           AND inbox.input_id = i.input_id
          JOIN public.agent_runtime_runs r
            ON r.tenant_id = i.tenant_id AND r.run_id = i.run_id
           AND r.target_kind = i.target_kind AND r.target_id = i.target_id
           AND r.agent_id = i.agent_id AND r.session_id = i.session_id
           AND r.end_user_id = i.end_user_id
          JOIN public.nw_runtime_tool_input_waits w
            ON w.tenant_id = i.tenant_id AND w.action_id = NEW.action_id
           AND w.invocation_id = NEW.invocation_id
           AND w.wait_revision = NEW.wait_revision
           AND w.pending_id = NEW.pending_id AND w.wake_id = NEW.wake_id
          JOIN public.agent_runtime_actions a
            ON a.tenant_id = w.tenant_id AND a.action_id = w.action_id
           AND a.invocation_id = w.invocation_id
           AND a.target_kind = i.target_kind AND a.target_id = i.target_id
           AND a.agent_id = i.agent_id AND a.session_id = i.session_id
           AND a.end_user_id = i.end_user_id AND a.run_id = i.run_id
         WHERE i.tenant_id = NEW.tenant_id
           AND i.target_kind = NEW.target_kind AND i.target_id = NEW.target_id
           AND i.agent_id = NEW.agent_id
           AND i.session_id = NEW.session_id
           AND i.end_user_id = NEW.end_user_id
           AND i.input_id = NEW.input_id AND i.run_id = NEW.run_id
           AND i.request_fingerprint = NEW.request_fingerprint
           AND i.decision_kind = 'ACCEPTED_EXISTING_RUN'
           AND i.receipt_status = 'ACCEPTED'
           AND inbox.status = 'PROCESSING'
           AND a.state = 'WAITING_INPUT'
           AND a.record_data = w.action_data) THEN
        RAISE EXCEPTION 'Session Tool input response sources are not closed';
    END IF;
    RETURN NULL;
END;
$$;
