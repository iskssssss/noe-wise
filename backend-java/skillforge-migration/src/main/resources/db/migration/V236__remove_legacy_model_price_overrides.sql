-- 移除已淘汰的租户级模型单价覆盖；当前定价仅以平台销售价为准。
DROP TABLE IF EXISTS public.nw_model_price_overrides;
