CREATE TABLE public.nw_platform_billing_scans (
    actor_id bigint NOT NULL REFERENCES public.nw_platform_members(id) ON DELETE RESTRICT,
    operation_id varchar(36) NOT NULL,
    scanned integer NOT NULL DEFAULT 0 CHECK (scanned >= 0),
    warning integer NOT NULL DEFAULT 0 CHECK (warning >= 0),
    active integer NOT NULL DEFAULT 0 CHECK (active >= 0),
    completed_at timestamptz,
    PRIMARY KEY (actor_id, operation_id)
);
ALTER TABLE public.nw_platform_billing_scans ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_platform_billing_scans FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_platform_billing_scans_platform_only ON public.nw_platform_billing_scans
    USING (current_setting('app.platform_admin', true) = 'true')
    WITH CHECK (current_setting('app.platform_admin', true) = 'true');
COMMENT ON TABLE public.nw_platform_billing_scans IS '平台手动扫描回执与幂等事实；领取、状态变更与完成同事务，禁止删除后复用旧键';
COMMENT ON COLUMN public.nw_platform_billing_scans.actor_id IS '平台认证操作者，禁止由客户端指定';
COMMENT ON COLUMN public.nw_platform_billing_scans.operation_id IS '客户端生成的扫描 UUID；同一操作者重试复用原值';
COMMENT ON COLUMN public.nw_platform_billing_scans.scanned IS '扫描 active 或 warning 状态机构的数量';
COMMENT ON COLUMN public.nw_platform_billing_scans.warning IS '本次实际转为欠费预警的数量';
COMMENT ON COLUMN public.nw_platform_billing_scans.active IS '本次实际恢复正常的数量';
COMMENT ON COLUMN public.nw_platform_billing_scans.completed_at IS '扫描完成时间；事务内领取时暂为空，完成后才提交';
