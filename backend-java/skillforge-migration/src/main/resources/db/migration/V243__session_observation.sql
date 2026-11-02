-- 平台会话观测只增加只读投影与查询索引，不创建第二套会话或计量权威状态。
CREATE INDEX IF NOT EXISTS idx_skillforge_chat_sessions_observation_recent
    ON public.nw_chat_sessions (updated_at DESC, tenant_id, session_id);

CREATE INDEX IF NOT EXISTS idx_skillforge_chat_sessions_observation_tenant_recent
    ON public.nw_chat_sessions (tenant_id, updated_at DESC, session_id);

CREATE INDEX IF NOT EXISTS idx_skillforge_chat_messages_observation_session
    ON public.nw_chat_messages (tenant_id, session_id, id DESC);

CREATE INDEX IF NOT EXISTS idx_skillforge_chat_messages_observation_root
    ON public.nw_chat_messages (
        tenant_id,
        session_id,
        (COALESCE(metadata ->> 'root_input_id', metadata ->> 'rootInputId')),
        id DESC);

CREATE INDEX IF NOT EXISTS idx_runtime_session_events_observation_root
    ON public.agent_runtime_session_events
        (tenant_id, session_id, root_input_id, session_sequence);

CREATE INDEX IF NOT EXISTS idx_runtime_runs_observation_root
    ON public.agent_runtime_runs
        (tenant_id, session_id, causation_input_id, created_epoch_second, created_nano);

CREATE INDEX IF NOT EXISTS idx_runtime_preparations_observation_run
    ON public.nw_runtime_session_preparations (tenant_id, run_id);

CREATE INDEX IF NOT EXISTS idx_runtime_action_requests_observation_run
    ON public.agent_runtime_session_action_requests (tenant_id, run_id);

-- 新记录由 Runtime typed key 同步写入最小只读投影；历史记录保持 NULL，观测层显示 UNASSOCIATED。
ALTER TABLE public.nw_runtime_session_preparations
    ADD COLUMN loop_id text,
    ADD COLUMN iteration integer,
    ADD CONSTRAINT ck_runtime_preparation_observation_identity CHECK (
        (loop_id IS NULL AND iteration IS NULL)
        OR (loop_id IS NOT NULL AND iteration IS NOT NULL
            AND btrim(loop_id)<>'' AND iteration>0));

ALTER TABLE public.agent_runtime_session_action_requests
    ADD COLUMN loop_id text,
    ADD COLUMN iteration integer,
    ADD COLUMN action_id text,
    ADD CONSTRAINT ck_runtime_action_request_observation_identity CHECK (
        (loop_id IS NULL AND iteration IS NULL AND action_id IS NULL)
        OR (loop_id IS NOT NULL AND iteration IS NOT NULL AND action_id IS NOT NULL
            AND btrim(loop_id)<>'' AND iteration>0 AND btrim(action_id)<>''));

COMMENT ON COLUMN public.nw_runtime_session_preparations.loop_id IS
    'Runtime typed preparation key 的只读循环投影；历史 NULL 不推断';
COMMENT ON COLUMN public.nw_runtime_session_preparations.iteration IS
    'Runtime typed preparation key 的只读迭代投影；历史 NULL 不推断';
COMMENT ON COLUMN public.agent_runtime_session_action_requests.loop_id IS
    'Runtime typed action request key 的只读循环投影；历史 NULL 不推断';
COMMENT ON COLUMN public.agent_runtime_session_action_requests.iteration IS
    'Runtime typed action request key 的只读迭代投影；历史 NULL 不推断';
COMMENT ON COLUMN public.agent_runtime_session_action_requests.action_id IS
    'Runtime typed action request key 的只读动作投影；历史 NULL 不推断';

CREATE INDEX IF NOT EXISTS idx_skillforge_external_invocations_observation_attempt
    ON public.nw_external_invocations (tenant_id, attempt_id, started_at)
    WHERE operation_type = 'model.chat';

CREATE VIEW public.observation_session_model_attempts
WITH (security_invoker=true) AS
WITH latest_fact AS (
    SELECT DISTINCT ON (tenant_id, attempt_id)
           tenant_id, attempt_id, action_id
      FROM public.agent_runtime_action_attempt_facts
     ORDER BY tenant_id, attempt_id, evidence_revision DESC
)
SELECT invocation.id AS attempt_ledger_id,
       invocation.tenant_id,
       COALESCE(action.session_id, source_event.session_id,
                usage.session_id) AS session_id,
       COALESCE(invocation.root_input_id, source_event.root_input_id,
                usage.root_input_id) AS root_input_id,
       COALESCE(action.run_id, source_event.run_id, usage.run_id) AS run_id,
       invocation.invocation_id,
       invocation.attempt_id,
       invocation.provider,
       COALESCE(NULLIF(invocation.metadata ->> 'model', ''),
                NULLIF(invocation.service, ''), 'unknown') AS model,
       COALESCE(NULLIF(invocation.metadata ->> 'call_point', ''),
                NULLIF(invocation.metadata ->> 'callPoint', ''),
                invocation.operation_type) AS call_point,
       invocation.status,
       invocation.started_at,
       COALESCE(action_event.event_id, source_event.event_id) AS trace_node_id
  FROM public.nw_external_invocations invocation
  LEFT JOIN latest_fact fact
    ON fact.tenant_id = invocation.tenant_id::text
   AND fact.attempt_id = invocation.attempt_id
  LEFT JOIN public.agent_runtime_actions action
    ON action.tenant_id = fact.tenant_id
   AND action.action_id = fact.action_id
  LEFT JOIN LATERAL (
       SELECT report.session_id, report.root_input_id, report.run_id
         FROM public.observation_effective_model_usage report
        WHERE report.tenant_id = invocation.tenant_id
          AND report.attempt_id = invocation.attempt_id
        ORDER BY report.ingested_at DESC, report.id DESC
        LIMIT 1
  ) usage ON true
  LEFT JOIN LATERAL (
       SELECT event.session_id, event.root_input_id, event.run_id, event.event_id
         FROM public.agent_runtime_session_events event
        WHERE event.tenant_id = invocation.tenant_id::text
          AND event.event_id = invocation.source_event_id
        ORDER BY event.session_sequence
        LIMIT 1
  ) source_event ON true
  LEFT JOIN LATERAL (
       SELECT event.event_id
         FROM public.agent_runtime_session_events event
        WHERE event.tenant_id = invocation.tenant_id::text
          AND event.session_id = action.session_id
          AND event.action_id = action.action_id
        ORDER BY event.session_sequence
        LIMIT 1
  ) action_event ON true
 WHERE invocation.operation_type = 'model.chat'
   AND COALESCE(action.session_id, source_event.session_id,
                usage.session_id) IS NOT NULL;

COMMENT ON VIEW public.observation_session_model_attempts IS
    '模型调用覆盖分母只读投影：每个已登记 external attempt 一行，按 Runtime Action/Session event 精确关联会话与轨迹节点';
