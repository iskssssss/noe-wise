-- 不保存 body/envelope，只保存首次受信签名入口的摘要和稳定系统认证元数据。
CREATE TABLE agent_runtime_callback_ingress_proofs (
    tenant_id text NOT NULL,
    callback_id text NOT NULL,
    delivery_id text NOT NULL,
    fingerprint text NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    authentication_ref text NOT NULL CHECK (length(authentication_ref) > 0),
    command_digest text NOT NULL CHECK (command_digest ~ '^[0-9a-f]{64}$'),
    registration_revision bigint NOT NULL CHECK (registration_revision > 0),
    verified_at numeric(30,9) NOT NULL CHECK (verified_at <> 'NaN'::numeric),
    PRIMARY KEY (tenant_id, delivery_id),
    FOREIGN KEY (tenant_id, callback_id) REFERENCES agent_runtime_callback_registrations (tenant_id, callback_id)
);
ALTER TABLE agent_runtime_callback_ingress_proofs ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_runtime_callback_ingress_proofs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON agent_runtime_callback_ingress_proofs
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
CREATE FUNCTION agent_runtime_callback_proof_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'callback_ingress_proof_immutable';
END;
$$;
CREATE TRIGGER callback_ingress_proof_immutable BEFORE UPDATE OR DELETE ON agent_runtime_callback_ingress_proofs
    FOR EACH ROW EXECUTE FUNCTION agent_runtime_callback_proof_immutable();
COMMENT ON TABLE agent_runtime_callback_ingress_proofs IS '受信回调签名证明；只保留摘要和重建稳定命令所需认证元数据，不保留业务载荷';
COMMENT ON COLUMN agent_runtime_callback_ingress_proofs.tenant_id IS '原签名凭据与注册所属租户';
COMMENT ON COLUMN agent_runtime_callback_ingress_proofs.callback_id IS '原回调注册身份';
COMMENT ON COLUMN agent_runtime_callback_ingress_proofs.delivery_id IS '租户内不可复用的回调投递身份';
COMMENT ON COLUMN agent_runtime_callback_ingress_proofs.fingerprint IS '绑定租户、注册、投递和原始业务字节的摘要，不含每次签名时间';
COMMENT ON COLUMN agent_runtime_callback_ingress_proofs.authentication_ref IS '首次签发并覆盖恢复责任期的系统认证引用';
COMMENT ON COLUMN agent_runtime_callback_ingress_proofs.command_digest IS '首次完整 VerifiedCallbackDelivery 的不可变摘要';
COMMENT ON COLUMN agent_runtime_callback_ingress_proofs.registration_revision IS '首次验证时的注册修订';
COMMENT ON COLUMN agent_runtime_callback_ingress_proofs.verified_at IS '首次验证的数据库 epoch 秒，保留纳秒';
COMMENT ON FUNCTION agent_runtime_callback_proof_immutable() IS '防止已签名命令的摘要、认证与时间被后续重试替换';
