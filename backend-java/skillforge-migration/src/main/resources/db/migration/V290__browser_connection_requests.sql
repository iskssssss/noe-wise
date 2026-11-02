-- 连接等待属于浏览器宿主；Runtime Action/ToolInputWait 继续拥有执行与恢复事实。
CREATE TABLE public.nw_browser_connection_requests (
    tenant_id bigint NOT NULL REFERENCES public.nw_tenants(id) ON DELETE RESTRICT,
    request_id varchar(36) NOT NULL,
    end_user_id bigint NOT NULL REFERENCES public.nw_end_users(id) ON DELETE RESTRICT,
    session_id varchar(128) NOT NULL,
    run_id varchar(128) NOT NULL,
    invocation_id varchar(256) NOT NULL,
    reason varchar(32) NOT NULL CHECK(reason IN ('NO_SELECTION','OFFLINE','AUTHORIZATION_REQUIRED')),
    status varchar(16) NOT NULL CHECK(status IN ('WAITING','READY','RESUMING','RESUMED','CANCELLING','CANCELLED','FAILED')),
    device_id varchar(128),
    browser_id varchar(128),
    make_default boolean NOT NULL DEFAULT false,
    pair_dispatched boolean NOT NULL DEFAULT false,
    error varchar(128),
    wait_snapshot jsonb,
    revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id,request_id),
    UNIQUE(tenant_id,run_id,invocation_id)
);
COMMENT ON TABLE public.nw_browser_connection_requests IS '个人浏览器按需连接、准确设备选择和原工具等待的自动恢复进度；不保存授权凭据';
COMMENT ON COLUMN public.nw_browser_connection_requests.tenant_id IS '可信来源机构，强制租户 RLS';
COMMENT ON COLUMN public.nw_browser_connection_requests.request_id IS '同一 Run/invocation 稳定连接请求 UUID';
COMMENT ON COLUMN public.nw_browser_connection_requests.end_user_id IS '原 Run 所属个人身份';
COMMENT ON COLUMN public.nw_browser_connection_requests.session_id IS '来源逻辑 Session，原等待与恢复仍由 Runtime 管理';
COMMENT ON COLUMN public.nw_browser_connection_requests.run_id IS '来源 Runtime Run';
COMMENT ON COLUMN public.nw_browser_connection_requests.invocation_id IS '原工具调用身份，恢复保持不变';
COMMENT ON COLUMN public.nw_browser_connection_requests.reason IS '首次等待原因，不作为当前设备授权判断';
COMMENT ON COLUMN public.nw_browser_connection_requests.status IS '连接等待、目标冻结、恢复提交或终结状态，不替代执行回执';
COMMENT ON COLUMN public.nw_browser_connection_requests.device_id IS '明确选择或按配对凭据摘要关联的准确设备';
COMMENT ON COLUMN public.nw_browser_connection_requests.browser_id IS '实际启动前冻结的远程浏览器身份';
COMMENT ON COLUMN public.nw_browser_connection_requests.make_default IS '用户是否明确要求保存为默认设备';
COMMENT ON COLUMN public.nw_browser_connection_requests.pair_dispatched IS '已派发配对控制，防止未知结果被重复签发';
COMMENT ON COLUMN public.nw_browser_connection_requests.error IS '受控公开错误码，无异常原文或凭据';
COMMENT ON COLUMN public.nw_browser_connection_requests.wait_snapshot IS '首次观察的准确 Action/ToolInputWait 身份快照，不含输入正文或配对凭据';
COMMENT ON COLUMN public.nw_browser_connection_requests.revision IS '目标选择与生命周期推进的 CAS 版本';
COMMENT ON COLUMN public.nw_browser_connection_requests.created_at IS '首次创建及稳定恢复输入时间';
COMMENT ON COLUMN public.nw_browser_connection_requests.updated_at IS '最近状态推进时间';
CREATE INDEX skillforge_browser_connection_requests_pending_idx ON public.nw_browser_connection_requests(tenant_id,request_id)
    WHERE status IN ('WAITING','READY','RESUMING','CANCELLING');
COMMENT ON INDEX public.skillforge_browser_connection_requests_pending_idx IS '有界扫描尚未完成的连接恢复';
ALTER TABLE public.nw_browser_connection_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_browser_connection_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_browser_connection_requests_tenant_isolation ON public.nw_browser_connection_requests
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint);
