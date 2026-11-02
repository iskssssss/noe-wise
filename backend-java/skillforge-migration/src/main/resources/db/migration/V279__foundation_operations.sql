-- 平台安全事件和未归因认证失败进入同一追加审计表，机构永远只能读取自己归属的事实。
ALTER TABLE public.nw_audit_logs ALTER COLUMN tenant_id DROP NOT NULL;
ALTER TABLE public.nw_audit_logs ADD COLUMN category varchar(32) NOT NULL DEFAULT 'operation'
  CHECK(category IN('authentication','authorization','sensitive','export','operation'));
ALTER TABLE public.nw_audit_logs ADD COLUMN outcome varchar(16) NOT NULL DEFAULT 'success'
  CHECK(outcome IN('success','failure','denied'));
COMMENT ON COLUMN public.nw_audit_logs.tenant_id IS '机构归属；NULL为平台事件或无法归因到已验证机构的认证失败';
COMMENT ON COLUMN public.nw_audit_logs.category IS '安全事件分类：认证、授权、敏感读取、导出、普通操作';
COMMENT ON COLUMN public.nw_audit_logs.outcome IS '实际操作结果success/failure/denied，不能把未知结果标为成功';
DROP POLICY skillforge_audit_logs_tenant_isolation ON public.nw_audit_logs;
CREATE POLICY skillforge_audit_logs_tenant_read ON public.nw_audit_logs FOR SELECT
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint);
CREATE POLICY skillforge_audit_logs_tenant_insert ON public.nw_audit_logs FOR INSERT
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint
  OR(tenant_id IS NULL AND category='authentication' AND outcome IN('failure','denied')));
CREATE POLICY skillforge_audit_logs_tenant_delete ON public.nw_audit_logs FOR DELETE
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint);
-- SELECT FOR UPDATE需要UPDATE行策略；真正UPDATE始终被下方不可变触发器拒绝。
CREATE POLICY skillforge_audit_logs_tenant_lock ON public.nw_audit_logs FOR UPDATE
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint)
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint);
CREATE INDEX idx_audit_security_query ON public.nw_audit_logs(tenant_id,category,outcome,created_at DESC,id DESC);
CREATE INDEX idx_audit_platform_query ON public.nw_audit_logs(created_at DESC,id DESC) WHERE tenant_id IS NULL;

CREATE TABLE public.nw_foundation_retention_policies(
 scope varchar(32) NOT NULL CHECK(scope='platform' OR scope ~ '^[1-9][0-9]{0,18}$'),
 resource varchar(32) NOT NULL CHECK(resource='audit'),
 retention_days integer NOT NULL CHECK(retention_days BETWEEN 30 AND 3650),
 enabled boolean NOT NULL DEFAULT false,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(scope,resource)
);
COMMENT ON TABLE public.nw_foundation_retention_policies IS '当前审计保留策略；默认关闭，30天硬下限，版本CAS防止覆盖';
COMMENT ON COLUMN public.nw_foundation_retention_policies.scope IS 'platform或精确机构十进制ID，可信主体决定';
COMMENT ON COLUMN public.nw_foundation_retention_policies.resource IS '拥有删除写权的资源，目前仅audit';
COMMENT ON COLUMN public.nw_foundation_retention_policies.retention_days IS '保留天数，30至3650，新预览受理时冻结';
COMMENT ON COLUMN public.nw_foundation_retention_policies.enabled IS '是否允许新清理，默认关闭';
COMMENT ON COLUMN public.nw_foundation_retention_policies.version IS '单调CAS版本，变化使旧预览无法执行';
COMMENT ON COLUMN public.nw_foundation_retention_policies.created_at IS '创建时间';
COMMENT ON COLUMN public.nw_foundation_retention_policies.updated_at IS '最后更新生效时间';

