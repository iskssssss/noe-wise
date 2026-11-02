-- 浏览器执行事实归独立服务持久卷；机构表保存可信调用关联与用户可见回执。
CREATE TABLE public.nw_browser_call_receipts (
    tenant_id bigint NOT NULL REFERENCES public.nw_tenants(id) ON DELETE RESTRICT,
    end_user_id bigint NOT NULL REFERENCES public.nw_end_users(id) ON DELETE RESTRICT,
    invocation_id varchar(256) NOT NULL,
    task_id varchar(128),
    session_id varchar(128) NOT NULL,
    run_id varchar(128) NOT NULL,
    operation varchar(64) NOT NULL,
    status varchar(32) NOT NULL CHECK(status IN ('prepared','starting','bound','active','submitted','succeeded','unknown','failed','rejected','cancelling','cancelled','cleanup_failed')),
    public_receipt jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id,end_user_id,invocation_id)
);
COMMENT ON TABLE public.nw_browser_call_receipts IS '个人浏览器调用的宿主关联和公开回执；真实提交事实由 Browser service 持久卷拥有';
COMMENT ON COLUMN public.nw_browser_call_receipts.tenant_id IS '认证后的机构身份';
COMMENT ON COLUMN public.nw_browser_call_receipts.end_user_id IS '可信 Runtime 或已登记个人身份';
COMMENT ON COLUMN public.nw_browser_call_receipts.invocation_id IS 'Runtime 稳定工具调用身份';
COMMENT ON COLUMN public.nw_browser_call_receipts.task_id IS '个人浏览器任务绑定标识';
COMMENT ON COLUMN public.nw_browser_call_receipts.session_id IS '调用来源 Session，使用已有会话身份';
COMMENT ON COLUMN public.nw_browser_call_receipts.run_id IS '调用来源 Run';
COMMENT ON COLUMN public.nw_browser_call_receipts.operation IS '固定上游封闭操作名';
COMMENT ON COLUMN public.nw_browser_call_receipts.status IS '浏览器调用提交、已知完成或未知结果状态，不替代 Run 状态';
COMMENT ON COLUMN public.nw_browser_call_receipts.public_receipt IS '动态官方结果的公开投影，不含配对凭据或内部路径';
COMMENT ON COLUMN public.nw_browser_call_receipts.created_at IS '首次受理时间';
COMMENT ON COLUMN public.nw_browser_call_receipts.updated_at IS '最近回执收敛时间';
CREATE INDEX skillforge_browser_call_receipts_session_idx ON public.nw_browser_call_receipts(tenant_id,end_user_id,session_id,created_at);
COMMENT ON INDEX public.skillforge_browser_call_receipts_session_idx IS '按个人与来源会话查询浏览器调用';
ALTER TABLE public.nw_browser_call_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_browser_call_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_browser_call_receipts_tenant_isolation ON public.nw_browser_call_receipts
  USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint)
  WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint);
