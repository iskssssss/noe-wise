-- 当前预发布目标模型；仅用于新建或明确授权重建的数据库。
DO $$ BEGIN EXECUTE format('ALTER DATABASE %I SET timezone TO %L', current_database(), 'Asia/Shanghai'); END $$;
SET TIME ZONE 'Asia/Shanghai';
ALTER TABLE nw_usage_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_billing_invoices ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_usage_events ALTER COLUMN pricing_snapshot_version TYPE text;
ALTER TABLE nw_usage_events ALTER COLUMN currency DROP NOT NULL;
ALTER TABLE nw_usage_events ALTER COLUMN currency DROP DEFAULT;
COMMENT ON COLUMN nw_usage_events.pricing_snapshot_version IS '完整价格与汇率组合版本，不截断哈希及版本标识';
COMMENT ON COLUMN nw_usage_events.currency IS '供应商费用币种，不计费请求根为NULL';
ALTER TABLE nw_tenants DROP COLUMN billing_timezone;
ALTER TABLE nw_usage_events DROP CONSTRAINT ck_skillforge_usage_events_amount_status;
ALTER TABLE nw_usage_events ADD CONSTRAINT ck_skillforge_usage_events_amount_status CHECK(amount_status IN ('priced','unpriced','unresolved','not_applicable'));
ALTER TABLE nw_usage_events ADD CONSTRAINT ck_request_nonbillable CHECK ((event_type='request' AND amount_status='not_applicable') OR (event_type<>'request' AND amount_status<>'not_applicable'));
-- request 不参与金额定价，不能混入缺价格统计。

CREATE VIEW billing_effective_usage WITH (security_invoker=true) AS
SELECT e.*, c.effective_amount, c.effective_currency, c.effective_rate,
       CASE WHEN e.event_type='request' THEN 'not_applicable'
            WHEN c.effective_status IS DISTINCT FROM 'priced' THEN COALESCE(c.effective_status,'unpriced')
            WHEN c.effective_amount IS NULL THEN 'unresolved'
            WHEN c.effective_currency IS DISTINCT FROM 'CNY' AND c.effective_rate IS NULL THEN 'unresolved'
            WHEN COALESCE(a.gaps,0)>0 THEN 'unresolved' ELSE 'priced' END AS cost_status,
       CASE WHEN c.effective_status='priced' AND c.effective_amount IS NOT NULL
              AND (c.effective_currency='CNY' OR c.effective_rate IS NOT NULL)
            THEN c.effective_amount * CASE WHEN c.effective_currency='CNY' THEN 1 ELSE c.effective_rate END + COALESCE(a.delta,0)
            ELSE a.delta END AS confirmed_cost_cents
FROM nw_usage_events e
LEFT JOIN LATERAL (SELECT r.* FROM nw_usage_events r WHERE r.tenant_id=e.tenant_id
    AND r.adjustment_of_event_id=e.id AND r.usage_event_kind='reconciliation'
    ORDER BY r.ingested_at DESC,r.id DESC LIMIT 1) r ON true
CROSS JOIN LATERAL (SELECT CASE WHEN r.id IS NULL THEN e.total_amount ELSE r.total_amount END AS effective_amount,
    CASE WHEN r.id IS NULL THEN e.currency ELSE r.currency END AS effective_currency,
    CASE WHEN r.id IS NULL THEN e.exchange_rate_to_cny ELSE r.exchange_rate_to_cny END AS effective_rate,
    CASE WHEN r.id IS NULL AND e.event_type IN ('llm.chat','llm.embedding') AND e.usage_source IS DISTINCT FROM 'PROVIDER_REPORTED' THEN 'unresolved'
         WHEN r.id IS NULL THEN e.amount_status ELSE r.amount_status END AS effective_status) c
LEFT JOIN LATERAL (SELECT SUM(CASE WHEN x.amount_status='priced' THEN x.total_amount *
    CASE WHEN x.currency='CNY' THEN 1 ELSE x.exchange_rate_to_cny END END) AS delta,
    COUNT(*) FILTER(WHERE x.amount_status IS DISTINCT FROM 'priced' OR x.total_amount IS NULL
        OR (x.currency IS DISTINCT FROM 'CNY' AND x.exchange_rate_to_cny IS NULL)) AS gaps
    FROM nw_usage_events x WHERE x.tenant_id=e.tenant_id AND x.adjustment_of_event_id=e.id
    AND x.usage_event_kind='adjustment') a ON true
