-- 账单统一按模型用量的原计价事实查询；删除已下线销售定价和商业出账数据，不保留归档。
-- 明确使用RESTRICT，额外外部依赖必须阻止迁移，不能级联删除模型用量或其他领域对象。
DROP TABLE public.nw_billing_price_audit RESTRICT;
DROP TABLE public.nw_billing_sales_prices RESTRICT;
DROP TABLE public.nw_billing_invoice_usage_claims RESTRICT;
DROP TABLE public.nw_billing_invoices RESTRICT;
DROP FUNCTION public.protect_billing_immutable() RESTRICT;
DROP FUNCTION public.protect_sealed_invoice() RESTRICT;
