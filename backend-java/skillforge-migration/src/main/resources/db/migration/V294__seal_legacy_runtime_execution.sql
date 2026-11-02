-- 执行写入只由 V292 typed Store 拥有；本迁移不迁移、不删除、不重放任何历史业务事实。
-- 保留旧事实的 RLS/FK 和读取。仅 V287 已有的精确冷归档证明允许单向正文表示替换，
-- 它不能改变 owner/status/revision/lease、结果、usage 或任何业务身份。
CREATE FUNCTION public.reject_legacy_runtime_execution_write()
RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    IF TG_OP='UPDATE' AND public.agent_runtime_session_archive_update_valid(
            TG_TABLE_NAME,to_jsonb(OLD),to_jsonb(NEW)) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'legacy_runtime_execution_read_only';
END $$;

DO $seal$
DECLARE table_name text; old_trigger record;
BEGIN
    -- 列表逐一对应已删除的旧 Run/Action、来源图、permit/consume、prepare/journal、
    -- 队列/调度适配器。这里不包含现行 Workflow ExecutionLedger、callback、预算、
    -- DAG、委派、SessionStore/Log、产品SSE session_outbox、Memory 或冻结能力/Profile/Tokenizer。
    FOREACH table_name IN ARRAY ARRAY[
        'agent_runtime_session_input_streams',
        'agent_runtime_input_ledger',
        'agent_runtime_session_inbox',
        'agent_runtime_inbox_mutations',
        'agent_runtime_runs',
        'agent_runtime_run_mutations',
        'agent_runtime_run_state_publications',
        'agent_runtime_run_publication_mutations',
        'agent_runtime_session_admission_prepares',
        'nw_runtime_session_admission_conflict_audits',
        'agent_runtime_session_commit_journals',
        'agent_runtime_actions',
        'agent_runtime_action_attempt_facts',
        'nw_runtime_action_resolutions',
        'nw_runtime_action_permit_consumptions',
        'nw_runtime_action_operations',
        'nw_runtime_action_conflict_audits',
        'agent_runtime_action_state_publications',
        'nw_runtime_action_publication_mutations',
        'nw_runtime_work_queue_items',
        'nw_runtime_work_queue_operations',
        'nw_runtime_work_queue_conflict_audits',
        'agent_runtime_session_outbox',
        'nw_runtime_session_outbox_operations',
        'agent_runtime_event_deliveries',
        'nw_runtime_event_operations',
        'agent_runtime_event_conflict_audits',
        'nw_runtime_scheduler_items',
        'nw_runtime_scheduler_operations',
        'nw_runtime_scheduler_conflict_audits',
        'nw_runtime_workflow_invocations',
        'nw_runtime_knowledge_results',
        'nw_runtime_session_responsibilities',
        'nw_runtime_session_model_operations',
        'nw_runtime_session_model_consumes',
        'nw_runtime_session_model_projection_policies',
        'nw_runtime_session_model_projections',
        'nw_runtime_session_preparations',
        'nw_runtime_session_initial_runs',
        'nw_runtime_session_workflow_inputs',
        'agent_runtime_session_action_requests',
        'agent_runtime_session_action_preparations',
        'nw_runtime_session_knowledge_observations',
        'nw_runtime_session_knowledge_operations',
        'nw_runtime_session_knowledge_consumptions',
        'nw_runtime_session_tool_operations',
        'nw_runtime_session_tool_consumptions',
        'agent_runtime_session_tool_projections',
        'nw_runtime_session_workflow_operations',
        'nw_runtime_session_workflow_consumptions',
        'nw_runtime_session_workflow_projections',
        'nw_runtime_session_workflow_resumes',
        'nw_runtime_session_cancellation_requests',
        'nw_runtime_session_terminal_failures',
        'nw_runtime_tool_unknown_evidence',
        'nw_runtime_tool_unknown_adoptions',
        'nw_runtime_tool_unknown_cases',
        'nw_runtime_tool_unknown_case_operations',
        'nw_runtime_tool_unknown_case_alerts',
        'nw_runtime_tool_input_waits',
        'nw_runtime_session_tool_input_responses',
        'nw_runtime_tool_input_resumes',
        'nw_runtime_workflow_node_preparations',
        'nw_runtime_workflow_node_action_grants',
        'nw_runtime_workflow_wakeups'
    ] LOOP
        -- 仅移除该明确封存表上失用的业务触发器；不触及 FK/internal trigger，
        -- 不按前缀匹配其他现行领域，也不使用 CASCADE 删除表或函数。
        FOR old_trigger IN SELECT tgname FROM pg_trigger
            WHERE tgrelid=format('public.%I',table_name)::regclass AND NOT tgisinternal
        LOOP
            EXECUTE format('DROP TRIGGER %I ON public.%I',old_trigger.tgname,table_name);
        END LOOP;
        EXECUTE format('CREATE TRIGGER legacy_execution_read_only BEFORE INSERT OR UPDATE OR DELETE ON public.%I '
            'FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write()',table_name);
    END LOOP;
END $seal$;

-- 旧 checkpoint 只保留既有生命周期的精确归档/孤儿 GC 删除权，不再接受新执行正文。
-- 其原 checkpoint_retirement/body_ref guard 保留，仍核对真实归档和引用，不授予任意删除。
CREATE TRIGGER legacy_checkpoint_no_new_write BEFORE INSERT OR UPDATE ON public.agent_runtime_session_checkpoints
    FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();
CREATE TRIGGER legacy_checkpoint_body_no_new_write BEFORE INSERT OR UPDATE ON public.agent_runtime_session_checkpoint_bodies
    FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();
