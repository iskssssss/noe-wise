-- 固定单链授权：机构关系仅作为逐跳有效性证明，不继承业务或执行权限。
DO $$ DECLARE c record; BEGIN
 FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='nw_agent_definition_grants'::regclass AND contype='c' AND pg_get_constraintdef(oid) LIKE '%actions%'
 LOOP EXECUTE format('ALTER TABLE nw_agent_definition_grants DROP CONSTRAINT %I',c.conname); END LOOP;
END $$;
ALTER TABLE nw_agent_definition_grants
 ADD COLUMN owner_kind varchar(16) NOT NULL DEFAULT 'platform',
 ADD COLUMN owner_tenant_id bigint REFERENCES nw_tenants(id),
 ADD COLUMN grantor_kind varchar(16) NOT NULL DEFAULT 'platform',
 ADD COLUMN grantor_tenant_id bigint REFERENCES nw_tenants(id),
 ADD COLUMN parent_grant_id bigint REFERENCES nw_agent_definition_grants(grant_id),
 ADD COLUMN parent_grant_revision bigint,
 ADD COLUMN relation_ref bigint REFERENCES nw_institution_relation_revisions(id),
 ADD COLUMN relation_revision bigint,
 ADD COLUMN allow_regrant boolean NOT NULL DEFAULT false,
 ADD COLUMN remaining_depth integer NOT NULL DEFAULT 0,
 ADD CONSTRAINT grant_actions CHECK(actions IN ('deploy','execute','deploy,execute','execute,deploy')),
 ADD CONSTRAINT grant_owner CHECK((owner_kind='platform' AND owner_tenant_id IS NULL) OR (owner_kind='tenant' AND owner_tenant_id IS NOT NULL)),
 ADD CONSTRAINT grant_issuer CHECK((grantor_kind='platform' AND grantor_tenant_id IS NULL AND parent_grant_id IS NULL AND relation_ref IS NULL AND relation_revision IS NULL)
 OR (grantor_kind='tenant' AND grantor_tenant_id IS NOT NULL AND relation_ref IS NOT NULL AND relation_revision>0)),
 ADD CONSTRAINT grant_parent CHECK((parent_grant_id IS NULL AND parent_grant_revision IS NULL) OR (parent_grant_id IS NOT NULL AND parent_grant_revision>0)),
 ADD CONSTRAINT grant_depth CHECK((allow_regrant AND remaining_depth>0) OR (NOT allow_regrant AND remaining_depth=0));
CREATE INDEX agent_grant_issuer_idx ON nw_agent_definition_grants(grantor_tenant_id,grant_id);
CREATE TABLE nw_agent_definition_grant_revisions (LIKE nw_agent_definition_grants INCLUDING DEFAULTS);
ALTER TABLE nw_agent_definition_grant_revisions ADD PRIMARY KEY(grant_id,revision),
 ADD COLUMN changed_by text NOT NULL, ADD COLUMN operation text NOT NULL, ADD COLUMN command_ref text NOT NULL;
INSERT INTO nw_agent_definition_grant_revisions SELECT g.*,'migration','import',request_key FROM nw_agent_definition_grants g;
-- V297的撤销行仍保留完整原授字段，可以原样恢复其唯一原授修订事实。
INSERT INTO nw_agent_definition_grant_revisions(grant_id,definition_id,target_tenant_id,actions,expires_at,status,revision,actor_ref,request_key,fingerprint,
 revoke_key,revoke_fingerprint,revoked_by,created_at,revoked_at,owner_kind,owner_tenant_id,grantor_kind,grantor_tenant_id,parent_grant_id,parent_grant_revision,
 relation_ref,relation_revision,allow_regrant,remaining_depth,changed_by,operation,command_ref)
 SELECT grant_id,definition_id,target_tenant_id,actions,expires_at,'active',1,actor_ref,request_key,fingerprint,NULL,NULL,NULL,created_at,NULL,
 owner_kind,owner_tenant_id,grantor_kind,grantor_tenant_id,parent_grant_id,parent_grant_revision,relation_ref,relation_revision,allow_regrant,remaining_depth,
 actor_ref,'create',request_key FROM nw_agent_definition_grants WHERE status='revoked' AND revision=2;
ALTER TABLE nw_agent_definition_grants ADD CONSTRAINT grant_parent_revision_ref FOREIGN KEY(parent_grant_id,parent_grant_revision)
 REFERENCES nw_agent_definition_grant_revisions(grant_id,revision);
ALTER TABLE nw_agent_definition_grant_revisions ADD FOREIGN KEY(grant_id) REFERENCES nw_agent_definition_grants(grant_id);
CREATE TABLE nw_agent_definition_grant_requests (
 actor_ref text NOT NULL,command_ref varchar(256) NOT NULL,fingerprint varchar(64) NOT NULL,
 grant_id bigint NOT NULL REFERENCES nw_agent_definition_grants(grant_id),response jsonb NOT NULL CHECK(jsonb_typeof(response)='object'),
 recorded_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(actor_ref,command_ref));
