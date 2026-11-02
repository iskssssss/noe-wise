-- 观测边界只附着于真实Attempt行，不创建第二状态、队列或调用账本。
ALTER TABLE public.agent_runtime_execution_attempts ADD COLUMN observation_sequence bigint GENERATED ALWAYS AS IDENTITY;
CREATE UNIQUE INDEX execution_attempts_observation_sequence ON public.agent_runtime_execution_attempts(observation_sequence);
COMMENT ON COLUMN public.agent_runtime_execution_attempts.observation_sequence IS '真实Attempt注册时分配的稳定观测分页游标；仅用于upperBound，不表示Provider已发送、完成或有用量';

DROP VIEW public.observation_session_model_attempts;
CREATE VIEW public.observation_session_model_attempts WITH(security_invoker=true) AS
SELECT attempt.observation_sequence AS attempt_ledger_id,session.tenant_id,run.session_id,run.root_input_id,run.run_id,
       invocation.invocation_id,attempt.attempt_id,invocation.provider,
       COALESCE(NULLIF(invocation.metadata->>'model',''),NULLIF(invocation.service,'')) AS model,
       COALESCE(NULLIF(invocation.metadata->>'call_point',''),NULLIF(invocation.metadata->>'callPoint',''),'model.chat') AS call_point,
       COALESCE(attempt.result_kind,'UNRESOLVED') AS status,invocation.started_at,
       event.event_id AS trace_node_id,
       input.reported AS prompt_tokens,output.reported AS completion_tokens
FROM public.agent_runtime_execution_attempts attempt
JOIN public.agent_runtime_execution_actions action ON action.tenant_id=attempt.tenant_id AND action.run_id=attempt.run_id AND action.action_id=attempt.action_id AND action.kind='MODEL'
JOIN public.agent_runtime_execution_runs run ON run.tenant_id=attempt.tenant_id AND run.run_id=attempt.run_id
JOIN public.nw_chat_sessions session ON session.tenant_id::text=run.tenant_id AND session.session_id=run.session_id
LEFT JOIN LATERAL(SELECT i.* FROM public.nw_external_invocations i WHERE i.tenant_id=session.tenant_id AND i.attempt_id=attempt.attempt_id AND i.operation_type='model.chat'
    ORDER BY i.id LIMIT 1) invocation ON true
LEFT JOIN public.agent_runtime_budget_attempt_amounts input ON input.tenant_id=attempt.tenant_id AND input.attempt_id=attempt.attempt_id AND input.dimension='INPUT_TOKENS'
LEFT JOIN public.agent_runtime_budget_attempt_amounts output ON output.tenant_id=attempt.tenant_id AND output.attempt_id=attempt.attempt_id AND output.dimension='OUTPUT_TOKENS'
LEFT JOIN LATERAL(SELECT e.event_id FROM public.agent_runtime_execution_events e WHERE e.tenant_id=attempt.tenant_id AND e.run_id=attempt.run_id
    AND e.canonical_json::jsonb->>'actionId'=action.action_id
    AND (e.canonical_json::jsonb->>'attemptId'=attempt.attempt_id OR action.selected_attempt=attempt.attempt_id)
    ORDER BY e.event_sequence LIMIT 1) event ON true;
COMMENT ON VIEW public.observation_session_model_attempts IS '由真实typed MODEL Attempt驱动的分母；原外部审计和budget reported精确关联，缺失不补零、不伪造调用时间';

GRANT SELECT ON public.observation_session_model_attempts TO skillforge;
GRANT USAGE,SELECT ON SEQUENCE public.agent_runtime_execution_attempts_observation_sequence_seq TO skillforge;
