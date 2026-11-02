-- 已执行的历史迁移保持原校验值；仅新发布的绑定使用宿主授权来源。
ALTER TABLE agent_runtime_model_bindings
    DROP CONSTRAINT agent_runtime_model_bindings_source_kind_check,
    DROP CONSTRAINT model_binding_source_owner;

-- 历史绑定不可改写，保留其原始来源证据；新写入强制满足 HOST 契约。
ALTER TABLE agent_runtime_model_bindings
    ADD CONSTRAINT model_binding_host_source CHECK (source_kind = 'HOST') NOT VALID;

COMMENT ON COLUMN agent_runtime_model_bindings.source_kind IS
    '新发布绑定使用受信宿主来源 HOST；历史绑定保留原来源证据';
COMMENT ON COLUMN agent_runtime_model_bindings.source_id IS
    '宿主已授权的不可变配置版本引用，参与冻结摘要';
COMMENT ON CONSTRAINT model_binding_host_source ON agent_runtime_model_bindings IS
    '新写入仅接受 HOST，历史不可变绑定不重写';
