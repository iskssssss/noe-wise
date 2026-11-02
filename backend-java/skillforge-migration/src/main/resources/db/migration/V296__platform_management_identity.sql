-- 独立平台管理机器身份；不存在 Application 归属，也不授予机构执行身份。
CREATE TABLE nw_platform_management_principals (
    principal_id bigint PRIMARY KEY,
    name varchar(200) NOT NULL CHECK(btrim(name)<>''),
    status varchar(16) NOT NULL CHECK(status IN('active','revoked')),
    revision bigint NOT NULL CHECK(revision>0),
    credential_version bigint NOT NULL CHECK(credential_version>0),
    key_hash varchar(64) NOT NULL UNIQUE CHECK(key_hash ~ '^[a-f0-9]{64}$'),
    key_prefix varchar(16) NOT NULL,
    signing_secret_encrypted text,
    created_by_ref varchar(128) NOT NULL CHECK(created_by_ref ~ '^platform_member:[1-9][0-9]*$'),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK((status='active' AND signing_secret_encrypted IS NOT NULL) OR (status='revoked' AND signing_secret_encrypted IS NULL))
);
CREATE INDEX skillforge_platform_management_principals_created_idx ON nw_platform_management_principals(created_at DESC,principal_id DESC);
CREATE TABLE nw_platform_management_scopes (
    principal_id bigint NOT NULL REFERENCES nw_platform_management_principals(principal_id) ON DELETE RESTRICT,
    action varchar(128) NOT NULL CHECK(action ~ '^platform\.[a-z_]+\.[a-z_.]+$'),
    PRIMARY KEY(principal_id,action)
);
CREATE TABLE nw_platform_management_targets (
    principal_id bigint NOT NULL REFERENCES nw_platform_management_principals(principal_id) ON DELETE RESTRICT,
    kind varchar(32) NOT NULL CHECK(kind IN('tenant','agent_definition','namespace')),
    target_value varchar(128) NOT NULL CHECK(btrim(target_value)<>'' AND position('*' IN target_value)=0),
    CHECK(kind='namespace' OR target_value ~ '^[1-9][0-9]{0,18}$'),
    PRIMARY KEY(principal_id,kind,target_value)
);
CREATE TABLE nw_platform_management_receipts (
    actor_ref varchar(128) NOT NULL CHECK(actor_ref ~ '^platform_member:[1-9][0-9]*$'),
    operation varchar(32) NOT NULL CHECK(operation IN('create','rotate','revoke')),
    object_scope varchar(128) NOT NULL,
    idempotency_key varchar(128) NOT NULL CHECK(btrim(idempotency_key)<>''),
    fingerprint varchar(64) NOT NULL CHECK(fingerprint ~ '^[a-f0-9]{64}$'),
    result_json jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(actor_ref,operation,object_scope,idempotency_key),
    CHECK(result_json IS NULL OR (jsonb_typeof(result_json)='object' AND NOT result_json ?| ARRAY['management_key','signing_secret','signing_secret_encrypted','key_hash']))
);
-- 只扩张平台治理资源种类，原机构 Agent/Session 共享保持原范围。
ALTER TABLE nw_foundation_resource_ownership DROP CONSTRAINT nw_foundation_resource_ownership_scope_key_check;
ALTER TABLE nw_foundation_resource_ownership DROP CONSTRAINT nw_foundation_resource_ownership_kind_check;
ALTER TABLE nw_foundation_resource_ownership ADD CONSTRAINT skillforge_foundation_resource_ownership_domain_check CHECK(
    (scope_key='platform' AND kind IN('tenant','agent_definition')) OR
    (scope_key<>'platform' AND kind IN('agent','workflow','knowledge','session'))
);
ALTER TABLE nw_foundation_role_permissions DROP CONSTRAINT nw_foundation_role_permissions_permission_code_check;
ALTER TABLE nw_foundation_role_permissions ADD CONSTRAINT skillforge_foundation_role_permissions_domain_check CHECK(
    permission_code ~ '^foundation\.[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'
    OR (scope_key='platform' AND permission_code ~ '^platform\.[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$')
    OR (scope_key<>'platform' AND permission_code ~ '^tenant\.[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$')
);
CREATE TABLE nw_platform_management_namespace_grants (
    namespace varchar(128) NOT NULL CHECK(btrim(namespace)<>'' AND position('*' IN namespace)=0),
    grantee_type varchar(16) NOT NULL CHECK(grantee_type IN('member','role','department')),
    grantee_id bigint NOT NULL,
    action varchar(128) NOT NULL CHECK(action IN('platform.institutions.provision','platform.institutions.associate','platform.agent_definitions.create')),
    created_by_id bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(namespace,grantee_type,grantee_id,action)
);
-- 管理表仅在平台上下文可见；绑定机构执行域时不能查询管理密文或篡改目标。
DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['nw_platform_management_principals','nw_platform_management_scopes','nw_platform_management_targets','nw_platform_management_receipts','nw_platform_management_namespace_grants'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO skillforge',table_name);
        EXECUTE format('CREATE POLICY platform_management_only ON %I USING (coalesce(current_setting(''app.tenant_id'',true),'''')='''') WITH CHECK (coalesce(current_setting(''app.tenant_id'',true),'''')='''')',table_name);
    END LOOP;
END $$;
CREATE FUNCTION platform_namespace_authorization_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE nw_foundation_access_scopes SET authorization_version=authorization_version+1 WHERE scope_key='platform';
    RETURN NULL;
END $$;
CREATE TRIGGER platform_namespace_authorization_version AFTER INSERT OR UPDATE OR DELETE ON nw_platform_management_namespace_grants
    FOR EACH STATEMENT EXECUTE FUNCTION platform_namespace_authorization_changed();
-- 并发失败的事务整体回滚；安全回执未完成时不能留下已签发但不可查的提交。
CREATE FUNCTION platform_management_receipt_complete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM nw_platform_management_receipts WHERE actor_ref=NEW.actor_ref AND operation=NEW.operation
        AND object_scope=NEW.object_scope AND idempotency_key=NEW.idempotency_key AND result_json IS NULL) THEN
        RAISE EXCEPTION 'platform_management_receipt_incomplete';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER platform_management_receipt_completion AFTER INSERT OR UPDATE ON nw_platform_management_receipts
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform_management_receipt_complete();
CREATE FUNCTION platform_management_receipt_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.result_json IS NOT NULL THEN RAISE EXCEPTION 'platform_management_receipt_immutable'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER platform_management_receipt_immutability BEFORE UPDATE OR DELETE ON nw_platform_management_receipts
    FOR EACH ROW EXECUTE FUNCTION platform_management_receipt_immutable();
COMMENT ON TABLE nw_platform_management_principals IS '独立受限平台管理机器；Key摘要和加密签名秘密成对存储，撤销终态';
COMMENT ON TABLE nw_platform_management_scopes IS '固定精确管理动作，不能隐含机构执行或管理身份自扩权';
COMMENT ON TABLE nw_platform_management_targets IS '明确Tenant/Agent定义/namespace目标，空集合代表无该类目标';
COMMENT ON TABLE nw_platform_management_receipts IS '主体和业务操作稳定幂等回执，仅保存安全原响应，不保存任何秘密';
COMMENT ON TABLE nw_platform_management_namespace_grants IS '当前平台人员的明确namespace目标范围，不替代Foundation动作授权';
