-- 新执行主链的目标数据。旧执行事实保持只读保留边界；本迁移不改写或删除旧表。
CREATE TABLE public.agent_runtime_execution_sessions (
    tenant_id text NOT NULL, session_id text NOT NULL,
    target_kind varchar(32) NOT NULL, target_id text NOT NULL, end_user_id bigint NOT NULL,
    last_event_sequence bigint NOT NULL DEFAULT 0,
    CONSTRAINT execution_sessions_pk PRIMARY KEY (tenant_id,session_id),
    CONSTRAINT execution_sessions_owner_uq UNIQUE (tenant_id,session_id,target_kind,target_id,end_user_id),
    CONSTRAINT execution_sessions_identity_ck CHECK (btrim(tenant_id)<>'' AND btrim(session_id)<>'' AND btrim(target_id)<>'' AND end_user_id>0),
    CONSTRAINT execution_sessions_kind_ck CHECK (target_kind IN ('AGENT','TEAM')),
    CONSTRAINT execution_sessions_sequence_ck CHECK (last_event_sequence>=0)
);
CREATE TABLE public.agent_runtime_execution_materials (
    tenant_id text NOT NULL, reference text NOT NULL, schema_id text NOT NULL, canonical_json text NOT NULL,
    CONSTRAINT execution_materials_pk PRIMARY KEY (tenant_id,reference),
    CONSTRAINT execution_materials_content_ck CHECK (btrim(tenant_id)<>'' AND btrim(reference)<>'' AND btrim(schema_id)<>'' AND btrim(canonical_json)<>'')
);
CREATE TABLE public.agent_runtime_execution_runs (
    tenant_id text NOT NULL, run_id varchar(160) NOT NULL, session_id text NOT NULL,
    target_kind varchar(32) NOT NULL, target_id text NOT NULL, end_user_id bigint NOT NULL, agent_id text NOT NULL,
    input_id varchar(160) NOT NULL, trace_id text NOT NULL, span_id text NOT NULL, root_input_id text NOT NULL,
    caused_by_event_id text, identity_ref text NOT NULL, actor_verification_ref text NOT NULL, configuration_ref text NOT NULL,
    input_ref text NOT NULL, initial_context_ref text NOT NULL, admission_fingerprint text NOT NULL,
    status varchar(32) NOT NULL, phase varchar(32) NOT NULL, revision bigint NOT NULL, fence bigint NOT NULL DEFAULT 0,
    cancelled boolean NOT NULL DEFAULT false, decisions bigint NOT NULL DEFAULT 0, tool_calls bigint NOT NULL DEFAULT 0,
    knowledge_calls bigint NOT NULL DEFAULT 0, context_ref text NOT NULL, current_action_id varchar(160),
    waiting_action_id varchar(160), waiting_generation bigint,
    batch_source_attempt text, batch_size integer,
    lease_worker_id text, lease_claim_id text, lease_until_second bigint, lease_until_nano integer,
    next_due_second bigint, next_due_nano integer,
    created_second bigint NOT NULL, created_nano integer NOT NULL, updated_second bigint NOT NULL, updated_nano integer NOT NULL,
    CONSTRAINT execution_runs_pk PRIMARY KEY (tenant_id,run_id),
    CONSTRAINT execution_runs_session_uq UNIQUE (tenant_id,run_id,session_id),
    CONSTRAINT execution_runs_full_owner_uq UNIQUE (tenant_id,target_kind,target_id,agent_id,session_id,end_user_id,run_id),
    CONSTRAINT execution_runs_input_uq UNIQUE (tenant_id,session_id,target_kind,target_id,input_id),
    CONSTRAINT execution_runs_owner_fk FOREIGN KEY (tenant_id,session_id,target_kind,target_id,end_user_id)
        REFERENCES public.agent_runtime_execution_sessions(tenant_id,session_id,target_kind,target_id,end_user_id),
    CONSTRAINT execution_runs_input_fk FOREIGN KEY (tenant_id,input_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id,reference),
    CONSTRAINT execution_runs_initial_context_fk FOREIGN KEY (tenant_id,initial_context_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id,reference),
    CONSTRAINT execution_runs_context_fk FOREIGN KEY (tenant_id,context_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id,reference),
    CONSTRAINT execution_runs_identity_ck CHECK (btrim(run_id)<>'' AND btrim(agent_id)<>'' AND btrim(input_id)<>'' AND btrim(trace_id)<>'' AND btrim(span_id)<>'' AND btrim(root_input_id)<>'' AND btrim(identity_ref)<>'' AND btrim(actor_verification_ref)<>'' AND btrim(configuration_ref)<>'' AND btrim(admission_fingerprint)<>''),
    CONSTRAINT execution_runs_status_ck CHECK (status IN ('QUEUED','RUNNING','SUSPENDED','RESUMING','COMPLETED','TERMINATED','FAILED','CANCELLED')),
    CONSTRAINT execution_runs_phase_ck CHECK (phase IN ('READY','AWAITING_RESULT','WAITING','UNCERTAIN','FINISHED') AND (phase='FINISHED')=(status IN ('COMPLETED','TERMINATED','FAILED','CANCELLED'))),
    CONSTRAINT execution_runs_counts_ck CHECK (revision>0 AND fence>=0 AND decisions>=0 AND tool_calls>=0 AND knowledge_calls>=0),
    CONSTRAINT execution_runs_lease_ck CHECK ((lease_claim_id IS NULL AND lease_worker_id IS NULL AND lease_until_second IS NULL AND lease_until_nano IS NULL)
        OR (lease_claim_id IS NOT NULL AND btrim(lease_claim_id)<>'' AND lease_worker_id IS NOT NULL AND btrim(lease_worker_id)<>'' AND fence>0 AND lease_until_second IS NOT NULL AND lease_until_nano IS NOT NULL AND lease_until_nano BETWEEN 0 AND 999999999)),
    CONSTRAINT execution_runs_terminal_lease_ck CHECK (phase NOT IN ('FINISHED','WAITING','UNCERTAIN') OR (lease_claim_id IS NULL AND next_due_second IS NULL)),
    CONSTRAINT execution_runs_due_ck CHECK ((next_due_second IS NULL AND next_due_nano IS NULL) OR (next_due_second IS NOT NULL AND next_due_nano IS NOT NULL AND next_due_nano BETWEEN 0 AND 999999999)),
    CONSTRAINT execution_runs_action_phase_ck CHECK ((phase<>'READY' OR current_action_id IS NULL OR waiting_action_id IS NOT NULL)
        AND (phase NOT IN ('AWAITING_RESULT','WAITING','UNCERTAIN') OR current_action_id IS NOT NULL OR batch_source_attempt IS NOT NULL)),
    CONSTRAINT execution_runs_batch_ck CHECK ((batch_source_attempt IS NULL)=(batch_size IS NULL)
        AND (batch_source_attempt IS NULL OR (btrim(batch_source_attempt)<>'' AND batch_size>=2 AND current_action_id IS NULL))),
    CONSTRAINT execution_runs_wait_ck CHECK ((waiting_action_id IS NULL)=(waiting_generation IS NULL)
        AND (waiting_generation IS NULL OR (waiting_generation>0 AND current_action_id=waiting_action_id))
        AND (phase<>'WAITING' OR waiting_action_id IS NOT NULL OR batch_source_attempt IS NOT NULL)),
    CONSTRAINT execution_runs_time_ck CHECK (created_nano BETWEEN 0 AND 999999999 AND updated_nano BETWEEN 0 AND 999999999 AND (updated_second,updated_nano)>=(created_second,created_nano))
);
CREATE INDEX execution_runs_due_idx ON public.agent_runtime_execution_runs(tenant_id,next_due_second,next_due_nano,run_id)
    WHERE phase IN ('READY','AWAITING_RESULT') AND NOT cancelled AND next_due_second IS NOT NULL;
