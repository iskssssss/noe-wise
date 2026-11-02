-- V70: 用量金额高精度与写时价格事实快照。
-- cents 列用于当前聚合展示，精确列保存写时计价事实。
ALTER TABLE public.nw_usage_events
    ADD COLUMN input_amount numeric(24,12),
    ADD COLUMN output_amount numeric(24,12),
    ADD COLUMN total_amount numeric(24,12),
    ADD COLUMN exchange_rate_to_cny numeric(24,12),
    ADD COLUMN pricing_snapshot_version varchar(64);

COMMENT ON COLUMN public.nw_usage_events.input_amount IS '输入精确金额（分），保留小数以支持高精度单价';
COMMENT ON COLUMN public.nw_usage_events.output_amount IS '输出精确金额（分）';
COMMENT ON COLUMN public.nw_usage_events.total_amount IS '合计精确金额（分）';
COMMENT ON COLUMN public.nw_usage_events.exchange_rate_to_cny IS '写时汇率快照（相对 CNY），不可变';
COMMENT ON COLUMN public.nw_usage_events.pricing_snapshot_version IS '写时价格目录/汇率快照版本';
