-- 认证事实与独立安全审计；不复制当前 IAM 权限，不依赖业务 owner 外键。
CREATE TABLE public.nw_runtime_authentication_facts (
    authentication_ref text PRIMARY KEY,
    tenant_id text NOT NULL,
    principal_kind text NOT NULL CHECK (principal_kind IN ('SERVICE', 'SYSTEM')),
    principal_id text NOT NULL CHECK (btrim(principal_id) <> ''),
    integration_id text,
    credential_version bigint NOT NULL,
    agent_id text NOT NULL CHECK (btrim(agent_id) <> ''),
    session_id text NOT NULL CHECK (btrim(session_id) <> ''),
    end_user_id bigint NOT NULL CHECK (end_user_id > 0),
    channel text NOT NULL CHECK (btrim(channel) <> ''),
    source text NOT NULL CHECK (btrim(source) <> ''),
    expires_at timestamptz NOT NULL,
    active boolean NOT NULL DEFAULT true,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, authentication_ref),
    CHECK (btrim(tenant_id) <> '' AND authentication_ref LIKE 'authentication:%'),
    CHECK ((principal_kind = 'SERVICE' AND btrim(integration_id) <> '' AND integration_id IS NOT NULL AND credential_version > 0)
        OR (principal_kind = 'SYSTEM' AND integration_id IS NULL AND credential_version = 0)),
    CHECK (expires_at > recorded_at)
);
CREATE TABLE public.nw_runtime_identity_verifications (
    verification_ref text PRIMARY KEY,
    tenant_id text NOT NULL,
    authentication_ref text NOT NULL,
    scopes_snapshot text NOT NULL,
    identity_snapshot text NOT NULL CHECK (btrim(identity_snapshot) <> ''),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (tenant_id, authentication_ref)
      REFERENCES public.nw_runtime_authentication_facts(tenant_id, authentication_ref)
      ON DELETE RESTRICT
);
CREATE TABLE public.nw_runtime_security_audits (
    audit_ref text PRIMARY KEY,
    verified_tenant_id text,
    operation text NOT NULL CHECK (operation IN ('verify', 'authorize')),
    result text NOT NULL CHECK (result IN ('verified', 'identity_rejected', 'identity_unavailable',
                                         'allowed', 'authorization_denied', 'policy_unavailable')),
    policy_revision bigint NOT NULL CHECK (policy_revision > 0),
    subject_digest text NOT NULL CHECK (subject_digest ~ '^[a-f0-9]{64}$'),
    trace_digest text NOT NULL CHECK (trace_digest ~ '^[a-f0-9]{64}$'),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX agent_runtime_identity_verification_auth_idx
  ON public.nw_runtime_identity_verifications(tenant_id, authentication_ref);
CREATE INDEX agent_runtime_security_audit_tenant_idx
  ON public.nw_runtime_security_audits(verified_tenant_id, recorded_at);

ALTER TABLE public.nw_runtime_authentication_facts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_authentication_facts FORCE ROW LEVEL SECURITY;
CREATE POLICY runtime_authentication_tenant ON public.nw_runtime_authentication_facts
  USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
  WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));
ALTER TABLE public.nw_runtime_identity_verifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_identity_verifications FORCE ROW LEVEL SECURITY;
CREATE POLICY runtime_verification_tenant ON public.nw_runtime_identity_verifications
  USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
  WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));
ALTER TABLE public.nw_runtime_security_audits ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_security_audits FORCE ROW LEVEL SECURITY;
-- 无可信租户的拒绝也要留审计；普通角色不能读全局失败日志。
CREATE POLICY runtime_security_audit_insert ON public.nw_runtime_security_audits FOR INSERT
  WITH CHECK (verified_tenant_id IS NULL OR verified_tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));