WHERE e.usage_event_kind='measured';
COMMENT ON VIEW billing_effective_usage IS '原子用量的有效成本投影；最新对账确认替代原金额，调整仅按增减额追加，金额单位为分';

CREATE TABLE nw_billing_sales_prices (
 id bigint PRIMARY KEY, event_type varchar(64) NOT NULL, provider varchar(128) NOT NULL,
 model varchar(256) NOT NULL, effective_at timestamptz NOT NULL,
 input_per_million numeric(30,12), output_per_million numeric(30,12), per_quantity numeric(30,12),
 currency varchar(3) NOT NULL DEFAULT 'CNY' CHECK(currency='CNY'),
 actor_id text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(input_per_million>=0 AND output_per_million>=0 AND per_quantity>=0),
 UNIQUE(event_type,provider,model,effective_at)
);
COMMENT ON TABLE nw_billing_sales_prices IS '不可变销售价格版本；元/百万 Token 或元/数量，独立于供应商成本';
CREATE TABLE nw_billing_price_audit (
 id bigint PRIMARY KEY, price_id bigint NOT NULL REFERENCES nw_billing_sales_prices(id),
 actor_id text NOT NULL, action text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE nw_billing_price_audit IS '平台价格发布审计；与发布价格同事务追加';

ALTER TABLE nw_billing_invoices ADD COLUMN document_key text;
ALTER TABLE nw_billing_invoices ADD COLUMN original_invoice_id bigint REFERENCES nw_billing_invoices(id);
ALTER TABLE nw_billing_invoices ADD COLUMN snapshot jsonb NOT NULL DEFAULT '{}';
ALTER TABLE nw_billing_invoices ADD COLUMN sealed_at timestamptz;
ALTER TABLE nw_billing_invoices ALTER COLUMN total_amount_cents TYPE bigint;
ALTER TABLE nw_billing_invoices ALTER COLUMN total_amount_cents DROP NOT NULL;
ALTER TABLE nw_billing_invoices ALTER COLUMN total_amount_cents DROP DEFAULT;
ALTER TABLE nw_billing_invoices ALTER COLUMN status SET DEFAULT 'draft';
CREATE UNIQUE INDEX skillforge_billing_invoices_document_key ON nw_billing_invoices(tenant_id,document_key);
COMMENT ON COLUMN nw_billing_invoices.document_key IS '主账单 month:YYYY-MM；补充单及人工调整使用请求幂等键';
COMMENT ON COLUMN nw_billing_invoices.original_invoice_id IS '补充或调整单引用的已封账主账单';
COMMENT ON COLUMN nw_billing_invoices.snapshot IS '版本化账单快照：逐事件计量、销售价格、成本缺口及十进制金额';
COMMENT ON COLUMN nw_billing_invoices.sealed_at IS '北京时间封账时刻（绝对时刻存储）';
CREATE TABLE nw_billing_invoice_usage_claims (
 tenant_id bigint NOT NULL, usage_id bigint NOT NULL REFERENCES nw_usage_events(id),
 invoice_id bigint NOT NULL REFERENCES nw_billing_invoices(id), PRIMARY KEY(tenant_id,usage_id)
);
COMMENT ON TABLE nw_billing_invoice_usage_claims IS '封账用量归属；同一原始计量事实仅归入一个账单';
ALTER TABLE nw_billing_invoice_usage_claims ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_billing_invoice_usage_claims FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_billing_invoice_usage_claims_tenant ON nw_billing_invoice_usage_claims
 USING(tenant_id::text=current_setting('app.tenant_id',true))
 WITH CHECK(tenant_id::text=current_setting('app.tenant_id',true));
CREATE FUNCTION protect_billing_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'sealed billing facts are immutable'; END $$;
CREATE TRIGGER skillforge_billing_sales_prices_immutable BEFORE UPDATE OR DELETE ON nw_billing_sales_prices
 FOR EACH ROW EXECUTE FUNCTION protect_billing_immutable();
CREATE TRIGGER skillforge_billing_price_audit_immutable BEFORE UPDATE OR DELETE ON nw_billing_price_audit
 FOR EACH ROW EXECUTE FUNCTION protect_billing_immutable();
CREATE TRIGGER skillforge_billing_invoice_usage_claims_immutable BEFORE UPDATE OR DELETE ON nw_billing_invoice_usage_claims
 FOR EACH ROW EXECUTE FUNCTION protect_billing_immutable();
CREATE FUNCTION protect_sealed_invoice() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF OLD.status='open' THEN RAISE EXCEPTION 'sealed invoice is immutable'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW; END $$;
CREATE TRIGGER skillforge_billing_invoices_sealed_immutable BEFORE UPDATE OR DELETE ON nw_billing_invoices
 FOR EACH ROW EXECUTE FUNCTION protect_sealed_invoice();

CREATE UNIQUE INDEX skillforge_usage_events_tenant_identity ON nw_usage_events(tenant_id,id);
CREATE UNIQUE INDEX skillforge_billing_invoices_tenant_identity ON nw_billing_invoices(tenant_id,id);
ALTER TABLE nw_billing_invoice_usage_claims ADD CONSTRAINT claims_usage_tenant FOREIGN KEY(tenant_id,usage_id) REFERENCES nw_usage_events(tenant_id,id);
ALTER TABLE nw_billing_invoice_usage_claims ADD CONSTRAINT claims_invoice_tenant FOREIGN KEY(tenant_id,invoice_id) REFERENCES nw_billing_invoices(tenant_id,id);
ALTER TABLE nw_billing_invoices ADD CONSTRAINT invoice_original_tenant FOREIGN KEY(tenant_id,original_invoice_id) REFERENCES nw_billing_invoices(tenant_id,id);
CREATE INDEX skillforge_usage_events_request_root ON nw_usage_events(tenant_id,root_input_id,occurred_at);
CREATE INDEX skillforge_usage_events_adjustment_origin ON nw_usage_events(tenant_id,adjustment_of_event_id,usage_event_kind,ingested_at,id);
COMMENT ON COLUMN nw_billing_sales_prices.id IS '销售价格版本标识';
COMMENT ON COLUMN nw_billing_sales_prices.event_type IS '用量服务类型';
COMMENT ON COLUMN nw_billing_sales_prices.provider IS '服务提供方精确标识';
COMMENT ON COLUMN nw_billing_sales_prices.model IS '模型或服务精确标识';
COMMENT ON COLUMN nw_billing_sales_prices.effective_at IS '价格开始生效的绝对时刻，业务按北京时间解释';
COMMENT ON COLUMN nw_billing_sales_prices.input_per_million IS '输入单价，元每百万Token';
COMMENT ON COLUMN nw_billing_sales_prices.output_per_million IS '输出单价，元每百万Token';
COMMENT ON COLUMN nw_billing_sales_prices.per_quantity IS '数量单价，元每计量单位';
COMMENT ON COLUMN nw_billing_sales_prices.currency IS '应收币种，本期固定CNY';
COMMENT ON COLUMN nw_billing_sales_prices.actor_id IS '发布操作者标识';
COMMENT ON COLUMN nw_billing_sales_prices.created_at IS '发布时间';
COMMENT ON COLUMN nw_billing_price_audit.id IS '审计标识';
COMMENT ON COLUMN nw_billing_price_audit.price_id IS '发布的不可变价格版本';
COMMENT ON COLUMN nw_billing_price_audit.actor_id IS '发布操作者标识';
COMMENT ON COLUMN nw_billing_price_audit.action IS '审计动作编码';
COMMENT ON COLUMN nw_billing_price_audit.created_at IS '审计时间';
COMMENT ON COLUMN nw_billing_invoice_usage_claims.tenant_id IS '所属租户，复合外键防止跨租户归账';
COMMENT ON COLUMN nw_billing_invoice_usage_claims.usage_id IS '原始计量事件，同租户仅允许归账一次';
COMMENT ON COLUMN nw_billing_invoice_usage_claims.invoice_id IS '拥有该用量的已封账账单';

-- baseline 来源于 pg_dump，其 row_security=off 不应留在迁移连接回池后的会话中。
SET row_security = on;
