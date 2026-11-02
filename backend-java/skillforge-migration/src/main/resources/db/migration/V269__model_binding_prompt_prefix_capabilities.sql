-- 保留旧冻结绑定原字节；能力扩展只由新发布格式4明确提供，不回填默认能力或摘要。
ALTER TABLE public.agent_runtime_model_bindings
    ADD COLUMN system_updates_in_history text,
    ADD COLUMN tool_update_mode text,
    ADD COLUMN retain_tools_with_tool_choice_none text;

ALTER TABLE public.agent_runtime_model_bindings
    DROP CONSTRAINT agent_runtime_model_bindings_binding_format_version_check,
    DROP CONSTRAINT model_binding_provider_profile_complete,
    ALTER COLUMN binding_format_version SET DEFAULT 4;

-- 原有六列的 NOT NULL 继续保留；两种物理存量都必须保持完整供应商来源。
ALTER TABLE public.agent_runtime_model_bindings
    ADD CONSTRAINT model_binding_provider_profile_complete CHECK (
        length(provider_id) > 0
        AND length(service_id) > 0
        AND length(profile_version) > 0
        AND length(protocol) > 0
        AND length(authentication_scheme) > 0
        AND length(dialect_version) > 0
    );

-- 显式 IS NOT NULL 防止 SQL CHECK 的 UNKNOWN 放过缺失值；格式3仅保留空扩展字段。
ALTER TABLE public.agent_runtime_model_bindings
    ADD CONSTRAINT model_binding_format_structure CHECK (
        (
            binding_format_version = 3
            AND system_updates_in_history IS NULL
            AND tool_update_mode IS NULL
            AND retain_tools_with_tool_choice_none IS NULL
        )
        OR
        (
            binding_format_version = 4
            AND system_updates_in_history IS NOT NULL
            AND system_updates_in_history IN ('SUPPORTED', 'UNSUPPORTED', 'UNVERIFIED')
            AND tool_update_mode IS NOT NULL
            AND tool_update_mode IN ('NONE', 'ADDITION_ONLY', 'ADD_REMOVE', 'UNVERIFIED')
            AND retain_tools_with_tool_choice_none IS NOT NULL
            AND retain_tools_with_tool_choice_none IN ('SUPPORTED', 'UNSUPPORTED', 'UNVERIFIED')
        )
    );

-- 沿用历史来源的写入门禁模式：不扫描旧行，但所有新 INSERT/UPDATE 只接受格式4。
-- 历史行仍受原 UPDATE/DELETE 不可变触发器保护，Runtime 不增加旧格式恢复能力。
ALTER TABLE public.agent_runtime_model_bindings
    ADD CONSTRAINT model_binding_current_format CHECK (binding_format_version = 4) NOT VALID;

COMMENT ON COLUMN public.agent_runtime_model_bindings.binding_format_version IS
    '新发布绑定仅格式4；格式3仅保留原始历史字节，Runtime不恢复';
COMMENT ON COLUMN public.agent_runtime_model_bindings.system_updates_in_history IS
    '格式4冻结历史内系统更新能力；历史格式3保留NULL，不回填';
COMMENT ON COLUMN public.agent_runtime_model_bindings.tool_update_mode IS
    '格式4冻结工具目录更新协议；历史格式3保留NULL，不回填';
COMMENT ON COLUMN public.agent_runtime_model_bindings.retain_tools_with_tool_choice_none IS
    '格式4冻结保留工具声明并以NONE禁用调用的能力；历史格式3保留NULL，不回填';
COMMENT ON COLUMN public.agent_runtime_model_bindings.provider_id IS '冻结的SDK LLM品牌标识；完整来源必填';
COMMENT ON COLUMN public.agent_runtime_model_bindings.service_id IS '冻结的SDK LLM实际接入服务标识；完整来源必填';
COMMENT ON COLUMN public.agent_runtime_model_bindings.profile_version IS '冻结的SDK ServiceProfile版本；完整来源必填';
COMMENT ON COLUMN public.agent_runtime_model_bindings.protocol IS '冻结的SDK模型协议标识；完整来源必填';
COMMENT ON COLUMN public.agent_runtime_model_bindings.authentication_scheme IS '冻结的SDK认证方案标识；完整来源必填';
COMMENT ON COLUMN public.agent_runtime_model_bindings.dialect_version IS '冻结的SDK协议方言版本；完整来源必填';
COMMENT ON CONSTRAINT model_binding_provider_profile_complete ON public.agent_runtime_model_bindings IS
    '完整供应商profile来源事实；能力扩展格式由独立约束校验';
COMMENT ON CONSTRAINT model_binding_format_structure ON public.agent_runtime_model_bindings IS
    '历史格式3仅保留空扩展字段；格式4必须完整冻结合法前缀能力';
COMMENT ON CONSTRAINT model_binding_current_format ON public.agent_runtime_model_bindings IS
    '所有新写入仅接受格式4；已有不可变格式3仅作历史封存';