CREATE POLICY runtime_security_audit_read ON public.nw_runtime_security_audits FOR SELECT
  USING (verified_tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

CREATE FUNCTION public.resolve_runtime_authentication_tenant(p_reference text) RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS $$
  SELECT tenant_id FROM public.nw_runtime_authentication_facts WHERE authentication_ref = p_reference
$$;
REVOKE ALL ON FUNCTION public.resolve_runtime_authentication_tenant(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.resolve_runtime_authentication_tenant(text) TO skillforge;

CREATE FUNCTION public.guard_runtime_identity_fact() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND TG_TABLE_NAME = 'nw_runtime_authentication_facts' THEN
    IF (to_jsonb(NEW) - 'active') = (to_jsonb(OLD) - 'active') AND OLD.active AND NOT NEW.active THEN
      RETURN NEW;
    END IF;
  END IF;
  RAISE EXCEPTION 'runtime_identity_fact_immutable';
END $$;
CREATE TRIGGER runtime_authentication_immutable BEFORE UPDATE OR DELETE
  ON public.nw_runtime_authentication_facts FOR EACH ROW EXECUTE FUNCTION public.guard_runtime_identity_fact();
CREATE TRIGGER runtime_verification_immutable BEFORE UPDATE OR DELETE
  ON public.nw_runtime_identity_verifications FOR EACH ROW EXECUTE FUNCTION public.guard_runtime_identity_fact();
CREATE TRIGGER runtime_security_audit_immutable BEFORE UPDATE OR DELETE
  ON public.nw_runtime_security_audits FOR EACH ROW EXECUTE FUNCTION public.guard_runtime_identity_fact();

COMMENT ON TABLE public.nw_runtime_authentication_facts IS '受信宿主入口签发的不可逆认证事实；不存凭据或当前权限';
COMMENT ON COLUMN public.nw_runtime_authentication_facts.authentication_ref IS '随机不可逆认证引用，不能由请求自行签发';
COMMENT ON COLUMN public.nw_runtime_authentication_facts.tenant_id IS '原认证主体租户，RLS隔离键';
COMMENT ON COLUMN public.nw_runtime_authentication_facts.principal_kind IS '可信主体类型SERVICE或SYSTEM；无真实认证入口的EndUser不签发';
COMMENT ON COLUMN public.nw_runtime_authentication_facts.principal_id IS '原认证主体标识';
COMMENT ON COLUMN public.nw_runtime_authentication_facts.integration_id IS '原服务集成标识，系统主体为空';
COMMENT ON COLUMN public.nw_runtime_authentication_facts.credential_version IS '原服务凭据版本，系统主体为零；不代表策略版本';
COMMENT ON COLUMN public.nw_runtime_authentication_facts.agent_id IS '认证入口绑定的Agent';
COMMENT ON COLUMN public.nw_runtime_authentication_facts.session_id IS '认证入口绑定的Session';
COMMENT ON COLUMN public.nw_runtime_authentication_facts.end_user_id IS '服务或系统代办的完整owner终端用户';
COMMENT ON COLUMN public.nw_runtime_authentication_facts.channel IS '原认证入口渠道';
COMMENT ON COLUMN public.nw_runtime_authentication_facts.source IS '原认证入口来源';
COMMENT ON COLUMN public.nw_runtime_authentication_facts.expires_at IS '存储时间基准的认证失效时刻';
COMMENT ON COLUMN public.nw_runtime_authentication_facts.active IS '仅允许从有效转为已撤销';
COMMENT ON COLUMN public.nw_runtime_authentication_facts.recorded_at IS '认证事实数据库提交记录时间';
COMMENT ON TABLE public.nw_runtime_identity_verifications IS '验证成功的不可变全值快照，与独立验证审计同事务提交';
COMMENT ON COLUMN public.nw_runtime_identity_verifications.verification_ref IS '原验证结果引用，同时指向验证成功审计';
COMMENT ON COLUMN public.nw_runtime_identity_verifications.tenant_id IS '原认证租户，RLS隔离键';
COMMENT ON COLUMN public.nw_runtime_identity_verifications.authentication_ref IS '原认证事实引用';
COMMENT ON COLUMN public.nw_runtime_identity_verifications.scopes_snapshot IS '当次验证scope的排序Base64快照；授权必须重新读取IAM';
COMMENT ON COLUMN public.nw_runtime_identity_verifications.identity_snapshot IS '版本化完整VerifiedIdentity编码，拒绝调用方伪造或漂移';
COMMENT ON COLUMN public.nw_runtime_identity_verifications.recorded_at IS '数据库记录时间';
COMMENT ON TABLE public.nw_runtime_security_audits IS '独立安全审计；未验证请求的租户为空，拒绝将claimed tenant写为可信归属';
COMMENT ON COLUMN public.nw_runtime_security_audits.audit_ref IS '实际持久化审计事实引用';
COMMENT ON COLUMN public.nw_runtime_security_audits.verified_tenant_id IS '从原认证事实解析的租户；未知认证或伪造验证引用为空';
COMMENT ON COLUMN public.nw_runtime_security_audits.operation IS 'verify或authorize安全操作';
COMMENT ON COLUMN public.nw_runtime_security_audits.result IS '固定安全结果码，不保存异常或请求正文';
COMMENT ON COLUMN public.nw_runtime_security_audits.policy_revision IS '同次评估的动作/schema策略版本，与credential version独立';
COMMENT ON COLUMN public.nw_runtime_security_audits.subject_digest IS '域分离SHA256认证或验证引用摘要';
COMMENT ON COLUMN public.nw_runtime_security_audits.trace_digest IS '域分离SHA256追踪摘要';
COMMENT ON COLUMN public.nw_runtime_security_audits.recorded_at IS '数据库记录时间';
COMMENT ON INDEX public.agent_runtime_identity_verification_auth_idx IS '按租户追溯原认证的验证事实';
COMMENT ON INDEX public.agent_runtime_security_audit_tenant_idx IS '受RLS约束的租户审计时间检索';
COMMENT ON FUNCTION public.resolve_runtime_authentication_tenant(text) IS '认证前只解析随机引用的原租户，不返回主体/凭据/权限';
COMMENT ON FUNCTION public.guard_runtime_identity_fact() IS '禁止修改或删除安全事实，只允许撤销认证';


-- 受信宿主登记的独立执行身份，不要求将平台配置复制到机构 agents。
CREATE TABLE nw_runtime_host_owners (
 tenant_id text NOT NULL,
 agent_id text NOT NULL,
 session_id text NOT NULL,
 end_user_id bigint NOT NULL CHECK(end_user_id>0),
 integration_id text NOT NULL CHECK(length(integration_id)>0),
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,agent_id,session_id)
);
ALTER TABLE nw_runtime_host_owners ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_runtime_host_owners FORCE ROW LEVEL SECURITY;
CREATE POLICY runtime_host_owners_tenant ON nw_runtime_host_owners
 USING(tenant_id=current_setting('app.tenant_id',true))
 WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
COMMENT ON TABLE nw_runtime_host_owners IS '受信宿主登记的独立执行owner，只能由宿主内部装配写入';
COMMENT ON COLUMN nw_runtime_host_owners.tenant_id IS '数据与执行隔离空间';
COMMENT ON COLUMN nw_runtime_host_owners.agent_id IS '宿主智能体身份';
COMMENT ON COLUMN nw_runtime_host_owners.session_id IS '本次独立执行会话';
COMMENT ON COLUMN nw_runtime_host_owners.end_user_id IS '宿主执行身份，平台调试固定内部主体1';
COMMENT ON COLUMN nw_runtime_host_owners.integration_id IS '准入服务主体必须匹配的集成标识';
COMMENT ON COLUMN nw_runtime_host_owners.created_at IS '可信登记时间';

CREATE FUNCTION reject_runtime_host_owner_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'runtime_host_owner_immutable'; END; $$;
CREATE TRIGGER runtime_host_owner_immutable BEFORE UPDATE OR DELETE ON nw_runtime_host_owners
 FOR EACH ROW EXECUTE FUNCTION reject_runtime_host_owner_change();
COMMENT ON FUNCTION reject_runtime_host_owner_change() IS '受信宿主owner登记后不可改变归属';
