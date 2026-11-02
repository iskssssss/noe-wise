-- V57: 用量来源与分项 token 语义。
-- UNKNOWN 表示供应商未返回且当前无法可靠估算，不得表现为精确上报值。

ALTER TABLE public.nw_usage_events
    ADD COLUMN usage_source character varying(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN cached_input_tokens integer,
    ADD COLUMN cache_write_input_tokens integer,
    ADD COLUMN reasoning_output_tokens integer,
    ADD COLUMN estimator_version character varying(128),
    ADD CONSTRAINT ck_skillforge_usage_events_usage_source
        CHECK (usage_source IN ('PROVIDER_REPORTED', 'ESTIMATED', 'UNKNOWN')),
    ADD CONSTRAINT ck_skillforge_usage_events_estimator_version
        CHECK (usage_source <> 'ESTIMATED' OR estimator_version IS NOT NULL);

COMMENT ON COLUMN public.nw_usage_events.usage_source IS 'token 计量来源：PROVIDER_REPORTED（供应商响应）、ESTIMATED（平台估算，须带 estimator_version）或 UNKNOWN；未知不以零伪装精确值';
COMMENT ON COLUMN public.nw_usage_events.cached_input_tokens IS '输入 token 中命中供应商缓存的子集，已包含在 prompt_tokens 中，聚合不得重复相加';
COMMENT ON COLUMN public.nw_usage_events.cache_write_input_tokens IS '输入 token 中写入供应商缓存的子集，已包含在 prompt_tokens 中，聚合不得重复相加';
COMMENT ON COLUMN public.nw_usage_events.reasoning_output_tokens IS '输出 token 中模型推理的子集，已包含在 completion_tokens 中，聚合不得重复相加';
COMMENT ON COLUMN public.nw_usage_events.estimator_version IS 'usage_source=ESTIMATED 时使用的估算器与规则版本；其他来源为空';
