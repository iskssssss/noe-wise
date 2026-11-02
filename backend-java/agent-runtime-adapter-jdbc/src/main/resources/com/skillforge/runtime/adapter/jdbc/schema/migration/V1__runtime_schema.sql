-- Runtime JDBC PostgreSQL 16 基线；只包含 Runtime 拥有的存储与约束。
-- 宿主提供数据库、角色及最小权限；本迁移不创建角色或安装宿主业务表。
SET LOCAL check_function_bodies = false;
SET LOCAL search_path = public, pg_catalog;
CREATE EXTENSION IF NOT EXISTS vector;
--
-- Name: agent_runtime_callback_proof_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_callback_proof_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'callback_ingress_proof_immutable';
END;
$$;



--
-- Name: agent_runtime_memory_head_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_memory_head_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'agent runtime memory head cannot be deleted'; END IF;
 IF (NEW.tenant_id,NEW.fact_id,NEW.end_user_id,NEW.agent_id,NEW.session_id,NEW.group_binding_id,NEW.subject_end_user_id,NEW.scope,NEW.type_id)
 IS DISTINCT FROM (OLD.tenant_id,OLD.fact_id,OLD.end_user_id,OLD.agent_id,OLD.session_id,OLD.group_binding_id,OLD.subject_end_user_id,OLD.scope,OLD.type_id)
 OR NEW.current_revision<>OLD.current_revision+1 THEN
  RAISE EXCEPTION 'agent runtime memory head identity or revision jump';
 END IF;
 RETURN NEW;
END $$;



--
-- Name: agent_runtime_memory_history_unprotected(text, text, bigint, timestamp with time zone); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_memory_history_unprotected(requested_tenant text, requested_fact text, requested_revision bigint, cutoff timestamp with time zone) RETURNS boolean
    LANGUAGE plpgsql STABLE
    AS $$
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



--
-- Name: agent_runtime_memory_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_memory_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'agent runtime memory history is immutable';
END;
$$;



--
-- Name: agent_runtime_model_binding_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_model_binding_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'model_binding_immutable';
END;
$$;



--
-- Name: agent_runtime_model_binding_revision(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_model_binding_revision() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE previous_revision bigint;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('model-binding:' || length(NEW.tenant_id)::text || ':' || NEW.tenant_id || NEW.binding_id, 0));
    SELECT COALESCE(max(revision), 0) INTO previous_revision FROM agent_runtime_model_bindings
        WHERE tenant_id = NEW.tenant_id AND binding_id = NEW.binding_id;
    IF NEW.revision <> previous_revision + 1 THEN
        RAISE EXCEPTION 'model_binding_revision_invalid';
    END IF;
    RETURN NEW;
END;
$$;



--
-- Name: agent_runtime_session_archive_gate(jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_archive_gate(cold jsonb) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE t text:=cold->>'tenant_id'; s text:=cold->>'owner_session_id';
 k text:=cold->>'owner_target_kind'; id text:=cold->>'owner_target_id'; u bigint:=(cold->>'owner_end_user_id')::bigint;
 source public.agent_runtime_sessions; run public.agent_runtime_runs; root bigint;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 SELECT o.end_user_id INTO root FROM public.agent_runtime_session_owners o
  WHERE (o.tenant_id,o.session_id,o.target_kind,o.target_id,o.end_user_id)=(t,s,k,id,u) FOR NO KEY UPDATE NOWAIT;
 SELECT h.* INTO source FROM public.agent_runtime_sessions h
  WHERE (h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=(t,s,k,id,u) FOR UPDATE NOWAIT;
 IF root IS NULL OR source.status IS DISTINCT FROM 'CLOSED'
  OR (source.session_revision,source.updated_epoch_second,source.updated_nano) IS DISTINCT FROM
    ((cold->>'source_session_revision')::bigint,(cold->>'closed_epoch_second')::bigint,(cold->>'closed_nano')::integer)
  OR source.updated_epoch_second::numeric+source.updated_nano::numeric/1000000000
       >extract(epoch FROM clock_timestamp()-interval '30 days')
 THEN RAISE EXCEPTION 'session_archive_source_changed'; END IF;
 IF cardinality(public.agent_runtime_session_owner_archive_protection(t,s,k,id,u))>0
 THEN RAISE EXCEPTION 'session_archive_owner_protected'; END IF;
 IF cold->>'source_run_id' IS NOT NULL THEN
  SELECT h.* INTO run FROM public.agent_runtime_runs h
   WHERE h.tenant_id=t AND h.run_id=cold->>'source_run_id' FOR NO KEY UPDATE NOWAIT;
  IF (run.tenant_id,run.session_id,run.target_kind,run.target_id,run.end_user_id) IS DISTINCT FROM (t,s,k,id,u)
    OR run.status NOT IN('COMPLETED','TERMINATED','FAILED','CANCELLED')
    OR (run.run_revision,run.updated_epoch_second,run.updated_nano) IS DISTINCT FROM
      ((cold->>'source_run_revision')::bigint,(cold->>'source_run_updated_epoch_second')::bigint,(cold->>'source_run_updated_nano')::integer)
    OR run.updated_epoch_second::numeric+run.updated_nano::numeric/1000000000
       >extract(epoch FROM clock_timestamp()-interval '30 days')
  THEN RAISE EXCEPTION 'session_archive_run_source_changed'; END IF;
 END IF;
END $$;



--
-- Name: agent_runtime_session_archive_insert_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_archive_insert_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $_$
DECLARE spec record; cold jsonb:=to_jsonb(NEW); original jsonb; hot jsonb; key jsonb:='{}';
 field text; expected text; key_where text; actual_run_id text; event public.agent_runtime_session_events;
BEGIN
 SELECT * INTO STRICT spec FROM public.agent_runtime_session_archive_spec(TG_ARGV[0]::integer);
 IF TG_TABLE_NAME<>spec.cold_table THEN RAISE EXCEPTION 'session_archive_fixed_table_required'; END IF;
 PERFORM public.agent_runtime_session_archive_gate(cold);
 FOREACH field IN ARRAY spec.key_columns LOOP key:=key||jsonb_build_object(field,cold->field); END LOOP;
 SELECT string_agg('h.'||quote_ident(x)||'=($6->>'||quote_literal(x)||')',' AND ') INTO key_where FROM unnest(spec.key_columns) x;
 EXECUTE 'SELECT to_jsonb(h),'||spec.run_sql||' FROM '||spec.from_sql||' WHERE '||spec.owner_sql||' AND '||key_where
  INTO hot,actual_run_id USING NEW.tenant_id,NEW.owner_session_id,NEW.owner_target_kind,NEW.owner_target_id,NEW.owner_end_user_id,key;
 original:=public.agent_runtime_session_archive_original_row(cold,spec.payload_columns,TG_ARGV[0]='9');
 IF hot IS NULL OR hot IS DISTINCT FROM original OR NEW.source_run_id IS DISTINCT FROM actual_run_id
  OR encode(sha256(convert_to(original::text,'UTF8')),'hex') IS DISTINCT FROM NEW.original_row_sha256
 THEN RAISE EXCEPTION 'session_archive_exact_hot_source_required'; END IF;
 FOREACH field IN ARRAY spec.payload_columns LOOP
  IF field='payload_data' THEN expected:=encode(sha256(decode(substring(cold->>field FROM 3),'hex')),'hex');
  ELSE expected:=public.agent_runtime_session_archive_text_digest(cold->>field);
    IF public.agent_runtime_session_archive_is_marker(cold->>field) THEN RAISE EXCEPTION 'session_archive_original_text_required'; END IF;
  END IF;
  IF expected IS DISTINCT FROM cold->>(field||'_sha256') THEN RAISE EXCEPTION 'session_archive_payload_digest_rejected'; END IF;
 END LOOP;
 IF TG_ARGV[0]='10' THEN
  -- Ownership is not a recovery reference. Hold a real edge until the copy commits.
  PERFORM 1 FROM public.agent_runtime_session_checkpoint_body_refs r
   WHERE (r.tenant_id,r.run_id,r.body_hash)=(NEW.tenant_id,NEW.run_id,NEW.content_hash)
   FOR KEY SHARE;
  IF NOT FOUND THEN RAISE EXCEPTION 'session_checkpoint_archive_body_reference_required'; END IF;
 END IF;
 IF TG_ARGV[0]='9' THEN
  event:=jsonb_populate_record(NULL::public.agent_runtime_session_events,original);
  IF NEW.payload_canonical_json IS DISTINCT FROM public.agent_runtime_session_event_payload(event.tenant_id,event.session_id,
    event.target_kind,event.target_id,event.end_user_id,event.run_id,event.event_id,event.event_type,event.payload_schema_id,event.payload_canonical_json)
  THEN RAISE EXCEPTION 'session_archive_event_original_digest_rejected'; END IF;
  IF NEW.original_event_digest IS DISTINCT FROM public.agent_runtime_session_event_digest(event)
   OR NEW.original_event_digest IS DISTINCT FROM public.agent_runtime_session_event_digest(
    jsonb_populate_record(NULL::public.agent_runtime_session_events,cold))
  THEN RAISE EXCEPTION 'session_archive_event_logical_digest_rejected'; END IF;
 END IF;
 RETURN NEW;
END $_$;



--
-- Name: agent_runtime_session_archive_is_marker(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_archive_is_marker(value text) RETURNS boolean
    LANGUAGE sql IMMUTABLE
    AS $_$
 SELECT coalesce(starts_with(value,'{"$runtimeSessionArchive":"v1","sha256":"'),false)
$_$;



--
-- Name: agent_runtime_session_archive_marker(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_archive_marker(value text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT
    AS $_$
 SELECT '{"$runtimeSessionArchive":"v1","sha256":"'||
     public.agent_runtime_session_archive_text_digest(value)||'"}'
$_$;



--
-- Name: agent_runtime_session_archive_original_row(jsonb, text[], boolean); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_archive_original_row(cold jsonb, payload_columns text[], event_row boolean DEFAULT false) RETURNS jsonb
    LANGUAGE plpgsql IMMUTABLE
    AS $$
DECLARE result jsonb; field text;
BEGIN
 result:=cold-ARRAY['owner_session_id','owner_target_kind','owner_target_id','owner_end_user_id',
 'source_session_revision','closed_epoch_second','closed_nano','source_run_id','source_run_revision',
 'source_run_updated_epoch_second','source_run_updated_nano','original_row_sha256','archived_at','original_payload_canonical_json','original_event_digest'];
 FOREACH field IN ARRAY payload_columns LOOP result:=result-(field||'_sha256'); END LOOP;
 IF event_row THEN result:=jsonb_set(result,'{payload_canonical_json}',cold->'original_payload_canonical_json'); END IF;
 RETURN result;
END $$;



--
-- Name: agent_runtime_session_archive_spec(integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_archive_spec(requested_phase integer) RETURNS TABLE(hot_table text, cold_table text, payload_columns text[], key_columns text[], from_sql text, owner_sql text, run_sql text)
    LANGUAGE sql IMMUTABLE
    AS $_$
 SELECT hot_table,cold_table,payload_columns,key_columns,from_sql,owner_sql,run_sql FROM (VALUES
 (1,'agent_runtime_input_ledger','agent_runtime_input_ledger_archives',ARRAY['command_data','receipt_data']::text[],ARRAY['tenant_id','target_kind','target_id','session_id','input_id']::text[],'public.agent_runtime_input_ledger h','(h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=($1,$2,$3,$4,$5)','h.run_id'),
 (2,'agent_runtime_session_admission_prepares','agent_runtime_session_admission_prepare_archives',ARRAY['command_data']::text[],ARRAY['tenant_id','target_kind','target_id','session_id','input_id']::text[],'public.agent_runtime_session_admission_prepares h','(h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=($1,$2,$3,$4,$5)','NULL::text'),
 (3,'agent_runtime_session_commit_journals','agent_runtime_session_commit_journal_archives',ARRAY['command_data']::text[],ARRAY['tenant_id','run_id','operation_hash']::text[],'public.agent_runtime_session_commit_journals h','(h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=($1,$2,$3,$4,$5)','h.run_id'),
 (4,'agent_runtime_runs','agent_runtime_run_record_archives',ARRAY['record_data']::text[],ARRAY['tenant_id','run_id']::text[],'public.agent_runtime_runs h','(h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=($1,$2,$3,$4,$5)','h.run_id'),
 (5,'agent_runtime_session_inbox','agent_runtime_session_inbox_archives',ARRAY['entry_data']::text[],ARRAY['tenant_id','target_kind','target_id','session_id','input_id']::text[],'public.agent_runtime_session_inbox h','(h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=($1,$2,$3,$4,$5)','(SELECT l.run_id FROM public.agent_runtime_input_ledger l WHERE (l.tenant_id,l.target_kind,l.target_id,l.session_id,l.input_id)=(h.tenant_id,h.target_kind,h.target_id,h.session_id,h.input_id))'),
 (6,'agent_runtime_run_mutations','agent_runtime_run_mutation_archives',ARRAY['output_data']::text[],ARRAY['tenant_id','run_id','mutation_key']::text[],'public.agent_runtime_run_mutations h','(h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=($1,$2,$3,$4,$5)','h.run_id'),
 (7,'agent_runtime_inbox_mutations','agent_runtime_session_inbox_mutation_archives',ARRAY['output_data']::text[],ARRAY['tenant_id','target_kind','target_id','session_id','input_id','mutation_key']::text[],'public.agent_runtime_inbox_mutations h JOIN public.agent_runtime_session_inbox o ON (o.tenant_id,o.target_kind,o.target_id,o.session_id,o.input_id)=(h.tenant_id,h.target_kind,h.target_id,h.session_id,h.input_id)','(o.tenant_id,o.session_id,o.target_kind,o.target_id,o.end_user_id)=($1,$2,$3,$4,$5)','(SELECT l.run_id FROM public.agent_runtime_input_ledger l WHERE (l.tenant_id,l.target_kind,l.target_id,l.session_id,l.input_id)=(h.tenant_id,h.target_kind,h.target_id,h.session_id,h.input_id))'),
 (8,'agent_runtime_run_publication_mutations','agent_runtime_run_publication_mutation_archives',ARRAY['output_data']::text[],ARRAY['tenant_id','source_id','event_id','mutation_key']::text[],'public.agent_runtime_run_publication_mutations h JOIN public.agent_runtime_runs o ON (o.tenant_id,o.run_id)=(h.tenant_id,h.source_id)','(o.tenant_id,o.session_id,o.target_kind,o.target_id,o.end_user_id)=($1,$2,$3,$4,$5)','h.source_id'),
 (9,'agent_runtime_session_events','agent_runtime_session_event_payload_archives',ARRAY['payload_canonical_json']::text[],ARRAY['tenant_id','target_kind','target_id','session_id','event_id']::text[],'public.agent_runtime_session_events h','(h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=($1,$2,$3,$4,$5)','h.run_id'),
 (10,'agent_runtime_session_checkpoint_bodies','agent_runtime_session_checkpoint_body_archives',ARRAY['payload_data']::text[],ARRAY['tenant_id','run_id','content_hash']::text[],'public.agent_runtime_session_checkpoint_bodies h JOIN public.agent_runtime_runs o ON (o.tenant_id,o.run_id)=(h.tenant_id,h.run_id)','(o.tenant_id,o.session_id,o.target_kind,o.target_id,o.end_user_id)=($1,$2,$3,$4,$5)','h.run_id'),
 (11,'agent_runtime_session_checkpoints','agent_runtime_session_checkpoint_archives',ARRAY['payload_data']::text[],ARRAY['tenant_id','run_id','content_hash']::text[],'public.agent_runtime_session_checkpoints h JOIN public.agent_runtime_runs o ON (o.tenant_id,o.run_id)=(h.tenant_id,h.run_id)','(o.tenant_id,o.session_id,o.target_kind,o.target_id,o.end_user_id)=($1,$2,$3,$4,$5)','h.run_id'),
 (12,'agent_runtime_session_checkpoint_bodies','agent_runtime_session_checkpoint_body_archives',ARRAY['payload_data']::text[],ARRAY['tenant_id','run_id','content_hash']::text[],'public.agent_runtime_session_checkpoint_bodies h JOIN public.agent_runtime_runs o ON (o.tenant_id,o.run_id)=(h.tenant_id,h.run_id)','(o.tenant_id,o.session_id,o.target_kind,o.target_id,o.end_user_id)=($1,$2,$3,$4,$5)','h.run_id')
 ) fixed(phase,hot_table,cold_table,payload_columns,key_columns,from_sql,owner_sql,run_sql) WHERE phase=requested_phase
$_$;



--
-- Name: agent_runtime_session_archive_text_digest(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_archive_text_digest(value text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT
    AS $$
 SELECT encode(sha256(convert_to('skillforge.runtime.session.private-archive.text.v1','UTF8')
     ||decode('00','hex')||convert_to(value,'UTF8')),'hex')
$$;



--
-- Name: agent_runtime_session_archive_update_valid(text, jsonb, jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_archive_update_valid(hot_table text, before_row jsonb, after_row jsonb) RETURNS boolean
    LANGUAGE plpgsql
    AS $_$
DECLARE spec record; cold jsonb; original jsonb; field text; key_where text; key jsonb:='{}'; changed boolean:=false;
BEGIN
 SELECT * INTO spec FROM public.agent_runtime_session_archive_spec(
  CASE hot_table
   WHEN 'agent_runtime_input_ledger' THEN 1 WHEN 'agent_runtime_session_admission_prepares' THEN 2
   WHEN 'agent_runtime_session_commit_journals' THEN 3 WHEN 'agent_runtime_runs' THEN 4
   WHEN 'agent_runtime_session_inbox' THEN 5 WHEN 'agent_runtime_run_mutations' THEN 6
   WHEN 'agent_runtime_inbox_mutations' THEN 7 WHEN 'agent_runtime_run_publication_mutations' THEN 8
   WHEN 'agent_runtime_session_events' THEN 9 ELSE 0 END);
 IF spec.hot_table IS NULL THEN RETURN false; END IF;
 IF (before_row-spec.payload_columns) IS DISTINCT FROM (after_row-spec.payload_columns) THEN RETURN false; END IF;
 FOREACH field IN ARRAY spec.key_columns LOOP key:=key||jsonb_build_object(field,before_row->field); END LOOP;
 SELECT string_agg('h.'||quote_ident(x)||'=($2->>'||quote_literal(x)||')',' AND ') INTO key_where FROM unnest(spec.key_columns) x;
 EXECUTE format('SELECT to_jsonb(h) FROM public.%I h WHERE h.tenant_id=$1 AND %s',spec.cold_table,key_where)
  INTO cold USING before_row->>'tenant_id',key;
 IF cold IS NULL THEN RETURN false; END IF;
 original:=public.agent_runtime_session_archive_original_row(cold,spec.payload_columns,hot_table='agent_runtime_session_events');
 IF original IS DISTINCT FROM before_row
  OR encode(sha256(convert_to(original::text,'UTF8')),'hex') IS DISTINCT FROM cold->>'original_row_sha256' THEN RETURN false; END IF;
 FOREACH field IN ARRAY spec.payload_columns LOOP
  IF public.agent_runtime_session_archive_is_marker(before_row->>field)
    OR after_row->>field IS DISTINCT FROM public.agent_runtime_session_archive_marker(cold->>field)
  THEN RETURN false; END IF;
  changed:=changed OR before_row->>field IS DISTINCT FROM after_row->>field;
 END LOOP;
 IF NOT changed THEN RETURN false; END IF;
 PERFORM public.agent_runtime_session_archive_gate(cold);
 IF hot_table='agent_runtime_session_events' AND public.agent_runtime_session_event_referenced(
  cold->>'tenant_id',cold->>'owner_session_id',cold->>'owner_target_kind',cold->>'owner_target_id',
  (cold->>'owner_end_user_id')::bigint,cold->>'event_id')
 THEN RAISE EXCEPTION 'session_archive_event_source_reference'; END IF;
 RETURN true;
END $_$;



--
-- Name: agent_runtime_session_archived_event_payload(text, text, text, text, bigint, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_archived_event_payload(t text, s text, k text, id text, u bigint, requested_event_id text, live text) RETURNS text
    LANGUAGE plpgsql STABLE
    AS $$
DECLARE original text; digest text;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 IF NOT public.agent_runtime_session_archive_is_marker(live) THEN RETURN live; END IF;
 SELECT h.payload_canonical_json,h.payload_canonical_json_sha256 INTO original,digest FROM public.agent_runtime_session_event_payload_archives h
  WHERE (h.tenant_id,h.owner_session_id,h.owner_target_kind,h.owner_target_id,h.owner_end_user_id)=(t,s,k,id,u)
   AND h.event_id=requested_event_id;
 IF original IS NULL OR digest IS DISTINCT FROM public.agent_runtime_session_archive_text_digest(original)
  OR live IS DISTINCT FROM public.agent_runtime_session_archive_marker(original)
 THEN RAISE EXCEPTION 'session_archive_exact_reference_rejected'; END IF;
 RETURN original;
END $$;



--
-- Name: agent_runtime_session_checkpoint_archive_ref_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_checkpoint_archive_ref_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE checkpoint jsonb; body jsonb;
BEGIN
 SELECT to_jsonb(a) INTO checkpoint FROM public.agent_runtime_session_checkpoint_archives a
  WHERE (a.tenant_id,a.run_id,a.content_hash)=(NEW.tenant_id,NEW.run_id,NEW.checkpoint_hash) FOR KEY SHARE;
 SELECT to_jsonb(a) INTO body FROM public.agent_runtime_session_checkpoint_body_archives a
  WHERE (a.tenant_id,a.run_id,a.content_hash)=(NEW.tenant_id,NEW.run_id,NEW.body_hash) FOR KEY SHARE;
 IF checkpoint IS NULL OR body IS NULL OR
  (checkpoint->>'owner_session_id',checkpoint->>'owner_target_kind',checkpoint->>'owner_target_id',checkpoint->>'owner_end_user_id',
   checkpoint->>'source_session_revision',checkpoint->>'closed_epoch_second',checkpoint->>'closed_nano',checkpoint->>'identity_data') IS DISTINCT FROM
  (body->>'owner_session_id',body->>'owner_target_kind',body->>'owner_target_id',body->>'owner_end_user_id',
   body->>'source_session_revision',body->>'closed_epoch_second',body->>'closed_nano',body->>'identity_data')
  OR NOT EXISTS(SELECT 1 FROM public.agent_runtime_session_checkpoint_body_refs r
   WHERE (r.tenant_id,r.run_id,r.checkpoint_hash,r.body_hash)=(NEW.tenant_id,NEW.run_id,NEW.checkpoint_hash,NEW.body_hash))
 THEN RAISE EXCEPTION 'session_checkpoint_archive_reference_rejected'; END IF;
 RETURN NEW;
END $$;



--
-- Name: agent_runtime_session_checkpoint_body_ref_retirement_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_checkpoint_body_ref_retirement_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF OLD.tenant_id IS DISTINCT FROM current_setting('app.tenant_id',true)
 THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 PERFORM 1 FROM public.agent_runtime_session_checkpoint_archive_body_refs r
  WHERE (r.tenant_id,r.run_id,r.checkpoint_hash,r.body_hash)=
   (OLD.tenant_id,OLD.run_id,OLD.checkpoint_hash,OLD.body_hash) FOR KEY SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'session_checkpoint_archive_body_reference_required'; END IF;
 RETURN OLD;
END $$;



--
-- Name: agent_runtime_session_checkpoint_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_checkpoint_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN RAISE EXCEPTION 'session_checkpoint_immutable'; END;
$$;



--
-- Name: agent_runtime_session_checkpoint_retirement_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_checkpoint_retirement_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $_$
DECLARE cold jsonb; original jsonb; spec record; p integer;
BEGIN
 p:=CASE TG_TABLE_NAME WHEN 'agent_runtime_session_checkpoints' THEN 11
  WHEN 'agent_runtime_session_checkpoint_bodies' THEN 10 ELSE 0 END;
 SELECT * INTO STRICT spec FROM public.agent_runtime_session_archive_spec(p);
 IF TG_OP='INSERT' THEN
  EXECUTE format('SELECT to_jsonb(a) FROM public.%I a WHERE (a.tenant_id,a.run_id,a.content_hash)=($1,$2,$3)',spec.cold_table)
   INTO cold USING NEW.tenant_id,NEW.run_id,NEW.content_hash;
  IF cold IS NULL THEN RETURN NEW; END IF;
  original:=public.agent_runtime_session_archive_original_row(cold,spec.payload_columns);
  IF (original->>'identity_data',original->>'schema_id',original->>'payload_data') IS DISTINCT FROM
     (NEW.identity_data,NEW.schema_id,'\x'||encode(NEW.payload_data,'hex'))
  THEN RAISE EXCEPTION 'session_checkpoint_archive_conflict'; END IF;
  -- A repeated exact freeze already has a durable cold row. Do not resurrect a hot row.
  RETURN NULL;
 END IF;
 EXECUTE format('SELECT to_jsonb(a) FROM public.%I a WHERE (a.tenant_id,a.run_id,a.content_hash)=($1,$2,$3)',spec.cold_table)
  INTO cold USING OLD.tenant_id,OLD.run_id,OLD.content_hash;
 IF p=10 AND cold IS NULL THEN
  IF EXISTS(SELECT 1 FROM public.agent_runtime_session_checkpoint_body_refs r
   WHERE (r.tenant_id,r.run_id,r.body_hash)=(OLD.tenant_id,OLD.run_id,OLD.content_hash))
  THEN RAISE EXCEPTION 'session_checkpoint_hot_body_referenced'; END IF;
  -- Existing unowned orphan GC remains legal. Owner retirement archives before releasing a reference.
  RETURN OLD;
 END IF;
 IF cold IS NULL OR public.agent_runtime_session_archive_original_row(cold,spec.payload_columns) IS DISTINCT FROM to_jsonb(OLD)
  OR cold->>'payload_data_sha256' IS DISTINCT FROM encode(sha256(OLD.payload_data),'hex')
 THEN RAISE EXCEPTION 'session_checkpoint_exact_archive_required'; END IF;
 PERFORM public.agent_runtime_session_archive_gate(cold);
 IF p=10 AND EXISTS(SELECT 1 FROM public.agent_runtime_session_checkpoint_body_refs r
  WHERE (r.tenant_id,r.run_id,r.body_hash)=(OLD.tenant_id,OLD.run_id,OLD.content_hash))
 THEN RAISE EXCEPTION 'session_checkpoint_hot_body_referenced'; END IF;
 IF p=11 AND EXISTS(
  SELECT 1 FROM public.agent_runtime_session_checkpoint_body_refs r
  WHERE (r.tenant_id,r.run_id,r.checkpoint_hash)=(OLD.tenant_id,OLD.run_id,OLD.content_hash)
   AND NOT EXISTS(SELECT 1 FROM public.agent_runtime_session_checkpoint_archive_body_refs a
    WHERE (a.tenant_id,a.run_id,a.checkpoint_hash,a.body_hash)=(r.tenant_id,r.run_id,r.checkpoint_hash,r.body_hash)))
 THEN RAISE EXCEPTION 'session_checkpoint_archive_body_reference_required'; END IF;
 RETURN OLD;
END $_$;



--
-- Name: agent_runtime_session_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_events (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint,
    session_sequence bigint NOT NULL,
    input_id text NOT NULL,
    run_id text,
    execution_id text,
    action_id text,
    delegation_id text,
    event_id text NOT NULL,
    event_fingerprint text NOT NULL,
    event_type text NOT NULL,
    event_version text NOT NULL,
    trace_id text NOT NULL,
    span_id text NOT NULL,
    root_input_id text NOT NULL,
    caused_by_event_id text,
    payload_schema_id text NOT NULL,
    payload_canonical_json text NOT NULL,
    occurred_at_epoch_second bigint NOT NULL,
    occurred_at_nano integer NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    subject_kind text GENERATED ALWAYS AS (
CASE
    WHEN (target_kind = 'GROUP'::text) THEN 'GROUP'::text
    ELSE 'USER'::text
END) STORED NOT NULL,
    subject_id text GENERATED ALWAYS AS (
CASE
    WHEN (target_kind = 'GROUP'::text) THEN target_id
    ELSE (end_user_id)::text
END) STORED NOT NULL,
    CONSTRAINT ck_agent_runtime_session_event_action CHECK (((action_id IS NULL) OR (btrim(action_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_event_delegation CHECK (((delegation_id IS NULL) OR (btrim(delegation_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_event_execution CHECK (((execution_id IS NULL) OR (btrim(execution_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_event_identity_text CHECK (((btrim(event_id) <> ''::text) AND (btrim(event_fingerprint) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_event_input CHECK ((btrim(input_id) <> ''::text)),
    CONSTRAINT ck_agent_runtime_session_event_occurred_at_epoch CHECK (((occurred_at_epoch_second >= '-31557014167219200'::bigint) AND (occurred_at_epoch_second <= '31556889864403199'::bigint))),
    CONSTRAINT ck_agent_runtime_session_event_occurred_at_nano CHECK (((occurred_at_nano >= 0) AND (occurred_at_nano < 1000000000))),
    CONSTRAINT ck_agent_runtime_session_event_payload CHECK (((btrim(payload_schema_id) <> ''::text) AND (btrim(payload_canonical_json) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_event_run CHECK (((run_id IS NULL) OR (btrim(run_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_event_schema CHECK (((btrim(event_type) <> ''::text) AND (btrim(event_version) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_event_sequence CHECK ((session_sequence > 0)),
    CONSTRAINT ck_agent_runtime_session_event_trace CHECK (((btrim(trace_id) <> ''::text) AND (btrim(span_id) <> ''::text) AND (btrim(root_input_id) <> ''::text) AND ((caused_by_event_id IS NULL) OR (btrim(caused_by_event_id) <> ''::text)))),
    CONSTRAINT domain_events_subject_ck CHECK ((((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (end_user_id IS NOT NULL) AND (end_user_id > 0)) OR ((target_kind = 'GROUP'::text) AND (end_user_id IS NULL)))),
    CONSTRAINT group_domain_events_only_ck CHECK (((target_kind <> 'GROUP'::text) OR (payload_schema_id = 'dag.work.v1'::text) OR ((event_type ~~ 'plan.%'::text) AND (payload_schema_id = (('skillforge.'::text || event_type) || '.v1'::text))) OR ((event_type = 'group.memory.source'::text) AND (payload_schema_id = 'skillforge.group.memory-source.v1'::text)) OR ((event_type = 'memory.written'::text) AND (payload_schema_id = 'skillforge.runtime.memory.written.v2'::text)) OR ((event_type = 'retrieval.performed'::text) AND (payload_schema_id = 'skillforge.runtime.memory.retrieval-performed.v3'::text))))
);

ALTER TABLE ONLY public.agent_runtime_session_events FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_event_digest(public.agent_runtime_session_events); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_event_digest(event public.agent_runtime_session_events) RETURNS text
    LANGUAGE plpgsql
    AS $$
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



--
-- Name: agent_runtime_session_event_host_referenced(text, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_event_host_referenced(t text, s text, eid text) RETURNS boolean
    LANGUAGE plpgsql
    AS $function$ BEGIN RETURN false; END $function$;



--
-- Name: agent_runtime_session_event_payload(text, text, text, text, bigint, text, text, text, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_event_payload(requested_tenant text, requested_session text, requested_target_kind text, requested_target text, requested_end_user bigint, requested_run text, requested_event text, requested_type text, requested_schema text, stored text) RETURNS text
    LANGUAGE plpgsql STABLE
    AS $$
DECLARE reference record; restored text := stored; expected_digest text; resolved integer := 0; token text;
BEGIN
    IF public.agent_runtime_session_archive_is_marker(stored) THEN
        RETURN public.agent_runtime_session_archived_event_payload(requested_tenant,requested_session,
            requested_target_kind,requested_target,requested_end_user,requested_event,stored);
    END IF;
    IF requested_type<>'memory.written' OR requested_schema<>'skillforge.runtime.memory.written.v2'
       OR NOT stored ~ '"canonicalJson":"@runtime-session-content:[a-f0-9]{64}"' THEN
        RETURN public.agent_runtime_session_output_payload(requested_tenant,requested_session,requested_target_kind,
            requested_target,requested_end_user,requested_run,requested_schema,stored);
    END IF;
    FOR reference IN SELECT body.content_hash,body.canonical_content,ref.original_payload_digest
        FROM public.agent_runtime_session_event_body_refs ref JOIN public.agent_runtime_session_event_bodies body
            ON (body.tenant_id,body.target_kind,body.target_id,body.session_id,body.content_hash)
                =(ref.tenant_id,ref.target_kind,ref.target_id,ref.session_id,ref.content_hash)
        WHERE ref.tenant_id=requested_tenant AND ref.target_kind=requested_target_kind AND ref.target_id=requested_target
            AND ref.session_id=requested_session AND ref.event_id=requested_event AND body.end_user_id IS NOT DISTINCT FROM requested_end_user LOOP
        IF expected_digest IS NOT NULL AND expected_digest<>reference.original_payload_digest THEN
            RAISE EXCEPTION 'session_event_body_reference_rejected';
        END IF;
        expected_digest := reference.original_payload_digest;
        IF encode(sha256(convert_to('skillforge.runtime.session.memory-event-text.v1','UTF8')||decode('00','hex')||convert_to(reference.canonical_content,'UTF8')),'hex')
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



--
-- Name: agent_runtime_session_event_referenced(text, text, text, text, bigint, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_event_referenced(t text, s text, k text, id text, u bigint, eid text) RETURNS boolean
    LANGUAGE plpgsql
    AS $$
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



--
-- Name: agent_runtime_session_history_answer_guard(public.agent_runtime_session_events); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_history_answer_guard(event public.agent_runtime_session_events) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE first public.agent_runtime_session_events; last public.agent_runtime_session_events; adopted public.agent_runtime_session_events;
    a json; b json; c json; d json; raw text; operation text; attempt text; name text;
BEGIN
 b := public.agent_runtime_session_history_snapshot(event);
 SELECT e.* INTO first FROM public.agent_runtime_session_events e
    WHERE (e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id,e.run_id) =
        (event.tenant_id,event.session_id,event.target_kind,event.target_id,event.end_user_id,event.run_id)
      AND e.event_type='agent.model-answer' AND substring(public.agent_runtime_session_archived_event_payload(e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id,e.event_id,e.payload_canonical_json) FROM '"messageId":"([^"]+)"')=b->>'messageId'
    ORDER BY e.session_sequence LIMIT 1;
 SELECT e.* INTO last FROM public.agent_runtime_session_events e
    WHERE (e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id,e.run_id) =
        (event.tenant_id,event.session_id,event.target_kind,event.target_id,event.end_user_id,event.run_id)
      AND e.event_type='agent.model-answer' AND substring(public.agent_runtime_session_archived_event_payload(e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id,e.event_id,e.payload_canonical_json) FROM '"messageId":"([^"]+)"')=b->>'messageId'
    ORDER BY e.session_sequence DESC LIMIT 1;
 SELECT e.* INTO adopted FROM public.agent_runtime_session_events e
    WHERE (e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id,e.run_id) =
        (event.tenant_id,event.session_id,event.target_kind,event.target_id,event.end_user_id,event.run_id)
      AND e.event_type='agent.model-answer-adopted' AND substring(public.agent_runtime_session_archived_event_payload(e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id,e.event_id,e.payload_canonical_json) FROM '"messageId":"([^"]+)"')=b->>'messageId'
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



--
-- Name: agent_runtime_session_history_closed_guard(public.agent_runtime_session_events); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_history_closed_guard(event public.agent_runtime_session_events) RETURNS void
    LANGUAGE plpgsql
    AS $$
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



--
-- Name: agent_runtime_session_history_field(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_history_field(value text) RETURNS bytea
    LANGUAGE sql IMMUTABLE
    AS $$
    SELECT CASE WHEN value IS NULL THEN int4send(-1)
        ELSE int4send(octet_length(convert_to(value,'UTF8')))||convert_to(value,'UTF8') END
$$;



--
-- Name: agent_runtime_session_history_native_fields(text, text[]); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_history_native_fields(raw text, names text[]) RETURNS json
    LANGUAGE plpgsql IMMUTABLE
    AS $$
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



--
-- Name: agent_runtime_session_history_owner_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_history_owner_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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



--
-- Name: agent_runtime_session_history_owner_protection(text, text, text, text, bigint); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_history_owner_protection(t text, s text, k text, id text, u bigint) RETURNS text[]
    LANGUAGE plpgsql
    AS $$
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



--
-- Name: agent_runtime_session_history_snapshot(public.agent_runtime_session_events); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_history_snapshot(event public.agent_runtime_session_events) RETURNS json
    LANGUAGE plpgsql
    AS $_$
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
END $_$;



--
-- Name: agent_runtime_session_history_string(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_history_string(raw text) RETURNS text
    LANGUAGE plpgsql IMMUTABLE
    AS $_$
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
END $_$;



--
-- Name: agent_runtime_session_history_string_token(text, integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_history_string_token(raw text, start_at integer) RETURNS text
    LANGUAGE plpgsql IMMUTABLE
    AS $$
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



--
-- Name: agent_runtime_session_history_tombstone_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_history_tombstone_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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



--
-- Name: agent_runtime_session_output_payload(text, text, text, text, bigint, text, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_output_payload(requested_tenant text, requested_session text, requested_target_kind text, requested_target text, requested_end_user bigint, requested_run text, requested_schema text, stored text) RETURNS text
    LANGUAGE plpgsql STABLE
    AS $_$
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
$_$;



--
-- Name: agent_runtime_session_owner_archive_protection(text, text, text, text, bigint); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_owner_archive_protection(t text, s text, k text, id text, u bigint) RETURNS text[]
    LANGUAGE plpgsql
    AS $$
DECLARE reasons text[];
BEGIN
 reasons:=public.agent_runtime_session_history_owner_protection(t,s,k,id,u);
 IF EXISTS(SELECT 1 FROM public.agent_runtime_session_inbox h
  WHERE (h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=(t,s,k,id,u)
    AND h.status IN('QUEUED','PROCESSING')) THEN reasons:=array_append(reasons,'ACTIVE_INBOX'); END IF;
 IF EXISTS(SELECT 1 FROM public.agent_runtime_runs h
  WHERE (h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=(t,s,k,id,u)
    AND h.status IN('COMPLETED','TERMINATED','FAILED','CANCELLED')
    AND h.updated_epoch_second::numeric+h.updated_nano::numeric/1000000000
       >extract(epoch FROM clock_timestamp()-interval '30 days'))
 THEN reasons:=array_append(reasons,'RECENT_RUN'); END IF;
 RETURN reasons;
END $$;



--
-- Name: agent_runtime_session_owner_retirement_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_owner_retirement_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 PERFORM public.agent_runtime_session_archive_gate(to_jsonb(NEW)||jsonb_build_object(
  'owner_session_id',NEW.session_id,'owner_target_kind',NEW.target_kind,'owner_target_id',NEW.target_id,'owner_end_user_id',NEW.end_user_id));
 IF TG_OP='UPDATE' AND (
  (NEW.tenant_id,NEW.target_kind,NEW.target_id,NEW.session_id,NEW.end_user_id,NEW.source_session_revision,
   NEW.closed_epoch_second,NEW.closed_nano,NEW.created_at) IS DISTINCT FROM
  (OLD.tenant_id,OLD.target_kind,OLD.target_id,OLD.session_id,OLD.end_user_id,OLD.source_session_revision,
   OLD.closed_epoch_second,OLD.closed_nano,OLD.created_at)
  OR NEW.retired_rows<OLD.retired_rows OR NEW.retired_bytes<OLD.retired_bytes)
 THEN RAISE EXCEPTION 'session_archive_progress_source_changed'; END IF;
 RETURN NEW;
END $$;



--
-- Name: agent_runtime_session_owner_retirement_pending(text, text, text, text, bigint); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_owner_retirement_pending(t text, s text, k text, id text, u bigint) RETURNS boolean
    LANGUAGE plpgsql STABLE
    AS $$
DECLARE p integer;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 FOR p IN 1..12 LOOP
  IF EXISTS(SELECT 1 FROM public.agent_runtime_session_retirement_objects(t,s,k,id,u,p,'{}',1)) THEN RETURN true; END IF;
 END LOOP;
 RETURN false;
END $$;



--
-- Name: agent_runtime_session_preparation_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_preparation_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN RAISE EXCEPTION 'session_preparation_immutable'; END;
$$;



--
-- Name: agent_runtime_session_retire_owner(text, text, text, text, bigint, integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_retire_owner(t text, s text, k text, id text, u bigint, maximum integer) RETURNS TABLE(rows bigint, bytes bigint, more_work boolean, protected_reasons text[])
    LANGUAGE plpgsql
    AS $_$
DECLARE source public.agent_runtime_sessions; progress public.agent_runtime_session_owner_retirements;
 spec record; item record; run public.agent_runtime_runs; root bigint; selected jsonb; original jsonb; cold jsonb;
 page jsonb; run_key text; field text; key_where text; assign_sql text; source_columns text; all_columns text;
 count_work integer:=0; count_rows bigint:=0; count_bytes bigint:=0; work_bytes bigint:=0; previous_key text[];
 hot_bytes bigint; stored_bytes bigint; canonical text; affected bigint; p integer; cursor_key text[];
 reasons text[]:='{}'; has_reference boolean:=false; source_event public.agent_runtime_session_events;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 IF maximum NOT BETWEEN 1 AND 16 THEN RAISE EXCEPTION 'session_archive_object_limit'; END IF;
 SELECT o.end_user_id INTO root FROM public.agent_runtime_session_owners o
  WHERE (o.tenant_id,o.session_id,o.target_kind,o.target_id,o.end_user_id)=(t,s,k,id,u) FOR NO KEY UPDATE;
 SELECT h.* INTO source FROM public.agent_runtime_sessions h
  WHERE (h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=(t,s,k,id,u) FOR UPDATE;
 IF root IS NULL OR source.status IS DISTINCT FROM 'CLOSED'
  OR source.updated_epoch_second::numeric+source.updated_nano::numeric/1000000000
    >extract(epoch FROM clock_timestamp()-interval '30 days')
 THEN RETURN QUERY SELECT 0::bigint,0::bigint,false,ARRAY['SESSION_NOT_OLD_CLOSED']::text[]; RETURN; END IF;
 reasons:=public.agent_runtime_session_owner_archive_protection(t,s,k,id,u);
 IF cardinality(reasons)>0 THEN RETURN QUERY SELECT 0::bigint,0::bigint,true,reasons; RETURN; END IF;
 -- Only a bounded event page is examined for the per-event protection diagnostic.
 SELECT EXISTS(SELECT 1 FROM (SELECT e.event_id FROM public.agent_runtime_session_events e
  WHERE (e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id)=(t,s,k,id,u)
  ORDER BY e.session_sequence LIMIT 16) finite
  WHERE public.agent_runtime_session_event_referenced(t,s,k,id,u,finite.event_id)) INTO has_reference;
 IF has_reference THEN reasons:=array_append(reasons,'EVENT_SOURCE_REFERENCE'); END IF;
 IF EXISTS(SELECT 1 FROM (SELECT e.run_id FROM public.agent_runtime_session_events e
  WHERE (e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id)=(t,s,k,id,u)
   AND e.run_id IS NOT NULL ORDER BY e.session_sequence LIMIT 16) finite
  WHERE NOT EXISTS(SELECT 1 FROM public.agent_runtime_runs r
   WHERE (r.tenant_id,r.run_id,r.session_id,r.target_kind,r.target_id,r.end_user_id)=(t,finite.run_id,s,k,id,u)))
 THEN reasons:=array_append(reasons,'EVENT_RUN_SOURCE_MISSING'); END IF;
 INSERT INTO public.agent_runtime_session_owner_retirements(tenant_id,session_id,target_kind,target_id,end_user_id,
  source_session_revision,closed_epoch_second,closed_nano)
 VALUES(t,s,k,id,u,source.session_revision,source.updated_epoch_second,source.updated_nano)
 ON CONFLICT(tenant_id,target_kind,target_id,session_id) DO NOTHING;
 SELECT h.* INTO progress FROM public.agent_runtime_session_owner_retirements h
  WHERE (h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=(t,s,k,id,u) FOR UPDATE;
 IF (progress.source_session_revision,progress.closed_epoch_second,progress.closed_nano) IS DISTINCT FROM
  (source.session_revision,source.updated_epoch_second,source.updated_nano) THEN RAISE EXCEPTION 'session_archive_progress_source_changed'; END IF;
 p:=CASE WHEN progress.status='RETIRED' THEN 1 ELSE progress.phase END;
 cursor_key:=CASE WHEN progress.status='RETIRED' THEN '{}'::text[] ELSE progress.scan_after_key END;
 WHILE p<=12 AND count_work<maximum LOOP
  SELECT * INTO STRICT spec FROM public.agent_runtime_session_archive_spec(p);
  SELECT coalesce(jsonb_agg(to_jsonb(o) ORDER BY o.sort_key),'[]'::jsonb) INTO page
   FROM public.agent_runtime_session_retirement_objects(t,s,k,id,u,p,cursor_key,maximum-count_work) o;
  IF jsonb_array_length(page)=0 THEN p:=p+1; cursor_key:='{}'; CONTINUE; END IF;
  -- Stable order locks only relevant Runs for the finite selected object page.
  FOR run_key IN SELECT DISTINCT value->>'source_run' FROM jsonb_array_elements(page)
   WHERE value->>'source_run' IS NOT NULL ORDER BY 1 LOOP
   PERFORM 1 FROM public.agent_runtime_runs r WHERE r.tenant_id=t AND r.run_id=run_key FOR NO KEY UPDATE;
  END LOOP;
  SELECT string_agg('h.'||quote_ident(x)||'=($2->>'||quote_literal(x)||')',' AND ')
   INTO key_where FROM unnest(spec.key_columns) x;
  FOR item IN SELECT value FROM jsonb_array_elements(page) LOOP
   previous_key:=cursor_key;
   count_work:=count_work+1;
   SELECT array_agg(value ORDER BY ordinality) INTO cursor_key
    FROM jsonb_array_elements_text(item.value->'sort_key') WITH ORDINALITY;
   hot_bytes:=(item.value->>'payload_bytes')::bigint;
   IF hot_bytes>8388608 THEN
    IF NOT 'PAYLOAD_BYTES_LIMIT'=ANY(reasons) THEN reasons:=array_append(reasons,'PAYLOAD_BYTES_LIMIT'); END IF;
    CONTINUE;
   END IF;
   IF work_bytes+hot_bytes>8388608 THEN
    -- Keep this object pending; do not advance its durable cursor until a later batch.
    cursor_key:=previous_key; EXIT;
   END IF;
   -- Run payload compaction changes no key. Preserve KEY SHARE compatibility for
   -- deferred checkpoint Run FKs while a concurrent freeze holds its body lock.
   EXECUTE 'SELECT to_jsonb(h) FROM public.'||quote_ident(spec.hot_table)||' h WHERE h.tenant_id=$1 AND '||key_where||
    CASE WHEN p=4 THEN ' FOR NO KEY UPDATE' ELSE ' FOR UPDATE' END
    INTO original USING t,item.value->'source_key';
   IF original IS NULL THEN CONTINUE; END IF;
   run:=NULL;
   IF item.value->>'source_run' IS NOT NULL THEN
    SELECT r.* INTO run FROM public.agent_runtime_runs r WHERE r.tenant_id=t AND r.run_id=item.value->>'source_run';
    IF (run.tenant_id,run.session_id,run.target_kind,run.target_id,run.end_user_id) IS DISTINCT FROM (t,s,k,id,u)
      OR run.status NOT IN('COMPLETED','TERMINATED','FAILED','CANCELLED')
      OR run.updated_epoch_second::numeric+run.updated_nano::numeric/1000000000
         >extract(epoch FROM clock_timestamp()-interval '30 days')
    THEN RAISE EXCEPTION 'session_archive_run_source_changed'; END IF;
   END IF;
   cold:=original||jsonb_build_object('owner_session_id',s,'owner_target_kind',k,'owner_target_id',id,'owner_end_user_id',u,
    'source_session_revision',source.session_revision,'closed_epoch_second',source.updated_epoch_second,'closed_nano',source.updated_nano,
    'source_run_id',run.run_id,'source_run_revision',run.run_revision,'source_run_updated_epoch_second',run.updated_epoch_second,
    'source_run_updated_nano',run.updated_nano,'original_row_sha256',encode(sha256(convert_to(original::text,'UTF8')),'hex'),
    'archived_at',clock_timestamp());
   IF p=9 THEN
    source_event:=jsonb_populate_record(NULL::public.agent_runtime_session_events,original);
    canonical:=public.agent_runtime_session_event_payload(source_event.tenant_id,source_event.session_id,source_event.target_kind,
     source_event.target_id,source_event.end_user_id,source_event.run_id,source_event.event_id,source_event.event_type,
     source_event.payload_schema_id,source_event.payload_canonical_json);
    IF octet_length(canonical)>8388608 THEN
     IF NOT 'PAYLOAD_BYTES_LIMIT'=ANY(reasons) THEN reasons:=array_append(reasons,'PAYLOAD_BYTES_LIMIT'); END IF; CONTINUE;
    END IF;
    IF work_bytes+greatest(hot_bytes,octet_length(canonical))>8388608 THEN cursor_key:=previous_key; EXIT; END IF;
    cold:=cold||jsonb_build_object('original_payload_canonical_json',original->>'payload_canonical_json',
     'original_event_digest',public.agent_runtime_session_event_digest(source_event),'payload_canonical_json',canonical);
    work_bytes:=work_bytes+greatest(hot_bytes,octet_length(canonical));
   ELSE work_bytes:=work_bytes+hot_bytes;
   END IF;
   FOREACH field IN ARRAY spec.payload_columns LOOP
    cold:=cold||jsonb_build_object(field||'_sha256',CASE WHEN field='payload_data'
     THEN encode(sha256(decode(substring(cold->>field FROM 3),'hex')),'hex')
     ELSE public.agent_runtime_session_archive_text_digest(cold->>field) END);
   END LOOP;
   IF p<12 THEN
    -- JSON is only the typed row constructor; payload_data remains bytea, original text is never parsed.
    EXECUTE format('INSERT INTO public.%I SELECT (jsonb_populate_record(NULL::public.%I,$1)).* ON CONFLICT DO NOTHING',
     spec.cold_table,spec.cold_table) USING cold;
    EXECUTE format('SELECT to_jsonb(h) FROM public.%I h WHERE h.tenant_id=$1 AND %s',spec.cold_table,key_where)
     INTO selected USING t,item.value->'source_key';
    IF public.agent_runtime_session_archive_original_row(selected,spec.payload_columns,p=9) IS DISTINCT FROM original
     THEN RAISE EXCEPTION 'session_archive_source_conflict'; END IF;
   END IF;
   IF p<=9 THEN
    SELECT string_agg(quote_ident(x)||' = public.agent_runtime_session_archive_marker($3->>'||quote_literal(x)||')',',')
     INTO assign_sql FROM unnest(spec.payload_columns) x;
    EXECUTE 'UPDATE public.'||quote_ident(spec.hot_table)||' h SET '||assign_sql||' WHERE h.tenant_id=$1 AND '||key_where
     USING t,item.value->'source_key',cold;
    GET DIAGNOSTICS affected=ROW_COUNT;
    SELECT coalesce(sum(octet_length(public.agent_runtime_session_archive_marker(cold->>x))),0) INTO stored_bytes FROM unnest(spec.payload_columns) x;
    count_rows:=count_rows+affected; count_bytes:=count_bytes+greatest(hot_bytes-stored_bytes,0)*affected;
    IF p=9 THEN DELETE FROM public.agent_runtime_session_event_body_refs r
     WHERE (r.tenant_id,r.target_kind,r.target_id,r.session_id,r.event_id)=(t,k,id,s,original->>'event_id'); END IF;
   ELSIF p=11 THEN
    INSERT INTO public.agent_runtime_session_checkpoint_archive_body_refs(tenant_id,run_id,checkpoint_hash,body_hash)
     SELECT r.tenant_id,r.run_id,r.checkpoint_hash,r.body_hash FROM public.agent_runtime_session_checkpoint_body_refs r
      WHERE (r.tenant_id,r.run_id,r.checkpoint_hash)=(t,original->>'run_id',original->>'content_hash')
     ON CONFLICT DO NOTHING;
    EXECUTE 'DELETE FROM public.'||quote_ident(spec.hot_table)||' h WHERE h.tenant_id=$1 AND '||key_where USING t,item.value->'source_key';
    GET DIAGNOSTICS affected=ROW_COUNT; count_rows:=count_rows+affected; count_bytes:=count_bytes+hot_bytes*affected;
   ELSIF p=12 THEN
    EXECUTE 'DELETE FROM public.'||quote_ident(spec.hot_table)||' h WHERE h.tenant_id=$1 AND '||key_where USING t,item.value->'source_key';
    GET DIAGNOSTICS affected=ROW_COUNT; count_rows:=count_rows+affected; count_bytes:=count_bytes+hot_bytes*affected;
   END IF;
   progress.scan_after_key:=cursor_key;
  END LOOP;
  IF work_bytes>=8388608 THEN EXIT; END IF;
 END LOOP;
 more_work:=public.agent_runtime_session_owner_retirement_pending(t,s,k,id,u);
 UPDATE public.agent_runtime_session_owner_retirements h SET phase=CASE WHEN p>12 THEN 1 ELSE p END,
  scan_after_key=CASE WHEN p>12 THEN '{}'::text[] ELSE cursor_key END,status=CASE WHEN more_work THEN 'PENDING' ELSE 'RETIRED' END,
  retired_rows=h.retired_rows+count_rows,retired_bytes=h.retired_bytes+count_bytes,updated_at=clock_timestamp()
  WHERE (h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=(t,s,k,id,u);
 RETURN QUERY SELECT count_rows,count_bytes,more_work,reasons;
END $_$;



--
-- Name: agent_runtime_session_retirement_objects(text, text, text, text, bigint, integer, text[], integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_retirement_objects(t text, s text, k text, id text, u bigint, p integer, after_key text[] DEFAULT '{}'::text[], maximum integer DEFAULT 16) RETURNS TABLE(source_key jsonb, sort_key text[], source_run text, payload_bytes bigint)
    LANGUAGE plpgsql STABLE
    AS $_$
DECLARE spec record; key_expression text; sort_expression text; bytes_expression text;
 where_extra text:=''; matching_key text; field text;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 IF maximum NOT BETWEEN 1 AND 16 THEN RAISE EXCEPTION 'session_archive_object_limit'; END IF;
 SELECT * INTO STRICT spec FROM public.agent_runtime_session_archive_spec(p);
 SELECT 'jsonb_build_object('||string_agg(quote_literal(x)||',h.'||quote_ident(x),',')||')',
  'ARRAY['||string_agg('h.'||quote_ident(x)||'::text',',')||']::text[]',
  string_agg('a.'||quote_ident(x)||'=h.'||quote_ident(x),' AND ')
 INTO key_expression,sort_expression,matching_key FROM unnest(spec.key_columns) x;
 SELECT string_agg('coalesce(octet_length(h.'||quote_ident(x)||'),0)::bigint','+') INTO bytes_expression
 FROM unnest(spec.payload_columns) x;
 IF p<=9 THEN
  SELECT ' AND ('||string_agg('NOT public.agent_runtime_session_archive_is_marker(h.'||quote_ident(x)||')',' OR ')||')'
  INTO where_extra FROM unnest(spec.payload_columns) x;
 ELSIF p=10 THEN
  where_extra:=format(' AND NOT EXISTS(SELECT 1 FROM public.%I a WHERE %s)',spec.cold_table,matching_key)||
   ' AND EXISTS(SELECT 1 FROM public.agent_runtime_session_checkpoint_body_refs r
    WHERE (r.tenant_id,r.run_id,r.body_hash)=(h.tenant_id,h.run_id,h.content_hash))';
 ELSIF p=11 THEN
  where_extra:=' AND NOT EXISTS(SELECT 1 FROM public.agent_runtime_session_checkpoint_body_refs r
   WHERE (r.tenant_id,r.run_id,r.checkpoint_hash)=(h.tenant_id,h.run_id,h.content_hash)
    AND NOT EXISTS(SELECT 1 FROM public.agent_runtime_session_checkpoint_body_archives a
     WHERE (a.tenant_id,a.run_id,a.content_hash)=(r.tenant_id,r.run_id,r.body_hash)))';
 ELSIF p=12 THEN
  -- Unreferenced bodies have no recovery obligation and require no cold copy.
  where_extra:=' AND NOT EXISTS(SELECT 1 FROM public.agent_runtime_session_checkpoint_body_refs r
    WHERE (r.tenant_id,r.run_id,r.body_hash)=(h.tenant_id,h.run_id,h.content_hash))';
 END IF;
 IF p=9 THEN where_extra:=where_extra||' AND h.event_type NOT IN(''agent.model-answer'',''agent.model-reasoning'',''agent.runtime-diagnostic'')
   AND NOT public.agent_runtime_session_event_referenced(h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id,h.event_id)
   AND (h.run_id IS NULL OR EXISTS(SELECT 1 FROM public.agent_runtime_runs r
    WHERE (r.tenant_id,r.run_id,r.session_id,r.target_kind,r.target_id,r.end_user_id)
      =(h.tenant_id,h.run_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)))'; END IF;
 RETURN QUERY EXECUTE 'SELECT '||key_expression||','||sort_expression||',('||spec.run_sql||')::text,'||bytes_expression||
  ' FROM '||spec.from_sql||' WHERE '||spec.owner_sql||where_extra||' AND ('||sort_expression||
  ')>$6 ORDER BY '||sort_expression||' LIMIT $7' USING t,s,k,id,u,after_key,maximum;
END $_$;



--
-- Name: agent_runtime_session_source_reference_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_source_reference_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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



--
-- Name: agent_runtime_session_task_reference_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_task_reference_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $_$
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
    IF NOT public.agent_runtime_session_task_user_owner_valid(item->'owner')
        OR item#>>'{owner,sessionRef,tenantId,value}' IS DISTINCT FROM tenant_key
        OR item#>>'{owner,sessionRef,sessionId}' IS NULL OR item#>>'{owner,target,kind}' IS NULL
        OR item#>>'{owner,target,id}' IS NULL OR item#>>'{owner,subject,endUserId,value}' IS NULL OR item#>>'{agentId,value}' IS NULL
        THEN RAISE EXCEPTION 'session_history_task_source_invalid'; END IF;
    FOR reference IN SELECT item->'eventId' WHERE item->'eventId' IS NOT NULL
        UNION ALL SELECT value FROM json_array_elements(COALESCE(item->'sourceEventIds','[]'::json)) LOOP
        IF reference->>'value' IS NULL THEN RAISE EXCEPTION 'session_history_task_source_invalid'; END IF;
        PERFORM 1 FROM public.agent_runtime_session_events e
            WHERE ROW(e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id::text,e.event_id)=
                ROW(tenant_key,item#>>'{owner,sessionRef,sessionId}',item#>>'{owner,target,kind}',
                    item#>>'{owner,target,id}',item#>>'{owner,subject,endUserId,value}',reference->>'value')
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
                        item#>>'{owner,target,id}',item#>>'{owner,subject,endUserId,value}',(item->>'throughSequence')::bigint) FOR KEY SHARE;
            IF NOT FOUND THEN RAISE EXCEPTION 'session_history_task_cursor_missing_or_retired'; END IF;
        END IF;
    END IF;
 END LOOP;
 RETURN NEW;
END $_$;



--
-- Name: agent_runtime_session_task_snapshot_referenced(text, text, text, text, text, bigint, text, bigint); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_task_snapshot_referenced(snapshot text, t text, s text, k text, id text, u bigint, eid text, seq bigint) RETURNS boolean
    LANGUAGE plpgsql IMMUTABLE
    AS $_$
DECLARE data json; item json;
BEGIN
 data := public.agent_runtime_session_task_source_fields(snapshot);
 IF json_typeof(data)<>'object' OR json_typeof(data->'sources') IS DISTINCT FROM 'array'
    OR json_typeof(data->'maintenance') IS DISTINCT FROM 'array' THEN RETURN true; END IF;
 FOR item IN SELECT value FROM json_array_elements(data->'sources') LOOP
    IF NOT public.agent_runtime_session_task_user_owner_valid(item->'owner')
        OR item#>>'{owner,sessionRef,tenantId,value}' IS NULL OR item#>>'{owner,sessionRef,sessionId}' IS NULL OR item#>>'{owner,target,kind}' IS NULL
        OR item#>>'{owner,target,id}' IS NULL OR item#>>'{owner,subject,endUserId,value}' IS NULL OR item#>>'{eventId,value}' IS NULL
        OR item#>>'{agentId,value}' IS NULL
        THEN RETURN true; END IF;
    IF ROW(item#>>'{owner,sessionRef,tenantId,value}',item#>>'{owner,sessionRef,sessionId}',item#>>'{owner,target,kind}',
        item#>>'{owner,target,id}',item#>>'{owner,subject,endUserId,value}',item#>>'{eventId,value}') =
        ROW(t,s,k,id,u::text,eid) THEN RETURN true; END IF;
 END LOOP;
 FOR item IN SELECT value FROM json_array_elements(data->'maintenance') LOOP
    IF NOT public.agent_runtime_session_task_user_owner_valid(item->'owner')
        OR item#>>'{owner,sessionRef,tenantId,value}' IS NULL OR item#>>'{owner,sessionRef,sessionId}' IS NULL
        OR item#>>'{owner,target,kind}' IS NULL OR item#>>'{owner,target,id}' IS NULL
        OR item#>>'{owner,subject,endUserId,value}' IS NULL OR item#>>'{agentId,value}' IS NULL
        OR json_typeof(item->'sourceEventIds') IS DISTINCT FROM 'array'
        OR (item->>'throughSequence') IS NULL OR (item->>'throughSequence') !~ '^(0|[1-9][0-9]*)$'
        THEN RETURN true; END IF;
    IF ROW(item#>>'{owner,sessionRef,tenantId,value}',item#>>'{owner,sessionRef,sessionId}',item#>>'{owner,target,kind}',
        item#>>'{owner,target,id}',item#>>'{owner,subject,endUserId,value}') = ROW(t,s,k,id,u::text)
        AND (EXISTS(SELECT 1 FROM json_array_elements(item->'sourceEventIds') ref WHERE ref->>'value'=eid)
            OR (item->>'throughSequence')::bigint=seq) THEN RETURN true; END IF;
 END LOOP;
 RETURN false;
EXCEPTION WHEN invalid_text_representation OR invalid_parameter_value OR numeric_value_out_of_range OR untranslatable_character OR raise_exception THEN RETURN true;
END $_$;



--
-- Name: agent_runtime_session_task_source_fields(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_task_source_fields(snapshot text) RETURNS json
    LANGUAGE plpgsql IMMUTABLE
    AS $$
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



--
-- Name: agent_runtime_session_task_user_owner_valid(json); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_task_user_owner_valid(owner json) RETURNS boolean
    LANGUAGE plpgsql IMMUTABLE
    AS $_$
BEGIN
 IF json_typeof(owner) IS DISTINCT FROM 'object' THEN RETURN false; END IF;
 IF (SELECT array_agg(key ORDER BY key) FROM json_object_keys(owner) key)
    IS DISTINCT FROM ARRAY['sessionRef','subject','target']::text[] THEN RETURN false; END IF;
 IF json_typeof(owner->'sessionRef') IS DISTINCT FROM 'object'
    OR json_typeof(owner->'subject') IS DISTINCT FROM 'object'
    OR json_typeof(owner->'target') IS DISTINCT FROM 'object' THEN RETURN false; END IF;
 IF (SELECT array_agg(key ORDER BY key) FROM json_object_keys(owner->'sessionRef') key)
       IS DISTINCT FROM ARRAY['sessionId','tenantId']::text[]
    OR (SELECT array_agg(key ORDER BY key) FROM json_object_keys(owner->'subject') key)
       IS DISTINCT FROM ARRAY['endUserId','kind']::text[]
    OR (SELECT array_agg(key ORDER BY key) FROM json_object_keys(owner->'target') key)
       IS DISTINCT FROM ARRAY['id','kind']::text[] THEN RETURN false; END IF;
 IF owner#>>'{subject,kind}' IS DISTINCT FROM 'USER'
    OR COALESCE(owner#>>'{target,kind}','') NOT IN('AGENT','TEAM')
    OR json_typeof(owner#>'{sessionRef,tenantId}') IS DISTINCT FROM 'object'
    OR json_typeof(owner#>'{subject,endUserId}') IS DISTINCT FROM 'object'
    OR json_typeof(owner#>'{sessionRef,sessionId}') IS DISTINCT FROM 'string'
    OR json_typeof(owner#>'{target,id}') IS DISTINCT FROM 'string'
    OR btrim(owner#>>'{sessionRef,sessionId}')=''
    OR btrim(owner#>>'{target,id}')='' THEN RETURN false; END IF;
 IF (SELECT array_agg(key ORDER BY key) FROM json_object_keys(owner#>'{sessionRef,tenantId}') key)
       IS DISTINCT FROM ARRAY['value']::text[]
    OR (SELECT array_agg(key ORDER BY key) FROM json_object_keys(owner#>'{subject,endUserId}') key)
       IS DISTINCT FROM ARRAY['value']::text[]
    OR json_typeof(owner#>'{sessionRef,tenantId,value}') IS DISTINCT FROM 'string'
    OR json_typeof(owner#>'{subject,endUserId,value}') IS DISTINCT FROM 'number'
    OR btrim(owner#>>'{sessionRef,tenantId,value}')='' THEN RETURN false; END IF;
 IF (owner#>>'{subject,endUserId,value}') !~ '^[1-9][0-9]{0,18}$' THEN RETURN false; END IF;
 RETURN (owner#>>'{subject,endUserId,value}')::numeric<=9223372036854775807;
END $_$;



--
-- Name: agent_runtime_session_token_profile_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_session_token_profile_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN RAISE EXCEPTION 'session_token_profile_immutable'; END;
$$;



--
-- Name: agent_runtime_workflow_child_preparation_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_workflow_child_preparation_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'workflow_child_preparation_immutable';
END;
$$;



--
-- Name: agent_runtime_workflow_child_source_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.agent_runtime_workflow_child_source_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'workflow_child_execution_source_immutable';
END;
$$;



--
-- Name: assert_agent_runtime_callback_action_binding(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.assert_agent_runtime_callback_action_binding() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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



--
-- Name: assert_agent_runtime_execution_closed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.assert_agent_runtime_execution_closed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    operation public.agent_runtime_execution_mutations%ROWTYPE;
    expected_kind varchar(16);
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO operation
          FROM public.agent_runtime_execution_mutations m
         WHERE m.tenant_id = NEW.tenant_id
           AND m.execution_id = NEW.execution_id
           AND m.mutation_kind = 'CREATE'
           AND m.output_revision = NEW.execution_revision
           AND m.output_state = NEW.state
           AND m.output_data = NEW.record_data;
    ELSIF NEW.execution_revision = OLD.execution_revision
          AND OLD.lease_claim_id IS NOT NULL AND NEW.lease_claim_id IS NULL THEN
        SELECT * INTO operation
          FROM public.agent_runtime_execution_mutations m
         WHERE m.tenant_id = NEW.tenant_id
           AND m.execution_id = NEW.execution_id
           AND m.mutation_kind = 'RELEASE'
           AND m.output_revision = NEW.execution_revision
           AND m.output_state = NEW.state
           AND m.output_data = NEW.record_data;
    ELSIF NEW.execution_revision = OLD.execution_revision THEN
        RETURN NULL;
    ELSE
        IF OLD.cancel_request_id IS NULL AND NEW.cancel_request_id IS NOT NULL THEN
            expected_kind := 'CANCEL';
        ELSIF OLD.state = NEW.state THEN
            expected_kind := 'PROGRESS';
        ELSIF (OLD.state IN ('WAITING_HITL', 'WAITING_EXTERNAL') AND NEW.state = 'RESUMING')
              OR (OLD.state = 'RESUMING' AND NEW.state = 'RUNNING') THEN
            expected_kind := 'RESUME';
        ELSE
            expected_kind := 'TRANSITION';
        END IF;
        SELECT * INTO operation
          FROM public.agent_runtime_execution_mutations m
         WHERE m.tenant_id = NEW.tenant_id
           AND m.execution_id = NEW.execution_id
           AND m.mutation_kind = expected_kind
           AND ((m.output_revision = NEW.execution_revision
                 AND m.output_state = NEW.state
                 AND m.output_data = NEW.record_data)
             OR (m.mutation_kind = 'RESUME'
                 AND m.intermediate_revision = NEW.execution_revision
                 AND m.intermediate_state = NEW.state
                 AND m.intermediate_data = NEW.record_data));
    END IF;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'execution state mutation must commit its journal';
    END IF;
    IF operation.mutation_kind = 'RELEASE' THEN
        RETURN NULL;
    END IF;
    IF operation.mutation_kind = 'RESUME' THEN
        IF NOT EXISTS (
            SELECT 1
              FROM public.agent_runtime_execution_state_publications p
             WHERE p.tenant_id = NEW.tenant_id
               AND p.source_id = NEW.execution_id
               AND p.source_revision = operation.intermediate_revision)
           OR NOT EXISTS (
            SELECT 1
              FROM public.agent_runtime_execution_state_publications p
             WHERE p.tenant_id = NEW.tenant_id
               AND p.source_id = NEW.execution_id
               AND p.source_revision = operation.output_revision) THEN
            RAISE EXCEPTION 'execution resume must commit two publications';
        END IF;
    ELSIF NOT EXISTS (
        SELECT 1
          FROM public.agent_runtime_execution_state_publications p
         WHERE p.tenant_id = NEW.tenant_id
           AND p.source_id = NEW.execution_id
           AND p.source_revision = operation.output_revision) THEN
        RAISE EXCEPTION 'execution mutation must commit its publication';
    END IF;
    RETURN NULL;
END;
$$;



--
-- Name: assert_agent_runtime_execution_mutation_closed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.assert_agent_runtime_execution_mutation_closed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_executions e
         WHERE e.tenant_id = NEW.tenant_id
           AND e.execution_id = NEW.execution_id
           AND e.execution_revision = NEW.output_revision
           AND e.state = NEW.output_state
           AND e.record_data = NEW.output_data) THEN
        RAISE EXCEPTION 'execution journal must match current output';
    END IF;
    IF NEW.mutation_kind = 'RELEASE' THEN
        RETURN NULL;
    END IF;
    IF NEW.mutation_kind = 'RESUME' THEN
        IF NOT EXISTS (
            SELECT 1 FROM public.agent_runtime_execution_state_publications p
             WHERE p.tenant_id = NEW.tenant_id
               AND p.source_id = NEW.execution_id
               AND p.source_revision = NEW.intermediate_revision)
           OR NOT EXISTS (
            SELECT 1 FROM public.agent_runtime_execution_state_publications p
             WHERE p.tenant_id = NEW.tenant_id
               AND p.source_id = NEW.execution_id
               AND p.source_revision = NEW.output_revision) THEN
            RAISE EXCEPTION 'execution resume journal requires two publications';
        END IF;
    ELSIF NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_execution_state_publications p
         WHERE p.tenant_id = NEW.tenant_id
           AND p.source_id = NEW.execution_id
           AND p.source_revision = NEW.output_revision) THEN
        RAISE EXCEPTION 'execution journal requires matching publication';
    END IF;
    RETURN NULL;
END;
$$;



--
-- Name: assert_agent_runtime_execution_publication_closed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.assert_agent_runtime_execution_publication_closed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_execution_mutations m
         WHERE m.tenant_id = NEW.tenant_id
           AND m.execution_id = NEW.source_id
           AND (m.output_revision = NEW.source_revision
                OR (m.mutation_kind = 'RESUME'
                    AND m.intermediate_revision = NEW.source_revision))) THEN
        RAISE EXCEPTION 'execution publication must match a journaled revision';
    END IF;
    RETURN NULL;
END;
$$;



--
-- Name: assert_agent_runtime_execution_publication_journal_closed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.assert_agent_runtime_execution_publication_journal_closed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM public.agent_runtime_execution_state_publications p
         WHERE p.tenant_id = NEW.tenant_id
           AND p.source_id = NEW.source_id AND p.event_id = NEW.event_id
           AND p.publication_revision = NEW.output_revision
           AND p.status = NEW.output_status
           AND p.publication_data = NEW.output_data) THEN
        RAISE EXCEPTION 'execution publication journal must match current output';
    END IF;
    RETURN NULL;
END;
$$;



--
-- Name: assert_agent_runtime_execution_publication_mutation_closed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.assert_agent_runtime_execution_publication_mutation_closed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM public.agent_runtime_execution_publication_mutations m
         WHERE m.tenant_id = NEW.tenant_id
           AND m.source_id = NEW.source_id AND m.event_id = NEW.event_id
           AND m.output_revision = NEW.publication_revision
           AND m.output_status = NEW.status
           AND m.output_data = NEW.publication_data) THEN
        RAISE EXCEPTION 'execution publication mutation must commit its journal';
    END IF;
    RETURN NULL;
END;
$$;



--
-- Name: assert_agent_runtime_memory_sources(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.assert_agent_runtime_memory_sources() RETURNS trigger
    LANGUAGE plpgsql
    AS $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM public.agent_runtime_memory_fact_sources s
  WHERE s.tenant_id=NEW.tenant_id AND s.fact_id=NEW.fact_id AND s.revision=NEW.content_revision) THEN
  RAISE EXCEPTION 'memory fact revision requires committed source events';
 END IF;
 IF EXISTS (SELECT 1 FROM public.agent_runtime_memory_fact_sources s
  JOIN public.agent_runtime_session_owners o ON o.tenant_id=s.tenant_id AND o.target_kind=s.source_target_kind
   AND o.target_id=s.source_target_id AND o.session_id=s.source_session_id
  WHERE s.tenant_id=NEW.tenant_id AND s.fact_id=NEW.fact_id AND s.revision=NEW.content_revision
   AND (s.source_session_id<>NEW.source_session_id
    OR (NEW.scope='SESSION' AND NEW.session_id<>s.source_session_id)
    OR (NEW.scope IN ('USER','USER_AGENT') AND (o.subject_kind<>'USER' OR o.end_user_id IS DISTINCT FROM NEW.end_user_id))
    OR (NEW.scope='USER_AGENT' AND s.agent_id IS DISTINCT FROM NEW.agent_id)
    OR (NEW.scope IN ('GROUP','GROUP_MEMBER') AND (s.source_target_kind<>'GROUP' OR s.source_target_id<>NEW.group_binding_id)))) THEN
  RAISE EXCEPTION 'memory fact typed owner source mismatch';
 END IF;
 RETURN NULL;
END $$;



--
-- Name: assert_agent_runtime_session_event_advanced(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.assert_agent_runtime_session_event_advanced() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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



--
-- Name: assert_agent_runtime_session_mutation_applied(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.assert_agent_runtime_session_mutation_applied() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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



--
-- Name: assert_agent_runtime_session_mutation_journaled(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.assert_agent_runtime_session_mutation_journaled() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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



--
-- Name: assert_memory_extraction_material_complete(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.assert_memory_extraction_material_complete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE j public.agent_runtime_memory_extraction_jobs; c public.agent_runtime_memory_extraction_candidates;
BEGIN
 IF TG_TABLE_NAME='agent_runtime_memory_extraction_jobs' THEN
  SELECT * INTO j FROM public.agent_runtime_memory_extraction_jobs
   WHERE (tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key)=
    (NEW.tenant_id,NEW.source_event_id,NEW.extractor_version,NEW.policy_version,NEW.memory_type,NEW.canonical_fact_key);
  IF j.source_time_epoch_second IS NOT NULL AND
   ((SELECT count(*) FROM public.agent_runtime_memory_extraction_sources a
     WHERE (a.tenant_id,a.source_event_id,a.extractor_version,a.policy_version,a.memory_type,a.canonical_fact_key)=
      (j.tenant_id,j.source_event_id,j.extractor_version,j.policy_version,j.memory_type,j.canonical_fact_key))<>1
    OR (SELECT count(*) FROM public.agent_runtime_memory_extraction_fact_aliases a
     WHERE (a.tenant_id,a.source_event_id,a.extractor_version,a.policy_version,a.memory_type,a.canonical_fact_key)=
      (j.tenant_id,j.source_event_id,j.extractor_version,j.policy_version,j.memory_type,j.canonical_fact_key))<>j.frozen_fact_count) THEN
   RAISE EXCEPTION 'memory_extraction_frozen_input_incomplete';
  END IF;
 ELSE
  SELECT * INTO c FROM public.agent_runtime_memory_extraction_candidates
   WHERE (tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,candidate_ordinal)=
    (NEW.tenant_id,NEW.source_event_id,NEW.extractor_version,NEW.policy_version,NEW.memory_type,NEW.canonical_fact_key,NEW.candidate_ordinal);
  IF (SELECT count(*) FROM public.agent_runtime_memory_extraction_conditions a WHERE
    (a.tenant_id,a.source_event_id,a.extractor_version,a.policy_version,a.memory_type,a.canonical_fact_key,a.candidate_ordinal)=
    (c.tenant_id,c.source_event_id,c.extractor_version,c.policy_version,c.memory_type,c.canonical_fact_key,c.candidate_ordinal))<>c.condition_count
   OR (SELECT count(*) FROM public.agent_runtime_memory_extraction_uncertainties a WHERE
    (a.tenant_id,a.source_event_id,a.extractor_version,a.policy_version,a.memory_type,a.canonical_fact_key,a.candidate_ordinal)=
    (c.tenant_id,c.source_event_id,c.extractor_version,c.policy_version,c.memory_type,c.canonical_fact_key,c.candidate_ordinal))<>c.uncertainty_count
   OR (SELECT count(*) FROM public.agent_runtime_memory_extraction_candidate_evidence a WHERE
    (a.tenant_id,a.source_event_id,a.extractor_version,a.policy_version,a.memory_type,a.canonical_fact_key,a.candidate_ordinal)=
    (c.tenant_id,c.source_event_id,c.extractor_version,c.policy_version,c.memory_type,c.canonical_fact_key,c.candidate_ordinal))<>c.evidence_count
   OR (SELECT count(*) FROM public.agent_runtime_memory_extraction_review_reasons a WHERE
    (a.tenant_id,a.source_event_id,a.extractor_version,a.policy_version,a.memory_type,a.canonical_fact_key,a.candidate_ordinal)=
    (c.tenant_id,c.source_event_id,c.extractor_version,c.policy_version,c.memory_type,c.canonical_fact_key,c.candidate_ordinal))<>coalesce(c.review_reason_count,0) THEN
   RAISE EXCEPTION 'memory_extraction_candidate_material_incomplete';
  END IF;
 END IF;
 RETURN NEW;
END $$;



--
-- Name: assert_memory_extraction_subjects(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.assert_memory_extraction_subjects() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE current_job public.agent_runtime_memory_extraction_jobs%ROWTYPE;
BEGIN
 SELECT * INTO current_job FROM public.agent_runtime_memory_extraction_jobs WHERE
  (tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key)=
  (NEW.tenant_id,NEW.source_event_id,NEW.extractor_version,NEW.policy_version,NEW.memory_type,NEW.canonical_fact_key);
 IF current_job.source_time_epoch_second IS NOT NULL AND
  (SELECT count(*) FROM public.agent_runtime_memory_extraction_subjects s WHERE
   (s.tenant_id,s.source_event_id,s.extractor_version,s.policy_version,s.memory_type,s.canonical_fact_key)=
   (current_job.tenant_id,current_job.source_event_id,current_job.extractor_version,current_job.policy_version,current_job.memory_type,current_job.canonical_fact_key))
   <>coalesce(current_job.frozen_subject_count,0) THEN
  RAISE EXCEPTION 'memory_extraction_subjects_incomplete';
 END IF;
 RETURN NEW;
END $$;



--
-- Name: assert_memory_typed_owner(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.assert_memory_typed_owner() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE r public.agent_runtime_memory_fact_revisions; h public.agent_runtime_memory_fact_heads;
BEGIN
 SELECT * INTO h FROM public.agent_runtime_memory_fact_heads WHERE tenant_id=NEW.tenant_id AND fact_id=NEW.fact_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'memory fact requires current head'; END IF;
 IF TG_TABLE_NAME='agent_runtime_memory_fact_heads' THEN
  SELECT * INTO r FROM public.agent_runtime_memory_fact_revisions WHERE tenant_id=h.tenant_id AND fact_id=h.fact_id AND revision=h.current_revision;
 ELSE r:=NEW;
 END IF;
 IF (r.end_user_id,r.agent_id,r.session_id,r.group_binding_id,r.subject_end_user_id,r.scope,r.type_id)
 IS DISTINCT FROM (h.end_user_id,h.agent_id,h.session_id,h.group_binding_id,h.subject_end_user_id,h.scope,h.type_id) THEN
  RAISE EXCEPTION 'memory current head typed owner mismatch';
 END IF;
 RETURN NULL;
END $$;



--
-- Name: check_agent_runtime_callback_delivery_closure(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.check_agent_runtime_callback_delivery_closure() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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
        END $$;



--
-- Name: check_agent_runtime_callback_registration_closure(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.check_agent_runtime_callback_registration_closure() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_TABLE_NAME = 'agent_runtime_callback_registration_operations' THEN
        IF NOT EXISTS (SELECT 1 FROM public.agent_runtime_callback_registrations r
            WHERE r.tenant_id = NEW.tenant_id AND r.callback_id = NEW.callback_id AND r.revision = NEW.output_revision) THEN
            RAISE EXCEPTION 'callback_registration_operation_output_mismatch';
        END IF;
    ELSIF NOT EXISTS (SELECT 1 FROM public.agent_runtime_callback_registration_operations o
        WHERE o.tenant_id = NEW.tenant_id AND o.callback_id = NEW.callback_id AND o.output_revision = NEW.revision) THEN
        RAISE EXCEPTION 'callback_registration_operation_missing';
    END IF;
    RETURN NEW;
END $$;



--
-- Name: check_execution_workflow_callback_closure(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.check_execution_workflow_callback_closure() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
        BEGIN
            IF NOT public.execution_workflow_callback_closed(NEW.tenant_id,NEW.delivery_id) THEN RAISE EXCEPTION 'callback_workflow_response_closure_missing'; END IF;
            RETURN NEW;
        END $$;



--
-- Name: execution_workflow_callback_closed(text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.execution_workflow_callback_closed(p_tenant text, p_delivery text) RETURNS boolean
    LANGUAGE sql STABLE
    SET search_path TO 'pg_catalog', 'public'
    AS $$
            SELECT EXISTS(SELECT 1 FROM public.agent_runtime_execution_workflow_callback_deliveries b
                JOIN public.agent_runtime_execution_waits w ON w.tenant_id=b.tenant_id AND w.run_id=b.run_id AND w.action_id=b.action_id AND w.generation=b.generation
                JOIN public.agent_runtime_callback_deliveries d ON d.tenant_id=b.tenant_id AND d.delivery_id=b.delivery_id
                JOIN public.agent_runtime_callback_ingress_proofs p ON p.tenant_id=d.tenant_id AND p.delivery_id=d.delivery_id AND p.callback_id=d.callback_id
                JOIN public.agent_runtime_callback_registrations r ON r.tenant_id=d.tenant_id AND r.callback_id=d.callback_id
                WHERE b.tenant_id=p_tenant AND b.delivery_id=p_delivery AND w.kind='WORKFLOW' AND w.input_id=b.input_id
                    AND w.response_ref IS NOT NULL AND w.accepted_revision IS NOT NULL AND d.status='DELIVERED'
                    AND d.input_id=b.input_id AND d.command_digest=p.command_digest AND b.input_id='callback:'||p.fingerprint AND r.status='ACTIVE')
        $$;



--
-- Name: guard_agent_runtime_callback_action_binding(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_agent_runtime_callback_action_binding() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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



--
-- Name: guard_agent_runtime_callback_delivery(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_agent_runtime_callback_delivery() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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
        END $$;



--
-- Name: guard_agent_runtime_callback_journal(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_agent_runtime_callback_journal() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND TG_TABLE_NAME = 'agent_runtime_callback_audits' AND NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
    RAISE EXCEPTION 'callback_journal_immutable';
END $$;



--
-- Name: guard_agent_runtime_callback_registration(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_agent_runtime_callback_registration() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'callback_registration_delete_forbidden'; END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ACTIVE' OR NEW.revision <> 1 THEN RAISE EXCEPTION 'callback_registration_initial_state'; END IF;
        RETURN NEW;
    END IF;
    IF (NEW.tenant_id,NEW.callback_id,NEW.idempotency_key,NEW.command_data,NEW.created_at)
        IS DISTINCT FROM (OLD.tenant_id,OLD.callback_id,OLD.idempotency_key,OLD.command_data,OLD.created_at)
        OR NEW.revision <> OLD.revision + 1 OR OLD.status = 'REVOKED' OR NEW.status = OLD.status THEN
        RAISE EXCEPTION 'callback_registration_immutable_or_transition';
    END IF;
    RETURN NEW;
END $$;



--
-- Name: guard_agent_runtime_delegation_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_agent_runtime_delegation_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.tenant_id <> OLD.tenant_id
       OR NEW.parent_session_id <> OLD.parent_session_id
       OR NEW.parent_target_kind <> OLD.parent_target_kind
       OR NEW.parent_target_id <> OLD.parent_target_id
       OR NEW.parent_end_user_id <> OLD.parent_end_user_id
       OR NEW.parent_agent_id <> OLD.parent_agent_id
       OR NEW.parent_causation_input_id <> OLD.parent_causation_input_id
       OR NEW.parent_run_id <> OLD.parent_run_id
       OR NEW.parent_trace_id <> OLD.parent_trace_id
       OR NEW.parent_span_id <> OLD.parent_span_id
       OR NEW.parent_root_input_id <> OLD.parent_root_input_id
       OR NEW.parent_caused_by_event_id IS DISTINCT FROM OLD.parent_caused_by_event_id
       OR NEW.parent_causation_identity_ref <> OLD.parent_causation_identity_ref
       OR NEW.delegation_id <> OLD.delegation_id
       OR NEW.operation_key <> OLD.operation_key
       OR NEW.child_run_id <> OLD.child_run_id
       OR NEW.child_input_id <> OLD.child_input_id
       OR NEW.previous_delegation_id IS DISTINCT FROM OLD.previous_delegation_id
       OR NEW.created_epoch_second <> OLD.created_epoch_second
       OR NEW.created_nano <> OLD.created_nano
       OR (NEW.snapshot - ARRAY['state','result','cancelKey','revision','updatedAt'])
          <> (OLD.snapshot - ARRAY['state','result','cancelKey','revision','updatedAt']) THEN
        RAISE EXCEPTION 'agent runtime delegation identity is immutable';
    END IF;
    IF NEW.revision <> OLD.revision + 1
       OR (NEW.updated_epoch_second,NEW.updated_nano)
          < (OLD.updated_epoch_second,OLD.updated_nano)
       OR (OLD.cancel_key IS NOT NULL
           AND NEW.cancel_key IS DISTINCT FROM OLD.cancel_key) THEN
        RAISE EXCEPTION 'agent runtime delegation CAS is invalid';
    END IF;
    IF OLD.state IN ('COMPLETED','FAILED','CANCELLED') THEN
        RAISE EXCEPTION 'agent runtime delegation state transition is invalid';
    END IF;
    RETURN NEW;
END;
$$;



--
-- Name: guard_agent_runtime_delegation_wait_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_agent_runtime_delegation_wait_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.tenant_id <> OLD.tenant_id
       OR NEW.parent_session_id <> OLD.parent_session_id
       OR NEW.parent_target_kind <> OLD.parent_target_kind
       OR NEW.parent_target_id <> OLD.parent_target_id
       OR NEW.parent_end_user_id <> OLD.parent_end_user_id
       OR NEW.parent_agent_id <> OLD.parent_agent_id
       OR NEW.parent_causation_input_id <> OLD.parent_causation_input_id
       OR NEW.parent_run_id <> OLD.parent_run_id
       OR NEW.parent_trace_id <> OLD.parent_trace_id
       OR NEW.parent_span_id <> OLD.parent_span_id
       OR NEW.parent_root_input_id <> OLD.parent_root_input_id
       OR NEW.parent_caused_by_event_id IS DISTINCT FROM OLD.parent_caused_by_event_id
       OR NEW.parent_causation_identity_ref <> OLD.parent_causation_identity_ref
       OR NEW.wait_id <> OLD.wait_id
       OR (NEW.snapshot - ARRAY['delivered','revision'])
          <> (OLD.snapshot - ARRAY['delivered','revision']) THEN
        RAISE EXCEPTION 'agent runtime delegation wait mutation is invalid';
    END IF;
    IF OLD.recovery_data IS NULL AND NEW.recovery_data IS NOT NULL THEN
        IF OLD.delivered OR NEW.delivered <> OLD.delivered
           OR NEW.revision <> OLD.revision OR NEW.snapshot <> OLD.snapshot
           OR NEW.recovery_input_id IS NULL THEN
            RAISE EXCEPTION 'agent runtime delegation recovery freeze is invalid';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.recovery_data IS DISTINCT FROM NEW.recovery_data
       OR OLD.recovery_input_id IS DISTINCT FROM NEW.recovery_input_id
       OR OLD.delivered OR NOT NEW.delivered
       OR NEW.revision <> OLD.revision + 1 THEN
        RAISE EXCEPTION 'agent runtime delegation wait delivery is invalid';
    END IF;
    RETURN NEW;
END;
$$;



--
-- Name: guard_agent_runtime_execution(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_agent_runtime_execution() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime execution is not deletable';
    END IF;
    IF (NEW.tenant_id, NEW.execution_id, NEW.session_id, NEW.agent_id,
        NEW.end_user_id, NEW.run_id, NEW.causation_input_id,
        NEW.execution_unit_id, NEW.definition_version, NEW.execution_mode,
        NEW.causative_action_id, NEW.delegation_id,
        NEW.created_epoch_second, NEW.created_nano)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.execution_id, OLD.session_id, OLD.agent_id,
        OLD.end_user_id, OLD.run_id, OLD.causation_input_id,
        OLD.execution_unit_id, OLD.definition_version, OLD.execution_mode,
        OLD.causative_action_id, OLD.delegation_id,
        OLD.created_epoch_second, OLD.created_nano) THEN
        RAISE EXCEPTION 'agent runtime execution immutable identity changed';
    END IF;
    IF NEW.execution_revision = OLD.execution_revision THEN
        IF (NEW.state, NEW.checkpoint_schema_id, NEW.checkpoint_canonical_json,
            NEW.waiting_suspend_ref, NEW.waiting_action_key, NEW.result_ref,
            NEW.reason_code, NEW.cancel_request_id,
            NEW.cancel_requested_epoch_second, NEW.cancel_requested_nano)
           IS DISTINCT FROM
           (OLD.state, OLD.checkpoint_schema_id, OLD.checkpoint_canonical_json,
            OLD.waiting_suspend_ref, OLD.waiting_action_key, OLD.result_ref,
            OLD.reason_code, OLD.cancel_request_id,
            OLD.cancel_requested_epoch_second, OLD.cancel_requested_nano) THEN
            RAISE EXCEPTION 'lease mutation changed execution domain state';
        END IF;
        IF OLD.lease_claim_id IS NULL AND NEW.lease_claim_id IS NULL THEN
            RAISE EXCEPTION 'execution revision may stay unchanged only for lease mutation';
        ELSIF OLD.lease_claim_id IS NULL THEN
            IF NEW.lease_execution_revision <> OLD.execution_revision THEN
                RAISE EXCEPTION 'execution acquisition must bind current revision';
            END IF;
        ELSIF NEW.lease_claim_id IS NULL THEN
            IF (NEW.last_requested_epoch_second, NEW.last_requested_nano)
               IS DISTINCT FROM
               (OLD.last_requested_epoch_second, OLD.last_requested_nano) THEN
                RAISE EXCEPTION 'execution release must preserve last lease request';
            END IF;
        ELSIF (NEW.lease_claim_id, NEW.lease_worker_id,
               NEW.lease_fencing_token)
              IS NOT DISTINCT FROM
              (OLD.lease_claim_id, OLD.lease_worker_id,
               OLD.lease_fencing_token) THEN
            IF NEW.lease_execution_revision
               IS DISTINCT FROM OLD.lease_execution_revision THEN
                RAISE EXCEPTION 'execution renewal changed acquisition revision';
            END IF;
            IF (NEW.lease_until_epoch_second, NEW.lease_until_nano)
               <= (OLD.lease_until_epoch_second, OLD.lease_until_nano) THEN
                RAISE EXCEPTION 'execution renewal must extend lease';
            END IF;
            IF (NEW.last_requested_epoch_second, NEW.last_requested_nano)
               IS DISTINCT FROM
               (OLD.last_requested_epoch_second, OLD.last_requested_nano) THEN
                RAISE EXCEPTION 'execution renewal must preserve last lease request';
            END IF;
        ELSE
            IF NEW.lease_fencing_token <= OLD.lease_fencing_token THEN
                RAISE EXCEPTION 'execution takeover must advance fence';
            END IF;
            IF NEW.lease_execution_revision <> OLD.execution_revision THEN
                RAISE EXCEPTION 'execution takeover must bind current revision';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.execution_revision <> OLD.execution_revision + 1 THEN
        RAISE EXCEPTION 'agent runtime execution revision must advance by one';
    END IF;
    IF (NEW.last_requested_epoch_second, NEW.last_requested_nano)
       IS DISTINCT FROM
       (OLD.last_requested_epoch_second, OLD.last_requested_nano) THEN
        RAISE EXCEPTION 'execution domain mutation must preserve last lease request';
    END IF;
    IF OLD.cancel_request_id IS NULL AND NEW.cancel_request_id IS NOT NULL THEN
        IF OLD.state IN ('COMPLETED', 'FAILED', 'LOOP_TERMINATED', 'CANCELLED')
           OR NEW.state <> OLD.state
           OR (NEW.checkpoint_schema_id, NEW.checkpoint_canonical_json,
               NEW.waiting_suspend_ref, NEW.waiting_action_key, NEW.result_ref,
               NEW.reason_code)
              IS DISTINCT FROM
              (OLD.checkpoint_schema_id, OLD.checkpoint_canonical_json,
               OLD.waiting_suspend_ref, OLD.waiting_action_key, OLD.result_ref,
               OLD.reason_code)
           OR (NEW.lease_claim_id, NEW.lease_worker_id,
               NEW.lease_fencing_token, NEW.lease_execution_revision,
               NEW.lease_until_epoch_second, NEW.lease_until_nano)
              IS DISTINCT FROM
              (OLD.lease_claim_id, OLD.lease_worker_id,
               OLD.lease_fencing_token, OLD.lease_execution_revision,
               OLD.lease_until_epoch_second, OLD.lease_until_nano) THEN
            RAISE EXCEPTION 'execution cancel request changed non-cancel state';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.cancel_request_id, NEW.cancel_requested_epoch_second,
        NEW.cancel_requested_nano)
       IS DISTINCT FROM
       (OLD.cancel_request_id, OLD.cancel_requested_epoch_second,
        OLD.cancel_requested_nano) THEN
        RAISE EXCEPTION 'execution cancel intent is immutable';
    END IF;
    IF OLD.lease_claim_id IS NULL THEN
        RAISE EXCEPTION 'execution domain mutation requires current lease';
    END IF;
    IF OLD.state = NEW.state THEN
        IF OLD.state <> 'RUNNING' THEN
            RAISE EXCEPTION 'same-state execution revision requires progress';
        END IF;
    ELSIF NOT (
        (OLD.state = 'QUEUED' AND NEW.state IN ('RUNNING', 'CANCELLED'))
        OR (OLD.state = 'RUNNING' AND NEW.state IN
            ('WAITING_HITL', 'WAITING_EXTERNAL', 'COMPLETED', 'FAILED',
             'LOOP_TERMINATED', 'CANCELLED'))
        OR (OLD.state IN ('WAITING_HITL', 'WAITING_EXTERNAL') AND NEW.state IN
            ('RESUMING', 'CANCELLED'))
        OR (OLD.state = 'RESUMING' AND NEW.state = 'RUNNING')) THEN
        RAISE EXCEPTION 'illegal agent runtime execution state transition';
    END IF;
    IF NEW.state IN ('COMPLETED', 'FAILED', 'LOOP_TERMINATED', 'CANCELLED') THEN
        IF NEW.lease_claim_id IS NOT NULL THEN
            RAISE EXCEPTION 'terminal execution must clear current lease';
        END IF;
    ELSIF (NEW.lease_claim_id, NEW.lease_worker_id,
           NEW.lease_fencing_token, NEW.lease_execution_revision,
           NEW.lease_until_epoch_second, NEW.lease_until_nano)
          IS DISTINCT FROM
          (OLD.lease_claim_id, OLD.lease_worker_id,
           OLD.lease_fencing_token, OLD.lease_execution_revision,
           OLD.lease_until_epoch_second, OLD.lease_until_nano) THEN
        RAISE EXCEPTION 'non-terminal execution mutation changed current lease';
    END IF;
    RETURN NEW;
END;
$$;



--
-- Name: guard_agent_runtime_execution_publication(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_agent_runtime_execution_publication() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime execution publication is not deletable';
    END IF;
    IF (NEW.tenant_id, NEW.source_kind, NEW.source_id, NEW.event_id,
        NEW.source_revision, NEW.created_epoch_second, NEW.created_nano)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.source_kind, OLD.source_id, OLD.event_id,
        OLD.source_revision, OLD.created_epoch_second, OLD.created_nano) THEN
        RAISE EXCEPTION 'execution publication immutable identity changed';
    END IF;
    IF OLD.status = 'ACKNOWLEDGED' THEN
        RAISE EXCEPTION 'acknowledged execution publication is immutable';
    END IF;
    IF NEW.publication_revision <> OLD.publication_revision + 1 THEN
        RAISE EXCEPTION 'execution publication revision must advance by one';
    END IF;
    IF NEW.status = 'CLAIMED' THEN
        IF OLD.status NOT IN ('PENDING', 'CLAIMED')
           OR NEW.attempts <> OLD.attempts + 1
           OR (OLD.status = 'CLAIMED'
               AND NEW.lease_fencing_token <= OLD.lease_fencing_token) THEN
            RAISE EXCEPTION 'invalid execution publication claim';
        END IF;
    ELSIF OLD.status = 'CLAIMED'
          AND NEW.status IN ('PENDING', 'ACKNOWLEDGED') THEN
        IF NEW.attempts <> OLD.attempts THEN
            RAISE EXCEPTION 'execution publication mutation changed attempts';
        END IF;
    ELSE
        RAISE EXCEPTION 'illegal execution publication state transition';
    END IF;
    RETURN NEW;
END;
$$;



--
-- Name: guard_agent_runtime_memory_history_delete(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_agent_runtime_memory_history_delete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE cutoff_text text;
BEGIN
    cutoff_text:=nullif(current_setting('app.memory_history_retention_cutoff',true),'');
    IF cutoff_text IS NULL OR NOT public.agent_runtime_memory_history_unprotected(
        OLD.tenant_id,OLD.fact_id,OLD.revision,cutoff_text::timestamptz) THEN
        RAISE EXCEPTION 'memory history is protected';
    END IF;
    RETURN OLD;
END $$;



--
-- Name: guard_agent_runtime_session(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_agent_runtime_session() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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



--
-- Name: guard_agent_runtime_session_event_append(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_agent_runtime_session_event_append() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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



--
-- Name: guard_agent_runtime_session_stream(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_agent_runtime_session_stream() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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



--
-- Name: guard_agent_runtime_session_target_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_agent_runtime_session_target_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF (NEW.target_kind, NEW.target_id)
       IS DISTINCT FROM (OLD.target_kind, OLD.target_id) THEN
        RAISE EXCEPTION 'agent runtime session target is immutable';
    END IF;
    RETURN NEW;
END;
$$;



--
-- Name: guard_agent_runtime_workflow_script_call(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_agent_runtime_workflow_script_call() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'PREPARED'
           OR NEW.dispatched_at IS NOT NULL
           OR NEW.terminal_at IS NOT NULL
           OR NEW.terminal_fingerprint IS NOT NULL
           OR NEW.terminal_data IS NOT NULL THEN
            RAISE EXCEPTION 'workflow_script_call_must_start_prepared';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'workflow_script_call_delete_forbidden';
    END IF;

    IF ROW(
        NEW.tenant_id, NEW.execution_id, NEW.session_id, NEW.target_kind,
        NEW.target_id, NEW.agent_id, NEW.end_user_id, NEW.run_id,
        NEW.node_id, NEW.visit, NEW.call_index, NEW.definition_revision,
        NEW.installation_revision, NEW.prepared_execution_revision,
        NEW.task_id, NEW.request_schema_id, NEW.deadline_epoch_second,
        NEW.deadline_nano, NEW.source_checkpoint_fingerprint,
        NEW.request_fingerprint, NEW.preparation_fingerprint,
        NEW.preparation_data, NEW.prepared_at)
       IS DISTINCT FROM ROW(
        OLD.tenant_id, OLD.execution_id, OLD.session_id, OLD.target_kind,
        OLD.target_id, OLD.agent_id, OLD.end_user_id, OLD.run_id,
        OLD.node_id, OLD.visit, OLD.call_index, OLD.definition_revision,
        OLD.installation_revision, OLD.prepared_execution_revision,
        OLD.task_id, OLD.request_schema_id, OLD.deadline_epoch_second,
        OLD.deadline_nano, OLD.source_checkpoint_fingerprint,
        OLD.request_fingerprint, OLD.preparation_fingerprint,
        OLD.preparation_data, OLD.prepared_at) THEN
        RAISE EXCEPTION 'workflow_script_call_preparation_immutable';
    END IF;

    IF OLD.state = 'PREPARED' AND NEW.state = 'DISPATCHED' THEN
        IF NEW.dispatched_at IS NULL
           OR NEW.terminal_at IS NOT NULL
           OR NEW.terminal_fingerprint IS NOT NULL
           OR NEW.terminal_data IS NOT NULL THEN
            RAISE EXCEPTION 'workflow_script_call_dispatch_invalid';
        END IF;
    ELSIF OLD.state = 'DISPATCHED'
          AND NEW.state IN (
              'COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT') THEN
        IF NEW.dispatched_at IS DISTINCT FROM OLD.dispatched_at
           OR NEW.terminal_at IS NULL
           OR NEW.terminal_fingerprint IS NULL
           OR NEW.terminal_data IS NULL THEN
            RAISE EXCEPTION 'workflow_script_call_terminal_invalid';
        END IF;
    ELSE
        RAISE EXCEPTION 'workflow_script_call_transition_invalid';
    END IF;
    RETURN NEW;
END;
$$;



--
-- Name: guard_execution_immutable_body(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_execution_immutable_body() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN RAISE EXCEPTION 'execution_immutable_body'; END $$;



--
-- Name: guard_memory_embedding_projection(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_memory_embedding_projection() RETURNS trigger
    LANGUAGE plpgsql
    AS $$ BEGIN
 IF TG_OP='DELETE' THEN
  IF OLD.state IN ('RUNNING','UNKNOWN') THEN RAISE EXCEPTION 'memory_embedding_unresolved_operation_delete_forbidden'; END IF;
  RETURN OLD;
 END IF;
 IF (NEW.tenant_id,NEW.fact_id,NEW.content_revision,NEW.projection_version,NEW.analyzer_version,NEW.model_id,NEW.model_revision,NEW.dimensions,NEW.text_digest,NEW.invocation_id)
 IS DISTINCT FROM (OLD.tenant_id,OLD.fact_id,OLD.content_revision,OLD.projection_version,OLD.analyzer_version,OLD.model_id,OLD.model_revision,OLD.dimensions,OLD.text_digest,OLD.invocation_id)
 OR NOT ((OLD.state='QUEUED' AND NEW.state='RUNNING') OR (OLD.state='RUNNING' AND NEW.state IN ('READY','REJECTED','UNKNOWN'))) THEN
  RAISE EXCEPTION 'memory_embedding_operation_identity_or_state_mismatch';
 END IF;RETURN NEW;
END $$;



--
-- Name: guard_memory_extraction_detail_insert(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_memory_extraction_detail_insert() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE item_limit integer;
BEGIN
 IF TG_TABLE_NAME='agent_runtime_memory_extraction_fact_aliases' THEN
  SELECT frozen_fact_count INTO item_limit FROM public.agent_runtime_memory_extraction_jobs
   WHERE (tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key)=
    (NEW.tenant_id,NEW.source_event_id,NEW.extractor_version,NEW.policy_version,NEW.memory_type,NEW.canonical_fact_key);
  IF item_limit IS NULL OR NEW.alias_ordinal>=item_limit THEN RAISE EXCEPTION 'memory_extraction_alias_outside_frozen_input'; END IF;
 ELSIF TG_TABLE_NAME<>'agent_runtime_memory_extraction_sources' THEN
  SELECT CASE TG_TABLE_NAME
   WHEN 'agent_runtime_memory_extraction_conditions' THEN condition_count
   WHEN 'agent_runtime_memory_extraction_uncertainties' THEN uncertainty_count
   WHEN 'agent_runtime_memory_extraction_candidate_evidence' THEN evidence_count
   WHEN 'agent_runtime_memory_extraction_review_reasons' THEN review_reason_count END
  INTO item_limit FROM public.agent_runtime_memory_extraction_candidates
  WHERE (tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,candidate_ordinal)=
   (NEW.tenant_id,NEW.source_event_id,NEW.extractor_version,NEW.policy_version,NEW.memory_type,NEW.canonical_fact_key,NEW.candidate_ordinal);
  IF item_limit IS NULL OR NEW.item_ordinal>=item_limit THEN RAISE EXCEPTION 'memory_extraction_detail_outside_frozen_candidate'; END IF;
 END IF;
 RETURN NEW;
END $$;



--
-- Name: guard_memory_extraction_frozen_material(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_memory_extraction_frozen_material() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_TABLE_NAME='agent_runtime_memory_extraction_jobs' THEN
  IF OLD.source_time_epoch_second IS NOT NULL AND
    (NEW.source_time_epoch_second,NEW.source_time_nano,NEW.frozen_fact_count) IS DISTINCT FROM
    (OLD.source_time_epoch_second,OLD.source_time_nano,OLD.frozen_fact_count) THEN
   RAISE EXCEPTION 'memory_extraction_input_immutable';
  END IF;
 ELSE
  IF (NEW.candidate_type,NEW.subject,NEW.predicate,NEW.value_json,NEW.signal,NEW.sensitive,NEW.candidate_scope,
      NEW.temporal_kind,NEW.temporal_from_epoch_second,NEW.temporal_from_nano,NEW.temporal_to_epoch_second,NEW.temporal_to_nano,
      NEW.relation_kind,NEW.relation_target,NEW.condition_count,NEW.uncertainty_count,NEW.evidence_count,NEW.command_key)
   IS DISTINCT FROM (OLD.candidate_type,OLD.subject,OLD.predicate,OLD.value_json,OLD.signal,OLD.sensitive,OLD.candidate_scope,
      OLD.temporal_kind,OLD.temporal_from_epoch_second,OLD.temporal_from_nano,OLD.temporal_to_epoch_second,OLD.temporal_to_nano,
      OLD.relation_kind,OLD.relation_target,OLD.condition_count,OLD.uncertainty_count,OLD.evidence_count,OLD.command_key)
   OR (OLD.prepared_scope IS NOT NULL AND
    (NEW.prepared_type,NEW.prepared_scope,NEW.retention_class,NEW.schema_version,NEW.payload_schema_id,NEW.valid_from_epoch_second,
     NEW.valid_from_nano,NEW.valid_to_epoch_second,NEW.valid_to_nano,NEW.expires_epoch_second,NEW.expires_nano,
     NEW.confidence,NEW.importance,NEW.review_required,NEW.initial_status,NEW.review_reason_count,NEW.target_fact_id,NEW.target_revision)
    IS DISTINCT FROM (OLD.prepared_type,OLD.prepared_scope,OLD.retention_class,OLD.schema_version,OLD.payload_schema_id,OLD.valid_from_epoch_second,
     OLD.valid_from_nano,OLD.valid_to_epoch_second,OLD.valid_to_nano,OLD.expires_epoch_second,OLD.expires_nano,
     OLD.confidence,OLD.importance,OLD.review_required,OLD.initial_status,OLD.review_reason_count,OLD.target_fact_id,OLD.target_revision)) THEN
   RAISE EXCEPTION 'memory_extraction_candidate_immutable';
  END IF;
 END IF;
 RETURN NEW;
END;
$$;



--
-- Name: guard_memory_extraction_group_identity(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_memory_extraction_group_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF (NEW.integration_id,NEW.group_binding_id,NEW.agent_id,NEW.end_user_id,NEW.source_target_kind,NEW.source_target_id,NEW.source_session_id)
 IS DISTINCT FROM (OLD.integration_id,OLD.group_binding_id,OLD.agent_id,OLD.end_user_id,OLD.source_target_kind,OLD.source_target_id,OLD.source_session_id)
 OR (OLD.source_time_epoch_second IS NOT NULL AND NEW.frozen_subject_count IS DISTINCT FROM OLD.frozen_subject_count) THEN
  RAISE EXCEPTION 'memory_extraction_group_identity_immutable';
 END IF;
 RETURN NEW;
END $$;



--
-- Name: guard_memory_extraction_subject(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_memory_extraction_subject() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE expected_count integer;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'memory_extraction_subject_immutable'; END IF;
 SELECT frozen_subject_count INTO expected_count FROM public.agent_runtime_memory_extraction_jobs WHERE
  (tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key)=
  (NEW.tenant_id,NEW.source_event_id,NEW.extractor_version,NEW.policy_version,NEW.memory_type,NEW.canonical_fact_key);
 IF expected_count IS NULL OR NEW.subject_ordinal>=expected_count THEN
  RAISE EXCEPTION 'memory_extraction_subject_outside_frozen_input';
 END IF;
 RETURN NEW;
END $$;



--
-- Name: guard_memory_query_embedding_operation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_memory_query_embedding_operation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'memory_query_embedding_operation_delete_forbidden'; END IF;
 IF (NEW.tenant_id,NEW.operation_id,NEW.fingerprint,NEW.invocation_id,NEW.model_id,NEW.model_revision,NEW.dimensions,NEW.run_data,NEW.identity_data,NEW.deadline_at,NEW.recorded_at)
 IS DISTINCT FROM (OLD.tenant_id,OLD.operation_id,OLD.fingerprint,OLD.invocation_id,OLD.model_id,OLD.model_revision,OLD.dimensions,OLD.run_data,OLD.identity_data,OLD.deadline_at,OLD.recorded_at)
 OR OLD.state<>'RUNNING' OR NEW.state NOT IN ('READY','REJECTED','UNKNOWN') THEN
  RAISE EXCEPTION 'memory_query_embedding_operation_identity_or_state_mismatch';
 END IF;RETURN NEW;
END $$;



--
-- Name: guard_team_work_answer_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_team_work_answer_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.session_id <> OLD.session_id
       OR NEW.root_input_id <> OLD.root_input_id OR NEW.result_ref <> OLD.result_ref
       OR NEW.answer_type <> OLD.answer_type OR NEW.answer_text <> OLD.answer_text
       OR NEW.source_control_run_id IS DISTINCT FROM OLD.source_control_run_id
       OR NEW.source_control_revision IS DISTINCT FROM OLD.source_control_revision
       OR NEW.source_result_ref IS DISTINCT FROM OLD.source_result_ref
       OR NEW.source_agent_id IS DISTINCT FROM OLD.source_agent_id
       OR NEW.source_run_refs <> OLD.source_run_refs OR NEW.answer_revision <> OLD.answer_revision
       OR NEW.message_id <> OLD.message_id OR NEW.message_event_id <> OLD.message_event_id
       OR NEW.completion_event_id <> OLD.completion_event_id OR NEW.created_at <> OLD.created_at
       OR OLD.status <> 'RECORDED' OR NEW.status <> 'DELIVERED' OR NEW.delivered_at IS NULL THEN
        RAISE EXCEPTION 'team work answer is immutable';
    END IF;
    RETURN NEW;
END;
$$;



--
-- Name: project_agent_runtime_memory_usage(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.project_agent_runtime_memory_usage() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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



--
-- Name: protect_delegation_parent_source(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_delegation_parent_source() RETURNS trigger
    LANGUAGE plpgsql
    AS $$ BEGIN
 IF (NEW.parent_requester_kind,NEW.parent_requester_end_user_id,NEW.parent_requester_system_principal_id,
     NEW.parent_source_session_id,NEW.parent_source_message_id,NEW.parent_origin_ref)
    IS DISTINCT FROM
    (OLD.parent_requester_kind,OLD.parent_requester_end_user_id,OLD.parent_requester_system_principal_id,
     OLD.parent_source_session_id,OLD.parent_source_message_id,OLD.parent_origin_ref)
 THEN RAISE EXCEPTION 'delegation original requester source is immutable'; END IF;
 RETURN NEW;
END $$;



--
-- Name: reject_agent_runtime_delegation_delete(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_agent_runtime_delegation_delete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'agent runtime delegation facts are immutable';
END;
$$;



--
-- Name: reject_agent_runtime_execution_journal_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_agent_runtime_execution_journal_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'agent runtime execution journal is append-only';
    RETURN NULL;
END;
$$;



--
-- Name: reject_agent_runtime_session_event_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_agent_runtime_session_event_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $_$
DECLARE canonical text; data json; name text; operation text; expected text;
BEGIN
    IF TG_OP='UPDATE' AND public.agent_runtime_session_archive_update_valid(TG_TABLE_NAME,to_jsonb(OLD),to_jsonb(NEW)) THEN RETURN NEW; END IF;
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
END $_$;



--
-- Name: reject_agent_runtime_session_owner_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_agent_runtime_session_owner_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'agent runtime session owner root is immutable';
    RETURN NULL;
END;
$$;



--
-- Name: reject_agent_runtime_session_store_fact_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_agent_runtime_session_store_fact_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'agent runtime session store fact is immutable';
    RETURN NULL;
END;
$$;



--
-- Name: reject_legacy_runtime_execution_write(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_legacy_runtime_execution_write() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP='UPDATE' AND public.agent_runtime_session_archive_update_valid(
            TG_TABLE_NAME,to_jsonb(OLD),to_jsonb(NEW)) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'legacy_runtime_execution_read_only';
END $$;



--
-- Name: reject_team_task_result_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_team_task_result_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'team task results are immutable';
END;
$$;



--
-- Name: reject_team_work_answer_delete(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_team_work_answer_delete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'team work answer is immutable';
END;
$$;



--
-- Name: release_dag_capacity_on_run_yield(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.release_dag_capacity_on_run_yield() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.status IN ('SUSPENDED','COMPLETED','FAILED','TERMINATED','CANCELLED') AND OLD.status <> NEW.status THEN
        DELETE FROM public.agent_runtime_dag_permits WHERE tenant_id=NEW.tenant_id AND run_id=NEW.run_id;
    END IF;
    RETURN NEW;
END $$;



--
-- Name: require_typed_callback_completion(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.require_typed_callback_completion() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.status='DELIVERED' AND NOT EXISTS(
        SELECT 1 FROM public.agent_runtime_execution_workflow_callback_deliveries b
        WHERE b.tenant_id=NEW.tenant_id AND b.delivery_id=NEW.delivery_id AND b.input_id=NEW.input_id
    ) THEN
        RAISE EXCEPTION 'callback_typed_response_required';
    END IF;
    RETURN NEW;
END $$;



--
-- Name: reset_memory_decay_on_activation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reset_memory_decay_on_activation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    previous_status text;
    current_status text;
BEGIN
    SELECT status INTO previous_status FROM public.agent_runtime_memory_fact_revisions
        WHERE tenant_id=OLD.tenant_id AND fact_id=OLD.fact_id AND revision=OLD.current_revision;
    SELECT status INTO current_status FROM public.agent_runtime_memory_fact_revisions
        WHERE tenant_id=NEW.tenant_id AND fact_id=NEW.fact_id AND revision=NEW.current_revision;
    IF previous_status IS NOT NULL AND previous_status <> 'ACTIVE' AND current_status = 'ACTIVE' THEN
        INSERT INTO public.agent_runtime_memory_usage_projection
            (tenant_id,fact_id,projected_decay_score,last_referenced_at,updated_at)
        VALUES (NEW.tenant_id,NEW.fact_id,0,now(),now())
        ON CONFLICT (tenant_id,fact_id) DO UPDATE
            SET projected_decay_score=0,last_referenced_at=now(),updated_at=now();
    END IF;
    RETURN NEW;
END;
$$;



--
-- Name: agent_runtime_action_attempt_facts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_action_attempt_facts (
    tenant_id text NOT NULL,
    action_id character varying(160) NOT NULL,
    invocation_id text NOT NULL,
    attempt_id text NOT NULL,
    start_operation_id text NOT NULL,
    evidence_revision bigint NOT NULL,
    reconciles_execute_attempt_id text,
    "authorization" character varying(24) NOT NULL,
    call_kind character varying(32) NOT NULL,
    status character varying(24) NOT NULL,
    outcome_resolution character varying(24),
    outcome_type text,
    outcome_schema_id text,
    outcome_canonical_json text,
    resolution_key text,
    fact_ref text NOT NULL,
    fact_data text NOT NULL,
    started_epoch_second bigint NOT NULL,
    started_nano integer NOT NULL,
    updated_epoch_second bigint NOT NULL,
    updated_nano integer NOT NULL,
    CONSTRAINT ck_agent_runtime_action_attempt_authorization CHECK ((((call_kind)::text = ANY ((ARRAY['MODEL_GENERATE'::character varying, 'MODEL_STREAM'::character varying, 'TOOL_INVOKE'::character varying, 'WORKFLOW_DISPATCH'::character varying, 'WORKFLOW_RESUME'::character varying, 'WORKFLOW_RECOVER_RESULT'::character varying, 'WORKFLOW_CANCEL'::character varying, 'KNOWLEDGE_EXECUTE'::character varying])::text[])) AND (((("authorization")::text = 'EXECUTE'::text) AND (reconciles_execute_attempt_id IS NULL) AND ((call_kind)::text <> 'WORKFLOW_RECOVER_RESULT'::text)) OR ((("authorization")::text = 'RESUME_INPUT'::text) AND (reconciles_execute_attempt_id IS NULL) AND ((call_kind)::text = 'TOOL_INVOKE'::text)) OR ((("authorization")::text = 'RECONCILE'::text) AND (reconciles_execute_attempt_id IS NOT NULL) AND (btrim(reconciles_execute_attempt_id) <> ''::text) AND ((call_kind)::text = 'WORKFLOW_RECOVER_RESULT'::text))))),
    CONSTRAINT ck_agent_runtime_action_attempt_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim((action_id)::text) <> ''::text) AND (btrim(invocation_id) <> ''::text) AND (btrim(attempt_id) <> ''::text) AND (attempt_id <> invocation_id) AND (btrim(start_operation_id) <> ''::text) AND (evidence_revision > 0) AND (btrim(fact_ref) <> ''::text) AND (btrim(fact_data) <> ''::text))),
    CONSTRAINT ck_agent_runtime_action_attempt_outcome CHECK ((((status)::text = ANY ((ARRAY['STARTED'::character varying, 'NOT_DISPATCHED'::character varying, 'INPUT_REQUIRED'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying, 'UNRESOLVED'::character varying])::text[])) AND ((((status)::text = ANY ((ARRAY['STARTED'::character varying, 'NOT_DISPATCHED'::character varying])::text[])) AND (outcome_resolution IS NULL) AND (outcome_type IS NULL) AND (outcome_schema_id IS NULL) AND (outcome_canonical_json IS NULL) AND (resolution_key IS NULL)) OR (((status)::text = 'INPUT_REQUIRED'::text) AND ((outcome_resolution)::text = 'INPUT_REQUIRED'::text) AND (outcome_type = 'InputRequired'::text) AND (outcome_schema_id IS NOT NULL) AND (btrim(outcome_schema_id) <> ''::text) AND (outcome_canonical_json IS NOT NULL) AND (btrim(outcome_canonical_json) <> ''::text) AND (resolution_key IS NULL)) OR (((status)::text = 'SUCCEEDED'::text) AND ((outcome_resolution)::text = 'KNOWN_RESULT'::text) AND (outcome_type IS NOT NULL) AND (btrim(outcome_type) <> ''::text) AND (outcome_schema_id IS NOT NULL) AND (btrim(outcome_schema_id) <> ''::text) AND (outcome_canonical_json IS NOT NULL) AND (btrim(outcome_canonical_json) <> ''::text)) OR (((status)::text = 'FAILED'::text) AND ((outcome_resolution)::text = 'KNOWN_FAILURE'::text) AND (outcome_type IS NOT NULL) AND (btrim(outcome_type) <> ''::text) AND (outcome_schema_id IS NOT NULL) AND (btrim(outcome_schema_id) <> ''::text) AND (outcome_canonical_json IS NOT NULL) AND (btrim(outcome_canonical_json) <> ''::text)) OR (((status)::text = 'UNRESOLVED'::text) AND ((outcome_resolution)::text = 'OUTCOME_UNKNOWN'::text) AND (outcome_type IS NOT NULL) AND (btrim(outcome_type) <> ''::text) AND (outcome_schema_id IS NOT NULL) AND (btrim(outcome_schema_id) <> ''::text) AND (outcome_canonical_json IS NOT NULL) AND (btrim(outcome_canonical_json) <> ''::text) AND (resolution_key IS NULL))) AND ((resolution_key IS NULL) OR (btrim(resolution_key) <> ''::text)))),
    CONSTRAINT ck_agent_runtime_action_attempt_times CHECK ((((started_nano >= 0) AND (started_nano <= 999999999)) AND ((updated_nano >= 0) AND (updated_nano <= 999999999)) AND (ROW(updated_epoch_second, updated_nano) >= ROW(started_epoch_second, started_nano))))
);

ALTER TABLE ONLY public.agent_runtime_action_attempt_facts FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_action_state_publications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_action_state_publications (
    tenant_id text NOT NULL,
    source_kind character varying(16) NOT NULL,
    source_id character varying(160) NOT NULL,
    event_id character varying(160) NOT NULL,
    source_revision bigint NOT NULL,
    publication_revision bigint NOT NULL,
    available_epoch_second bigint NOT NULL,
    available_nano integer NOT NULL,
    status character varying(16) NOT NULL,
    attempts integer NOT NULL,
    lease_claim_id text,
    lease_worker_id text,
    lease_fencing_token bigint,
    lease_until_epoch_second bigint,
    lease_until_nano integer,
    projection_ref text,
    failure_data text,
    publication_fingerprint character varying(64) NOT NULL,
    publication_data text NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    updated_epoch_second bigint NOT NULL,
    updated_nano integer NOT NULL,
    CONSTRAINT ck_agent_runtime_action_publication_codec CHECK ((((publication_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (btrim(publication_data) <> ''::text))),
    CONSTRAINT ck_agent_runtime_action_publication_identity CHECK (((btrim(tenant_id) <> ''::text) AND ((source_kind)::text = 'action'::text) AND (btrim((source_id)::text) <> ''::text) AND (btrim((event_id)::text) <> ''::text) AND (source_revision > 0) AND (publication_revision > 0) AND ((available_nano >= 0) AND (available_nano <= 999999999)) AND (attempts >= 0))),
    CONSTRAINT ck_agent_runtime_action_publication_lease CHECK (((((status)::text = 'CLAIMED'::text) AND (lease_claim_id IS NOT NULL) AND (lease_worker_id IS NOT NULL) AND (lease_fencing_token IS NOT NULL) AND (lease_until_epoch_second IS NOT NULL) AND (lease_until_nano IS NOT NULL) AND (btrim(lease_claim_id) <> ''::text) AND (btrim(lease_worker_id) <> ''::text) AND (lease_fencing_token > 0) AND ((lease_until_nano >= 0) AND (lease_until_nano <= 999999999))) OR (((status)::text <> 'CLAIMED'::text) AND (lease_claim_id IS NULL) AND (lease_worker_id IS NULL) AND (lease_fencing_token IS NULL) AND (lease_until_epoch_second IS NULL) AND (lease_until_nano IS NULL)))),
    CONSTRAINT ck_agent_runtime_action_publication_projection CHECK (((((status)::text = 'ACKNOWLEDGED'::text) = (projection_ref IS NOT NULL)) AND ((projection_ref IS NULL) OR (btrim(projection_ref) <> ''::text)))),
    CONSTRAINT ck_agent_runtime_action_publication_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'CLAIMED'::character varying, 'ACKNOWLEDGED'::character varying])::text[]))),
    CONSTRAINT ck_agent_runtime_action_publication_times CHECK ((((created_nano >= 0) AND (created_nano <= 999999999)) AND ((updated_nano >= 0) AND (updated_nano <= 999999999)) AND (ROW(updated_epoch_second, updated_nano) >= ROW(created_epoch_second, created_nano))))
);

ALTER TABLE ONLY public.agent_runtime_action_state_publications FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_actions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_actions (
    tenant_id text NOT NULL,
    action_id character varying(160) NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    run_id character varying(160) NOT NULL,
    causation_input_id character varying(160) NOT NULL,
    causation_identity_ref text NOT NULL,
    execution_id character varying(160),
    delegation_id character varying(160),
    action_kind text NOT NULL,
    execute_call_kind character varying(32) NOT NULL,
    execute_target_handle text NOT NULL,
    execute_request_fingerprint character varying(64) NOT NULL,
    execute_invocation_snapshot_ref text NOT NULL,
    execute_call_data text NOT NULL,
    reconcile_call_kind character varying(32),
    reconcile_target_handle text,
    reconcile_request_fingerprint character varying(64),
    reconcile_invocation_snapshot_ref text,
    reconcile_call_data text,
    idempotency_key text NOT NULL,
    retry_mode character varying(48) NOT NULL,
    invocation_id text NOT NULL,
    state character varying(24) NOT NULL,
    "authorization" character varying(24) NOT NULL,
    action_revision bigint NOT NULL,
    attempt integer NOT NULL,
    attempt_id text NOT NULL,
    attempt_call_kind character varying(32) NOT NULL,
    attempt_target_handle text NOT NULL,
    attempt_request_fingerprint character varying(64) NOT NULL,
    attempt_invocation_snapshot_ref text NOT NULL,
    attempt_call_data text NOT NULL,
    reconciles_execute_attempt_id text,
    canonical_observation_data text,
    outcome_fact_ref text,
    reason_code text,
    lease_claim_id text,
    lease_worker_id text,
    lease_fencing_token bigint,
    lease_action_revision bigint,
    lease_until_epoch_second bigint,
    lease_until_nano integer,
    initial_claim_data text NOT NULL,
    record_data text NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    updated_epoch_second bigint NOT NULL,
    updated_nano integer NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    CONSTRAINT agent_runtime_actions_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_action_attempt_call CHECK ((((attempt_call_kind)::text = ANY ((ARRAY['MODEL_GENERATE'::character varying, 'MODEL_STREAM'::character varying, 'TOOL_INVOKE'::character varying, 'WORKFLOW_DISPATCH'::character varying, 'WORKFLOW_RESUME'::character varying, 'WORKFLOW_RECOVER_RESULT'::character varying, 'WORKFLOW_CANCEL'::character varying, 'KNOWLEDGE_EXECUTE'::character varying])::text[])) AND (btrim(attempt_target_handle) <> ''::text) AND ((attempt_request_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (btrim(attempt_invocation_snapshot_ref) <> ''::text) AND (btrim(attempt_call_data) <> ''::text) AND (((("authorization")::text = 'EXECUTE'::text) AND (reconciles_execute_attempt_id IS NULL) AND (((attempt_call_kind)::text = (execute_call_kind)::text) AND (attempt_target_handle = execute_target_handle) AND ((attempt_request_fingerprint)::text = (execute_request_fingerprint)::text) AND (attempt_invocation_snapshot_ref = execute_invocation_snapshot_ref) AND (attempt_call_data = execute_call_data))) OR ((("authorization")::text = 'RESUME_INPUT'::text) AND ((execute_call_kind)::text = 'TOOL_INVOKE'::text) AND ((attempt_call_kind)::text = 'TOOL_INVOKE'::text) AND (attempt_target_handle = execute_target_handle) AND (attempt_invocation_snapshot_ref <> execute_invocation_snapshot_ref) AND (attempt_invocation_snapshot_ref ~ '^skillforge[.]runtime[.]portal[.]tool-continuation[.]v1:[0-9a-f]{64}$'::text) AND (reconciles_execute_attempt_id IS NULL)) OR ((("authorization")::text = 'RECONCILE'::text) AND ((attempt_call_kind)::text = 'WORKFLOW_RECOVER_RESULT'::text) AND (reconciles_execute_attempt_id IS NOT NULL) AND (btrim(reconciles_execute_attempt_id) <> ''::text) AND (attempt_target_handle = reconcile_target_handle)) OR ((("authorization")::text = 'FINALIZE_INPUT'::text) AND ((execute_call_kind)::text = 'TOOL_INVOKE'::text) AND ((attempt_call_kind)::text = 'TOOL_INVOKE'::text) AND (attempt_target_handle = execute_target_handle) AND (reconciles_execute_attempt_id IS NULL)) OR ((("authorization")::text = ANY ((ARRAY['FINALIZE_KNOWN'::character varying, 'FINALIZE_UNKNOWN'::character varying])::text[])) AND ((((attempt_call_kind)::text = 'WORKFLOW_RECOVER_RESULT'::text) AND (reconciles_execute_attempt_id IS NOT NULL) AND (btrim(reconciles_execute_attempt_id) <> ''::text)) OR (((attempt_call_kind)::text <> 'WORKFLOW_RECOVER_RESULT'::text) AND (reconciles_execute_attempt_id IS NULL))))))),
    CONSTRAINT ck_agent_runtime_action_codec CHECK (((btrim(initial_claim_data) <> ''::text) AND (btrim(record_data) <> ''::text) AND ((canonical_observation_data IS NULL) OR (btrim(canonical_observation_data) <> ''::text)))),
    CONSTRAINT ck_agent_runtime_action_execute_call CHECK ((((execute_call_kind)::text = ANY ((ARRAY['MODEL_GENERATE'::character varying, 'MODEL_STREAM'::character varying, 'TOOL_INVOKE'::character varying, 'WORKFLOW_DISPATCH'::character varying, 'WORKFLOW_RESUME'::character varying, 'WORKFLOW_CANCEL'::character varying, 'KNOWLEDGE_EXECUTE'::character varying])::text[])) AND (btrim(execute_target_handle) <> ''::text) AND ((execute_request_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (btrim(execute_invocation_snapshot_ref) <> ''::text) AND (btrim(execute_call_data) <> ''::text))),
    CONSTRAINT ck_agent_runtime_action_identity CHECK (((btrim(action_kind) <> ''::text) AND (btrim(idempotency_key) <> ''::text) AND (btrim(invocation_id) <> ''::text) AND (btrim(attempt_id) <> ''::text) AND (attempt_id <> invocation_id) AND (action_revision > 0) AND (attempt > 0))),
    CONSTRAINT ck_agent_runtime_action_owner CHECK (((btrim(tenant_id) <> ''::text) AND (btrim((action_id)::text) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (end_user_id > 0) AND (btrim((run_id)::text) <> ''::text) AND (btrim((causation_input_id)::text) <> ''::text) AND (btrim(causation_identity_ref) <> ''::text) AND ((execution_id IS NULL) OR (btrim((execution_id)::text) <> ''::text)) AND ((delegation_id IS NULL) OR (btrim((delegation_id)::text) <> ''::text)))),
    CONSTRAINT ck_agent_runtime_action_reconcile_call CHECK (((((retry_mode)::text = 'RESULT_QUERYABLE'::text) AND ((reconcile_call_kind)::text = 'WORKFLOW_RECOVER_RESULT'::text) AND (reconcile_target_handle IS NOT NULL) AND (reconcile_request_fingerprint IS NOT NULL) AND (reconcile_invocation_snapshot_ref IS NOT NULL) AND (reconcile_call_data IS NOT NULL) AND (btrim(reconcile_target_handle) <> ''::text) AND ((reconcile_request_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (btrim(reconcile_invocation_snapshot_ref) <> ''::text) AND (btrim(reconcile_call_data) <> ''::text)) OR (((retry_mode)::text <> 'RESULT_QUERYABLE'::text) AND (reconcile_call_kind IS NULL) AND (reconcile_target_handle IS NULL) AND (reconcile_request_fingerprint IS NULL) AND (reconcile_invocation_snapshot_ref IS NULL) AND (reconcile_call_data IS NULL)))),
    CONSTRAINT ck_agent_runtime_action_retry_contract CHECK (((((execute_call_kind)::text = ANY ((ARRAY['MODEL_GENERATE'::character varying, 'MODEL_STREAM'::character varying, 'KNOWLEDGE_EXECUTE'::character varying])::text[])) AND ((retry_mode)::text = 'NO_AUTOMATIC_RETRY'::text)) OR (((execute_call_kind)::text = 'TOOL_INVOKE'::text) AND ((retry_mode)::text = ANY ((ARRAY['NO_AUTOMATIC_RETRY'::character varying, 'PROVIDER_IDEMPOTENT_BY_ACTION_ID'::character varying])::text[]))) OR (((execute_call_kind)::text = ANY ((ARRAY['WORKFLOW_DISPATCH'::character varying, 'WORKFLOW_RESUME'::character varying, 'WORKFLOW_CANCEL'::character varying])::text[])) AND ((retry_mode)::text = ANY ((ARRAY['NO_AUTOMATIC_RETRY'::character varying, 'RESULT_QUERYABLE'::character varying])::text[]))))),
    CONSTRAINT ck_agent_runtime_action_state CHECK ((((state)::text = ANY ((ARRAY['CLAIMED'::character varying, 'WAITING_INPUT'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying, 'OUTCOME_UNKNOWN'::character varying])::text[])) AND (("authorization")::text = ANY ((ARRAY['EXECUTE'::character varying, 'RESUME_INPUT'::character varying, 'RECONCILE'::character varying, 'FINALIZE_INPUT'::character varying, 'FINALIZE_KNOWN'::character varying, 'FINALIZE_UNKNOWN'::character varying])::text[])))),
    CONSTRAINT ck_agent_runtime_action_terminal_fields CHECK (((((state)::text = 'CLAIMED'::text) AND (canonical_observation_data IS NULL) AND (outcome_fact_ref IS NULL) AND (reason_code IS NULL) AND (lease_claim_id IS NOT NULL) AND (lease_worker_id IS NOT NULL) AND (lease_fencing_token IS NOT NULL) AND (lease_action_revision = action_revision) AND (lease_until_epoch_second IS NOT NULL) AND (lease_until_nano IS NOT NULL) AND (btrim(lease_claim_id) <> ''::text) AND (btrim(lease_worker_id) <> ''::text) AND (lease_fencing_token > 0) AND ((lease_until_nano >= 0) AND (lease_until_nano <= 999999999))) OR (((state)::text = 'WAITING_INPUT'::text) AND (canonical_observation_data IS NULL) AND (outcome_fact_ref IS NULL) AND (reason_code IS NULL) AND (lease_claim_id IS NULL) AND (lease_worker_id IS NULL) AND (lease_fencing_token IS NULL) AND (lease_action_revision IS NULL) AND (lease_until_epoch_second IS NULL) AND (lease_until_nano IS NULL)) OR (((state)::text = 'SUCCEEDED'::text) AND (outcome_fact_ref IS NOT NULL) AND (btrim(outcome_fact_ref) <> ''::text) AND (reason_code IS NULL) AND (lease_claim_id IS NULL) AND (lease_worker_id IS NULL) AND (lease_fencing_token IS NULL) AND (lease_action_revision IS NULL) AND (lease_until_epoch_second IS NULL) AND (lease_until_nano IS NULL)) OR (((state)::text = ANY ((ARRAY['FAILED'::character varying, 'OUTCOME_UNKNOWN'::character varying])::text[])) AND (canonical_observation_data IS NULL) AND (outcome_fact_ref IS NOT NULL) AND (reason_code IS NOT NULL) AND (btrim(outcome_fact_ref) <> ''::text) AND (btrim(reason_code) <> ''::text) AND (lease_claim_id IS NULL) AND (lease_worker_id IS NULL) AND (lease_fencing_token IS NULL) AND (lease_action_revision IS NULL) AND (lease_until_epoch_second IS NULL) AND (lease_until_nano IS NULL)))),
    CONSTRAINT ck_agent_runtime_action_times CHECK ((((created_nano >= 0) AND (created_nano <= 999999999)) AND ((updated_nano >= 0) AND (updated_nano <= 999999999)) AND (ROW(updated_epoch_second, updated_nano) >= ROW(created_epoch_second, created_nano))))
);

ALTER TABLE ONLY public.agent_runtime_actions FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_callback_audits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_callback_audits (
    tenant_id text NOT NULL,
    resource_kind text NOT NULL,
    resource_id text NOT NULL,
    command_digest text NOT NULL,
    audit_ref text NOT NULL,
    reason_code text NOT NULL,
    CONSTRAINT agent_runtime_callback_audits_audit_ref_check CHECK ((btrim(audit_ref) <> ''::text)),
    CONSTRAINT agent_runtime_callback_audits_command_digest_check CHECK ((command_digest ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT agent_runtime_callback_audits_reason_code_check CHECK ((btrim(reason_code) <> ''::text)),
    CONSTRAINT agent_runtime_callback_audits_resource_id_check CHECK ((btrim(resource_id) <> ''::text)),
    CONSTRAINT agent_runtime_callback_audits_resource_kind_check CHECK ((resource_kind = ANY (ARRAY['registration'::text, 'delivery'::text]))),
    CONSTRAINT agent_runtime_callback_audits_tenant_id_check CHECK ((btrim(tenant_id) <> ''::text))
);

ALTER TABLE ONLY public.agent_runtime_callback_audits FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_callback_deliveries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_callback_deliveries (
    tenant_id text NOT NULL,
    delivery_id text NOT NULL,
    callback_id text NOT NULL,
    command_digest text NOT NULL,
    input_id text,
    envelope_data text,
    status text NOT NULL,
    revision bigint NOT NULL,
    attempt integer NOT NULL,
    reason_code text,
    audit_ref text,
    lease_claim text,
    lease_worker text,
    lease_token bigint,
    lease_until numeric(30,9),
    available_at numeric(30,9) NOT NULL,
    recorded_at numeric(30,9) NOT NULL,
    updated_at numeric(30,9) NOT NULL,
    CONSTRAINT agent_runtime_callback_deliveries_attempt_check CHECK ((attempt >= 0)),
    CONSTRAINT agent_runtime_callback_deliveries_available_at_check CHECK ((available_at <> 'NaN'::numeric)),
    CONSTRAINT agent_runtime_callback_deliveries_callback_id_check CHECK ((btrim(callback_id) <> ''::text)),
    CONSTRAINT agent_runtime_callback_deliveries_command_digest_check CHECK ((command_digest ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT agent_runtime_callback_deliveries_delivery_id_check CHECK ((btrim(delivery_id) <> ''::text)),
    CONSTRAINT agent_runtime_callback_deliveries_lease_until_check CHECK ((lease_until <> 'NaN'::numeric)),
    CONSTRAINT agent_runtime_callback_deliveries_recorded_at_check CHECK ((recorded_at <> 'NaN'::numeric)),
    CONSTRAINT agent_runtime_callback_deliveries_revision_check CHECK ((revision > 0)),
    CONSTRAINT agent_runtime_callback_deliveries_status_check CHECK ((status = ANY (ARRAY['READY'::text, 'PROCESSING'::text, 'DELIVERED'::text, 'REJECTED'::text, 'DEAD_LETTER'::text]))),
    CONSTRAINT agent_runtime_callback_deliveries_tenant_id_check CHECK ((btrim(tenant_id) <> ''::text)),
    CONSTRAINT agent_runtime_callback_deliveries_updated_at_check CHECK ((updated_at <> 'NaN'::numeric)),
    CONSTRAINT agent_runtime_callback_delivery_fields CHECK ((((status = 'REJECTED'::text) AND (input_id IS NULL) AND (envelope_data IS NULL) AND (attempt = 0) AND (reason_code IS NOT NULL) AND (btrim(reason_code) <> ''::text) AND (audit_ref IS NOT NULL) AND (btrim(audit_ref) <> ''::text)) OR ((status = 'DEAD_LETTER'::text) AND (input_id IS NOT NULL) AND (btrim(input_id) <> ''::text) AND (envelope_data IS NOT NULL) AND (envelope_data <> ''::text) AND (attempt > 0) AND (reason_code IS NOT NULL) AND (btrim(reason_code) <> ''::text) AND (audit_ref IS NOT NULL) AND (btrim(audit_ref) <> ''::text)) OR ((status = ANY (ARRAY['READY'::text, 'PROCESSING'::text, 'DELIVERED'::text])) AND (input_id IS NOT NULL) AND (btrim(input_id) <> ''::text) AND (envelope_data IS NOT NULL) AND (envelope_data <> ''::text) AND (reason_code IS NULL) AND (audit_ref IS NULL) AND ((status = 'READY'::text) OR (attempt > 0))))),
    CONSTRAINT agent_runtime_callback_delivery_lease CHECK ((((status = 'PROCESSING'::text) AND (lease_claim IS NOT NULL) AND (btrim(lease_claim) <> ''::text) AND (lease_worker IS NOT NULL) AND (btrim(lease_worker) <> ''::text) AND (lease_token IS NOT NULL) AND (lease_token > 0) AND (lease_until IS NOT NULL)) OR ((status <> 'PROCESSING'::text) AND (lease_claim IS NULL) AND (lease_worker IS NULL) AND (lease_token IS NULL) AND (lease_until IS NULL))))
);

ALTER TABLE ONLY public.agent_runtime_callback_deliveries FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_callback_delivery_operations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_callback_delivery_operations (
    tenant_id text NOT NULL,
    delivery_id text NOT NULL,
    operation_id text NOT NULL,
    command_data text NOT NULL,
    output_revision bigint NOT NULL,
    CONSTRAINT agent_runtime_callback_delivery_operation_output_revision_check CHECK ((output_revision > 1)),
    CONSTRAINT agent_runtime_callback_delivery_operations_command_data_check CHECK ((command_data <> ''::text)),
    CONSTRAINT agent_runtime_callback_delivery_operations_operation_id_check CHECK ((btrim(operation_id) <> ''::text))
);

ALTER TABLE ONLY public.agent_runtime_callback_delivery_operations FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_callback_fence_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.agent_runtime_callback_fence_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;



--
-- Name: agent_runtime_callback_ingress_proofs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_callback_ingress_proofs (
    tenant_id text NOT NULL,
    callback_id text NOT NULL,
    delivery_id text NOT NULL,
    fingerprint text NOT NULL,
    authentication_ref text NOT NULL,
    command_digest text NOT NULL,
    registration_revision bigint NOT NULL,
    verified_at numeric(30,9) NOT NULL,
    CONSTRAINT agent_runtime_callback_ingress_proo_registration_revision_check CHECK ((registration_revision > 0)),
    CONSTRAINT agent_runtime_callback_ingress_proofs_authentication_ref_check CHECK ((length(authentication_ref) > 0)),
    CONSTRAINT agent_runtime_callback_ingress_proofs_command_digest_check CHECK ((command_digest ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT agent_runtime_callback_ingress_proofs_fingerprint_check CHECK ((fingerprint ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT agent_runtime_callback_ingress_proofs_verified_at_check CHECK ((verified_at <> 'NaN'::numeric))
);

ALTER TABLE ONLY public.agent_runtime_callback_ingress_proofs FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_callback_registration_operations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_callback_registration_operations (
    tenant_id text NOT NULL,
    callback_id text NOT NULL,
    operation_id text NOT NULL,
    command_data text NOT NULL,
    output_revision bigint NOT NULL,
    CONSTRAINT agent_runtime_callback_registration_opera_output_revision_check CHECK ((output_revision > 1)),
    CONSTRAINT agent_runtime_callback_registration_operatio_command_data_check CHECK ((command_data <> ''::text)),
    CONSTRAINT agent_runtime_callback_registration_operatio_operation_id_check CHECK ((btrim(operation_id) <> ''::text))
);

ALTER TABLE ONLY public.agent_runtime_callback_registration_operations FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_callback_registrations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_callback_registrations (
    tenant_id text NOT NULL,
    callback_id text NOT NULL,
    idempotency_key text NOT NULL,
    command_data text NOT NULL,
    status text NOT NULL,
    revision bigint NOT NULL,
    created_at numeric(30,9) NOT NULL,
    updated_at numeric(30,9) NOT NULL,
    target_kind text DEFAULT 'OTHER'::text NOT NULL,
    action_agent_id text,
    action_session_id text,
    action_end_user_id bigint,
    action_run_id text,
    action_id text,
    action_invocation_id text,
    registered_action_revision bigint,
    action_target_kind text,
    action_target_id text,
    CONSTRAINT agent_runtime_callback_registrations_callback_id_check CHECK ((btrim(callback_id) <> ''::text)),
    CONSTRAINT agent_runtime_callback_registrations_command_data_check CHECK ((command_data <> ''::text)),
    CONSTRAINT agent_runtime_callback_registrations_created_at_check CHECK ((created_at <> 'NaN'::numeric)),
    CONSTRAINT agent_runtime_callback_registrations_idempotency_key_check CHECK ((btrim(idempotency_key) <> ''::text)),
    CONSTRAINT agent_runtime_callback_registrations_revision_check CHECK ((revision > 0)),
    CONSTRAINT agent_runtime_callback_registrations_status_check CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'SUSPENDED'::text, 'REVOKED'::text]))),
    CONSTRAINT agent_runtime_callback_registrations_target_kind_check CHECK ((target_kind = ANY (ARRAY['OTHER'::text, 'ACTION'::text]))),
    CONSTRAINT agent_runtime_callback_registrations_tenant_id_check CHECK ((btrim(tenant_id) <> ''::text)),
    CONSTRAINT agent_runtime_callback_registrations_updated_at_check CHECK ((updated_at <> 'NaN'::numeric)),
    CONSTRAINT ck_agent_runtime_callback_action_binding CHECK ((((action_agent_id IS NULL) AND (action_session_id IS NULL) AND (action_end_user_id IS NULL) AND (action_run_id IS NULL) AND (action_id IS NULL) AND (action_invocation_id IS NULL) AND (registered_action_revision IS NULL)) OR ((btrim(action_agent_id) <> ''::text) AND (btrim(action_session_id) <> ''::text) AND (action_end_user_id > 0) AND (btrim(action_run_id) <> ''::text) AND (btrim(action_id) <> ''::text) AND (btrim(action_invocation_id) <> ''::text) AND (registered_action_revision > 0)))),
    CONSTRAINT ck_agent_runtime_callback_action_target CHECK ((((target_kind = 'OTHER'::text) AND (action_target_kind IS NULL) AND (action_target_id IS NULL)) OR ((target_kind = 'ACTION'::text) AND (action_target_kind IS NOT NULL) AND (action_target_id IS NOT NULL) AND (action_target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(action_target_id) <> ''::text)))),
    CONSTRAINT ck_agent_runtime_callback_action_target_kind CHECK ((((target_kind = 'ACTION'::text) AND (action_id IS NOT NULL)) OR ((target_kind = 'OTHER'::text) AND (action_id IS NULL))))
);

ALTER TABLE ONLY public.agent_runtime_callback_registrations FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_conversation_task_bindings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_conversation_task_bindings (
    tenant_id text NOT NULL,
    task_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    session_id text NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    CONSTRAINT agent_runtime_conversation_task_bindings_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text)))
);

ALTER TABLE ONLY public.agent_runtime_conversation_task_bindings FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_conversation_task_operations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_conversation_task_operations (
    tenant_id text NOT NULL,
    task_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    session_id text NOT NULL,
    event_id text NOT NULL,
    expected_revision bigint NOT NULL,
    selected boolean NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    CONSTRAINT agent_runtime_conversation_task_operations_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text))),
    CONSTRAINT ck_conversation_task_operation_revision CHECK ((expected_revision >= 0))
);

ALTER TABLE ONLY public.agent_runtime_conversation_task_operations FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_conversation_task_roots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_conversation_task_roots (
    tenant_id text NOT NULL,
    task_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    run_id text,
    fence bigint DEFAULT 0 NOT NULL,
    until_second bigint,
    until_nano integer,
    CONSTRAINT ck_conversation_task_lease CHECK (((fence >= 0) AND (((run_id IS NULL) AND (until_second IS NULL) AND (until_nano IS NULL)) OR ((run_id IS NOT NULL) AND (btrim(run_id) <> ''::text) AND (until_second IS NOT NULL) AND (until_nano IS NOT NULL) AND ((until_nano >= 0) AND (until_nano <= 999999999)))))),
    CONSTRAINT ck_conversation_task_owner CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(task_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (end_user_id > 0)))
);

ALTER TABLE ONLY public.agent_runtime_conversation_task_roots FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_conversation_tasks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_conversation_tasks (
    tenant_id text NOT NULL,
    task_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    revision bigint NOT NULL,
    status text NOT NULL,
    title text NOT NULL,
    summary text NOT NULL,
    schema_id text NOT NULL,
    snapshot text NOT NULL,
    updated_second bigint NOT NULL,
    updated_nano integer NOT NULL,
    CONSTRAINT ck_conversation_task_projection CHECK (((revision > 0) AND (status = ANY (ARRAY['OPEN'::text, 'WAITING_USER'::text, 'COMPLETED'::text, 'CANCELLED'::text])) AND ((char_length(title) >= 1) AND (char_length(title) <= 200)) AND (char_length(summary) <= 8000) AND (btrim(schema_id) <> ''::text) AND ((updated_nano >= 0) AND (updated_nano <= 999999999))))
);

ALTER TABLE ONLY public.agent_runtime_conversation_tasks FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_dag_admissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_dag_admissions (
    tenant_id character varying(160) NOT NULL,
    session_id character varying(240) NOT NULL,
    root_input_id character varying(240) NOT NULL,
    task_id character varying(240) NOT NULL,
    admission_id character varying(240) NOT NULL,
    run_id character varying(240) NOT NULL,
    agent_id character varying(240) NOT NULL,
    plan_revision bigint NOT NULL,
    binding text NOT NULL,
    executor_type character varying(10) NOT NULL,
    CONSTRAINT agent_runtime_dag_admissions_executor_type_check CHECK (((executor_type)::text = ANY ((ARRAY['PRIMARY'::character varying, 'MEMBER'::character varying])::text[])))
);

ALTER TABLE ONLY public.agent_runtime_dag_admissions FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_dag_commands; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_dag_commands (
    tenant_id character varying(160) NOT NULL,
    session_id character varying(240) NOT NULL,
    root_input_id character varying(240) NOT NULL,
    command_id character varying(240) NOT NULL,
    fingerprint character varying(128) NOT NULL
);

ALTER TABLE ONLY public.agent_runtime_dag_commands FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_dag_control_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_dag_control_runs (
    tenant_id character varying(160) NOT NULL,
    session_id character varying(240) NOT NULL,
    root_input_id character varying(240) NOT NULL,
    run_id character varying(240) NOT NULL,
    agent_id character varying(240) NOT NULL,
    role character varying(20) NOT NULL,
    CONSTRAINT agent_runtime_dag_control_runs_role_check CHECK (((role)::text = ANY ((ARRAY['ROUTING'::character varying, 'WORKING'::character varying])::text[])))
);

ALTER TABLE ONLY public.agent_runtime_dag_control_runs FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_dag_inputs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_dag_inputs (
    sequence bigint NOT NULL,
    tenant_id character varying(160) NOT NULL,
    session_id character varying(240) NOT NULL,
    root_input_id character varying(240) NOT NULL,
    input_id character varying(240) NOT NULL,
    payload text NOT NULL,
    state character varying(20) DEFAULT 'ACCEPTED'::character varying NOT NULL,
    detail text DEFAULT ''::text NOT NULL,
    received_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT agent_runtime_dag_inputs_state_check CHECK (((state)::text = ANY ((ARRAY['ACCEPTED'::character varying, 'APPLIED'::character varying, 'REJECTED'::character varying])::text[])))
);

ALTER TABLE ONLY public.agent_runtime_dag_inputs FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_dag_inputs_sequence_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_dag_inputs ALTER COLUMN sequence ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.agent_runtime_dag_inputs_sequence_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);



--
-- Name: agent_runtime_dag_permits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_dag_permits (
    tenant_id character varying(160) NOT NULL,
    run_id character varying(240) NOT NULL,
    fence bigint NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    admission_id character varying(240) NOT NULL,
    CONSTRAINT agent_runtime_dag_permits_fence_check CHECK ((fence > 0))
);

ALTER TABLE ONLY public.agent_runtime_dag_permits FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_dag_step_actions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_dag_step_actions (
    tenant_id character varying(160) NOT NULL,
    action_id character varying(160) NOT NULL,
    admission_id character varying(240) NOT NULL,
    run_id character varying(240) NOT NULL,
    task_id character varying(240) NOT NULL
);

ALTER TABLE ONLY public.agent_runtime_dag_step_actions FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_dag_works; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_dag_works (
    work_sequence bigint NOT NULL,
    tenant_id character varying(160) NOT NULL,
    session_id character varying(240) NOT NULL,
    root_input_id character varying(240) NOT NULL,
    target_kind character varying(10) NOT NULL,
    target_id character varying(240) NOT NULL,
    end_user_id bigint,
    revision bigint NOT NULL,
    status character varying(20) NOT NULL,
    snapshot text NOT NULL,
    updated_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    subject_kind text GENERATED ALWAYS AS (
CASE
    WHEN ((target_kind)::text = 'GROUP'::text) THEN 'GROUP'::text
    ELSE 'USER'::text
END) STORED NOT NULL,
    subject_id text GENERATED ALWAYS AS (
CASE
    WHEN ((target_kind)::text = 'GROUP'::text) THEN (target_id)::text
    ELSE (end_user_id)::text
END) STORED NOT NULL,
    CONSTRAINT agent_runtime_dag_works_revision_check CHECK ((revision > 0)),
    CONSTRAINT agent_runtime_dag_works_status_check CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'ACTIVE'::character varying, 'CANCELLING'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT dag_work_subject_ck CHECK (((((target_kind)::text = ANY ((ARRAY['AGENT'::character varying, 'TEAM'::character varying])::text[])) AND (end_user_id IS NOT NULL) AND (end_user_id > 0)) OR (((target_kind)::text = 'GROUP'::text) AND (end_user_id IS NULL))))
);

ALTER TABLE ONLY public.agent_runtime_dag_works FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_dag_works_work_sequence_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_dag_works ALTER COLUMN work_sequence ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.agent_runtime_dag_works_work_sequence_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);



--
-- Name: agent_runtime_delegation_waits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_delegation_waits (
    tenant_id character varying(160) NOT NULL,
    parent_session_id character varying(240) NOT NULL,
    parent_target_kind character varying(10) NOT NULL,
    parent_target_id character varying(240) NOT NULL,
    parent_end_user_id bigint,
    parent_agent_id character varying(240) NOT NULL,
    parent_causation_input_id character varying(240) NOT NULL,
    parent_run_id character varying(240) NOT NULL,
    parent_trace_id character varying(240) NOT NULL,
    parent_span_id character varying(240) NOT NULL,
    parent_root_input_id character varying(240) NOT NULL,
    parent_caused_by_event_id character varying(240),
    parent_causation_identity_ref character varying(240) NOT NULL,
    wait_id character varying(240) NOT NULL,
    delivered boolean NOT NULL,
    revision bigint NOT NULL,
    snapshot jsonb NOT NULL,
    recovery_input_id character varying(240),
    recovery_data jsonb,
    parent_requester_kind text NOT NULL,
    parent_requester_end_user_id bigint,
    parent_requester_system_principal_id text,
    parent_source_session_id text,
    parent_source_message_id text,
    parent_origin_ref text NOT NULL,
    CONSTRAINT agent_runtime_delegation_waits_check CHECK (((btrim((tenant_id)::text) <> ''::text) AND (btrim((parent_session_id)::text) <> ''::text) AND (btrim((parent_target_id)::text) <> ''::text) AND (btrim((parent_agent_id)::text) <> ''::text) AND (btrim((parent_causation_input_id)::text) <> ''::text) AND (btrim((parent_run_id)::text) <> ''::text) AND (btrim((parent_trace_id)::text) <> ''::text) AND (btrim((parent_span_id)::text) <> ''::text) AND (btrim((parent_root_input_id)::text) <> ''::text) AND (btrim((parent_causation_identity_ref)::text) <> ''::text) AND (btrim((wait_id)::text) <> ''::text))),
    CONSTRAINT agent_runtime_delegation_waits_check1 CHECK ((((NOT delivered) AND (revision = 1)) OR (delivered AND (revision = 2)))),
    CONSTRAINT agent_runtime_delegation_waits_check2 CHECK (((recovery_input_id IS NULL) = (recovery_data IS NULL))),
    CONSTRAINT agent_runtime_delegation_waits_check3 CHECK (((snapshot ? 'id'::text) AND ((snapshot ->> 'id'::text) = (wait_id)::text) AND (snapshot ? 'delivered'::text) AND (((snapshot ->> 'delivered'::text))::boolean = delivered) AND (snapshot ? 'revision'::text) AND (((snapshot ->> 'revision'::text))::bigint = revision))),
    CONSTRAINT agent_runtime_delegation_waits_origin_ck CHECK ((btrim(parent_origin_ref) <> ''::text)),
    CONSTRAINT agent_runtime_delegation_waits_parent_caused_by_event_id_check CHECK (((parent_caused_by_event_id IS NULL) OR (btrim((parent_caused_by_event_id)::text) <> ''::text))),
    CONSTRAINT agent_runtime_delegation_waits_parent_end_user_id_check CHECK ((parent_end_user_id > 0)),
    CONSTRAINT agent_runtime_delegation_waits_recovery_data_check CHECK (((recovery_data IS NULL) OR (jsonb_typeof(recovery_data) = 'object'::text))),
    CONSTRAINT agent_runtime_delegation_waits_recovery_input_id_check CHECK (((recovery_input_id IS NULL) OR (btrim((recovery_input_id)::text) <> ''::text))),
    CONSTRAINT agent_runtime_delegation_waits_requester_ck CHECK ((((parent_requester_kind = 'END_USER'::text) AND (parent_requester_end_user_id IS NOT NULL) AND (parent_requester_end_user_id > 0) AND (parent_requester_system_principal_id IS NULL)) OR ((parent_requester_kind = 'SYSTEM'::text) AND (parent_requester_end_user_id IS NULL) AND (parent_requester_system_principal_id IS NOT NULL) AND (btrim(parent_requester_system_principal_id) <> ''::text)))),
    CONSTRAINT agent_runtime_delegation_waits_revision_check CHECK ((revision > 0)),
    CONSTRAINT agent_runtime_delegation_waits_snapshot_check CHECK ((jsonb_typeof(snapshot) = 'object'::text)),
    CONSTRAINT agent_runtime_delegation_waits_source_ck CHECK ((((parent_source_session_id IS NULL) AND (parent_source_message_id IS NULL)) OR ((parent_source_session_id IS NOT NULL) AND (btrim(parent_source_session_id) <> ''::text) AND (parent_source_message_id IS NOT NULL) AND (btrim(parent_source_message_id) <> ''::text)))),
    CONSTRAINT agent_runtime_delegation_waits_subject_ck CHECK (((((parent_target_kind)::text = ANY ((ARRAY['AGENT'::character varying, 'TEAM'::character varying])::text[])) AND (parent_end_user_id IS NOT NULL) AND (parent_end_user_id > 0)) OR (((parent_target_kind)::text = 'GROUP'::text) AND (parent_end_user_id IS NULL))))
);

ALTER TABLE ONLY public.agent_runtime_delegation_waits FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_delegations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_delegations (
    tenant_id character varying(160) NOT NULL,
    parent_session_id character varying(240) NOT NULL,
    parent_target_kind character varying(10) NOT NULL,
    parent_target_id character varying(240) NOT NULL,
    parent_end_user_id bigint,
    parent_agent_id character varying(240) NOT NULL,
    parent_causation_input_id character varying(240) NOT NULL,
    parent_run_id character varying(240) NOT NULL,
    parent_trace_id character varying(240) NOT NULL,
    parent_span_id character varying(240) NOT NULL,
    parent_root_input_id character varying(240) NOT NULL,
    parent_caused_by_event_id character varying(240),
    parent_causation_identity_ref character varying(240) NOT NULL,
    delegation_id character varying(240) NOT NULL,
    operation_key character varying(240) NOT NULL,
    child_run_id character varying(240) NOT NULL,
    child_input_id character varying(240) NOT NULL,
    previous_delegation_id character varying(240),
    state character varying(20) NOT NULL,
    cancel_key character varying(240),
    revision bigint NOT NULL,
    snapshot jsonb NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    updated_epoch_second bigint NOT NULL,
    updated_nano integer NOT NULL,
    parent_requester_kind text NOT NULL,
    parent_requester_end_user_id bigint,
    parent_requester_system_principal_id text,
    parent_source_session_id text,
    parent_source_message_id text,
    parent_origin_ref text NOT NULL,
    CONSTRAINT agent_runtime_delegations_cancel_key_check CHECK (((cancel_key IS NULL) OR (btrim((cancel_key)::text) <> ''::text))),
    CONSTRAINT agent_runtime_delegations_check CHECK (((btrim((tenant_id)::text) <> ''::text) AND (btrim((parent_session_id)::text) <> ''::text) AND (btrim((parent_target_id)::text) <> ''::text) AND (btrim((parent_agent_id)::text) <> ''::text) AND (btrim((parent_causation_input_id)::text) <> ''::text) AND (btrim((parent_run_id)::text) <> ''::text) AND (btrim((parent_trace_id)::text) <> ''::text) AND (btrim((parent_span_id)::text) <> ''::text) AND (btrim((parent_root_input_id)::text) <> ''::text) AND (btrim((parent_causation_identity_ref)::text) <> ''::text) AND (btrim((delegation_id)::text) <> ''::text) AND (btrim((operation_key)::text) <> ''::text) AND (btrim((child_run_id)::text) <> ''::text) AND (btrim((child_input_id)::text) <> ''::text))),
    CONSTRAINT agent_runtime_delegations_check1 CHECK ((ROW(updated_epoch_second, updated_nano) >= ROW(created_epoch_second, created_nano))),
    CONSTRAINT agent_runtime_delegations_check2 CHECK (((snapshot ? 'id'::text) AND ((snapshot ->> 'id'::text) = (delegation_id)::text) AND (snapshot ? 'operationKey'::text) AND ((snapshot ->> 'operationKey'::text) = (operation_key)::text) AND (snapshot ? 'state'::text) AND ((snapshot ->> 'state'::text) = (state)::text) AND (snapshot ? 'revision'::text) AND (((snapshot ->> 'revision'::text))::bigint = revision))),
    CONSTRAINT agent_runtime_delegations_created_nano_check CHECK (((created_nano >= 0) AND (created_nano <= 999999999))),
    CONSTRAINT agent_runtime_delegations_origin_ck CHECK ((btrim(parent_origin_ref) <> ''::text)),
    CONSTRAINT agent_runtime_delegations_parent_caused_by_event_id_check CHECK (((parent_caused_by_event_id IS NULL) OR (btrim((parent_caused_by_event_id)::text) <> ''::text))),
    CONSTRAINT agent_runtime_delegations_parent_end_user_id_check CHECK ((parent_end_user_id > 0)),
    CONSTRAINT agent_runtime_delegations_previous_delegation_id_check CHECK (((previous_delegation_id IS NULL) OR (btrim((previous_delegation_id)::text) <> ''::text))),
    CONSTRAINT agent_runtime_delegations_requester_ck CHECK ((((parent_requester_kind = 'END_USER'::text) AND (parent_requester_end_user_id IS NOT NULL) AND (parent_requester_end_user_id > 0) AND (parent_requester_system_principal_id IS NULL)) OR ((parent_requester_kind = 'SYSTEM'::text) AND (parent_requester_end_user_id IS NULL) AND (parent_requester_system_principal_id IS NOT NULL) AND (btrim(parent_requester_system_principal_id) <> ''::text)))),
    CONSTRAINT agent_runtime_delegations_revision_check CHECK ((revision > 0)),
    CONSTRAINT agent_runtime_delegations_snapshot_check CHECK ((jsonb_typeof(snapshot) = 'object'::text)),
    CONSTRAINT agent_runtime_delegations_source_ck CHECK ((((parent_source_session_id IS NULL) AND (parent_source_message_id IS NULL)) OR ((parent_source_session_id IS NOT NULL) AND (btrim(parent_source_session_id) <> ''::text) AND (parent_source_message_id IS NOT NULL) AND (btrim(parent_source_message_id) <> ''::text)))),
    CONSTRAINT agent_runtime_delegations_state_check CHECK (((state)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'WAITING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT agent_runtime_delegations_subject_ck CHECK (((((parent_target_kind)::text = ANY ((ARRAY['AGENT'::character varying, 'TEAM'::character varying])::text[])) AND (parent_end_user_id IS NOT NULL) AND (parent_end_user_id > 0)) OR (((parent_target_kind)::text = 'GROUP'::text) AND (parent_end_user_id IS NULL)))),
    CONSTRAINT agent_runtime_delegations_updated_nano_check CHECK (((updated_nano >= 0) AND (updated_nano <= 999999999)))
);

ALTER TABLE ONLY public.agent_runtime_delegations FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_event_conflict_audits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_event_conflict_audits (
    tenant_id text NOT NULL,
    event_id text NOT NULL,
    destination_id text NOT NULL,
    command_digest text NOT NULL,
    audit_ref text NOT NULL,
    CONSTRAINT agent_runtime_event_conflict_audits_audit_ref_check CHECK ((btrim(audit_ref) <> ''::text)),
    CONSTRAINT agent_runtime_event_conflict_audits_command_digest_check CHECK ((command_digest ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT agent_runtime_event_conflict_audits_destination_id_check CHECK ((btrim(destination_id) <> ''::text)),
    CONSTRAINT agent_runtime_event_conflict_audits_event_id_check CHECK ((btrim(event_id) <> ''::text)),
    CONSTRAINT agent_runtime_event_conflict_audits_tenant_id_check CHECK ((btrim(tenant_id) <> ''::text))
);

ALTER TABLE ONLY public.agent_runtime_event_conflict_audits FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_event_deliveries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_event_deliveries (
    tenant_id text NOT NULL,
    event_id text NOT NULL,
    destination_id text NOT NULL,
    idempotency_key text NOT NULL,
    submission_data text NOT NULL,
    state text NOT NULL,
    revision bigint NOT NULL,
    attempt integer NOT NULL,
    available_second bigint NOT NULL,
    available_nano integer NOT NULL,
    lease_claim text,
    lease_worker text,
    lease_token bigint,
    lease_second bigint,
    lease_nano integer,
    failure_code text,
    failure_retry text,
    failure_ref text,
    updated_second bigint NOT NULL,
    updated_nano integer NOT NULL,
    CONSTRAINT agent_runtime_event_deliveries_attempt_check CHECK ((attempt >= 0)),
    CONSTRAINT agent_runtime_event_deliveries_available_nano_check CHECK (((available_nano >= 0) AND (available_nano <= 999999999))),
    CONSTRAINT agent_runtime_event_deliveries_destination_id_check CHECK ((btrim(destination_id) <> ''::text)),
    CONSTRAINT agent_runtime_event_deliveries_event_id_check CHECK ((btrim(event_id) <> ''::text)),
    CONSTRAINT agent_runtime_event_deliveries_idempotency_key_check CHECK ((btrim(idempotency_key) <> ''::text)),
    CONSTRAINT agent_runtime_event_deliveries_revision_check CHECK ((revision > 0)),
    CONSTRAINT agent_runtime_event_deliveries_state_check CHECK ((state = ANY (ARRAY['PENDING'::text, 'DELIVERING'::text, 'DELIVERED'::text, 'DEAD_LETTER'::text]))),
    CONSTRAINT agent_runtime_event_deliveries_submission_data_check CHECK ((submission_data <> ''::text)),
    CONSTRAINT agent_runtime_event_deliveries_tenant_id_check CHECK ((btrim(tenant_id) <> ''::text)),
    CONSTRAINT agent_runtime_event_deliveries_updated_nano_check CHECK (((updated_nano >= 0) AND (updated_nano <= 999999999))),
    CONSTRAINT agent_runtime_event_delivery_failure CHECK ((((failure_code IS NULL) AND (failure_retry IS NULL) AND (failure_ref IS NULL)) OR ((failure_code IS NOT NULL) AND (btrim(failure_code) <> ''::text) AND (failure_ref IS NOT NULL) AND (btrim(failure_ref) <> ''::text) AND (failure_retry IS NOT NULL) AND (failure_retry = ANY (ARRAY['NONE'::text, 'IDEMPOTENT_BY_STABLE_KEY'::text]))))),
    CONSTRAINT agent_runtime_event_delivery_lease CHECK ((((state = 'DELIVERING'::text) AND (lease_claim IS NOT NULL) AND (btrim(lease_claim) <> ''::text) AND (lease_worker IS NOT NULL) AND (btrim(lease_worker) <> ''::text) AND (lease_token IS NOT NULL) AND (lease_token > 0) AND (lease_second IS NOT NULL) AND (lease_nano IS NOT NULL) AND ((lease_nano >= 0) AND (lease_nano <= 999999999))) OR ((state <> 'DELIVERING'::text) AND (lease_claim IS NULL) AND (lease_worker IS NULL) AND (lease_token IS NULL) AND (lease_second IS NULL) AND (lease_nano IS NULL)))),
    CONSTRAINT agent_runtime_event_delivery_matrix CHECK ((((attempt = 0) AND (state = 'PENDING'::text) AND (failure_code IS NULL)) OR ((attempt > 0) AND (((state = 'PENDING'::text) AND (failure_retry IS NOT NULL) AND (failure_retry = 'IDEMPOTENT_BY_STABLE_KEY'::text)) OR ((state = 'DELIVERING'::text) AND ((failure_code IS NULL) OR (failure_retry = 'IDEMPOTENT_BY_STABLE_KEY'::text))) OR ((state = 'DELIVERED'::text) AND (failure_code IS NULL)) OR ((state = 'DEAD_LETTER'::text) AND (failure_retry IS NOT NULL) AND (failure_retry = 'NONE'::text))))))
);

ALTER TABLE ONLY public.agent_runtime_event_deliveries FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_execution_actions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_execution_actions (
    tenant_id text NOT NULL,
    action_id character varying(160) NOT NULL,
    run_id character varying(160) NOT NULL,
    action_ordinal bigint NOT NULL,
    kind character varying(32) NOT NULL,
    target_handle text NOT NULL,
    capability_ref text NOT NULL,
    request_ref text NOT NULL,
    fingerprint text NOT NULL,
    status character varying(32) NOT NULL,
    current_attempt text NOT NULL,
    selected_attempt text,
    batch_source_attempt text,
    batch_size integer,
    batch_slot integer,
    CONSTRAINT execution_actions_batch_ck CHECK ((((batch_source_attempt IS NULL) AND (batch_size IS NULL) AND (batch_slot IS NULL)) OR ((batch_source_attempt IS NOT NULL) AND (btrim(batch_source_attempt) <> ''::text) AND (batch_size IS NOT NULL) AND (batch_size >= 2) AND (batch_slot IS NOT NULL) AND ((batch_slot >= 1) AND (batch_slot <= batch_size)) AND ((kind)::text = 'TOOL'::text)))),
    CONSTRAINT execution_actions_identity_ck CHECK (((btrim((action_id)::text) <> ''::text) AND (action_ordinal > 0) AND (btrim(target_handle) <> ''::text) AND (btrim(capability_ref) <> ''::text) AND (btrim(fingerprint) <> ''::text))),
    CONSTRAINT execution_actions_kind_ck CHECK (((kind)::text = ANY ((ARRAY['MODEL'::character varying, 'TOOL'::character varying, 'KNOWLEDGE'::character varying, 'WORKFLOW'::character varying, 'DELEGATION'::character varying])::text[]))),
    CONSTRAINT execution_actions_selected_ck CHECK (((((status)::text <> ALL ((ARRAY['DISPATCHED'::character varying, 'WAITING_INPUT'::character varying])::text[])) OR (selected_attempt IS NULL)) AND (((status)::text <> ALL ((ARRAY['SUCCEEDED'::character varying, 'FAILED'::character varying])::text[])) OR (selected_attempt IS NOT NULL)))),
    CONSTRAINT execution_actions_status_ck CHECK (((status)::text = ANY ((ARRAY['DISPATCHED'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying, 'UNKNOWN'::character varying, 'WAITING_INPUT'::character varying])::text[])))
);

ALTER TABLE ONLY public.agent_runtime_execution_actions FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_execution_attempts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_execution_attempts (
    tenant_id text NOT NULL,
    attempt_id text NOT NULL,
    action_id character varying(160) NOT NULL,
    run_id character varying(160) NOT NULL,
    fence bigint NOT NULL,
    request_ref text NOT NULL,
    fingerprint text NOT NULL,
    continuation_generation bigint,
    result_kind character varying(32),
    result_ref text,
    uncertainty_ref text,
    feedback_ref text,
    external_operation text,
    adopted boolean DEFAULT false NOT NULL,
    observation_sequence bigint NOT NULL,
    CONSTRAINT execution_attempts_identity_ck CHECK (((btrim(attempt_id) <> ''::text) AND (fence > 0) AND (btrim(fingerprint) <> ''::text) AND ((continuation_generation IS NULL) OR (continuation_generation > 0)))),
    CONSTRAINT execution_attempts_result_ck CHECK ((((result_kind IS NULL) = (result_ref IS NULL)) AND ((result_kind IS NULL) OR ((result_kind)::text = ANY ((ARRAY['SUCCEEDED'::character varying, 'FAILED'::character varying, 'UNKNOWN'::character varying, 'INPUT_REQUIRED'::character varying])::text[]))) AND ((NOT adopted) OR (result_ref IS NOT NULL)) AND ((feedback_ref IS NULL) OR (result_ref IS NOT NULL)) AND ((uncertainty_ref IS NULL) OR (result_ref IS NOT NULL))))
);

ALTER TABLE ONLY public.agent_runtime_execution_attempts FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_execution_attempts_observation_sequence_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_execution_attempts ALTER COLUMN observation_sequence ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.agent_runtime_execution_attempts_observation_sequence_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);



--
-- Name: agent_runtime_execution_batch_members; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_execution_batch_members (
    tenant_id text NOT NULL,
    run_id character varying(160) NOT NULL,
    source_attempt text NOT NULL,
    slot integer NOT NULL,
    size integer NOT NULL,
    action_id character varying(160) NOT NULL,
    first_attempt_id text NOT NULL,
    reused boolean NOT NULL,
    reused_result_ref text,
    reused_feedback_ref text,
    CONSTRAINT execution_batch_members_reused_ck CHECK (((reused AND (reused_result_ref IS NOT NULL) AND (reused_feedback_ref IS NOT NULL)) OR ((NOT reused) AND (reused_result_ref IS NULL) AND (reused_feedback_ref IS NULL)))),
    CONSTRAINT execution_batch_members_slot_ck CHECK ((((size >= 2) AND (size <= 8)) AND ((slot >= 1) AND (slot <= size))))
);

ALTER TABLE ONLY public.agent_runtime_execution_batch_members FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_execution_child_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_execution_child_runs (
    tenant_id text NOT NULL,
    parent_run_id character varying(160) NOT NULL,
    parent_action_id character varying(160) NOT NULL,
    parent_attempt_id text NOT NULL,
    child_run_id character varying(160) NOT NULL,
    origin_kind character varying(32) NOT NULL,
    delegation_id text,
    execution_id text,
    node_id text,
    node_visit integer,
    node_call integer,
    checkpoint_fingerprint text,
    source_revision bigint,
    source_fence bigint,
    CONSTRAINT execution_child_runs_distinct_ck CHECK (((parent_run_id)::text <> (child_run_id)::text)),
    CONSTRAINT execution_child_runs_origin_ck CHECK (((((origin_kind)::text = 'DELEGATION'::text) AND (delegation_id IS NOT NULL) AND (btrim(delegation_id) <> ''::text) AND (execution_id IS NULL) AND (node_id IS NULL) AND (node_visit IS NULL) AND (node_call IS NULL) AND (checkpoint_fingerprint IS NULL) AND (source_revision IS NULL) AND (source_fence IS NULL)) OR (((origin_kind)::text = 'WORKFLOW_NODE'::text) AND (delegation_id IS NULL) AND (execution_id IS NOT NULL) AND (btrim(execution_id) <> ''::text) AND (node_id IS NOT NULL) AND (btrim(node_id) <> ''::text) AND (node_visit IS NOT NULL) AND (node_visit > 0) AND (node_call IS NOT NULL) AND (node_call > 0) AND (checkpoint_fingerprint IS NOT NULL) AND (btrim(checkpoint_fingerprint) <> ''::text) AND (source_revision IS NOT NULL) AND (source_revision > 0) AND (source_fence IS NOT NULL) AND (source_fence > 0))))
);

ALTER TABLE ONLY public.agent_runtime_execution_child_runs FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_execution_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_execution_events (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    run_id character varying(160) NOT NULL,
    event_sequence bigint NOT NULL,
    revision bigint NOT NULL,
    event_id text NOT NULL,
    type text NOT NULL,
    schema_id text NOT NULL,
    canonical_json text NOT NULL,
    occurred_second bigint NOT NULL,
    occurred_nano integer NOT NULL,
    CONSTRAINT execution_events_content_ck CHECK (((event_sequence > 0) AND (revision > 0) AND (btrim(event_id) <> ''::text) AND (btrim(type) <> ''::text) AND (btrim(schema_id) <> ''::text) AND (btrim(canonical_json) <> ''::text) AND ((occurred_nano >= 0) AND (occurred_nano <= 999999999))))
);

ALTER TABLE ONLY public.agent_runtime_execution_events FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_execution_fencing_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.agent_runtime_execution_fencing_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;



--
-- Name: agent_runtime_execution_materials; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_execution_materials (
    tenant_id text NOT NULL,
    reference text NOT NULL,
    schema_id text NOT NULL,
    canonical_json text NOT NULL,
    CONSTRAINT execution_materials_content_ck CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(reference) <> ''::text) AND (btrim(schema_id) <> ''::text) AND (btrim(canonical_json) <> ''::text)))
);

ALTER TABLE ONLY public.agent_runtime_execution_materials FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_execution_mutations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_execution_mutations (
    tenant_id text NOT NULL,
    execution_id character varying(160) NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint,
    run_id character varying(160) NOT NULL,
    mutation_key text NOT NULL,
    mutation_kind character varying(16) NOT NULL,
    expected_revision bigint NOT NULL,
    expected_state character varying(24),
    command_fingerprint character varying(64) NOT NULL,
    command_data text NOT NULL,
    output_revision bigint NOT NULL,
    output_state character varying(24) NOT NULL,
    output_fingerprint character varying(64) NOT NULL,
    output_data text NOT NULL,
    intermediate_revision bigint,
    intermediate_state character varying(24),
    intermediate_fingerprint character varying(64),
    intermediate_data text,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    subject_kind text GENERATED ALWAYS AS (
CASE
    WHEN (target_kind = 'GROUP'::text) THEN 'GROUP'::text
    ELSE 'USER'::text
END) STORED NOT NULL,
    subject_id text GENERATED ALWAYS AS (
CASE
    WHEN (target_kind = 'GROUP'::text) THEN target_id
    ELSE (end_user_id)::text
END) STORED NOT NULL,
    CONSTRAINT agent_runtime_execution_mutations_subject_ck CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(target_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (btrim((run_id)::text) <> ''::text) AND (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (end_user_id IS NOT NULL) AND (end_user_id > 0)) OR ((target_kind = 'GROUP'::text) AND (end_user_id IS NULL))))),
    CONSTRAINT ck_agent_runtime_execution_mutation_codec CHECK ((((command_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND ((output_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (btrim(command_data) <> ''::text) AND (btrim(output_data) <> ''::text))),
    CONSTRAINT ck_agent_runtime_execution_mutation_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim((execution_id)::text) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (end_user_id > 0) AND (btrim((run_id)::text) <> ''::text) AND (btrim(mutation_key) <> ''::text))),
    CONSTRAINT ck_agent_runtime_execution_mutation_intermediate CHECK (((((mutation_kind)::text = 'RESUME'::text) AND (intermediate_revision IS NOT NULL) AND (intermediate_state IS NOT NULL) AND (intermediate_fingerprint IS NOT NULL) AND (intermediate_data IS NOT NULL) AND (intermediate_revision = (expected_revision + 1)) AND ((intermediate_state)::text = 'RESUMING'::text) AND ((intermediate_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (btrim(intermediate_data) <> ''::text)) OR (((mutation_kind)::text <> 'RESUME'::text) AND (intermediate_revision IS NULL) AND (intermediate_state IS NULL) AND (intermediate_fingerprint IS NULL) AND (intermediate_data IS NULL)))),
    CONSTRAINT ck_agent_runtime_execution_mutation_kind CHECK (((mutation_kind)::text = ANY ((ARRAY['CREATE'::character varying, 'RELEASE'::character varying, 'CANCEL'::character varying, 'PROGRESS'::character varying, 'TRANSITION'::character varying, 'RESUME'::character varying])::text[]))),
    CONSTRAINT ck_agent_runtime_execution_mutation_revision CHECK (((((mutation_kind)::text = 'CREATE'::text) AND (expected_revision = 0) AND (expected_state IS NULL) AND (output_revision = 1) AND ((output_state)::text = 'QUEUED'::text)) OR (((mutation_kind)::text = 'RELEASE'::text) AND (expected_revision > 0) AND (expected_state IS NULL) AND (output_revision >= expected_revision)) OR (((mutation_kind)::text = 'CANCEL'::text) AND (expected_revision > 0) AND (expected_state IS NOT NULL) AND ((output_state)::text = (expected_state)::text) AND (output_revision = (expected_revision + 1))) OR (((mutation_kind)::text = 'PROGRESS'::text) AND (expected_revision > 0) AND (expected_state IS NOT NULL) AND ((expected_state)::text = 'RUNNING'::text) AND ((output_state)::text = 'RUNNING'::text) AND (output_revision = (expected_revision + 1))) OR (((mutation_kind)::text = 'TRANSITION'::text) AND (expected_revision > 0) AND (expected_state IS NOT NULL) AND (output_revision = (expected_revision + 1))) OR (((mutation_kind)::text = 'RESUME'::text) AND (expected_revision > 0) AND (expected_state IS NOT NULL) AND ((expected_state)::text = ANY ((ARRAY['WAITING_HITL'::character varying, 'WAITING_EXTERNAL'::character varying])::text[])) AND (output_revision = (expected_revision + 2)) AND ((output_state)::text = 'RUNNING'::text)))),
    CONSTRAINT ck_agent_runtime_execution_mutation_state CHECK ((((output_state)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'WAITING_HITL'::character varying, 'WAITING_EXTERNAL'::character varying, 'RESUMING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'LOOP_TERMINATED'::character varying, 'CANCELLED'::character varying])::text[])) AND ((expected_state IS NULL) OR ((expected_state)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'WAITING_HITL'::character varying, 'WAITING_EXTERNAL'::character varying, 'RESUMING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'LOOP_TERMINATED'::character varying, 'CANCELLED'::character varying])::text[])))))
);

ALTER TABLE ONLY public.agent_runtime_execution_mutations FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_execution_plan_child_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_execution_plan_child_runs (
    tenant_id text NOT NULL,
    parent_run_id character varying(160) NOT NULL,
    child_run_id character varying(160) NOT NULL,
    work_session_id text NOT NULL,
    root_input_id text NOT NULL,
    task_id text NOT NULL,
    admission_id text NOT NULL,
    plan_revision bigint NOT NULL,
    permit_fence bigint NOT NULL,
    CONSTRAINT execution_plan_child_runs_identity_ck CHECK ((((parent_run_id)::text <> (child_run_id)::text) AND (btrim(work_session_id) <> ''::text) AND (btrim(root_input_id) <> ''::text) AND (btrim(task_id) <> ''::text) AND (btrim(admission_id) <> ''::text) AND (plan_revision > 0) AND (permit_fence > 0)))
);

ALTER TABLE ONLY public.agent_runtime_execution_plan_child_runs FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_execution_plan_controls; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_execution_plan_controls (
    tenant_id text NOT NULL,
    run_id character varying(160) NOT NULL,
    source_action_id character varying(160) NOT NULL,
    source_attempt_id text NOT NULL,
    request_ref text NOT NULL,
    result_ref text NOT NULL,
    adopted_revision bigint NOT NULL,
    CONSTRAINT execution_plan_controls_revision_ck CHECK ((adopted_revision > 0))
);

ALTER TABLE ONLY public.agent_runtime_execution_plan_controls FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_execution_program_child_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_execution_program_child_runs (
    tenant_id text NOT NULL,
    parent_run_id character varying(160) NOT NULL,
    parent_action_id character varying(160) NOT NULL,
    parent_attempt_id text NOT NULL,
    ordinal integer NOT NULL,
    child_run_id character varying(160) NOT NULL,
    CONSTRAINT execution_program_child_runs_ordinal_ck CHECK (((ordinal >= 1) AND (ordinal <= 1024)))
);

ALTER TABLE ONLY public.agent_runtime_execution_program_child_runs FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_execution_publication_mutations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_execution_publication_mutations (
    tenant_id text NOT NULL,
    source_id character varying(160) NOT NULL,
    event_id character varying(160) NOT NULL,
    mutation_key text NOT NULL,
    mutation_kind character varying(16) NOT NULL,
    command_fingerprint character varying(64) NOT NULL,
    command_data text NOT NULL,
    output_revision bigint NOT NULL,
    output_status character varying(16) NOT NULL,
    output_fingerprint character varying(64) NOT NULL,
    output_data text NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT ck_agent_runtime_execution_publication_mutation_codec CHECK ((((command_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND ((output_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (btrim(command_data) <> ''::text) AND (btrim(output_data) <> ''::text))),
    CONSTRAINT ck_agent_runtime_execution_publication_mutation_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim((source_id)::text) <> ''::text) AND (btrim((event_id)::text) <> ''::text) AND (btrim(mutation_key) <> ''::text))),
    CONSTRAINT ck_agent_runtime_execution_publication_mutation_kind CHECK (((mutation_kind)::text = ANY ((ARRAY['ACKNOWLEDGE'::character varying, 'RETRY'::character varying])::text[]))),
    CONSTRAINT ck_agent_runtime_execution_publication_mutation_output CHECK (((output_revision > 1) AND ((((mutation_kind)::text = 'ACKNOWLEDGE'::text) AND ((output_status)::text = 'ACKNOWLEDGED'::text)) OR (((mutation_kind)::text = 'RETRY'::text) AND ((output_status)::text = 'PENDING'::text)))))
);

ALTER TABLE ONLY public.agent_runtime_execution_publication_mutations FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_execution_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_execution_runs (
    tenant_id text NOT NULL,
    run_id character varying(160) NOT NULL,
    session_id text NOT NULL,
    target_kind character varying(32) NOT NULL,
    target_id text NOT NULL,
    end_user_id bigint,
    agent_id text NOT NULL,
    input_id character varying(160) NOT NULL,
    trace_id text NOT NULL,
    span_id text NOT NULL,
    root_input_id text NOT NULL,
    caused_by_event_id text,
    identity_ref text NOT NULL,
    actor_verification_ref text NOT NULL,
    configuration_ref text NOT NULL,
    input_ref text NOT NULL,
    initial_context_ref text NOT NULL,
    admission_fingerprint text NOT NULL,
    status character varying(32) NOT NULL,
    phase character varying(32) NOT NULL,
    revision bigint NOT NULL,
    fence bigint DEFAULT 0 NOT NULL,
    cancelled boolean DEFAULT false NOT NULL,
    decisions bigint DEFAULT 0 NOT NULL,
    tool_calls bigint DEFAULT 0 NOT NULL,
    knowledge_calls bigint DEFAULT 0 NOT NULL,
    context_ref text NOT NULL,
    current_action_id character varying(160),
    waiting_action_id character varying(160),
    waiting_generation bigint,
    batch_source_attempt text,
    batch_size integer,
    lease_worker_id text,
    lease_claim_id text,
    lease_until_second bigint,
    lease_until_nano integer,
    next_due_second bigint,
    next_due_nano integer,
    created_second bigint NOT NULL,
    created_nano integer NOT NULL,
    updated_second bigint NOT NULL,
    updated_nano integer NOT NULL,
    subject_kind text GENERATED ALWAYS AS (
CASE
    WHEN ((target_kind)::text = 'GROUP'::text) THEN 'GROUP'::text
    ELSE 'USER'::text
END) STORED NOT NULL,
    subject_id text GENERATED ALWAYS AS (
CASE
    WHEN ((target_kind)::text = 'GROUP'::text) THEN target_id
    ELSE (end_user_id)::text
END) STORED NOT NULL,
    requester_kind text NOT NULL,
    requester_end_user_id bigint,
    requester_system_principal_id text,
    source_session_id text,
    source_message_id text,
    origin_ref text NOT NULL,
    CONSTRAINT execution_runs_action_phase_ck CHECK (((((phase)::text <> 'READY'::text) OR (current_action_id IS NULL) OR (waiting_action_id IS NOT NULL)) AND (((phase)::text <> ALL ((ARRAY['AWAITING_RESULT'::character varying, 'WAITING'::character varying, 'UNCERTAIN'::character varying])::text[])) OR (current_action_id IS NOT NULL) OR (batch_source_attempt IS NOT NULL)))),
    CONSTRAINT execution_runs_batch_ck CHECK ((((batch_source_attempt IS NULL) = (batch_size IS NULL)) AND ((batch_source_attempt IS NULL) OR ((btrim(batch_source_attempt) <> ''::text) AND (batch_size >= 2) AND (current_action_id IS NULL))))),
    CONSTRAINT execution_runs_counts_ck CHECK (((revision > 0) AND (fence >= 0) AND (decisions >= 0) AND (tool_calls >= 0) AND (knowledge_calls >= 0))),
    CONSTRAINT execution_runs_due_ck CHECK ((((next_due_second IS NULL) AND (next_due_nano IS NULL)) OR ((next_due_second IS NOT NULL) AND (next_due_nano IS NOT NULL) AND ((next_due_nano >= 0) AND (next_due_nano <= 999999999))))),
    CONSTRAINT execution_runs_identity_ck CHECK (((btrim((run_id)::text) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (btrim((input_id)::text) <> ''::text) AND (btrim(trace_id) <> ''::text) AND (btrim(span_id) <> ''::text) AND (btrim(root_input_id) <> ''::text) AND (btrim(identity_ref) <> ''::text) AND (btrim(actor_verification_ref) <> ''::text) AND (btrim(configuration_ref) <> ''::text) AND (btrim(admission_fingerprint) <> ''::text))),
    CONSTRAINT execution_runs_lease_ck CHECK ((((lease_claim_id IS NULL) AND (lease_worker_id IS NULL) AND (lease_until_second IS NULL) AND (lease_until_nano IS NULL)) OR ((lease_claim_id IS NOT NULL) AND (btrim(lease_claim_id) <> ''::text) AND (lease_worker_id IS NOT NULL) AND (btrim(lease_worker_id) <> ''::text) AND (fence > 0) AND (lease_until_second IS NOT NULL) AND (lease_until_nano IS NOT NULL) AND ((lease_until_nano >= 0) AND (lease_until_nano <= 999999999))))),
    CONSTRAINT execution_runs_origin_ck CHECK ((btrim(origin_ref) <> ''::text)),
    CONSTRAINT execution_runs_phase_ck CHECK ((((phase)::text = ANY ((ARRAY['READY'::character varying, 'AWAITING_RESULT'::character varying, 'WAITING'::character varying, 'UNCERTAIN'::character varying, 'FINISHED'::character varying])::text[])) AND (((phase)::text = 'FINISHED'::text) = ((status)::text = ANY ((ARRAY['COMPLETED'::character varying, 'TERMINATED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))))),
    CONSTRAINT execution_runs_requester_ck CHECK ((((requester_kind = 'END_USER'::text) AND (requester_end_user_id IS NOT NULL) AND (requester_end_user_id > 0) AND (requester_system_principal_id IS NULL) AND ((subject_kind = 'GROUP'::text) OR (requester_end_user_id = end_user_id))) OR ((requester_kind = 'SYSTEM'::text) AND (requester_end_user_id IS NULL) AND (requester_system_principal_id IS NOT NULL) AND (btrim(requester_system_principal_id) <> ''::text)))),
    CONSTRAINT execution_runs_source_ck CHECK ((((source_session_id IS NULL) AND (source_message_id IS NULL)) OR ((source_session_id IS NOT NULL) AND (btrim(source_session_id) <> ''::text) AND (source_message_id IS NOT NULL) AND (btrim(source_message_id) <> ''::text) AND ((subject_kind = 'GROUP'::text) OR (source_session_id = session_id))))),
    CONSTRAINT execution_runs_status_ck CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'SUSPENDED'::character varying, 'RESUMING'::character varying, 'COMPLETED'::character varying, 'TERMINATED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT execution_runs_subject_ck CHECK (((((target_kind)::text = ANY ((ARRAY['AGENT'::character varying, 'TEAM'::character varying])::text[])) AND (end_user_id IS NOT NULL) AND (end_user_id > 0)) OR (((target_kind)::text = 'GROUP'::text) AND (end_user_id IS NULL)))),
    CONSTRAINT execution_runs_terminal_lease_ck CHECK ((((phase)::text <> ALL ((ARRAY['FINISHED'::character varying, 'WAITING'::character varying, 'UNCERTAIN'::character varying])::text[])) OR ((lease_claim_id IS NULL) AND (next_due_second IS NULL)))),
    CONSTRAINT execution_runs_time_ck CHECK ((((created_nano >= 0) AND (created_nano <= 999999999)) AND ((updated_nano >= 0) AND (updated_nano <= 999999999)) AND (ROW(updated_second, updated_nano) >= ROW(created_second, created_nano)))),
    CONSTRAINT execution_runs_wait_ck CHECK ((((waiting_action_id IS NULL) = (waiting_generation IS NULL)) AND ((waiting_generation IS NULL) OR ((waiting_generation > 0) AND ((current_action_id)::text = (waiting_action_id)::text))) AND (((phase)::text <> 'WAITING'::text) OR (waiting_action_id IS NOT NULL) OR (batch_source_attempt IS NOT NULL))))
);

ALTER TABLE ONLY public.agent_runtime_execution_runs FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_execution_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_execution_sessions (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    target_kind character varying(32) NOT NULL,
    target_id text NOT NULL,
    end_user_id bigint,
    last_event_sequence bigint DEFAULT 0 NOT NULL,
    subject_kind text GENERATED ALWAYS AS (
CASE
    WHEN ((target_kind)::text = 'GROUP'::text) THEN 'GROUP'::text
    ELSE 'USER'::text
END) STORED NOT NULL,
    subject_id text GENERATED ALWAYS AS (
CASE
    WHEN ((target_kind)::text = 'GROUP'::text) THEN target_id
    ELSE (end_user_id)::text
END) STORED NOT NULL,
    CONSTRAINT execution_sessions_sequence_ck CHECK ((last_event_sequence >= 0)),
    CONSTRAINT execution_sessions_subject_ck CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(target_id) <> ''::text) AND ((((target_kind)::text = ANY ((ARRAY['AGENT'::character varying, 'TEAM'::character varying])::text[])) AND (end_user_id IS NOT NULL) AND (end_user_id > 0)) OR (((target_kind)::text = 'GROUP'::text) AND (end_user_id IS NULL)))))
);

ALTER TABLE ONLY public.agent_runtime_execution_sessions FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_execution_state_publications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_execution_state_publications (
    tenant_id text NOT NULL,
    source_kind character varying(16) NOT NULL,
    source_id character varying(160) NOT NULL,
    event_id character varying(160) NOT NULL,
    source_revision bigint NOT NULL,
    publication_revision bigint NOT NULL,
    available_epoch_second bigint NOT NULL,
    available_nano integer NOT NULL,
    status character varying(16) NOT NULL,
    attempts integer NOT NULL,
    lease_claim_id text,
    lease_worker_id text,
    lease_fencing_token bigint,
    lease_until_epoch_second bigint,
    lease_until_nano integer,
    projection_ref text,
    failure_data text,
    publication_data text NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    updated_epoch_second bigint NOT NULL,
    updated_nano integer NOT NULL,
    CONSTRAINT ck_agent_runtime_execution_publication_codec CHECK ((btrim(publication_data) <> ''::text)),
    CONSTRAINT ck_agent_runtime_execution_publication_identity CHECK ((((source_kind)::text = 'execution'::text) AND (btrim((source_id)::text) <> ''::text) AND (btrim((event_id)::text) <> ''::text) AND (source_revision > 0) AND (publication_revision > 0) AND ((available_nano >= 0) AND (available_nano <= 999999999)) AND (attempts >= 0))),
    CONSTRAINT ck_agent_runtime_execution_publication_lease CHECK (((((status)::text = 'CLAIMED'::text) AND (lease_claim_id IS NOT NULL) AND (lease_worker_id IS NOT NULL) AND (lease_fencing_token IS NOT NULL) AND (lease_until_epoch_second IS NOT NULL) AND (lease_until_nano IS NOT NULL) AND (btrim(lease_claim_id) <> ''::text) AND (btrim(lease_worker_id) <> ''::text) AND (lease_fencing_token > 0) AND ((lease_until_nano >= 0) AND (lease_until_nano <= 999999999))) OR (((status)::text <> 'CLAIMED'::text) AND (lease_claim_id IS NULL) AND (lease_worker_id IS NULL) AND (lease_fencing_token IS NULL) AND (lease_until_epoch_second IS NULL) AND (lease_until_nano IS NULL)))),
    CONSTRAINT ck_agent_runtime_execution_publication_projection CHECK (((((status)::text = 'ACKNOWLEDGED'::text) = (projection_ref IS NOT NULL)) AND ((projection_ref IS NULL) OR (btrim(projection_ref) <> ''::text)))),
    CONSTRAINT ck_agent_runtime_execution_publication_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'CLAIMED'::character varying, 'ACKNOWLEDGED'::character varying])::text[]))),
    CONSTRAINT ck_agent_runtime_execution_publication_times CHECK ((((created_nano >= 0) AND (created_nano <= 999999999)) AND ((updated_nano >= 0) AND (updated_nano <= 999999999)) AND (ROW(updated_epoch_second, updated_nano) >= ROW(created_epoch_second, created_nano))))
);

ALTER TABLE ONLY public.agent_runtime_execution_state_publications FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_execution_supplements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_execution_supplements (
    tenant_id text NOT NULL,
    run_id character varying(160) NOT NULL,
    input_id character varying(256) NOT NULL,
    content_ref text NOT NULL,
    fingerprint text NOT NULL,
    actor_ref text NOT NULL,
    ordinal bigint NOT NULL,
    applied_revision bigint,
    CONSTRAINT execution_supplements_identity_ck CHECK (((btrim((input_id)::text) <> ''::text) AND (btrim(fingerprint) <> ''::text) AND (btrim(actor_ref) <> ''::text))),
    CONSTRAINT execution_supplements_revision_ck CHECK (((ordinal > 0) AND ((applied_revision IS NULL) OR (applied_revision > 0))))
);

ALTER TABLE ONLY public.agent_runtime_execution_supplements FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_execution_wait_children; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_execution_wait_children (
    tenant_id text NOT NULL,
    action_id character varying(160) NOT NULL,
    generation bigint NOT NULL,
    ordinal integer NOT NULL,
    delegation_id text NOT NULL,
    child_run_id character varying(160) NOT NULL,
    CONSTRAINT execution_wait_children_identity_ck CHECK (((ordinal > 0) AND (btrim(delegation_id) <> ''::text)))
);

ALTER TABLE ONLY public.agent_runtime_execution_wait_children FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_execution_wait_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_execution_wait_requests (
    tenant_id text NOT NULL,
    action_id character varying(160) NOT NULL,
    generation bigint NOT NULL,
    input_key text NOT NULL,
    method character varying(64) NOT NULL,
    CONSTRAINT execution_wait_requests_key_ck CHECK ((btrim(input_key) <> ''::text)),
    CONSTRAINT execution_wait_requests_method_ck CHECK (((method)::text = ANY ((ARRAY['elicitation/create'::character varying, 'sampling/createMessage'::character varying, 'roots/list'::character varying])::text[])))
);

ALTER TABLE ONLY public.agent_runtime_execution_wait_requests FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_execution_waits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_execution_waits (
    tenant_id text NOT NULL,
    run_id character varying(160) NOT NULL,
    action_id character varying(160) NOT NULL,
    generation bigint NOT NULL,
    kind character varying(32) NOT NULL,
    source_attempt text NOT NULL,
    input_id character varying(160),
    response_ref text,
    fingerprint text,
    actor_ref text,
    accepted_revision bigint,
    continuation_attempt text,
    consumed_revision bigint,
    declared_revision bigint NOT NULL,
    business_wait_id text,
    execution_id text,
    suspend_ref text,
    action_key text,
    execution_revision bigint,
    plan_id text,
    plan_revision bigint,
    CONSTRAINT agent_runtime_execution_waits_declared_revision_check CHECK ((declared_revision > 0)),
    CONSTRAINT execution_waits_accepted_ck CHECK ((((input_id IS NULL) AND (response_ref IS NULL) AND (fingerprint IS NULL) AND (actor_ref IS NULL) AND (accepted_revision IS NULL)) OR ((input_id IS NOT NULL) AND (btrim((input_id)::text) <> ''::text) AND (response_ref IS NOT NULL) AND (fingerprint IS NOT NULL) AND (btrim(fingerprint) <> ''::text) AND (actor_ref IS NOT NULL) AND (btrim(actor_ref) <> ''::text) AND (accepted_revision IS NOT NULL) AND (accepted_revision > 0)))),
    CONSTRAINT execution_waits_consumed_ck CHECK ((((continuation_attempt IS NULL) OR (((kind)::text = ANY ((ARRAY['TOOL'::character varying, 'WORKFLOW'::character varying])::text[])) AND (input_id IS NOT NULL) AND (btrim(continuation_attempt) <> ''::text))) AND ((consumed_revision IS NULL) OR (((kind)::text = ANY ((ARRAY['HUMAN'::character varying, 'DELEGATION'::character varying, 'PLAN'::character varying])::text[])) AND (input_id IS NOT NULL) AND (consumed_revision > accepted_revision))))),
    CONSTRAINT execution_waits_identity_ck CHECK (((generation > 0) AND (btrim(source_attempt) <> ''::text))),
    CONSTRAINT execution_waits_internal_ck CHECK (((((kind)::text = ANY ((ARRAY['TOOL'::character varying, 'HUMAN'::character varying])::text[])) AND (business_wait_id IS NULL) AND (execution_id IS NULL) AND (suspend_ref IS NULL) AND (action_key IS NULL) AND (execution_revision IS NULL) AND (plan_id IS NULL) AND (plan_revision IS NULL)) OR (((kind)::text = 'DELEGATION'::text) AND (business_wait_id IS NOT NULL) AND (btrim(business_wait_id) <> ''::text) AND (execution_id IS NULL) AND (suspend_ref IS NULL) AND (action_key IS NULL) AND (execution_revision IS NULL) AND (plan_id IS NULL) AND (plan_revision IS NULL)) OR (((kind)::text = 'WORKFLOW'::text) AND (business_wait_id IS NULL) AND (execution_id IS NOT NULL) AND (btrim(execution_id) <> ''::text) AND (suspend_ref IS NOT NULL) AND (btrim(suspend_ref) <> ''::text) AND (action_key IS NOT NULL) AND (btrim(action_key) <> ''::text) AND (execution_revision IS NOT NULL) AND (execution_revision > 0) AND (plan_id IS NULL) AND (plan_revision IS NULL)) OR (((kind)::text = 'PLAN'::text) AND (business_wait_id IS NOT NULL) AND (btrim(business_wait_id) <> ''::text) AND (plan_id IS NOT NULL) AND (btrim(plan_id) <> ''::text) AND (plan_revision IS NOT NULL) AND (plan_revision > 0) AND (execution_id IS NULL) AND (suspend_ref IS NULL) AND (action_key IS NULL) AND (execution_revision IS NULL)))),
    CONSTRAINT execution_waits_kind_ck CHECK (((kind)::text = ANY ((ARRAY['TOOL'::character varying, 'HUMAN'::character varying, 'DELEGATION'::character varying, 'WORKFLOW'::character varying, 'PLAN'::character varying])::text[])))
);

ALTER TABLE ONLY public.agent_runtime_execution_waits FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_execution_workflow_callback_deliveries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_execution_workflow_callback_deliveries (
    tenant_id text NOT NULL,
    delivery_id text NOT NULL,
    run_id character varying(160) NOT NULL,
    action_id character varying(160) NOT NULL,
    generation bigint NOT NULL,
    input_id character varying(160) NOT NULL,
    CONSTRAINT execution_workflow_callback_identity_ck CHECK (((btrim(delivery_id) <> ''::text) AND (btrim((input_id)::text) <> ''::text) AND (generation > 0)))
);

ALTER TABLE ONLY public.agent_runtime_execution_workflow_callback_deliveries FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_executions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_executions (
    tenant_id text NOT NULL,
    execution_id character varying(160) NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint,
    run_id character varying(160) NOT NULL,
    causation_input_id character varying(160) NOT NULL,
    execution_unit_id text NOT NULL,
    definition_version text NOT NULL,
    execution_mode character varying(16) NOT NULL,
    causative_action_id text,
    delegation_id text,
    state character varying(24) NOT NULL,
    execution_revision bigint NOT NULL,
    checkpoint_schema_id text NOT NULL,
    checkpoint_canonical_json text NOT NULL,
    waiting_suspend_ref text,
    waiting_action_key text,
    result_ref text,
    reason_code text,
    lease_claim_id text,
    lease_worker_id text,
    lease_fencing_token bigint,
    lease_execution_revision bigint,
    lease_until_epoch_second bigint,
    lease_until_nano integer,
    last_requested_epoch_second bigint,
    last_requested_nano integer,
    cancel_request_id character varying(160),
    cancel_requested_epoch_second bigint,
    cancel_requested_nano integer,
    record_data text NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    updated_epoch_second bigint NOT NULL,
    updated_nano integer NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    subject_kind text GENERATED ALWAYS AS (
CASE
    WHEN (target_kind = 'GROUP'::text) THEN 'GROUP'::text
    ELSE 'USER'::text
END) STORED NOT NULL,
    subject_id text GENERATED ALWAYS AS (
CASE
    WHEN (target_kind = 'GROUP'::text) THEN target_id
    ELSE (end_user_id)::text
END) STORED NOT NULL,
    CONSTRAINT agent_runtime_executions_subject_ck CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(target_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (btrim((run_id)::text) <> ''::text) AND (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (end_user_id IS NOT NULL) AND (end_user_id > 0)) OR ((target_kind = 'GROUP'::text) AND (end_user_id IS NULL))))),
    CONSTRAINT ck_agent_runtime_execution_cancel CHECK ((((cancel_request_id IS NULL) AND (cancel_requested_epoch_second IS NULL) AND (cancel_requested_nano IS NULL)) OR ((cancel_request_id IS NOT NULL) AND (btrim((cancel_request_id)::text) <> ''::text) AND (cancel_requested_epoch_second IS NOT NULL) AND (cancel_requested_nano IS NOT NULL) AND ((cancel_requested_nano >= 0) AND (cancel_requested_nano <= 999999999))))),
    CONSTRAINT ck_agent_runtime_execution_checkpoint CHECK (((btrim(checkpoint_schema_id) <> ''::text) AND (btrim(checkpoint_canonical_json) <> ''::text))),
    CONSTRAINT ck_agent_runtime_execution_codec CHECK ((btrim(record_data) <> ''::text)),
    CONSTRAINT ck_agent_runtime_execution_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim((execution_id)::text) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (end_user_id > 0) AND (btrim((run_id)::text) <> ''::text) AND (btrim((causation_input_id)::text) <> ''::text) AND (btrim(execution_unit_id) <> ''::text) AND (btrim(definition_version) <> ''::text) AND (execution_revision > 0))),
    CONSTRAINT ck_agent_runtime_execution_last_requested CHECK ((((last_requested_epoch_second IS NULL) AND (last_requested_nano IS NULL)) OR ((last_requested_epoch_second IS NOT NULL) AND (last_requested_nano IS NOT NULL) AND ((last_requested_nano >= 0) AND (last_requested_nano <= 999999999))))),
    CONSTRAINT ck_agent_runtime_execution_lease CHECK (((((lease_claim_id IS NULL) AND (lease_worker_id IS NULL) AND (lease_fencing_token IS NULL) AND (lease_execution_revision IS NULL) AND (lease_until_epoch_second IS NULL) AND (lease_until_nano IS NULL)) OR ((lease_claim_id IS NOT NULL) AND (lease_worker_id IS NOT NULL) AND (lease_fencing_token IS NOT NULL) AND (lease_execution_revision IS NOT NULL) AND (lease_until_epoch_second IS NOT NULL) AND (lease_until_nano IS NOT NULL) AND (btrim(lease_claim_id) <> ''::text) AND (btrim(lease_worker_id) <> ''::text) AND (lease_fencing_token > 0) AND (lease_execution_revision > 0) AND (lease_execution_revision <= execution_revision) AND ((lease_until_nano >= 0) AND (lease_until_nano <= 999999999)))) AND (((state)::text <> ALL ((ARRAY['COMPLETED'::character varying, 'FAILED'::character varying, 'LOOP_TERMINATED'::character varying, 'CANCELLED'::character varying])::text[])) OR (lease_claim_id IS NULL)))),
    CONSTRAINT ck_agent_runtime_execution_metadata CHECK ((((execution_mode)::text = ANY ((ARRAY['FOREGROUND'::character varying, 'BACKGROUND'::character varying])::text[])) AND ((causative_action_id IS NULL) OR (btrim(causative_action_id) <> ''::text)) AND ((delegation_id IS NULL) OR (btrim(delegation_id) <> ''::text)))),
    CONSTRAINT ck_agent_runtime_execution_reason CHECK (((((state)::text = ANY ((ARRAY['FAILED'::character varying, 'LOOP_TERMINATED'::character varying])::text[])) = (reason_code IS NOT NULL)) AND ((reason_code IS NULL) OR (btrim(reason_code) <> ''::text)))),
    CONSTRAINT ck_agent_runtime_execution_result CHECK (((((state)::text = ANY ((ARRAY['WAITING_HITL'::character varying, 'WAITING_EXTERNAL'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'LOOP_TERMINATED'::character varying, 'CANCELLED'::character varying])::text[])) = (result_ref IS NOT NULL)) AND ((result_ref IS NULL) OR (btrim(result_ref) <> ''::text)))),
    CONSTRAINT ck_agent_runtime_execution_state CHECK (((state)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'WAITING_HITL'::character varying, 'WAITING_EXTERNAL'::character varying, 'RESUMING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'LOOP_TERMINATED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT ck_agent_runtime_execution_times CHECK ((((created_nano >= 0) AND (created_nano <= 999999999)) AND ((updated_nano >= 0) AND (updated_nano <= 999999999)) AND (ROW(updated_epoch_second, updated_nano) >= ROW(created_epoch_second, created_nano)))),
    CONSTRAINT ck_agent_runtime_execution_waiting CHECK (((((state)::text = ANY ((ARRAY['WAITING_HITL'::character varying, 'WAITING_EXTERNAL'::character varying])::text[])) AND (waiting_suspend_ref IS NOT NULL) AND (waiting_action_key IS NOT NULL) AND (btrim(waiting_suspend_ref) <> ''::text) AND (btrim(waiting_action_key) <> ''::text)) OR (((state)::text <> ALL ((ARRAY['WAITING_HITL'::character varying, 'WAITING_EXTERNAL'::character varying])::text[])) AND (waiting_suspend_ref IS NULL) AND (waiting_action_key IS NULL))))
);

ALTER TABLE ONLY public.agent_runtime_executions FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_inbox_mutations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_inbox_mutations (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    input_id character varying(160) NOT NULL,
    mutation_key text NOT NULL,
    mutation_kind character varying(16) NOT NULL,
    command_fingerprint character varying(64) NOT NULL,
    output_revision bigint NOT NULL,
    output_status character varying(16) NOT NULL,
    output_fingerprint character varying(64) NOT NULL,
    output_data text NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    CONSTRAINT agent_runtime_inbox_mutations_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_inbox_mutation_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim((input_id)::text) <> ''::text) AND (btrim(mutation_key) <> ''::text) AND ((mutation_kind)::text = ANY ((ARRAY['COMPLETE'::character varying, 'RETRY'::character varying, 'FAIL'::character varying])::text[])) AND ((command_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND ((output_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (output_revision > 1) AND ((output_status)::text = ANY ((ARRAY['QUEUED'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[])) AND (btrim(output_data) <> ''::text)))
);

ALTER TABLE ONLY public.agent_runtime_inbox_mutations FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_input_ledger; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_input_ledger (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    input_id character varying(160) NOT NULL,
    request_fingerprint text NOT NULL,
    verification_ref text NOT NULL,
    causation_identity_ref text NOT NULL,
    decision_kind character varying(32) NOT NULL,
    run_id character varying(160),
    receipt_status character varying(16) NOT NULL,
    ledger_revision bigint NOT NULL,
    command_data text NOT NULL,
    receipt_data text NOT NULL,
    recorded_epoch_second bigint NOT NULL,
    recorded_nano integer NOT NULL,
    expires_epoch_second bigint NOT NULL,
    expires_nano integer NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    CONSTRAINT agent_runtime_input_ledger_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_input_ledger_codec CHECK (((btrim(command_data) <> ''::text) AND (btrim(receipt_data) <> ''::text))),
    CONSTRAINT ck_agent_runtime_input_ledger_decision CHECK ((((decision_kind)::text = ANY ((ARRAY['ACCEPTED_NEW_RUN'::character varying, 'ACCEPTED_EXISTING_RUN'::character varying, 'REJECTED'::character varying])::text[])) AND ((receipt_status)::text = ANY ((ARRAY['ACCEPTED'::character varying, 'REJECTED'::character varying])::text[])) AND (ledger_revision = 1) AND ((((receipt_status)::text = 'ACCEPTED'::text) AND ((decision_kind)::text = ANY ((ARRAY['ACCEPTED_NEW_RUN'::character varying, 'ACCEPTED_EXISTING_RUN'::character varying])::text[])) AND (run_id IS NOT NULL) AND (btrim((run_id)::text) <> ''::text)) OR (((receipt_status)::text = 'REJECTED'::text) AND ((decision_kind)::text = 'REJECTED'::text))))),
    CONSTRAINT ck_agent_runtime_input_ledger_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (btrim((input_id)::text) <> ''::text) AND (btrim(request_fingerprint) <> ''::text) AND (btrim(verification_ref) <> ''::text) AND (btrim(causation_identity_ref) <> ''::text))),
    CONSTRAINT ck_agent_runtime_input_ledger_times CHECK ((((recorded_nano >= 0) AND (recorded_nano <= 999999999)) AND ((expires_nano >= 0) AND (expires_nano <= 999999999)) AND (ROW(expires_epoch_second, expires_nano) >= ROW(recorded_epoch_second, recorded_nano))))
);

ALTER TABLE ONLY public.agent_runtime_input_ledger FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_input_ledger_archives; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_input_ledger_archives (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    input_id character varying(160) NOT NULL,
    request_fingerprint text NOT NULL,
    verification_ref text NOT NULL,
    causation_identity_ref text NOT NULL,
    decision_kind character varying(32) NOT NULL,
    run_id character varying(160),
    receipt_status character varying(16) NOT NULL,
    ledger_revision bigint NOT NULL,
    command_data text NOT NULL,
    receipt_data text NOT NULL,
    recorded_epoch_second bigint NOT NULL,
    recorded_nano integer NOT NULL,
    expires_epoch_second bigint NOT NULL,
    expires_nano integer NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    owner_session_id text NOT NULL,
    owner_target_kind text NOT NULL,
    owner_target_id text NOT NULL,
    owner_end_user_id bigint NOT NULL,
    source_session_revision bigint NOT NULL,
    closed_epoch_second bigint NOT NULL,
    closed_nano integer NOT NULL,
    source_run_id text,
    source_run_revision bigint,
    source_run_updated_epoch_second bigint,
    source_run_updated_nano integer,
    original_row_sha256 text NOT NULL,
    archived_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    command_data_sha256 text NOT NULL,
    receipt_data_sha256 text NOT NULL,
    CONSTRAINT agent_runtime_input_ledger_archiv_source_session_revision_check CHECK ((source_session_revision > 0)),
    CONSTRAINT agent_runtime_input_ledger_archives_closed_nano_check CHECK (((closed_nano >= 0) AND (closed_nano <= 999999999))),
    CONSTRAINT agent_runtime_input_ledger_archives_command_data_sha256_check CHECK ((command_data_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_input_ledger_archives_original_row_sha256_check CHECK ((original_row_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_input_ledger_archives_owner_end_user_id_check CHECK ((owner_end_user_id > 0)),
    CONSTRAINT agent_runtime_input_ledger_archives_receipt_data_sha256_check CHECK ((receipt_data_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_input_ledger_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_input_ledger_codec CHECK (((btrim(command_data) <> ''::text) AND (btrim(receipt_data) <> ''::text))),
    CONSTRAINT ck_agent_runtime_input_ledger_decision CHECK ((((decision_kind)::text = ANY ((ARRAY['ACCEPTED_NEW_RUN'::character varying, 'ACCEPTED_EXISTING_RUN'::character varying, 'REJECTED'::character varying])::text[])) AND ((receipt_status)::text = ANY ((ARRAY['ACCEPTED'::character varying, 'REJECTED'::character varying])::text[])) AND (ledger_revision = 1) AND ((((receipt_status)::text = 'ACCEPTED'::text) AND ((decision_kind)::text = ANY ((ARRAY['ACCEPTED_NEW_RUN'::character varying, 'ACCEPTED_EXISTING_RUN'::character varying])::text[])) AND (run_id IS NOT NULL) AND (btrim((run_id)::text) <> ''::text)) OR (((receipt_status)::text = 'REJECTED'::text) AND ((decision_kind)::text = 'REJECTED'::text))))),
    CONSTRAINT ck_agent_runtime_input_ledger_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (btrim((input_id)::text) <> ''::text) AND (btrim(request_fingerprint) <> ''::text) AND (btrim(verification_ref) <> ''::text) AND (btrim(causation_identity_ref) <> ''::text))),
    CONSTRAINT ck_agent_runtime_input_ledger_times CHECK ((((recorded_nano >= 0) AND (recorded_nano <= 999999999)) AND ((expires_nano >= 0) AND (expires_nano <= 999999999)) AND (ROW(expires_epoch_second, expires_nano) >= ROW(recorded_epoch_second, recorded_nano))))
);

ALTER TABLE ONLY public.agent_runtime_input_ledger_archives FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_memory_conflict_audits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_memory_conflict_audits (
    tenant_id text NOT NULL,
    audit_ref text NOT NULL,
    idempotency_key text NOT NULL,
    fact_id text NOT NULL,
    current_revision bigint NOT NULL,
    reason_code text NOT NULL,
    command_digest character varying(64) NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    CONSTRAINT ck_agent_runtime_memory_audit_identity CHECK (((btrim(audit_ref) <> ''::text) AND (btrim(idempotency_key) <> ''::text) AND (btrim(reason_code) <> ''::text))),
    CONSTRAINT ck_agent_runtime_memory_audit_payload CHECK ((((command_digest)::text ~ '^[0-9a-f]{64}$'::text) AND (current_revision > 0) AND ((created_nano >= 0) AND (created_nano <= 999999999))))
);

ALTER TABLE ONLY public.agent_runtime_memory_conflict_audits FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_memory_embeddings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_memory_embeddings (
    tenant_id text NOT NULL,
    fact_id text NOT NULL,
    content_revision bigint NOT NULL,
    projection_version text NOT NULL,
    analyzer_version text NOT NULL,
    model_id text NOT NULL,
    model_revision bigint NOT NULL,
    dimensions integer NOT NULL,
    text_digest character varying(64) NOT NULL,
    invocation_id text NOT NULL,
    state character varying(16) DEFAULT 'QUEUED'::character varying NOT NULL,
    embedding public.vector,
    lease_token text,
    lease_until timestamp with time zone,
    reason_code text,
    updated_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT memory_embeddings_digest_ck CHECK (((text_digest)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT memory_embeddings_lease_ck CHECK (((((state)::text = 'RUNNING'::text) AND (lease_token IS NOT NULL) AND (lease_until IS NOT NULL)) OR (((state)::text <> 'RUNNING'::text) AND (lease_token IS NULL) AND (lease_until IS NULL)))),
    CONSTRAINT memory_embeddings_model_ck CHECK (((btrim(model_id) <> ''::text) AND (model_revision > 0) AND (dimensions > 0))),
    CONSTRAINT memory_embeddings_state_ck CHECK (((state)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'READY'::character varying, 'REJECTED'::character varying, 'UNKNOWN'::character varying])::text[]))),
    CONSTRAINT memory_embeddings_vector_ck CHECK (((((state)::text = 'READY'::text) AND (embedding IS NOT NULL) AND (public.vector_dims(embedding) = dimensions) AND (public.vector_norm(embedding) > (0)::double precision)) OR (((state)::text <> 'READY'::text) AND (embedding IS NULL))))
);

ALTER TABLE ONLY public.agent_runtime_memory_embeddings FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_memory_extraction_candidate_evidence; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_memory_extraction_candidate_evidence (
    tenant_id text NOT NULL,
    source_event_id text NOT NULL,
    extractor_version text NOT NULL,
    policy_version text NOT NULL,
    memory_type text NOT NULL,
    canonical_fact_key text NOT NULL,
    candidate_ordinal integer NOT NULL,
    item_ordinal integer NOT NULL,
    source_alias text NOT NULL,
    quote text NOT NULL,
    CONSTRAINT agent_runtime_memory_extraction_candidate_evidence_check CHECK ((((item_ordinal >= 0) AND (item_ordinal <= 31)) AND (btrim(quote) <> ''::text)))
);

ALTER TABLE ONLY public.agent_runtime_memory_extraction_candidate_evidence FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_memory_extraction_candidates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_memory_extraction_candidates (
    tenant_id text NOT NULL,
    source_event_id text NOT NULL,
    extractor_version text NOT NULL,
    policy_version text NOT NULL,
    memory_type text NOT NULL,
    canonical_fact_key text NOT NULL,
    candidate_ordinal integer NOT NULL,
    command_key text NOT NULL,
    state character varying(16) DEFAULT 'READY'::character varying NOT NULL,
    candidate_type text NOT NULL,
    subject text NOT NULL,
    predicate text NOT NULL,
    value_json jsonb NOT NULL,
    signal text NOT NULL,
    sensitive boolean NOT NULL,
    candidate_scope text NOT NULL,
    temporal_kind text NOT NULL,
    temporal_from_epoch_second bigint,
    temporal_from_nano integer,
    temporal_to_epoch_second bigint,
    temporal_to_nano integer,
    relation_kind text NOT NULL,
    relation_target text,
    condition_count integer NOT NULL,
    uncertainty_count integer NOT NULL,
    evidence_count integer NOT NULL,
    prepared_type text,
    prepared_scope text,
    retention_class text,
    schema_version text,
    payload_schema_id text,
    valid_from_epoch_second bigint,
    valid_from_nano integer,
    valid_to_epoch_second bigint,
    valid_to_nano integer,
    expires_epoch_second bigint,
    expires_nano integer,
    confidence double precision,
    importance double precision,
    review_required boolean,
    initial_status text,
    review_reason_count integer,
    target_fact_id text,
    target_revision bigint,
    fact_id text,
    fact_revision bigint,
    error_code text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_memory_extraction_candidate CHECK (((candidate_ordinal >= 0) AND (candidate_ordinal < 32) AND (btrim(command_key) <> ''::text) AND ((state)::text = ANY ((ARRAY['READY'::character varying, 'PREPARED'::character varying, 'ACK_PENDING'::character varying, 'UNKNOWN'::character varying, 'RECORDED'::character varying, 'FAILED'::character varying])::text[])) AND ((target_fact_id IS NULL) = (target_revision IS NULL)) AND ((target_revision IS NULL) OR (target_revision > 0)) AND ((fact_id IS NULL) = (fact_revision IS NULL)) AND ((fact_revision IS NULL) OR (fact_revision > 0)) AND (((state)::text <> 'RECORDED'::text) OR (fact_id IS NOT NULL)) AND ((((state)::text = 'READY'::text) AND (prepared_scope IS NULL)) OR (((state)::text = 'FAILED'::text) AND (prepared_scope IS NULL) AND (fact_id IS NULL) AND (target_fact_id IS NULL) AND (error_code = ANY (ARRAY['memory_forgotten_source_suppressed'::text, 'memory_superseded_source_suppressed'::text]))) OR (((state)::text <> 'READY'::text) AND (prepared_scope IS NOT NULL))) AND (candidate_type = ANY (ARRAY['preference'::text, 'episode'::text])) AND (candidate_scope = ANY (ARRAY['user'::text, 'user_agent'::text, 'group'::text, 'group_member'::text])) AND (btrim(subject) <> ''::text) AND (btrim(predicate) <> ''::text) AND (jsonb_typeof(value_json) = 'object'::text) AND (octet_length((value_json)::text) <= 16384) AND (signal = ANY (ARRAY['observed'::text, 'explicit_save'::text, 'repeated_confirmed'::text])) AND (temporal_kind = ANY (ARRAY['stable'::text, 'task'::text, 'interval'::text, 'unknown'::text])) AND (relation_kind = ANY (ARRAY['new'::text, 'similar'::text, 'possible_conflict'::text, 'correction'::text])) AND ((relation_kind = 'new'::text) = (relation_target IS NULL)) AND ((condition_count >= 0) AND (condition_count <= 32)) AND ((uncertainty_count >= 0) AND (uncertainty_count <= 32)) AND ((evidence_count >= 1) AND (evidence_count <= 32)) AND ((temporal_from_epoch_second IS NULL) = (temporal_from_nano IS NULL)) AND ((temporal_to_epoch_second IS NULL) = (temporal_to_nano IS NULL)) AND ((temporal_from_nano IS NULL) OR ((temporal_from_nano >= 0) AND (temporal_from_nano <= 999999999))) AND ((temporal_to_nano IS NULL) OR ((temporal_to_nano >= 0) AND (temporal_to_nano <= 999999999))) AND (((temporal_kind = 'interval'::text) AND (temporal_from_epoch_second IS NOT NULL) AND ((temporal_to_epoch_second IS NULL) OR (ROW(temporal_to_epoch_second, temporal_to_nano) > ROW(temporal_from_epoch_second, temporal_from_nano)))) OR ((temporal_kind <> 'interval'::text) AND (temporal_from_epoch_second IS NULL) AND (temporal_to_epoch_second IS NULL))) AND ((prepared_scope IS NULL) OR COALESCE(((prepared_scope = ANY (ARRAY['USER'::text, 'USER_AGENT'::text, 'GROUP'::text, 'GROUP_MEMBER'::text])) AND (prepared_type = ANY (ARRAY['preference'::text, 'episode'::text])) AND (btrim(retention_class) <> ''::text) AND (btrim(schema_version) <> ''::text) AND (btrim(payload_schema_id) <> ''::text) AND (valid_from_epoch_second IS NOT NULL) AND ((valid_from_nano >= 0) AND (valid_from_nano <= 999999999)) AND ((confidence >= (0)::double precision) AND (confidence <= (1)::double precision)) AND ((importance >= (0)::double precision) AND (importance <= (1)::double precision)) AND (review_required IS NOT NULL) AND (initial_status = ANY (ARRAY['ACTIVE'::text, 'PENDING_CONFIRM'::text])) AND ((review_reason_count >= 0) AND (review_reason_count <= 32)) AND ((valid_to_epoch_second IS NULL) = (valid_to_nano IS NULL)) AND ((expires_epoch_second IS NULL) = (expires_nano IS NULL)) AND ((valid_to_nano IS NULL) OR ((valid_to_nano >= 0) AND (valid_to_nano <= 999999999))) AND ((expires_nano IS NULL) OR ((expires_nano >= 0) AND (expires_nano <= 999999999)))), false))))
);

ALTER TABLE ONLY public.agent_runtime_memory_extraction_candidates FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_memory_extraction_conditions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_memory_extraction_conditions (
    tenant_id text NOT NULL,
    source_event_id text NOT NULL,
    extractor_version text NOT NULL,
    policy_version text NOT NULL,
    memory_type text NOT NULL,
    canonical_fact_key text NOT NULL,
    candidate_ordinal integer NOT NULL,
    item_ordinal integer NOT NULL,
    item_text text NOT NULL,
    CONSTRAINT agent_runtime_memory_extraction_conditions_check CHECK ((((item_ordinal >= 0) AND (item_ordinal <= 31)) AND (btrim(item_text) <> ''::text)))
);

ALTER TABLE ONLY public.agent_runtime_memory_extraction_conditions FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_memory_extraction_evidence; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_memory_extraction_evidence (
    tenant_id text NOT NULL,
    extraction_digest character varying(64) NOT NULL,
    agent_id text NOT NULL,
    source_target_kind character varying(16) NOT NULL,
    source_target_id text NOT NULL,
    source_session_id text NOT NULL,
    source_event_id text NOT NULL,
    extractor_version text NOT NULL,
    fact_id text NOT NULL,
    revision bigint NOT NULL,
    access_audit_ref text NOT NULL,
    source_audit_ref text NOT NULL,
    recorded_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_agent_runtime_memory_extraction_evidence_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND ((source_target_kind)::text = ANY ((ARRAY['AGENT'::character varying, 'TEAM'::character varying])::text[])) AND (btrim(source_target_id) <> ''::text) AND (btrim(source_session_id) <> ''::text) AND (btrim(source_event_id) <> ''::text) AND (btrim(extractor_version) <> ''::text) AND (btrim(fact_id) <> ''::text) AND (revision > 0) AND (btrim(access_audit_ref) <> ''::text) AND (btrim(source_audit_ref) <> ''::text) AND ((extraction_digest)::text ~ '^[0-9a-f]{64}$'::text)))
);

ALTER TABLE ONLY public.agent_runtime_memory_extraction_evidence FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_memory_extraction_fact_aliases; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_memory_extraction_fact_aliases (
    tenant_id text NOT NULL,
    source_event_id text NOT NULL,
    extractor_version text NOT NULL,
    policy_version text NOT NULL,
    memory_type text NOT NULL,
    canonical_fact_key text NOT NULL,
    alias_ordinal integer NOT NULL,
    alias text NOT NULL,
    fact_id text NOT NULL,
    fact_revision bigint NOT NULL,
    CONSTRAINT agent_runtime_memory_extraction_fact_aliases_check CHECK ((((alias_ordinal >= 0) AND (alias_ordinal <= 63)) AND (btrim(alias) <> ''::text) AND (fact_revision > 0)))
);

ALTER TABLE ONLY public.agent_runtime_memory_extraction_fact_aliases FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_memory_extraction_jobs; Type: TABLE; Schema: public; Owner: -
--

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
    status character varying(16) DEFAULT 'READY'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    result_fact_id text,
    result_revision bigint,
    error_code text,
    available_at timestamp with time zone DEFAULT now() NOT NULL,
    claimed_at timestamp with time zone,
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    source_target_kind text NOT NULL,
    source_target_id text NOT NULL,
    policy_version text DEFAULT 'memory-extraction-policy-v2'::text NOT NULL,
    source_time_epoch_second bigint,
    source_time_nano integer,
    frozen_fact_count integer,
    model_status character varying(16),
    system_run_id text,
    configuration_ref text,
    invocation_ref text,
    claim_token text,
    lease_until timestamp with time zone,
    integration_id character varying(128),
    group_binding_id bigint,
    frozen_subject_count integer,
    CONSTRAINT ck_agent_runtime_memory_extraction_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(source_event_id) <> ''::text) AND (btrim(extractor_version) <> ''::text) AND (btrim(memory_type) <> ''::text) AND (btrim(canonical_fact_key) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (end_user_id > 0) AND (btrim(source_session_id) <> ''::text) AND (btrim(source_event_type) <> ''::text) AND (attempts >= 0))),
    CONSTRAINT ck_agent_runtime_memory_extraction_result CHECK ((((result_fact_id IS NULL) = (result_revision IS NULL)) AND ((result_revision IS NULL) OR (result_revision > 0)))),
    CONSTRAINT ck_agent_runtime_memory_extraction_status CHECK (((status)::text = ANY ((ARRAY['READY'::character varying, 'PROCESSING'::character varying, 'WAITING'::character varying, 'ACK_PENDING'::character varying, 'UNKNOWN'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'SKIPPED'::character varying])::text[]))),
    CONSTRAINT ck_memory_extraction_semantic_material CHECK (((btrim(policy_version) <> ''::text) AND ((model_status IS NULL) OR ((model_status)::text = ANY ((ARRAY['WAITING'::character varying, 'COMPLETED'::character varying, 'UNKNOWN'::character varying, 'REJECTED'::character varying])::text[]))) AND ((source_time_epoch_second IS NULL) = (source_time_nano IS NULL)) AND ((source_time_epoch_second IS NULL) = (frozen_fact_count IS NULL)) AND ((source_time_nano IS NULL) OR ((source_time_nano >= 0) AND (source_time_nano <= 999999999))) AND ((frozen_fact_count IS NULL) OR ((frozen_fact_count >= 0) AND (frozen_fact_count <= 64))))),
    CONSTRAINT memory_extraction_group_owner_ck CHECK ((((source_target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (integration_id IS NULL) AND (group_binding_id IS NULL)) OR ((source_target_kind = 'GROUP'::text) AND (integration_id IS NOT NULL) AND (btrim((integration_id)::text) <> ''::text) AND (group_binding_id IS NOT NULL) AND (group_binding_id > 0) AND (source_target_id = (group_binding_id)::text) AND (source_event_type = 'group.memory.source'::text)))),
    CONSTRAINT memory_extraction_group_subject_count_ck CHECK ((((frozen_subject_count IS NULL) OR (frozen_subject_count >= 0)) AND ((source_time_epoch_second IS NULL) OR ((source_target_kind = 'GROUP'::text) AND COALESCE((frozen_subject_count > 0), false)) OR ((source_target_kind <> 'GROUP'::text) AND (COALESCE(frozen_subject_count, 0) = 0)))))
);

ALTER TABLE ONLY public.agent_runtime_memory_extraction_jobs FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_memory_extraction_review_reasons; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_memory_extraction_review_reasons (
    tenant_id text NOT NULL,
    source_event_id text NOT NULL,
    extractor_version text NOT NULL,
    policy_version text NOT NULL,
    memory_type text NOT NULL,
    canonical_fact_key text NOT NULL,
    candidate_ordinal integer NOT NULL,
    item_ordinal integer NOT NULL,
    item_text text NOT NULL,
    CONSTRAINT agent_runtime_memory_extraction_review_reasons_check CHECK ((((item_ordinal >= 0) AND (item_ordinal <= 31)) AND (btrim(item_text) <> ''::text)))
);

ALTER TABLE ONLY public.agent_runtime_memory_extraction_review_reasons FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_memory_extraction_sources; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_memory_extraction_sources (
    tenant_id text NOT NULL,
    source_event_id text NOT NULL,
    extractor_version text NOT NULL,
    policy_version text NOT NULL,
    memory_type text NOT NULL,
    canonical_fact_key text NOT NULL,
    source_alias text NOT NULL,
    source_ordinal integer NOT NULL,
    CONSTRAINT agent_runtime_memory_extraction_sources_check CHECK (((source_alias = 's0'::text) AND (source_ordinal = 0)))
);

ALTER TABLE ONLY public.agent_runtime_memory_extraction_sources FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_memory_extraction_subjects; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_memory_extraction_subjects (
    tenant_id text NOT NULL,
    source_event_id text NOT NULL,
    extractor_version text NOT NULL,
    policy_version text NOT NULL,
    memory_type text NOT NULL,
    canonical_fact_key text NOT NULL,
    subject_ordinal integer NOT NULL,
    subject_end_user_id bigint NOT NULL,
    CONSTRAINT agent_runtime_memory_extraction_subjects_check CHECK (((subject_ordinal >= 0) AND (subject_end_user_id > 0)))
);

ALTER TABLE ONLY public.agent_runtime_memory_extraction_subjects FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_memory_extraction_uncertainties; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_memory_extraction_uncertainties (
    tenant_id text NOT NULL,
    source_event_id text NOT NULL,
    extractor_version text NOT NULL,
    policy_version text NOT NULL,
    memory_type text NOT NULL,
    canonical_fact_key text NOT NULL,
    candidate_ordinal integer NOT NULL,
    item_ordinal integer NOT NULL,
    item_text text NOT NULL,
    CONSTRAINT agent_runtime_memory_extraction_uncertainties_check CHECK ((((item_ordinal >= 0) AND (item_ordinal <= 31)) AND (btrim(item_text) <> ''::text)))
);

ALTER TABLE ONLY public.agent_runtime_memory_extraction_uncertainties FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_memory_fact_heads; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_memory_fact_heads (
    tenant_id text NOT NULL,
    fact_id text NOT NULL,
    current_revision bigint NOT NULL,
    end_user_id bigint,
    agent_id text,
    session_id text,
    scope character varying(16) NOT NULL,
    type_id text NOT NULL,
    group_binding_id text,
    subject_end_user_id bigint,
    CONSTRAINT ck_memory_head_identity CHECK (((current_revision > 0) AND (btrim(type_id) <> ''::text))),
    CONSTRAINT ck_memory_head_typed_owner CHECK (((((scope)::text = 'SESSION'::text) AND (session_id IS NOT NULL) AND (btrim(session_id) <> ''::text) AND (end_user_id IS NULL) AND (agent_id IS NULL) AND (group_binding_id IS NULL) AND (subject_end_user_id IS NULL)) OR (((scope)::text = 'USER_AGENT'::text) AND (end_user_id IS NOT NULL) AND (end_user_id > 0) AND (agent_id IS NOT NULL) AND (btrim(agent_id) <> ''::text) AND (session_id IS NULL) AND (group_binding_id IS NULL) AND (subject_end_user_id IS NULL)) OR (((scope)::text = 'USER'::text) AND (end_user_id IS NOT NULL) AND (end_user_id > 0) AND (agent_id IS NULL) AND (session_id IS NULL) AND (group_binding_id IS NULL) AND (subject_end_user_id IS NULL)) OR (((scope)::text = 'GROUP'::text) AND (group_binding_id IS NOT NULL) AND (btrim(group_binding_id) <> ''::text) AND (end_user_id IS NULL) AND (agent_id IS NULL) AND (session_id IS NULL) AND (subject_end_user_id IS NULL)) OR (((scope)::text = 'GROUP_MEMBER'::text) AND (group_binding_id IS NOT NULL) AND (btrim(group_binding_id) <> ''::text) AND (subject_end_user_id IS NOT NULL) AND (subject_end_user_id > 0) AND (end_user_id IS NULL) AND (agent_id IS NULL) AND (session_id IS NULL))))
);

ALTER TABLE ONLY public.agent_runtime_memory_fact_heads FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_memory_fact_revisions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_memory_fact_revisions (
    tenant_id text NOT NULL,
    fact_id text NOT NULL,
    revision bigint NOT NULL,
    end_user_id bigint,
    agent_id text,
    session_id text,
    scope character varying(16) NOT NULL,
    type_id text NOT NULL,
    status character varying(24) NOT NULL,
    source_session_id text NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    valid_from_epoch_second bigint NOT NULL,
    valid_from_nano integer NOT NULL,
    valid_to_epoch_second bigint,
    valid_to_nano integer,
    expires_epoch_second bigint,
    expires_nano integer,
    superseded_fact_id text,
    fact_data bytea,
    content_revision bigint DEFAULT 1 NOT NULL,
    fact_data_digest character varying(64),
    extraction_digest character varying(64),
    recorded_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    group_binding_id text,
    subject_end_user_id bigint,
    CONSTRAINT ck_agent_runtime_memory_content_digest CHECK (((fact_data IS NULL) OR ((fact_data_digest)::text = encode(sha256(fact_data), 'hex'::text)))),
    CONSTRAINT ck_agent_runtime_memory_content_owner CHECK ((((revision = 1) AND (fact_data IS NOT NULL) AND (fact_data_digest IS NOT NULL) AND (extraction_digest IS NOT NULL) AND ((fact_data_digest)::text ~ '^[0-9a-f]{64}$'::text) AND ((extraction_digest)::text ~ '^[0-9a-f]{64}$'::text)) OR ((revision > 1) AND (fact_data IS NULL) AND (fact_data_digest IS NULL) AND (extraction_digest IS NULL)))),
    CONSTRAINT ck_agent_runtime_memory_content_revision CHECK (((content_revision = 1) AND (content_revision <= revision))),
    CONSTRAINT ck_agent_runtime_memory_revision_expires_nano CHECK (((expires_nano IS NULL) OR ((expires_nano >= 0) AND (expires_nano <= 999999999)))),
    CONSTRAINT ck_agent_runtime_memory_revision_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(fact_id) <> ''::text) AND (revision > 0))),
    CONSTRAINT ck_agent_runtime_memory_revision_optional_times CHECK ((((valid_to_epoch_second IS NULL) = (valid_to_nano IS NULL)) AND ((expires_epoch_second IS NULL) = (expires_nano IS NULL)))),
    CONSTRAINT ck_agent_runtime_memory_revision_required_nanos CHECK ((((created_nano >= 0) AND (created_nano <= 999999999)) AND ((valid_from_nano >= 0) AND (valid_from_nano <= 999999999)))),
    CONSTRAINT ck_agent_runtime_memory_revision_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DECAYED'::character varying, 'PENDING_CONFIRM'::character varying, 'REJECTED'::character varying, 'ARCHIVED'::character varying, 'FORGOTTEN'::character varying])::text[]))),
    CONSTRAINT ck_agent_runtime_memory_revision_superseded_fact CHECK (((superseded_fact_id IS NULL) OR (btrim(superseded_fact_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_memory_revision_valid_to_nano CHECK (((valid_to_nano IS NULL) OR ((valid_to_nano >= 0) AND (valid_to_nano <= 999999999)))),
    CONSTRAINT ck_memory_revision_identity CHECK (((revision > 0) AND (btrim(type_id) <> ''::text))),
    CONSTRAINT ck_memory_revision_session_source CHECK ((((scope)::text <> 'SESSION'::text) OR (session_id = source_session_id))),
    CONSTRAINT ck_memory_revision_typed_owner CHECK (((((scope)::text = 'SESSION'::text) AND (session_id IS NOT NULL) AND (btrim(session_id) <> ''::text) AND (end_user_id IS NULL) AND (agent_id IS NULL) AND (group_binding_id IS NULL) AND (subject_end_user_id IS NULL)) OR (((scope)::text = 'USER_AGENT'::text) AND (end_user_id IS NOT NULL) AND (end_user_id > 0) AND (agent_id IS NOT NULL) AND (btrim(agent_id) <> ''::text) AND (session_id IS NULL) AND (group_binding_id IS NULL) AND (subject_end_user_id IS NULL)) OR (((scope)::text = 'USER'::text) AND (end_user_id IS NOT NULL) AND (end_user_id > 0) AND (agent_id IS NULL) AND (session_id IS NULL) AND (group_binding_id IS NULL) AND (subject_end_user_id IS NULL)) OR (((scope)::text = 'GROUP'::text) AND (group_binding_id IS NOT NULL) AND (btrim(group_binding_id) <> ''::text) AND (end_user_id IS NULL) AND (agent_id IS NULL) AND (session_id IS NULL) AND (subject_end_user_id IS NULL)) OR (((scope)::text = 'GROUP_MEMBER'::text) AND (group_binding_id IS NOT NULL) AND (btrim(group_binding_id) <> ''::text) AND (subject_end_user_id IS NOT NULL) AND (subject_end_user_id > 0) AND (end_user_id IS NULL) AND (agent_id IS NULL) AND (session_id IS NULL))))
);

ALTER TABLE ONLY public.agent_runtime_memory_fact_revisions FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_memory_fact_sources; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_memory_fact_sources (
    tenant_id text NOT NULL,
    fact_id text NOT NULL,
    revision bigint NOT NULL,
    source_ordinal integer NOT NULL,
    agent_id text,
    source_session_id text NOT NULL,
    source_event_id text NOT NULL,
    source_target_kind text NOT NULL,
    source_target_id text NOT NULL,
    CONSTRAINT ck_agent_runtime_memory_source_content_owner CHECK ((revision = 1)),
    CONSTRAINT ck_memory_source_target_owner CHECK (((source_ordinal >= 0) AND (btrim(source_session_id) <> ''::text) AND (btrim(source_event_id) <> ''::text) AND (((source_target_kind = 'GROUP'::text) AND (agent_id IS NULL)) OR ((source_target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (agent_id IS NOT NULL) AND (btrim(agent_id) <> ''::text)))))
);

ALTER TABLE ONLY public.agent_runtime_memory_fact_sources FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_memory_operations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_memory_operations (
    tenant_id text NOT NULL,
    idempotency_key text NOT NULL,
    command_kind character varying(16) NOT NULL,
    command_data bytea NOT NULL,
    result_fact_id text NOT NULL,
    result_revision bigint NOT NULL,
    recorded_epoch_second bigint NOT NULL,
    recorded_nano integer NOT NULL,
    CONSTRAINT ck_agent_runtime_memory_operation_key CHECK ((btrim(idempotency_key) <> ''::text)),
    CONSTRAINT ck_agent_runtime_memory_operation_kind CHECK (((command_kind)::text = ANY ((ARRAY['CREATE'::character varying, 'SUPERSEDE'::character varying, 'TRANSITION'::character varying, 'ACCEPT_REVISION'::character varying])::text[]))),
    CONSTRAINT ck_agent_runtime_memory_operation_result CHECK (((result_revision > 0) AND ((recorded_nano >= 0) AND (recorded_nano <= 999999999))))
);

ALTER TABLE ONLY public.agent_runtime_memory_operations FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_memory_query_embeddings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_memory_query_embeddings (
    tenant_id text NOT NULL,
    operation_id character varying(64) NOT NULL,
    fingerprint character varying(64) NOT NULL,
    invocation_id text NOT NULL,
    model_id text NOT NULL,
    model_revision bigint NOT NULL,
    dimensions integer NOT NULL,
    run_data text NOT NULL,
    identity_data text NOT NULL,
    state character varying(16) NOT NULL,
    embedding public.vector,
    reason_code text,
    deadline_at timestamp with time zone NOT NULL,
    recorded_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT memory_query_embeddings_identity_ck CHECK ((((operation_id)::text ~ '^[0-9a-f]{64}$'::text) AND ((fingerprint)::text ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT memory_query_embeddings_model_ck CHECK (((btrim(model_id) <> ''::text) AND (model_revision > 0) AND (dimensions > 0))),
    CONSTRAINT memory_query_embeddings_state_ck CHECK (((state)::text = ANY ((ARRAY['RUNNING'::character varying, 'READY'::character varying, 'REJECTED'::character varying, 'UNKNOWN'::character varying])::text[]))),
    CONSTRAINT memory_query_embeddings_vector_ck CHECK (((((state)::text = 'READY'::text) AND (embedding IS NOT NULL) AND (public.vector_dims(embedding) = dimensions) AND (public.vector_norm(embedding) > (0)::double precision)) OR (((state)::text <> 'READY'::text) AND (embedding IS NULL))))
);

ALTER TABLE ONLY public.agent_runtime_memory_query_embeddings FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_memory_search_documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_memory_search_documents (
    tenant_id text NOT NULL,
    fact_id text NOT NULL,
    content_revision bigint NOT NULL,
    projection_version text NOT NULL,
    analyzer_version text NOT NULL,
    search_text text NOT NULL,
    text_digest character varying(64) NOT NULL,
    lexical_terms tsvector NOT NULL,
    run_data text NOT NULL,
    identity_data text NOT NULL,
    CONSTRAINT memory_search_documents_digest_ck CHECK (((text_digest)::text = encode(sha256(convert_to(search_text, 'UTF8'::name)), 'hex'::text))),
    CONSTRAINT memory_search_documents_identity_ck CHECK (((content_revision = 1) AND (btrim(projection_version) <> ''::text) AND (btrim(analyzer_version) <> ''::text)))
);

ALTER TABLE ONLY public.agent_runtime_memory_search_documents FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_memory_usage_projection; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_memory_usage_projection (
    tenant_id text NOT NULL,
    fact_id text NOT NULL,
    candidate_count bigint DEFAULT 0 NOT NULL,
    selected_count bigint DEFAULT 0 NOT NULL,
    injected_count bigint DEFAULT 0 NOT NULL,
    referenced_count bigint DEFAULT 0 NOT NULL,
    last_referenced_at timestamp with time zone,
    projected_decay_score numeric(8,7) DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_agent_runtime_memory_usage_counts CHECK (((candidate_count >= 0) AND (selected_count >= 0) AND (injected_count >= 0) AND (referenced_count >= 0))),
    CONSTRAINT ck_agent_runtime_memory_usage_decay CHECK (((projected_decay_score >= (0)::numeric) AND (projected_decay_score <= (1)::numeric)))
);

ALTER TABLE ONLY public.agent_runtime_memory_usage_projection FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_model_bindings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_model_bindings (
    tenant_id text NOT NULL,
    binding_id text NOT NULL,
    revision bigint NOT NULL,
    agent_id text,
    source_kind text NOT NULL,
    source_id text NOT NULL,
    content_digest text NOT NULL,
    endpoint text NOT NULL,
    model_id text NOT NULL,
    encrypted_credential text NOT NULL,
    strategy_id text NOT NULL,
    strategy_version text NOT NULL,
    response_formats text NOT NULL,
    image_policy jsonb NOT NULL,
    streaming boolean NOT NULL,
    reasoning boolean NOT NULL,
    timeout text NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    binding_format_version smallint DEFAULT 4 NOT NULL,
    provider_id character varying(64) NOT NULL,
    service_id character varying(64) NOT NULL,
    profile_version character varying(64) NOT NULL,
    protocol character varying(64) NOT NULL,
    authentication_scheme character varying(64) NOT NULL,
    dialect_version character varying(64) NOT NULL,
    system_updates_in_history text,
    tool_update_mode text,
    retain_tools_with_tool_choice_none text,
    CONSTRAINT agent_runtime_model_bindings_agent_id_check CHECK (((agent_id IS NULL) OR (length(agent_id) > 0))),
    CONSTRAINT agent_runtime_model_bindings_binding_id_check CHECK ((length(binding_id) > 0)),
    CONSTRAINT agent_runtime_model_bindings_content_digest_check CHECK ((content_digest ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT agent_runtime_model_bindings_encrypted_credential_check CHECK ((length(encrypted_credential) > 0)),
    CONSTRAINT agent_runtime_model_bindings_endpoint_check CHECK ((length(endpoint) > 0)),
    CONSTRAINT agent_runtime_model_bindings_image_policy_check CHECK ((jsonb_typeof(image_policy) = 'object'::text)),
    CONSTRAINT agent_runtime_model_bindings_model_id_check CHECK ((length(model_id) > 0)),
    CONSTRAINT agent_runtime_model_bindings_response_formats_check CHECK ((response_formats = ANY (ARRAY['TEXT'::text, 'JSON_OBJECT,TEXT'::text, 'JSON_SCHEMA,TEXT'::text, 'JSON_OBJECT,JSON_SCHEMA,TEXT'::text]))),
    CONSTRAINT agent_runtime_model_bindings_revision_check CHECK ((revision > 0)),
    CONSTRAINT agent_runtime_model_bindings_source_id_check CHECK ((length(source_id) > 0)),
    CONSTRAINT agent_runtime_model_bindings_strategy_id_check CHECK ((length(strategy_id) > 0)),
    CONSTRAINT agent_runtime_model_bindings_strategy_version_check CHECK ((length(strategy_version) > 0)),
    CONSTRAINT agent_runtime_model_bindings_tenant_id_check CHECK ((length(tenant_id) > 0)),
    CONSTRAINT agent_runtime_model_bindings_timeout_check CHECK ((length(timeout) > 0)),
    CONSTRAINT model_binding_format_structure CHECK ((((binding_format_version = 3) AND (system_updates_in_history IS NULL) AND (tool_update_mode IS NULL) AND (retain_tools_with_tool_choice_none IS NULL)) OR ((binding_format_version = 4) AND (system_updates_in_history IS NOT NULL) AND (system_updates_in_history = ANY (ARRAY['SUPPORTED'::text, 'UNSUPPORTED'::text, 'UNVERIFIED'::text])) AND (tool_update_mode IS NOT NULL) AND (tool_update_mode = ANY (ARRAY['NONE'::text, 'ADDITION_ONLY'::text, 'ADD_REMOVE'::text, 'UNVERIFIED'::text])) AND (retain_tools_with_tool_choice_none IS NOT NULL) AND (retain_tools_with_tool_choice_none = ANY (ARRAY['SUPPORTED'::text, 'UNSUPPORTED'::text, 'UNVERIFIED'::text]))))),
    CONSTRAINT model_binding_provider_profile_complete CHECK (((length((provider_id)::text) > 0) AND (length((service_id)::text) > 0) AND (length((profile_version)::text) > 0) AND (length((protocol)::text) > 0) AND (length((authentication_scheme)::text) > 0) AND (length((dialect_version)::text) > 0)))
);

ALTER TABLE ONLY public.agent_runtime_model_bindings FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_run_mutation_archives; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_run_mutation_archives (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    run_id character varying(160) NOT NULL,
    mutation_key text NOT NULL,
    mutation_kind character varying(16) NOT NULL,
    command_fingerprint character varying(64) NOT NULL,
    output_revision bigint NOT NULL,
    output_status character varying(16) NOT NULL,
    output_fingerprint character varying(64) NOT NULL,
    output_data text NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    owner_session_id text NOT NULL,
    owner_target_kind text NOT NULL,
    owner_target_id text NOT NULL,
    owner_end_user_id bigint NOT NULL,
    source_session_revision bigint NOT NULL,
    closed_epoch_second bigint NOT NULL,
    closed_nano integer NOT NULL,
    source_run_id text,
    source_run_revision bigint,
    source_run_updated_epoch_second bigint,
    source_run_updated_nano integer,
    original_row_sha256 text NOT NULL,
    archived_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    output_data_sha256 text NOT NULL,
    CONSTRAINT agent_runtime_run_mutation_archiv_source_session_revision_check CHECK ((source_session_revision > 0)),
    CONSTRAINT agent_runtime_run_mutation_archives_closed_nano_check CHECK (((closed_nano >= 0) AND (closed_nano <= 999999999))),
    CONSTRAINT agent_runtime_run_mutation_archives_original_row_sha256_check CHECK ((original_row_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_run_mutation_archives_output_data_sha256_check CHECK ((output_data_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_run_mutation_archives_owner_end_user_id_check CHECK ((owner_end_user_id > 0)),
    CONSTRAINT agent_runtime_run_mutation_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (end_user_id > 0))),
    CONSTRAINT ck_agent_runtime_run_mutation_identity CHECK (((btrim(mutation_key) <> ''::text) AND ((mutation_kind)::text = ANY ((ARRAY['RELEASE'::character varying, 'CANCEL'::character varying, 'PROGRESS'::character varying, 'TRANSITION'::character varying])::text[])) AND ((command_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND ((output_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (output_revision > 0) AND ((output_status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'SUSPENDED'::character varying, 'RESUMING'::character varying, 'COMPLETED'::character varying, 'TERMINATED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[])) AND (btrim(output_data) <> ''::text)))
);

ALTER TABLE ONLY public.agent_runtime_run_mutation_archives FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_run_mutations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_run_mutations (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    run_id character varying(160) NOT NULL,
    mutation_key text NOT NULL,
    mutation_kind character varying(16) NOT NULL,
    command_fingerprint character varying(64) NOT NULL,
    output_revision bigint NOT NULL,
    output_status character varying(16) NOT NULL,
    output_fingerprint character varying(64) NOT NULL,
    output_data text NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    CONSTRAINT agent_runtime_run_mutation_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (end_user_id > 0))),
    CONSTRAINT ck_agent_runtime_run_mutation_identity CHECK (((btrim(mutation_key) <> ''::text) AND ((mutation_kind)::text = ANY ((ARRAY['RELEASE'::character varying, 'CANCEL'::character varying, 'PROGRESS'::character varying, 'TRANSITION'::character varying])::text[])) AND ((command_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND ((output_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (output_revision > 0) AND ((output_status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'SUSPENDED'::character varying, 'RESUMING'::character varying, 'COMPLETED'::character varying, 'TERMINATED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[])) AND (btrim(output_data) <> ''::text)))
);

ALTER TABLE ONLY public.agent_runtime_run_mutations FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_run_publication_mutation_archives; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_run_publication_mutation_archives (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    source_id character varying(160) NOT NULL,
    event_id character varying(160) NOT NULL,
    mutation_key text NOT NULL,
    mutation_kind character varying(16) NOT NULL,
    command_fingerprint character varying(64) NOT NULL,
    output_revision bigint NOT NULL,
    output_status character varying(16) NOT NULL,
    output_fingerprint character varying(64) NOT NULL,
    output_data text NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    owner_session_id text NOT NULL,
    owner_target_kind text NOT NULL,
    owner_target_id text NOT NULL,
    owner_end_user_id bigint NOT NULL,
    source_session_revision bigint NOT NULL,
    closed_epoch_second bigint NOT NULL,
    closed_nano integer NOT NULL,
    source_run_id text,
    source_run_revision bigint,
    source_run_updated_epoch_second bigint,
    source_run_updated_nano integer,
    original_row_sha256 text NOT NULL,
    archived_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    output_data_sha256 text NOT NULL,
    CONSTRAINT agent_runtime_run_publication_mut_source_session_revision_check CHECK ((source_session_revision > 0)),
    CONSTRAINT agent_runtime_run_publication_mutatio_original_row_sha256_check CHECK ((original_row_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_run_publication_mutation__owner_end_user_id_check CHECK ((owner_end_user_id > 0)),
    CONSTRAINT agent_runtime_run_publication_mutation_archiv_closed_nano_check CHECK (((closed_nano >= 0) AND (closed_nano <= 999999999))),
    CONSTRAINT agent_runtime_run_publication_mutation_output_data_sha256_check CHECK ((output_data_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT ck_agent_runtime_run_publication_mutation_identity CHECK (((btrim(session_id) <> ''::text) AND (btrim(mutation_key) <> ''::text) AND ((mutation_kind)::text = ANY ((ARRAY['ACKNOWLEDGE'::character varying, 'RETRY'::character varying])::text[])) AND ((command_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND ((output_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (output_revision > 1) AND ((output_status)::text = ANY ((ARRAY['PENDING'::character varying, 'ACKNOWLEDGED'::character varying])::text[])) AND (btrim(output_data) <> ''::text)))
);

ALTER TABLE ONLY public.agent_runtime_run_publication_mutation_archives FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_run_publication_mutations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_run_publication_mutations (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    source_id character varying(160) NOT NULL,
    event_id character varying(160) NOT NULL,
    mutation_key text NOT NULL,
    mutation_kind character varying(16) NOT NULL,
    command_fingerprint character varying(64) NOT NULL,
    output_revision bigint NOT NULL,
    output_status character varying(16) NOT NULL,
    output_fingerprint character varying(64) NOT NULL,
    output_data text NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT ck_agent_runtime_run_publication_mutation_identity CHECK (((btrim(session_id) <> ''::text) AND (btrim(mutation_key) <> ''::text) AND ((mutation_kind)::text = ANY ((ARRAY['ACKNOWLEDGE'::character varying, 'RETRY'::character varying])::text[])) AND ((command_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND ((output_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (output_revision > 1) AND ((output_status)::text = ANY ((ARRAY['PENDING'::character varying, 'ACKNOWLEDGED'::character varying])::text[])) AND (btrim(output_data) <> ''::text)))
);

ALTER TABLE ONLY public.agent_runtime_run_publication_mutations FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_run_record_archives; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_run_record_archives (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    run_id character varying(160) NOT NULL,
    causation_input_id character varying(160) NOT NULL,
    status character varying(16) NOT NULL,
    run_revision bigint NOT NULL,
    lease_claim_id text,
    lease_worker_id text,
    lease_fencing_token bigint,
    lease_until_epoch_second bigint,
    lease_until_nano integer,
    last_requested_epoch_second bigint,
    last_requested_nano integer,
    cancel_request_id character varying(160),
    cancel_requested_epoch_second bigint,
    cancel_requested_nano integer,
    record_data text NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    updated_epoch_second bigint NOT NULL,
    updated_nano integer NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    owner_session_id text NOT NULL,
    owner_target_kind text NOT NULL,
    owner_target_id text NOT NULL,
    owner_end_user_id bigint NOT NULL,
    source_session_revision bigint NOT NULL,
    closed_epoch_second bigint NOT NULL,
    closed_nano integer NOT NULL,
    source_run_id text,
    source_run_revision bigint,
    source_run_updated_epoch_second bigint,
    source_run_updated_nano integer,
    original_row_sha256 text NOT NULL,
    archived_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    record_data_sha256 text NOT NULL,
    CONSTRAINT agent_runtime_run_record_archives_closed_nano_check CHECK (((closed_nano >= 0) AND (closed_nano <= 999999999))),
    CONSTRAINT agent_runtime_run_record_archives_original_row_sha256_check CHECK ((original_row_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_run_record_archives_owner_end_user_id_check CHECK ((owner_end_user_id > 0)),
    CONSTRAINT agent_runtime_run_record_archives_record_data_sha256_check CHECK ((record_data_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_run_record_archives_source_session_revision_check CHECK ((source_session_revision > 0)),
    CONSTRAINT agent_runtime_runs_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_run_cancel CHECK ((((cancel_request_id IS NULL) AND (cancel_requested_epoch_second IS NULL) AND (cancel_requested_nano IS NULL)) OR ((btrim((cancel_request_id)::text) <> ''::text) AND (cancel_requested_epoch_second IS NOT NULL) AND ((cancel_requested_nano >= 0) AND (cancel_requested_nano <= 999999999))))),
    CONSTRAINT ck_agent_runtime_run_codec CHECK ((btrim(record_data) <> ''::text)),
    CONSTRAINT ck_agent_runtime_run_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (btrim((run_id)::text) <> ''::text) AND (btrim((causation_input_id)::text) <> ''::text) AND (run_revision > 0))),
    CONSTRAINT ck_agent_runtime_run_last_requested CHECK ((((last_requested_epoch_second IS NULL) AND (last_requested_nano IS NULL)) OR ((last_requested_epoch_second IS NOT NULL) AND ((last_requested_nano >= 0) AND (last_requested_nano <= 999999999))))),
    CONSTRAINT ck_agent_runtime_run_lease CHECK (((((lease_claim_id IS NULL) AND (lease_worker_id IS NULL) AND (lease_fencing_token IS NULL) AND (lease_until_epoch_second IS NULL) AND (lease_until_nano IS NULL)) OR ((btrim(lease_claim_id) <> ''::text) AND (btrim(lease_worker_id) <> ''::text) AND (lease_fencing_token > 0) AND (lease_until_epoch_second IS NOT NULL) AND ((lease_until_nano >= 0) AND (lease_until_nano <= 999999999)))) AND (((status)::text <> ALL ((ARRAY['COMPLETED'::character varying, 'TERMINATED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[])) OR (lease_claim_id IS NULL)))),
    CONSTRAINT ck_agent_runtime_run_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'SUSPENDED'::character varying, 'RESUMING'::character varying, 'COMPLETED'::character varying, 'TERMINATED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT ck_agent_runtime_run_times CHECK ((((created_nano >= 0) AND (created_nano <= 999999999)) AND ((updated_nano >= 0) AND (updated_nano <= 999999999)) AND (ROW(updated_epoch_second, updated_nano) >= ROW(created_epoch_second, created_nano))))
);

ALTER TABLE ONLY public.agent_runtime_run_record_archives FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_run_state_publications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_run_state_publications (
    tenant_id text NOT NULL,
    source_kind character varying(16) NOT NULL,
    source_id character varying(160) NOT NULL,
    event_id character varying(160) NOT NULL,
    source_revision bigint NOT NULL,
    publication_revision bigint NOT NULL,
    available_epoch_second bigint NOT NULL,
    available_nano integer NOT NULL,
    status character varying(16) NOT NULL,
    attempts integer NOT NULL,
    lease_claim_id text,
    lease_worker_id text,
    lease_fencing_token bigint,
    lease_until_epoch_second bigint,
    lease_until_nano integer,
    projection_ref text,
    failure_data text,
    publication_data text NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    updated_epoch_second bigint NOT NULL,
    updated_nano integer NOT NULL,
    mutation_result_data text,
    CONSTRAINT ck_agent_runtime_run_state_publication_codec CHECK ((btrim(publication_data) <> ''::text)),
    CONSTRAINT ck_agent_runtime_run_state_publication_identity CHECK ((((source_kind)::text = 'run'::text) AND (btrim((source_id)::text) <> ''::text) AND (btrim((event_id)::text) <> ''::text) AND (source_revision > 0) AND (publication_revision > 0) AND ((available_nano >= 0) AND (available_nano <= 999999999)) AND (attempts >= 0))),
    CONSTRAINT ck_agent_runtime_run_state_publication_lease CHECK (((((status)::text = 'CLAIMED'::text) = ((lease_claim_id IS NOT NULL) AND (lease_worker_id IS NOT NULL) AND (lease_fencing_token IS NOT NULL) AND (lease_until_epoch_second IS NOT NULL) AND (lease_until_nano IS NOT NULL))) AND ((lease_claim_id IS NULL) OR ((btrim(lease_claim_id) <> ''::text) AND (btrim(lease_worker_id) <> ''::text) AND (lease_fencing_token > 0) AND ((lease_until_nano >= 0) AND (lease_until_nano <= 999999999)))))),
    CONSTRAINT ck_agent_runtime_run_state_publication_projection CHECK (((((status)::text = 'ACKNOWLEDGED'::text) = (projection_ref IS NOT NULL)) AND ((projection_ref IS NULL) OR (btrim(projection_ref) <> ''::text)))),
    CONSTRAINT ck_agent_runtime_run_state_publication_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'CLAIMED'::character varying, 'ACKNOWLEDGED'::character varying])::text[]))),
    CONSTRAINT ck_agent_runtime_run_state_publication_times CHECK ((((created_nano >= 0) AND (created_nano <= 999999999)) AND ((updated_nano >= 0) AND (updated_nano <= 999999999)) AND (ROW(updated_epoch_second, updated_nano) >= ROW(created_epoch_second, created_nano))))
);

ALTER TABLE ONLY public.agent_runtime_run_state_publications FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_runs (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    run_id character varying(160) NOT NULL,
    causation_input_id character varying(160) NOT NULL,
    status character varying(16) NOT NULL,
    run_revision bigint NOT NULL,
    lease_claim_id text,
    lease_worker_id text,
    lease_fencing_token bigint,
    lease_until_epoch_second bigint,
    lease_until_nano integer,
    last_requested_epoch_second bigint,
    last_requested_nano integer,
    cancel_request_id character varying(160),
    cancel_requested_epoch_second bigint,
    cancel_requested_nano integer,
    record_data text NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    updated_epoch_second bigint NOT NULL,
    updated_nano integer NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    CONSTRAINT agent_runtime_runs_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_run_cancel CHECK ((((cancel_request_id IS NULL) AND (cancel_requested_epoch_second IS NULL) AND (cancel_requested_nano IS NULL)) OR ((btrim((cancel_request_id)::text) <> ''::text) AND (cancel_requested_epoch_second IS NOT NULL) AND ((cancel_requested_nano >= 0) AND (cancel_requested_nano <= 999999999))))),
    CONSTRAINT ck_agent_runtime_run_codec CHECK ((btrim(record_data) <> ''::text)),
    CONSTRAINT ck_agent_runtime_run_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (btrim((run_id)::text) <> ''::text) AND (btrim((causation_input_id)::text) <> ''::text) AND (run_revision > 0))),
    CONSTRAINT ck_agent_runtime_run_last_requested CHECK ((((last_requested_epoch_second IS NULL) AND (last_requested_nano IS NULL)) OR ((last_requested_epoch_second IS NOT NULL) AND ((last_requested_nano >= 0) AND (last_requested_nano <= 999999999))))),
    CONSTRAINT ck_agent_runtime_run_lease CHECK (((((lease_claim_id IS NULL) AND (lease_worker_id IS NULL) AND (lease_fencing_token IS NULL) AND (lease_until_epoch_second IS NULL) AND (lease_until_nano IS NULL)) OR ((btrim(lease_claim_id) <> ''::text) AND (btrim(lease_worker_id) <> ''::text) AND (lease_fencing_token > 0) AND (lease_until_epoch_second IS NOT NULL) AND ((lease_until_nano >= 0) AND (lease_until_nano <= 999999999)))) AND (((status)::text <> ALL ((ARRAY['COMPLETED'::character varying, 'TERMINATED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[])) OR (lease_claim_id IS NULL)))),
    CONSTRAINT ck_agent_runtime_run_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'SUSPENDED'::character varying, 'RESUMING'::character varying, 'COMPLETED'::character varying, 'TERMINATED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT ck_agent_runtime_run_times CHECK ((((created_nano >= 0) AND (created_nano <= 999999999)) AND ((updated_nano >= 0) AND (updated_nano <= 999999999)) AND (ROW(updated_epoch_second, updated_nano) >= ROW(created_epoch_second, created_nano))))
);

ALTER TABLE ONLY public.agent_runtime_runs FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_action_preparations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_action_preparations (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    journal_ref text NOT NULL,
    preparation_kind text NOT NULL,
    key_data text NOT NULL,
    prepared_data text NOT NULL,
    frozen_at_epoch_second bigint NOT NULL,
    frozen_at_nano integer NOT NULL,
    CONSTRAINT agent_runtime_session_action_preparation_preparation_kind_check CHECK ((preparation_kind = ANY (ARRAY['TOOL'::text, 'WORKFLOW'::text, 'KNOWLEDGE'::text]))),
    CONSTRAINT agent_runtime_session_action_preparations_frozen_at_nano_check CHECK (((frozen_at_nano >= 0) AND (frozen_at_nano <= 999999999))),
    CONSTRAINT agent_runtime_session_action_preparations_journal_ref_check CHECK ((btrim(journal_ref) <> ''::text)),
    CONSTRAINT agent_runtime_session_action_preparations_key_data_check CHECK ((btrim(key_data) <> ''::text)),
    CONSTRAINT agent_runtime_session_action_preparations_prepared_data_check CHECK ((btrim(prepared_data) <> ''::text))
);

ALTER TABLE ONLY public.agent_runtime_session_action_preparations FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_action_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_action_requests (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    journal_ref text NOT NULL,
    request_kind text NOT NULL,
    key_data text NOT NULL,
    request_data text NOT NULL,
    recorded_at_epoch_second bigint NOT NULL,
    recorded_at_nano integer NOT NULL,
    loop_id text,
    iteration integer,
    action_id text,
    CONSTRAINT agent_runtime_session_action_requests_journal_ref_check CHECK ((btrim(journal_ref) <> ''::text)),
    CONSTRAINT agent_runtime_session_action_requests_key_data_check CHECK ((btrim(key_data) <> ''::text)),
    CONSTRAINT agent_runtime_session_action_requests_recorded_at_nano_check CHECK (((recorded_at_nano >= 0) AND (recorded_at_nano <= 999999999))),
    CONSTRAINT agent_runtime_session_action_requests_request_data_check CHECK ((btrim(request_data) <> ''::text)),
    CONSTRAINT agent_runtime_session_action_requests_request_kind_check CHECK ((request_kind = ANY (ARRAY['TOOL'::text, 'WORKFLOW'::text, 'KNOWLEDGE'::text, 'DELEGATION'::text]))),
    CONSTRAINT ck_runtime_action_request_observation_identity CHECK ((((loop_id IS NULL) AND (iteration IS NULL) AND (action_id IS NULL)) OR ((loop_id IS NOT NULL) AND (iteration IS NOT NULL) AND (action_id IS NOT NULL) AND (btrim(loop_id) <> ''::text) AND (iteration > 0) AND (btrim(action_id) <> ''::text))))
);

ALTER TABLE ONLY public.agent_runtime_session_action_requests FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_admission_prepare_archives; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_admission_prepare_archives (
    tenant_id text NOT NULL,
    agent_id text NOT NULL,
    session_id text NOT NULL,
    end_user_id bigint NOT NULL,
    input_id character varying(160) NOT NULL,
    request_fingerprint character varying(64) NOT NULL,
    lifecycle_policy character varying(40) NOT NULL,
    lifecycle_proof text,
    command_fingerprint character varying(64) NOT NULL,
    command_data text NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    owner_session_id text NOT NULL,
    owner_target_kind text NOT NULL,
    owner_target_id text NOT NULL,
    owner_end_user_id bigint NOT NULL,
    source_session_revision bigint NOT NULL,
    closed_epoch_second bigint NOT NULL,
    closed_nano integer NOT NULL,
    source_run_id text,
    source_run_revision bigint,
    source_run_updated_epoch_second bigint,
    source_run_updated_nano integer,
    original_row_sha256 text NOT NULL,
    archived_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    command_data_sha256 text NOT NULL,
    CONSTRAINT agent_runtime_session_admission_p_source_session_revision_check CHECK ((source_session_revision > 0)),
    CONSTRAINT agent_runtime_session_admission_prepa_command_data_sha256_check CHECK ((command_data_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_session_admission_prepa_original_row_sha256_check CHECK ((original_row_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_session_admission_prepare_archi_closed_nano_check CHECK (((closed_nano >= 0) AND (closed_nano <= 999999999))),
    CONSTRAINT agent_runtime_session_admission_prepare_owner_end_user_id_check CHECK ((owner_end_user_id > 0)),
    CONSTRAINT agent_runtime_session_admission_prepares_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_admission_command CHECK ((((command_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (btrim(command_data) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_admission_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (end_user_id > 0) AND (btrim((input_id)::text) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_admission_lifecycle CHECK (((((lifecycle_policy)::text = 'ACTIVE_ONLY'::text) AND (lifecycle_proof IS NULL)) OR (((lifecycle_policy)::text = 'CLOSED_EXISTING_RUN_RECOVERY'::text) AND (lifecycle_proof IS NOT NULL) AND (btrim(lifecycle_proof) <> ''::text)))),
    CONSTRAINT ck_agent_runtime_session_admission_request CHECK (((request_fingerprint)::text ~ '^[0-9a-f]{64}$'::text))
);

ALTER TABLE ONLY public.agent_runtime_session_admission_prepare_archives FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_admission_prepares; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_admission_prepares (
    tenant_id text NOT NULL,
    agent_id text NOT NULL,
    session_id text NOT NULL,
    end_user_id bigint NOT NULL,
    input_id character varying(160) NOT NULL,
    request_fingerprint character varying(64) NOT NULL,
    lifecycle_policy character varying(40) NOT NULL,
    lifecycle_proof text,
    command_fingerprint character varying(64) NOT NULL,
    command_data text NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    CONSTRAINT agent_runtime_session_admission_prepares_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_admission_command CHECK ((((command_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (btrim(command_data) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_admission_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (end_user_id > 0) AND (btrim((input_id)::text) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_admission_lifecycle CHECK (((((lifecycle_policy)::text = 'ACTIVE_ONLY'::text) AND (lifecycle_proof IS NULL)) OR (((lifecycle_policy)::text = 'CLOSED_EXISTING_RUN_RECOVERY'::text) AND (lifecycle_proof IS NOT NULL) AND (btrim(lifecycle_proof) <> ''::text)))),
    CONSTRAINT ck_agent_runtime_session_admission_request CHECK (((request_fingerprint)::text ~ '^[0-9a-f]{64}$'::text))
);

ALTER TABLE ONLY public.agent_runtime_session_admission_prepares FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_checkpoint_archive_body_refs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_checkpoint_archive_body_refs (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    checkpoint_hash text NOT NULL,
    body_hash character varying(64) NOT NULL
);

ALTER TABLE ONLY public.agent_runtime_session_checkpoint_archive_body_refs FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_checkpoint_archives; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_checkpoint_archives (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    content_hash text NOT NULL,
    identity_data text NOT NULL,
    schema_id text NOT NULL,
    payload_data bytea NOT NULL,
    recorded_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    owner_session_id text NOT NULL,
    owner_target_kind text NOT NULL,
    owner_target_id text NOT NULL,
    owner_end_user_id bigint NOT NULL,
    source_session_revision bigint NOT NULL,
    closed_epoch_second bigint NOT NULL,
    closed_nano integer NOT NULL,
    source_run_id text,
    source_run_revision bigint,
    source_run_updated_epoch_second bigint,
    source_run_updated_nano integer,
    original_row_sha256 text NOT NULL,
    archived_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    payload_data_sha256 text NOT NULL,
    CONSTRAINT agent_runtime_session_checkpoint_arch_original_row_sha256_check CHECK ((original_row_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_session_checkpoint_arch_payload_data_sha256_check CHECK ((payload_data_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_session_checkpoint_archiv_owner_end_user_id_check CHECK ((owner_end_user_id > 0)),
    CONSTRAINT agent_runtime_session_checkpoint_archives_closed_nano_check CHECK (((closed_nano >= 0) AND (closed_nano <= 999999999))),
    CONSTRAINT agent_runtime_session_checkpoint_source_session_revision_check1 CHECK ((source_session_revision > 0)),
    CONSTRAINT agent_runtime_session_checkpoints_content_hash_check CHECK ((content_hash ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_session_checkpoints_identity_data_check CHECK ((btrim(identity_data) <> ''::text)),
    CONSTRAINT agent_runtime_session_checkpoints_payload_data_check CHECK ((octet_length(payload_data) > 0)),
    CONSTRAINT agent_runtime_session_checkpoints_run_id_check CHECK ((btrim(run_id) <> ''::text)),
    CONSTRAINT agent_runtime_session_checkpoints_schema_id_check CHECK ((btrim(schema_id) <> ''::text)),
    CONSTRAINT agent_runtime_session_checkpoints_tenant_id_check CHECK ((btrim(tenant_id) <> ''::text))
);

ALTER TABLE ONLY public.agent_runtime_session_checkpoint_archives FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_checkpoint_bodies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_checkpoint_bodies (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    content_hash character varying(64) NOT NULL,
    identity_data text NOT NULL,
    schema_id character varying(128) NOT NULL,
    payload_data bytea NOT NULL,
    recorded_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT session_checkpoint_bodies_hash CHECK (((content_hash)::text ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT session_checkpoint_bodies_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(run_id) <> ''::text) AND (btrim(identity_data) <> ''::text) AND (btrim((schema_id)::text) <> ''::text))),
    CONSTRAINT session_checkpoint_bodies_payload CHECK ((octet_length(payload_data) > 0))
);

ALTER TABLE ONLY public.agent_runtime_session_checkpoint_bodies FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_checkpoint_body_archives; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_checkpoint_body_archives (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    content_hash character varying(64) NOT NULL,
    identity_data text NOT NULL,
    schema_id character varying(128) NOT NULL,
    payload_data bytea NOT NULL,
    recorded_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    owner_session_id text NOT NULL,
    owner_target_kind text NOT NULL,
    owner_target_id text NOT NULL,
    owner_end_user_id bigint NOT NULL,
    source_session_revision bigint NOT NULL,
    closed_epoch_second bigint NOT NULL,
    closed_nano integer NOT NULL,
    source_run_id text,
    source_run_revision bigint,
    source_run_updated_epoch_second bigint,
    source_run_updated_nano integer,
    original_row_sha256 text NOT NULL,
    archived_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    payload_data_sha256 text NOT NULL,
    CONSTRAINT agent_runtime_session_checkpoint__source_session_revision_check CHECK ((source_session_revision > 0)),
    CONSTRAINT agent_runtime_session_checkpoint_body_a_owner_end_user_id_check CHECK ((owner_end_user_id > 0)),
    CONSTRAINT agent_runtime_session_checkpoint_body_archive_closed_nano_check CHECK (((closed_nano >= 0) AND (closed_nano <= 999999999))),
    CONSTRAINT agent_runtime_session_checkpoint_body_original_row_sha256_check CHECK ((original_row_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_session_checkpoint_body_payload_data_sha256_check CHECK ((payload_data_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT session_checkpoint_bodies_hash CHECK (((content_hash)::text ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT session_checkpoint_bodies_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(run_id) <> ''::text) AND (btrim(identity_data) <> ''::text) AND (btrim((schema_id)::text) <> ''::text))),
    CONSTRAINT session_checkpoint_bodies_payload CHECK ((octet_length(payload_data) > 0))
);

ALTER TABLE ONLY public.agent_runtime_session_checkpoint_body_archives FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_checkpoint_body_refs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_checkpoint_body_refs (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    checkpoint_hash character varying(64) NOT NULL,
    body_hash character varying(64) NOT NULL
);

ALTER TABLE ONLY public.agent_runtime_session_checkpoint_body_refs FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_checkpoints; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_checkpoints (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    content_hash text NOT NULL,
    identity_data text NOT NULL,
    schema_id text NOT NULL,
    payload_data bytea NOT NULL,
    recorded_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT agent_runtime_session_checkpoints_content_hash_check CHECK ((content_hash ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_session_checkpoints_identity_data_check CHECK ((btrim(identity_data) <> ''::text)),
    CONSTRAINT agent_runtime_session_checkpoints_payload_data_check CHECK ((octet_length(payload_data) > 0)),
    CONSTRAINT agent_runtime_session_checkpoints_run_id_check CHECK ((btrim(run_id) <> ''::text)),
    CONSTRAINT agent_runtime_session_checkpoints_schema_id_check CHECK ((btrim(schema_id) <> ''::text)),
    CONSTRAINT agent_runtime_session_checkpoints_tenant_id_check CHECK ((btrim(tenant_id) <> ''::text))
);

ALTER TABLE ONLY public.agent_runtime_session_checkpoints FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_commit_journal_archives; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_commit_journal_archives (
    tenant_id text NOT NULL,
    run_id character varying(160) NOT NULL,
    operation_hash character varying(64) NOT NULL,
    agent_id text NOT NULL,
    session_id text NOT NULL,
    end_user_id bigint NOT NULL,
    causation_input_id character varying(160) NOT NULL,
    operation_ref text NOT NULL,
    expected_status character varying(16) NOT NULL,
    target_status character varying(16) NOT NULL,
    expected_revision bigint NOT NULL,
    command_fingerprint character varying(64) NOT NULL,
    command_data text NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    owner_session_id text NOT NULL,
    owner_target_kind text NOT NULL,
    owner_target_id text NOT NULL,
    owner_end_user_id bigint NOT NULL,
    source_session_revision bigint NOT NULL,
    closed_epoch_second bigint NOT NULL,
    closed_nano integer NOT NULL,
    source_run_id text,
    source_run_revision bigint,
    source_run_updated_epoch_second bigint,
    source_run_updated_nano integer,
    original_row_sha256 text NOT NULL,
    archived_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    command_data_sha256 text NOT NULL,
    CONSTRAINT agent_runtime_session_commit_jour_source_session_revision_check CHECK ((source_session_revision > 0)),
    CONSTRAINT agent_runtime_session_commit_journal__command_data_sha256_check CHECK ((command_data_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_session_commit_journal__original_row_sha256_check CHECK ((original_row_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_session_commit_journal_ar_owner_end_user_id_check CHECK ((owner_end_user_id > 0)),
    CONSTRAINT agent_runtime_session_commit_journal_archives_closed_nano_check CHECK (((closed_nano >= 0) AND (closed_nano <= 999999999))),
    CONSTRAINT agent_runtime_session_commit_journals_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_commit_command CHECK ((((command_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (btrim(command_data) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_commit_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (end_user_id > 0) AND (btrim((run_id)::text) <> ''::text) AND (btrim((causation_input_id)::text) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_commit_operation CHECK ((((operation_hash)::text ~ '^[0-9a-f]{64}$'::text) AND (btrim(operation_ref) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_commit_revision CHECK ((expected_revision > 0)),
    CONSTRAINT ck_agent_runtime_session_commit_status CHECK ((((expected_status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'SUSPENDED'::character varying, 'RESUMING'::character varying, 'COMPLETED'::character varying, 'TERMINATED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[])) AND ((target_status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'SUSPENDED'::character varying, 'RESUMING'::character varying, 'COMPLETED'::character varying, 'TERMINATED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))))
);

ALTER TABLE ONLY public.agent_runtime_session_commit_journal_archives FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_commit_journals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_commit_journals (
    tenant_id text NOT NULL,
    run_id character varying(160) NOT NULL,
    operation_hash character varying(64) NOT NULL,
    agent_id text NOT NULL,
    session_id text NOT NULL,
    end_user_id bigint NOT NULL,
    causation_input_id character varying(160) NOT NULL,
    operation_ref text NOT NULL,
    expected_status character varying(16) NOT NULL,
    target_status character varying(16) NOT NULL,
    expected_revision bigint NOT NULL,
    command_fingerprint character varying(64) NOT NULL,
    command_data text NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    CONSTRAINT agent_runtime_session_commit_journals_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_commit_command CHECK ((((command_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (btrim(command_data) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_commit_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (end_user_id > 0) AND (btrim((run_id)::text) <> ''::text) AND (btrim((causation_input_id)::text) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_commit_operation CHECK ((((operation_hash)::text ~ '^[0-9a-f]{64}$'::text) AND (btrim(operation_ref) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_commit_revision CHECK ((expected_revision > 0)),
    CONSTRAINT ck_agent_runtime_session_commit_status CHECK ((((expected_status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'SUSPENDED'::character varying, 'RESUMING'::character varying, 'COMPLETED'::character varying, 'TERMINATED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[])) AND ((target_status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'SUSPENDED'::character varying, 'RESUMING'::character varying, 'COMPLETED'::character varying, 'TERMINATED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))))
);

ALTER TABLE ONLY public.agent_runtime_session_commit_journals FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_conflict_audits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_conflict_audits (
    tenant_id text NOT NULL,
    audit_ref text NOT NULL,
    resource_id_hash character varying(64) NOT NULL,
    conflict_kind character varying(16) NOT NULL,
    current_revision bigint NOT NULL,
    stored_fingerprint character varying(64) NOT NULL,
    requested_fingerprint character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT ck_agent_runtime_session_conflict_audit_facts CHECK ((((conflict_kind)::text = ANY ((ARRAY['CREATE'::character varying, 'CAS'::character varying])::text[])) AND (current_revision > 0) AND ((stored_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND ((requested_fingerprint)::text ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT ck_agent_runtime_session_conflict_audit_identity CHECK (((btrim(tenant_id) <> ''::text) AND (audit_ref ~ '^session-store-conflict:sha256:[0-9a-f]{64}$'::text) AND ((resource_id_hash)::text ~ '^[0-9a-f]{64}$'::text)))
);

ALTER TABLE ONLY public.agent_runtime_session_conflict_audits FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_event_bodies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_event_bodies (
    tenant_id text NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    session_id text NOT NULL,
    end_user_id bigint,
    content_hash character varying(64) NOT NULL,
    canonical_content text NOT NULL,
    recorded_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    subject_kind text GENERATED ALWAYS AS (
CASE
    WHEN (target_kind = 'GROUP'::text) THEN 'GROUP'::text
    ELSE 'USER'::text
END) STORED NOT NULL,
    subject_id text GENERATED ALWAYS AS (
CASE
    WHEN (target_kind = 'GROUP'::text) THEN target_id
    ELSE (end_user_id)::text
END) STORED NOT NULL,
    CONSTRAINT session_event_bodies_content CHECK ((("left"(canonical_content, 1) = '"'::text) AND ("right"(canonical_content, 1) = '"'::text))),
    CONSTRAINT session_event_bodies_hash CHECK (((content_hash)::text ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT session_event_bodies_typed_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(target_id) <> ''::text) AND (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (end_user_id IS NOT NULL) AND (end_user_id > 0)) OR ((target_kind = 'GROUP'::text) AND (end_user_id IS NULL)))))
);

ALTER TABLE ONLY public.agent_runtime_session_event_bodies FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_event_body_refs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_event_body_refs (
    tenant_id text NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    session_id text NOT NULL,
    event_id text NOT NULL,
    content_hash character varying(64) NOT NULL,
    original_payload_digest character varying(64) NOT NULL,
    CONSTRAINT session_event_body_refs_digests CHECK ((((content_hash)::text ~ '^[a-f0-9]{64}$'::text) AND ((original_payload_digest)::text ~ '^[a-f0-9]{64}$'::text)))
);

ALTER TABLE ONLY public.agent_runtime_session_event_body_refs FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_event_payload_archives; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_event_payload_archives (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    session_sequence bigint NOT NULL,
    input_id text NOT NULL,
    run_id text,
    execution_id text,
    action_id text,
    delegation_id text,
    event_id text NOT NULL,
    event_fingerprint text NOT NULL,
    event_type text NOT NULL,
    event_version text NOT NULL,
    trace_id text NOT NULL,
    span_id text NOT NULL,
    root_input_id text NOT NULL,
    caused_by_event_id text,
    payload_schema_id text NOT NULL,
    payload_canonical_json text NOT NULL,
    occurred_at_epoch_second bigint NOT NULL,
    occurred_at_nano integer NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    owner_session_id text NOT NULL,
    owner_target_kind text NOT NULL,
    owner_target_id text NOT NULL,
    owner_end_user_id bigint NOT NULL,
    source_session_revision bigint NOT NULL,
    closed_epoch_second bigint NOT NULL,
    closed_nano integer NOT NULL,
    source_run_id text,
    source_run_revision bigint,
    source_run_updated_epoch_second bigint,
    source_run_updated_nano integer,
    original_row_sha256 text NOT NULL,
    archived_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    payload_canonical_json_sha256 text NOT NULL,
    original_payload_canonical_json text NOT NULL,
    original_event_digest text NOT NULL,
    CONSTRAINT agent_runtime_session_event__payload_canonical_json_sha25_check CHECK ((payload_canonical_json_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_session_event_paylo_source_session_revision_check CHECK ((source_session_revision > 0)),
    CONSTRAINT agent_runtime_session_event_payload_a_original_row_sha256_check CHECK ((original_row_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_session_event_payload_arc_owner_end_user_id_check CHECK ((owner_end_user_id > 0)),
    CONSTRAINT agent_runtime_session_event_payload_archives_closed_nano_check CHECK (((closed_nano >= 0) AND (closed_nano <= 999999999))),
    CONSTRAINT agent_runtime_session_event_payload_original_event_digest_check CHECK ((original_event_digest ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_session_events_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_event_action CHECK (((action_id IS NULL) OR (btrim(action_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_event_delegation CHECK (((delegation_id IS NULL) OR (btrim(delegation_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_event_execution CHECK (((execution_id IS NULL) OR (btrim(execution_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_event_identity_text CHECK (((btrim(event_id) <> ''::text) AND (btrim(event_fingerprint) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_event_input CHECK ((btrim(input_id) <> ''::text)),
    CONSTRAINT ck_agent_runtime_session_event_occurred_at_epoch CHECK (((occurred_at_epoch_second >= '-31557014167219200'::bigint) AND (occurred_at_epoch_second <= '31556889864403199'::bigint))),
    CONSTRAINT ck_agent_runtime_session_event_occurred_at_nano CHECK (((occurred_at_nano >= 0) AND (occurred_at_nano < 1000000000))),
    CONSTRAINT ck_agent_runtime_session_event_payload CHECK (((btrim(payload_schema_id) <> ''::text) AND (btrim(payload_canonical_json) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_event_run CHECK (((run_id IS NULL) OR (btrim(run_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_event_schema CHECK (((btrim(event_type) <> ''::text) AND (btrim(event_version) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_event_sequence CHECK ((session_sequence > 0)),
    CONSTRAINT ck_agent_runtime_session_event_trace CHECK (((btrim(trace_id) <> ''::text) AND (btrim(span_id) <> ''::text) AND (btrim(root_input_id) <> ''::text) AND ((caused_by_event_id IS NULL) OR (btrim(caused_by_event_id) <> ''::text))))
);

ALTER TABLE ONLY public.agent_runtime_session_event_payload_archives FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_event_tombstones; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_event_tombstones (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    end_user_id bigint NOT NULL,
    event_id text NOT NULL,
    session_sequence bigint NOT NULL,
    event_digest character varying(64) NOT NULL,
    recorded_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT session_event_tombstones_digest CHECK ((((event_digest)::text ~ '^[a-f0-9]{64}$'::text) AND (session_sequence > 0)))
);

ALTER TABLE ONLY public.agent_runtime_session_event_tombstones FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_history_retirements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_history_retirements (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    end_user_id bigint NOT NULL,
    source_session_revision bigint NOT NULL,
    closed_epoch_second bigint NOT NULL,
    closed_nano integer NOT NULL,
    scan_after_sequence bigint DEFAULT 0 NOT NULL,
    retired_through_sequence bigint DEFAULT 0 NOT NULL,
    retired_event_count bigint DEFAULT 0 NOT NULL,
    retired_bytes bigint DEFAULT 0 NOT NULL,
    retired_answer_count bigint DEFAULT 0 NOT NULL,
    retired_reasoning_count bigint DEFAULT 0 NOT NULL,
    status character varying(16) DEFAULT 'RETIRING'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    updated_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    resume_after_sequence bigint DEFAULT 0 NOT NULL,
    recheck_after_sequence bigint,
    retired_diagnostic_count bigint DEFAULT 0 NOT NULL,
    retired_diagnostic_bytes bigint DEFAULT 0 NOT NULL,
    CONSTRAINT session_history_private_scan CHECK ((((resume_after_sequence >= 0) AND (resume_after_sequence <= scan_after_sequence)) AND ((recheck_after_sequence IS NULL) OR ((recheck_after_sequence >= 0) AND (recheck_after_sequence <= scan_after_sequence))) AND (retired_diagnostic_count >= 0) AND (retired_diagnostic_bytes >= 0))),
    CONSTRAINT session_history_retirements_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text) AND (end_user_id > 0))),
    CONSTRAINT session_history_retirements_progress CHECK (((scan_after_sequence >= 0) AND ((retired_through_sequence >= 0) AND (retired_through_sequence <= scan_after_sequence)) AND (retired_event_count >= 0) AND (retired_bytes >= 0) AND (retired_answer_count >= 0) AND (retired_reasoning_count >= 0) AND (retired_event_count = (retired_answer_count + retired_reasoning_count)))),
    CONSTRAINT session_history_retirements_revision CHECK (((source_session_revision > 0) AND ((closed_nano >= 0) AND (closed_nano <= 999999999)))),
    CONSTRAINT session_history_retirements_status CHECK (((status)::text = ANY ((ARRAY['RETIRING'::character varying, 'RETIRED'::character varying])::text[])))
);

ALTER TABLE ONLY public.agent_runtime_session_history_retirements FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_inbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_inbox (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    input_id character varying(160) NOT NULL,
    inbox_sequence bigint NOT NULL,
    status character varying(16) NOT NULL,
    inbox_revision bigint NOT NULL,
    attempts integer NOT NULL,
    available_epoch_second bigint NOT NULL,
    available_nano integer NOT NULL,
    lease_claim_id text,
    lease_worker_id text,
    lease_fencing_token bigint,
    lease_until_epoch_second bigint,
    lease_until_nano integer,
    last_requested_epoch_second bigint,
    last_requested_nano integer,
    result_ref text,
    reason_code text,
    entry_data text NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    updated_epoch_second bigint NOT NULL,
    updated_nano integer NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    lane character varying(240) DEFAULT ''::character varying NOT NULL,
    CONSTRAINT agent_runtime_session_inbox_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_inbox_available CHECK (((available_nano >= 0) AND (available_nano <= 999999999))),
    CONSTRAINT ck_agent_runtime_session_inbox_codec CHECK ((btrim(entry_data) <> ''::text)),
    CONSTRAINT ck_agent_runtime_session_inbox_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (btrim((input_id)::text) <> ''::text) AND (inbox_sequence > 0) AND (inbox_revision > 0) AND (attempts >= 0))),
    CONSTRAINT ck_agent_runtime_session_inbox_last_requested CHECK ((((last_requested_epoch_second IS NULL) AND (last_requested_nano IS NULL)) OR ((last_requested_epoch_second IS NOT NULL) AND ((last_requested_nano >= 0) AND (last_requested_nano <= 999999999))))),
    CONSTRAINT ck_agent_runtime_session_inbox_lease CHECK (((((status)::text = 'PROCESSING'::text) = ((lease_claim_id IS NOT NULL) AND (lease_worker_id IS NOT NULL) AND (lease_fencing_token IS NOT NULL) AND (lease_until_epoch_second IS NOT NULL) AND (lease_until_nano IS NOT NULL))) AND ((lease_claim_id IS NULL) OR ((btrim(lease_claim_id) <> ''::text) AND (btrim(lease_worker_id) <> ''::text) AND (lease_fencing_token > 0) AND ((lease_until_nano >= 0) AND (lease_until_nano <= 999999999)))))),
    CONSTRAINT ck_agent_runtime_session_inbox_result CHECK ((((reason_code IS NULL) OR (result_ref IS NOT NULL)) AND (((status)::text <> ALL ((ARRAY['COMPLETED'::character varying, 'FAILED'::character varying])::text[])) OR (result_ref IS NOT NULL)) AND (((status)::text <> 'FAILED'::text) OR (reason_code IS NOT NULL)))),
    CONSTRAINT ck_agent_runtime_session_inbox_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT ck_agent_runtime_session_inbox_times CHECK ((((created_nano >= 0) AND (created_nano <= 999999999)) AND ((updated_nano >= 0) AND (updated_nano <= 999999999)) AND (ROW(updated_epoch_second, updated_nano) >= ROW(created_epoch_second, created_nano))))
);

ALTER TABLE ONLY public.agent_runtime_session_inbox FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_inbox_archives; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_inbox_archives (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    input_id character varying(160) NOT NULL,
    inbox_sequence bigint NOT NULL,
    status character varying(16) NOT NULL,
    inbox_revision bigint NOT NULL,
    attempts integer NOT NULL,
    available_epoch_second bigint NOT NULL,
    available_nano integer NOT NULL,
    lease_claim_id text,
    lease_worker_id text,
    lease_fencing_token bigint,
    lease_until_epoch_second bigint,
    lease_until_nano integer,
    last_requested_epoch_second bigint,
    last_requested_nano integer,
    result_ref text,
    reason_code text,
    entry_data text NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    updated_epoch_second bigint NOT NULL,
    updated_nano integer NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    lane character varying(240) DEFAULT ''::character varying NOT NULL,
    owner_session_id text NOT NULL,
    owner_target_kind text NOT NULL,
    owner_target_id text NOT NULL,
    owner_end_user_id bigint NOT NULL,
    source_session_revision bigint NOT NULL,
    closed_epoch_second bigint NOT NULL,
    closed_nano integer NOT NULL,
    source_run_id text,
    source_run_revision bigint,
    source_run_updated_epoch_second bigint,
    source_run_updated_nano integer,
    original_row_sha256 text NOT NULL,
    archived_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    entry_data_sha256 text NOT NULL,
    CONSTRAINT agent_runtime_session_inbox_archi_source_session_revision_check CHECK ((source_session_revision > 0)),
    CONSTRAINT agent_runtime_session_inbox_archives_closed_nano_check CHECK (((closed_nano >= 0) AND (closed_nano <= 999999999))),
    CONSTRAINT agent_runtime_session_inbox_archives_entry_data_sha256_check CHECK ((entry_data_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_session_inbox_archives_original_row_sha256_check CHECK ((original_row_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_session_inbox_archives_owner_end_user_id_check CHECK ((owner_end_user_id > 0)),
    CONSTRAINT agent_runtime_session_inbox_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_inbox_available CHECK (((available_nano >= 0) AND (available_nano <= 999999999))),
    CONSTRAINT ck_agent_runtime_session_inbox_codec CHECK ((btrim(entry_data) <> ''::text)),
    CONSTRAINT ck_agent_runtime_session_inbox_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (btrim((input_id)::text) <> ''::text) AND (inbox_sequence > 0) AND (inbox_revision > 0) AND (attempts >= 0))),
    CONSTRAINT ck_agent_runtime_session_inbox_last_requested CHECK ((((last_requested_epoch_second IS NULL) AND (last_requested_nano IS NULL)) OR ((last_requested_epoch_second IS NOT NULL) AND ((last_requested_nano >= 0) AND (last_requested_nano <= 999999999))))),
    CONSTRAINT ck_agent_runtime_session_inbox_lease CHECK (((((status)::text = 'PROCESSING'::text) = ((lease_claim_id IS NOT NULL) AND (lease_worker_id IS NOT NULL) AND (lease_fencing_token IS NOT NULL) AND (lease_until_epoch_second IS NOT NULL) AND (lease_until_nano IS NOT NULL))) AND ((lease_claim_id IS NULL) OR ((btrim(lease_claim_id) <> ''::text) AND (btrim(lease_worker_id) <> ''::text) AND (lease_fencing_token > 0) AND ((lease_until_nano >= 0) AND (lease_until_nano <= 999999999)))))),
    CONSTRAINT ck_agent_runtime_session_inbox_result CHECK ((((reason_code IS NULL) OR (result_ref IS NOT NULL)) AND (((status)::text <> ALL ((ARRAY['COMPLETED'::character varying, 'FAILED'::character varying])::text[])) OR (result_ref IS NOT NULL)) AND (((status)::text <> 'FAILED'::text) OR (reason_code IS NOT NULL)))),
    CONSTRAINT ck_agent_runtime_session_inbox_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT ck_agent_runtime_session_inbox_times CHECK ((((created_nano >= 0) AND (created_nano <= 999999999)) AND ((updated_nano >= 0) AND (updated_nano <= 999999999)) AND (ROW(updated_epoch_second, updated_nano) >= ROW(created_epoch_second, created_nano))))
);

ALTER TABLE ONLY public.agent_runtime_session_inbox_archives FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_inbox_mutation_archives; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_inbox_mutation_archives (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    input_id character varying(160) NOT NULL,
    mutation_key text NOT NULL,
    mutation_kind character varying(16) NOT NULL,
    command_fingerprint character varying(64) NOT NULL,
    output_revision bigint NOT NULL,
    output_status character varying(16) NOT NULL,
    output_fingerprint character varying(64) NOT NULL,
    output_data text NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    owner_session_id text NOT NULL,
    owner_target_kind text NOT NULL,
    owner_target_id text NOT NULL,
    owner_end_user_id bigint NOT NULL,
    source_session_revision bigint NOT NULL,
    closed_epoch_second bigint NOT NULL,
    closed_nano integer NOT NULL,
    source_run_id text,
    source_run_revision bigint,
    source_run_updated_epoch_second bigint,
    source_run_updated_nano integer,
    original_row_sha256 text NOT NULL,
    archived_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    output_data_sha256 text NOT NULL,
    CONSTRAINT agent_runtime_inbox_mutations_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text))),
    CONSTRAINT agent_runtime_session_inbox_mutat_source_session_revision_check CHECK ((source_session_revision > 0)),
    CONSTRAINT agent_runtime_session_inbox_mutation__original_row_sha256_check CHECK ((original_row_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_session_inbox_mutation_a_output_data_sha256_check CHECK ((output_data_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT agent_runtime_session_inbox_mutation_ar_owner_end_user_id_check CHECK ((owner_end_user_id > 0)),
    CONSTRAINT agent_runtime_session_inbox_mutation_archives_closed_nano_check CHECK (((closed_nano >= 0) AND (closed_nano <= 999999999))),
    CONSTRAINT ck_agent_runtime_inbox_mutation_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim((input_id)::text) <> ''::text) AND (btrim(mutation_key) <> ''::text) AND ((mutation_kind)::text = ANY ((ARRAY['COMPLETE'::character varying, 'RETRY'::character varying, 'FAIL'::character varying])::text[])) AND ((command_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND ((output_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (output_revision > 1) AND ((output_status)::text = ANY ((ARRAY['QUEUED'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[])) AND (btrim(output_data) <> ''::text)))
);

ALTER TABLE ONLY public.agent_runtime_session_inbox_mutation_archives FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_input_streams; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_input_streams (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text,
    end_user_id bigint NOT NULL,
    next_sequence bigint NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    CONSTRAINT agent_runtime_session_input_streams_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_input_stream_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (next_sequence > 0)))
);

ALTER TABLE ONLY public.agent_runtime_session_input_streams FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_mutations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_mutations (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text,
    end_user_id bigint NOT NULL,
    expected_status character varying(16) NOT NULL,
    target_status character varying(16) NOT NULL,
    expected_revision bigint NOT NULL,
    causation_input_id text NOT NULL,
    command_fingerprint character varying(64) NOT NULL,
    command_data text NOT NULL,
    output_status character varying(16) NOT NULL,
    output_revision bigint NOT NULL,
    output_updated_epoch_second bigint NOT NULL,
    output_updated_nano integer NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    CONSTRAINT agent_runtime_session_mutations_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_mutation_command CHECK ((((command_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (btrim(command_data) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_mutation_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (end_user_id > 0) AND (btrim(causation_input_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_mutation_time CHECK (((output_updated_nano >= 0) AND (output_updated_nano <= 999999999))),
    CONSTRAINT ck_agent_runtime_session_mutation_transition CHECK (((expected_revision > 0) AND ((((expected_status)::text = 'CREATED'::text) AND ((target_status)::text = 'ACTIVE'::text)) OR (((expected_status)::text = 'ACTIVE'::text) AND ((target_status)::text = 'CLOSED'::text))) AND ((output_status)::text = (target_status)::text) AND (output_revision = (expected_revision + 1))))
);

ALTER TABLE ONLY public.agent_runtime_session_mutations FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_outbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_outbox (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    session_sequence bigint NOT NULL,
    event_id text NOT NULL,
    destination text NOT NULL,
    initial_available_epoch_second bigint NOT NULL,
    initial_available_nano integer NOT NULL,
    available_epoch_second bigint NOT NULL,
    available_nano integer NOT NULL,
    status text NOT NULL,
    outbox_revision bigint NOT NULL,
    attempts integer NOT NULL,
    lease_claim_id text,
    lease_worker_id text,
    lease_fencing_token bigint,
    lease_until_epoch_second bigint,
    lease_until_nano integer,
    last_requested_epoch_second bigint,
    last_requested_nano integer,
    reason_code text,
    diagnostic_ref text,
    created_at timestamp with time zone DEFAULT statement_timestamp() NOT NULL,
    updated_at timestamp with time zone DEFAULT statement_timestamp() NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    CONSTRAINT agent_runtime_session_outbox_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_outbox_available_epoch CHECK (((available_epoch_second >= '-31557014167219200'::bigint) AND (available_epoch_second <= '31556889864403199'::bigint) AND (available_nano >= 0) AND (available_nano < 1000000000))),
    CONSTRAINT ck_agent_runtime_session_outbox_dead_letter_diagnostic CHECK (((status <> 'DEAD_LETTER'::text) OR ((reason_code IS NOT NULL) AND (diagnostic_ref IS NOT NULL)))),
    CONSTRAINT ck_agent_runtime_session_outbox_destination CHECK ((btrim(destination) <> ''::text)),
    CONSTRAINT ck_agent_runtime_session_outbox_diagnostic_pair CHECK ((((reason_code IS NULL) AND (diagnostic_ref IS NULL)) OR ((reason_code IS NOT NULL) AND (btrim(reason_code) <> ''::text) AND (diagnostic_ref IS NOT NULL) AND (btrim(diagnostic_ref) <> ''::text)))),
    CONSTRAINT ck_agent_runtime_session_outbox_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (end_user_id > 0) AND (session_sequence > 0) AND (btrim(event_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_outbox_initial_available_epoch CHECK (((initial_available_epoch_second >= '-31557014167219200'::bigint) AND (initial_available_epoch_second <= '31556889864403199'::bigint) AND (initial_available_nano >= 0) AND (initial_available_nano < 1000000000))),
    CONSTRAINT ck_agent_runtime_session_outbox_lease_shape CHECK ((((status = 'DELIVERING'::text) AND (lease_claim_id IS NOT NULL) AND (btrim(lease_claim_id) <> ''::text) AND (lease_worker_id IS NOT NULL) AND (btrim(lease_worker_id) <> ''::text) AND (lease_fencing_token IS NOT NULL) AND (lease_fencing_token > 0) AND (lease_until_epoch_second IS NOT NULL) AND (lease_until_epoch_second >= '-31557014167219200'::bigint) AND (lease_until_epoch_second <= '31556889864403199'::bigint) AND (lease_until_nano IS NOT NULL) AND (lease_until_nano >= 0) AND (lease_until_nano < 1000000000)) OR ((status <> 'DELIVERING'::text) AND (lease_claim_id IS NULL) AND (lease_worker_id IS NULL) AND (lease_fencing_token IS NULL) AND (lease_until_epoch_second IS NULL) AND (lease_until_nano IS NULL)))),
    CONSTRAINT ck_agent_runtime_session_outbox_requested_at_pair CHECK ((((last_requested_epoch_second IS NULL) AND (last_requested_nano IS NULL)) OR ((last_requested_epoch_second IS NOT NULL) AND (last_requested_epoch_second >= '-31557014167219200'::bigint) AND (last_requested_epoch_second <= '31556889864403199'::bigint) AND (last_requested_nano IS NOT NULL) AND (last_requested_nano >= 0) AND (last_requested_nano < 1000000000)))),
    CONSTRAINT ck_agent_runtime_session_outbox_revision_attempts CHECK (((outbox_revision > 0) AND (attempts >= 0))),
    CONSTRAINT ck_agent_runtime_session_outbox_status CHECK ((status = ANY (ARRAY['PENDING'::text, 'DELIVERING'::text, 'SENT'::text, 'DEAD_LETTER'::text]))),
    CONSTRAINT ck_agent_runtime_session_outbox_timestamps CHECK ((updated_at >= created_at))
);

ALTER TABLE ONLY public.agent_runtime_session_outbox FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_output_chunks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_output_chunks (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    end_user_id bigint NOT NULL,
    run_id text NOT NULL,
    message_id text NOT NULL,
    content_hash character varying(64) NOT NULL,
    canonical_content text NOT NULL,
    recorded_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT session_output_chunks_content CHECK ((("left"(canonical_content, 1) = '"'::text) AND ("right"(canonical_content, 1) = '"'::text))),
    CONSTRAINT session_output_chunks_hash CHECK (((content_hash)::text ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT session_output_chunks_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text) AND (end_user_id > 0) AND (btrim(run_id) <> ''::text) AND (btrim(message_id) <> ''::text)))
);

ALTER TABLE ONLY public.agent_runtime_session_output_chunks FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_owner_retirements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_owner_retirements (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    end_user_id bigint NOT NULL,
    source_session_revision bigint NOT NULL,
    closed_epoch_second bigint NOT NULL,
    closed_nano integer NOT NULL,
    phase integer DEFAULT 1 NOT NULL,
    scan_after_key text[] DEFAULT '{}'::text[] NOT NULL,
    status text DEFAULT 'PENDING'::text NOT NULL,
    retired_rows bigint DEFAULT 0 NOT NULL,
    retired_bytes bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    updated_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT agent_runtime_session_owner_retirements_closed_nano_check CHECK (((closed_nano >= 0) AND (closed_nano <= 999999999))),
    CONSTRAINT agent_runtime_session_owner_retirements_phase_check CHECK (((phase >= 1) AND (phase <= 12))),
    CONSTRAINT agent_runtime_session_owner_retirements_retired_bytes_check CHECK ((retired_bytes >= 0)),
    CONSTRAINT agent_runtime_session_owner_retirements_retired_rows_check CHECK ((retired_rows >= 0)),
    CONSTRAINT agent_runtime_session_owner_retirements_status_check CHECK ((status = ANY (ARRAY['PENDING'::text, 'RETIRED'::text])))
);

ALTER TABLE ONLY public.agent_runtime_session_owner_retirements FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_owners; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_owners (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text,
    end_user_id bigint,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    subject_kind text GENERATED ALWAYS AS (
CASE
    WHEN (target_kind = 'GROUP'::text) THEN 'GROUP'::text
    ELSE 'USER'::text
END) STORED NOT NULL,
    subject_id text GENERATED ALWAYS AS (
CASE
    WHEN (target_kind = 'GROUP'::text) THEN target_id
    ELSE (end_user_id)::text
END) STORED NOT NULL,
    CONSTRAINT session_owners_subject_ck CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(target_id) <> ''::text) AND (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (end_user_id IS NOT NULL) AND (end_user_id > 0)) OR ((target_kind = 'GROUP'::text) AND (end_user_id IS NULL)))))
);

ALTER TABLE ONLY public.agent_runtime_session_owners FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_preparation_profiles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_preparation_profiles (
    tenant_id text NOT NULL,
    profile_ref text NOT NULL,
    profile_data text NOT NULL,
    recorded_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT agent_runtime_session_preparation_profiles_profile_data_check CHECK ((btrim(profile_data) <> ''::text)),
    CONSTRAINT agent_runtime_session_preparation_profiles_profile_ref_check CHECK ((btrim(profile_ref) <> ''::text)),
    CONSTRAINT agent_runtime_session_preparation_profiles_tenant_id_check CHECK ((btrim(tenant_id) <> ''::text))
);

ALTER TABLE ONLY public.agent_runtime_session_preparation_profiles FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_streams; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_streams (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text,
    end_user_id bigint,
    next_sequence bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    subject_kind text GENERATED ALWAYS AS (
CASE
    WHEN (target_kind = 'GROUP'::text) THEN 'GROUP'::text
    ELSE 'USER'::text
END) STORED NOT NULL,
    subject_id text GENERATED ALWAYS AS (
CASE
    WHEN (target_kind = 'GROUP'::text) THEN target_id
    ELSE (end_user_id)::text
END) STORED NOT NULL,
    CONSTRAINT ck_agent_runtime_session_stream_agent CHECK ((btrim(agent_id) <> ''::text)),
    CONSTRAINT ck_agent_runtime_session_stream_end_user CHECK ((end_user_id > 0)),
    CONSTRAINT ck_agent_runtime_session_stream_sequence CHECK ((next_sequence > 0)),
    CONSTRAINT ck_agent_runtime_session_stream_session CHECK ((btrim(session_id) <> ''::text)),
    CONSTRAINT ck_agent_runtime_session_stream_tenant CHECK ((btrim(tenant_id) <> ''::text)),
    CONSTRAINT domain_stream_subject_ck CHECK ((((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (end_user_id IS NOT NULL) AND (end_user_id > 0)) OR ((target_kind = 'GROUP'::text) AND (end_user_id IS NULL))))
);

ALTER TABLE ONLY public.agent_runtime_session_streams FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_token_profiles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_token_profiles (
    tenant_id text NOT NULL,
    profile_ref text NOT NULL,
    profile_data text NOT NULL,
    recorded_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT agent_runtime_session_token_profiles_profile_data_check CHECK ((btrim(profile_data) <> ''::text)),
    CONSTRAINT agent_runtime_session_token_profiles_profile_ref_check CHECK ((btrim(profile_ref) <> ''::text)),
    CONSTRAINT agent_runtime_session_token_profiles_tenant_id_check CHECK ((btrim(tenant_id) <> ''::text))
);

ALTER TABLE ONLY public.agent_runtime_session_token_profiles FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_session_tool_projections; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_session_tool_projections (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    journal_ref text NOT NULL,
    preparation_ref text,
    program_event_id text,
    fact_ref text NOT NULL,
    key_data text NOT NULL,
    projection_data text NOT NULL,
    occurred_at_epoch_second bigint NOT NULL,
    occurred_at_nano integer NOT NULL,
    CONSTRAINT agent_runtime_session_tool_projections_check CHECK ((((preparation_ref IS NOT NULL) AND (preparation_ref = journal_ref) AND (program_event_id IS NULL)) OR ((program_event_id IS NOT NULL) AND (program_event_id = journal_ref) AND (preparation_ref IS NULL)))),
    CONSTRAINT agent_runtime_session_tool_projections_fact_ref_check CHECK ((btrim(fact_ref) <> ''::text)),
    CONSTRAINT agent_runtime_session_tool_projections_journal_ref_check CHECK ((btrim(journal_ref) <> ''::text)),
    CONSTRAINT agent_runtime_session_tool_projections_key_data_check CHECK ((btrim(key_data) <> ''::text)),
    CONSTRAINT agent_runtime_session_tool_projections_occurred_at_nano_check CHECK (((occurred_at_nano >= 0) AND (occurred_at_nano <= 999999999))),
    CONSTRAINT agent_runtime_session_tool_projections_projection_data_check CHECK ((btrim(projection_data) <> ''::text))
);

ALTER TABLE ONLY public.agent_runtime_session_tool_projections FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_sessions (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text,
    end_user_id bigint NOT NULL,
    channel text NOT NULL,
    scope_key text NOT NULL,
    persona_version text NOT NULL,
    create_fingerprint character varying(64) NOT NULL,
    create_command_data text NOT NULL,
    status character varying(16) NOT NULL,
    session_revision bigint NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    updated_epoch_second bigint NOT NULL,
    updated_nano integer NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    CONSTRAINT agent_runtime_sessions_target_check CHECK (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (btrim(target_id) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_create_codec CHECK ((((create_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (btrim(create_command_data) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_identity CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (end_user_id > 0) AND (btrim(channel) <> ''::text) AND (btrim(scope_key) <> ''::text) AND (btrim(persona_version) <> ''::text))),
    CONSTRAINT ck_agent_runtime_session_lifecycle CHECK ((((status)::text = ANY ((ARRAY['CREATED'::character varying, 'ACTIVE'::character varying, 'CLOSED'::character varying])::text[])) AND (session_revision > 0))),
    CONSTRAINT ck_agent_runtime_session_times CHECK ((((created_nano >= 0) AND (created_nano <= 999999999)) AND ((updated_nano >= 0) AND (updated_nano <= 999999999)) AND (ROW(updated_epoch_second, updated_nano) >= ROW(created_epoch_second, created_nano))))
);

ALTER TABLE ONLY public.agent_runtime_sessions FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_team_task_results; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_team_task_results (
    tenant_id character varying(160) NOT NULL,
    session_id character varying(240) NOT NULL,
    root_input_id character varying(240) NOT NULL,
    task_id character varying(240) NOT NULL,
    run_id character varying(240) NOT NULL,
    output_name character varying(240) NOT NULL,
    result_id character varying(240) NOT NULL,
    schema_id character varying(100) NOT NULL,
    kind character varying(40) NOT NULL,
    status character varying(40) NOT NULL,
    payload jsonb NOT NULL,
    payload_sha256 character varying(64) NOT NULL,
    command_sha256 character varying(64) NOT NULL,
    source_event_ref character varying(240),
    invocation_id character varying(240) NOT NULL,
    attempt_id character varying(240) NOT NULL,
    authorization_summary jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT agent_runtime_team_task_results_authorization_summary_check CHECK ((jsonb_typeof(authorization_summary) = 'object'::text)),
    CONSTRAINT agent_runtime_team_task_results_check CHECK (((btrim((session_id)::text) <> ''::text) AND (btrim((root_input_id)::text) <> ''::text) AND (btrim((task_id)::text) <> ''::text) AND (btrim((run_id)::text) <> ''::text) AND (btrim((output_name)::text) <> ''::text) AND (btrim((result_id)::text) <> ''::text) AND (btrim((invocation_id)::text) <> ''::text) AND (btrim((attempt_id)::text) <> ''::text))),
    CONSTRAINT agent_runtime_team_task_results_check1 CHECK (((jsonb_typeof(payload) = 'object'::text) AND ((payload ->> 'schema_id'::text) = (schema_id)::text) AND ((payload ->> 'result_id'::text) = (result_id)::text) AND ((payload ->> 'task_id'::text) = (task_id)::text) AND ((payload ->> 'output_name'::text) = (output_name)::text) AND ((payload ->> 'kind'::text) = (kind)::text) AND ((payload ->> 'status'::text) = (status)::text))),
    CONSTRAINT agent_runtime_team_task_results_command_sha256_check CHECK (((command_sha256)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT agent_runtime_team_task_results_kind_check CHECK (((kind)::text = ANY ((ARRAY['text'::character varying, 'document'::character varying, 'spreadsheet'::character varying, 'presentation'::character varying, 'image'::character varying, 'design'::character varying, 'website'::character varying, 'application'::character varying, 'code'::character varying, 'media'::character varying, 'operation'::character varying, 'configuration'::character varying])::text[]))),
    CONSTRAINT agent_runtime_team_task_results_payload_sha256_check CHECK (((payload_sha256)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT agent_runtime_team_task_results_schema_id_check CHECK (((schema_id)::text = 'team-task-result.v1'::text)),
    CONSTRAINT agent_runtime_team_task_results_status_check CHECK (((status)::text = ANY ((ARRAY['succeeded'::character varying, 'partial'::character varying, 'failed'::character varying])::text[])))
);

ALTER TABLE ONLY public.agent_runtime_team_task_results FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_team_work_answers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_team_work_answers (
    tenant_id character varying(160) NOT NULL,
    session_id character varying(240) NOT NULL,
    root_input_id character varying(240) NOT NULL,
    result_ref character varying(240) NOT NULL,
    answer_type character varying(20) NOT NULL,
    answer_text text NOT NULL,
    source_control_run_id character varying(240),
    source_control_revision bigint,
    source_result_ref character varying(240),
    source_agent_id character varying(240),
    source_run_refs jsonb NOT NULL,
    answer_revision bigint NOT NULL,
    message_id character varying(240) NOT NULL,
    message_event_id character varying(240) NOT NULL,
    completion_event_id character varying(240) NOT NULL,
    status character varying(20) NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    delivered_at timestamp with time zone,
    CONSTRAINT agent_runtime_team_work_answers_answer_revision_check CHECK ((answer_revision > 0)),
    CONSTRAINT agent_runtime_team_work_answers_answer_type_check CHECK (((answer_type)::text = ANY ((ARRAY['DIRECT'::character varying, 'RESULT'::character varying, 'SUMMARY'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT agent_runtime_team_work_answers_check CHECK (((btrim((result_ref)::text) <> ''::text) AND (btrim(answer_text) <> ''::text) AND (btrim((message_id)::text) <> ''::text) AND (btrim((message_event_id)::text) <> ''::text) AND (btrim((completion_event_id)::text) <> ''::text))),
    CONSTRAINT agent_runtime_team_work_answers_check1 CHECK (((((status)::text = 'RECORDED'::text) AND (delivered_at IS NULL)) OR (((status)::text = 'DELIVERED'::text) AND (delivered_at IS NOT NULL)))),
    CONSTRAINT agent_runtime_team_work_answers_check2 CHECK (((((answer_type)::text = 'RESULT'::text) AND (source_result_ref IS NOT NULL) AND (source_agent_id IS NOT NULL)) OR (((answer_type)::text <> 'RESULT'::text) AND (source_result_ref IS NULL)))),
    CONSTRAINT agent_runtime_team_work_answers_check3 CHECK (((source_control_run_id IS NULL) = (source_control_revision IS NULL))),
    CONSTRAINT agent_runtime_team_work_answers_check4 CHECK (((source_control_run_id IS NOT NULL) OR ((answer_type)::text = 'CANCELLED'::text))),
    CONSTRAINT agent_runtime_team_work_answers_source_control_revision_check CHECK ((source_control_revision > 0)),
    CONSTRAINT agent_runtime_team_work_answers_source_run_refs_check CHECK ((jsonb_typeof(source_run_refs) = 'array'::text)),
    CONSTRAINT agent_runtime_team_work_answers_status_check CHECK (((status)::text = ANY ((ARRAY['RECORDED'::character varying, 'DELIVERED'::character varying])::text[])))
);

ALTER TABLE ONLY public.agent_runtime_team_work_answers FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_workflow_child_execution_sources; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_workflow_child_execution_sources (
    tenant_id text NOT NULL,
    child_execution_id character varying(160) NOT NULL,
    parent_execution_id character varying(160) NOT NULL,
    node_id text NOT NULL,
    visit integer NOT NULL,
    call_index integer NOT NULL,
    child_ordinal integer NOT NULL,
    branch_id text NOT NULL,
    session_id text NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint,
    run_id character varying(160) NOT NULL,
    command_fingerprint character varying(64) NOT NULL,
    command_data text NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    subject_kind text GENERATED ALWAYS AS (
CASE
    WHEN (target_kind = 'GROUP'::text) THEN 'GROUP'::text
    ELSE 'USER'::text
END) STORED NOT NULL,
    subject_id text GENERATED ALWAYS AS (
CASE
    WHEN (target_kind = 'GROUP'::text) THEN target_id
    ELSE (end_user_id)::text
END) STORED NOT NULL,
    CONSTRAINT agent_runtime_workflow_child_execution_sources_subject_ck CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(target_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (btrim((run_id)::text) <> ''::text) AND (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (end_user_id IS NOT NULL) AND (end_user_id > 0)) OR ((target_kind = 'GROUP'::text) AND (end_user_id IS NULL))))),
    CONSTRAINT ck_agent_runtime_workflow_child_source_codec CHECK ((((command_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (btrim(command_data) <> ''::text))),
    CONSTRAINT workflow_child_source_identity_ck CHECK (((btrim((child_execution_id)::text) <> ''::text) AND (btrim((parent_execution_id)::text) <> ''::text) AND (btrim(node_id) <> ''::text) AND (visit > 0) AND (call_index > 0) AND ((child_ordinal >= 0) AND (child_ordinal <= 31)) AND (btrim(branch_id) <> ''::text) AND ((child_execution_id)::text <> (parent_execution_id)::text)))
);

ALTER TABLE ONLY public.agent_runtime_workflow_child_execution_sources FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_workflow_child_preparations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_workflow_child_preparations (
    tenant_id text NOT NULL,
    parent_execution_id character varying(160) NOT NULL,
    session_id text NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint,
    run_id character varying(160) NOT NULL,
    node_id text NOT NULL,
    visit integer NOT NULL,
    call_index integer NOT NULL,
    definition_revision bigint NOT NULL,
    installation_revision bigint NOT NULL,
    prepared_execution_revision bigint NOT NULL,
    source_checkpoint_fingerprint character varying(64) NOT NULL,
    preparation_fingerprint character varying(64) NOT NULL,
    preparation_data text NOT NULL,
    prepared_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    subject_kind text GENERATED ALWAYS AS (
CASE
    WHEN (target_kind = 'GROUP'::text) THEN 'GROUP'::text
    ELSE 'USER'::text
END) STORED NOT NULL,
    subject_id text GENERATED ALWAYS AS (
CASE
    WHEN (target_kind = 'GROUP'::text) THEN target_id
    ELSE (end_user_id)::text
END) STORED NOT NULL,
    CONSTRAINT agent_runtime_workflow_child_preparations_subject_ck CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(target_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (btrim((run_id)::text) <> ''::text) AND (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (end_user_id IS NOT NULL) AND (end_user_id > 0)) OR ((target_kind = 'GROUP'::text) AND (end_user_id IS NULL))))),
    CONSTRAINT ck_agent_runtime_workflow_child_preparation_codec CHECK ((((source_checkpoint_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND ((preparation_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (btrim(preparation_data) <> ''::text))),
    CONSTRAINT ck_agent_runtime_workflow_child_preparation_source CHECK (((definition_revision > 0) AND (installation_revision > 0) AND (prepared_execution_revision > 0))),
    CONSTRAINT workflow_child_preparation_source_ck CHECK (((btrim((parent_execution_id)::text) <> ''::text) AND (btrim(node_id) <> ''::text) AND (visit > 0) AND (call_index > 0)))
);

ALTER TABLE ONLY public.agent_runtime_workflow_child_preparations FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_workflow_script_calls; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_workflow_script_calls (
    tenant_id text NOT NULL,
    execution_id character varying(160) NOT NULL,
    session_id text NOT NULL,
    target_kind text NOT NULL,
    target_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint,
    run_id character varying(160) NOT NULL,
    node_id text NOT NULL,
    visit integer NOT NULL,
    call_index integer NOT NULL,
    definition_revision bigint NOT NULL,
    installation_revision bigint NOT NULL,
    prepared_execution_revision bigint NOT NULL,
    task_id character varying(64) NOT NULL,
    request_schema_id text NOT NULL,
    deadline_epoch_second bigint NOT NULL,
    deadline_nano integer NOT NULL,
    source_checkpoint_fingerprint character varying(64) NOT NULL,
    request_fingerprint character varying(64) NOT NULL,
    preparation_fingerprint character varying(64) NOT NULL,
    preparation_data text NOT NULL,
    state character varying(16) DEFAULT 'PREPARED'::character varying NOT NULL,
    terminal_fingerprint character varying(64),
    terminal_data text,
    prepared_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    dispatched_at timestamp with time zone,
    terminal_at timestamp with time zone,
    subject_kind text GENERATED ALWAYS AS (
CASE
    WHEN (target_kind = 'GROUP'::text) THEN 'GROUP'::text
    ELSE 'USER'::text
END) STORED NOT NULL,
    subject_id text GENERATED ALWAYS AS (
CASE
    WHEN (target_kind = 'GROUP'::text) THEN target_id
    ELSE (end_user_id)::text
END) STORED NOT NULL,
    CONSTRAINT agent_runtime_workflow_script_calls_subject_ck CHECK (((btrim(tenant_id) <> ''::text) AND (btrim(session_id) <> ''::text) AND (btrim(target_id) <> ''::text) AND (btrim(agent_id) <> ''::text) AND (btrim((run_id)::text) <> ''::text) AND (((target_kind = ANY (ARRAY['AGENT'::text, 'TEAM'::text])) AND (end_user_id IS NOT NULL) AND (end_user_id > 0)) OR ((target_kind = 'GROUP'::text) AND (end_user_id IS NULL))))),
    CONSTRAINT ck_agent_runtime_workflow_script_preparation CHECK ((((source_checkpoint_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND ((request_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND ((preparation_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (btrim(preparation_data) <> ''::text))),
    CONSTRAINT ck_agent_runtime_workflow_script_source CHECK (((definition_revision > 0) AND (installation_revision > 0) AND (prepared_execution_revision > 0) AND ((task_id)::text ~ '^[0-9a-f]{64}$'::text) AND (request_schema_id = 'application/json'::text) AND ((deadline_nano >= 0) AND (deadline_nano <= 999999999)))),
    CONSTRAINT ck_agent_runtime_workflow_script_state CHECK (((state)::text = ANY ((ARRAY['PREPARED'::character varying, 'DISPATCHED'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'TIMED_OUT'::character varying])::text[]))),
    CONSTRAINT ck_agent_runtime_workflow_script_terminal CHECK (((((state)::text = 'PREPARED'::text) AND (dispatched_at IS NULL) AND (terminal_at IS NULL) AND (terminal_fingerprint IS NULL) AND (terminal_data IS NULL)) OR (((state)::text = 'DISPATCHED'::text) AND (dispatched_at IS NOT NULL) AND (terminal_at IS NULL) AND (terminal_fingerprint IS NULL) AND (terminal_data IS NULL)) OR (((state)::text = ANY ((ARRAY['COMPLETED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'TIMED_OUT'::character varying])::text[])) AND (dispatched_at IS NOT NULL) AND (terminal_at IS NOT NULL) AND ((terminal_fingerprint)::text ~ '^[0-9a-f]{64}$'::text) AND (btrim(terminal_data) <> ''::text)))),
    CONSTRAINT ck_agent_runtime_workflow_script_times CHECK ((((dispatched_at IS NULL) OR (dispatched_at >= prepared_at)) AND ((terminal_at IS NULL) OR (terminal_at >= dispatched_at)))),
    CONSTRAINT workflow_script_source_ck CHECK (((btrim((execution_id)::text) <> ''::text) AND (btrim(node_id) <> ''::text) AND (visit > 0) AND (call_index > 0)))
);

ALTER TABLE ONLY public.agent_runtime_workflow_script_calls FORCE ROW LEVEL SECURITY;



--
-- Name: runtime_budget_attempt_amounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_budget_attempt_amounts (
    tenant_id character varying(64) NOT NULL,
    attempt_id character varying(256) NOT NULL,
    dimension character varying(32) NOT NULL,
    reserved bigint NOT NULL,
    reported bigint,
    settled boolean DEFAULT false NOT NULL,
    CONSTRAINT agent_runtime_budget_attempt_amounts_reported_check CHECK ((reported >= 0)),
    CONSTRAINT agent_runtime_budget_attempt_amounts_reserved_check CHECK ((reserved >= 0))
);

ALTER TABLE ONLY public.agent_runtime_budget_attempt_amounts FORCE ROW LEVEL SECURITY;



--
-- Name: runtime_budget_activity; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_budget_activity (
    tenant_id character varying(64) NOT NULL,
    run_id character varying(256) NOT NULL,
    interval_id character varying(256) NOT NULL,
    started_at timestamp with time zone NOT NULL,
    confirmed_through timestamp with time zone NOT NULL,
    CONSTRAINT agent_runtime_budget_activity_check CHECK ((confirmed_through >= started_at))
);

ALTER TABLE ONLY public.agent_runtime_budget_activity FORCE ROW LEVEL SECURITY;



--
-- Name: runtime_budget_attempts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_budget_attempts (
    tenant_id character varying(64) NOT NULL,
    attempt_id character varying(256) NOT NULL,
    run_id character varying(256) NOT NULL,
    fingerprint text NOT NULL,
    category character varying(32) NOT NULL,
    closing boolean NOT NULL,
    state character varying(32) NOT NULL,
    result_reference text,
    CONSTRAINT agent_runtime_budget_attempts_state_check CHECK (((state)::text = ANY ((ARRAY['RESERVED'::character varying, 'SENT'::character varying, 'RESULT_SAVED'::character varying, 'SETTLED'::character varying])::text[])))
);

ALTER TABLE ONLY public.agent_runtime_budget_attempts FORCE ROW LEVEL SECURITY;



--
-- Name: runtime_budget_balances; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_budget_balances (
    tenant_id character varying(64) NOT NULL,
    scope_kind character varying(16) NOT NULL,
    scope_id character varying(256) NOT NULL,
    dimension character varying(32) NOT NULL,
    maximum bigint NOT NULL,
    consumed bigint DEFAULT 0 NOT NULL,
    reserved bigint DEFAULT 0 NOT NULL,
    CONSTRAINT agent_runtime_budget_balances_consumed_check CHECK ((consumed >= 0)),
    CONSTRAINT agent_runtime_budget_balances_maximum_check CHECK ((maximum >= 0)),
    CONSTRAINT agent_runtime_budget_balances_reserved_check CHECK ((reserved >= 0)),
    CONSTRAINT agent_runtime_budget_balances_scope_kind_check CHECK (((scope_kind)::text = ANY ((ARRAY['ROOT'::character varying, 'RUN'::character varying, 'STAGE_BASE'::character varying, 'STAGE'::character varying, 'CLOSING'::character varying, 'CLOSING_RUN'::character varying])::text[])))
);

ALTER TABLE ONLY public.agent_runtime_budget_balances FORCE ROW LEVEL SECURITY;



--
-- Name: runtime_budget_closing_targets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_budget_closing_targets (
    tenant_id character varying(64) NOT NULL,
    run_id character varying(256) NOT NULL,
    target_handle text NOT NULL
);

ALTER TABLE ONLY public.agent_runtime_budget_closing_targets FORCE ROW LEVEL SECURITY;



--
-- Name: runtime_budget_result_receipts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_budget_result_receipts (
    tenant_id character varying(64) NOT NULL,
    attempt_id character varying(256) NOT NULL,
    result_reference text NOT NULL
);

ALTER TABLE ONLY public.agent_runtime_budget_result_receipts FORCE ROW LEVEL SECURITY;



--
-- Name: runtime_budget_roots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_budget_roots (
    tenant_id character varying(64) NOT NULL,
    root_id character varying(256) NOT NULL,
    scenario character varying(32) NOT NULL,
    source_version text NOT NULL,
    active_limit_seconds bigint NOT NULL,
    closing_active_seconds bigint DEFAULT 0 NOT NULL,
    closing_run_id character varying(256),
    phase character varying(16) DEFAULT 'BUSINESS'::character varying NOT NULL,
    CONSTRAINT agent_runtime_budget_roots_active_limit_seconds_check CHECK ((active_limit_seconds > 0)),
    CONSTRAINT agent_runtime_budget_roots_closing_active_seconds_check CHECK ((closing_active_seconds >= 0)),
    CONSTRAINT agent_runtime_budget_roots_phase_check CHECK (((phase)::text = ANY ((ARRAY['BUSINESS'::character varying, 'CLOSING'::character varying])::text[])))
);

ALTER TABLE ONLY public.agent_runtime_budget_roots FORCE ROW LEVEL SECURITY;



--
-- Name: runtime_budget_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_runtime_budget_runs (
    tenant_id character varying(64) NOT NULL,
    run_id character varying(256) NOT NULL,
    root_id character varying(256) NOT NULL,
    role character varying(16) NOT NULL,
    source_version text NOT NULL,
    active_limit_seconds bigint NOT NULL,
    CONSTRAINT agent_runtime_budget_runs_active_limit_seconds_check CHECK ((active_limit_seconds > 0)),
    CONSTRAINT agent_runtime_budget_runs_role_check CHECK (((role)::text = ANY ((ARRAY['PRIMARY'::character varying, 'MEMBER'::character varying])::text[])))
);

ALTER TABLE ONLY public.agent_runtime_budget_runs FORCE ROW LEVEL SECURITY;



--
-- Name: agent_runtime_action_attempt_facts agent_runtime_action_attempt_facts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_action_attempt_facts
    ADD CONSTRAINT agent_runtime_action_attempt_facts_pkey PRIMARY KEY (tenant_id, action_id, attempt_id, evidence_revision);



--
-- Name: agent_runtime_action_state_publications agent_runtime_action_state_publications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_action_state_publications
    ADD CONSTRAINT agent_runtime_action_state_publications_pkey PRIMARY KEY (tenant_id, source_id, event_id);



--
-- Name: agent_runtime_actions agent_runtime_actions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_actions
    ADD CONSTRAINT agent_runtime_actions_pkey PRIMARY KEY (tenant_id, action_id);



--
-- Name: agent_runtime_callback_audits agent_runtime_callback_audit_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_callback_audits
    ADD CONSTRAINT agent_runtime_callback_audit_pk PRIMARY KEY (tenant_id, resource_kind, resource_id, command_digest);



--
-- Name: agent_runtime_callback_audits agent_runtime_callback_audit_ref; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_callback_audits
    ADD CONSTRAINT agent_runtime_callback_audit_ref UNIQUE (audit_ref);



--
-- Name: agent_runtime_callback_delivery_operations agent_runtime_callback_delivery_operation_output; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_callback_delivery_operations
    ADD CONSTRAINT agent_runtime_callback_delivery_operation_output UNIQUE (tenant_id, delivery_id, output_revision);



--
-- Name: agent_runtime_callback_delivery_operations agent_runtime_callback_delivery_operation_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_callback_delivery_operations
    ADD CONSTRAINT agent_runtime_callback_delivery_operation_pk PRIMARY KEY (tenant_id, delivery_id, operation_id);



--
-- Name: agent_runtime_callback_deliveries agent_runtime_callback_delivery_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_callback_deliveries
    ADD CONSTRAINT agent_runtime_callback_delivery_pk PRIMARY KEY (tenant_id, delivery_id);



--
-- Name: agent_runtime_callback_ingress_proofs agent_runtime_callback_ingress_proofs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_callback_ingress_proofs
    ADD CONSTRAINT agent_runtime_callback_ingress_proofs_pkey PRIMARY KEY (tenant_id, delivery_id);



--
-- Name: agent_runtime_callback_registrations agent_runtime_callback_registration_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_callback_registrations
    ADD CONSTRAINT agent_runtime_callback_registration_key UNIQUE (tenant_id, idempotency_key);



--
-- Name: agent_runtime_callback_registration_operations agent_runtime_callback_registration_operation_output; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_callback_registration_operations
    ADD CONSTRAINT agent_runtime_callback_registration_operation_output UNIQUE (tenant_id, callback_id, output_revision);



--
-- Name: agent_runtime_callback_registration_operations agent_runtime_callback_registration_operation_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_callback_registration_operations
    ADD CONSTRAINT agent_runtime_callback_registration_operation_pk PRIMARY KEY (tenant_id, callback_id, operation_id);



--
-- Name: agent_runtime_callback_registrations agent_runtime_callback_registration_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_callback_registrations
    ADD CONSTRAINT agent_runtime_callback_registration_pk PRIMARY KEY (tenant_id, callback_id);



--
-- Name: agent_runtime_dag_admissions agent_runtime_dag_admissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_dag_admissions
    ADD CONSTRAINT agent_runtime_dag_admissions_pkey PRIMARY KEY (tenant_id, admission_id);



--
-- Name: agent_runtime_dag_admissions agent_runtime_dag_admissions_tenant_id_session_id_root_inpu_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_dag_admissions
    ADD CONSTRAINT agent_runtime_dag_admissions_tenant_id_session_id_root_inpu_key UNIQUE (tenant_id, session_id, root_input_id, task_id);



--
-- Name: agent_runtime_dag_commands agent_runtime_dag_commands_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_dag_commands
    ADD CONSTRAINT agent_runtime_dag_commands_pkey PRIMARY KEY (tenant_id, session_id, command_id);



--
-- Name: agent_runtime_dag_control_runs agent_runtime_dag_control_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_dag_control_runs
    ADD CONSTRAINT agent_runtime_dag_control_runs_pkey PRIMARY KEY (tenant_id, run_id);



--
-- Name: agent_runtime_dag_inputs agent_runtime_dag_inputs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_dag_inputs
    ADD CONSTRAINT agent_runtime_dag_inputs_pkey PRIMARY KEY (tenant_id, session_id, input_id);



--
-- Name: agent_runtime_dag_permits agent_runtime_dag_permits_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_dag_permits
    ADD CONSTRAINT agent_runtime_dag_permits_pkey PRIMARY KEY (tenant_id, run_id);



--
-- Name: agent_runtime_dag_step_actions agent_runtime_dag_step_actions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_dag_step_actions
    ADD CONSTRAINT agent_runtime_dag_step_actions_pkey PRIMARY KEY (tenant_id, action_id);



--
-- Name: agent_runtime_dag_works agent_runtime_dag_works_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_dag_works
    ADD CONSTRAINT agent_runtime_dag_works_pkey PRIMARY KEY (tenant_id, session_id, root_input_id);



--
-- Name: agent_runtime_delegation_waits agent_runtime_delegation_waits_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_delegation_waits
    ADD CONSTRAINT agent_runtime_delegation_waits_pkey PRIMARY KEY (tenant_id, parent_run_id, wait_id);



--
-- Name: agent_runtime_delegation_waits agent_runtime_delegation_waits_tenant_id_recovery_input_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_delegation_waits
    ADD CONSTRAINT agent_runtime_delegation_waits_tenant_id_recovery_input_id_key UNIQUE (tenant_id, recovery_input_id);



--
-- Name: agent_runtime_delegations agent_runtime_delegations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_delegations
    ADD CONSTRAINT agent_runtime_delegations_pkey PRIMARY KEY (tenant_id, parent_run_id, delegation_id);



--
-- Name: agent_runtime_delegations agent_runtime_delegations_tenant_id_parent_run_id_operation_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_delegations
    ADD CONSTRAINT agent_runtime_delegations_tenant_id_parent_run_id_operation_key UNIQUE (tenant_id, parent_run_id, operation_key);



--
-- Name: agent_runtime_event_conflict_audits agent_runtime_event_audit_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_event_conflict_audits
    ADD CONSTRAINT agent_runtime_event_audit_pk PRIMARY KEY (tenant_id, event_id, destination_id, command_digest);



--
-- Name: agent_runtime_event_conflict_audits agent_runtime_event_audit_ref; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_event_conflict_audits
    ADD CONSTRAINT agent_runtime_event_audit_ref UNIQUE (audit_ref);



--
-- Name: agent_runtime_event_deliveries agent_runtime_event_delivery_idempotency; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_event_deliveries
    ADD CONSTRAINT agent_runtime_event_delivery_idempotency UNIQUE (tenant_id, idempotency_key);



--
-- Name: agent_runtime_event_deliveries agent_runtime_event_delivery_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_event_deliveries
    ADD CONSTRAINT agent_runtime_event_delivery_pk PRIMARY KEY (tenant_id, event_id, destination_id);



--
-- Name: agent_runtime_execution_mutations agent_runtime_execution_mutations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_mutations
    ADD CONSTRAINT agent_runtime_execution_mutations_pkey PRIMARY KEY (tenant_id, execution_id, mutation_key);



--
-- Name: agent_runtime_execution_publication_mutations agent_runtime_execution_publication_mutations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_publication_mutations
    ADD CONSTRAINT agent_runtime_execution_publication_mutations_pkey PRIMARY KEY (tenant_id, source_id, event_id, mutation_key);



--
-- Name: agent_runtime_execution_state_publications agent_runtime_execution_state_publications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_state_publications
    ADD CONSTRAINT agent_runtime_execution_state_publications_pkey PRIMARY KEY (tenant_id, source_id, event_id);



--
-- Name: agent_runtime_executions agent_runtime_executions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_executions
    ADD CONSTRAINT agent_runtime_executions_pkey PRIMARY KEY (tenant_id, execution_id);



--
-- Name: agent_runtime_inbox_mutations agent_runtime_inbox_mutations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_inbox_mutations
    ADD CONSTRAINT agent_runtime_inbox_mutations_pkey PRIMARY KEY (tenant_id, target_kind, target_id, session_id, input_id, mutation_key);



--
-- Name: agent_runtime_input_ledger_archives agent_runtime_input_ledger_ar_tenant_id_target_kind_target__key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_input_ledger_archives
    ADD CONSTRAINT agent_runtime_input_ledger_ar_tenant_id_target_kind_target__key UNIQUE (tenant_id, target_kind, target_id, session_id, end_user_id, input_id);



--
-- Name: agent_runtime_input_ledger_archives agent_runtime_input_ledger_ar_tenant_id_target_kind_target_key1; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_input_ledger_archives
    ADD CONSTRAINT agent_runtime_input_ledger_ar_tenant_id_target_kind_target_key1 UNIQUE (tenant_id, target_kind, target_id, session_id, agent_id, end_user_id, input_id);



--
-- Name: agent_runtime_input_ledger_archives agent_runtime_input_ledger_archives_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_input_ledger_archives
    ADD CONSTRAINT agent_runtime_input_ledger_archives_pkey PRIMARY KEY (tenant_id, target_kind, target_id, session_id, input_id);



--
-- Name: agent_runtime_input_ledger agent_runtime_input_ledger_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_input_ledger
    ADD CONSTRAINT agent_runtime_input_ledger_pkey PRIMARY KEY (tenant_id, target_kind, target_id, session_id, input_id);



--
-- Name: agent_runtime_memory_extraction_fact_aliases agent_runtime_memory_extracti_tenant_id_source_event_id_ex_key1; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_fact_aliases
    ADD CONSTRAINT agent_runtime_memory_extracti_tenant_id_source_event_id_ex_key1 UNIQUE (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, alias_ordinal);



--
-- Name: agent_runtime_memory_extraction_subjects agent_runtime_memory_extracti_tenant_id_source_event_id_ex_key2; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_subjects
    ADD CONSTRAINT agent_runtime_memory_extracti_tenant_id_source_event_id_ex_key2 UNIQUE (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, subject_end_user_id);



--
-- Name: agent_runtime_memory_extraction_sources agent_runtime_memory_extracti_tenant_id_source_event_id_ext_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_sources
    ADD CONSTRAINT agent_runtime_memory_extracti_tenant_id_source_event_id_ext_key UNIQUE (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, source_ordinal);



--
-- Name: agent_runtime_memory_extraction_candidate_evidence agent_runtime_memory_extraction_candidate_evidence_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_candidate_evidence
    ADD CONSTRAINT agent_runtime_memory_extraction_candidate_evidence_pkey PRIMARY KEY (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, candidate_ordinal, item_ordinal);



--
-- Name: agent_runtime_memory_extraction_conditions agent_runtime_memory_extraction_conditions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_conditions
    ADD CONSTRAINT agent_runtime_memory_extraction_conditions_pkey PRIMARY KEY (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, candidate_ordinal, item_ordinal);



--
-- Name: agent_runtime_memory_extraction_fact_aliases agent_runtime_memory_extraction_fact_aliases_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_fact_aliases
    ADD CONSTRAINT agent_runtime_memory_extraction_fact_aliases_pkey PRIMARY KEY (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, alias);



--
-- Name: agent_runtime_memory_extraction_review_reasons agent_runtime_memory_extraction_review_reasons_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_review_reasons
    ADD CONSTRAINT agent_runtime_memory_extraction_review_reasons_pkey PRIMARY KEY (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, candidate_ordinal, item_ordinal);



--
-- Name: agent_runtime_memory_extraction_sources agent_runtime_memory_extraction_sources_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_sources
    ADD CONSTRAINT agent_runtime_memory_extraction_sources_pkey PRIMARY KEY (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, source_alias);



--
-- Name: agent_runtime_memory_extraction_subjects agent_runtime_memory_extraction_subjects_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_subjects
    ADD CONSTRAINT agent_runtime_memory_extraction_subjects_pkey PRIMARY KEY (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, subject_ordinal);



--
-- Name: agent_runtime_memory_extraction_uncertainties agent_runtime_memory_extraction_uncertainties_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_uncertainties
    ADD CONSTRAINT agent_runtime_memory_extraction_uncertainties_pkey PRIMARY KEY (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, candidate_ordinal, item_ordinal);



--
-- Name: agent_runtime_model_bindings agent_runtime_model_bindings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_model_bindings
    ADD CONSTRAINT agent_runtime_model_bindings_pkey PRIMARY KEY (tenant_id, binding_id, revision);



--
-- Name: agent_runtime_run_mutation_archives agent_runtime_run_mutation_archives_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_run_mutation_archives
    ADD CONSTRAINT agent_runtime_run_mutation_archives_pkey PRIMARY KEY (tenant_id, run_id, mutation_key);



--
-- Name: agent_runtime_run_mutations agent_runtime_run_mutations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_run_mutations
    ADD CONSTRAINT agent_runtime_run_mutations_pkey PRIMARY KEY (tenant_id, run_id, mutation_key);



--
-- Name: agent_runtime_run_publication_mutation_archives agent_runtime_run_publication_mutation_archives_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_run_publication_mutation_archives
    ADD CONSTRAINT agent_runtime_run_publication_mutation_archives_pkey PRIMARY KEY (tenant_id, source_id, event_id, mutation_key);



--
-- Name: agent_runtime_run_publication_mutations agent_runtime_run_publication_mutations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_run_publication_mutations
    ADD CONSTRAINT agent_runtime_run_publication_mutations_pkey PRIMARY KEY (tenant_id, source_id, event_id, mutation_key);



--
-- Name: agent_runtime_run_record_archives agent_runtime_run_record_arch_tenant_id_target_kind_target__key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_run_record_archives
    ADD CONSTRAINT agent_runtime_run_record_arch_tenant_id_target_kind_target__key UNIQUE (tenant_id, target_kind, target_id, session_id, run_id);



--
-- Name: agent_runtime_run_record_archives agent_runtime_run_record_arch_tenant_id_target_kind_target_key1; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_run_record_archives
    ADD CONSTRAINT agent_runtime_run_record_arch_tenant_id_target_kind_target_key1 UNIQUE (tenant_id, target_kind, target_id, agent_id, session_id, end_user_id, run_id);



--
-- Name: agent_runtime_run_record_archives agent_runtime_run_record_archives_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_run_record_archives
    ADD CONSTRAINT agent_runtime_run_record_archives_pkey PRIMARY KEY (tenant_id, run_id);



--
-- Name: agent_runtime_run_state_publications agent_runtime_run_state_publications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_run_state_publications
    ADD CONSTRAINT agent_runtime_run_state_publications_pkey PRIMARY KEY (tenant_id, source_id, event_id);



--
-- Name: agent_runtime_runs agent_runtime_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_runs
    ADD CONSTRAINT agent_runtime_runs_pkey PRIMARY KEY (tenant_id, run_id);



--
-- Name: agent_runtime_session_action_preparations agent_runtime_session_action_p_tenant_id_run_id_journal_ref_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_action_preparations
    ADD CONSTRAINT agent_runtime_session_action_p_tenant_id_run_id_journal_ref_key UNIQUE (tenant_id, run_id, journal_ref);



--
-- Name: agent_runtime_session_action_preparations agent_runtime_session_action_preparations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_action_preparations
    ADD CONSTRAINT agent_runtime_session_action_preparations_pkey PRIMARY KEY (tenant_id, journal_ref);



--
-- Name: agent_runtime_session_action_requests agent_runtime_session_action_r_tenant_id_run_id_journal_ref_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_action_requests
    ADD CONSTRAINT agent_runtime_session_action_r_tenant_id_run_id_journal_ref_key UNIQUE (tenant_id, run_id, journal_ref);



--
-- Name: agent_runtime_session_action_requests agent_runtime_session_action_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_action_requests
    ADD CONSTRAINT agent_runtime_session_action_requests_pkey PRIMARY KEY (tenant_id, journal_ref);



--
-- Name: agent_runtime_session_admission_prepare_archives agent_runtime_session_admission_prepare_archives_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_admission_prepare_archives
    ADD CONSTRAINT agent_runtime_session_admission_prepare_archives_pkey PRIMARY KEY (tenant_id, target_kind, target_id, session_id, input_id);



--
-- Name: agent_runtime_session_admission_prepares agent_runtime_session_admission_prepares_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_admission_prepares
    ADD CONSTRAINT agent_runtime_session_admission_prepares_pkey PRIMARY KEY (tenant_id, target_kind, target_id, session_id, input_id);



--
-- Name: agent_runtime_session_checkpoint_archive_body_refs agent_runtime_session_checkpoint_archive_body_refs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_checkpoint_archive_body_refs
    ADD CONSTRAINT agent_runtime_session_checkpoint_archive_body_refs_pkey PRIMARY KEY (tenant_id, run_id, checkpoint_hash, body_hash);



--
-- Name: agent_runtime_session_checkpoint_archives agent_runtime_session_checkpoint_archives_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_checkpoint_archives
    ADD CONSTRAINT agent_runtime_session_checkpoint_archives_pkey PRIMARY KEY (tenant_id, run_id, content_hash);



--
-- Name: agent_runtime_session_checkpoint_body_archives agent_runtime_session_checkpoint_body_archives_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_checkpoint_body_archives
    ADD CONSTRAINT agent_runtime_session_checkpoint_body_archives_pkey PRIMARY KEY (tenant_id, run_id, content_hash);



--
-- Name: agent_runtime_session_checkpoints agent_runtime_session_checkpoints_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_checkpoints
    ADD CONSTRAINT agent_runtime_session_checkpoints_pkey PRIMARY KEY (tenant_id, run_id, content_hash);



--
-- Name: agent_runtime_session_commit_journal_archives agent_runtime_session_commit_journal_archives_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_commit_journal_archives
    ADD CONSTRAINT agent_runtime_session_commit_journal_archives_pkey PRIMARY KEY (tenant_id, run_id, operation_hash);



--
-- Name: agent_runtime_session_commit_journals agent_runtime_session_commit_journals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_commit_journals
    ADD CONSTRAINT agent_runtime_session_commit_journals_pkey PRIMARY KEY (tenant_id, run_id, operation_hash);



--
-- Name: agent_runtime_session_conflict_audits agent_runtime_session_conflict_audits_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_conflict_audits
    ADD CONSTRAINT agent_runtime_session_conflict_audits_pkey PRIMARY KEY (tenant_id, audit_ref);



--
-- Name: agent_runtime_session_event_payload_archives agent_runtime_session_event_p_tenant_id_target_kind_target__key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_event_payload_archives
    ADD CONSTRAINT agent_runtime_session_event_p_tenant_id_target_kind_target__key UNIQUE (tenant_id, target_kind, target_id, session_id, event_id);



--
-- Name: agent_runtime_session_event_payload_archives agent_runtime_session_event_p_tenant_id_target_kind_target_key1; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_event_payload_archives
    ADD CONSTRAINT agent_runtime_session_event_p_tenant_id_target_kind_target_key1 UNIQUE (tenant_id, target_kind, target_id, session_id, agent_id, end_user_id, session_sequence, event_id);



--
-- Name: agent_runtime_session_event_payload_archives agent_runtime_session_event_paylo_tenant_id_run_id_event_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_event_payload_archives
    ADD CONSTRAINT agent_runtime_session_event_paylo_tenant_id_run_id_event_id_key UNIQUE (tenant_id, run_id, event_id);



--
-- Name: agent_runtime_session_event_payload_archives agent_runtime_session_event_payload_archives_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_event_payload_archives
    ADD CONSTRAINT agent_runtime_session_event_payload_archives_pkey PRIMARY KEY (tenant_id, target_kind, target_id, session_id, session_sequence);



--
-- Name: agent_runtime_session_events agent_runtime_session_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_events
    ADD CONSTRAINT agent_runtime_session_events_pkey PRIMARY KEY (tenant_id, target_kind, target_id, session_id, session_sequence);



--
-- Name: agent_runtime_session_events agent_runtime_session_events_run_event; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_events
    ADD CONSTRAINT agent_runtime_session_events_run_event UNIQUE (tenant_id, run_id, event_id);



--
-- Name: agent_runtime_session_inbox_archives agent_runtime_session_inbox_a_tenant_id_target_kind_target__key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_inbox_archives
    ADD CONSTRAINT agent_runtime_session_inbox_a_tenant_id_target_kind_target__key UNIQUE (tenant_id, target_kind, target_id, session_id, inbox_sequence);



--
-- Name: agent_runtime_session_inbox_archives agent_runtime_session_inbox_archives_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_inbox_archives
    ADD CONSTRAINT agent_runtime_session_inbox_archives_pkey PRIMARY KEY (tenant_id, target_kind, target_id, session_id, input_id);



--
-- Name: agent_runtime_session_inbox_mutation_archives agent_runtime_session_inbox_mutation_archives_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_inbox_mutation_archives
    ADD CONSTRAINT agent_runtime_session_inbox_mutation_archives_pkey PRIMARY KEY (tenant_id, target_kind, target_id, session_id, input_id, mutation_key);



--
-- Name: agent_runtime_session_inbox agent_runtime_session_inbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_inbox
    ADD CONSTRAINT agent_runtime_session_inbox_pkey PRIMARY KEY (tenant_id, target_kind, target_id, session_id, input_id);



--
-- Name: agent_runtime_session_input_streams agent_runtime_session_input_streams_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_input_streams
    ADD CONSTRAINT agent_runtime_session_input_streams_pkey PRIMARY KEY (tenant_id, target_kind, target_id, session_id);



--
-- Name: agent_runtime_session_mutations agent_runtime_session_mutations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_mutations
    ADD CONSTRAINT agent_runtime_session_mutations_pkey PRIMARY KEY (tenant_id, target_kind, target_id, session_id, expected_status, target_status, expected_revision, causation_input_id);



--
-- Name: agent_runtime_session_outbox agent_runtime_session_outbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_outbox
    ADD CONSTRAINT agent_runtime_session_outbox_pkey PRIMARY KEY (tenant_id, target_kind, target_id, session_id, event_id);



--
-- Name: agent_runtime_session_owner_retirements agent_runtime_session_owner_retirements_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_owner_retirements
    ADD CONSTRAINT agent_runtime_session_owner_retirements_pkey PRIMARY KEY (tenant_id, target_kind, target_id, session_id);



--
-- Name: agent_runtime_session_owners agent_runtime_session_owners_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_owners
    ADD CONSTRAINT agent_runtime_session_owners_pkey PRIMARY KEY (tenant_id, target_kind, target_id, session_id);



--
-- Name: agent_runtime_session_preparation_profiles agent_runtime_session_preparation_profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_preparation_profiles
    ADD CONSTRAINT agent_runtime_session_preparation_profiles_pkey PRIMARY KEY (tenant_id, profile_ref);



--
-- Name: agent_runtime_session_streams agent_runtime_session_streams_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_streams
    ADD CONSTRAINT agent_runtime_session_streams_pkey PRIMARY KEY (tenant_id, target_kind, target_id, session_id);



--
-- Name: agent_runtime_session_token_profiles agent_runtime_session_token_profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_token_profiles
    ADD CONSTRAINT agent_runtime_session_token_profiles_pkey PRIMARY KEY (tenant_id, profile_ref);



--
-- Name: agent_runtime_session_tool_projections agent_runtime_session_tool_projections_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_tool_projections
    ADD CONSTRAINT agent_runtime_session_tool_projections_pkey PRIMARY KEY (tenant_id, journal_ref, fact_ref);



--
-- Name: agent_runtime_sessions agent_runtime_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_sessions
    ADD CONSTRAINT agent_runtime_sessions_pkey PRIMARY KEY (tenant_id, target_kind, target_id, session_id);



--
-- Name: agent_runtime_team_task_results agent_runtime_team_task_resul_tenant_id_invocation_id_outpu_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_team_task_results
    ADD CONSTRAINT agent_runtime_team_task_resul_tenant_id_invocation_id_outpu_key UNIQUE (tenant_id, invocation_id, output_name);



--
-- Name: agent_runtime_team_task_results agent_runtime_team_task_results_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_team_task_results
    ADD CONSTRAINT agent_runtime_team_task_results_pkey PRIMARY KEY (tenant_id, result_id);



--
-- Name: agent_runtime_team_work_answers agent_runtime_team_work_answe_tenant_id_completion_event_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_team_work_answers
    ADD CONSTRAINT agent_runtime_team_work_answe_tenant_id_completion_event_id_key UNIQUE (tenant_id, completion_event_id);



--
-- Name: agent_runtime_team_work_answers agent_runtime_team_work_answers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_team_work_answers
    ADD CONSTRAINT agent_runtime_team_work_answers_pkey PRIMARY KEY (tenant_id, session_id, root_input_id);



--
-- Name: agent_runtime_team_work_answers agent_runtime_team_work_answers_tenant_id_message_event_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_team_work_answers
    ADD CONSTRAINT agent_runtime_team_work_answers_tenant_id_message_event_id_key UNIQUE (tenant_id, message_event_id);



--
-- Name: agent_runtime_team_work_answers agent_runtime_team_work_answers_tenant_id_message_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_team_work_answers
    ADD CONSTRAINT agent_runtime_team_work_answers_tenant_id_message_id_key UNIQUE (tenant_id, message_id);



--
-- Name: agent_runtime_team_work_answers agent_runtime_team_work_answers_tenant_id_result_ref_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_team_work_answers
    ADD CONSTRAINT agent_runtime_team_work_answers_tenant_id_result_ref_key UNIQUE (tenant_id, result_ref);



--
-- Name: agent_runtime_workflow_child_execution_sources agent_runtime_workflow_child_execution_sources_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_workflow_child_execution_sources
    ADD CONSTRAINT agent_runtime_workflow_child_execution_sources_pkey PRIMARY KEY (tenant_id, child_execution_id);



--
-- Name: agent_runtime_workflow_child_preparations agent_runtime_workflow_child_preparations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_workflow_child_preparations
    ADD CONSTRAINT agent_runtime_workflow_child_preparations_pkey PRIMARY KEY (tenant_id, parent_execution_id, node_id, visit, call_index);



--
-- Name: agent_runtime_workflow_script_calls agent_runtime_workflow_script_calls_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_workflow_script_calls
    ADD CONSTRAINT agent_runtime_workflow_script_calls_pkey PRIMARY KEY (tenant_id, execution_id, node_id, visit, call_index);



--
-- Name: agent_runtime_session_streams domain_stream_subject_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_streams
    ADD CONSTRAINT domain_stream_subject_uq UNIQUE (tenant_id, target_kind, target_id, session_id, subject_kind, subject_id);



--
-- Name: agent_runtime_execution_actions execution_actions_batch_slot_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_actions
    ADD CONSTRAINT execution_actions_batch_slot_uq UNIQUE (tenant_id, run_id, batch_source_attempt, batch_slot);



--
-- Name: agent_runtime_execution_actions execution_actions_ordinal_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_actions
    ADD CONSTRAINT execution_actions_ordinal_uq UNIQUE (tenant_id, run_id, action_ordinal);



--
-- Name: agent_runtime_execution_actions execution_actions_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_actions
    ADD CONSTRAINT execution_actions_pk PRIMARY KEY (tenant_id, action_id);



--
-- Name: agent_runtime_execution_actions execution_actions_run_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_actions
    ADD CONSTRAINT execution_actions_run_uq UNIQUE (tenant_id, action_id, run_id);



--
-- Name: agent_runtime_execution_attempts execution_attempts_action_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_attempts
    ADD CONSTRAINT execution_attempts_action_uq UNIQUE (tenant_id, action_id, attempt_id);



--
-- Name: agent_runtime_execution_attempts execution_attempts_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_attempts
    ADD CONSTRAINT execution_attempts_pk PRIMARY KEY (tenant_id, attempt_id);



--
-- Name: agent_runtime_execution_attempts execution_attempts_run_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_attempts
    ADD CONSTRAINT execution_attempts_run_uq UNIQUE (tenant_id, run_id, attempt_id);



--
-- Name: agent_runtime_execution_batch_members execution_batch_members_action_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_batch_members
    ADD CONSTRAINT execution_batch_members_action_uq UNIQUE (tenant_id, run_id, source_attempt, action_id);



--
-- Name: agent_runtime_execution_batch_members execution_batch_members_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_batch_members
    ADD CONSTRAINT execution_batch_members_pk PRIMARY KEY (tenant_id, run_id, source_attempt, slot);



--
-- Name: agent_runtime_execution_child_runs execution_child_runs_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_child_runs
    ADD CONSTRAINT execution_child_runs_pk PRIMARY KEY (tenant_id, parent_action_id, child_run_id);



--
-- Name: agent_runtime_execution_events execution_events_identity_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_events
    ADD CONSTRAINT execution_events_identity_uq UNIQUE (tenant_id, session_id, event_id);



--
-- Name: agent_runtime_execution_events execution_events_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_events
    ADD CONSTRAINT execution_events_pk PRIMARY KEY (tenant_id, session_id, event_sequence);



--
-- Name: agent_runtime_execution_materials execution_materials_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_materials
    ADD CONSTRAINT execution_materials_pk PRIMARY KEY (tenant_id, reference);



--
-- Name: agent_runtime_execution_plan_child_runs execution_plan_child_runs_admission_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_plan_child_runs
    ADD CONSTRAINT execution_plan_child_runs_admission_uq UNIQUE (tenant_id, admission_id);



--
-- Name: agent_runtime_execution_plan_child_runs execution_plan_child_runs_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_plan_child_runs
    ADD CONSTRAINT execution_plan_child_runs_pk PRIMARY KEY (tenant_id, child_run_id);



--
-- Name: agent_runtime_execution_plan_controls execution_plan_controls_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_plan_controls
    ADD CONSTRAINT execution_plan_controls_pk PRIMARY KEY (tenant_id, source_action_id);



--
-- Name: agent_runtime_execution_program_child_runs execution_program_child_runs_child_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_program_child_runs
    ADD CONSTRAINT execution_program_child_runs_child_uq UNIQUE (tenant_id, child_run_id);



--
-- Name: agent_runtime_execution_program_child_runs execution_program_child_runs_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_program_child_runs
    ADD CONSTRAINT execution_program_child_runs_pk PRIMARY KEY (tenant_id, parent_run_id, parent_action_id, parent_attempt_id, ordinal);



--
-- Name: agent_runtime_execution_runs execution_runs_full_owner_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_runs
    ADD CONSTRAINT execution_runs_full_owner_uq UNIQUE (tenant_id, target_kind, target_id, agent_id, session_id, end_user_id, run_id);



--
-- Name: agent_runtime_execution_runs execution_runs_input_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_runs
    ADD CONSTRAINT execution_runs_input_uq UNIQUE (tenant_id, session_id, target_kind, target_id, input_id);



--
-- Name: agent_runtime_execution_runs execution_runs_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_runs
    ADD CONSTRAINT execution_runs_pk PRIMARY KEY (tenant_id, run_id);



--
-- Name: agent_runtime_execution_runs execution_runs_session_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_runs
    ADD CONSTRAINT execution_runs_session_uq UNIQUE (tenant_id, run_id, session_id);



--
-- Name: agent_runtime_execution_runs execution_runs_workflow_subject_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_runs
    ADD CONSTRAINT execution_runs_workflow_subject_uq UNIQUE (tenant_id, target_kind, target_id, agent_id, session_id, subject_kind, subject_id, run_id);



--
-- Name: agent_runtime_execution_sessions execution_sessions_owner_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_sessions
    ADD CONSTRAINT execution_sessions_owner_uq UNIQUE (tenant_id, session_id, target_kind, target_id, end_user_id);



--
-- Name: agent_runtime_execution_sessions execution_sessions_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_sessions
    ADD CONSTRAINT execution_sessions_pk PRIMARY KEY (tenant_id, session_id);



--
-- Name: agent_runtime_execution_sessions execution_sessions_subject_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_sessions
    ADD CONSTRAINT execution_sessions_subject_uq UNIQUE (tenant_id, session_id, target_kind, target_id, subject_kind, subject_id);



--
-- Name: agent_runtime_execution_supplements execution_supplements_ordinal_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_supplements
    ADD CONSTRAINT execution_supplements_ordinal_uq UNIQUE (tenant_id, run_id, ordinal);



--
-- Name: agent_runtime_execution_supplements execution_supplements_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_supplements
    ADD CONSTRAINT execution_supplements_pk PRIMARY KEY (tenant_id, run_id, input_id);



--
-- Name: agent_runtime_execution_wait_children execution_wait_children_entry_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_wait_children
    ADD CONSTRAINT execution_wait_children_entry_uq UNIQUE (tenant_id, action_id, generation, delegation_id);



--
-- Name: agent_runtime_execution_wait_children execution_wait_children_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_wait_children
    ADD CONSTRAINT execution_wait_children_pk PRIMARY KEY (tenant_id, action_id, generation, ordinal);



--
-- Name: agent_runtime_execution_wait_children execution_wait_children_run_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_wait_children
    ADD CONSTRAINT execution_wait_children_run_uq UNIQUE (tenant_id, action_id, generation, child_run_id);



--
-- Name: agent_runtime_execution_wait_requests execution_wait_requests_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_wait_requests
    ADD CONSTRAINT execution_wait_requests_pk PRIMARY KEY (tenant_id, action_id, generation, input_key);



--
-- Name: agent_runtime_execution_waits execution_waits_input_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_waits
    ADD CONSTRAINT execution_waits_input_uq UNIQUE (tenant_id, run_id, input_id);



--
-- Name: agent_runtime_execution_waits execution_waits_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_waits
    ADD CONSTRAINT execution_waits_pk PRIMARY KEY (tenant_id, action_id, generation);



--
-- Name: agent_runtime_execution_waits execution_waits_run_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_waits
    ADD CONSTRAINT execution_waits_run_uq UNIQUE (tenant_id, run_id, action_id, generation);



--
-- Name: agent_runtime_execution_waits execution_waits_source_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_waits
    ADD CONSTRAINT execution_waits_source_uq UNIQUE (tenant_id, source_attempt);



--
-- Name: agent_runtime_execution_workflow_callback_deliveries execution_workflow_callback_deliveries_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_workflow_callback_deliveries
    ADD CONSTRAINT execution_workflow_callback_deliveries_pk PRIMARY KEY (tenant_id, delivery_id);



--
-- Name: agent_runtime_memory_embeddings memory_embeddings_invocation_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_embeddings
    ADD CONSTRAINT memory_embeddings_invocation_uq UNIQUE (tenant_id, invocation_id);



--
-- Name: agent_runtime_memory_embeddings memory_embeddings_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_embeddings
    ADD CONSTRAINT memory_embeddings_pk PRIMARY KEY (tenant_id, fact_id, content_revision, projection_version, analyzer_version, model_id, model_revision, dimensions);



--
-- Name: agent_runtime_memory_query_embeddings memory_query_embeddings_invocation_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_query_embeddings
    ADD CONSTRAINT memory_query_embeddings_invocation_uq UNIQUE (tenant_id, invocation_id);



--
-- Name: agent_runtime_memory_query_embeddings memory_query_embeddings_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_query_embeddings
    ADD CONSTRAINT memory_query_embeddings_pk PRIMARY KEY (tenant_id, operation_id);



--
-- Name: agent_runtime_memory_search_documents memory_search_documents_digest_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_search_documents
    ADD CONSTRAINT memory_search_documents_digest_uq UNIQUE (tenant_id, fact_id, content_revision, projection_version, analyzer_version, text_digest);



--
-- Name: agent_runtime_memory_search_documents memory_search_documents_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_search_documents
    ADD CONSTRAINT memory_search_documents_pk PRIMARY KEY (tenant_id, fact_id, content_revision, projection_version, analyzer_version);



--
-- Name: agent_runtime_memory_conflict_audits pk_agent_runtime_memory_conflict_audits; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_conflict_audits
    ADD CONSTRAINT pk_agent_runtime_memory_conflict_audits PRIMARY KEY (tenant_id, audit_ref);



--
-- Name: agent_runtime_memory_extraction_evidence pk_agent_runtime_memory_extraction_evidence; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_evidence
    ADD CONSTRAINT pk_agent_runtime_memory_extraction_evidence PRIMARY KEY (tenant_id, source_target_kind, source_target_id, source_session_id, source_event_id, extractor_version, extraction_digest);



--
-- Name: agent_runtime_memory_extraction_jobs pk_agent_runtime_memory_extraction_job; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_jobs
    ADD CONSTRAINT pk_agent_runtime_memory_extraction_job PRIMARY KEY (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key);



--
-- Name: agent_runtime_memory_fact_heads pk_agent_runtime_memory_fact_heads; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_fact_heads
    ADD CONSTRAINT pk_agent_runtime_memory_fact_heads PRIMARY KEY (tenant_id, fact_id);



--
-- Name: agent_runtime_memory_fact_revisions pk_agent_runtime_memory_fact_revisions; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_fact_revisions
    ADD CONSTRAINT pk_agent_runtime_memory_fact_revisions PRIMARY KEY (tenant_id, fact_id, revision);



--
-- Name: agent_runtime_memory_fact_sources pk_agent_runtime_memory_fact_sources; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_fact_sources
    ADD CONSTRAINT pk_agent_runtime_memory_fact_sources PRIMARY KEY (tenant_id, fact_id, revision, source_ordinal);



--
-- Name: agent_runtime_memory_operations pk_agent_runtime_memory_operations; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_operations
    ADD CONSTRAINT pk_agent_runtime_memory_operations PRIMARY KEY (tenant_id, idempotency_key);



--
-- Name: agent_runtime_memory_usage_projection pk_agent_runtime_memory_usage_projection; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_usage_projection
    ADD CONSTRAINT pk_agent_runtime_memory_usage_projection PRIMARY KEY (tenant_id, fact_id);



--
-- Name: agent_runtime_conversation_task_bindings pk_conversation_task_bindings; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_conversation_task_bindings
    ADD CONSTRAINT pk_conversation_task_bindings PRIMARY KEY (tenant_id, target_kind, target_id, session_id, agent_id);



--
-- Name: agent_runtime_conversation_task_operations pk_conversation_task_operations; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_conversation_task_operations
    ADD CONSTRAINT pk_conversation_task_operations PRIMARY KEY (tenant_id, target_kind, target_id, session_id, agent_id, event_id);



--
-- Name: agent_runtime_conversation_task_roots pk_conversation_task_roots; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_conversation_task_roots
    ADD CONSTRAINT pk_conversation_task_roots PRIMARY KEY (tenant_id, task_id);



--
-- Name: agent_runtime_conversation_tasks pk_conversation_tasks; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_conversation_tasks
    ADD CONSTRAINT pk_conversation_tasks PRIMARY KEY (tenant_id, task_id);



--
-- Name: agent_runtime_memory_extraction_candidates pk_memory_extraction_candidate; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_candidates
    ADD CONSTRAINT pk_memory_extraction_candidate PRIMARY KEY (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, candidate_ordinal);



--
-- Name: runtime_budget_activity runtime_budget_activity_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_budget_activity
    ADD CONSTRAINT agent_runtime_budget_activity_pkey PRIMARY KEY (tenant_id, run_id, interval_id);



--
-- Name: runtime_budget_attempt_amounts runtime_budget_attempt_amounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_budget_attempt_amounts
    ADD CONSTRAINT agent_runtime_budget_attempt_amounts_pkey PRIMARY KEY (tenant_id, attempt_id, dimension);



--
-- Name: runtime_budget_attempts runtime_budget_attempts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_budget_attempts
    ADD CONSTRAINT agent_runtime_budget_attempts_pkey PRIMARY KEY (tenant_id, attempt_id);



--
-- Name: runtime_budget_balances runtime_budget_balances_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_budget_balances
    ADD CONSTRAINT agent_runtime_budget_balances_pkey PRIMARY KEY (tenant_id, scope_kind, scope_id, dimension);



--
-- Name: runtime_budget_closing_targets runtime_budget_closing_targets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_budget_closing_targets
    ADD CONSTRAINT agent_runtime_budget_closing_targets_pkey PRIMARY KEY (tenant_id, run_id, target_handle);



--
-- Name: runtime_budget_result_receipts runtime_budget_result_receipts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_budget_result_receipts
    ADD CONSTRAINT agent_runtime_budget_result_receipts_pkey PRIMARY KEY (tenant_id, attempt_id, result_reference);



--
-- Name: runtime_budget_roots runtime_budget_roots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_budget_roots
    ADD CONSTRAINT agent_runtime_budget_roots_pkey PRIMARY KEY (tenant_id, root_id);



--
-- Name: runtime_budget_runs runtime_budget_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_budget_runs
    ADD CONSTRAINT agent_runtime_budget_runs_pkey PRIMARY KEY (tenant_id, run_id);



--
-- Name: agent_runtime_session_checkpoint_bodies session_checkpoint_bodies_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_checkpoint_bodies
    ADD CONSTRAINT session_checkpoint_bodies_pk PRIMARY KEY (tenant_id, run_id, content_hash);



--
-- Name: agent_runtime_session_checkpoint_body_refs session_checkpoint_body_refs_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_checkpoint_body_refs
    ADD CONSTRAINT session_checkpoint_body_refs_pk PRIMARY KEY (tenant_id, run_id, checkpoint_hash, body_hash);



--
-- Name: agent_runtime_session_event_bodies session_event_bodies_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_event_bodies
    ADD CONSTRAINT session_event_bodies_pk PRIMARY KEY (tenant_id, target_kind, target_id, session_id, content_hash);



--
-- Name: agent_runtime_session_event_body_refs session_event_body_refs_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_event_body_refs
    ADD CONSTRAINT session_event_body_refs_pk PRIMARY KEY (tenant_id, target_kind, target_id, session_id, event_id, content_hash);



--
-- Name: agent_runtime_session_event_tombstones session_event_tombstones_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_event_tombstones
    ADD CONSTRAINT session_event_tombstones_pk PRIMARY KEY (tenant_id, target_kind, target_id, session_id, event_id);



--
-- Name: agent_runtime_session_event_tombstones session_event_tombstones_sequence; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_event_tombstones
    ADD CONSTRAINT session_event_tombstones_sequence UNIQUE (tenant_id, target_kind, target_id, session_id, session_sequence);



--
-- Name: agent_runtime_session_history_retirements session_history_retirements_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_history_retirements
    ADD CONSTRAINT session_history_retirements_pk PRIMARY KEY (tenant_id, target_kind, target_id, session_id);



--
-- Name: agent_runtime_session_output_chunks session_output_chunks_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_output_chunks
    ADD CONSTRAINT session_output_chunks_pk PRIMARY KEY (tenant_id, run_id, message_id, content_hash);



--
-- Name: agent_runtime_session_owners session_owners_subject_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_owners
    ADD CONSTRAINT session_owners_subject_uq UNIQUE (tenant_id, target_kind, target_id, session_id, subject_kind, subject_id);



--
-- Name: agent_runtime_action_attempt_facts uq_agent_runtime_action_attempt_fact_attempt_ref; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_action_attempt_facts
    ADD CONSTRAINT uq_agent_runtime_action_attempt_fact_attempt_ref UNIQUE (tenant_id, action_id, attempt_id, fact_ref);



--
-- Name: agent_runtime_action_attempt_facts uq_agent_runtime_action_attempt_fact_identity; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_action_attempt_facts
    ADD CONSTRAINT uq_agent_runtime_action_attempt_fact_identity UNIQUE (tenant_id, action_id, invocation_id, fact_ref);



--
-- Name: agent_runtime_action_attempt_facts uq_agent_runtime_action_attempt_fact_ref; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_action_attempt_facts
    ADD CONSTRAINT uq_agent_runtime_action_attempt_fact_ref UNIQUE (tenant_id, fact_ref);



--
-- Name: agent_runtime_actions uq_agent_runtime_action_idempotency; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_actions
    ADD CONSTRAINT uq_agent_runtime_action_idempotency UNIQUE (tenant_id, idempotency_key);



--
-- Name: agent_runtime_actions uq_agent_runtime_action_invocation; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_actions
    ADD CONSTRAINT uq_agent_runtime_action_invocation UNIQUE (tenant_id, action_id, invocation_id);



--
-- Name: agent_runtime_action_state_publications uq_agent_runtime_action_state_publication_revision; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_action_state_publications
    ADD CONSTRAINT uq_agent_runtime_action_state_publication_revision UNIQUE (tenant_id, source_id, source_revision);



--
-- Name: agent_runtime_callback_registrations uq_agent_runtime_callback_action_binding; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_callback_registrations
    ADD CONSTRAINT uq_agent_runtime_callback_action_binding UNIQUE (tenant_id, callback_id, action_run_id, action_id, action_invocation_id);



--
-- Name: agent_runtime_dag_admissions uq_agent_runtime_dag_admission_task_run; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_dag_admissions
    ADD CONSTRAINT uq_agent_runtime_dag_admission_task_run UNIQUE (tenant_id, session_id, root_input_id, task_id, run_id);



--
-- Name: agent_runtime_executions uq_agent_runtime_execution_full_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_executions
    ADD CONSTRAINT uq_agent_runtime_execution_full_owner UNIQUE (tenant_id, execution_id, target_kind, target_id, agent_id, session_id, end_user_id, run_id);



--
-- Name: agent_runtime_execution_state_publications uq_agent_runtime_execution_state_publication_revision; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_state_publications
    ADD CONSTRAINT uq_agent_runtime_execution_state_publication_revision UNIQUE (tenant_id, source_id, source_revision);



--
-- Name: agent_runtime_input_ledger uq_agent_runtime_input_ledger_execution; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_input_ledger
    ADD CONSTRAINT uq_agent_runtime_input_ledger_execution UNIQUE (tenant_id, target_kind, target_id, session_id, agent_id, end_user_id, input_id);



--
-- Name: agent_runtime_input_ledger uq_agent_runtime_input_ledger_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_input_ledger
    ADD CONSTRAINT uq_agent_runtime_input_ledger_owner UNIQUE (tenant_id, target_kind, target_id, session_id, end_user_id, input_id);



--
-- Name: agent_runtime_memory_fact_sources uq_agent_runtime_memory_fact_source_event; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_fact_sources
    ADD CONSTRAINT uq_agent_runtime_memory_fact_source_event UNIQUE (tenant_id, fact_id, revision, source_event_id);



--
-- Name: agent_runtime_runs uq_agent_runtime_run_full_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_runs
    ADD CONSTRAINT uq_agent_runtime_run_full_owner UNIQUE (tenant_id, target_kind, target_id, agent_id, session_id, end_user_id, run_id);



--
-- Name: agent_runtime_runs uq_agent_runtime_run_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_runs
    ADD CONSTRAINT uq_agent_runtime_run_owner UNIQUE (tenant_id, target_kind, target_id, session_id, run_id);



--
-- Name: agent_runtime_run_state_publications uq_agent_runtime_run_state_publication_revision; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_run_state_publications
    ADD CONSTRAINT uq_agent_runtime_run_state_publication_revision UNIQUE (tenant_id, source_id, source_revision);



--
-- Name: agent_runtime_session_events uq_agent_runtime_session_event_identity; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_events
    ADD CONSTRAINT uq_agent_runtime_session_event_identity UNIQUE (tenant_id, target_kind, target_id, session_id, event_id);



--
-- Name: agent_runtime_session_events uq_agent_runtime_session_event_outbox_reference; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_events
    ADD CONSTRAINT uq_agent_runtime_session_event_outbox_reference UNIQUE (tenant_id, target_kind, target_id, session_id, agent_id, end_user_id, session_sequence, event_id);



--
-- Name: agent_runtime_sessions uq_agent_runtime_session_full_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_sessions
    ADD CONSTRAINT uq_agent_runtime_session_full_owner UNIQUE (tenant_id, target_kind, target_id, session_id, end_user_id);



--
-- Name: agent_runtime_session_inbox uq_agent_runtime_session_inbox_sequence; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_inbox
    ADD CONSTRAINT uq_agent_runtime_session_inbox_sequence UNIQUE (tenant_id, target_kind, target_id, session_id, inbox_sequence);



--
-- Name: agent_runtime_session_input_streams uq_agent_runtime_session_input_stream_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_input_streams
    ADD CONSTRAINT uq_agent_runtime_session_input_stream_owner UNIQUE (tenant_id, target_kind, target_id, session_id, end_user_id);



--
-- Name: agent_runtime_session_mutations uq_agent_runtime_session_mutation_output; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_mutations
    ADD CONSTRAINT uq_agent_runtime_session_mutation_output UNIQUE (tenant_id, target_kind, target_id, session_id, end_user_id, output_revision);



--
-- Name: agent_runtime_session_outbox uq_agent_runtime_session_outbox_sequence; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_outbox
    ADD CONSTRAINT uq_agent_runtime_session_outbox_sequence UNIQUE (tenant_id, target_kind, target_id, session_id, session_sequence);



--
-- Name: agent_runtime_session_owners uq_agent_runtime_session_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_owners
    ADD CONSTRAINT uq_agent_runtime_session_owner UNIQUE (tenant_id, target_kind, target_id, session_id, end_user_id);



--
-- Name: agent_runtime_session_streams uq_agent_runtime_session_stream_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_streams
    ADD CONSTRAINT uq_agent_runtime_session_stream_owner UNIQUE (tenant_id, target_kind, target_id, session_id, end_user_id);



--
-- Name: agent_runtime_workflow_child_preparations uq_agent_runtime_workflow_child_preparation_full_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_workflow_child_preparations
    ADD CONSTRAINT uq_agent_runtime_workflow_child_preparation_full_owner UNIQUE (tenant_id, parent_execution_id, node_id, visit, call_index, target_kind, target_id, agent_id, session_id, end_user_id, run_id);



--
-- Name: agent_runtime_workflow_child_execution_sources uq_agent_runtime_workflow_child_source_branch; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_workflow_child_execution_sources
    ADD CONSTRAINT uq_agent_runtime_workflow_child_source_branch UNIQUE (tenant_id, parent_execution_id, node_id, visit, call_index, branch_id);



--
-- Name: agent_runtime_workflow_child_execution_sources uq_agent_runtime_workflow_child_source_ordinal; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_workflow_child_execution_sources
    ADD CONSTRAINT uq_agent_runtime_workflow_child_source_ordinal UNIQUE (tenant_id, parent_execution_id, node_id, visit, call_index, child_ordinal);



--
-- Name: agent_runtime_workflow_script_calls uq_agent_runtime_workflow_script_task; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_workflow_script_calls
    ADD CONSTRAINT uq_agent_runtime_workflow_script_task UNIQUE (tenant_id, task_id);



--
-- Name: agent_runtime_conversation_task_roots uq_conversation_task_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_conversation_task_roots
    ADD CONSTRAINT uq_conversation_task_owner UNIQUE (tenant_id, task_id, agent_id, end_user_id);



--
-- Name: agent_runtime_conversation_tasks uq_conversation_task_projection_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_conversation_tasks
    ADD CONSTRAINT uq_conversation_task_projection_owner UNIQUE (tenant_id, task_id, agent_id, end_user_id);



--
-- Name: agent_runtime_dag_admissions uq_dag_admission_run; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_dag_admissions
    ADD CONSTRAINT uq_dag_admission_run UNIQUE (tenant_id, admission_id, run_id);



--
-- Name: agent_runtime_memory_extraction_candidates uq_memory_extraction_candidate_command; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_candidates
    ADD CONSTRAINT uq_memory_extraction_candidate_command UNIQUE (tenant_id, command_key);



--
-- Name: agent_runtime_workflow_child_preparations workflow_child_preparation_subject_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_workflow_child_preparations
    ADD CONSTRAINT workflow_child_preparation_subject_uq UNIQUE (tenant_id, parent_execution_id, node_id, visit, call_index, target_kind, target_id, agent_id, session_id, subject_kind, subject_id, run_id);



--
-- Name: agent_runtime_executions workflow_execution_subject_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_executions
    ADD CONSTRAINT workflow_execution_subject_uq UNIQUE (tenant_id, execution_id, target_kind, target_id, agent_id, session_id, subject_kind, subject_id, run_id);



--
-- Name: agent_runtime_callback_delivery_expired; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_callback_delivery_expired ON public.agent_runtime_callback_deliveries USING btree (tenant_id, lease_until, delivery_id) WHERE (status = 'PROCESSING'::text);



--
-- Name: agent_runtime_callback_delivery_ready; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_callback_delivery_ready ON public.agent_runtime_callback_deliveries USING btree (tenant_id, available_at, delivery_id) WHERE (status = 'READY'::text);



--
-- Name: agent_runtime_callback_delivery_registration; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_callback_delivery_registration ON public.agent_runtime_callback_deliveries USING btree (tenant_id, callback_id, recorded_at);



--
-- Name: agent_runtime_dag_active_session; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX agent_runtime_dag_active_session ON public.agent_runtime_dag_works USING btree (tenant_id, session_id) WHERE ((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'CANCELLING'::character varying])::text[]));



--
-- Name: agent_runtime_dag_member_run; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX agent_runtime_dag_member_run ON public.agent_runtime_dag_admissions USING btree (tenant_id, run_id) WHERE ((executor_type)::text = 'MEMBER'::text);



--
-- Name: agent_runtime_dag_recovery; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_dag_recovery ON public.agent_runtime_dag_works USING btree (tenant_id, updated_at) WHERE ((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'CANCELLING'::character varying])::text[]));



--
-- Name: agent_runtime_delegation_waits_pending_scan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_delegation_waits_pending_scan ON public.agent_runtime_delegation_waits USING btree (tenant_id, parent_run_id, wait_id, revision) WHERE (delivered = false);



--
-- Name: agent_runtime_delegations_active_scan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_delegations_active_scan ON public.agent_runtime_delegations USING btree (tenant_id, updated_epoch_second, updated_nano, parent_run_id, delegation_id) WHERE ((state)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'WAITING'::character varying])::text[]));



--
-- Name: agent_runtime_delegations_fixed_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_delegations_fixed_lookup ON public.agent_runtime_delegations USING btree (tenant_id, parent_run_id, delegation_id, operation_key, state, revision);



--
-- Name: agent_runtime_delegations_session_history; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_delegations_session_history ON public.agent_runtime_delegations USING btree (tenant_id, parent_session_id, parent_target_kind, parent_target_id, parent_end_user_id, created_epoch_second, created_nano, delegation_id, parent_run_id);



--
-- Name: agent_runtime_event_expired; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_event_expired ON public.agent_runtime_event_deliveries USING btree (tenant_id, lease_second, lease_nano) WHERE (state = 'DELIVERING'::text);



--
-- Name: agent_runtime_event_ready; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_event_ready ON public.agent_runtime_event_deliveries USING btree (tenant_id, available_second, available_nano, event_id, destination_id) WHERE (state = 'PENDING'::text);



--
-- Name: agent_runtime_inbox_lanes; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_inbox_lanes ON public.agent_runtime_session_inbox USING btree (tenant_id, session_id, lane, inbox_sequence) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying])::text[]));



--
-- Name: agent_runtime_run_record_arch_tenant_id_session_id_causatio_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_run_record_arch_tenant_id_session_id_causatio_idx ON public.agent_runtime_run_record_archives USING btree (tenant_id, session_id, causation_input_id, created_epoch_second, created_nano);



--
-- Name: agent_runtime_run_record_arch_tenant_id_status_lease_until__idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_run_record_arch_tenant_id_status_lease_until__idx ON public.agent_runtime_run_record_archives USING btree (tenant_id, status, lease_until_epoch_second, lease_until_nano, run_id) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'SUSPENDED'::character varying, 'RESUMING'::character varying])::text[]));



--
-- Name: agent_runtime_session_event_p_tenant_id_agent_id_session_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_session_event_p_tenant_id_agent_id_session_id_idx ON public.agent_runtime_session_event_payload_archives USING btree (tenant_id, agent_id, session_id, end_user_id, session_sequence);



--
-- Name: agent_runtime_session_event_p_tenant_id_session_id_root_inp_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_session_event_p_tenant_id_session_id_root_inp_idx ON public.agent_runtime_session_event_payload_archives USING btree (tenant_id, session_id, root_input_id, session_sequence);



--
-- Name: agent_runtime_session_event_p_tenant_id_target_kind_target__idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_session_event_p_tenant_id_target_kind_target__idx ON public.agent_runtime_session_event_payload_archives USING btree (tenant_id, target_kind, target_id, session_id, end_user_id, session_sequence);



--
-- Name: agent_runtime_session_event_p_tenant_id_target_kind_target_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_session_event_p_tenant_id_target_kind_target_idx1 ON public.agent_runtime_session_event_payload_archives USING btree (tenant_id, target_kind, target_id, session_id, run_id, event_type, session_sequence);



--
-- Name: agent_runtime_session_event_p_tenant_id_target_kind_target_idx2; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_session_event_p_tenant_id_target_kind_target_idx2 ON public.agent_runtime_session_event_payload_archives USING btree (tenant_id, target_kind, target_id, session_id, end_user_id, run_id, input_id, session_sequence);



--
-- Name: agent_runtime_session_event_p_tenant_id_target_kind_target_idx3; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_session_event_p_tenant_id_target_kind_target_idx3 ON public.agent_runtime_session_event_payload_archives USING btree (tenant_id, target_kind, target_id, session_id, run_id, "substring"(payload_canonical_json, '"messageId":"([^"]+)"'::text), session_sequence) WHERE (event_type = ANY (ARRAY['agent.model-answer'::text, 'agent.model-answer-adopted'::text]));



--
-- Name: agent_runtime_session_events_target_read; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_session_events_target_read ON public.agent_runtime_session_events USING btree (tenant_id, target_kind, target_id, session_id, end_user_id, session_sequence);



--
-- Name: agent_runtime_session_inbox_a_tenant_id_agent_id_session_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_session_inbox_a_tenant_id_agent_id_session_id_idx ON public.agent_runtime_session_inbox_archives USING btree (tenant_id, agent_id, session_id, inbox_sequence) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying])::text[]));



--
-- Name: agent_runtime_session_inbox_a_tenant_id_available_epoch_sec_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_session_inbox_a_tenant_id_available_epoch_sec_idx ON public.agent_runtime_session_inbox_archives USING btree (tenant_id, available_epoch_second, available_nano, session_id) WHERE ((status)::text = 'QUEUED'::text);



--
-- Name: agent_runtime_session_inbox_a_tenant_id_session_id_lane_inb_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_session_inbox_a_tenant_id_session_id_lane_inb_idx ON public.agent_runtime_session_inbox_archives USING btree (tenant_id, session_id, lane, inbox_sequence) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying])::text[]));



--
-- Name: agent_runtime_team_task_results_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_team_task_results_run ON public.agent_runtime_team_task_results USING btree (tenant_id, run_id, created_at, result_id);



--
-- Name: agent_runtime_team_task_results_work; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_team_task_results_work ON public.agent_runtime_team_task_results USING btree (tenant_id, session_id, root_input_id, task_id, output_name, created_at, result_id);



--
-- Name: agent_runtime_team_work_answers_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_team_work_answers_session ON public.agent_runtime_team_work_answers USING btree (tenant_id, session_id, created_at, root_input_id);



--
-- Name: execution_attempts_observation_sequence; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX execution_attempts_observation_sequence ON public.agent_runtime_execution_attempts USING btree (observation_sequence);



--
-- Name: execution_child_runs_child_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX execution_child_runs_child_idx ON public.agent_runtime_execution_child_runs USING btree (tenant_id, child_run_id);



--
-- Name: execution_events_run_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX execution_events_run_idx ON public.agent_runtime_execution_events USING btree (tenant_id, run_id, revision, event_sequence);



--
-- Name: execution_plan_child_runs_parent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX execution_plan_child_runs_parent_idx ON public.agent_runtime_execution_plan_child_runs USING btree (tenant_id, parent_run_id);



--
-- Name: execution_plan_controls_run_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX execution_plan_controls_run_idx ON public.agent_runtime_execution_plan_controls USING btree (tenant_id, run_id);



--
-- Name: execution_runs_due_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX execution_runs_due_idx ON public.agent_runtime_execution_runs USING btree (tenant_id, next_due_second, next_due_nano, run_id) WHERE (((phase)::text = ANY ((ARRAY['READY'::character varying, 'AWAITING_RESULT'::character varying])::text[])) AND (NOT cancelled) AND (next_due_second IS NOT NULL));



--
-- Name: execution_supplements_pending_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX execution_supplements_pending_idx ON public.agent_runtime_execution_supplements USING btree (tenant_id, run_id, ordinal) WHERE (applied_revision IS NULL);



--
-- Name: idx_agent_runtime_action_attempt_latest; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_runtime_action_attempt_latest ON public.agent_runtime_action_attempt_facts USING btree (tenant_id, action_id, attempt_id, evidence_revision DESC);



--
-- Name: idx_agent_runtime_action_attempt_resolution; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_runtime_action_attempt_resolution ON public.agent_runtime_action_attempt_facts USING btree (tenant_id, action_id, invocation_id, resolution_key) WHERE (resolution_key IS NOT NULL);



--
-- Name: idx_agent_runtime_action_publication_claim; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_runtime_action_publication_claim ON public.agent_runtime_action_state_publications USING btree (tenant_id, status, available_epoch_second, available_nano, lease_until_epoch_second, lease_until_nano, source_id, source_revision);



--
-- Name: idx_agent_runtime_action_recovery; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_runtime_action_recovery ON public.agent_runtime_actions USING btree (tenant_id, state, lease_until_epoch_second, lease_until_nano, updated_epoch_second, updated_nano, action_id) WHERE ((state)::text = 'CLAIMED'::text);



--
-- Name: idx_agent_runtime_execution_publication_claim; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_runtime_execution_publication_claim ON public.agent_runtime_execution_state_publications USING btree (tenant_id, status, available_epoch_second, available_nano, lease_until_epoch_second, lease_until_nano, source_id, source_revision);



--
-- Name: idx_agent_runtime_execution_recovery; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_runtime_execution_recovery ON public.agent_runtime_executions USING btree (tenant_id, state, lease_until_epoch_second, lease_until_nano, updated_epoch_second, updated_nano, execution_id) WHERE ((state)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'WAITING_HITL'::character varying, 'WAITING_EXTERNAL'::character varying, 'RESUMING'::character varying])::text[]));



--
-- Name: idx_agent_runtime_run_recovery; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_runtime_run_recovery ON public.agent_runtime_runs USING btree (tenant_id, status, lease_until_epoch_second, lease_until_nano, run_id) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'SUSPENDED'::character varying, 'RESUMING'::character varying])::text[]));



--
-- Name: idx_agent_runtime_run_state_publication_claim; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_runtime_run_state_publication_claim ON public.agent_runtime_run_state_publications USING btree (tenant_id, status, available_epoch_second, available_nano, lease_until_epoch_second, lease_until_nano, source_id, source_revision);



--
-- Name: idx_agent_runtime_session_events_read; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_runtime_session_events_read ON public.agent_runtime_session_events USING btree (tenant_id, agent_id, session_id, end_user_id, session_sequence);



--
-- Name: idx_agent_runtime_session_inbox_head; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_runtime_session_inbox_head ON public.agent_runtime_session_inbox USING btree (tenant_id, agent_id, session_id, inbox_sequence) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying])::text[]));



--
-- Name: idx_agent_runtime_session_inbox_schedule; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_runtime_session_inbox_schedule ON public.agent_runtime_session_inbox USING btree (tenant_id, available_epoch_second, available_nano, session_id) WHERE ((status)::text = 'QUEUED'::text);



--
-- Name: idx_agent_runtime_session_outbox_head; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_runtime_session_outbox_head ON public.agent_runtime_session_outbox USING btree (tenant_id, agent_id, session_id, session_sequence) WHERE (status = ANY (ARRAY['PENDING'::text, 'DELIVERING'::text]));



--
-- Name: idx_agent_runtime_session_outbox_pending_schedule; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_runtime_session_outbox_pending_schedule ON public.agent_runtime_session_outbox USING btree (tenant_id, status, available_epoch_second, available_nano, agent_id, session_id, session_sequence) WHERE (status = 'PENDING'::text);



--
-- Name: idx_runtime_action_requests_observation_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_runtime_action_requests_observation_run ON public.agent_runtime_session_action_requests USING btree (tenant_id, run_id);



--
-- Name: idx_runtime_runs_observation_root; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_runtime_runs_observation_root ON public.agent_runtime_runs USING btree (tenant_id, session_id, causation_input_id, created_epoch_second, created_nano);



--
-- Name: idx_runtime_session_events_observation_root; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_runtime_session_events_observation_root ON public.agent_runtime_session_events USING btree (tenant_id, session_id, root_input_id, session_sequence);



--
-- Name: idx_session_events_input_first_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_session_events_input_first_source ON public.agent_runtime_session_events USING btree (tenant_id, target_kind, target_id, session_id, end_user_id, run_id, input_id, session_sequence);



--
-- Name: idx_session_events_run_type_sequence; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_session_events_run_type_sequence ON public.agent_runtime_session_events USING btree (tenant_id, target_kind, target_id, session_id, run_id, event_type, session_sequence);



--
-- Name: input_ledger_archives__owner_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX input_ledger_archives__owner_idx ON public.agent_runtime_input_ledger_archives USING btree (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id);



--
-- Name: ix_agent_runtime_memory_extraction_content; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_agent_runtime_memory_extraction_content ON public.agent_runtime_memory_fact_revisions USING btree (tenant_id, extraction_digest, fact_id) WHERE (revision = 1);



--
-- Name: ix_agent_runtime_memory_extraction_evidence_fact; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_agent_runtime_memory_extraction_evidence_fact ON public.agent_runtime_memory_extraction_evidence USING btree (tenant_id, fact_id, revision);



--
-- Name: ix_agent_runtime_memory_extraction_ready; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_agent_runtime_memory_extraction_ready ON public.agent_runtime_memory_extraction_jobs USING btree (tenant_id, status, available_at, created_at);



--
-- Name: ix_agent_runtime_memory_query; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_agent_runtime_memory_query ON public.agent_runtime_memory_fact_heads USING btree (tenant_id, end_user_id, agent_id, scope, type_id, current_revision);



--
-- Name: ix_conversation_task_directory; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_conversation_task_directory ON public.agent_runtime_conversation_tasks USING btree (tenant_id, agent_id, end_user_id, updated_second DESC, updated_nano DESC, task_id);



--
-- Name: ix_memory_extraction_candidate_result; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_memory_extraction_candidate_result ON public.agent_runtime_memory_extraction_candidates USING btree (tenant_id, fact_id, fact_revision) WHERE (fact_id IS NOT NULL);



--
-- Name: ix_memory_extraction_candidate_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_memory_extraction_candidate_target ON public.agent_runtime_memory_extraction_candidates USING btree (tenant_id, target_fact_id, target_revision) WHERE (target_fact_id IS NOT NULL);



--
-- Name: ix_memory_group_current; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_memory_group_current ON public.agent_runtime_memory_fact_heads USING btree (tenant_id, group_binding_id, scope, subject_end_user_id, type_id) WHERE (group_binding_id IS NOT NULL);



--
-- Name: ix_memory_session_current; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_memory_session_current ON public.agent_runtime_memory_fact_heads USING btree (tenant_id, session_id, type_id) WHERE ((scope)::text = 'SESSION'::text);



--
-- Name: memory_embeddings_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX memory_embeddings_pending ON public.agent_runtime_memory_embeddings USING btree (tenant_id, updated_at, fact_id) WHERE ((state)::text = 'QUEUED'::text);



--
-- Name: memory_extraction_alias_revision; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX memory_extraction_alias_revision ON public.agent_runtime_memory_extraction_fact_aliases USING btree (tenant_id, fact_id, fact_revision);



--
-- Name: memory_extraction_system_run_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX memory_extraction_system_run_unique ON public.agent_runtime_memory_extraction_jobs USING btree (tenant_id, system_run_id) WHERE (system_run_id IS NOT NULL);



--
-- Name: memory_search_documents_terms; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX memory_search_documents_terms ON public.agent_runtime_memory_search_documents USING gin (lexical_terms);



--
-- Name: run_mutation_archives__owner_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX run_mutation_archives__owner_idx ON public.agent_runtime_run_mutation_archives USING btree (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id);



--
-- Name: run_publication_mutation_archives__owner_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX run_publication_mutation_archives__owner_idx ON public.agent_runtime_run_publication_mutation_archives USING btree (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id);



--
-- Name: run_record_archives__owner_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX run_record_archives__owner_idx ON public.agent_runtime_run_record_archives USING btree (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id);



--
-- Name: runtime_budget_attempts_recovery; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_budget_attempts_recovery ON public.agent_runtime_budget_attempts USING btree (tenant_id, run_id, state);



--
-- Name: runtime_budget_runs_root; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agent_runtime_budget_runs_root ON public.agent_runtime_budget_runs USING btree (tenant_id, root_id);



--
-- Name: session_admission_prepare_archives__owner_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX session_admission_prepare_archives__owner_idx ON public.agent_runtime_session_admission_prepare_archives USING btree (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id);



--
-- Name: session_answer_message_bounds; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX session_answer_message_bounds ON public.agent_runtime_session_events USING btree (tenant_id, target_kind, target_id, session_id, run_id, "substring"(payload_canonical_json, '"messageId":"([^"]+)"'::text), session_sequence) WHERE (event_type = ANY (ARRAY['agent.model-answer'::text, 'agent.model-answer-adopted'::text]));



--
-- Name: session_checkpoint_archive_body_refs_body_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX session_checkpoint_archive_body_refs_body_idx ON public.agent_runtime_session_checkpoint_archive_body_refs USING btree (tenant_id, run_id, body_hash);



--
-- Name: session_checkpoint_archives__owner_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX session_checkpoint_archives__owner_idx ON public.agent_runtime_session_checkpoint_archives USING btree (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id);



--
-- Name: session_checkpoint_body_archives__owner_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX session_checkpoint_body_archives__owner_idx ON public.agent_runtime_session_checkpoint_body_archives USING btree (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id);



--
-- Name: session_checkpoint_body_refs_body_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX session_checkpoint_body_refs_body_idx ON public.agent_runtime_session_checkpoint_body_refs USING btree (tenant_id, run_id, body_hash);



--
-- Name: session_closed_history_scan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX session_closed_history_scan ON public.agent_runtime_sessions USING btree (tenant_id, target_kind, target_id, session_id) WHERE ((status)::text = 'CLOSED'::text);



--
-- Name: session_commit_journal_archives__owner_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX session_commit_journal_archives__owner_idx ON public.agent_runtime_session_commit_journal_archives USING btree (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id);



--
-- Name: session_event_body_refs_body_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX session_event_body_refs_body_idx ON public.agent_runtime_session_event_body_refs USING btree (tenant_id, target_kind, target_id, session_id, content_hash);



--
-- Name: session_event_payload_archives__owner_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX session_event_payload_archives__owner_idx ON public.agent_runtime_session_event_payload_archives USING btree (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id);



--
-- Name: session_inbox_archives__owner_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX session_inbox_archives__owner_idx ON public.agent_runtime_session_inbox_archives USING btree (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id);



--
-- Name: session_inbox_mutation_archives__owner_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX session_inbox_mutation_archives__owner_idx ON public.agent_runtime_session_inbox_mutation_archives USING btree (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id);



--
-- Name: session_retirement_runs_gate_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX session_retirement_runs_gate_idx ON public.agent_runtime_runs USING btree (tenant_id, target_kind, target_id, session_id, end_user_id, status, updated_epoch_second, updated_nano, run_id);



--
-- Name: agent_runtime_callback_registrations agent_runtime_callback_action_binding_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_callback_action_binding_immutable BEFORE UPDATE ON public.agent_runtime_callback_registrations FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_callback_action_binding();



--
-- Name: agent_runtime_callback_registrations agent_runtime_callback_action_binding_sources; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER agent_runtime_callback_action_binding_sources AFTER INSERT ON public.agent_runtime_callback_registrations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.assert_agent_runtime_callback_action_binding();



--
-- Name: agent_runtime_callback_audits agent_runtime_callback_audit_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_callback_audit_guard BEFORE DELETE OR UPDATE ON public.agent_runtime_callback_audits FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_callback_journal();



--
-- Name: agent_runtime_callback_deliveries agent_runtime_callback_delivery_closure; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER agent_runtime_callback_delivery_closure AFTER UPDATE ON public.agent_runtime_callback_deliveries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.check_agent_runtime_callback_delivery_closure();



--
-- Name: agent_runtime_callback_deliveries agent_runtime_callback_delivery_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_callback_delivery_guard BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_callback_deliveries FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_callback_delivery();



--
-- Name: agent_runtime_callback_delivery_operations agent_runtime_callback_delivery_operation_closure; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER agent_runtime_callback_delivery_operation_closure AFTER INSERT ON public.agent_runtime_callback_delivery_operations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.check_agent_runtime_callback_delivery_closure();



--
-- Name: agent_runtime_callback_delivery_operations agent_runtime_callback_delivery_operation_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_callback_delivery_operation_guard BEFORE DELETE OR UPDATE ON public.agent_runtime_callback_delivery_operations FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_callback_journal();



--
-- Name: agent_runtime_callback_registrations agent_runtime_callback_registration_closure; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER agent_runtime_callback_registration_closure AFTER UPDATE ON public.agent_runtime_callback_registrations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.check_agent_runtime_callback_registration_closure();



--
-- Name: agent_runtime_callback_registrations agent_runtime_callback_registration_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_callback_registration_guard BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_callback_registrations FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_callback_registration();



--
-- Name: agent_runtime_callback_registration_operations agent_runtime_callback_registration_operation_closure; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER agent_runtime_callback_registration_operation_closure AFTER INSERT ON public.agent_runtime_callback_registration_operations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.check_agent_runtime_callback_registration_closure();



--
-- Name: agent_runtime_callback_registration_operations agent_runtime_callback_registration_operation_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_callback_registration_operation_guard BEFORE DELETE OR UPDATE ON public.agent_runtime_callback_registration_operations FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_callback_journal();



--
-- Name: agent_runtime_execution_runs agent_runtime_dag_yield; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_dag_yield AFTER UPDATE OF status ON public.agent_runtime_execution_runs FOR EACH ROW EXECUTE FUNCTION public.release_dag_capacity_on_run_yield();



--
-- Name: agent_runtime_executions agent_runtime_execution_closed; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER agent_runtime_execution_closed AFTER INSERT OR UPDATE ON public.agent_runtime_executions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.assert_agent_runtime_execution_closed();



--
-- Name: agent_runtime_executions agent_runtime_execution_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_execution_guard BEFORE DELETE OR UPDATE ON public.agent_runtime_executions FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_execution();



--
-- Name: agent_runtime_execution_mutations agent_runtime_execution_mutation_closed; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER agent_runtime_execution_mutation_closed AFTER INSERT ON public.agent_runtime_execution_mutations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.assert_agent_runtime_execution_mutation_closed();



--
-- Name: agent_runtime_execution_mutations agent_runtime_execution_mutation_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_execution_mutation_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_execution_mutations FOR EACH ROW EXECUTE FUNCTION public.reject_agent_runtime_execution_journal_mutation();



--
-- Name: agent_runtime_execution_state_publications agent_runtime_execution_publication_closed; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER agent_runtime_execution_publication_closed AFTER INSERT ON public.agent_runtime_execution_state_publications DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.assert_agent_runtime_execution_publication_closed();



--
-- Name: agent_runtime_execution_state_publications agent_runtime_execution_publication_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_execution_publication_guard BEFORE DELETE OR UPDATE ON public.agent_runtime_execution_state_publications FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_execution_publication();



--
-- Name: agent_runtime_execution_publication_mutations agent_runtime_execution_publication_journal_closed; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER agent_runtime_execution_publication_journal_closed AFTER INSERT ON public.agent_runtime_execution_publication_mutations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.assert_agent_runtime_execution_publication_journal_closed();



--
-- Name: agent_runtime_execution_state_publications agent_runtime_execution_publication_mutation_closed; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER agent_runtime_execution_publication_mutation_closed AFTER UPDATE ON public.agent_runtime_execution_state_publications DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN ((((old.status)::text = 'CLAIMED'::text) AND ((new.status)::text = ANY ((ARRAY['PENDING'::character varying, 'ACKNOWLEDGED'::character varying])::text[])))) EXECUTE FUNCTION public.assert_agent_runtime_execution_publication_mutation_closed();



--
-- Name: agent_runtime_execution_publication_mutations agent_runtime_execution_publication_mutation_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_execution_publication_mutation_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_execution_publication_mutations FOR EACH ROW EXECUTE FUNCTION public.reject_agent_runtime_execution_journal_mutation();



--
-- Name: agent_runtime_session_conflict_audits agent_runtime_session_conflict_audit_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_session_conflict_audit_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_session_conflict_audits FOR EACH ROW EXECUTE FUNCTION public.reject_agent_runtime_session_store_fact_mutation();



--
-- Name: agent_runtime_session_events agent_runtime_session_event_advance_consistency; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER agent_runtime_session_event_advance_consistency AFTER INSERT ON public.agent_runtime_session_events DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.assert_agent_runtime_session_event_advanced();



--
-- Name: agent_runtime_session_events agent_runtime_session_event_append_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_session_event_append_guard BEFORE INSERT ON public.agent_runtime_session_events FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_event_append();



--
-- Name: agent_runtime_session_events agent_runtime_session_event_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_session_event_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_session_events FOR EACH ROW EXECUTE FUNCTION public.reject_agent_runtime_session_event_mutation();



--
-- Name: agent_runtime_sessions agent_runtime_session_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_session_guard BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_sessions FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session();



--
-- Name: agent_runtime_session_mutations agent_runtime_session_mutation_applied; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER agent_runtime_session_mutation_applied AFTER INSERT ON public.agent_runtime_session_mutations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.assert_agent_runtime_session_mutation_applied();



--
-- Name: agent_runtime_session_mutations agent_runtime_session_mutation_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_session_mutation_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_session_mutations FOR EACH ROW EXECUTE FUNCTION public.reject_agent_runtime_session_store_fact_mutation();



--
-- Name: agent_runtime_sessions agent_runtime_session_mutation_journal; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER agent_runtime_session_mutation_journal AFTER UPDATE ON public.agent_runtime_sessions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.assert_agent_runtime_session_mutation_journaled();



--
-- Name: agent_runtime_session_owners agent_runtime_session_owner_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_session_owner_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_session_owners FOR EACH ROW EXECUTE FUNCTION public.reject_agent_runtime_session_owner_mutation();



--
-- Name: agent_runtime_session_streams agent_runtime_session_stream_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_session_stream_guard BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_session_streams FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_stream();



--
-- Name: agent_runtime_conversation_task_bindings agent_runtime_session_target_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_session_target_immutable BEFORE UPDATE ON public.agent_runtime_conversation_task_bindings FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_target_immutable();



--
-- Name: agent_runtime_conversation_task_operations agent_runtime_session_target_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_session_target_immutable BEFORE UPDATE ON public.agent_runtime_conversation_task_operations FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_target_immutable();



--
-- Name: agent_runtime_execution_mutations agent_runtime_session_target_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_session_target_immutable BEFORE UPDATE ON public.agent_runtime_execution_mutations FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_target_immutable();



--
-- Name: agent_runtime_executions agent_runtime_session_target_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_session_target_immutable BEFORE UPDATE ON public.agent_runtime_executions FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_target_immutable();



--
-- Name: agent_runtime_session_events agent_runtime_session_target_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_session_target_immutable BEFORE UPDATE ON public.agent_runtime_session_events FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_target_immutable();



--
-- Name: agent_runtime_session_mutations agent_runtime_session_target_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_session_target_immutable BEFORE UPDATE ON public.agent_runtime_session_mutations FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_target_immutable();



--
-- Name: agent_runtime_session_owners agent_runtime_session_target_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_session_target_immutable BEFORE UPDATE ON public.agent_runtime_session_owners FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_target_immutable();



--
-- Name: agent_runtime_session_streams agent_runtime_session_target_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_session_target_immutable BEFORE UPDATE ON public.agent_runtime_session_streams FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_target_immutable();



--
-- Name: agent_runtime_sessions agent_runtime_session_target_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_session_target_immutable BEFORE UPDATE ON public.agent_runtime_sessions FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_target_immutable();



--
-- Name: agent_runtime_workflow_child_preparations agent_runtime_workflow_child_preparation_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_workflow_child_preparation_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_workflow_child_preparations FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_workflow_child_preparation_immutable();



--
-- Name: agent_runtime_workflow_child_execution_sources agent_runtime_workflow_child_source_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER agent_runtime_workflow_child_source_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_workflow_child_execution_sources FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_workflow_child_source_immutable();



--
-- Name: agent_runtime_input_ledger_archives archive_exact_source; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_input_ledger_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('1');



--
-- Name: agent_runtime_run_mutation_archives archive_exact_source; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_run_mutation_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('6');



--
-- Name: agent_runtime_run_publication_mutation_archives archive_exact_source; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_run_publication_mutation_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('8');



--
-- Name: agent_runtime_run_record_archives archive_exact_source; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_run_record_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('4');



--
-- Name: agent_runtime_session_admission_prepare_archives archive_exact_source; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_session_admission_prepare_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('2');



--
-- Name: agent_runtime_session_checkpoint_archives archive_exact_source; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_session_checkpoint_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('11');



--
-- Name: agent_runtime_session_checkpoint_body_archives archive_exact_source; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_session_checkpoint_body_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('10');



--
-- Name: agent_runtime_session_commit_journal_archives archive_exact_source; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_session_commit_journal_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('3');



--
-- Name: agent_runtime_session_event_payload_archives archive_exact_source; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_session_event_payload_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('9');



--
-- Name: agent_runtime_session_inbox_archives archive_exact_source; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_session_inbox_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('5');



--
-- Name: agent_runtime_session_inbox_mutation_archives archive_exact_source; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_session_inbox_mutation_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('7');



--
-- Name: agent_runtime_input_ledger_archives archive_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_input_ledger_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_run_mutation_archives archive_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_run_mutation_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_run_publication_mutation_archives archive_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_run_publication_mutation_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_run_record_archives archive_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_run_record_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_session_admission_prepare_archives archive_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_session_admission_prepare_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_session_checkpoint_archive_body_refs archive_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_session_checkpoint_archive_body_refs FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_session_checkpoint_archives archive_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_session_checkpoint_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_session_checkpoint_body_archives archive_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_session_checkpoint_body_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_session_commit_journal_archives archive_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_session_commit_journal_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_session_event_payload_archives archive_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_session_event_payload_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_session_inbox_archives archive_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_session_inbox_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_session_inbox_mutation_archives archive_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER archive_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_session_inbox_mutation_archives FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_callback_ingress_proofs callback_ingress_proof_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER callback_ingress_proof_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_callback_ingress_proofs FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_callback_proof_immutable();



--
-- Name: agent_runtime_callback_deliveries callback_typed_completion; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER callback_typed_completion BEFORE UPDATE ON public.agent_runtime_callback_deliveries FOR EACH ROW EXECUTE FUNCTION public.require_typed_callback_completion();



--
-- Name: agent_runtime_session_checkpoint_archive_body_refs checkpoint_archive_reference_source; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER checkpoint_archive_reference_source BEFORE INSERT ON public.agent_runtime_session_checkpoint_archive_body_refs FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_archive_ref_guard();



--
-- Name: agent_runtime_session_checkpoint_body_refs checkpoint_body_ref_no_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER checkpoint_body_ref_no_update BEFORE UPDATE ON public.agent_runtime_session_checkpoint_body_refs FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_session_checkpoint_body_refs checkpoint_body_ref_retirement_proof; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER checkpoint_body_ref_retirement_proof BEFORE DELETE ON public.agent_runtime_session_checkpoint_body_refs FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_body_ref_retirement_guard();



--
-- Name: agent_runtime_session_checkpoint_bodies checkpoint_body_retirement_proof; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER checkpoint_body_retirement_proof BEFORE INSERT OR DELETE ON public.agent_runtime_session_checkpoint_bodies FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_retirement_guard();



--
-- Name: agent_runtime_session_checkpoints checkpoint_retirement_proof; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER checkpoint_retirement_proof BEFORE INSERT OR DELETE ON public.agent_runtime_session_checkpoints FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_retirement_guard();



--
-- Name: agent_runtime_delegations delegation_parent_source_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER delegation_parent_source_immutable BEFORE UPDATE ON public.agent_runtime_delegations FOR EACH ROW EXECUTE FUNCTION public.protect_delegation_parent_source();



--
-- Name: agent_runtime_delegation_waits delegation_wait_parent_source_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER delegation_wait_parent_source_immutable BEFORE UPDATE ON public.agent_runtime_delegation_waits FOR EACH ROW EXECUTE FUNCTION public.protect_delegation_parent_source();



--
-- Name: agent_runtime_execution_batch_members execution_batch_members_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER execution_batch_members_immutable BEFORE UPDATE ON public.agent_runtime_execution_batch_members FOR EACH ROW EXECUTE FUNCTION public.guard_execution_immutable_body();



--
-- Name: agent_runtime_execution_events execution_events_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER execution_events_immutable BEFORE UPDATE ON public.agent_runtime_execution_events FOR EACH ROW EXECUTE FUNCTION public.guard_execution_immutable_body();



--
-- Name: agent_runtime_execution_materials execution_materials_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER execution_materials_immutable BEFORE UPDATE ON public.agent_runtime_execution_materials FOR EACH ROW EXECUTE FUNCTION public.guard_execution_immutable_body();



--
-- Name: agent_runtime_execution_plan_child_runs execution_plan_child_runs_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER execution_plan_child_runs_immutable BEFORE UPDATE ON public.agent_runtime_execution_plan_child_runs FOR EACH ROW EXECUTE FUNCTION public.guard_execution_immutable_body();



--
-- Name: agent_runtime_execution_plan_controls execution_plan_controls_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER execution_plan_controls_immutable BEFORE UPDATE ON public.agent_runtime_execution_plan_controls FOR EACH ROW EXECUTE FUNCTION public.guard_execution_immutable_body();



--
-- Name: agent_runtime_execution_program_child_runs execution_program_child_runs_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER execution_program_child_runs_immutable BEFORE UPDATE ON public.agent_runtime_execution_program_child_runs FOR EACH ROW EXECUTE FUNCTION public.guard_execution_immutable_body();



--
-- Name: agent_runtime_execution_workflow_callback_deliveries execution_workflow_callback_deliveries_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER execution_workflow_callback_deliveries_immutable BEFORE UPDATE ON public.agent_runtime_execution_workflow_callback_deliveries FOR EACH ROW EXECUTE FUNCTION public.guard_execution_immutable_body();



--
-- Name: agent_runtime_execution_workflow_callback_deliveries execution_workflow_callback_response_closure; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER execution_workflow_callback_response_closure AFTER INSERT ON public.agent_runtime_execution_workflow_callback_deliveries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.check_execution_workflow_callback_closure();



--
-- Name: agent_runtime_memory_extraction_jobs extraction_group_identity_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER extraction_group_identity_immutable BEFORE UPDATE ON public.agent_runtime_memory_extraction_jobs FOR EACH ROW EXECUTE FUNCTION public.guard_memory_extraction_group_identity();



--
-- Name: agent_runtime_memory_extraction_subjects extraction_subject_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER extraction_subject_immutable BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_memory_extraction_subjects FOR EACH ROW EXECUTE FUNCTION public.guard_memory_extraction_subject();



--
-- Name: agent_runtime_memory_extraction_candidates frozen_candidate_complete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER frozen_candidate_complete AFTER INSERT OR UPDATE ON public.agent_runtime_memory_extraction_candidates DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.assert_memory_extraction_material_complete();



--
-- Name: agent_runtime_memory_extraction_candidate_evidence frozen_detail_bounds; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER frozen_detail_bounds BEFORE INSERT ON public.agent_runtime_memory_extraction_candidate_evidence FOR EACH ROW EXECUTE FUNCTION public.guard_memory_extraction_detail_insert();



--
-- Name: agent_runtime_memory_extraction_conditions frozen_detail_bounds; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER frozen_detail_bounds BEFORE INSERT ON public.agent_runtime_memory_extraction_conditions FOR EACH ROW EXECUTE FUNCTION public.guard_memory_extraction_detail_insert();



--
-- Name: agent_runtime_memory_extraction_fact_aliases frozen_detail_bounds; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER frozen_detail_bounds BEFORE INSERT ON public.agent_runtime_memory_extraction_fact_aliases FOR EACH ROW EXECUTE FUNCTION public.guard_memory_extraction_detail_insert();



--
-- Name: agent_runtime_memory_extraction_review_reasons frozen_detail_bounds; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER frozen_detail_bounds BEFORE INSERT ON public.agent_runtime_memory_extraction_review_reasons FOR EACH ROW EXECUTE FUNCTION public.guard_memory_extraction_detail_insert();



--
-- Name: agent_runtime_memory_extraction_sources frozen_detail_bounds; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER frozen_detail_bounds BEFORE INSERT ON public.agent_runtime_memory_extraction_sources FOR EACH ROW EXECUTE FUNCTION public.guard_memory_extraction_detail_insert();



--
-- Name: agent_runtime_memory_extraction_uncertainties frozen_detail_bounds; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER frozen_detail_bounds BEFORE INSERT ON public.agent_runtime_memory_extraction_uncertainties FOR EACH ROW EXECUTE FUNCTION public.guard_memory_extraction_detail_insert();



--
-- Name: agent_runtime_memory_extraction_jobs frozen_input_complete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER frozen_input_complete AFTER INSERT OR UPDATE ON public.agent_runtime_memory_extraction_jobs DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.assert_memory_extraction_material_complete();



--
-- Name: agent_runtime_memory_extraction_candidate_evidence frozen_material_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER frozen_material_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_memory_extraction_candidate_evidence FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable();



--
-- Name: agent_runtime_memory_extraction_conditions frozen_material_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER frozen_material_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_memory_extraction_conditions FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable();



--
-- Name: agent_runtime_memory_extraction_fact_aliases frozen_material_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER frozen_material_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_memory_extraction_fact_aliases FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable();



--
-- Name: agent_runtime_memory_extraction_review_reasons frozen_material_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER frozen_material_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_memory_extraction_review_reasons FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable();



--
-- Name: agent_runtime_memory_extraction_sources frozen_material_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER frozen_material_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_memory_extraction_sources FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable();



--
-- Name: agent_runtime_memory_extraction_uncertainties frozen_material_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER frozen_material_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_memory_extraction_uncertainties FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable();



--
-- Name: agent_runtime_memory_extraction_jobs frozen_subjects_complete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER frozen_subjects_complete AFTER INSERT OR UPDATE ON public.agent_runtime_memory_extraction_jobs DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.assert_memory_extraction_subjects();



--
-- Name: agent_runtime_delegations guard_agent_runtime_delegation_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER guard_agent_runtime_delegation_update BEFORE UPDATE ON public.agent_runtime_delegations FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_delegation_mutation();



--
-- Name: agent_runtime_delegation_waits guard_agent_runtime_delegation_wait_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER guard_agent_runtime_delegation_wait_update BEFORE UPDATE ON public.agent_runtime_delegation_waits FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_delegation_wait_mutation();



--
-- Name: agent_runtime_workflow_script_calls guard_agent_runtime_workflow_script_call; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER guard_agent_runtime_workflow_script_call BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_workflow_script_calls FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_workflow_script_call();



--
-- Name: agent_runtime_memory_extraction_candidates guard_memory_extraction_candidate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER guard_memory_extraction_candidate BEFORE UPDATE ON public.agent_runtime_memory_extraction_candidates FOR EACH ROW EXECUTE FUNCTION public.guard_memory_extraction_frozen_material();



--
-- Name: agent_runtime_memory_extraction_jobs guard_memory_extraction_input; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER guard_memory_extraction_input BEFORE UPDATE ON public.agent_runtime_memory_extraction_jobs FOR EACH ROW EXECUTE FUNCTION public.guard_memory_extraction_frozen_material();



--
-- Name: agent_runtime_team_work_answers guard_team_work_answer_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER guard_team_work_answer_update BEFORE UPDATE ON public.agent_runtime_team_work_answers FOR EACH ROW EXECUTE FUNCTION public.guard_team_work_answer_mutation();



--
-- Name: agent_runtime_session_checkpoint_bodies legacy_checkpoint_body_no_new_write; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_checkpoint_body_no_new_write BEFORE INSERT OR UPDATE ON public.agent_runtime_session_checkpoint_bodies FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_session_checkpoints legacy_checkpoint_no_new_write; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_checkpoint_no_new_write BEFORE INSERT OR UPDATE ON public.agent_runtime_session_checkpoints FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_session_checkpoint_body_refs legacy_checkpoint_reference_no_new_write; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_checkpoint_reference_no_new_write BEFORE INSERT OR UPDATE ON public.agent_runtime_session_checkpoint_body_refs FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_action_attempt_facts legacy_execution_read_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_execution_read_only BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_action_attempt_facts FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_action_state_publications legacy_execution_read_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_execution_read_only BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_action_state_publications FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_actions legacy_execution_read_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_execution_read_only BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_actions FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_event_conflict_audits legacy_execution_read_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_execution_read_only BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_event_conflict_audits FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_event_deliveries legacy_execution_read_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_execution_read_only BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_event_deliveries FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_inbox_mutations legacy_execution_read_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_execution_read_only BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_inbox_mutations FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_input_ledger legacy_execution_read_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_execution_read_only BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_input_ledger FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_run_mutations legacy_execution_read_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_execution_read_only BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_run_mutations FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_run_publication_mutations legacy_execution_read_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_execution_read_only BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_run_publication_mutations FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_run_state_publications legacy_execution_read_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_execution_read_only BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_run_state_publications FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_runs legacy_execution_read_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_execution_read_only BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_runs FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_session_action_preparations legacy_execution_read_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_execution_read_only BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_session_action_preparations FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_session_action_requests legacy_execution_read_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_execution_read_only BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_session_action_requests FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_session_admission_prepares legacy_execution_read_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_execution_read_only BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_session_admission_prepares FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_session_commit_journals legacy_execution_read_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_execution_read_only BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_session_commit_journals FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_session_inbox legacy_execution_read_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_execution_read_only BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_session_inbox FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_session_input_streams legacy_execution_read_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_execution_read_only BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_session_input_streams FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_session_outbox legacy_execution_read_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_execution_read_only BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_session_outbox FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_session_tool_projections legacy_execution_read_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER legacy_execution_read_only BEFORE INSERT OR DELETE OR UPDATE ON public.agent_runtime_session_tool_projections FOR EACH ROW EXECUTE FUNCTION public.reject_legacy_runtime_execution_write();



--
-- Name: agent_runtime_memory_embeddings memory_embedding_projection_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER memory_embedding_projection_guard BEFORE DELETE OR UPDATE ON public.agent_runtime_memory_embeddings FOR EACH ROW EXECUTE FUNCTION public.guard_memory_embedding_projection();



--
-- Name: agent_runtime_memory_fact_heads memory_head_typed_owner_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER memory_head_typed_owner_guard AFTER INSERT OR UPDATE ON public.agent_runtime_memory_fact_heads DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.assert_memory_typed_owner();



--
-- Name: agent_runtime_memory_query_embeddings memory_query_embedding_operation_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER memory_query_embedding_operation_guard BEFORE DELETE OR UPDATE ON public.agent_runtime_memory_query_embeddings FOR EACH ROW EXECUTE FUNCTION public.guard_memory_query_embedding_operation();



--
-- Name: agent_runtime_memory_fact_revisions memory_revision_typed_owner_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER memory_revision_typed_owner_guard AFTER INSERT ON public.agent_runtime_memory_fact_revisions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.assert_memory_typed_owner();



--
-- Name: agent_runtime_memory_search_documents memory_search_documents_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER memory_search_documents_immutable BEFORE UPDATE ON public.agent_runtime_memory_search_documents FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable();



--
-- Name: agent_runtime_model_bindings model_binding_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER model_binding_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_model_bindings FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_model_binding_immutable();



--
-- Name: agent_runtime_model_bindings model_binding_revision; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER model_binding_revision BEFORE INSERT ON public.agent_runtime_model_bindings FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_model_binding_revision();



--
-- Name: agent_runtime_session_owner_retirements owner_retirement_no_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER owner_retirement_no_delete BEFORE DELETE ON public.agent_runtime_session_owner_retirements FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_session_owner_retirements owner_retirement_source; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER owner_retirement_source BEFORE INSERT OR UPDATE ON public.agent_runtime_session_owner_retirements FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_owner_retirement_guard();



--
-- Name: agent_runtime_delegations reject_agent_runtime_delegation_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER reject_agent_runtime_delegation_delete BEFORE DELETE ON public.agent_runtime_delegations FOR EACH ROW EXECUTE FUNCTION public.reject_agent_runtime_delegation_delete();



--
-- Name: agent_runtime_delegation_waits reject_agent_runtime_delegation_wait_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER reject_agent_runtime_delegation_wait_delete BEFORE DELETE ON public.agent_runtime_delegation_waits FOR EACH ROW EXECUTE FUNCTION public.reject_agent_runtime_delegation_delete();



--
-- Name: agent_runtime_team_task_results reject_team_task_result_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER reject_team_task_result_update BEFORE DELETE OR UPDATE ON public.agent_runtime_team_task_results FOR EACH ROW EXECUTE FUNCTION public.reject_team_task_result_mutation();



--
-- Name: agent_runtime_team_work_answers reject_team_work_answer_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER reject_team_work_answer_delete BEFORE DELETE ON public.agent_runtime_team_work_answers FOR EACH ROW EXECUTE FUNCTION public.reject_team_work_answer_delete();



--
-- Name: agent_runtime_session_checkpoint_bodies session_checkpoint_bodies_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER session_checkpoint_bodies_immutable BEFORE UPDATE ON public.agent_runtime_session_checkpoint_bodies FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_session_checkpoints session_checkpoint_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER session_checkpoint_immutable BEFORE UPDATE ON public.agent_runtime_session_checkpoints FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_session_event_bodies session_event_bodies_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER session_event_bodies_immutable BEFORE UPDATE ON public.agent_runtime_session_event_bodies FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_session_event_body_refs session_event_body_refs_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER session_event_body_refs_immutable BEFORE UPDATE ON public.agent_runtime_session_event_body_refs FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_session_event_tombstones session_event_tombstones_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER session_event_tombstones_immutable BEFORE UPDATE ON public.agent_runtime_session_event_tombstones FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_session_events session_history_causal_reference; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER session_history_causal_reference BEFORE INSERT ON public.agent_runtime_session_events FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_source_reference_guard('caused_by_event_id');



--
-- Name: agent_runtime_delegations session_history_delegation_reference; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER session_history_delegation_reference BEFORE INSERT ON public.agent_runtime_delegations FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_source_reference_guard('parent_caused_by_event_id');



--
-- Name: agent_runtime_execution_state_publications session_history_execution_publication_reference; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER session_history_execution_publication_reference BEFORE INSERT ON public.agent_runtime_execution_state_publications FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_source_reference_guard('event_id');



--
-- Name: agent_runtime_session_history_retirements session_history_owner_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER session_history_owner_guard BEFORE INSERT OR UPDATE ON public.agent_runtime_session_history_retirements FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_history_owner_guard();



--
-- Name: agent_runtime_session_events session_history_task_event_reference; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER session_history_task_event_reference BEFORE INSERT ON public.agent_runtime_session_events FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_task_reference_guard();



--
-- Name: agent_runtime_conversation_tasks session_history_task_projection_reference; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER session_history_task_projection_reference BEFORE INSERT OR UPDATE OF snapshot, schema_id ON public.agent_runtime_conversation_tasks FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_task_reference_guard();



--
-- Name: agent_runtime_session_event_tombstones session_history_tombstone_delete_forbidden; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER session_history_tombstone_delete_forbidden BEFORE DELETE ON public.agent_runtime_session_event_tombstones FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_session_event_tombstones session_history_tombstone_source; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER session_history_tombstone_source BEFORE INSERT ON public.agent_runtime_session_event_tombstones FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_history_tombstone_guard();



--
-- Name: agent_runtime_delegation_waits session_history_wait_reference; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER session_history_wait_reference BEFORE INSERT ON public.agent_runtime_delegation_waits FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_source_reference_guard('parent_caused_by_event_id');



--
-- Name: agent_runtime_session_output_chunks session_output_chunks_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER session_output_chunks_immutable BEFORE UPDATE ON public.agent_runtime_session_output_chunks FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();



--
-- Name: agent_runtime_session_preparation_profiles session_preparation_profile_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER session_preparation_profile_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_session_preparation_profiles FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_preparation_immutable();



--
-- Name: agent_runtime_session_token_profiles session_token_profile_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER session_token_profile_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_session_token_profiles FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_token_profile_immutable();



--
-- Name: agent_runtime_memory_conflict_audits trg_agent_runtime_memory_audits_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_agent_runtime_memory_audits_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_memory_conflict_audits FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable();



--
-- Name: agent_runtime_memory_extraction_evidence trg_agent_runtime_memory_extraction_evidence_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_agent_runtime_memory_extraction_evidence_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_memory_extraction_evidence FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable();



--
-- Name: agent_runtime_memory_fact_heads trg_agent_runtime_memory_head_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_agent_runtime_memory_head_guard BEFORE DELETE OR UPDATE ON public.agent_runtime_memory_fact_heads FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_head_guard();



--
-- Name: agent_runtime_memory_fact_revisions trg_agent_runtime_memory_history_delete_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_agent_runtime_memory_history_delete_guard BEFORE DELETE ON public.agent_runtime_memory_fact_revisions FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_memory_history_delete();



--
-- Name: agent_runtime_memory_operations trg_agent_runtime_memory_operations_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_agent_runtime_memory_operations_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_memory_operations FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable();



--
-- Name: agent_runtime_memory_fact_revisions trg_agent_runtime_memory_revisions_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_agent_runtime_memory_revisions_immutable BEFORE UPDATE ON public.agent_runtime_memory_fact_revisions FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable();



--
-- Name: agent_runtime_memory_fact_sources trg_agent_runtime_memory_sources_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_agent_runtime_memory_sources_immutable BEFORE DELETE OR UPDATE ON public.agent_runtime_memory_fact_sources FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable();



--
-- Name: agent_runtime_memory_fact_revisions trg_agent_runtime_memory_sources_required; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER trg_agent_runtime_memory_sources_required AFTER INSERT ON public.agent_runtime_memory_fact_revisions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.assert_agent_runtime_memory_sources();



--
-- Name: agent_runtime_session_events trg_agent_runtime_memory_usage_projection; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_agent_runtime_memory_usage_projection AFTER INSERT ON public.agent_runtime_session_events FOR EACH ROW EXECUTE FUNCTION public.project_agent_runtime_memory_usage();



--
-- Name: agent_runtime_memory_fact_heads trg_memory_activation_decay_projection; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_memory_activation_decay_projection AFTER UPDATE OF current_revision ON public.agent_runtime_memory_fact_heads FOR EACH ROW WHEN ((old.current_revision IS DISTINCT FROM new.current_revision)) EXECUTE FUNCTION public.reset_memory_decay_on_activation();



--
-- Name: agent_runtime_action_attempt_facts agent_runtime_action_attempt_fact_action_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_action_attempt_facts
    ADD CONSTRAINT agent_runtime_action_attempt_fact_action_fkey FOREIGN KEY (tenant_id, action_id, invocation_id) REFERENCES public.agent_runtime_actions(tenant_id, action_id, invocation_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_actions agent_runtime_action_outcome_fact_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_actions
    ADD CONSTRAINT agent_runtime_action_outcome_fact_fkey FOREIGN KEY (tenant_id, action_id, invocation_id, outcome_fact_ref) REFERENCES public.agent_runtime_action_attempt_facts(tenant_id, action_id, invocation_id, fact_ref) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_action_state_publications agent_runtime_action_publication_action_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_action_state_publications
    ADD CONSTRAINT agent_runtime_action_publication_action_fkey FOREIGN KEY (tenant_id, source_id) REFERENCES public.agent_runtime_actions(tenant_id, action_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_actions agent_runtime_actions_target_owner_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_actions
    ADD CONSTRAINT agent_runtime_actions_target_owner_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_callback_delivery_operations agent_runtime_callback_delivery_operation_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_callback_delivery_operations
    ADD CONSTRAINT agent_runtime_callback_delivery_operation_fk FOREIGN KEY (tenant_id, delivery_id) REFERENCES public.agent_runtime_callback_deliveries(tenant_id, delivery_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_callback_ingress_proofs agent_runtime_callback_ingress_proof_tenant_id_callback_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_callback_ingress_proofs
    ADD CONSTRAINT agent_runtime_callback_ingress_proof_tenant_id_callback_id_fkey FOREIGN KEY (tenant_id, callback_id) REFERENCES public.agent_runtime_callback_registrations(tenant_id, callback_id);



--
-- Name: agent_runtime_callback_registration_operations agent_runtime_callback_registration_operation_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_callback_registration_operations
    ADD CONSTRAINT agent_runtime_callback_registration_operation_fk FOREIGN KEY (tenant_id, callback_id) REFERENCES public.agent_runtime_callback_registrations(tenant_id, callback_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_conversation_task_bindings agent_runtime_conversation_task_bindings_target_owner_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_conversation_task_bindings
    ADD CONSTRAINT agent_runtime_conversation_task_bindings_target_owner_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_conversation_task_operations agent_runtime_conversation_task_operations_target_owner_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_conversation_task_operations
    ADD CONSTRAINT agent_runtime_conversation_task_operations_target_owner_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_dag_admissions agent_runtime_dag_admissions_tenant_id_session_id_root_inp_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_dag_admissions
    ADD CONSTRAINT agent_runtime_dag_admissions_tenant_id_session_id_root_inp_fkey FOREIGN KEY (tenant_id, session_id, root_input_id) REFERENCES public.agent_runtime_dag_works(tenant_id, session_id, root_input_id);



--
-- Name: agent_runtime_dag_commands agent_runtime_dag_commands_tenant_id_session_id_root_input_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_dag_commands
    ADD CONSTRAINT agent_runtime_dag_commands_tenant_id_session_id_root_input_fkey FOREIGN KEY (tenant_id, session_id, root_input_id) REFERENCES public.agent_runtime_dag_works(tenant_id, session_id, root_input_id);



--
-- Name: agent_runtime_dag_control_runs agent_runtime_dag_control_run_tenant_id_session_id_root_in_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_dag_control_runs
    ADD CONSTRAINT agent_runtime_dag_control_run_tenant_id_session_id_root_in_fkey FOREIGN KEY (tenant_id, session_id, root_input_id) REFERENCES public.agent_runtime_dag_works(tenant_id, session_id, root_input_id);



--
-- Name: agent_runtime_dag_inputs agent_runtime_dag_inputs_tenant_id_session_id_root_input_i_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_dag_inputs
    ADD CONSTRAINT agent_runtime_dag_inputs_tenant_id_session_id_root_input_i_fkey FOREIGN KEY (tenant_id, session_id, root_input_id) REFERENCES public.agent_runtime_dag_works(tenant_id, session_id, root_input_id);



--
-- Name: agent_runtime_dag_step_actions agent_runtime_dag_step_action_tenant_id_admission_id_run_i_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_dag_step_actions
    ADD CONSTRAINT agent_runtime_dag_step_action_tenant_id_admission_id_run_i_fkey FOREIGN KEY (tenant_id, admission_id, run_id) REFERENCES public.agent_runtime_dag_admissions(tenant_id, admission_id, run_id);



--
-- Name: agent_runtime_execution_mutations agent_runtime_execution_mutations_target_owner_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_mutations
    ADD CONSTRAINT agent_runtime_execution_mutations_target_owner_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_execution_publication_mutations agent_runtime_execution_publication_mutation_publication_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_publication_mutations
    ADD CONSTRAINT agent_runtime_execution_publication_mutation_publication_fkey FOREIGN KEY (tenant_id, source_id, event_id) REFERENCES public.agent_runtime_execution_state_publications(tenant_id, source_id, event_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_execution_state_publications agent_runtime_execution_state_publication_execution_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_state_publications
    ADD CONSTRAINT agent_runtime_execution_state_publication_execution_fkey FOREIGN KEY (tenant_id, source_id) REFERENCES public.agent_runtime_executions(tenant_id, execution_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_executions agent_runtime_executions_target_owner_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_executions
    ADD CONSTRAINT agent_runtime_executions_target_owner_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_inbox_mutations agent_runtime_inbox_mutation_entry_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_inbox_mutations
    ADD CONSTRAINT agent_runtime_inbox_mutation_entry_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, input_id) REFERENCES public.agent_runtime_session_inbox(tenant_id, target_kind, target_id, session_id, input_id);



--
-- Name: agent_runtime_input_ledger_archives agent_runtime_input_ledger_ar_tenant_id_owner_target_kind__fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_input_ledger_archives
    ADD CONSTRAINT agent_runtime_input_ledger_ar_tenant_id_owner_target_kind__fkey FOREIGN KEY (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_input_ledger agent_runtime_input_ledger_target_owner_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_input_ledger
    ADD CONSTRAINT agent_runtime_input_ledger_target_owner_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_memory_extraction_fact_aliases agent_runtime_memory_extract_tenant_id_source_event_id_ex_fkey1; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_fact_aliases
    ADD CONSTRAINT agent_runtime_memory_extract_tenant_id_source_event_id_ex_fkey1 FOREIGN KEY (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key) REFERENCES public.agent_runtime_memory_extraction_jobs(tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key) ON DELETE RESTRICT;



--
-- Name: agent_runtime_memory_extraction_conditions agent_runtime_memory_extract_tenant_id_source_event_id_ex_fkey2; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_conditions
    ADD CONSTRAINT agent_runtime_memory_extract_tenant_id_source_event_id_ex_fkey2 FOREIGN KEY (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, candidate_ordinal) REFERENCES public.agent_runtime_memory_extraction_candidates(tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, candidate_ordinal) ON DELETE RESTRICT;



--
-- Name: agent_runtime_memory_extraction_uncertainties agent_runtime_memory_extract_tenant_id_source_event_id_ex_fkey3; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_uncertainties
    ADD CONSTRAINT agent_runtime_memory_extract_tenant_id_source_event_id_ex_fkey3 FOREIGN KEY (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, candidate_ordinal) REFERENCES public.agent_runtime_memory_extraction_candidates(tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, candidate_ordinal) ON DELETE RESTRICT;



--
-- Name: agent_runtime_memory_extraction_review_reasons agent_runtime_memory_extract_tenant_id_source_event_id_ex_fkey4; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_review_reasons
    ADD CONSTRAINT agent_runtime_memory_extract_tenant_id_source_event_id_ex_fkey4 FOREIGN KEY (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, candidate_ordinal) REFERENCES public.agent_runtime_memory_extraction_candidates(tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, candidate_ordinal) ON DELETE RESTRICT;



--
-- Name: agent_runtime_memory_extraction_candidate_evidence agent_runtime_memory_extract_tenant_id_source_event_id_ex_fkey5; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_candidate_evidence
    ADD CONSTRAINT agent_runtime_memory_extract_tenant_id_source_event_id_ex_fkey5 FOREIGN KEY (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, candidate_ordinal) REFERENCES public.agent_runtime_memory_extraction_candidates(tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, candidate_ordinal) ON DELETE RESTRICT;



--
-- Name: agent_runtime_memory_extraction_candidate_evidence agent_runtime_memory_extract_tenant_id_source_event_id_ex_fkey6; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_candidate_evidence
    ADD CONSTRAINT agent_runtime_memory_extract_tenant_id_source_event_id_ex_fkey6 FOREIGN KEY (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, source_alias) REFERENCES public.agent_runtime_memory_extraction_sources(tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, source_alias) ON DELETE RESTRICT;



--
-- Name: agent_runtime_memory_extraction_subjects agent_runtime_memory_extract_tenant_id_source_event_id_ex_fkey7; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_subjects
    ADD CONSTRAINT agent_runtime_memory_extract_tenant_id_source_event_id_ex_fkey7 FOREIGN KEY (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key) REFERENCES public.agent_runtime_memory_extraction_jobs(tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key) ON DELETE RESTRICT;



--
-- Name: agent_runtime_memory_extraction_fact_aliases agent_runtime_memory_extracti_tenant_id_fact_id_fact_revis_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_fact_aliases
    ADD CONSTRAINT agent_runtime_memory_extracti_tenant_id_fact_id_fact_revis_fkey FOREIGN KEY (tenant_id, fact_id, fact_revision) REFERENCES public.agent_runtime_memory_fact_revisions(tenant_id, fact_id, revision) ON DELETE RESTRICT;



--
-- Name: agent_runtime_memory_extraction_sources agent_runtime_memory_extracti_tenant_id_source_event_id_ex_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_sources
    ADD CONSTRAINT agent_runtime_memory_extracti_tenant_id_source_event_id_ex_fkey FOREIGN KEY (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key) REFERENCES public.agent_runtime_memory_extraction_jobs(tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key) ON DELETE RESTRICT;



--
-- Name: agent_runtime_runs agent_runtime_run_input_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_runs
    ADD CONSTRAINT agent_runtime_run_input_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, agent_id, end_user_id, causation_input_id) REFERENCES public.agent_runtime_input_ledger(tenant_id, target_kind, target_id, session_id, agent_id, end_user_id, input_id);



--
-- Name: agent_runtime_run_mutation_archives agent_runtime_run_mutation_ar_tenant_id_owner_target_kind__fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_run_mutation_archives
    ADD CONSTRAINT agent_runtime_run_mutation_ar_tenant_id_owner_target_kind__fkey FOREIGN KEY (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_run_mutations agent_runtime_run_mutation_run_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_run_mutations
    ADD CONSTRAINT agent_runtime_run_mutation_run_fkey FOREIGN KEY (tenant_id, target_kind, target_id, agent_id, session_id, end_user_id, run_id) REFERENCES public.agent_runtime_runs(tenant_id, target_kind, target_id, agent_id, session_id, end_user_id, run_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_run_mutations agent_runtime_run_mutations_target_owner_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_run_mutations
    ADD CONSTRAINT agent_runtime_run_mutations_target_owner_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_run_publication_mutations agent_runtime_run_publication_mutation_publication_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_run_publication_mutations
    ADD CONSTRAINT agent_runtime_run_publication_mutation_publication_fkey FOREIGN KEY (tenant_id, source_id, event_id) REFERENCES public.agent_runtime_run_state_publications(tenant_id, source_id, event_id);



--
-- Name: agent_runtime_run_publication_mutation_archives agent_runtime_run_publication_tenant_id_owner_target_kind__fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_run_publication_mutation_archives
    ADD CONSTRAINT agent_runtime_run_publication_tenant_id_owner_target_kind__fkey FOREIGN KEY (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_run_record_archives agent_runtime_run_record_arch_tenant_id_owner_target_kind__fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_run_record_archives
    ADD CONSTRAINT agent_runtime_run_record_arch_tenant_id_owner_target_kind__fkey FOREIGN KEY (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_run_state_publications agent_runtime_run_state_publication_run_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_run_state_publications
    ADD CONSTRAINT agent_runtime_run_state_publication_run_fkey FOREIGN KEY (tenant_id, source_id) REFERENCES public.agent_runtime_runs(tenant_id, run_id);



--
-- Name: agent_runtime_runs agent_runtime_runs_target_owner_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_runs
    ADD CONSTRAINT agent_runtime_runs_target_owner_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_action_preparations agent_runtime_session_action__tenant_id_run_id_journal_ref_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_action_preparations
    ADD CONSTRAINT agent_runtime_session_action__tenant_id_run_id_journal_ref_fkey FOREIGN KEY (tenant_id, run_id, journal_ref) REFERENCES public.agent_runtime_session_action_requests(tenant_id, run_id, journal_ref);



--
-- Name: agent_runtime_session_action_preparations agent_runtime_session_action_preparations_tenant_id_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_action_preparations
    ADD CONSTRAINT agent_runtime_session_action_preparations_tenant_id_run_id_fkey FOREIGN KEY (tenant_id, run_id) REFERENCES public.agent_runtime_runs(tenant_id, run_id);



--
-- Name: agent_runtime_session_action_requests agent_runtime_session_action_requests_tenant_id_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_action_requests
    ADD CONSTRAINT agent_runtime_session_action_requests_tenant_id_run_id_fkey FOREIGN KEY (tenant_id, run_id) REFERENCES public.agent_runtime_runs(tenant_id, run_id);



--
-- Name: agent_runtime_session_admission_prepare_archives agent_runtime_session_admissi_tenant_id_owner_target_kind__fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_admission_prepare_archives
    ADD CONSTRAINT agent_runtime_session_admissi_tenant_id_owner_target_kind__fkey FOREIGN KEY (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_admission_prepares agent_runtime_session_admission_prepares_target_owner_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_admission_prepares
    ADD CONSTRAINT agent_runtime_session_admission_prepares_target_owner_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_checkpoint_archives agent_runtime_session_checkp_tenant_id_owner_target_kind__fkey1; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_checkpoint_archives
    ADD CONSTRAINT agent_runtime_session_checkp_tenant_id_owner_target_kind__fkey1 FOREIGN KEY (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_checkpoint_body_archives agent_runtime_session_checkpo_tenant_id_owner_target_kind__fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_checkpoint_body_archives
    ADD CONSTRAINT agent_runtime_session_checkpo_tenant_id_owner_target_kind__fkey FOREIGN KEY (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_checkpoint_archive_body_refs agent_runtime_session_checkpo_tenant_id_run_id_checkpoint__fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_checkpoint_archive_body_refs
    ADD CONSTRAINT agent_runtime_session_checkpo_tenant_id_run_id_checkpoint__fkey FOREIGN KEY (tenant_id, run_id, checkpoint_hash) REFERENCES public.agent_runtime_session_checkpoint_archives(tenant_id, run_id, content_hash);



--
-- Name: agent_runtime_session_checkpoint_archive_body_refs agent_runtime_session_checkpoin_tenant_id_run_id_body_hash_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_checkpoint_archive_body_refs
    ADD CONSTRAINT agent_runtime_session_checkpoin_tenant_id_run_id_body_hash_fkey FOREIGN KEY (tenant_id, run_id, body_hash) REFERENCES public.agent_runtime_session_checkpoint_body_archives(tenant_id, run_id, content_hash);



--
-- Name: agent_runtime_session_checkpoints agent_runtime_session_checkpoints_tenant_id_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_checkpoints
    ADD CONSTRAINT agent_runtime_session_checkpoints_tenant_id_run_id_fkey FOREIGN KEY (tenant_id, run_id) REFERENCES public.agent_runtime_runs(tenant_id, run_id) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_session_commit_journal_archives agent_runtime_session_commit__tenant_id_owner_target_kind__fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_commit_journal_archives
    ADD CONSTRAINT agent_runtime_session_commit__tenant_id_owner_target_kind__fkey FOREIGN KEY (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_commit_journals agent_runtime_session_commit_journals_target_owner_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_commit_journals
    ADD CONSTRAINT agent_runtime_session_commit_journals_target_owner_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_commit_journals agent_runtime_session_commit_run_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_commit_journals
    ADD CONSTRAINT agent_runtime_session_commit_run_fkey FOREIGN KEY (tenant_id, target_kind, target_id, agent_id, session_id, end_user_id, run_id) REFERENCES public.agent_runtime_runs(tenant_id, target_kind, target_id, agent_id, session_id, end_user_id, run_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_event_payload_archives agent_runtime_session_event_p_tenant_id_owner_target_kind__fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_event_payload_archives
    ADD CONSTRAINT agent_runtime_session_event_p_tenant_id_owner_target_kind__fkey FOREIGN KEY (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_inbox_archives agent_runtime_session_inbox_a_tenant_id_owner_target_kind__fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_inbox_archives
    ADD CONSTRAINT agent_runtime_session_inbox_a_tenant_id_owner_target_kind__fkey FOREIGN KEY (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_inbox agent_runtime_session_inbox_input_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_inbox
    ADD CONSTRAINT agent_runtime_session_inbox_input_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, agent_id, end_user_id, input_id) REFERENCES public.agent_runtime_input_ledger(tenant_id, target_kind, target_id, session_id, agent_id, end_user_id, input_id);



--
-- Name: agent_runtime_session_inbox_mutation_archives agent_runtime_session_inbox_m_tenant_id_owner_target_kind__fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_inbox_mutation_archives
    ADD CONSTRAINT agent_runtime_session_inbox_m_tenant_id_owner_target_kind__fkey FOREIGN KEY (tenant_id, owner_target_kind, owner_target_id, owner_session_id, owner_end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_inbox agent_runtime_session_inbox_stream_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_inbox
    ADD CONSTRAINT agent_runtime_session_inbox_stream_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_input_streams(tenant_id, target_kind, target_id, session_id, end_user_id);



--
-- Name: agent_runtime_session_inbox agent_runtime_session_inbox_target_owner_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_inbox
    ADD CONSTRAINT agent_runtime_session_inbox_target_owner_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_input_streams agent_runtime_session_input_streams_target_owner_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_input_streams
    ADD CONSTRAINT agent_runtime_session_input_streams_target_owner_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_mutations agent_runtime_session_mutation_session_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_mutations
    ADD CONSTRAINT agent_runtime_session_mutation_session_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_sessions(tenant_id, target_kind, target_id, session_id, end_user_id);



--
-- Name: agent_runtime_session_mutations agent_runtime_session_mutations_target_owner_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_mutations
    ADD CONSTRAINT agent_runtime_session_mutations_target_owner_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_outbox agent_runtime_session_outbox_event_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_outbox
    ADD CONSTRAINT agent_runtime_session_outbox_event_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, agent_id, end_user_id, session_sequence, event_id) REFERENCES public.agent_runtime_session_events(tenant_id, target_kind, target_id, session_id, agent_id, end_user_id, session_sequence, event_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_outbox agent_runtime_session_outbox_target_owner_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_outbox
    ADD CONSTRAINT agent_runtime_session_outbox_target_owner_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_owner_retirements agent_runtime_session_owner_r_tenant_id_target_kind_target_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_owner_retirements
    ADD CONSTRAINT agent_runtime_session_owner_r_tenant_id_target_kind_target_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_tool_projections agent_runtime_session_tool_pr_tenant_id_run_id_preparation_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_tool_projections
    ADD CONSTRAINT agent_runtime_session_tool_pr_tenant_id_run_id_preparation_fkey FOREIGN KEY (tenant_id, run_id, preparation_ref) REFERENCES public.agent_runtime_session_action_preparations(tenant_id, run_id, journal_ref);



--
-- Name: agent_runtime_session_tool_projections agent_runtime_session_tool_pr_tenant_id_run_id_program_eve_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_tool_projections
    ADD CONSTRAINT agent_runtime_session_tool_pr_tenant_id_run_id_program_eve_fkey FOREIGN KEY (tenant_id, run_id, program_event_id) REFERENCES public.agent_runtime_session_events(tenant_id, run_id, event_id);



--
-- Name: agent_runtime_session_tool_projections agent_runtime_session_tool_projections_tenant_id_fact_ref_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_tool_projections
    ADD CONSTRAINT agent_runtime_session_tool_projections_tenant_id_fact_ref_fkey FOREIGN KEY (tenant_id, fact_ref) REFERENCES public.agent_runtime_action_attempt_facts(tenant_id, fact_ref);



--
-- Name: agent_runtime_session_tool_projections agent_runtime_session_tool_projections_tenant_id_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_tool_projections
    ADD CONSTRAINT agent_runtime_session_tool_projections_tenant_id_run_id_fkey FOREIGN KEY (tenant_id, run_id) REFERENCES public.agent_runtime_runs(tenant_id, run_id);



--
-- Name: agent_runtime_sessions agent_runtime_sessions_target_owner_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_sessions
    ADD CONSTRAINT agent_runtime_sessions_target_owner_fkey FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_team_task_results agent_runtime_team_task_resul_tenant_id_session_id_root_in_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_team_task_results
    ADD CONSTRAINT agent_runtime_team_task_resul_tenant_id_session_id_root_in_fkey FOREIGN KEY (tenant_id, session_id, root_input_id, task_id, run_id) REFERENCES public.agent_runtime_dag_admissions(tenant_id, session_id, root_input_id, task_id, run_id);



--
-- Name: agent_runtime_team_work_answers agent_runtime_team_work_answe_tenant_id_session_id_root_in_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_team_work_answers
    ADD CONSTRAINT agent_runtime_team_work_answe_tenant_id_session_id_root_in_fkey FOREIGN KEY (tenant_id, session_id, root_input_id) REFERENCES public.agent_runtime_dag_works(tenant_id, session_id, root_input_id);



--
-- Name: agent_runtime_team_work_answers agent_runtime_team_work_answe_tenant_id_source_control_run_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_team_work_answers
    ADD CONSTRAINT agent_runtime_team_work_answe_tenant_id_source_control_run_fkey FOREIGN KEY (tenant_id, source_control_run_id) REFERENCES public.agent_runtime_dag_control_runs(tenant_id, run_id);



--
-- Name: agent_runtime_team_work_answers agent_runtime_team_work_answer_tenant_id_source_result_ref_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_team_work_answers
    ADD CONSTRAINT agent_runtime_team_work_answer_tenant_id_source_result_ref_fkey FOREIGN KEY (tenant_id, source_result_ref) REFERENCES public.agent_runtime_team_task_results(tenant_id, result_id);



--
-- Name: agent_runtime_dag_works dag_work_subject_root_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_dag_works
    ADD CONSTRAINT dag_work_subject_root_fk FOREIGN KEY (tenant_id, target_kind, target_id, session_id, subject_kind, subject_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, subject_kind, subject_id);



--
-- Name: agent_runtime_session_events domain_event_subject_owner_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_events
    ADD CONSTRAINT domain_event_subject_owner_fk FOREIGN KEY (tenant_id, target_kind, target_id, session_id, subject_kind, subject_id) REFERENCES public.agent_runtime_session_streams(tenant_id, target_kind, target_id, session_id, subject_kind, subject_id);



--
-- Name: agent_runtime_session_streams domain_stream_subject_root_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_streams
    ADD CONSTRAINT domain_stream_subject_root_fk FOREIGN KEY (tenant_id, target_kind, target_id, session_id, subject_kind, subject_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, subject_kind, subject_id);



--
-- Name: agent_runtime_execution_actions execution_actions_batch_source_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_actions
    ADD CONSTRAINT execution_actions_batch_source_fk FOREIGN KEY (tenant_id, run_id, batch_source_attempt) REFERENCES public.agent_runtime_execution_attempts(tenant_id, run_id, attempt_id);



--
-- Name: agent_runtime_execution_actions execution_actions_current_attempt_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_actions
    ADD CONSTRAINT execution_actions_current_attempt_fk FOREIGN KEY (tenant_id, action_id, current_attempt) REFERENCES public.agent_runtime_execution_attempts(tenant_id, action_id, attempt_id) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_execution_actions execution_actions_request_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_actions
    ADD CONSTRAINT execution_actions_request_fk FOREIGN KEY (tenant_id, request_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id, reference);



--
-- Name: agent_runtime_execution_actions execution_actions_run_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_actions
    ADD CONSTRAINT execution_actions_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES public.agent_runtime_execution_runs(tenant_id, run_id);



--
-- Name: agent_runtime_execution_actions execution_actions_selected_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_actions
    ADD CONSTRAINT execution_actions_selected_fk FOREIGN KEY (tenant_id, action_id, selected_attempt) REFERENCES public.agent_runtime_execution_attempts(tenant_id, action_id, attempt_id) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_execution_attempts execution_attempts_action_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_attempts
    ADD CONSTRAINT execution_attempts_action_fk FOREIGN KEY (tenant_id, action_id, run_id) REFERENCES public.agent_runtime_execution_actions(tenant_id, action_id, run_id);



--
-- Name: agent_runtime_execution_attempts execution_attempts_budget_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_attempts
    ADD CONSTRAINT execution_attempts_budget_fk FOREIGN KEY (tenant_id, attempt_id) REFERENCES public.agent_runtime_budget_attempts(tenant_id, attempt_id);



--
-- Name: agent_runtime_execution_attempts execution_attempts_continuation_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_attempts
    ADD CONSTRAINT execution_attempts_continuation_fk FOREIGN KEY (tenant_id, action_id, continuation_generation) REFERENCES public.agent_runtime_execution_waits(tenant_id, action_id, generation);



--
-- Name: agent_runtime_execution_attempts execution_attempts_feedback_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_attempts
    ADD CONSTRAINT execution_attempts_feedback_fk FOREIGN KEY (tenant_id, feedback_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id, reference);



--
-- Name: agent_runtime_execution_attempts execution_attempts_request_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_attempts
    ADD CONSTRAINT execution_attempts_request_fk FOREIGN KEY (tenant_id, request_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id, reference);



--
-- Name: agent_runtime_execution_attempts execution_attempts_result_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_attempts
    ADD CONSTRAINT execution_attempts_result_fk FOREIGN KEY (tenant_id, result_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id, reference);



--
-- Name: agent_runtime_execution_attempts execution_attempts_uncertainty_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_attempts
    ADD CONSTRAINT execution_attempts_uncertainty_fk FOREIGN KEY (tenant_id, uncertainty_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id, reference);



--
-- Name: agent_runtime_execution_batch_members execution_batch_members_action_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_batch_members
    ADD CONSTRAINT execution_batch_members_action_fk FOREIGN KEY (tenant_id, action_id, run_id) REFERENCES public.agent_runtime_execution_actions(tenant_id, action_id, run_id);



--
-- Name: agent_runtime_execution_batch_members execution_batch_members_attempt_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_batch_members
    ADD CONSTRAINT execution_batch_members_attempt_fk FOREIGN KEY (tenant_id, action_id, first_attempt_id) REFERENCES public.agent_runtime_execution_attempts(tenant_id, action_id, attempt_id);



--
-- Name: agent_runtime_execution_batch_members execution_batch_members_feedback_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_batch_members
    ADD CONSTRAINT execution_batch_members_feedback_fk FOREIGN KEY (tenant_id, reused_feedback_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id, reference);



--
-- Name: agent_runtime_execution_batch_members execution_batch_members_result_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_batch_members
    ADD CONSTRAINT execution_batch_members_result_fk FOREIGN KEY (tenant_id, reused_result_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id, reference);



--
-- Name: agent_runtime_execution_batch_members execution_batch_members_source_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_batch_members
    ADD CONSTRAINT execution_batch_members_source_fk FOREIGN KEY (tenant_id, run_id, source_attempt) REFERENCES public.agent_runtime_execution_attempts(tenant_id, run_id, attempt_id);



--
-- Name: agent_runtime_execution_child_runs execution_child_runs_attempt_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_child_runs
    ADD CONSTRAINT execution_child_runs_attempt_fk FOREIGN KEY (tenant_id, parent_action_id, parent_attempt_id) REFERENCES public.agent_runtime_execution_attempts(tenant_id, action_id, attempt_id);



--
-- Name: agent_runtime_execution_child_runs execution_child_runs_child_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_child_runs
    ADD CONSTRAINT execution_child_runs_child_fk FOREIGN KEY (tenant_id, child_run_id) REFERENCES public.agent_runtime_execution_runs(tenant_id, run_id);



--
-- Name: agent_runtime_execution_child_runs execution_child_runs_parent_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_child_runs
    ADD CONSTRAINT execution_child_runs_parent_fk FOREIGN KEY (tenant_id, parent_action_id, parent_run_id) REFERENCES public.agent_runtime_execution_actions(tenant_id, action_id, run_id);



--
-- Name: agent_runtime_execution_events execution_events_run_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_events
    ADD CONSTRAINT execution_events_run_fk FOREIGN KEY (tenant_id, run_id, session_id) REFERENCES public.agent_runtime_execution_runs(tenant_id, run_id, session_id);



--
-- Name: agent_runtime_execution_plan_child_runs execution_plan_child_runs_admission_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_plan_child_runs
    ADD CONSTRAINT execution_plan_child_runs_admission_fk FOREIGN KEY (tenant_id, admission_id, child_run_id) REFERENCES public.agent_runtime_dag_admissions(tenant_id, admission_id, run_id);



--
-- Name: agent_runtime_execution_plan_child_runs execution_plan_child_runs_child_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_plan_child_runs
    ADD CONSTRAINT execution_plan_child_runs_child_fk FOREIGN KEY (tenant_id, child_run_id) REFERENCES public.agent_runtime_execution_runs(tenant_id, run_id);



--
-- Name: agent_runtime_execution_plan_child_runs execution_plan_child_runs_parent_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_plan_child_runs
    ADD CONSTRAINT execution_plan_child_runs_parent_fk FOREIGN KEY (tenant_id, parent_run_id) REFERENCES public.agent_runtime_execution_runs(tenant_id, run_id);



--
-- Name: agent_runtime_execution_plan_controls execution_plan_controls_action_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_plan_controls
    ADD CONSTRAINT execution_plan_controls_action_fk FOREIGN KEY (tenant_id, source_action_id, run_id) REFERENCES public.agent_runtime_execution_actions(tenant_id, action_id, run_id);



--
-- Name: agent_runtime_execution_plan_controls execution_plan_controls_attempt_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_plan_controls
    ADD CONSTRAINT execution_plan_controls_attempt_fk FOREIGN KEY (tenant_id, source_action_id, source_attempt_id) REFERENCES public.agent_runtime_execution_attempts(tenant_id, action_id, attempt_id);



--
-- Name: agent_runtime_execution_plan_controls execution_plan_controls_request_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_plan_controls
    ADD CONSTRAINT execution_plan_controls_request_fk FOREIGN KEY (tenant_id, request_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id, reference);



--
-- Name: agent_runtime_execution_plan_controls execution_plan_controls_result_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_plan_controls
    ADD CONSTRAINT execution_plan_controls_result_fk FOREIGN KEY (tenant_id, result_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id, reference);



--
-- Name: agent_runtime_execution_program_child_runs execution_program_child_runs_attempt_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_program_child_runs
    ADD CONSTRAINT execution_program_child_runs_attempt_fk FOREIGN KEY (tenant_id, parent_action_id, parent_attempt_id) REFERENCES public.agent_runtime_execution_attempts(tenant_id, action_id, attempt_id);



--
-- Name: agent_runtime_execution_program_child_runs execution_program_child_runs_child_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_program_child_runs
    ADD CONSTRAINT execution_program_child_runs_child_fk FOREIGN KEY (tenant_id, child_run_id) REFERENCES public.agent_runtime_execution_runs(tenant_id, run_id);



--
-- Name: agent_runtime_execution_program_child_runs execution_program_child_runs_parent_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_program_child_runs
    ADD CONSTRAINT execution_program_child_runs_parent_fk FOREIGN KEY (tenant_id, parent_action_id, parent_run_id) REFERENCES public.agent_runtime_execution_actions(tenant_id, action_id, run_id);



--
-- Name: agent_runtime_execution_runs execution_runs_batch_source_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_runs
    ADD CONSTRAINT execution_runs_batch_source_fk FOREIGN KEY (tenant_id, run_id, batch_source_attempt) REFERENCES public.agent_runtime_execution_attempts(tenant_id, run_id, attempt_id);



--
-- Name: agent_runtime_execution_runs execution_runs_context_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_runs
    ADD CONSTRAINT execution_runs_context_fk FOREIGN KEY (tenant_id, context_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id, reference);



--
-- Name: agent_runtime_execution_runs execution_runs_current_action_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_runs
    ADD CONSTRAINT execution_runs_current_action_fk FOREIGN KEY (tenant_id, current_action_id, run_id) REFERENCES public.agent_runtime_execution_actions(tenant_id, action_id, run_id) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_execution_runs execution_runs_initial_context_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_runs
    ADD CONSTRAINT execution_runs_initial_context_fk FOREIGN KEY (tenant_id, initial_context_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id, reference);



--
-- Name: agent_runtime_execution_runs execution_runs_input_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_runs
    ADD CONSTRAINT execution_runs_input_fk FOREIGN KEY (tenant_id, input_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id, reference);



--
-- Name: agent_runtime_execution_runs execution_runs_subject_owner_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_runs
    ADD CONSTRAINT execution_runs_subject_owner_fk FOREIGN KEY (tenant_id, session_id, target_kind, target_id, subject_kind, subject_id) REFERENCES public.agent_runtime_execution_sessions(tenant_id, session_id, target_kind, target_id, subject_kind, subject_id);



--
-- Name: agent_runtime_execution_runs execution_runs_wait_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_runs
    ADD CONSTRAINT execution_runs_wait_fk FOREIGN KEY (tenant_id, run_id, waiting_action_id, waiting_generation) REFERENCES public.agent_runtime_execution_waits(tenant_id, run_id, action_id, generation) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_execution_sessions execution_sessions_subject_root_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_sessions
    ADD CONSTRAINT execution_sessions_subject_root_fk FOREIGN KEY (tenant_id, target_kind, target_id, session_id, subject_kind, subject_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, subject_kind, subject_id);



--
-- Name: agent_runtime_execution_supplements execution_supplements_content_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_supplements
    ADD CONSTRAINT execution_supplements_content_fk FOREIGN KEY (tenant_id, content_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id, reference);



--
-- Name: agent_runtime_execution_supplements execution_supplements_run_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_supplements
    ADD CONSTRAINT execution_supplements_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES public.agent_runtime_execution_runs(tenant_id, run_id);



--
-- Name: agent_runtime_execution_wait_children execution_wait_children_run_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_wait_children
    ADD CONSTRAINT execution_wait_children_run_fk FOREIGN KEY (tenant_id, child_run_id) REFERENCES public.agent_runtime_execution_runs(tenant_id, run_id);



--
-- Name: agent_runtime_execution_wait_children execution_wait_children_wait_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_wait_children
    ADD CONSTRAINT execution_wait_children_wait_fk FOREIGN KEY (tenant_id, action_id, generation) REFERENCES public.agent_runtime_execution_waits(tenant_id, action_id, generation);



--
-- Name: agent_runtime_execution_wait_requests execution_wait_requests_wait_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_wait_requests
    ADD CONSTRAINT execution_wait_requests_wait_fk FOREIGN KEY (tenant_id, action_id, generation) REFERENCES public.agent_runtime_execution_waits(tenant_id, action_id, generation);



--
-- Name: agent_runtime_execution_waits execution_waits_action_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_waits
    ADD CONSTRAINT execution_waits_action_fk FOREIGN KEY (tenant_id, action_id, run_id) REFERENCES public.agent_runtime_execution_actions(tenant_id, action_id, run_id);



--
-- Name: agent_runtime_execution_waits execution_waits_continuation_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_waits
    ADD CONSTRAINT execution_waits_continuation_fk FOREIGN KEY (tenant_id, action_id, continuation_attempt) REFERENCES public.agent_runtime_execution_attempts(tenant_id, action_id, attempt_id) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_execution_waits execution_waits_response_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_waits
    ADD CONSTRAINT execution_waits_response_fk FOREIGN KEY (tenant_id, response_ref) REFERENCES public.agent_runtime_execution_materials(tenant_id, reference);



--
-- Name: agent_runtime_execution_waits execution_waits_source_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_waits
    ADD CONSTRAINT execution_waits_source_fk FOREIGN KEY (tenant_id, action_id, source_attempt) REFERENCES public.agent_runtime_execution_attempts(tenant_id, action_id, attempt_id);



--
-- Name: agent_runtime_execution_workflow_callback_deliveries execution_workflow_callback_delivery_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_workflow_callback_deliveries
    ADD CONSTRAINT execution_workflow_callback_delivery_fk FOREIGN KEY (tenant_id, delivery_id) REFERENCES public.agent_runtime_callback_deliveries(tenant_id, delivery_id);



--
-- Name: agent_runtime_execution_workflow_callback_deliveries execution_workflow_callback_proof_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_workflow_callback_deliveries
    ADD CONSTRAINT execution_workflow_callback_proof_fk FOREIGN KEY (tenant_id, delivery_id) REFERENCES public.agent_runtime_callback_ingress_proofs(tenant_id, delivery_id);



--
-- Name: agent_runtime_execution_workflow_callback_deliveries execution_workflow_callback_response_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_workflow_callback_deliveries
    ADD CONSTRAINT execution_workflow_callback_response_fk FOREIGN KEY (tenant_id, run_id, input_id) REFERENCES public.agent_runtime_execution_waits(tenant_id, run_id, input_id) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_execution_workflow_callback_deliveries execution_workflow_callback_wait_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_workflow_callback_deliveries
    ADD CONSTRAINT execution_workflow_callback_wait_fk FOREIGN KEY (tenant_id, run_id, action_id, generation) REFERENCES public.agent_runtime_execution_waits(tenant_id, run_id, action_id, generation);



--
-- Name: agent_runtime_callback_registrations fk_agent_runtime_callback_action_binding_action; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_callback_registrations
    ADD CONSTRAINT fk_agent_runtime_callback_action_binding_action FOREIGN KEY (tenant_id, action_id, action_invocation_id) REFERENCES public.agent_runtime_actions(tenant_id, action_id, invocation_id);



--
-- Name: agent_runtime_callback_registrations fk_agent_runtime_callback_action_binding_run; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_callback_registrations
    ADD CONSTRAINT fk_agent_runtime_callback_action_binding_run FOREIGN KEY (tenant_id, action_target_kind, action_target_id, action_agent_id, action_session_id, action_end_user_id, action_run_id) REFERENCES public.agent_runtime_runs(tenant_id, target_kind, target_id, agent_id, session_id, end_user_id, run_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_memory_conflict_audits fk_agent_runtime_memory_audit_head; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_conflict_audits
    ADD CONSTRAINT fk_agent_runtime_memory_audit_head FOREIGN KEY (tenant_id, fact_id) REFERENCES public.agent_runtime_memory_fact_heads(tenant_id, fact_id) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_memory_fact_revisions fk_agent_runtime_memory_content_revision; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_fact_revisions
    ADD CONSTRAINT fk_agent_runtime_memory_content_revision FOREIGN KEY (tenant_id, fact_id, content_revision) REFERENCES public.agent_runtime_memory_fact_revisions(tenant_id, fact_id, revision) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_memory_extraction_evidence fk_agent_runtime_memory_extraction_evidence_fact; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_evidence
    ADD CONSTRAINT fk_agent_runtime_memory_extraction_evidence_fact FOREIGN KEY (tenant_id, fact_id, revision) REFERENCES public.agent_runtime_memory_fact_revisions(tenant_id, fact_id, revision) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_memory_extraction_evidence fk_agent_runtime_memory_extraction_evidence_source; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_evidence
    ADD CONSTRAINT fk_agent_runtime_memory_extraction_evidence_source FOREIGN KEY (tenant_id, source_target_kind, source_target_id, source_session_id, source_event_id) REFERENCES public.agent_runtime_session_events(tenant_id, target_kind, target_id, session_id, event_id) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_memory_extraction_jobs fk_agent_runtime_memory_extraction_result; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_jobs
    ADD CONSTRAINT fk_agent_runtime_memory_extraction_result FOREIGN KEY (tenant_id, result_fact_id, result_revision) REFERENCES public.agent_runtime_memory_fact_revisions(tenant_id, fact_id, revision) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_memory_extraction_jobs fk_agent_runtime_memory_extraction_source; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_jobs
    ADD CONSTRAINT fk_agent_runtime_memory_extraction_source FOREIGN KEY (tenant_id, source_target_kind, source_target_id, source_session_id, source_event_id) REFERENCES public.agent_runtime_session_events(tenant_id, target_kind, target_id, session_id, event_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_memory_operations fk_agent_runtime_memory_operation_result; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_operations
    ADD CONSTRAINT fk_agent_runtime_memory_operation_result FOREIGN KEY (tenant_id, result_fact_id, result_revision) REFERENCES public.agent_runtime_memory_fact_revisions(tenant_id, fact_id, revision) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_memory_fact_sources fk_agent_runtime_memory_source_revision; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_fact_sources
    ADD CONSTRAINT fk_agent_runtime_memory_source_revision FOREIGN KEY (tenant_id, fact_id, revision) REFERENCES public.agent_runtime_memory_fact_revisions(tenant_id, fact_id, revision) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_memory_fact_sources fk_agent_runtime_memory_source_session_event; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_fact_sources
    ADD CONSTRAINT fk_agent_runtime_memory_source_session_event FOREIGN KEY (tenant_id, source_target_kind, source_target_id, source_session_id, source_event_id) REFERENCES public.agent_runtime_session_events(tenant_id, target_kind, target_id, session_id, event_id) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_memory_fact_revisions fk_agent_runtime_memory_superseded_head; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_fact_revisions
    ADD CONSTRAINT fk_agent_runtime_memory_superseded_head FOREIGN KEY (tenant_id, superseded_fact_id) REFERENCES public.agent_runtime_memory_fact_heads(tenant_id, fact_id) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_memory_usage_projection fk_agent_runtime_memory_usage_head; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_usage_projection
    ADD CONSTRAINT fk_agent_runtime_memory_usage_head FOREIGN KEY (tenant_id, fact_id) REFERENCES public.agent_runtime_memory_fact_heads(tenant_id, fact_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_conversation_task_bindings fk_conversation_task_binding_task; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_conversation_task_bindings
    ADD CONSTRAINT fk_conversation_task_binding_task FOREIGN KEY (tenant_id, task_id, agent_id, end_user_id) REFERENCES public.agent_runtime_conversation_tasks(tenant_id, task_id, agent_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_conversation_task_operations fk_conversation_task_operation_event; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_conversation_task_operations
    ADD CONSTRAINT fk_conversation_task_operation_event FOREIGN KEY (tenant_id, target_kind, target_id, session_id, event_id) REFERENCES public.agent_runtime_session_events(tenant_id, target_kind, target_id, session_id, event_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_conversation_task_operations fk_conversation_task_operation_task; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_conversation_task_operations
    ADD CONSTRAINT fk_conversation_task_operation_task FOREIGN KEY (tenant_id, task_id, agent_id, end_user_id) REFERENCES public.agent_runtime_conversation_tasks(tenant_id, task_id, agent_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_conversation_tasks fk_conversation_task_root; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_conversation_tasks
    ADD CONSTRAINT fk_conversation_task_root FOREIGN KEY (tenant_id, task_id, agent_id, end_user_id) REFERENCES public.agent_runtime_conversation_task_roots(tenant_id, task_id, agent_id, end_user_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_dag_permits fk_dag_permit_admission; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_dag_permits
    ADD CONSTRAINT fk_dag_permit_admission FOREIGN KEY (tenant_id, admission_id, run_id) REFERENCES public.agent_runtime_dag_admissions(tenant_id, admission_id, run_id);



--
-- Name: agent_runtime_memory_extraction_candidates fk_memory_extraction_candidate_job; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_candidates
    ADD CONSTRAINT fk_memory_extraction_candidate_job FOREIGN KEY (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key) REFERENCES public.agent_runtime_memory_extraction_jobs(tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key) ON DELETE RESTRICT;



--
-- Name: agent_runtime_memory_extraction_candidates fk_memory_extraction_candidate_result; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_candidates
    ADD CONSTRAINT fk_memory_extraction_candidate_result FOREIGN KEY (tenant_id, fact_id, fact_revision) REFERENCES public.agent_runtime_memory_fact_revisions(tenant_id, fact_id, revision) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_memory_extraction_candidates fk_memory_extraction_candidate_target; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_candidates
    ADD CONSTRAINT fk_memory_extraction_candidate_target FOREIGN KEY (tenant_id, target_fact_id, target_revision) REFERENCES public.agent_runtime_memory_fact_revisions(tenant_id, fact_id, revision) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_memory_fact_heads fk_memory_head_current_revision; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_fact_heads
    ADD CONSTRAINT fk_memory_head_current_revision FOREIGN KEY (tenant_id, fact_id, current_revision) REFERENCES public.agent_runtime_memory_fact_revisions(tenant_id, fact_id, revision) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_input_ledger_archives input_ledger_archives_hot_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_input_ledger_archives
    ADD CONSTRAINT input_ledger_archives_hot_fk FOREIGN KEY (tenant_id, target_kind, target_id, session_id, input_id) REFERENCES public.agent_runtime_input_ledger(tenant_id, target_kind, target_id, session_id, input_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_memory_embeddings memory_embeddings_document_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_embeddings
    ADD CONSTRAINT memory_embeddings_document_fk FOREIGN KEY (tenant_id, fact_id, content_revision, projection_version, analyzer_version, text_digest) REFERENCES public.agent_runtime_memory_search_documents(tenant_id, fact_id, content_revision, projection_version, analyzer_version, text_digest) ON DELETE RESTRICT;



--
-- Name: agent_runtime_memory_extraction_candidates memory_extraction_relation_alias_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_extraction_candidates
    ADD CONSTRAINT memory_extraction_relation_alias_fk FOREIGN KEY (tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, relation_target) REFERENCES public.agent_runtime_memory_extraction_fact_aliases(tenant_id, source_event_id, extractor_version, policy_version, memory_type, canonical_fact_key, alias) ON DELETE RESTRICT;



--
-- Name: agent_runtime_memory_search_documents memory_search_documents_fact_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_memory_search_documents
    ADD CONSTRAINT memory_search_documents_fact_fk FOREIGN KEY (tenant_id, fact_id, content_revision) REFERENCES public.agent_runtime_memory_fact_revisions(tenant_id, fact_id, revision) ON DELETE RESTRICT;



--
-- Name: agent_runtime_run_mutation_archives run_mutation_archives_hot_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_run_mutation_archives
    ADD CONSTRAINT run_mutation_archives_hot_fk FOREIGN KEY (tenant_id, run_id, mutation_key) REFERENCES public.agent_runtime_run_mutations(tenant_id, run_id, mutation_key) ON DELETE RESTRICT;



--
-- Name: agent_runtime_run_publication_mutation_archives run_publication_mutation_archives_hot_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_run_publication_mutation_archives
    ADD CONSTRAINT run_publication_mutation_archives_hot_fk FOREIGN KEY (tenant_id, source_id, event_id, mutation_key) REFERENCES public.agent_runtime_run_publication_mutations(tenant_id, source_id, event_id, mutation_key) ON DELETE RESTRICT;



--
-- Name: agent_runtime_run_record_archives run_record_archives_hot_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_run_record_archives
    ADD CONSTRAINT run_record_archives_hot_fk FOREIGN KEY (tenant_id, run_id) REFERENCES public.agent_runtime_runs(tenant_id, run_id) ON DELETE RESTRICT;



--
-- Name: runtime_budget_activity runtime_budget_activity_tenant_id_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_budget_activity
    ADD CONSTRAINT agent_runtime_budget_activity_tenant_id_run_id_fkey FOREIGN KEY (tenant_id, run_id) REFERENCES public.agent_runtime_budget_runs(tenant_id, run_id);



--
-- Name: runtime_budget_attempt_amounts runtime_budget_attempt_amounts_tenant_id_attempt_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_budget_attempt_amounts
    ADD CONSTRAINT agent_runtime_budget_attempt_amounts_tenant_id_attempt_id_fkey FOREIGN KEY (tenant_id, attempt_id) REFERENCES public.agent_runtime_budget_attempts(tenant_id, attempt_id);



--
-- Name: runtime_budget_attempts runtime_budget_attempts_tenant_id_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_budget_attempts
    ADD CONSTRAINT agent_runtime_budget_attempts_tenant_id_run_id_fkey FOREIGN KEY (tenant_id, run_id) REFERENCES public.agent_runtime_budget_runs(tenant_id, run_id);



--
-- Name: runtime_budget_closing_targets runtime_budget_closing_targets_tenant_id_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_budget_closing_targets
    ADD CONSTRAINT agent_runtime_budget_closing_targets_tenant_id_run_id_fkey FOREIGN KEY (tenant_id, run_id) REFERENCES public.agent_runtime_budget_runs(tenant_id, run_id);



--
-- Name: runtime_budget_result_receipts runtime_budget_result_receipts_tenant_id_attempt_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_budget_result_receipts
    ADD CONSTRAINT agent_runtime_budget_result_receipts_tenant_id_attempt_id_fkey FOREIGN KEY (tenant_id, attempt_id) REFERENCES public.agent_runtime_budget_attempts(tenant_id, attempt_id);



--
-- Name: runtime_budget_runs runtime_budget_runs_tenant_id_root_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_budget_runs
    ADD CONSTRAINT agent_runtime_budget_runs_tenant_id_root_id_fkey FOREIGN KEY (tenant_id, root_id) REFERENCES public.agent_runtime_budget_roots(tenant_id, root_id);



--
-- Name: agent_runtime_session_admission_prepare_archives session_admission_prepare_archives_hot_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_admission_prepare_archives
    ADD CONSTRAINT session_admission_prepare_archives_hot_fk FOREIGN KEY (tenant_id, target_kind, target_id, session_id, input_id) REFERENCES public.agent_runtime_session_admission_prepares(tenant_id, target_kind, target_id, session_id, input_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_checkpoint_archives session_checkpoint_archives_run_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_checkpoint_archives
    ADD CONSTRAINT session_checkpoint_archives_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES public.agent_runtime_runs(tenant_id, run_id) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_session_checkpoint_bodies session_checkpoint_bodies_run_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_checkpoint_bodies
    ADD CONSTRAINT session_checkpoint_bodies_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES public.agent_runtime_runs(tenant_id, run_id) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_session_checkpoint_body_archives session_checkpoint_body_archives_run_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_checkpoint_body_archives
    ADD CONSTRAINT session_checkpoint_body_archives_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES public.agent_runtime_runs(tenant_id, run_id) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_session_checkpoint_body_refs session_checkpoint_body_refs_body_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_checkpoint_body_refs
    ADD CONSTRAINT session_checkpoint_body_refs_body_fk FOREIGN KEY (tenant_id, run_id, body_hash) REFERENCES public.agent_runtime_session_checkpoint_bodies(tenant_id, run_id, content_hash);



--
-- Name: agent_runtime_session_checkpoint_body_refs session_checkpoint_body_refs_checkpoint_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_checkpoint_body_refs
    ADD CONSTRAINT session_checkpoint_body_refs_checkpoint_fk FOREIGN KEY (tenant_id, run_id, checkpoint_hash) REFERENCES public.agent_runtime_session_checkpoints(tenant_id, run_id, content_hash) ON DELETE CASCADE;



--
-- Name: agent_runtime_session_commit_journal_archives session_commit_journal_archives_hot_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_commit_journal_archives
    ADD CONSTRAINT session_commit_journal_archives_hot_fk FOREIGN KEY (tenant_id, run_id, operation_hash) REFERENCES public.agent_runtime_session_commit_journals(tenant_id, run_id, operation_hash) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_event_bodies session_event_bodies_typed_owner_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_event_bodies
    ADD CONSTRAINT session_event_bodies_typed_owner_fk FOREIGN KEY (tenant_id, target_kind, target_id, session_id, subject_kind, subject_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, subject_kind, subject_id);



--
-- Name: agent_runtime_session_event_body_refs session_event_body_refs_body_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_event_body_refs
    ADD CONSTRAINT session_event_body_refs_body_fk FOREIGN KEY (tenant_id, target_kind, target_id, session_id, content_hash) REFERENCES public.agent_runtime_session_event_bodies(tenant_id, target_kind, target_id, session_id, content_hash);



--
-- Name: agent_runtime_session_event_body_refs session_event_body_refs_event_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_event_body_refs
    ADD CONSTRAINT session_event_body_refs_event_fk FOREIGN KEY (tenant_id, target_kind, target_id, session_id, event_id) REFERENCES public.agent_runtime_session_events(tenant_id, target_kind, target_id, session_id, event_id) ON DELETE CASCADE;



--
-- Name: agent_runtime_session_event_payload_archives session_event_payload_archives_hot_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_event_payload_archives
    ADD CONSTRAINT session_event_payload_archives_hot_fk FOREIGN KEY (tenant_id, target_kind, target_id, session_id, event_id) REFERENCES public.agent_runtime_session_events(tenant_id, target_kind, target_id, session_id, event_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_event_tombstones session_event_tombstones_retirement_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_event_tombstones
    ADD CONSTRAINT session_event_tombstones_retirement_fk FOREIGN KEY (tenant_id, target_kind, target_id, session_id) REFERENCES public.agent_runtime_session_history_retirements(tenant_id, target_kind, target_id, session_id);



--
-- Name: agent_runtime_session_history_retirements session_history_retirements_owner_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_history_retirements
    ADD CONSTRAINT session_history_retirements_owner_fk FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_sessions(tenant_id, target_kind, target_id, session_id, end_user_id);



--
-- Name: agent_runtime_session_inbox_archives session_inbox_archives_hot_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_inbox_archives
    ADD CONSTRAINT session_inbox_archives_hot_fk FOREIGN KEY (tenant_id, target_kind, target_id, session_id, input_id) REFERENCES public.agent_runtime_session_inbox(tenant_id, target_kind, target_id, session_id, input_id) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_inbox_mutation_archives session_inbox_mutation_archives_hot_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_inbox_mutation_archives
    ADD CONSTRAINT session_inbox_mutation_archives_hot_fk FOREIGN KEY (tenant_id, target_kind, target_id, session_id, input_id, mutation_key) REFERENCES public.agent_runtime_inbox_mutations(tenant_id, target_kind, target_id, session_id, input_id, mutation_key) ON DELETE RESTRICT;



--
-- Name: agent_runtime_session_output_chunks session_output_chunks_owner_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_output_chunks
    ADD CONSTRAINT session_output_chunks_owner_fk FOREIGN KEY (tenant_id, target_kind, target_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_owners(tenant_id, target_kind, target_id, session_id, end_user_id);



--
-- Name: agent_runtime_session_output_chunks session_output_chunks_run_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_session_output_chunks
    ADD CONSTRAINT session_output_chunks_run_fk FOREIGN KEY (tenant_id, target_kind, target_id, session_id, run_id) REFERENCES public.agent_runtime_runs(tenant_id, target_kind, target_id, session_id, run_id) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_workflow_child_preparations workflow_child_preparation_subject_parent_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_workflow_child_preparations
    ADD CONSTRAINT workflow_child_preparation_subject_parent_fk FOREIGN KEY (tenant_id, parent_execution_id, target_kind, target_id, agent_id, session_id, subject_kind, subject_id, run_id) REFERENCES public.agent_runtime_executions(tenant_id, execution_id, target_kind, target_id, agent_id, session_id, subject_kind, subject_id, run_id);



--
-- Name: agent_runtime_workflow_child_execution_sources workflow_child_source_subject_child_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_workflow_child_execution_sources
    ADD CONSTRAINT workflow_child_source_subject_child_fk FOREIGN KEY (tenant_id, child_execution_id, target_kind, target_id, agent_id, session_id, subject_kind, subject_id, run_id) REFERENCES public.agent_runtime_executions(tenant_id, execution_id, target_kind, target_id, agent_id, session_id, subject_kind, subject_id, run_id);



--
-- Name: agent_runtime_workflow_child_execution_sources workflow_child_source_subject_parent_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_workflow_child_execution_sources
    ADD CONSTRAINT workflow_child_source_subject_parent_fk FOREIGN KEY (tenant_id, parent_execution_id, node_id, visit, call_index, target_kind, target_id, agent_id, session_id, subject_kind, subject_id, run_id) REFERENCES public.agent_runtime_workflow_child_preparations(tenant_id, parent_execution_id, node_id, visit, call_index, target_kind, target_id, agent_id, session_id, subject_kind, subject_id, run_id);



--
-- Name: agent_runtime_executions workflow_execution_subject_run_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_executions
    ADD CONSTRAINT workflow_execution_subject_run_fk FOREIGN KEY (tenant_id, target_kind, target_id, agent_id, session_id, subject_kind, subject_id, run_id) REFERENCES public.agent_runtime_execution_runs(tenant_id, target_kind, target_id, agent_id, session_id, subject_kind, subject_id, run_id);



--
-- Name: agent_runtime_execution_mutations workflow_mutation_subject_execution_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_execution_mutations
    ADD CONSTRAINT workflow_mutation_subject_execution_fk FOREIGN KEY (tenant_id, execution_id, target_kind, target_id, agent_id, session_id, subject_kind, subject_id, run_id) REFERENCES public.agent_runtime_executions(tenant_id, execution_id, target_kind, target_id, agent_id, session_id, subject_kind, subject_id, run_id) DEFERRABLE INITIALLY DEFERRED;



--
-- Name: agent_runtime_workflow_script_calls workflow_script_subject_execution_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_runtime_workflow_script_calls
    ADD CONSTRAINT workflow_script_subject_execution_fk FOREIGN KEY (tenant_id, execution_id, target_kind, target_id, agent_id, session_id, subject_kind, subject_id, run_id) REFERENCES public.agent_runtime_executions(tenant_id, execution_id, target_kind, target_id, agent_id, session_id, subject_kind, subject_id, run_id);



--
-- Name: agent_runtime_action_attempt_facts; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_action_attempt_facts ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_action_attempt_facts agent_runtime_action_attempt_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_action_attempt_tenant_isolation ON public.agent_runtime_action_attempt_facts USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_action_state_publications agent_runtime_action_publication_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_action_publication_tenant_isolation ON public.agent_runtime_action_state_publications USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_action_state_publications; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_action_state_publications ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_actions agent_runtime_action_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_action_tenant_isolation ON public.agent_runtime_actions USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_actions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_actions ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_callback_audits agent_runtime_callback_audit_tenant; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_callback_audit_tenant ON public.agent_runtime_callback_audits USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_callback_audits; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_callback_audits ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_callback_deliveries; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_callback_deliveries ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_callback_delivery_operations agent_runtime_callback_delivery_operation_tenant; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_callback_delivery_operation_tenant ON public.agent_runtime_callback_delivery_operations USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_callback_delivery_operations; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_callback_delivery_operations ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_callback_deliveries agent_runtime_callback_delivery_tenant; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_callback_delivery_tenant ON public.agent_runtime_callback_deliveries USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_callback_ingress_proofs; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_callback_ingress_proofs ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_callback_registration_operations agent_runtime_callback_registration_operation_tenant; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_callback_registration_operation_tenant ON public.agent_runtime_callback_registration_operations USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_callback_registration_operations; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_callback_registration_operations ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_callback_registrations agent_runtime_callback_registration_tenant; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_callback_registration_tenant ON public.agent_runtime_callback_registrations USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_callback_registrations; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_callback_registrations ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_conversation_task_bindings; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_conversation_task_bindings ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_conversation_task_operations; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_conversation_task_operations ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_conversation_task_roots; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_conversation_task_roots ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_conversation_tasks; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_conversation_tasks ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_dag_admissions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_dag_admissions ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_dag_commands; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_dag_commands ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_dag_control_runs; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_dag_control_runs ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_dag_inputs; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_dag_inputs ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_dag_permits; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_dag_permits ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_dag_step_actions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_dag_step_actions ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_dag_works; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_dag_works ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_delegation_waits; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_delegation_waits ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_delegations; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_delegations ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_event_conflict_audits agent_runtime_event_audit_tenant; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_event_audit_tenant ON public.agent_runtime_event_conflict_audits USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_event_conflict_audits; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_event_conflict_audits ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_event_deliveries; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_event_deliveries ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_event_deliveries agent_runtime_event_delivery_tenant; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_event_delivery_tenant ON public.agent_runtime_event_deliveries USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_execution_actions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_execution_actions ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_execution_attempts; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_execution_attempts ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_execution_batch_members; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_execution_batch_members ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_execution_child_runs; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_execution_child_runs ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_execution_events; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_execution_events ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_execution_materials; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_execution_materials ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_execution_mutations agent_runtime_execution_mutation_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_execution_mutation_tenant_isolation ON public.agent_runtime_execution_mutations USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_execution_mutations; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_execution_mutations ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_execution_plan_child_runs; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_execution_plan_child_runs ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_execution_plan_controls; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_execution_plan_controls ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_execution_program_child_runs; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_execution_program_child_runs ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_execution_publication_mutations agent_runtime_execution_publication_mutation_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_execution_publication_mutation_tenant_isolation ON public.agent_runtime_execution_publication_mutations USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_execution_publication_mutations; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_execution_publication_mutations ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_execution_state_publications agent_runtime_execution_publication_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_execution_publication_tenant_isolation ON public.agent_runtime_execution_state_publications USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_execution_runs; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_execution_runs ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_execution_sessions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_execution_sessions ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_execution_state_publications; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_execution_state_publications ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_execution_supplements; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_execution_supplements ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_executions agent_runtime_execution_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_execution_tenant_isolation ON public.agent_runtime_executions USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_execution_wait_children; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_execution_wait_children ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_execution_wait_requests; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_execution_wait_requests ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_execution_waits; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_execution_waits ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_execution_workflow_callback_deliveries; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_execution_workflow_callback_deliveries ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_executions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_executions ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_inbox_mutations agent_runtime_inbox_mutation_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_inbox_mutation_tenant_isolation ON public.agent_runtime_inbox_mutations USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_inbox_mutations; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_inbox_mutations ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_input_ledger; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_input_ledger ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_input_ledger_archives; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_input_ledger_archives ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_input_ledger agent_runtime_input_ledger_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_input_ledger_tenant_isolation ON public.agent_runtime_input_ledger USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_memory_conflict_audits agent_runtime_memory_audit_tenant; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_memory_audit_tenant ON public.agent_runtime_memory_conflict_audits USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_memory_conflict_audits; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_memory_conflict_audits ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_memory_embeddings; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_memory_embeddings ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_memory_extraction_candidate_evidence; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_memory_extraction_candidate_evidence ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_memory_extraction_candidates; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_memory_extraction_candidates ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_memory_extraction_conditions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_memory_extraction_conditions ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_memory_extraction_evidence; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_memory_extraction_evidence ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_memory_extraction_evidence agent_runtime_memory_extraction_evidence_tenant; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_memory_extraction_evidence_tenant ON public.agent_runtime_memory_extraction_evidence USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_memory_extraction_fact_aliases; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_memory_extraction_fact_aliases ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_memory_extraction_jobs; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_memory_extraction_jobs ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_memory_extraction_review_reasons; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_memory_extraction_review_reasons ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_memory_extraction_sources; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_memory_extraction_sources ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_memory_extraction_subjects; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_memory_extraction_subjects ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_memory_extraction_jobs agent_runtime_memory_extraction_tenant; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_memory_extraction_tenant ON public.agent_runtime_memory_extraction_jobs USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_memory_extraction_uncertainties; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_memory_extraction_uncertainties ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_memory_fact_heads; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_memory_fact_heads ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_memory_fact_revisions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_memory_fact_revisions ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_memory_fact_sources; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_memory_fact_sources ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_memory_fact_heads agent_runtime_memory_head_tenant; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_memory_head_tenant ON public.agent_runtime_memory_fact_heads USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_memory_operations agent_runtime_memory_operation_tenant; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_memory_operation_tenant ON public.agent_runtime_memory_operations USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_memory_operations; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_memory_operations ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_memory_query_embeddings; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_memory_query_embeddings ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_memory_fact_revisions agent_runtime_memory_revision_tenant; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_memory_revision_tenant ON public.agent_runtime_memory_fact_revisions USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_memory_search_documents; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_memory_search_documents ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_memory_fact_sources agent_runtime_memory_source_tenant; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_memory_source_tenant ON public.agent_runtime_memory_fact_sources USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_memory_usage_projection; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_memory_usage_projection ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_memory_usage_projection agent_runtime_memory_usage_tenant; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_memory_usage_tenant ON public.agent_runtime_memory_usage_projection USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_model_bindings; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_model_bindings ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_run_mutation_archives; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_run_mutation_archives ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_run_mutations agent_runtime_run_mutation_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_run_mutation_tenant_isolation ON public.agent_runtime_run_mutations USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_run_mutations; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_run_mutations ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_run_publication_mutation_archives; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_run_publication_mutation_archives ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_run_publication_mutations agent_runtime_run_publication_mutation_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_run_publication_mutation_tenant_isolation ON public.agent_runtime_run_publication_mutations USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_run_publication_mutations; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_run_publication_mutations ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_run_record_archives; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_run_record_archives ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_run_state_publications agent_runtime_run_state_publication_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_run_state_publication_tenant_isolation ON public.agent_runtime_run_state_publications USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_run_state_publications; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_run_state_publications ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_runs agent_runtime_run_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_run_tenant_isolation ON public.agent_runtime_runs USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_runs; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_runs ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_action_preparations; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_action_preparations ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_action_requests; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_action_requests ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_admission_prepare_archives; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_admission_prepare_archives ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_admission_prepares agent_runtime_session_admission_prepare_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_session_admission_prepare_tenant_isolation ON public.agent_runtime_session_admission_prepares USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_session_admission_prepares; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_admission_prepares ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_checkpoint_archive_body_refs; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_checkpoint_archive_body_refs ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_checkpoint_archives; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_checkpoint_archives ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_checkpoint_bodies; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_checkpoint_bodies ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_checkpoint_body_archives; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_checkpoint_body_archives ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_checkpoint_body_refs; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_checkpoint_body_refs ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_checkpoints; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_checkpoints ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_commit_journal_archives; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_commit_journal_archives ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_commit_journals agent_runtime_session_commit_journal_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_session_commit_journal_tenant_isolation ON public.agent_runtime_session_commit_journals USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_session_commit_journals; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_commit_journals ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_conflict_audits agent_runtime_session_conflict_audit_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_session_conflict_audit_tenant_isolation ON public.agent_runtime_session_conflict_audits USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_session_conflict_audits; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_conflict_audits ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_event_bodies; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_event_bodies ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_event_body_refs; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_event_body_refs ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_event_payload_archives; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_event_payload_archives ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_events agent_runtime_session_event_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_session_event_tenant_isolation ON public.agent_runtime_session_events USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_session_event_tombstones; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_event_tombstones ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_events; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_events ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_history_retirements; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_history_retirements ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_inbox; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_inbox ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_inbox_archives; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_inbox_archives ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_inbox_mutation_archives; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_inbox_mutation_archives ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_inbox agent_runtime_session_inbox_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_session_inbox_tenant_isolation ON public.agent_runtime_session_inbox USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_session_input_streams agent_runtime_session_input_stream_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_session_input_stream_tenant_isolation ON public.agent_runtime_session_input_streams USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_session_input_streams; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_input_streams ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_mutations agent_runtime_session_mutation_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_session_mutation_tenant_isolation ON public.agent_runtime_session_mutations USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_session_mutations; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_mutations ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_outbox; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_outbox ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_outbox agent_runtime_session_outbox_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_session_outbox_tenant_isolation ON public.agent_runtime_session_outbox USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_session_output_chunks; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_output_chunks ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_owner_retirements; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_owner_retirements ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_owners agent_runtime_session_owner_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_session_owner_tenant_isolation ON public.agent_runtime_session_owners USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_session_owners; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_owners ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_preparation_profiles; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_preparation_profiles ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_streams agent_runtime_session_stream_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_session_stream_tenant_isolation ON public.agent_runtime_session_streams USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_session_streams; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_streams ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_sessions agent_runtime_session_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_session_tenant_isolation ON public.agent_runtime_sessions USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_session_token_profiles; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_token_profiles ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_session_tool_projections; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_session_tool_projections ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_sessions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_sessions ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_team_task_results; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_team_task_results ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_team_work_answers; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_team_work_answers ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_workflow_child_execution_sources; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_workflow_child_execution_sources ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_workflow_child_preparations agent_runtime_workflow_child_preparation_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_workflow_child_preparation_tenant_isolation ON public.agent_runtime_workflow_child_preparations USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_workflow_child_preparations; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_workflow_child_preparations ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_workflow_child_execution_sources agent_runtime_workflow_child_source_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_workflow_child_source_tenant_isolation ON public.agent_runtime_workflow_child_execution_sources USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_workflow_script_calls; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_workflow_script_calls ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_workflow_script_calls agent_runtime_workflow_script_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_runtime_workflow_script_tenant_isolation ON public.agent_runtime_workflow_script_calls USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: runtime_budget_activity; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_budget_activity ENABLE ROW LEVEL SECURITY;


--
-- Name: runtime_budget_attempt_amounts; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_budget_attempt_amounts ENABLE ROW LEVEL SECURITY;


--
-- Name: runtime_budget_attempts; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_budget_attempts ENABLE ROW LEVEL SECURITY;


--
-- Name: runtime_budget_balances; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_budget_balances ENABLE ROW LEVEL SECURITY;


--
-- Name: runtime_budget_closing_targets; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_budget_closing_targets ENABLE ROW LEVEL SECURITY;


--
-- Name: runtime_budget_result_receipts; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_budget_result_receipts ENABLE ROW LEVEL SECURITY;


--
-- Name: runtime_budget_roots; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_budget_roots ENABLE ROW LEVEL SECURITY;


--
-- Name: runtime_budget_runs; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_runtime_budget_runs ENABLE ROW LEVEL SECURITY;


--
-- Name: agent_runtime_callback_ingress_proofs tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_callback_ingress_proofs USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_conversation_task_bindings tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_conversation_task_bindings USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_conversation_task_operations tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_conversation_task_operations USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_conversation_task_roots tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_conversation_task_roots USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_conversation_tasks tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_conversation_tasks USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_dag_admissions tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_dag_admissions USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_dag_commands tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_dag_commands USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_dag_control_runs tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_dag_control_runs USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_dag_inputs tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_dag_inputs USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_dag_permits tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_dag_permits USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_dag_step_actions tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_dag_step_actions USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_dag_works tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_dag_works USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_delegation_waits tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_delegation_waits USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_delegations tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_delegations USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_execution_actions tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_execution_actions USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_execution_attempts tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_execution_attempts USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_execution_batch_members tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_execution_batch_members USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_execution_child_runs tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_execution_child_runs USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_execution_events tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_execution_events USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_execution_materials tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_execution_materials USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_execution_plan_child_runs tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_execution_plan_child_runs USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_execution_plan_controls tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_execution_plan_controls USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_execution_program_child_runs tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_execution_program_child_runs USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_execution_runs tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_execution_runs USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_execution_sessions tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_execution_sessions USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_execution_supplements tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_execution_supplements USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_execution_wait_children tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_execution_wait_children USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_execution_wait_requests tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_execution_wait_requests USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_execution_waits tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_execution_waits USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_execution_workflow_callback_deliveries tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_execution_workflow_callback_deliveries USING ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK ((tenant_id = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_input_ledger_archives tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_input_ledger_archives USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_memory_embeddings tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_memory_embeddings USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_memory_extraction_candidate_evidence tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_memory_extraction_candidate_evidence USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_memory_extraction_conditions tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_memory_extraction_conditions USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_memory_extraction_fact_aliases tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_memory_extraction_fact_aliases USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_memory_extraction_review_reasons tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_memory_extraction_review_reasons USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_memory_extraction_sources tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_memory_extraction_sources USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_memory_extraction_uncertainties tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_memory_extraction_uncertainties USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_memory_query_embeddings tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_memory_query_embeddings USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_memory_search_documents tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_memory_search_documents USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_model_bindings tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_model_bindings USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_run_mutation_archives tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_run_mutation_archives USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_run_publication_mutation_archives tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_run_publication_mutation_archives USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_run_record_archives tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_run_record_archives USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_action_preparations tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_action_preparations USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_action_requests tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_action_requests USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_admission_prepare_archives tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_admission_prepare_archives USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_checkpoint_archive_body_refs tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_checkpoint_archive_body_refs USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_checkpoint_archives tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_checkpoint_archives USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_checkpoint_bodies tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_checkpoint_bodies USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_checkpoint_body_archives tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_checkpoint_body_archives USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_checkpoint_body_refs tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_checkpoint_body_refs USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_checkpoints tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_checkpoints USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_commit_journal_archives tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_commit_journal_archives USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_event_bodies tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_event_bodies USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_event_body_refs tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_event_body_refs USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_event_payload_archives tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_event_payload_archives USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_event_tombstones tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_event_tombstones USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_history_retirements tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_history_retirements USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_inbox_archives tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_inbox_archives USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_inbox_mutation_archives tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_inbox_mutation_archives USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_output_chunks tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_output_chunks USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_owner_retirements tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_owner_retirements USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_preparation_profiles tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_preparation_profiles USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_token_profiles tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_token_profiles USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_session_tool_projections tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_session_tool_projections USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_team_task_results tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_team_task_results USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_team_work_answers tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_team_work_answers USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));



--
-- Name: runtime_budget_activity tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_budget_activity USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: runtime_budget_attempt_amounts tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_budget_attempt_amounts USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: runtime_budget_attempts tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_budget_attempts USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: runtime_budget_balances tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_budget_balances USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: runtime_budget_closing_targets tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_budget_closing_targets USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: runtime_budget_result_receipts tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_budget_result_receipts USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: runtime_budget_roots tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_budget_roots USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: runtime_budget_runs tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.agent_runtime_budget_runs USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));



--
-- Name: agent_runtime_memory_extraction_candidates tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.agent_runtime_memory_extraction_candidates USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));



--
-- Name: agent_runtime_memory_extraction_subjects tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.agent_runtime_memory_extraction_subjects USING ((tenant_id = current_setting('app.tenant_id'::text, true))) WITH CHECK ((tenant_id = current_setting('app.tenant_id'::text, true)));
