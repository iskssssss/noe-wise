-- 当前个人 ConversationTask 的来源 owner 使用显式 USER subject；群执行留给后续批次。
-- 只替换仍由正式触发器和退休引用查询消费的函数，不改历史迁移或封存执行链。
CREATE FUNCTION public.agent_runtime_session_task_user_owner_valid(owner json) RETURNS boolean
LANGUAGE plpgsql IMMUTABLE SECURITY INVOKER AS $$
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
END $$;
COMMENT ON FUNCTION public.agent_runtime_session_task_user_owner_valid(json)
 IS '当前个人任务快照的精确 owner 结构；只认 USER/AGENT或TEAM，拒绝群、旧顶层用户及混合字段';

-- 格式不明的既有来源继续保留保护；不会将解析失败解释为可释放。
CREATE OR REPLACE FUNCTION public.agent_runtime_session_task_snapshot_referenced(snapshot text,t text,s text,k text,id text,u bigint,eid text,seq bigint)
RETURNS boolean LANGUAGE plpgsql IMMUTABLE SECURITY INVOKER AS $$
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
END $$;

-- 新写入只接受正式新 owner 协议，事件源与游标仍须持锁匹配真实 SessionLog。
CREATE OR REPLACE FUNCTION public.agent_runtime_session_task_reference_guard() RETURNS trigger
LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
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
END $$;
