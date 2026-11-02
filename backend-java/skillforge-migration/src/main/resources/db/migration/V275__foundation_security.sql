-- 安全会话和凭据由宿主管理，不进入 Runtime 冻结历史。
CREATE TABLE nw_foundation_security_mfa (
 scope_key varchar(64) NOT NULL, member_id bigint NOT NULL,
 secret_encrypted text, enabled boolean NOT NULL DEFAULT false,
 last_counter bigint NOT NULL DEFAULT -1 CHECK(last_counter >= -1),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(scope_key,member_id), CHECK(NOT enabled OR secret_encrypted IS NOT NULL),
 CHECK(scope_key='platform' OR scope_key ~ '^tenant:[1-9][0-9]*$')
);
CREATE TABLE nw_foundation_security_recovery_codes (
 scope_key varchar(64) NOT NULL,member_id bigint NOT NULL,code_hash varchar(64) NOT NULL CHECK(code_hash ~ '^[a-f0-9]{64}$'),
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(scope_key,member_id,code_hash),
 FOREIGN KEY(scope_key,member_id) REFERENCES nw_foundation_security_mfa(scope_key,member_id) ON DELETE CASCADE
);
CREATE TABLE nw_foundation_security_sessions (
 id bigint PRIMARY KEY,scope_key varchar(64) NOT NULL,member_id bigint NOT NULL,
 credential_hash varchar(64) NOT NULL CHECK(credential_hash ~ '^[a-f0-9]{64}$'),
 refresh_hash varchar(64) CHECK(refresh_hash ~ '^[a-f0-9]{64}$'),
 state varchar(32) NOT NULL CHECK(state IN ('enrollment','challenge','full')),
 device_label varchar(256) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz NOT NULL,verified_at timestamptz,revoked_at timestamptz,
 CHECK(expires_at > created_at),CHECK(scope_key='platform' OR scope_key ~ '^tenant:[1-9][0-9]*$'),
 UNIQUE(scope_key,refresh_hash)
);
CREATE INDEX foundation_security_session_member ON nw_foundation_security_sessions(scope_key,member_id,created_at DESC) WHERE revoked_at IS NULL;
CREATE TABLE nw_foundation_security_policies (
 scope_key varchar(64) PRIMARY KEY CHECK(scope_key='platform' OR scope_key ~ '^tenant:[1-9][0-9]*$'),
 require_all_mfa boolean NOT NULL DEFAULT false,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE nw_foundation_security_recovery_tokens (
 scope_key varchar(64) NOT NULL, member_id bigint NOT NULL,
 purpose varchar(32) NOT NULL CHECK(purpose IN ('recovery','invitation')),
 token_hash varchar(64) NOT NULL CHECK(token_hash ~ '^[a-f0-9]{64}$'),
 created_at timestamptz NOT NULL DEFAULT now(),expires_at timestamptz NOT NULL CHECK(expires_at > created_at),
 PRIMARY KEY(scope_key,token_hash),CHECK(scope_key='platform' OR scope_key ~ '^tenant:[1-9][0-9]*$')
);
DO $security$
DECLARE security_table text;
BEGIN
 FOREACH security_table IN ARRAY ARRAY['nw_foundation_security_mfa','nw_foundation_security_recovery_codes','nw_foundation_security_sessions','nw_foundation_security_policies','nw_foundation_security_recovery_tokens'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',security_table);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',security_table);
  EXECUTE format('CREATE POLICY security_scope ON %I USING (scope_key = ''tenant:'' || current_setting(''app.tenant_id'',true) OR (scope_key = ''platform'' AND coalesce(current_setting(''app.tenant_id'',true),'''') = '''')) WITH CHECK (scope_key = ''tenant:'' || current_setting(''app.tenant_id'',true) OR (scope_key = ''platform'' AND coalesce(current_setting(''app.tenant_id'',true),'''') = ''''))',security_table);
 END LOOP;
END $security$;
COMMENT ON TABLE nw_foundation_security_mfa IS 'AES-GCM保护TOTP种子；行锁和计数器阻止验证码重放';
COMMENT ON TABLE nw_foundation_security_recovery_codes IS '高熵恢复码仅存摘要，使用即原子删除';
COMMENT ON TABLE nw_foundation_security_sessions IS '访问与刷新令牌共同引用的权威设备会话；当前账号密码指纹与撤销状态实时验证';
COMMENT ON TABLE nw_foundation_security_policies IS '租户全员MFA策略；默认关闭，仅显式开启生效';
COMMENT ON TABLE nw_foundation_security_recovery_tokens IS '邮件交付的一次性密码恢复与邀请激活凭据；不提供查询明文';