CREATE TRIGGER agent_grant_history_immutable BEFORE UPDATE OR DELETE ON nw_agent_definition_grant_revisions
 FOR EACH ROW EXECUTE FUNCTION prevent_direct_deployment_revision_mutation();
CREATE TRIGGER agent_grant_request_immutable BEFORE UPDATE OR DELETE ON nw_agent_definition_grant_requests
 FOR EACH ROW EXECUTE FUNCTION prevent_direct_deployment_revision_mutation();

CREATE FUNCTION enforce_agent_grant_chain() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p nw_agent_definition_grants; t nw_tenants; source nw_agent_templates; BEGIN
 IF TG_OP='UPDATE' THEN
  IF (to_jsonb(NEW)-ARRAY['actions','expires_at','status','revision','allow_regrant','remaining_depth','revoke_key','revoke_fingerprint','revoked_by','revoked_at'])
    IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['actions','expires_at','status','revision','allow_regrant','remaining_depth','revoke_key','revoke_fingerprint','revoked_by','revoked_at'])
    OR OLD.status<>'active' OR NEW.revision<>OLD.revision+1
    OR NOT string_to_array(NEW.actions,',') <@ string_to_array(OLD.actions,',')
    OR (OLD.expires_at IS NOT NULL AND (NEW.expires_at IS NULL OR NEW.expires_at>OLD.expires_at))
    OR (NEW.allow_regrant AND NOT OLD.allow_regrant) OR NEW.remaining_depth>OLD.remaining_depth THEN
   RAISE EXCEPTION 'grant identity is immutable and scope may only narrow';
  END IF;
  RETURN NEW;
 END IF;
 SELECT * INTO source FROM nw_agent_templates WHERE id=NEW.definition_id;
 IF source.id IS NULL OR source.owner_kind<>NEW.owner_kind OR source.owner_tenant_id IS DISTINCT FROM NEW.owner_tenant_id
   OR source.status<>'published' OR source.distribution_policy<>'AUTHORIZED_DEPLOY' THEN RAISE EXCEPTION 'grant source unavailable'; END IF;
 IF NOT EXISTS(SELECT 1 FROM nw_tenants WHERE id=NEW.target_tenant_id AND status IN ('active','warning'))
  OR (NEW.owner_kind='tenant' AND NOT EXISTS(SELECT 1 FROM nw_tenants WHERE id=NEW.owner_tenant_id AND status IN ('active','warning')))
  OR (NEW.grantor_kind='tenant' AND NOT EXISTS(SELECT 1 FROM nw_tenants WHERE id=NEW.grantor_tenant_id AND status IN ('active','warning'))) THEN
  RAISE EXCEPTION 'grant institution unavailable'; END IF;
 IF NEW.grantor_kind='platform' THEN
  IF NEW.owner_kind<>'platform' THEN RAISE EXCEPTION 'platform grant must own source'; END IF;
 ELSE
  SELECT * INTO t FROM nw_tenants WHERE id=NEW.target_tenant_id FOR SHARE;
  IF t.parent_tenant_id IS DISTINCT FROM NEW.grantor_tenant_id OR t.relation_revision IS DISTINCT FROM NEW.relation_revision
   OR NOT EXISTS(SELECT 1 FROM nw_institution_relation_revisions r WHERE r.id=NEW.relation_ref AND r.child_tenant_id=t.id
    AND r.parent_tenant_id=NEW.grantor_tenant_id AND r.revision=t.relation_revision) THEN RAISE EXCEPTION 'grant requires current direct relation'; END IF;
  IF NEW.parent_grant_id IS NULL THEN
   IF NEW.owner_kind<>'tenant' OR NEW.owner_tenant_id<>NEW.grantor_tenant_id THEN RAISE EXCEPTION 'tenant original grant must own source'; END IF;
  ELSE
   SELECT * INTO p FROM nw_agent_definition_grants WHERE grant_id=NEW.parent_grant_id FOR SHARE;
   IF p.grant_id IS NULL OR p.target_tenant_id<>NEW.grantor_tenant_id OR p.definition_id<>NEW.definition_id
    OR p.owner_kind<>NEW.owner_kind OR p.owner_tenant_id IS DISTINCT FROM NEW.owner_tenant_id
    OR p.revision<>NEW.parent_grant_revision OR p.status<>'active' OR (p.expires_at IS NOT NULL AND p.expires_at<=clock_timestamp())
    OR NOT p.allow_regrant OR p.remaining_depth<1 OR NEW.remaining_depth>=p.remaining_depth
    OR NOT string_to_array(NEW.actions,',') <@ string_to_array(p.actions,',')
    OR (p.expires_at IS NOT NULL AND (NEW.expires_at IS NULL OR NEW.expires_at>p.expires_at)) THEN RAISE EXCEPTION 'grant exceeds parent scope'; END IF;
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER agent_grant_chain_integrity BEFORE INSERT OR UPDATE ON nw_agent_definition_grants FOR EACH ROW EXECUTE FUNCTION enforce_agent_grant_chain();