CREATE TABLE public.nw_foundation_cleanup_runs(
 id bigint PRIMARY KEY CHECK(id>0),
 scope varchar(32) NOT NULL,
 actor_id varchar(128) NOT NULL,
 resource varchar(32) NOT NULL CHECK(resource='audit'),
 policy_version bigint NOT NULL CHECK(policy_version>=0),
 cutoff timestamptz NOT NULL,
 candidate_ids jsonb NOT NULL CHECK(jsonb_typeof(candidate_ids)='array' AND jsonb_array_length(candidate_ids)<=1000),
 candidates bigint NOT NULL CHECK(candidates BETWEEN 0 AND 1000),
 protected_count bigint NOT NULL CHECK(protected_count BETWEEN 0 AND candidates),
 deleted_count bigint NOT NULL DEFAULT 0 CHECK(deleted_count BETWEEN 0 AND candidates),
 status varchar(16) NOT NULL CHECK(status IN('ready','succeeded','failed','expired')),
 expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 completed_at timestamptz,
 failure_code varchar(64),
 FOREIGN KEY(scope,resource) REFERENCES public.nw_foundation_retention_policies(scope,resource),
 CHECK((status='ready')=(completed_at IS NULL)),
 CHECK(expires_at>created_at),
 CHECK(candidates=jsonb_array_length(candidate_ids))
);
CREATE INDEX idx_skillforge_foundation_cleanup_runs ON public.nw_foundation_cleanup_runs(scope,created_at DESC,id DESC);
COMMENT ON TABLE public.nw_foundation_cleanup_runs IS '持久清理预览与执行回执；冻结候选不扩大范围；终态幂等读取';
COMMENT ON COLUMN public.nw_foundation_cleanup_runs.id IS '清理预览与回执ID';
COMMENT ON COLUMN public.nw_foundation_cleanup_runs.scope IS '可信主体作用域';
COMMENT ON COLUMN public.nw_foundation_cleanup_runs.actor_id IS '生成预览的真实主体';
COMMENT ON COLUMN public.nw_foundation_cleanup_runs.resource IS '删除写权所属资源';
COMMENT ON COLUMN public.nw_foundation_cleanup_runs.policy_version IS '预览受理时冻结的策略版本';
COMMENT ON COLUMN public.nw_foundation_cleanup_runs.cutoff IS '预览冻结的到期截止时间';
COMMENT ON COLUMN public.nw_foundation_cleanup_runs.candidate_ids IS '最多1000个确定候选ID，执行不扩大';
COMMENT ON COLUMN public.nw_foundation_cleanup_runs.candidates IS '冻结候选数量';
COMMENT ON COLUMN public.nw_foundation_cleanup_runs.protected_count IS '预览或执行时活动调查引用保护数量';
COMMENT ON COLUMN public.nw_foundation_cleanup_runs.deleted_count IS '已提交事务实际删除数量';
COMMENT ON COLUMN public.nw_foundation_cleanup_runs.status IS 'ready可执行；succeeded/failed/expired均为终态';
COMMENT ON COLUMN public.nw_foundation_cleanup_runs.expires_at IS '预览15分钟后到期';
COMMENT ON COLUMN public.nw_foundation_cleanup_runs.created_at IS '预览登记时间';
COMMENT ON COLUMN public.nw_foundation_cleanup_runs.completed_at IS '终态提交时间';
COMMENT ON COLUMN public.nw_foundation_cleanup_runs.failure_code IS '固定脱敏技术错误编码';

CREATE TABLE public.nw_foundation_audit_holds(
 id bigint PRIMARY KEY CHECK(id>0),
 scope varchar(32) NOT NULL CHECK(scope='platform' OR scope ~ '^[1-9][0-9]{0,18}$'),
 audit_id bigint NOT NULL CHECK(audit_id>0),
 reason varchar(500) NOT NULL CHECK(btrim(reason)<>''),
 expires_at timestamptz,
 released_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now()
);
-- 不以永久FK保护已解除引用；删除触发器和登记时的审计行锁保护活动引用，历史解除回执保留。
CREATE INDEX idx_skillforge_foundation_audit_holds_active ON public.nw_foundation_audit_holds(audit_id) WHERE released_at IS NULL;
COMMENT ON TABLE public.nw_foundation_audit_holds IS '对具体审计事实的调查/法务保留引用；登记与清理锁同一事实行，解除记录继续保留';
COMMENT ON COLUMN public.nw_foundation_audit_holds.id IS '引用保护ID';
COMMENT ON COLUMN public.nw_foundation_audit_holds.scope IS '保护和审计事实必须相同作用域';
COMMENT ON COLUMN public.nw_foundation_audit_holds.audit_id IS '不可重绑定的审计事实ID，已解除引用可保留墓碑';
COMMENT ON COLUMN public.nw_foundation_audit_holds.reason IS '受控调查或法务保留原因，不放入凭据或正文';
COMMENT ON COLUMN public.nw_foundation_audit_holds.expires_at IS '可选保护到期时间，NULL须显式解除';
COMMENT ON COLUMN public.nw_foundation_audit_holds.released_at IS '显式解除时间';
COMMENT ON COLUMN public.nw_foundation_audit_holds.created_at IS '引用登记时间';

