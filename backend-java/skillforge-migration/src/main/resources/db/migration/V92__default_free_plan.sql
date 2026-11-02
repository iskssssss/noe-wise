-- 空库必须具备机构开通服务约定的默认 free 套餐。
-- 预发布阶段不为缺失的历史套餐推断配额，仅建立最小可用默认项。
INSERT INTO public.nw_billing_plans (
    id,
    plan_code,
    display_name,
    quota_api_calls_per_month,
    quota_tokens_included,
    quota_max_knowledge_bases,
    quota_max_members,
    quota_max_channels,
    rate_limit_per_minute,
    slo_availability_target,
    features,
    is_active
) VALUES (
    1,
    'free',
    '免费版',
    NULL,
    0,
    NULL,
    NULL,
    NULL,
    120,
    0.9900,
    '{}'::jsonb,
    true
);