ALTER TABLE nw_agent_deployments ADD COLUMN grant_chain jsonb;
ALTER TABLE nw_agent_deployment_revisions ADD COLUMN grant_chain jsonb;
UPDATE nw_agent_deployments d SET grant_chain=jsonb_build_object('definition_id',d.definition_id::text,'owner_kind',g.owner_kind,'owner_tenant_id',g.owner_tenant_id::text,
 'hops',jsonb_build_array(jsonb_build_object('grant_id',g.grant_id::text,'revision',d.grant_revision,'grantor_kind',g.grantor_kind,'grantor_tenant_id',g.grantor_tenant_id::text,
 'target_tenant_id',g.target_tenant_id::text,'parent_grant_id',NULL,'parent_grant_revision',NULL,'relation_ref',NULL,'relation_revision',NULL)))
 FROM nw_agent_definition_grants g WHERE g.grant_id=d.grant_id;
-- 历史修订表在迁移中仅增加明确原直授链字段。
ALTER TABLE nw_agent_deployment_revisions DISABLE TRIGGER skillforge_agent_deployment_revisions_immutable;
UPDATE nw_agent_deployment_revisions r SET grant_chain=d.grant_chain FROM nw_agent_deployments d WHERE d.deployment_id=r.deployment_id;
ALTER TABLE nw_agent_deployment_revisions ENABLE TRIGGER skillforge_agent_deployment_revisions_immutable;
ALTER TABLE nw_agent_deployments ALTER COLUMN grant_chain SET NOT NULL,ADD CHECK(jsonb_typeof(grant_chain)='object');
ALTER TABLE nw_agent_deployment_revisions ALTER COLUMN grant_chain SET NOT NULL,ADD CHECK(jsonb_typeof(grant_chain)='object');
CREATE TABLE nw_agent_deployment_receipts (
 deployment_id bigint NOT NULL,revision bigint NOT NULL,tenant_id bigint NOT NULL,response jsonb NOT NULL CHECK(jsonb_typeof(response)='object'),
 PRIMARY KEY(deployment_id,revision),FOREIGN KEY(deployment_id,revision) REFERENCES nw_agent_deployment_revisions(deployment_id,revision));
ALTER TABLE nw_agent_deployment_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_agent_deployment_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_agent_deployment_receipts_tenant ON nw_agent_deployment_receipts USING(tenant_id::text=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id::text=current_setting('app.tenant_id',true));
CREATE TRIGGER agent_deployment_receipt_immutable BEFORE UPDATE OR DELETE ON nw_agent_deployment_receipts FOR EACH ROW EXECUTE FUNCTION prevent_direct_deployment_revision_mutation();

-- Runtime同SqlSession复验：精确冻结身份，当前范围逐跳收窄；不选择其它授权也不读取机构私有业务。
CREATE FUNCTION public.agent_grant_chain_available(chain jsonb,target_tenant bigint,source_definition bigint,purpose text)
 RETURNS boolean LANGUAGE plpgsql STABLE AS $$