CREATE TRIGGER legacy_checkpoint_reference_no_new_write BEFORE INSERT OR UPDATE ON public.agent_runtime_session_checkpoint_body_refs
    FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();

-- DELIVERED 的唯一新写入口是 typed Workflow Wait + 原 HMAC ingress 的同事务闭包。
-- V292 deferred response closure 继续核查真实 accepted response；续租/重排/dead-letter 不受影响。
CREATE FUNCTION public.require_typed_callback_completion()
RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    IF NEW.status='DELIVERED' AND NOT EXISTS(
        SELECT 1 FROM public.agent_runtime_execution_workflow_callback_deliveries b
        WHERE b.tenant_id=NEW.tenant_id AND b.delivery_id=NEW.delivery_id AND b.input_id=NEW.input_id
    ) THEN
        RAISE EXCEPTION 'callback_typed_response_required';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER callback_typed_completion BEFORE UPDATE ON public.agent_runtime_callback_deliveries
    FOR EACH ROW EXECUTE FUNCTION public.require_typed_callback_completion();


-- 读取保护保留历史 UNKNOWN/引用，不重新驱动历史执行；当前 typed Run/Action 同样保护媒体及旧 SessionLog 历史。
-- 用 CREATE OR REPLACE 保持原函数 OID，现有生命周期调用者立即读取当前 typed 事实。
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
        AND action.state IN('CLAIMED','WAITING_INPUT','OUTCOME_UNKNOWN'))
      OR EXISTS(SELECT 1 FROM public.agent_runtime_execution_runs run WHERE run.tenant_id=requested_tenant
        AND run.session_id IN(requested_product_session,requested_runtime_session)
        AND run.status NOT IN('COMPLETED','TERMINATED','FAILED','CANCELLED'))
      OR EXISTS(SELECT 1 FROM public.agent_runtime_execution_actions action
        JOIN public.agent_runtime_execution_runs run ON run.tenant_id=action.tenant_id AND run.run_id=action.run_id
        WHERE run.tenant_id=requested_tenant AND run.session_id IN(requested_product_session,requested_runtime_session)
          AND action.status IN('DISPATCHED','WAITING_INPUT','UNKNOWN')) THEN RETURN false; END IF;
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

CREATE OR REPLACE FUNCTION public.agent_runtime_session_history_owner_protection(t text,s text,k text,id text,u bigint)
RETURNS text[] LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE reasons text[];
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_history_tenant_required'; END IF;
 SELECT COALESCE(array_agg(DISTINCT reason ORDER BY reason),'{}'::text[]) INTO reasons FROM (
    SELECT 'ACTIVE_OR_WAITING_RUN' AS reason WHERE EXISTS(SELECT 1 FROM public.agent_runtime_runs r
        WHERE (r.tenant_id,r.session_id,r.target_kind,r.target_id,r.end_user_id)=(t,s,k,id,u)
          AND r.status NOT IN('COMPLETED','TERMINATED','FAILED','CANCELLED'))
    UNION ALL SELECT 'UNKNOWN_EFFECT' WHERE EXISTS(SELECT 1 FROM public.agent_runtime_actions a
        WHERE a.tenant_id=t AND a.session_id=s AND a.state='OUTCOME_UNKNOWN')
    UNION ALL SELECT 'ACTIVE_OR_WAITING_ACTION' WHERE EXISTS(SELECT 1 FROM public.agent_runtime_actions a
        WHERE a.tenant_id=t AND a.session_id=s AND a.state IN('CLAIMED','WAITING_INPUT'))
    UNION ALL SELECT 'ACTIVE_OR_WAITING_RUN' WHERE EXISTS(SELECT 1 FROM public.agent_runtime_execution_runs r
        WHERE (r.tenant_id,r.session_id,r.target_kind,r.target_id,r.end_user_id)=(t,s,k,id,u)
          AND r.status NOT IN('COMPLETED','TERMINATED','FAILED','CANCELLED'))
    UNION ALL SELECT 'UNKNOWN_EFFECT' WHERE EXISTS(SELECT 1 FROM public.agent_runtime_execution_actions a
        JOIN public.agent_runtime_execution_runs r ON r.tenant_id=a.tenant_id AND r.run_id=a.run_id
        WHERE (r.tenant_id,r.session_id,r.target_kind,r.target_id,r.end_user_id)=(t,s,k,id,u) AND a.status='UNKNOWN')
    UNION ALL SELECT 'ACTIVE_OR_WAITING_ACTION' WHERE EXISTS(SELECT 1 FROM public.agent_runtime_execution_actions a
        JOIN public.agent_runtime_execution_runs r ON r.tenant_id=a.tenant_id AND r.run_id=a.run_id
        WHERE (r.tenant_id,r.session_id,r.target_kind,r.target_id,r.end_user_id)=(t,s,k,id,u)
          AND a.status IN('DISPATCHED','WAITING_INPUT'))
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

CREATE OR REPLACE FUNCTION public.agent_runtime_session_event_referenced(t text,s text,k text,id text,u bigint,eid text)
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
    AND public.agent_runtime_session_task_snapshot_referenced(public.agent_runtime_session_archived_event_payload(a.tenant_id,a.session_id,a.target_kind,a.target_id,a.end_user_id,a.event_id,a.payload_canonical_json),t,s,k,id,u,eid,source_sequence))
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
 OR EXISTS(SELECT 1 FROM public.agent_runtime_execution_runs r
    WHERE r.tenant_id=t AND r.caused_by_event_id=eid)
 OR public.agent_runtime_session_event_host_referenced(t,s,eid)
 INTO referenced;
 RETURN referenced;
END $$;