CREATE TABLE public.agent_runtime_execution_actions (
    tenant_id text NOT NULL, action_id varchar(160) NOT NULL, run_id varchar(160) NOT NULL,
    action_ordinal bigint NOT NULL, kind varchar(32) NOT NULL, target_handle text NOT NULL,
    capability_ref text NOT NULL, request_ref text NOT NULL, fingerprint text NOT NULL,
    status varchar(32) NOT NULL, current_attempt text NOT NULL, selected_attempt text,
    batch_source_attempt text, batch_size integer, batch_slot integer,
    CONSTRAINT execution_actions_pk PRIMARY KEY (tenant_id,action_id),
    CONSTRAINT execution_actions_run_uq UNIQUE (tenant_id,action_id,run_id),
    CONSTRAINT execution_actions_ordinal_uq UNIQUE (tenant_id,run_id,action_ordinal),
    CONSTRAINT execution_actions_batch_slot_uq UNIQUE (tenant_id,run_id,batch_source_attempt,batch_slot),
    CONSTRAINT execution_actions_run_fk FOREIGN KEY (tenant_id,run_id) REFERENCES public.agent_runtime_execution_runs(tenant_id,run_id),
    CONSTRAINT execution_actions_request_fk FOREIGN KEY (tenant_id,request_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id,reference),
    CONSTRAINT execution_actions_identity_ck CHECK (btrim(action_id)<>'' AND action_ordinal>0 AND btrim(target_handle)<>'' AND btrim(capability_ref)<>'' AND btrim(fingerprint)<>''),
    CONSTRAINT execution_actions_kind_ck CHECK (kind IN ('MODEL','TOOL','KNOWLEDGE','WORKFLOW','DELEGATION')),
    CONSTRAINT execution_actions_batch_ck CHECK ((batch_source_attempt IS NULL AND batch_size IS NULL AND batch_slot IS NULL)
        OR (batch_source_attempt IS NOT NULL AND btrim(batch_source_attempt)<>'' AND batch_size IS NOT NULL AND batch_size>=2
            AND batch_slot IS NOT NULL AND batch_slot BETWEEN 1 AND batch_size AND kind='TOOL')),
    CONSTRAINT execution_actions_status_ck CHECK (status IN ('DISPATCHED','SUCCEEDED','FAILED','UNKNOWN','WAITING_INPUT')),
    CONSTRAINT execution_actions_selected_ck CHECK ((status NOT IN ('DISPATCHED','WAITING_INPUT') OR selected_attempt IS NULL)
        AND (status NOT IN ('SUCCEEDED','FAILED') OR selected_attempt IS NOT NULL))
);
CREATE TABLE public.agent_runtime_execution_attempts (
    tenant_id text NOT NULL, attempt_id text NOT NULL, action_id varchar(160) NOT NULL, run_id varchar(160) NOT NULL,
    fence bigint NOT NULL, request_ref text NOT NULL, fingerprint text NOT NULL, continuation_generation bigint,
    result_kind varchar(32), result_ref text, uncertainty_ref text, feedback_ref text, external_operation text,
    adopted boolean NOT NULL DEFAULT false,
    CONSTRAINT execution_attempts_pk PRIMARY KEY (tenant_id,attempt_id),
    CONSTRAINT execution_attempts_action_uq UNIQUE (tenant_id,action_id,attempt_id),
    CONSTRAINT execution_attempts_run_uq UNIQUE (tenant_id,run_id,attempt_id),
    CONSTRAINT execution_attempts_action_fk FOREIGN KEY (tenant_id,action_id,run_id) REFERENCES public.agent_runtime_execution_actions(tenant_id,action_id,run_id),
    CONSTRAINT execution_attempts_request_fk FOREIGN KEY (tenant_id,request_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id,reference),
    CONSTRAINT execution_attempts_result_fk FOREIGN KEY (tenant_id,result_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id,reference),
    CONSTRAINT execution_attempts_uncertainty_fk FOREIGN KEY (tenant_id,uncertainty_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id,reference),
    CONSTRAINT execution_attempts_feedback_fk FOREIGN KEY (tenant_id,feedback_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id,reference),
    CONSTRAINT execution_attempts_budget_fk FOREIGN KEY (tenant_id,attempt_id) REFERENCES public.agent_runtime_budget_attempts(tenant_id,attempt_id),
    CONSTRAINT execution_attempts_identity_ck CHECK (btrim(attempt_id)<>'' AND fence>0 AND btrim(fingerprint)<>'' AND (continuation_generation IS NULL OR continuation_generation>0)),
    CONSTRAINT execution_attempts_result_ck CHECK ((result_kind IS NULL)=(result_ref IS NULL) AND (result_kind IS NULL OR result_kind IN ('SUCCEEDED','FAILED','UNKNOWN','INPUT_REQUIRED')) AND (NOT adopted OR result_ref IS NOT NULL)
        AND (feedback_ref IS NULL OR result_ref IS NOT NULL) AND (uncertainty_ref IS NULL OR result_ref IS NOT NULL))
);
ALTER TABLE public.agent_runtime_execution_actions ADD CONSTRAINT execution_actions_selected_fk
    FOREIGN KEY (tenant_id,action_id,selected_attempt) REFERENCES public.agent_runtime_execution_attempts(tenant_id,action_id,attempt_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE public.agent_runtime_execution_actions ADD CONSTRAINT execution_actions_current_attempt_fk
    FOREIGN KEY (tenant_id,action_id,current_attempt) REFERENCES public.agent_runtime_execution_attempts(tenant_id,action_id,attempt_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE public.agent_runtime_execution_runs ADD CONSTRAINT execution_runs_current_action_fk
    FOREIGN KEY (tenant_id,current_action_id,run_id) REFERENCES public.agent_runtime_execution_actions(tenant_id,action_id,run_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE public.agent_runtime_execution_runs ADD CONSTRAINT execution_runs_batch_source_fk
    FOREIGN KEY (tenant_id,run_id,batch_source_attempt) REFERENCES public.agent_runtime_execution_attempts(tenant_id,run_id,attempt_id);
ALTER TABLE public.agent_runtime_execution_actions ADD CONSTRAINT execution_actions_batch_source_fk
    FOREIGN KEY (tenant_id,run_id,batch_source_attempt) REFERENCES public.agent_runtime_execution_attempts(tenant_id,run_id,attempt_id);
CREATE TABLE public.agent_runtime_execution_batch_members (
    tenant_id text NOT NULL,run_id varchar(160) NOT NULL,source_attempt text NOT NULL,slot integer NOT NULL,size integer NOT NULL,
    action_id varchar(160) NOT NULL,first_attempt_id text NOT NULL,reused boolean NOT NULL,reused_result_ref text,reused_feedback_ref text,
    CONSTRAINT execution_batch_members_pk PRIMARY KEY(tenant_id,run_id,source_attempt,slot),
    CONSTRAINT execution_batch_members_action_uq UNIQUE(tenant_id,run_id,source_attempt,action_id),
    CONSTRAINT execution_batch_members_source_fk FOREIGN KEY(tenant_id,run_id,source_attempt) REFERENCES public.agent_runtime_execution_attempts(tenant_id,run_id,attempt_id),
    CONSTRAINT execution_batch_members_action_fk FOREIGN KEY(tenant_id,action_id,run_id) REFERENCES public.agent_runtime_execution_actions(tenant_id,action_id,run_id),
    CONSTRAINT execution_batch_members_attempt_fk FOREIGN KEY(tenant_id,action_id,first_attempt_id) REFERENCES public.agent_runtime_execution_attempts(tenant_id,action_id,attempt_id),
    CONSTRAINT execution_batch_members_result_fk FOREIGN KEY(tenant_id,reused_result_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id,reference),
    CONSTRAINT execution_batch_members_feedback_fk FOREIGN KEY(tenant_id,reused_feedback_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id,reference),
    CONSTRAINT execution_batch_members_slot_ck CHECK(size BETWEEN 2 AND 8 AND slot BETWEEN 1 AND size),
    CONSTRAINT execution_batch_members_reused_ck CHECK((reused AND reused_result_ref IS NOT NULL AND reused_feedback_ref IS NOT NULL)
        OR (NOT reused AND reused_result_ref IS NULL AND reused_feedback_ref IS NULL))
);
CREATE TABLE public.agent_runtime_execution_waits (
    tenant_id text NOT NULL, run_id varchar(160) NOT NULL, action_id varchar(160) NOT NULL, generation bigint NOT NULL,
    kind varchar(32) NOT NULL, source_attempt text NOT NULL, input_id varchar(160), response_ref text, fingerprint text, actor_ref text,
    accepted_revision bigint, continuation_attempt text, consumed_revision bigint, declared_revision bigint NOT NULL CHECK(declared_revision>0),
    business_wait_id text, execution_id text, suspend_ref text, action_key text, execution_revision bigint,
    plan_id text, plan_revision bigint,
    CONSTRAINT execution_waits_pk PRIMARY KEY (tenant_id,action_id,generation),
    CONSTRAINT execution_waits_run_uq UNIQUE (tenant_id,run_id,action_id,generation),
    CONSTRAINT execution_waits_input_uq UNIQUE (tenant_id,run_id,input_id),
    CONSTRAINT execution_waits_source_uq UNIQUE (tenant_id,source_attempt),
    CONSTRAINT execution_waits_action_fk FOREIGN KEY (tenant_id,action_id,run_id) REFERENCES public.agent_runtime_execution_actions(tenant_id,action_id,run_id),
    CONSTRAINT execution_waits_source_fk FOREIGN KEY (tenant_id,action_id,source_attempt) REFERENCES public.agent_runtime_execution_attempts(tenant_id,action_id,attempt_id),
    CONSTRAINT execution_waits_response_fk FOREIGN KEY (tenant_id,response_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id,reference),
    CONSTRAINT execution_waits_continuation_fk FOREIGN KEY (tenant_id,action_id,continuation_attempt) REFERENCES public.agent_runtime_execution_attempts(tenant_id,action_id,attempt_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT execution_waits_identity_ck CHECK (generation>0 AND btrim(source_attempt)<>''),
    CONSTRAINT execution_waits_kind_ck CHECK (kind IN ('TOOL','HUMAN','DELEGATION','WORKFLOW','PLAN')),
    CONSTRAINT execution_waits_internal_ck CHECK (
        (kind IN ('TOOL','HUMAN') AND business_wait_id IS NULL AND execution_id IS NULL AND suspend_ref IS NULL AND action_key IS NULL AND execution_revision IS NULL AND plan_id IS NULL AND plan_revision IS NULL)
        OR (kind='DELEGATION' AND business_wait_id IS NOT NULL AND btrim(business_wait_id)<>'' AND execution_id IS NULL AND suspend_ref IS NULL AND action_key IS NULL AND execution_revision IS NULL AND plan_id IS NULL AND plan_revision IS NULL)
        OR (kind='WORKFLOW' AND business_wait_id IS NULL AND execution_id IS NOT NULL AND btrim(execution_id)<>'' AND suspend_ref IS NOT NULL AND btrim(suspend_ref)<>''
            AND action_key IS NOT NULL AND btrim(action_key)<>'' AND execution_revision IS NOT NULL AND execution_revision>0 AND plan_id IS NULL AND plan_revision IS NULL)
        OR (kind='PLAN' AND business_wait_id IS NOT NULL AND btrim(business_wait_id)<>'' AND plan_id IS NOT NULL AND btrim(plan_id)<>'' AND plan_revision IS NOT NULL AND plan_revision>0
            AND execution_id IS NULL AND suspend_ref IS NULL AND action_key IS NULL AND execution_revision IS NULL)),
    CONSTRAINT execution_waits_accepted_ck CHECK ((input_id IS NULL AND response_ref IS NULL AND fingerprint IS NULL AND actor_ref IS NULL AND accepted_revision IS NULL)
        OR (input_id IS NOT NULL AND btrim(input_id)<>'' AND response_ref IS NOT NULL AND fingerprint IS NOT NULL AND btrim(fingerprint)<>''
            AND actor_ref IS NOT NULL AND btrim(actor_ref)<>'' AND accepted_revision IS NOT NULL AND accepted_revision>0)),
    CONSTRAINT execution_waits_consumed_ck CHECK ((continuation_attempt IS NULL OR (kind IN ('TOOL','WORKFLOW') AND input_id IS NOT NULL AND btrim(continuation_attempt)<>''))
        AND (consumed_revision IS NULL OR (kind IN ('HUMAN','DELEGATION','PLAN') AND input_id IS NOT NULL AND consumed_revision>accepted_revision)))
);
CREATE TABLE public.agent_runtime_execution_wait_children (
    tenant_id text NOT NULL, action_id varchar(160) NOT NULL, generation bigint NOT NULL, ordinal integer NOT NULL,
    delegation_id text NOT NULL, child_run_id varchar(160) NOT NULL,
    CONSTRAINT execution_wait_children_pk PRIMARY KEY (tenant_id,action_id,generation,ordinal),
    CONSTRAINT execution_wait_children_entry_uq UNIQUE (tenant_id,action_id,generation,delegation_id),
    CONSTRAINT execution_wait_children_run_uq UNIQUE (tenant_id,action_id,generation,child_run_id),
    CONSTRAINT execution_wait_children_wait_fk FOREIGN KEY (tenant_id,action_id,generation) REFERENCES public.agent_runtime_execution_waits(tenant_id,action_id,generation),
    CONSTRAINT execution_wait_children_run_fk FOREIGN KEY (tenant_id,child_run_id) REFERENCES public.agent_runtime_execution_runs(tenant_id,run_id),
    CONSTRAINT execution_wait_children_identity_ck CHECK (ordinal>0 AND btrim(delegation_id)<>'')
);
CREATE TABLE public.agent_runtime_execution_wait_requests (
    tenant_id text NOT NULL, action_id varchar(160) NOT NULL, generation bigint NOT NULL, input_key text NOT NULL, method varchar(64) NOT NULL,
    CONSTRAINT execution_wait_requests_pk PRIMARY KEY (tenant_id,action_id,generation,input_key),
    CONSTRAINT execution_wait_requests_wait_fk FOREIGN KEY (tenant_id,action_id,generation) REFERENCES public.agent_runtime_execution_waits(tenant_id,action_id,generation),
    CONSTRAINT execution_wait_requests_key_ck CHECK (btrim(input_key)<>''),
    CONSTRAINT execution_wait_requests_method_ck CHECK (method IN ('elicitation/create','sampling/createMessage','roots/list'))
);
ALTER TABLE public.agent_runtime_execution_runs ADD CONSTRAINT execution_runs_wait_fk
    FOREIGN KEY (tenant_id,run_id,waiting_action_id,waiting_generation) REFERENCES public.agent_runtime_execution_waits(tenant_id,run_id,action_id,generation) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE public.agent_runtime_execution_attempts ADD CONSTRAINT execution_attempts_continuation_fk
    FOREIGN KEY (tenant_id,action_id,continuation_generation) REFERENCES public.agent_runtime_execution_waits(tenant_id,action_id,generation);
CREATE TABLE public.agent_runtime_execution_child_runs (
    tenant_id text NOT NULL, parent_run_id varchar(160) NOT NULL, parent_action_id varchar(160) NOT NULL,
    parent_attempt_id text NOT NULL, child_run_id varchar(160) NOT NULL, origin_kind varchar(32) NOT NULL,
    delegation_id text, execution_id text, node_id text, node_visit integer, node_call integer,
    checkpoint_fingerprint text, source_revision bigint, source_fence bigint,
    CONSTRAINT execution_child_runs_pk PRIMARY KEY (tenant_id,parent_action_id,child_run_id),
    CONSTRAINT execution_child_runs_parent_fk FOREIGN KEY (tenant_id,parent_action_id,parent_run_id) REFERENCES public.agent_runtime_execution_actions(tenant_id,action_id,run_id),
    CONSTRAINT execution_child_runs_attempt_fk FOREIGN KEY (tenant_id,parent_action_id,parent_attempt_id) REFERENCES public.agent_runtime_execution_attempts(tenant_id,action_id,attempt_id),
    CONSTRAINT execution_child_runs_child_fk FOREIGN KEY (tenant_id,child_run_id) REFERENCES public.agent_runtime_execution_runs(tenant_id,run_id),
    CONSTRAINT execution_child_runs_distinct_ck CHECK (parent_run_id<>child_run_id),
    CONSTRAINT execution_child_runs_origin_ck CHECK ((origin_kind='DELEGATION' AND delegation_id IS NOT NULL AND btrim(delegation_id)<>''
        AND execution_id IS NULL AND node_id IS NULL AND node_visit IS NULL AND node_call IS NULL AND checkpoint_fingerprint IS NULL AND source_revision IS NULL AND source_fence IS NULL)
        OR (origin_kind='WORKFLOW_NODE' AND delegation_id IS NULL AND execution_id IS NOT NULL AND btrim(execution_id)<>'' AND node_id IS NOT NULL
            AND btrim(node_id)<>'' AND node_visit IS NOT NULL AND node_visit>0 AND node_call IS NOT NULL AND node_call>0 AND checkpoint_fingerprint IS NOT NULL AND btrim(checkpoint_fingerprint)<>''
            AND source_revision IS NOT NULL AND source_revision>0 AND source_fence IS NOT NULL AND source_fence>0))
);
CREATE INDEX execution_child_runs_child_idx ON public.agent_runtime_execution_child_runs(tenant_id,child_run_id);
-- Workflow Execution仍是自己的业务权威；新写完整owner必须属于新Run，历史事实只读保留。
DO $$ BEGIN
    IF to_regclass('public.agent_runtime_executions') IS NOT NULL THEN
        ALTER TABLE public.agent_runtime_executions DROP CONSTRAINT IF EXISTS agent_runtime_execution_run_fkey;
        ALTER TABLE public.agent_runtime_executions ADD CONSTRAINT agent_runtime_execution_run_fkey
            FOREIGN KEY (tenant_id,target_kind,target_id,agent_id,session_id,end_user_id,run_id)
            REFERENCES public.agent_runtime_execution_runs(tenant_id,target_kind,target_id,agent_id,session_id,end_user_id,run_id)
            ON DELETE RESTRICT NOT VALID;
        IF NOT EXISTS (SELECT 1 FROM public.agent_runtime_executions e WHERE NOT EXISTS (
            SELECT 1 FROM public.agent_runtime_execution_runs r WHERE r.tenant_id=e.tenant_id AND r.target_kind=e.target_kind
              AND r.target_id=e.target_id AND r.agent_id=e.agent_id AND r.session_id=e.session_id AND r.end_user_id=e.end_user_id AND r.run_id=e.run_id)) THEN
            ALTER TABLE public.agent_runtime_executions VALIDATE CONSTRAINT agent_runtime_execution_run_fkey;
        END IF;
    END IF;
END $$;
-- DAG容量仍由原领域permit拥有；释放触发器改订阅新Run的真实挂起/终态，不继续监听旧执行格式。
DO $$ BEGIN
    IF to_regclass('public.agent_runtime_dag_permits') IS NOT NULL
            AND to_regprocedure('public.release_dag_capacity_on_run_yield()') IS NOT NULL THEN
        IF to_regclass('public.agent_runtime_runs') IS NOT NULL THEN
            DROP TRIGGER IF EXISTS agent_runtime_dag_yield ON public.agent_runtime_runs;
        END IF;
        CREATE TRIGGER agent_runtime_dag_yield AFTER UPDATE OF status ON public.agent_runtime_execution_runs
            FOR EACH ROW EXECUTE FUNCTION public.release_dag_capacity_on_run_yield();
    END IF;
END $$;
CREATE TABLE public.agent_runtime_execution_events (
    tenant_id text NOT NULL, session_id text NOT NULL, run_id varchar(160) NOT NULL, event_sequence bigint NOT NULL,
    revision bigint NOT NULL, event_id text NOT NULL, type text NOT NULL, schema_id text NOT NULL, canonical_json text NOT NULL,
    occurred_second bigint NOT NULL, occurred_nano integer NOT NULL,
    CONSTRAINT execution_events_pk PRIMARY KEY (tenant_id,session_id,event_sequence),
    CONSTRAINT execution_events_identity_uq UNIQUE (tenant_id,session_id,event_id),
    CONSTRAINT execution_events_run_fk FOREIGN KEY (tenant_id,run_id,session_id) REFERENCES public.agent_runtime_execution_runs(tenant_id,run_id,session_id),
    CONSTRAINT execution_events_content_ck CHECK (event_sequence>0 AND revision>0 AND btrim(event_id)<>'' AND btrim(type)<>'' AND btrim(schema_id)<>'' AND btrim(canonical_json)<>'' AND occurred_nano BETWEEN 0 AND 999999999)
);
CREATE INDEX execution_events_run_idx ON public.agent_runtime_execution_events(tenant_id,run_id,revision,event_sequence);
CREATE TABLE public.agent_runtime_execution_supplements (
    tenant_id text NOT NULL, run_id varchar(160) NOT NULL, input_id varchar(256) NOT NULL,
    content_ref text NOT NULL, fingerprint text NOT NULL, actor_ref text NOT NULL,
    ordinal bigint NOT NULL, applied_revision bigint,
    CONSTRAINT execution_supplements_pk PRIMARY KEY (tenant_id,run_id,input_id),
    CONSTRAINT execution_supplements_ordinal_uq UNIQUE (tenant_id,run_id,ordinal),
    CONSTRAINT execution_supplements_run_fk FOREIGN KEY (tenant_id,run_id)
        REFERENCES public.agent_runtime_execution_runs(tenant_id,run_id),
    CONSTRAINT execution_supplements_content_fk FOREIGN KEY (tenant_id,content_ref)
        REFERENCES public.agent_runtime_execution_materials(tenant_id,reference),
    CONSTRAINT execution_supplements_identity_ck CHECK (btrim(input_id)<>'' AND btrim(fingerprint)<>'' AND btrim(actor_ref)<>''),
    CONSTRAINT execution_supplements_revision_ck CHECK (ordinal>0 AND (applied_revision IS NULL OR applied_revision>0))
);
CREATE INDEX execution_supplements_pending_idx ON public.agent_runtime_execution_supplements(tenant_id,run_id,ordinal)
    WHERE applied_revision IS NULL;
CREATE TABLE public.agent_runtime_execution_plan_controls (
    tenant_id text NOT NULL, run_id varchar(160) NOT NULL, source_action_id varchar(160) NOT NULL,
    source_attempt_id text NOT NULL, request_ref text NOT NULL, result_ref text NOT NULL, adopted_revision bigint NOT NULL,
    CONSTRAINT execution_plan_controls_pk PRIMARY KEY (tenant_id,source_action_id),
    CONSTRAINT execution_plan_controls_action_fk FOREIGN KEY (tenant_id,source_action_id,run_id)
        REFERENCES public.agent_runtime_execution_actions(tenant_id,action_id,run_id),
    CONSTRAINT execution_plan_controls_attempt_fk FOREIGN KEY (tenant_id,source_action_id,source_attempt_id)
        REFERENCES public.agent_runtime_execution_attempts(tenant_id,action_id,attempt_id),
    CONSTRAINT execution_plan_controls_request_fk FOREIGN KEY (tenant_id,request_ref)
        REFERENCES public.agent_runtime_execution_materials(tenant_id,reference),
    CONSTRAINT execution_plan_controls_result_fk FOREIGN KEY (tenant_id,result_ref)
        REFERENCES public.agent_runtime_execution_materials(tenant_id,reference),
    CONSTRAINT execution_plan_controls_revision_ck CHECK (adopted_revision>0)
);
CREATE INDEX execution_plan_controls_run_idx ON public.agent_runtime_execution_plan_controls(tenant_id,run_id);
CREATE TABLE public.agent_runtime_execution_plan_child_runs (
    tenant_id text NOT NULL,parent_run_id varchar(160) NOT NULL,child_run_id varchar(160) NOT NULL,
    work_session_id text NOT NULL,root_input_id text NOT NULL,task_id text NOT NULL,admission_id text NOT NULL,
    plan_revision bigint NOT NULL,permit_fence bigint NOT NULL,
    CONSTRAINT execution_plan_child_runs_pk PRIMARY KEY (tenant_id,child_run_id),
    CONSTRAINT execution_plan_child_runs_admission_uq UNIQUE (tenant_id,admission_id),
    CONSTRAINT execution_plan_child_runs_parent_fk FOREIGN KEY (tenant_id,parent_run_id)
        REFERENCES public.agent_runtime_execution_runs(tenant_id,run_id),
    CONSTRAINT execution_plan_child_runs_child_fk FOREIGN KEY (tenant_id,child_run_id)
        REFERENCES public.agent_runtime_execution_runs(tenant_id,run_id),
    CONSTRAINT execution_plan_child_runs_identity_ck CHECK (parent_run_id<>child_run_id AND btrim(work_session_id)<>''
        AND btrim(root_input_id)<>'' AND btrim(task_id)<>'' AND btrim(admission_id)<>'' AND plan_revision>0 AND permit_fence>0)
);
CREATE INDEX execution_plan_child_runs_parent_idx ON public.agent_runtime_execution_plan_child_runs(tenant_id,parent_run_id);
CREATE TABLE public.agent_runtime_execution_program_child_runs (
    tenant_id text NOT NULL,parent_run_id varchar(160) NOT NULL,parent_action_id varchar(160) NOT NULL,parent_attempt_id text NOT NULL,
    ordinal integer NOT NULL,child_run_id varchar(160) NOT NULL,
    CONSTRAINT execution_program_child_runs_pk PRIMARY KEY(tenant_id,parent_run_id,parent_action_id,parent_attempt_id,ordinal),
    CONSTRAINT execution_program_child_runs_child_uq UNIQUE(tenant_id,child_run_id),
    CONSTRAINT execution_program_child_runs_parent_fk FOREIGN KEY(tenant_id,parent_action_id,parent_run_id) REFERENCES public.agent_runtime_execution_actions(tenant_id,action_id,run_id),
    CONSTRAINT execution_program_child_runs_attempt_fk FOREIGN KEY(tenant_id,parent_action_id,parent_attempt_id) REFERENCES public.agent_runtime_execution_attempts(tenant_id,action_id,attempt_id),
    CONSTRAINT execution_program_child_runs_child_fk FOREIGN KEY(tenant_id,child_run_id) REFERENCES public.agent_runtime_execution_runs(tenant_id,run_id),
    CONSTRAINT execution_program_child_runs_ordinal_ck CHECK(ordinal BETWEEN 1 AND 1024)
);
CREATE TABLE public.agent_runtime_execution_workflow_callback_deliveries (
    tenant_id text NOT NULL,delivery_id text NOT NULL,run_id varchar(160) NOT NULL,
    action_id varchar(160) NOT NULL,generation bigint NOT NULL,input_id varchar(160) NOT NULL,
    CONSTRAINT execution_workflow_callback_deliveries_pk PRIMARY KEY (tenant_id,delivery_id),
    CONSTRAINT execution_workflow_callback_wait_fk FOREIGN KEY (tenant_id,run_id,action_id,generation)
        REFERENCES public.agent_runtime_execution_waits(tenant_id,run_id,action_id,generation),
    CONSTRAINT execution_workflow_callback_response_fk FOREIGN KEY (tenant_id,run_id,input_id)
        REFERENCES public.agent_runtime_execution_waits(tenant_id,run_id,input_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT execution_workflow_callback_identity_ck CHECK (btrim(delivery_id)<>'' AND btrim(input_id)<>'' AND generation>0)
);
-- 完整宿主schema已有原DAG admission；纯Store模块夹具未安装该领域，不建立影子admission。
DO $$ BEGIN
    IF to_regclass('public.agent_runtime_dag_admissions') IS NOT NULL THEN
        ALTER TABLE public.agent_runtime_execution_plan_child_runs ADD CONSTRAINT execution_plan_child_runs_admission_fk
            FOREIGN KEY (tenant_id,admission_id,child_run_id)
            REFERENCES public.agent_runtime_dag_admissions(tenant_id,admission_id,run_id);
        COMMENT ON CONSTRAINT execution_plan_child_runs_admission_fk ON public.agent_runtime_execution_plan_child_runs IS 'Plan child只能引用同租户原DAG immutable admission与准确成员Run';
    END IF;
END $$;
DO $$ DECLARE relation_name text; BEGIN
    FOREACH relation_name IN ARRAY ARRAY['agent_runtime_execution_sessions','agent_runtime_execution_materials','agent_runtime_execution_runs',
        'agent_runtime_execution_actions','agent_runtime_execution_attempts','agent_runtime_execution_batch_members','agent_runtime_execution_waits',
        'agent_runtime_execution_wait_requests','agent_runtime_execution_wait_children','agent_runtime_execution_child_runs','agent_runtime_execution_events','agent_runtime_execution_supplements',
        'agent_runtime_execution_plan_controls','agent_runtime_execution_plan_child_runs','agent_runtime_execution_program_child_runs','agent_runtime_execution_workflow_callback_deliveries'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',relation_name);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY',relation_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (tenant_id=COALESCE(current_setting(''app.tenant_id'',true),'''')) WITH CHECK (tenant_id=COALESCE(current_setting(''app.tenant_id'',true),''''))',relation_name);
    END LOOP;
END $$;
COMMENT ON TABLE public.agent_runtime_execution_supplements IS '原Run的稳定补充输入与一次本地context采纳；不创建新Run、外部调用或等待响应';
COMMENT ON COLUMN public.agent_runtime_execution_supplements.tenant_id IS '原Run所属可信租户，事务绑定并强制RLS';
COMMENT ON COLUMN public.agent_runtime_execution_supplements.run_id IS '补充内容所属准确原Run，原因果与root身份不变';
COMMENT ON COLUMN public.agent_runtime_execution_supplements.input_id IS '真实认证入口生成的稳定补充输入幂等身份';
COMMENT ON COLUMN public.agent_runtime_execution_supplements.content_ref IS '原补充文本的不可变Material引用；不得覆盖同键正文';
COMMENT ON COLUMN public.agent_runtime_execution_supplements.fingerprint IS '原补充材料schema与规范正文摘要，同输入不同内容冲突';
COMMENT ON COLUMN public.agent_runtime_execution_supplements.actor_ref IS '首次接受该补充时真实核验主体的引用，不替换Run原因果身份';
COMMENT ON COLUMN public.agent_runtime_execution_supplements.ordinal IS '原Run锁内分配的补充输入接受顺序，不是模型或工具次数';
COMMENT ON COLUMN public.agent_runtime_execution_supplements.applied_revision IS '本地context一次采纳生效的Run版本；不等同已进入模型请求或已消费模型结果';
COMMENT ON CONSTRAINT execution_supplements_pk ON public.agent_runtime_execution_supplements IS '同原Run补充输入幂等唯一';
COMMENT ON CONSTRAINT execution_supplements_ordinal_uq ON public.agent_runtime_execution_supplements IS '原Run内补充顺序唯一';
COMMENT ON CONSTRAINT execution_supplements_run_fk ON public.agent_runtime_execution_supplements IS '补充只能归属同租户已存在的原Run';
COMMENT ON CONSTRAINT execution_supplements_content_fk ON public.agent_runtime_execution_supplements IS '补充必须引用同租户真实不可变材料';
COMMENT ON CONSTRAINT execution_supplements_identity_ck ON public.agent_runtime_execution_supplements IS '真实输入、摘要和认证引用不得空白';
COMMENT ON CONSTRAINT execution_supplements_revision_ck ON public.agent_runtime_execution_supplements IS '补充顺序与已采纳Run版本必须严格为正';
COMMENT ON INDEX public.execution_supplements_pending_idx IS '按准确Run和接受顺序有界获取待采纳补充';
COMMENT ON TABLE public.agent_runtime_execution_plan_controls IS '原成功MODEL决定拥有的一次本地Plan控制回执与context采纳，不创建Provider调用或预算';
COMMENT ON COLUMN public.agent_runtime_execution_plan_controls.tenant_id IS '原Run所属可信租户，强制RLS隔离';
COMMENT ON COLUMN public.agent_runtime_execution_plan_controls.run_id IS '本地Plan控制所属原Run，不新增Run';
COMMENT ON COLUMN public.agent_runtime_execution_plan_controls.source_action_id IS '原成功MODEL Action，也是Plan领域的稳定operationKey';
COMMENT ON COLUMN public.agent_runtime_execution_plan_controls.source_attempt_id IS '原Action准确选定且已采纳的成功MODEL Attempt';
COMMENT ON COLUMN public.agent_runtime_execution_plan_controls.request_ref IS '原模型Plan命令的不可变完整材料，重放必须全值相同';
COMMENT ON COLUMN public.agent_runtime_execution_plan_controls.result_ref IS '所属Plan领域真实业务回执的不可变材料，与context一次提交';
COMMENT ON COLUMN public.agent_runtime_execution_plan_controls.adopted_revision IS '真实业务回执、context和可选PLAN等待一起生效的Run版本';
COMMENT ON CONSTRAINT execution_plan_controls_pk ON public.agent_runtime_execution_plan_controls IS '同原MODEL Action只允许一个本地Plan控制';
COMMENT ON CONSTRAINT execution_plan_controls_action_fk ON public.agent_runtime_execution_plan_controls IS 'Plan控制来源必须是同租户同Run真实Action';
COMMENT ON CONSTRAINT execution_plan_controls_attempt_fk ON public.agent_runtime_execution_plan_controls IS 'Plan控制精确绑定原Action的真实MODEL Attempt';
COMMENT ON CONSTRAINT execution_plan_controls_request_fk ON public.agent_runtime_execution_plan_controls IS '原命令引用同租户不可变材料';
COMMENT ON CONSTRAINT execution_plan_controls_result_fk ON public.agent_runtime_execution_plan_controls IS '实际Plan回执引用同租户不可变材料';
COMMENT ON CONSTRAINT execution_plan_controls_revision_ck ON public.agent_runtime_execution_plan_controls IS '本地Plan采纳的Run版本必须严格为正';
COMMENT ON INDEX public.execution_plan_controls_run_idx IS '按原Run定位本地Plan控制事实与保留引用';
COMMENT ON TABLE public.agent_runtime_execution_plan_child_runs IS '原已批准Plan成员准入与真实child Run的一次受理关系，不借用Provider Attempt或复制DAG绑定';
COMMENT ON COLUMN public.agent_runtime_execution_plan_child_runs.tenant_id IS '父Run与原DAG admission所属可信租户，强制RLS';
COMMENT ON COLUMN public.agent_runtime_execution_plan_child_runs.parent_run_id IS '原工作控制Run；新受理需当前有效且未取消，重放只读原回执';
COMMENT ON COLUMN public.agent_runtime_execution_plan_child_runs.child_run_id IS '原DAG immutable binding指定的唯一MEMBER子Run';
COMMENT ON COLUMN public.agent_runtime_execution_plan_child_runs.work_session_id IS '原工作Session身份，不改为child独立Session';
COMMENT ON COLUMN public.agent_runtime_execution_plan_child_runs.root_input_id IS '原DAG work输入身份，与immutable admission一起核验';
COMMENT ON COLUMN public.agent_runtime_execution_plan_child_runs.task_id IS '原获准成员任务身份，任务正文由DAG admission拥有';
COMMENT ON COLUMN public.agent_runtime_execution_plan_child_runs.admission_id IS '原DAG业务准入幂等身份，不能由新的Provider Attempt代替';
COMMENT ON COLUMN public.agent_runtime_execution_plan_child_runs.plan_revision IS '原immutable binding的已批准Plan版本';
COMMENT ON COLUMN public.agent_runtime_execution_plan_child_runs.permit_fence IS '第一次准入时真实DAG容量许可代数，后来许可变化不改写原受理';
COMMENT ON CONSTRAINT execution_plan_child_runs_pk ON public.agent_runtime_execution_plan_child_runs IS '同租户原child只保存一次Plan来源';
COMMENT ON CONSTRAINT execution_plan_child_runs_admission_uq ON public.agent_runtime_execution_plan_child_runs IS '同业务admission不能受理另一个child';
COMMENT ON CONSTRAINT execution_plan_child_runs_parent_fk ON public.agent_runtime_execution_plan_child_runs IS '父Run必须是同租户真实typed Run';
COMMENT ON CONSTRAINT execution_plan_child_runs_child_fk ON public.agent_runtime_execution_plan_child_runs IS 'child来源与真实typed child同事务生效';
COMMENT ON CONSTRAINT execution_plan_child_runs_identity_ck ON public.agent_runtime_execution_plan_child_runs IS '来源身份、批准版本和首次许可代数完整且有效';
COMMENT ON INDEX public.execution_plan_child_runs_parent_idx IS '按原父Run核对Plan成员受理关系及保留引用';
COMMENT ON TABLE public.agent_runtime_execution_workflow_callback_deliveries IS '原HMAC Workflow delivery与准确typed等待及已接受响应的同事务关系，不造旧Inbox/receipt/operation';
COMMENT ON COLUMN public.agent_runtime_execution_workflow_callback_deliveries.tenant_id IS '原Workflow等待与签名入口所属可信租户，强制RLS';
COMMENT ON COLUMN public.agent_runtime_execution_workflow_callback_deliveries.delivery_id IS '真实已验签原callback delivery身份';
COMMENT ON COLUMN public.agent_runtime_execution_workflow_callback_deliveries.run_id IS '原Workflow能力所属typed Run';
COMMENT ON COLUMN public.agent_runtime_execution_workflow_callback_deliveries.action_id IS '产生精确Workflow等待的原Action';
COMMENT ON COLUMN public.agent_runtime_execution_workflow_callback_deliveries.generation IS '原Workflow等待代数，不用Run版本推测';
COMMENT ON COLUMN public.agent_runtime_execution_workflow_callback_deliveries.input_id IS '原HMAC入口稳定InputId，提交时必须是该精确等待已接受的真实响应';
COMMENT ON CONSTRAINT execution_workflow_callback_deliveries_pk ON public.agent_runtime_execution_workflow_callback_deliveries IS '同原delivery只能确认一次Workflow等待';
COMMENT ON CONSTRAINT execution_workflow_callback_wait_fk ON public.agent_runtime_execution_workflow_callback_deliveries IS 'callback关系精确引用同Run原Workflow等待';
COMMENT ON CONSTRAINT execution_workflow_callback_response_fk ON public.agent_runtime_execution_workflow_callback_deliveries IS '交付确认与原Input响应接受必须在同事务结束前闭合';
COMMENT ON CONSTRAINT execution_workflow_callback_identity_ck ON public.agent_runtime_execution_workflow_callback_deliveries IS '真实delivery、输入与等待代数完整有效';
CREATE FUNCTION public.guard_execution_immutable_body() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'execution_immutable_body'; END $$;
CREATE TRIGGER execution_program_child_runs_immutable BEFORE UPDATE ON public.agent_runtime_execution_program_child_runs
    FOR EACH ROW EXECUTE FUNCTION public.guard_execution_immutable_body();
CREATE TRIGGER execution_batch_members_immutable BEFORE UPDATE ON public.agent_runtime_execution_batch_members
    FOR EACH ROW EXECUTE FUNCTION public.guard_execution_immutable_body();
CREATE TRIGGER execution_materials_immutable BEFORE UPDATE ON public.agent_runtime_execution_materials
    FOR EACH ROW EXECUTE FUNCTION public.guard_execution_immutable_body();
CREATE TRIGGER execution_events_immutable BEFORE UPDATE ON public.agent_runtime_execution_events
    FOR EACH ROW EXECUTE FUNCTION public.guard_execution_immutable_body();
CREATE TRIGGER execution_plan_controls_immutable BEFORE UPDATE ON public.agent_runtime_execution_plan_controls
    FOR EACH ROW EXECUTE FUNCTION public.guard_execution_immutable_body();
CREATE TRIGGER execution_plan_child_runs_immutable BEFORE UPDATE ON public.agent_runtime_execution_plan_child_runs
    FOR EACH ROW EXECUTE FUNCTION public.guard_execution_immutable_body();
CREATE TRIGGER execution_workflow_callback_deliveries_immutable BEFORE UPDATE ON public.agent_runtime_execution_workflow_callback_deliveries
    FOR EACH ROW EXECUTE FUNCTION public.guard_execution_immutable_body();
COMMENT ON TABLE public.agent_runtime_execution_sessions IS '执行会话归属与唯一公开消息游标；不保存Run执行快照';
COMMENT ON TABLE public.agent_runtime_execution_materials IS '真实请求、上下文及结果的不可变协议正文；不嵌入完整Run或来源图';
COMMENT ON TABLE public.agent_runtime_execution_runs IS '新执行主链唯一Run权威状态；结构化revision、lease、取消和当前材料引用';
COMMENT ON TABLE public.agent_runtime_execution_actions IS '逻辑动作与原请求、冻结能力及最终选定结果；continuation保持原逻辑动作';
COMMENT ON TABLE public.agent_runtime_execution_attempts IS '单次派发及结果采纳；预算权威仍是runtime_budget账本，结果未知不重放';
COMMENT ON TABLE public.agent_runtime_execution_events IS '已提交安全产品事件，Session内统一有序游标，查询不恢复Run';
COMMENT ON INDEX public.execution_runs_due_idx IS '按租户和最近到期定位有界补扫工作，不增加第二工作队列';
COMMENT ON INDEX public.execution_events_run_idx IS '重复受理按Run和受理revision核对原事件';
COMMENT ON COLUMN public.agent_runtime_execution_sessions.tenant_id IS '可信会话归属租户；事务绑定后受FORCE RLS约束';
COMMENT ON COLUMN public.agent_runtime_execution_sessions.session_id IS '租户内稳定会话身份';
COMMENT ON COLUMN public.agent_runtime_execution_sessions.target_kind IS '会话服务目标类型AGENT或TEAM，受理后不可改变';
COMMENT ON COLUMN public.agent_runtime_execution_sessions.target_id IS '会话服务目标原身份；与实际执行Agent分离';
COMMENT ON COLUMN public.agent_runtime_execution_sessions.end_user_id IS '会话归属终端用户，不从模型参数取得';
COMMENT ON COLUMN public.agent_runtime_execution_sessions.last_event_sequence IS 'Session内已提交公开事件最大序号；Run锁后分配，不拥有执行状态';
COMMENT ON COLUMN public.agent_runtime_execution_materials.tenant_id IS '正文所属租户；材料引用不可跨租户解析';
COMMENT ON COLUMN public.agent_runtime_execution_materials.reference IS '租户内不可变正文引用，同键异内容拒绝';
COMMENT ON COLUMN public.agent_runtime_execution_materials.schema_id IS '调用方形成的真实协议正文schema身份';
COMMENT ON COLUMN public.agent_runtime_execution_materials.canonical_json IS '调用方提供的规范协议正文；不保存整个Run、checkpoint或来源图';
COMMENT ON COLUMN public.agent_runtime_execution_runs.tenant_id IS '可信Run归属租户，与预算账本及Session一致';
COMMENT ON COLUMN public.agent_runtime_execution_runs.run_id IS '租户内稳定运行身份，旧执行格式不恢复到此表';
COMMENT ON COLUMN public.agent_runtime_execution_runs.session_id IS '运行所属会话，公开事件共用该Session游标';
COMMENT ON COLUMN public.agent_runtime_execution_runs.target_kind IS '受理冻结的会话目标类型';
COMMENT ON COLUMN public.agent_runtime_execution_runs.target_id IS '受理冻结的会话目标身份';
COMMENT ON COLUMN public.agent_runtime_execution_runs.end_user_id IS '受理冻结的终端用户归属';
COMMENT ON COLUMN public.agent_runtime_execution_runs.agent_id IS '本Run实际执行Agent；团队目标不冒充Agent';
COMMENT ON COLUMN public.agent_runtime_execution_runs.input_id IS '触发本Run的原输入身份，Session内受唯一约束保护';
COMMENT ON COLUMN public.agent_runtime_execution_runs.trace_id IS '受理时取得的请求追踪身份';
COMMENT ON COLUMN public.agent_runtime_execution_runs.span_id IS '受理时取得的请求span身份';
COMMENT ON COLUMN public.agent_runtime_execution_runs.root_input_id IS '原追踪链根输入，不从当前输入重新推断';
COMMENT ON COLUMN public.agent_runtime_execution_runs.caused_by_event_id IS '原因事件身份；NULL表示入口没有原因事件';
COMMENT ON COLUMN public.agent_runtime_execution_runs.identity_ref IS '入口已核实的原调用方身份引用，不携带凭据';
COMMENT ON COLUMN public.agent_runtime_execution_runs.configuration_ref IS '受理冻结配置的所属权威引用，执行不以当前配置重建历史';
COMMENT ON COLUMN public.agent_runtime_execution_runs.input_ref IS '本次受理输入的不可变协议材料';
COMMENT ON COLUMN public.agent_runtime_execution_runs.initial_context_ref IS '原受理上下文材料，专用于输入重放一致性核对';
COMMENT ON COLUMN public.agent_runtime_execution_runs.admission_fingerprint IS '入口原受理指纹，正文及归属仍须分别核对';
COMMENT ON COLUMN public.agent_runtime_execution_runs.status IS 'Run生命周期权威状态；终态禁止新派发';
COMMENT ON COLUMN public.agent_runtime_execution_runs.phase IS '当前真实业务边界：READY、AWAITING_RESULT、WAITING、UNCERTAIN或FINISHED';
COMMENT ON COLUMN public.agent_runtime_execution_runs.revision IS 'Run业务状态CAS版本，成功提交直接返回给活跃循环';
COMMENT ON COLUMN public.agent_runtime_execution_runs.fence IS '本Run单调递增领取代数；释放租约后仍保留';
COMMENT ON COLUMN public.agent_runtime_execution_runs.cancelled IS '已提交取消事实；后续结果可以落账但不能推进';
COMMENT ON COLUMN public.agent_runtime_execution_runs.decisions IS '已采纳决策累计次数，非负且不能回退';
COMMENT ON COLUMN public.agent_runtime_execution_runs.tool_calls IS '已采纳工具步骤累计次数，不是第二套预算余额';
COMMENT ON COLUMN public.agent_runtime_execution_runs.knowledge_calls IS '已采纳Knowledge步骤累计次数，不是第二套预算余额';
COMMENT ON COLUMN public.agent_runtime_execution_runs.context_ref IS '当前已提交轻量执行上下文材料的精确引用';
COMMENT ON COLUMN public.agent_runtime_execution_runs.current_action_id IS '当前待结果动作；NULL表示当前没有待结果动作';
COMMENT ON COLUMN public.agent_runtime_execution_runs.lease_worker_id IS '当前单一推进者身份；NULL表示没有活动租约';
COMMENT ON COLUMN public.agent_runtime_execution_runs.lease_claim_id IS '本代领取业务身份；NULL表示没有活动租约';
COMMENT ON COLUMN public.agent_runtime_execution_runs.lease_until_second IS '租约到期Instant的epochSecond；与nano成对可空';
COMMENT ON COLUMN public.agent_runtime_execution_runs.lease_until_nano IS '租约到期Instant的纳秒分量，无损保留0到999999999';
COMMENT ON COLUMN public.agent_runtime_execution_runs.next_due_second IS '最近补扫到期Instant的epochSecond；NULL表示不参加到期补扫';
COMMENT ON COLUMN public.agent_runtime_execution_runs.next_due_nano IS '最近补扫到期Instant的纳秒分量，与second成对保存';
COMMENT ON COLUMN public.agent_runtime_execution_runs.created_second IS '首次受理数据库时间的epochSecond，不随恢复改写';
COMMENT ON COLUMN public.agent_runtime_execution_runs.created_nano IS '首次受理时间的纳秒分量';
COMMENT ON COLUMN public.agent_runtime_execution_runs.updated_second IS '最近成功业务提交数据库时间的epochSecond';
COMMENT ON COLUMN public.agent_runtime_execution_runs.updated_nano IS '最近业务提交时间的纳秒分量';
COMMENT ON COLUMN public.agent_runtime_execution_actions.tenant_id IS '动作所属租户，与原Run一致';
COMMENT ON COLUMN public.agent_runtime_execution_actions.action_id IS 'Run内逻辑动作的稳定身份';
COMMENT ON COLUMN public.agent_runtime_execution_actions.run_id IS '动作所属唯一Run';
COMMENT ON COLUMN public.agent_runtime_execution_actions.action_ordinal IS 'Run内逻辑动作序号，唯一且大于零';
COMMENT ON COLUMN public.agent_runtime_execution_actions.kind IS 'MODEL、TOOL、KNOWLEDGE、WORKFLOW或DELEGATION真实能力类别';
COMMENT ON COLUMN public.agent_runtime_execution_actions.target_handle IS '本动作精确冻结调用目标，不用显示名替代';
COMMENT ON COLUMN public.agent_runtime_execution_actions.capability_ref IS '原冻结能力版本的所属权威引用';
COMMENT ON COLUMN public.agent_runtime_execution_actions.request_ref IS '原始逻辑请求的不可变材料引用；每个Attempt另存自己的实际发送请求';
COMMENT ON COLUMN public.agent_runtime_execution_actions.fingerprint IS '原始逻辑请求指纹；continuation不改写原请求或指纹';
COMMENT ON COLUMN public.agent_runtime_execution_actions.status IS '逻辑动作派发或结果采纳状态；UNKNOWN禁止自动重放';
COMMENT ON COLUMN public.agent_runtime_execution_actions.current_attempt IS '已登记的本轮派发身份，恢复可以定位无结果Attempt';
COMMENT ON COLUMN public.agent_runtime_execution_actions.selected_attempt IS '已被当前Run采纳的结果身份；派发时NULL，不提前选择赢家';
COMMENT ON COLUMN public.agent_runtime_execution_attempts.tenant_id IS '派发所属租户，与原动作和预算事实一致';
COMMENT ON COLUMN public.agent_runtime_execution_attempts.attempt_id IS '一次不可自动重放的派发身份，同时关联原预算Attempt';
COMMENT ON COLUMN public.agent_runtime_execution_attempts.action_id IS '派发所属逻辑动作';
COMMENT ON COLUMN public.agent_runtime_execution_attempts.run_id IS '派发所属原Run，跨Run提交拒绝';
COMMENT ON COLUMN public.agent_runtime_execution_attempts.fence IS '登记派发时的原Run租约代数，不因接管覆盖';
COMMENT ON COLUMN public.agent_runtime_execution_attempts.result_kind IS '实际结果分类；NULL表示登记后尚无确定结果，不能推定未发送';
COMMENT ON COLUMN public.agent_runtime_execution_attempts.result_ref IS '实际原始结果的不可变材料引用；NULL表示结果尚未保存';
COMMENT ON COLUMN public.agent_runtime_execution_attempts.uncertainty_ref IS '曾经UNKNOWN时取得的原不确定结果材料；后续补确定事实仍保留，NULL表示未保存过UNKNOWN';
COMMENT ON COLUMN public.agent_runtime_execution_attempts.feedback_ref IS '安全反馈材料；NULL表示该结果没有独立反馈正文';
COMMENT ON COLUMN public.agent_runtime_execution_attempts.external_operation IS 'Provider或工具提供的原外部操作标识；NULL表示未提供';
COMMENT ON COLUMN public.agent_runtime_execution_attempts.adopted IS '该结果已被当前有效推进者选定；迟到保存不自动设为true';
COMMENT ON COLUMN public.agent_runtime_execution_events.tenant_id IS '已提交事件所属租户';
COMMENT ON COLUMN public.agent_runtime_execution_events.session_id IS '事件所属会话，同Session的不同Run共用唯一游标';
COMMENT ON COLUMN public.agent_runtime_execution_events.run_id IS '产生该安全产品事件的原Run';
COMMENT ON COLUMN public.agent_runtime_execution_events.event_sequence IS 'Session内严格递增的持久投递游标';
COMMENT ON COLUMN public.agent_runtime_execution_events.revision IS '事件和Run原子生效的业务版本';
COMMENT ON COLUMN public.agent_runtime_execution_events.event_id IS 'Session内唯一产品事件身份；同键异内容拒绝';
COMMENT ON COLUMN public.agent_runtime_execution_events.type IS '已安全转换的产品事件类型，不保存内部阶段证明';
COMMENT ON COLUMN public.agent_runtime_execution_events.schema_id IS '产品事件规范正文的schema身份';
COMMENT ON COLUMN public.agent_runtime_execution_events.canonical_json IS '已安全转换的产品展示正文，不保存运行状态快照';
COMMENT ON COLUMN public.agent_runtime_execution_events.occurred_second IS '该业务提交数据库时间的epochSecond';
COMMENT ON COLUMN public.agent_runtime_execution_events.occurred_nano IS '该业务提交时间的纳秒分量';
COMMENT ON FUNCTION public.guard_execution_immutable_body() IS '拒绝覆盖已保存协议正文或公开事件，不参与状态推进';
COMMENT ON CONSTRAINT execution_sessions_pk ON public.agent_runtime_execution_sessions IS '租户内唯一会话消息顺序根';
COMMENT ON CONSTRAINT execution_sessions_owner_uq ON public.agent_runtime_execution_sessions IS 'Run外键核对完整会话目标和用户归属';
COMMENT ON CONSTRAINT execution_sessions_identity_ck ON public.agent_runtime_execution_sessions IS '拒绝空白会话身份及无效终端用户';
COMMENT ON CONSTRAINT execution_sessions_kind_ck ON public.agent_runtime_execution_sessions IS '会话目标仅允许当前AGENT和TEAM契约';
COMMENT ON CONSTRAINT execution_sessions_sequence_ck ON public.agent_runtime_execution_sessions IS '公开消息游标不能为负';
COMMENT ON CONSTRAINT execution_materials_pk ON public.agent_runtime_execution_materials IS '租户内正文引用唯一';
COMMENT ON CONSTRAINT execution_materials_content_ck ON public.agent_runtime_execution_materials IS '正文引用、schema和正文必须存在且非空白';
COMMENT ON CONSTRAINT execution_runs_pk ON public.agent_runtime_execution_runs IS '租户内唯一Run权威身份';
COMMENT ON CONSTRAINT execution_runs_session_uq ON public.agent_runtime_execution_runs IS '公开事件核对Run所属原Session';
COMMENT ON CONSTRAINT execution_runs_input_uq ON public.agent_runtime_execution_runs IS '同Session目标和输入只能受理一次';
COMMENT ON CONSTRAINT execution_runs_owner_fk ON public.agent_runtime_execution_runs IS 'Run必须属于原Session完整目标和用户';
COMMENT ON CONSTRAINT execution_runs_input_fk ON public.agent_runtime_execution_runs IS '受理输入必须引用已保存的同租户材料';
COMMENT ON CONSTRAINT execution_runs_initial_context_fk ON public.agent_runtime_execution_runs IS '原受理上下文必须真实存在';
COMMENT ON CONSTRAINT execution_runs_context_fk ON public.agent_runtime_execution_runs IS '提交进度不能引用悬空上下文';
COMMENT ON CONSTRAINT execution_runs_identity_ck ON public.agent_runtime_execution_runs IS '拒绝缺失的运行、追踪、身份或冻结配置引用';
COMMENT ON CONSTRAINT execution_runs_status_ck ON public.agent_runtime_execution_runs IS '仅允许当前Run生命周期状态';
COMMENT ON CONSTRAINT execution_runs_phase_ck ON public.agent_runtime_execution_runs IS '执行边界枚举与生命周期终态严格一致';
COMMENT ON CONSTRAINT execution_runs_counts_ck ON public.agent_runtime_execution_runs IS '版本为正，租约代数及进度计数非负';
COMMENT ON CONSTRAINT execution_runs_lease_ck ON public.agent_runtime_execution_runs IS '租约身份和精确到期时间整体存在或整体为空';
COMMENT ON CONSTRAINT execution_runs_terminal_lease_ck ON public.agent_runtime_execution_runs IS '终态、输入等待和派发不确定静止时释放租约与due但保留fence';
COMMENT ON CONSTRAINT execution_runs_due_ck ON public.agent_runtime_execution_runs IS '补扫到期时间成对保存并保留纳秒精度';
COMMENT ON CONSTRAINT execution_runs_action_phase_ck ON public.agent_runtime_execution_runs IS 'READY仅在接受等待响应后保留原动作，待结果与静止状态必须指向原动作';
COMMENT ON CONSTRAINT execution_runs_time_ck ON public.agent_runtime_execution_runs IS '时间纳秒合法且最近提交不早于受理';
COMMENT ON CONSTRAINT execution_runs_current_action_fk ON public.agent_runtime_execution_runs IS '待结果动作必须属于同租户同Run';
COMMENT ON CONSTRAINT execution_actions_pk ON public.agent_runtime_execution_actions IS '租户内唯一逻辑动作';
COMMENT ON CONSTRAINT execution_actions_run_uq ON public.agent_runtime_execution_actions IS 'Attempt和当前动作外键核对原Run归属';
COMMENT ON CONSTRAINT execution_actions_ordinal_uq ON public.agent_runtime_execution_actions IS '同Run逻辑动作序号唯一';
COMMENT ON CONSTRAINT execution_actions_run_fk ON public.agent_runtime_execution_actions IS '动作必须属于已受理Run';
COMMENT ON CONSTRAINT execution_actions_request_fk ON public.agent_runtime_execution_actions IS '派发请求必须是原同租户不可变材料';
COMMENT ON CONSTRAINT execution_actions_identity_ck ON public.agent_runtime_execution_actions IS '动作身份、序号、精确目标和指纹完整';
COMMENT ON CONSTRAINT execution_actions_kind_ck ON public.agent_runtime_execution_actions IS '逻辑动作只允许当前真实能力类别';
COMMENT ON CONSTRAINT execution_actions_status_ck ON public.agent_runtime_execution_actions IS '动作状态枚举包含结果未知和人工等待';
COMMENT ON CONSTRAINT execution_actions_selected_ck ON public.agent_runtime_execution_actions IS '派发和INPUT_REQUIRED不预选最终结果；成功失败必须指定最终Attempt，缺结果UNKNOWN可为空';
COMMENT ON CONSTRAINT execution_actions_selected_fk ON public.agent_runtime_execution_actions IS '选定结果必须来自原逻辑动作的Attempt';
COMMENT ON CONSTRAINT execution_actions_current_attempt_fk ON public.agent_runtime_execution_actions IS '登记派发后即可精确定位原Attempt，不以选定结果字段代替';
COMMENT ON CONSTRAINT execution_attempts_pk ON public.agent_runtime_execution_attempts IS '租户内一次派发业务身份唯一';
COMMENT ON CONSTRAINT execution_attempts_action_uq ON public.agent_runtime_execution_attempts IS '动作当前派发和选定结果复合引用目标';
COMMENT ON CONSTRAINT execution_attempts_action_fk ON public.agent_runtime_execution_attempts IS '一次派发必须属于同租户原动作和Run';
COMMENT ON CONSTRAINT execution_attempts_result_fk ON public.agent_runtime_execution_attempts IS '实际结果必须引用原同租户已保存材料';
COMMENT ON CONSTRAINT execution_attempts_uncertainty_fk ON public.agent_runtime_execution_attempts IS '补确定结果后仍保留原UNKNOWN证据';
COMMENT ON CONSTRAINT execution_attempts_feedback_fk ON public.agent_runtime_execution_attempts IS '安全反馈存在时必须引用真实材料';
COMMENT ON CONSTRAINT execution_attempts_budget_fk ON public.agent_runtime_execution_attempts IS '派发和原预算Attempt在同一业务事务存在';
COMMENT ON CONSTRAINT execution_attempts_identity_ck ON public.agent_runtime_execution_attempts IS '派发身份非空且原fence为正';
COMMENT ON CONSTRAINT execution_attempts_result_ck ON public.agent_runtime_execution_attempts IS '结果分类和正文成对，未保存结果不能标记采纳';
COMMENT ON CONSTRAINT execution_events_pk ON public.agent_runtime_execution_events IS 'Session内统一且唯一的持久消息游标';
COMMENT ON CONSTRAINT execution_events_identity_uq ON public.agent_runtime_execution_events IS '产品事件业务身份在Session内只生效一次';
COMMENT ON CONSTRAINT execution_events_run_fk ON public.agent_runtime_execution_events IS '事件必须来自同Session内的原Run';
COMMENT ON CONSTRAINT execution_events_content_ck ON public.agent_runtime_execution_events IS '公开事件游标、提交版本、类型和安全正文完整';
COMMENT ON TRIGGER execution_materials_immutable ON public.agent_runtime_execution_materials IS '正文保存后禁止覆盖，重放由引用和全值核对';
COMMENT ON TRIGGER execution_events_immutable ON public.agent_runtime_execution_events IS '已提交安全事件禁止覆盖';
COMMENT ON COLUMN public.agent_runtime_execution_runs.actor_verification_ref IS '最近被接受的当前调用方验证引用；resume可更新，原identity_ref原因果不改变';
COMMENT ON COLUMN public.agent_runtime_execution_runs.waiting_action_id IS '当前输入等待所属原逻辑动作；接受响应后保留至该动作完成，NULL表示无当前等待';
COMMENT ON COLUMN public.agent_runtime_execution_runs.waiting_generation IS '原动作内单调等待代数，与waiting_action_id成对可空';
COMMENT ON CONSTRAINT execution_runs_wait_ck ON public.agent_runtime_execution_runs IS '等待引用成对、代数为正且属于current_action；WAITING必须具有精确等待';
COMMENT ON CONSTRAINT execution_runs_wait_fk ON public.agent_runtime_execution_runs IS '当前等待必须属于同租户同Run原动作中的真实一轮等待';
COMMENT ON COLUMN public.agent_runtime_execution_attempts.request_ref IS '该次物理调用实际发送的不可变请求，continuation与初始请求分别保留';
COMMENT ON COLUMN public.agent_runtime_execution_attempts.fingerprint IS '该次物理请求与原预算Demand共用的精确指纹';
COMMENT ON COLUMN public.agent_runtime_execution_attempts.continuation_generation IS '本次调用消费的原等待代数；NULL表示原始派发';
COMMENT ON CONSTRAINT execution_attempts_request_fk ON public.agent_runtime_execution_attempts IS '每次实际发送请求必须是真实同租户材料';
COMMENT ON CONSTRAINT execution_attempts_continuation_fk ON public.agent_runtime_execution_attempts IS 'continuation必须引用原逻辑动作内的真实等待';
COMMENT ON TABLE public.agent_runtime_execution_waits IS '真实工具输入请求或模型AskUser产生的业务等待、精确响应回执和一次消费事实；不保存通用执行快照';
COMMENT ON COLUMN public.agent_runtime_execution_waits.tenant_id IS '可信等待所属租户，强制RLS与原Run一致';
COMMENT ON COLUMN public.agent_runtime_execution_waits.run_id IS '等待所属原Run，不接受跨Run响应';
COMMENT ON COLUMN public.agent_runtime_execution_waits.action_id IS '等待所属原逻辑工具动作，恢复不创建第二逻辑动作';
COMMENT ON COLUMN public.agent_runtime_execution_waits.generation IS '原动作内单调递增等待代数';
COMMENT ON COLUMN public.agent_runtime_execution_waits.source_attempt IS '产生该轮工具INPUT_REQUIRED或模型SUCCEEDED中AskUser决定的原物理调用';
COMMENT ON COLUMN public.agent_runtime_execution_waits.kind IS 'TOOL等待实际INPUT_REQUIRED；HUMAN等待实际成功模型结果中的AskUser决定';
COMMENT ON COLUMN public.agent_runtime_execution_waits.consumed_revision IS 'HUMAN响应被本地采纳的精确业务版本；NULL表示尚未本地采纳，不伪造continuation Attempt';
COMMENT ON COLUMN public.agent_runtime_execution_waits.input_id IS '唯一被接受的稳定入口输入；NULL表示尚无响应';
COMMENT ON COLUMN public.agent_runtime_execution_waits.response_ref IS '被接受响应的不可变材料引用；未接受时NULL';
COMMENT ON COLUMN public.agent_runtime_execution_waits.fingerprint IS '被接受响应的精确业务指纹；未接受时NULL';
COMMENT ON COLUMN public.agent_runtime_execution_waits.actor_ref IS '接受响应时当前IAM已核实的验证引用；未接受时NULL';
COMMENT ON COLUMN public.agent_runtime_execution_waits.accepted_revision IS '响应和Run原子接受时的业务revision；未接受时NULL';
COMMENT ON COLUMN public.agent_runtime_execution_waits.continuation_attempt IS '唯一消费该响应的新物理调用；NULL表示尚未消费';
COMMENT ON CONSTRAINT execution_waits_pk ON public.agent_runtime_execution_waits IS '同租户原动作内每轮等待唯一';
COMMENT ON CONSTRAINT execution_waits_run_uq ON public.agent_runtime_execution_waits IS 'Run等待复合引用核对原归属';
COMMENT ON CONSTRAINT execution_waits_input_uq ON public.agent_runtime_execution_waits IS '同Run稳定输入只能接受一次，不覆盖或跨轮消费';
COMMENT ON CONSTRAINT execution_waits_source_uq ON public.agent_runtime_execution_waits IS '一个真实工具请求结果或模型AskUser结果只产生一轮等待';
COMMENT ON CONSTRAINT execution_waits_action_fk ON public.agent_runtime_execution_waits IS '等待必须属于同租户同Run的原动作';
COMMENT ON CONSTRAINT execution_waits_source_fk ON public.agent_runtime_execution_waits IS '等待来源必须是原动作的真实Attempt';
COMMENT ON CONSTRAINT execution_waits_response_fk ON public.agent_runtime_execution_waits IS '已接受响应引用真实同租户不可变材料';
COMMENT ON CONSTRAINT execution_waits_continuation_fk ON public.agent_runtime_execution_waits IS '消费响应的Attempt必须属于原逻辑动作';
COMMENT ON CONSTRAINT execution_waits_identity_ck ON public.agent_runtime_execution_waits IS '等待代数与来源Attempt完整且有效';
COMMENT ON CONSTRAINT execution_waits_kind_ck ON public.agent_runtime_execution_waits IS '只允许真实工具等待和人类输入等待';
COMMENT ON CONSTRAINT execution_waits_accepted_ck ON public.agent_runtime_execution_waits IS '响应输入、材料、指纹、actor及版本全部存在或全部为空';
COMMENT ON CONSTRAINT execution_waits_consumed_ck ON public.agent_runtime_execution_waits IS '只有接受过响应才可消费；TOOL以新Attempt消费，HUMAN以递增业务版本本地消费';
COMMENT ON TABLE public.agent_runtime_execution_wait_requests IS '每轮等待请求的精确key和MCP方法；正文仍由原INPUT_REQUIRED结果拥有';
COMMENT ON COLUMN public.agent_runtime_execution_wait_requests.tenant_id IS '原等待所属可信租户，强制RLS';
COMMENT ON COLUMN public.agent_runtime_execution_wait_requests.action_id IS '请求所属原逻辑动作';
COMMENT ON COLUMN public.agent_runtime_execution_wait_requests.generation IS '请求所属精确等待代数';
COMMENT ON COLUMN public.agent_runtime_execution_wait_requests.input_key IS '原工具请求map中的精确稳定key';
COMMENT ON COLUMN public.agent_runtime_execution_wait_requests.method IS '原MCP请求的精确wire方法，响应必须一致';
COMMENT ON CONSTRAINT execution_wait_requests_pk ON public.agent_runtime_execution_wait_requests IS '一轮等待内请求key唯一';
COMMENT ON CONSTRAINT execution_wait_requests_wait_fk ON public.agent_runtime_execution_wait_requests IS '请求必须属于同租户原动作内真实等待';
COMMENT ON CONSTRAINT execution_wait_requests_key_ck ON public.agent_runtime_execution_wait_requests IS '请求key不得空白';
COMMENT ON CONSTRAINT execution_wait_requests_method_ck ON public.agent_runtime_execution_wait_requests IS '只接受当前MCP输入请求支持的方法';
COMMENT ON COLUMN public.agent_runtime_execution_runs.batch_source_attempt IS '当前并行工具组的原MODEL Attempt，NULL表示当前不是并行组';
COMMENT ON COLUMN public.agent_runtime_execution_runs.batch_size IS '原模型决定的并行槽位总数，与组来源成对为空';
COMMENT ON COLUMN public.agent_runtime_execution_actions.batch_source_attempt IS '原模型决定的来源Attempt；NULL表示单动作，组内不另建批次快照';
COMMENT ON COLUMN public.agent_runtime_execution_actions.batch_size IS '原组不可变总槽位数，恢复核验不能丢失兄弟动作';
COMMENT ON COLUMN public.agent_runtime_execution_actions.batch_slot IS '原模型tool_calls顺序中的1起始槽位；NULL表示单动作';
COMMENT ON CONSTRAINT execution_runs_batch_ck ON public.agent_runtime_execution_runs IS '组来源和大小成对且不冒充单currentAction';
COMMENT ON CONSTRAINT execution_runs_batch_source_fk ON public.agent_runtime_execution_runs IS '当前并行组必须锚定同租户同Run原MODEL Attempt';
COMMENT ON CONSTRAINT execution_actions_batch_source_fk ON public.agent_runtime_execution_actions IS '每个并行槽位必须锚定同租户同Run真实原模型派发';
COMMENT ON CONSTRAINT execution_actions_batch_slot_uq ON public.agent_runtime_execution_actions IS '原模型组内槽位唯一且顺序稳定';
COMMENT ON CONSTRAINT execution_actions_batch_ck ON public.agent_runtime_execution_actions IS '并行分组字段整体存在，大小和槽位有效且仅工具支持';
COMMENT ON CONSTRAINT execution_attempts_run_uq ON public.agent_runtime_execution_attempts IS '并行组来源复合引用核对原Run归属';
COMMENT ON CONSTRAINT execution_runs_full_owner_uq ON public.agent_runtime_execution_runs IS 'Workflow Execution完整owner外键的唯一权威目标';
COMMENT ON TABLE public.agent_runtime_execution_child_runs IS '父Action与真实子Run的委派或Workflow节点来源关系；不保存执行快照或第二预算账本';
COMMENT ON COLUMN public.agent_runtime_execution_child_runs.tenant_id IS '父子运行同一可信租户，FORCE RLS';
COMMENT ON COLUMN public.agent_runtime_execution_child_runs.parent_run_id IS '产生委派或Workflow外调的原父Run';
COMMENT ON COLUMN public.agent_runtime_execution_child_runs.parent_action_id IS '原逻辑父Action，多个父动作可引用已受理的同一业务子Run';
COMMENT ON COLUMN public.agent_runtime_execution_child_runs.parent_attempt_id IS '第一次准入该关联的准确父派发Attempt';
COMMENT ON COLUMN public.agent_runtime_execution_child_runs.child_run_id IS '真正执行模型或工具的typed子Run，预算以MEMBER继承父root';
COMMENT ON COLUMN public.agent_runtime_execution_child_runs.origin_kind IS 'DELEGATION业务Entry或WORKFLOW_NODE真实节点来源';
COMMENT ON COLUMN public.agent_runtime_execution_child_runs.delegation_id IS '真实委派Entry身份；非委派来源时NULL';
COMMENT ON COLUMN public.agent_runtime_execution_child_runs.execution_id IS '真实Workflow Execution身份；非节点来源时NULL';
COMMENT ON COLUMN public.agent_runtime_execution_child_runs.node_id IS '原Workflow节点身份；非节点来源时NULL';
COMMENT ON COLUMN public.agent_runtime_execution_child_runs.node_visit IS '原节点visit正序号；非节点来源时NULL';
COMMENT ON COLUMN public.agent_runtime_execution_child_runs.node_call IS '原节点call正序号；非节点来源时NULL';
COMMENT ON COLUMN public.agent_runtime_execution_child_runs.checkpoint_fingerprint IS '节点实际来源checkpoint的schema和正文摘要；非节点来源时NULL';
COMMENT ON COLUMN public.agent_runtime_execution_child_runs.source_revision IS '首次受理节点时真实Execution业务版本；非节点来源时NULL';
COMMENT ON COLUMN public.agent_runtime_execution_child_runs.source_fence IS '首次受理节点时真实Execution租约代数；非节点来源时NULL';
COMMENT ON CONSTRAINT execution_child_runs_pk ON public.agent_runtime_execution_child_runs IS '每个父逻辑动作与同一子Run关联唯一';
COMMENT ON CONSTRAINT execution_child_runs_parent_fk ON public.agent_runtime_execution_child_runs IS '父Action必须属于原父Run';
COMMENT ON CONSTRAINT execution_child_runs_attempt_fk ON public.agent_runtime_execution_child_runs IS '准入来源派发必须属于原父Action';
COMMENT ON CONSTRAINT execution_child_runs_child_fk ON public.agent_runtime_execution_child_runs IS '子Run必须与关系在同一业务事务真实受理';
COMMENT ON CONSTRAINT execution_child_runs_distinct_ck ON public.agent_runtime_execution_child_runs IS '子Run不得冒充父Run自身';
COMMENT ON CONSTRAINT execution_child_runs_origin_ck ON public.agent_runtime_execution_child_runs IS '委派和节点来源字段互斥并各自完整';
COMMENT ON INDEX public.execution_child_runs_child_idx IS '相同子Run来源一致性及父业务关联有界检索';
COMMENT ON COLUMN public.agent_runtime_execution_waits.business_wait_id IS '真实委派等待或Plan业务等待身份；其他类别为空';
COMMENT ON COLUMN public.agent_runtime_execution_waits.execution_id IS 'WORKFLOW等待的真实Execution；其他类别为空';
COMMENT ON COLUMN public.agent_runtime_execution_waits.suspend_ref IS 'Workflow Engine实际挂起引用；其他类别为空';
COMMENT ON COLUMN public.agent_runtime_execution_waits.action_key IS '本次Workflow恢复的原稳定动作键；其他类别为空';
COMMENT ON COLUMN public.agent_runtime_execution_waits.execution_revision IS '原Workflow挂起时真实Engine业务版本；其他类别为空';
COMMENT ON COLUMN public.agent_runtime_execution_waits.plan_id IS 'PLAN等待的真实计划身份；其他类别为空';
COMMENT ON COLUMN public.agent_runtime_execution_waits.plan_revision IS 'PLAN等待的真实计划版本；其他类别为空';
COMMENT ON CONSTRAINT execution_waits_internal_ck ON public.agent_runtime_execution_waits IS '等待类别只能携带所属委派、Workflow或Plan精确来源，字段互斥完整';
COMMENT ON TABLE public.agent_runtime_execution_wait_children IS '精确委派等待的业务Entry与真实子Run清单，不保存通用工作队列';
COMMENT ON COLUMN public.agent_runtime_execution_wait_children.tenant_id IS '父子等待所属可信租户，FORCE RLS';
COMMENT ON COLUMN public.agent_runtime_execution_wait_children.action_id IS '等待所属原逻辑动作';
COMMENT ON COLUMN public.agent_runtime_execution_wait_children.generation IS '原动作内精确等待代数';
COMMENT ON COLUMN public.agent_runtime_execution_wait_children.ordinal IS '原依赖清单中的稳定1起始槽位';
COMMENT ON COLUMN public.agent_runtime_execution_wait_children.delegation_id IS '真实委派Entry业务身份';
COMMENT ON COLUMN public.agent_runtime_execution_wait_children.child_run_id IS 'Entry拥有的真实typed子Run';
COMMENT ON CONSTRAINT execution_wait_children_pk ON public.agent_runtime_execution_wait_children IS '原等待中每个依赖槽位唯一';
COMMENT ON CONSTRAINT execution_wait_children_entry_uq ON public.agent_runtime_execution_wait_children IS '同一等待不重复接受同一委派Entry';
COMMENT ON CONSTRAINT execution_wait_children_run_uq ON public.agent_runtime_execution_wait_children IS '同一等待不重复消费同一子Run';
COMMENT ON CONSTRAINT execution_wait_children_wait_fk ON public.agent_runtime_execution_wait_children IS '依赖必须属于原Action内真实等待';
COMMENT ON CONSTRAINT execution_wait_children_run_fk ON public.agent_runtime_execution_wait_children IS '依赖只能指向已受理的同租户真实子Run';
COMMENT ON CONSTRAINT execution_wait_children_identity_ck ON public.agent_runtime_execution_wait_children IS '依赖槽位与业务身份必须有效';

-- Workflow交付以原HMAC与typed等待响应闭合，不伪造InputReceipt或通用投递operation。
DO $install$
BEGIN
    IF to_regclass('public.agent_runtime_callback_deliveries') IS NOT NULL THEN
        ALTER TABLE public.agent_runtime_execution_workflow_callback_deliveries ADD CONSTRAINT execution_workflow_callback_delivery_fk
            FOREIGN KEY (tenant_id,delivery_id) REFERENCES public.agent_runtime_callback_deliveries(tenant_id,delivery_id);
        ALTER TABLE public.agent_runtime_execution_workflow_callback_deliveries ADD CONSTRAINT execution_workflow_callback_proof_fk
            FOREIGN KEY (tenant_id,delivery_id) REFERENCES public.agent_runtime_callback_ingress_proofs(tenant_id,delivery_id);
        COMMENT ON CONSTRAINT execution_workflow_callback_delivery_fk ON public.agent_runtime_execution_workflow_callback_deliveries IS '只能确认真实已收录的原callback delivery';
        COMMENT ON CONSTRAINT execution_workflow_callback_proof_fk ON public.agent_runtime_execution_workflow_callback_deliveries IS 'Workflow交付必须有原真实HMAC入口持久证明';
        EXECUTE $ddl$
        CREATE FUNCTION public.execution_workflow_callback_closed(p_tenant text,p_delivery text) RETURNS boolean
        LANGUAGE sql STABLE SET search_path=pg_catalog,public AS $body$
            SELECT EXISTS(SELECT 1 FROM public.agent_runtime_execution_workflow_callback_deliveries b
                JOIN public.agent_runtime_execution_waits w ON w.tenant_id=b.tenant_id AND w.run_id=b.run_id AND w.action_id=b.action_id AND w.generation=b.generation
                JOIN public.agent_runtime_callback_deliveries d ON d.tenant_id=b.tenant_id AND d.delivery_id=b.delivery_id
                JOIN public.agent_runtime_callback_ingress_proofs p ON p.tenant_id=d.tenant_id AND p.delivery_id=d.delivery_id AND p.callback_id=d.callback_id
                JOIN public.agent_runtime_callback_registrations r ON r.tenant_id=d.tenant_id AND r.callback_id=d.callback_id
                WHERE b.tenant_id=p_tenant AND b.delivery_id=p_delivery AND w.kind='WORKFLOW' AND w.input_id=b.input_id
                    AND w.response_ref IS NOT NULL AND w.accepted_revision IS NOT NULL AND d.status='DELIVERED'
                    AND d.input_id=b.input_id AND d.command_digest=p.command_digest AND b.input_id='callback:'||p.fingerprint AND r.status='ACTIVE')
        $body$
        $ddl$;
        EXECUTE $ddl$
        CREATE OR REPLACE FUNCTION public.guard_agent_runtime_callback_delivery() RETURNS trigger LANGUAGE plpgsql AS $body$
        DECLARE storage_now numeric;
        BEGIN
            storage_now := extract(epoch FROM clock_timestamp());
            IF TG_OP='DELETE' THEN RAISE EXCEPTION 'callback_delivery_delete_forbidden'; END IF;
            IF TG_OP='INSERT' THEN
                IF NEW.status NOT IN ('READY','REJECTED') OR NEW.revision<>1 OR NEW.attempt<>0 THEN RAISE EXCEPTION 'callback_delivery_initial_state'; END IF;
                IF NEW.status='READY' AND NOT EXISTS(SELECT 1 FROM public.agent_runtime_callback_registrations r WHERE r.tenant_id=NEW.tenant_id AND r.callback_id=NEW.callback_id AND r.status='ACTIVE') THEN
                    RAISE EXCEPTION 'callback_delivery_registration_missing'; END IF;
                IF NEW.status='REJECTED' AND NOT EXISTS(SELECT 1 FROM public.agent_runtime_callback_audits a WHERE a.tenant_id=NEW.tenant_id AND a.resource_kind='delivery' AND a.resource_id=NEW.delivery_id AND a.audit_ref=NEW.audit_ref) THEN
                    RAISE EXCEPTION 'callback_delivery_rejection_audit_missing'; END IF;
                RETURN NEW;
            END IF;
            IF (NEW.tenant_id,NEW.delivery_id,NEW.callback_id,NEW.command_digest,NEW.input_id,NEW.envelope_data,NEW.recorded_at)
                IS DISTINCT FROM (OLD.tenant_id,OLD.delivery_id,OLD.callback_id,OLD.command_digest,OLD.input_id,OLD.envelope_data,OLD.recorded_at)
                OR NEW.revision<>OLD.revision+1 OR OLD.status IN ('DELIVERED','REJECTED','DEAD_LETTER') THEN RAISE EXCEPTION 'callback_delivery_immutable_or_revision'; END IF;
            IF NEW.status='DELIVERED' AND EXISTS(SELECT 1 FROM public.agent_runtime_execution_workflow_callback_deliveries b
                JOIN public.agent_runtime_callback_ingress_proofs p ON p.tenant_id=b.tenant_id AND p.delivery_id=b.delivery_id
                WHERE b.tenant_id=NEW.tenant_id AND b.delivery_id=NEW.delivery_id AND b.input_id=NEW.input_id AND p.command_digest=NEW.command_digest AND p.callback_id=NEW.callback_id) THEN
                IF OLD.status NOT IN ('READY','PROCESSING')
                    OR NEW.available_at IS DISTINCT FROM OLD.available_at THEN RAISE EXCEPTION 'callback_workflow_delivery_transition'; END IF;
                IF (OLD.status='READY' AND NEW.attempt<>OLD.attempt+1)
                    OR (OLD.status='PROCESSING' AND NEW.attempt<>OLD.attempt) THEN RAISE EXCEPTION 'callback_workflow_delivery_transition'; END IF;
                RETURN NEW;
            END IF;
            IF NEW.status='PROCESSING' AND NEW.attempt=OLD.attempt+1 THEN
                IF OLD.status NOT IN ('READY','PROCESSING') OR (OLD.status='READY' AND OLD.available_at>storage_now)
                    OR (OLD.status='PROCESSING' AND OLD.lease_until>storage_now) OR NEW.lease_until<=storage_now
                    OR (OLD.lease_token IS NOT NULL AND NEW.lease_token<=OLD.lease_token) OR NEW.lease_claim IS NOT DISTINCT FROM OLD.lease_claim THEN RAISE EXCEPTION 'callback_delivery_claim_mismatch'; END IF;
            ELSIF OLD.status='PROCESSING' AND NEW.attempt=OLD.attempt THEN
                IF OLD.lease_until<=storage_now THEN RAISE EXCEPTION 'callback_delivery_expired'; END IF;
                IF NEW.status NOT IN ('PROCESSING','READY','DELIVERED','DEAD_LETTER') THEN RAISE EXCEPTION 'callback_delivery_mutation_state'; END IF;
                IF NEW.status='PROCESSING' AND ((NEW.lease_claim,NEW.lease_worker,NEW.lease_token) IS DISTINCT FROM (OLD.lease_claim,OLD.lease_worker,OLD.lease_token) OR NEW.lease_until<=OLD.lease_until) THEN
                    RAISE EXCEPTION 'callback_delivery_renew_mismatch'; END IF;
            ELSE RAISE EXCEPTION 'callback_delivery_transition'; END IF;
            IF NEW.status<>'READY' AND NEW.available_at IS DISTINCT FROM OLD.available_at THEN RAISE EXCEPTION 'callback_delivery_availability_immutable'; END IF;
            RETURN NEW;
        END $body$
        $ddl$;
        EXECUTE $ddl$
        CREATE OR REPLACE FUNCTION public.check_agent_runtime_callback_delivery_closure() RETURNS trigger LANGUAGE plpgsql AS $body$
        BEGIN
            IF TG_TABLE_NAME='agent_runtime_callback_delivery_operations' THEN
                IF NOT EXISTS(SELECT 1 FROM public.agent_runtime_callback_deliveries d WHERE d.tenant_id=NEW.tenant_id AND d.delivery_id=NEW.delivery_id AND d.revision=NEW.output_revision) THEN
                    RAISE EXCEPTION 'callback_delivery_operation_output_mismatch'; END IF;
            ELSIF EXISTS(SELECT 1 FROM public.agent_runtime_execution_workflow_callback_deliveries b WHERE b.tenant_id=NEW.tenant_id AND b.delivery_id=NEW.delivery_id) THEN
                IF NOT public.execution_workflow_callback_closed(NEW.tenant_id,NEW.delivery_id) THEN RAISE EXCEPTION 'callback_workflow_response_closure_missing'; END IF;
            ELSIF NEW.attempt=OLD.attempt AND NOT EXISTS(SELECT 1 FROM public.agent_runtime_callback_delivery_operations o WHERE o.tenant_id=NEW.tenant_id AND o.delivery_id=NEW.delivery_id AND o.output_revision=NEW.revision) THEN
                RAISE EXCEPTION 'callback_delivery_operation_missing';
            END IF;
            RETURN NEW;
        END $body$
        $ddl$;
        EXECUTE $ddl$
        CREATE FUNCTION public.check_execution_workflow_callback_closure() RETURNS trigger LANGUAGE plpgsql AS $body$
        BEGIN
            IF NOT public.execution_workflow_callback_closed(NEW.tenant_id,NEW.delivery_id) THEN RAISE EXCEPTION 'callback_workflow_response_closure_missing'; END IF;
            RETURN NEW;
        END $body$
        $ddl$;
        CREATE CONSTRAINT TRIGGER execution_workflow_callback_response_closure AFTER INSERT ON public.agent_runtime_execution_workflow_callback_deliveries
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.check_execution_workflow_callback_closure();
        COMMENT ON FUNCTION public.execution_workflow_callback_closed(text,text) IS '原HMAC证明、准确typed Workflow等待已接受响应与交付事实必须真实一致';
        COMMENT ON FUNCTION public.check_execution_workflow_callback_closure() IS '交付关系只引用本事务真实接受的原Workflow响应，不造旧operation';
        COMMENT ON FUNCTION public.guard_agent_runtime_callback_delivery() IS '冻结原delivery；Workflow按真实HMAC与typed响应确认，其余领域mutation保留自身准确闭包';
        COMMENT ON FUNCTION public.check_agent_runtime_callback_delivery_closure() IS 'Workflow delivery以准确typed响应和原HMAC闭合，其余领域mutation核自身原operation';
    END IF;
END $install$;

COMMENT ON TABLE public.agent_runtime_execution_batch_members IS '原成功MODEL决定拥有的有序native槽位，不复制请求正文或旧Action归属';
COMMENT ON COLUMN public.agent_runtime_execution_batch_members.tenant_id IS '同原Run可信租户，强制RLS';
COMMENT ON COLUMN public.agent_runtime_execution_batch_members.run_id IS '真实原Run';
COMMENT ON COLUMN public.agent_runtime_execution_batch_members.source_attempt IS '拥有本组的原已采纳成功MODEL Attempt';
COMMENT ON COLUMN public.agent_runtime_execution_batch_members.slot IS '原tool_calls内1起始顺序，包含复用槽位';
COMMENT ON COLUMN public.agent_runtime_execution_batch_members.size IS '原native调用组总槽位数';
COMMENT ON COLUMN public.agent_runtime_execution_batch_members.action_id IS '实际新调用或原已采纳Action，不修改旧Action原归属';
COMMENT ON COLUMN public.agent_runtime_execution_batch_members.first_attempt_id IS '初始新调用或精确复用的原Attempt';
COMMENT ON COLUMN public.agent_runtime_execution_batch_members.reused IS '原已采纳结果复用，不创建Attempt、预算或发送权';
COMMENT ON COLUMN public.agent_runtime_execution_batch_members.reused_result_ref IS '精确原结果引用，fresh为空';
COMMENT ON COLUMN public.agent_runtime_execution_batch_members.reused_feedback_ref IS '精确原安全反馈引用，fresh为空';
COMMENT ON COLUMN public.agent_runtime_execution_waits.declared_revision IS '原card建立时Run业务版本；并行早响应只接受此版本或当前版本';

COMMENT ON TABLE public.agent_runtime_execution_program_child_runs IS '真实run_code父TOOL Attempt内RPC ordinal与唯一typed MEMBER child的原准入关系';
COMMENT ON COLUMN public.agent_runtime_execution_program_child_runs.tenant_id IS '原父子Run所属可信租户，强制RLS';
COMMENT ON COLUMN public.agent_runtime_execution_program_child_runs.parent_run_id IS '仍在途原程序TOOL所属Run';
COMMENT ON COLUMN public.agent_runtime_execution_program_child_runs.parent_action_id IS '原run_code逻辑工具动作，不造Workflow node';
COMMENT ON COLUMN public.agent_runtime_execution_program_child_runs.parent_attempt_id IS '发生RPC的真实在途TOOL Attempt';
COMMENT ON COLUMN public.agent_runtime_execution_program_child_runs.ordinal IS '该Attempt内真实sandbox RPC顺序，唯一拥有一个child准入';
COMMENT ON COLUMN public.agent_runtime_execution_program_child_runs.child_run_id IS '真实MEMBER child，与原parent共享预算root，不在parent插入第二currentAction';