DECLARE h jsonb; g nw_agent_definition_grants; hist nw_agent_definition_grant_revisions; p nw_agent_definition_grants;
 source nw_agent_templates; n integer:=0; total integer; previous_id bigint:=NULL; visited bigint[]:='{}'; BEGIN
 IF chain IS NULL OR purpose IS NULL OR purpose NOT IN ('deploy','execute') OR jsonb_typeof(chain) IS DISTINCT FROM 'object' OR jsonb_typeof(chain->'hops') IS DISTINCT FROM 'array'
  OR (chain->>'definition_id')::bigint IS DISTINCT FROM source_definition THEN RETURN false; END IF;
 total=jsonb_array_length(chain->'hops'); IF total<1 THEN RETURN false; END IF;
 SELECT * INTO source FROM nw_agent_templates WHERE id=source_definition;
 IF source.id IS NULL OR source.status<>'published' OR source.distribution_policy<>'AUTHORIZED_DEPLOY'
  OR source.owner_kind IS DISTINCT FROM chain->>'owner_kind' OR source.owner_tenant_id IS DISTINCT FROM (chain->>'owner_tenant_id')::bigint
  OR (source.owner_kind='tenant' AND NOT EXISTS(SELECT 1 FROM nw_tenants WHERE id=source.owner_tenant_id AND status IN ('active','warning'))) THEN RETURN false; END IF;
 FOR h IN SELECT value FROM jsonb_array_elements(chain->'hops') LOOP
  n=n+1; SELECT * INTO g FROM nw_agent_definition_grants WHERE grant_id=(h->>'grant_id')::bigint;
  SELECT * INTO hist FROM nw_agent_definition_grant_revisions WHERE grant_id=(h->>'grant_id')::bigint AND revision=(h->>'revision')::bigint;
  IF g.grant_id IS NULL OR hist.grant_id IS NULL OR g.grant_id=ANY(visited) OR g.definition_id<>source_definition OR g.status<>'active'
   OR hist.status<>'active' OR (g.expires_at IS NOT NULL AND g.expires_at<=statement_timestamp())
   OR NOT purpose=ANY(string_to_array(g.actions,',')) OR g.owner_kind<>source.owner_kind OR g.owner_tenant_id IS DISTINCT FROM source.owner_tenant_id
   OR g.grantor_kind IS DISTINCT FROM h->>'grantor_kind' OR g.grantor_tenant_id IS DISTINCT FROM (h->>'grantor_tenant_id')::bigint
   OR g.target_tenant_id IS DISTINCT FROM (h->>'target_tenant_id')::bigint OR g.parent_grant_id IS DISTINCT FROM (h->>'parent_grant_id')::bigint
   OR g.parent_grant_revision IS DISTINCT FROM (h->>'parent_grant_revision')::bigint OR g.relation_ref IS DISTINCT FROM (h->>'relation_ref')::bigint
   OR g.relation_revision IS DISTINCT FROM (h->>'relation_revision')::bigint
   OR hist.definition_id<>g.definition_id OR hist.target_tenant_id<>g.target_tenant_id OR hist.grantor_kind<>g.grantor_kind
   OR hist.grantor_tenant_id IS DISTINCT FROM g.grantor_tenant_id OR hist.parent_grant_id IS DISTINCT FROM g.parent_grant_id
   OR hist.parent_grant_revision IS DISTINCT FROM g.parent_grant_revision OR hist.owner_kind<>g.owner_kind OR hist.owner_tenant_id IS DISTINCT FROM g.owner_tenant_id
   OR hist.relation_ref IS DISTINCT FROM g.relation_ref OR hist.relation_revision IS DISTINCT FROM g.relation_revision
   OR NOT EXISTS(SELECT 1 FROM nw_tenants WHERE id=g.target_tenant_id AND status IN ('active','warning')) THEN RETURN false; END IF;
  IF n=1 THEN
   IF g.parent_grant_id IS NOT NULL OR (g.grantor_kind='platform' AND source.owner_kind<>'platform')
    OR (g.grantor_kind='tenant' AND (source.owner_kind<>'tenant' OR source.owner_tenant_id IS DISTINCT FROM g.grantor_tenant_id)) THEN RETURN false; END IF;
  ELSE
   IF g.parent_grant_id IS DISTINCT FROM previous_id OR g.grantor_tenant_id IS DISTINCT FROM p.target_tenant_id
    OR g.grantor_kind<>'tenant' OR NOT p.allow_regrant OR p.remaining_depth<1 OR g.remaining_depth>=p.remaining_depth
    OR NOT string_to_array(g.actions,',') <@ string_to_array(p.actions,',')
    OR (p.expires_at IS NOT NULL AND (g.expires_at IS NULL OR g.expires_at>p.expires_at)) THEN RETURN false; END IF;
  END IF;
  IF g.grantor_kind='tenant' THEN
   IF NOT EXISTS(SELECT 1 FROM nw_tenants t JOIN nw_institution_relation_revisions r ON r.id=g.relation_ref AND r.child_tenant_id=t.id
    AND r.parent_tenant_id=g.grantor_tenant_id AND r.revision=t.relation_revision
    WHERE t.id=g.target_tenant_id AND t.parent_tenant_id=g.grantor_tenant_id AND t.relation_revision=g.relation_revision)
    OR NOT EXISTS(SELECT 1 FROM nw_tenants WHERE id=g.grantor_tenant_id AND status IN ('active','warning')) THEN RETURN false; END IF;
  END IF;
  visited=array_append(visited,g.grant_id);previous_id=g.grant_id;p=g;
 END LOOP;
 RETURN p.target_tenant_id=target_tenant;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range OR invalid_parameter_value THEN RETURN false;
END $$;
GRANT SELECT,INSERT ON nw_agent_definition_grant_revisions,nw_agent_definition_grant_requests,nw_agent_deployment_receipts TO skillforge;
GRANT EXECUTE ON FUNCTION public.agent_grant_chain_available(jsonb,bigint,bigint,text) TO skillforge;
COMMENT ON FUNCTION public.agent_grant_chain_available(jsonb,bigint,bigint,text) IS '精确冻结单链的当前治理有效性；历史修订不随narrow变化，不合并其它授权';
