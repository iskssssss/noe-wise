-- 直属子机构容量沿用Foundation套餐/机构覆盖权威，不新增商业表、累计计数或默认套餐数量。
ALTER TABLE public.nw_billing_plans ADD COLUMN quota_max_sub_institutions integer;
ALTER TABLE public.nw_billing_plans ADD CONSTRAINT skillforge_billing_plans_quota_max_sub_institutions_check CHECK(quota_max_sub_institutions >= 0);
COMMENT ON COLUMN public.nw_billing_plans.quota_max_sub_institutions IS '当前直属子机构数上限，含停用子机构；NULL不限，0禁止新增，不累计后代';
COMMENT ON CONSTRAINT skillforge_billing_plans_quota_max_sub_institutions_check ON public.nw_billing_plans IS '子机构数量额度只能为NULL或非负整数';

ALTER TABLE public.nw_tenants ADD COLUMN override_quota_max_sub_institutions integer;
ALTER TABLE public.nw_tenants ADD CONSTRAINT skillforge_tenants_override_quota_max_sub_institutions_check CHECK(override_quota_max_sub_institutions >= 0);
COMMENT ON COLUMN public.nw_tenants.override_quota_max_sub_institutions IS '当前直属子机构数量覆盖；NULL继承套餐，0禁止新增，解除关系释放容量';
COMMENT ON CONSTRAINT skillforge_tenants_override_quota_max_sub_institutions_check ON public.nw_tenants IS '子机构数量覆盖只能为NULL或非负整数';