DO $$ DECLARE name text; BEGIN
 FOREACH name IN ARRAY ARRAY['nw_foundation_retention_policies','nw_foundation_cleanup_runs','nw_foundation_audit_holds'] LOOP
  EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',name);
  EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY',name);
  EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING(scope=current_setting(''app.tenant_id'',true)) WITH CHECK(scope=current_setting(''app.tenant_id'',true))',name);
 END LOOP;
END $$;

CREATE FUNCTION public.foundation_audit_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'audit_event_immutable'; END IF;
 IF current_setting('app.audit_cleanup',true) IS DISTINCT FROM 'on' OR OLD.created_at>now()-interval '30 days'
  THEN RAISE EXCEPTION 'audit_delete_requires_retention'; END IF;
 IF EXISTS(SELECT 1 FROM public.nw_foundation_audit_holds WHERE audit_id=OLD.id AND released_at IS NULL AND(expires_at IS NULL OR expires_at>now()))
  THEN RAISE EXCEPTION 'audit_event_held'; END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER foundation_audit_immutable BEFORE UPDATE OR DELETE ON public.nw_audit_logs FOR EACH ROW EXECUTE FUNCTION public.foundation_audit_immutable();

CREATE FUNCTION public.foundation_audit_hold_guard() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE actual_scope text; BEGIN
 IF TG_OP='INSERT' THEN
  SELECT COALESCE(tenant_id::text,'platform') INTO actual_scope FROM public.nw_audit_logs WHERE id=NEW.audit_id FOR UPDATE;
  IF actual_scope IS NULL OR actual_scope<>NEW.scope THEN RAISE EXCEPTION 'audit_hold_scope_invalid'; END IF;
 ELSE
  IF NEW.id<>OLD.id OR NEW.scope<>OLD.scope OR NEW.audit_id<>OLD.audit_id OR NEW.reason<>OLD.reason
  OR NEW.expires_at IS DISTINCT FROM OLD.expires_at OR NEW.created_at<>OLD.created_at
  OR OLD.released_at IS NOT NULL OR NEW.released_at IS NULL
  THEN RAISE EXCEPTION 'audit_hold_immutable'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER foundation_audit_hold_guard BEFORE INSERT OR UPDATE ON public.nw_foundation_audit_holds FOR EACH ROW EXECUTE FUNCTION public.foundation_audit_hold_guard();

CREATE FUNCTION public.foundation_cleanup_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF OLD.status<>'ready' OR NEW.status='ready' OR NEW.scope<>OLD.scope OR NEW.id<>OLD.id
 OR NEW.candidate_ids<>OLD.candidate_ids OR NEW.cutoff<>OLD.cutoff OR NEW.policy_version<>OLD.policy_version
 OR NEW.actor_id<>OLD.actor_id OR NEW.resource<>OLD.resource OR NEW.candidates<>OLD.candidates
 OR NEW.expires_at<>OLD.expires_at OR NEW.created_at<>OLD.created_at
 THEN RAISE EXCEPTION 'cleanup_receipt_immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER foundation_cleanup_immutable BEFORE UPDATE ON public.nw_foundation_cleanup_runs FOR EACH ROW EXECUTE FUNCTION public.foundation_cleanup_immutable();
