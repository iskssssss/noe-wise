-- 配置权威仍是 tenants/agents；本表只冻结已发布的 exact binding，不双写当前宿主配置。
CREATE TABLE agent_runtime_model_bindings (
    tenant_id text NOT NULL CHECK (length(tenant_id) > 0),
    binding_id text NOT NULL CHECK (length(binding_id) > 0),
    revision bigint NOT NULL CHECK (revision > 0),
    agent_id text CHECK (agent_id IS NULL OR length(agent_id) > 0),
    source_kind text NOT NULL CHECK (source_kind IN ('TENANT','AGENT')),
    source_id text NOT NULL CHECK (length(source_id) > 0),
    content_digest text NOT NULL CHECK (content_digest ~ '^[0-9a-f]{64}$'),
    endpoint text NOT NULL CHECK (length(endpoint) > 0),
    model_id text NOT NULL CHECK (length(model_id) > 0),
    encrypted_credential text NOT NULL CHECK (length(encrypted_credential) > 0),
    strategy_id text NOT NULL CHECK (length(strategy_id) > 0),
    strategy_version text NOT NULL CHECK (length(strategy_version) > 0),
    response_formats text NOT NULL CHECK (response_formats IN
        ('TEXT','JSON_OBJECT,TEXT','JSON_SCHEMA,TEXT','JSON_OBJECT,JSON_SCHEMA,TEXT')),
    image_policy jsonb NOT NULL CHECK (jsonb_typeof(image_policy) = 'object'),
    streaming boolean NOT NULL,
    reasoning boolean NOT NULL CHECK (NOT reasoning),
    timeout text NOT NULL CHECK (length(timeout) > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id,binding_id,revision),
    CONSTRAINT model_binding_source_owner CHECK
        ((source_kind = 'TENANT' AND source_id = tenant_id)
         OR (source_kind = 'AGENT' AND agent_id IS NOT NULL AND source_id = agent_id))
);
ALTER TABLE agent_runtime_model_bindings ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_runtime_model_bindings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON agent_runtime_model_bindings
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
CREATE FUNCTION agent_runtime_model_binding_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'model_binding_immutable';
END;
$$;
CREATE TRIGGER model_binding_immutable BEFORE UPDATE OR DELETE ON agent_runtime_model_bindings
    FOR EACH ROW EXECUTE FUNCTION agent_runtime_model_binding_immutable();
CREATE FUNCTION agent_runtime_model_binding_revision() RETURNS trigger LANGUAGE plpgsql AS $$
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
CREATE TRIGGER model_binding_revision BEFORE INSERT ON agent_runtime_model_bindings
    FOR EACH ROW EXECUTE FUNCTION agent_runtime_model_binding_revision();
COMMENT ON TABLE agent_runtime_model_bindings IS '宿主原配置及受信策略的不可变版本；resolution 与调用共享完整 exact binding';
COMMENT ON COLUMN agent_runtime_model_bindings.tenant_id IS '模型绑定所属租户，独立连接使用 transaction-local RLS';
COMMENT ON COLUMN agent_runtime_model_bindings.binding_id IS '受信宿主给出的模型绑定身份';
COMMENT ON COLUMN agent_runtime_model_bindings.revision IS '同租户同绑定严格递增版本，旧版本不得修改';
COMMENT ON COLUMN agent_runtime_model_bindings.agent_id IS '原请求绑定的 Agent，配置不完整时整组读取租户配置';
COMMENT ON COLUMN agent_runtime_model_bindings.source_kind IS '实际使用的原配置表：TENANT 或 AGENT';
COMMENT ON COLUMN agent_runtime_model_bindings.source_id IS '实际原配置行身份；与来源种类和租户或 Agent 一致';
COMMENT ON COLUMN agent_runtime_model_bindings.content_digest IS '完整来源、配置密文、绑定身份及策略规范值的 SHA256';
COMMENT ON COLUMN agent_runtime_model_bindings.endpoint IS '冻结的 openai_compatible 服务地址，仅 transport 内部可见';
COMMENT ON COLUMN agent_runtime_model_bindings.model_id IS '冻结的有效模型标识，不由请求替换';
COMMENT ON COLUMN agent_runtime_model_bindings.encrypted_credential IS '从原配置冻结的 AES-GCM 密文，不存明文';
COMMENT ON COLUMN agent_runtime_model_bindings.strategy_id IS '受信宿主注册的模型策略身份';
COMMENT ON COLUMN agent_runtime_model_bindings.strategy_version IS '受信宿主注册的策略版本';
COMMENT ON COLUMN agent_runtime_model_bindings.response_formats IS '按枚举名称排序的响应格式集合，至少包含 TEXT';
COMMENT ON COLUMN agent_runtime_model_bindings.streaming IS '受信宿主冻结的流式协议能力';
COMMENT ON COLUMN agent_runtime_model_bindings.reasoning IS '当前统一 wire 尚未支持 reasoning，固定为 false';
COMMENT ON COLUMN agent_runtime_model_bindings.timeout IS '严格正 JDK Duration 规范值，由发布入口验证';
COMMENT ON COLUMN agent_runtime_model_bindings.created_at IS '不可变版本首次发布的数据库时间';
COMMENT ON CONSTRAINT model_binding_source_owner ON agent_runtime_model_bindings IS '冻结来源不可跨租户或替换原 Agent';
COMMENT ON FUNCTION agent_runtime_model_binding_immutable() IS '拒绝覆写和删除历史模型配置绑定';
COMMENT ON FUNCTION agent_runtime_model_binding_revision() IS '同绑定发布先取得一致事务锁，再要求下一个严格递增版本';
COMMENT ON INDEX agent_runtime_model_bindings_pkey IS '完整租户、绑定、版本唯一定位及历史版本读取索引';

COMMENT ON COLUMN public.agent_runtime_model_bindings.image_policy IS '冻结具体模型的图片输入能力、资源上限与版本化图片计量规则，不推测或回填历史能力';
