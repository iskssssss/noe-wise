-- 新绑定必须持有原部署修订的完整链；既有 Run/Attempt/UNKNOWN/费用事实不回填或改写。
CREATE FUNCTION public.enforce_frozen_run_grant_chains() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE entry record; frozen_authorization jsonb; chain jsonb; leaf jsonb;
BEGIN
 IF jsonb_typeof(NEW.scope_snapshot->'authorizations') IS NOT NULL
    AND jsonb_typeof(NEW.scope_snapshot->'authorizations')<>'object' THEN
  RAISE EXCEPTION 'run authorization map must be an object';
 END IF;
 IF EXISTS(SELECT 1 FROM nw_agent_deployments d WHERE d.tenant_id::text=NEW.tenant_id AND d.agent_id::text=NEW.actor_agent_id)
    AND NOT COALESCE(NEW.scope_snapshot->'authorizations' ? NEW.actor_agent_id,false) THEN
  RAISE EXCEPTION 'controlled run must freeze its selected authorization';
 END IF;
 FOR entry IN SELECT key,value FROM jsonb_each(COALESCE(NEW.scope_snapshot->'authorizations','{}'::jsonb)) LOOP
  frozen_authorization=entry.value;chain=frozen_authorization->'grant_chain';
  IF jsonb_typeof(chain) IS DISTINCT FROM 'object' OR jsonb_typeof(chain->'hops') IS DISTINCT FROM 'array' THEN
   RAISE EXCEPTION 'run grant chain is required';
  END IF;
  IF jsonb_array_length(chain->'hops')<1 THEN RAISE EXCEPTION 'run grant chain cannot be empty'; END IF;
  leaf=chain->'hops'->(jsonb_array_length(chain->'hops')-1);
  IF (leaf->>'grant_id')::bigint IS DISTINCT FROM (frozen_authorization->>'grant_id')::bigint
    OR (leaf->>'revision')::bigint IS DISTINCT FROM (frozen_authorization->>'grant_revision')::bigint
    OR leaf->>'target_tenant_id' IS DISTINCT FROM NEW.tenant_id
    OR NOT EXISTS(SELECT 1 FROM nw_agent_deployments d JOIN nw_agent_deployment_revisions r ON r.deployment_id=d.deployment_id
      WHERE d.tenant_id::text=NEW.tenant_id AND d.agent_id::text=entry.key
       AND d.deployment_id=(frozen_authorization->>'deployment_id')::bigint
       AND r.tenant_id=d.tenant_id AND r.revision=(frozen_authorization->>'deployment_revision')::bigint
       AND r.grant_id=(frozen_authorization->>'grant_id')::bigint AND r.grant_revision=(frozen_authorization->>'grant_revision')::bigint
       AND r.grant_chain=chain AND d.definition_id=(chain->>'definition_id')::bigint) THEN
   RAISE EXCEPTION 'run authorization must reference the exact original deployment revision';
  END IF;
 END LOOP;
 RETURN NEW;
END $$;
CREATE TRIGGER agent_run_grant_chain_integrity BEFORE INSERT ON public.nw_runtime_agent_run_bindings
 FOR EACH ROW EXECUTE FUNCTION public.enforce_frozen_run_grant_chains();
CREATE TRIGGER agent_run_bindings_immutable BEFORE UPDATE ON public.nw_runtime_agent_run_bindings
 FOR EACH ROW EXECUTE FUNCTION public.prevent_direct_deployment_revision_mutation();
COMMENT ON COLUMN public.nw_runtime_agent_run_bindings.scope_snapshot IS
 '原请求者、发布配置及完整选定grant_chain；子Run继承原链，当前部署换授不改写；受限派发在原SqlSession逐跳复验当前治理有效性';
