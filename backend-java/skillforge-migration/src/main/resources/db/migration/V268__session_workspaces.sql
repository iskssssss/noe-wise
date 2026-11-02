CREATE TABLE public.nw_chat_session_workspaces (
    workspace_id char(64) PRIMARY KEY,
    tenant_id bigint NOT NULL REFERENCES nw_tenants(id),
    session_id text NOT NULL,
    product_session_id text NOT NULL,
    end_user_id bigint NOT NULL,
    cleanup_state text NOT NULL DEFAULT 'active' CHECK (cleanup_state IN ('active','pending','complete')),
    initial_operation_id char(64) NOT NULL CHECK(initial_operation_id ~ '^[0-9a-f]{64}$'),
    initialized boolean NOT NULL DEFAULT FALSE,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,session_id), UNIQUE(tenant_id,workspace_id),
    CHECK(workspace_id ~ '^[0-9a-f]{64}$'), CHECK(end_user_id>0)
);
CREATE INDEX idx_skillforge_chat_session_workspaces_product ON public.nw_chat_session_workspaces(tenant_id,product_session_id);
CREATE INDEX idx_skillforge_chat_session_workspaces_cleanup ON public.nw_chat_session_workspaces(tenant_id,cleanup_state,updated_at);
ALTER TABLE public.nw_chat_session_workspaces ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_chat_session_workspaces FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_chat_session_workspaces_tenant ON public.nw_chat_session_workspaces
    USING(tenant_id::text=COALESCE(current_setting('app.tenant_id',true),''))
    WITH CHECK(tenant_id::text=COALESCE(current_setting('app.tenant_id',true),''));
COMMENT ON TABLE public.nw_chat_session_workspaces IS '会话工作区宿主归属与持久清理意图，不复制Sandbox文件和备份状态';
COMMENT ON COLUMN public.nw_chat_session_workspaces.workspace_id IS '由可信tenant和Runtime Session生成的SHA-256身份';
COMMENT ON COLUMN public.nw_chat_session_workspaces.tenant_id IS '所属租户及RLS隔离身份';
COMMENT ON COLUMN public.nw_chat_session_workspaces.session_id IS '拥有独立文件目录的Runtime Session，包括委派子Session';
COMMENT ON COLUMN public.nw_chat_session_workspaces.product_session_id IS '用于展示与级联清理的产品会话；删除后仍保留清理墓碑，因此不级联外键';
COMMENT ON COLUMN public.nw_chat_session_workspaces.end_user_id IS '可信Run冻结的最终用户身份';
COMMENT ON COLUMN public.nw_chat_session_workspaces.cleanup_state IS 'active允许访问，pending等待Sandbox静止与清理，complete保留删除墓碑';
COMMENT ON COLUMN public.nw_chat_session_workspaces.initial_operation_id IS '首次可信调用的初始化资格；未确认时只有同调用能重试，不允许新调用空建';
COMMENT ON COLUMN public.nw_chat_session_workspaces.initialized IS 'Sandbox明确初始化成功后单向确认；后续本地状态缺失必须拒绝执行，不重新空建';
COMMENT ON COLUMN public.nw_chat_session_workspaces.created_at IS '归属记录创建时间';
COMMENT ON COLUMN public.nw_chat_session_workspaces.updated_at IS '清理意图最近变化时间';

CREATE TABLE public.nw_chat_session_workspace_imports (
    tenant_id bigint NOT NULL,
    workspace_id char(64) NOT NULL,
    operation_id char(64) NOT NULL,
    evidence jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,workspace_id,operation_id),
    FOREIGN KEY(tenant_id,workspace_id) REFERENCES public.nw_chat_session_workspaces(tenant_id,workspace_id) ON DELETE CASCADE,
    CHECK(operation_id ~ '^[0-9a-f]{64}$')
);
ALTER TABLE public.nw_chat_session_workspace_imports ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_chat_session_workspace_imports FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_chat_session_workspace_imports_tenant ON public.nw_chat_session_workspace_imports
    USING(tenant_id::text=COALESCE(current_setting('app.tenant_id',true),''))
    WITH CHECK(tenant_id::text=COALESCE(current_setting('app.tenant_id',true),''));
COMMENT ON TABLE public.nw_chat_session_workspace_imports IS '工作区显式文件导入的来源证据，幂等操作不重复登记';
COMMENT ON COLUMN public.nw_chat_session_workspace_imports.tenant_id IS '来源证据所属租户';
COMMENT ON COLUMN public.nw_chat_session_workspace_imports.workspace_id IS '接收方独立工作区';
COMMENT ON COLUMN public.nw_chat_session_workspace_imports.operation_id IS '可信调用执行身份摘要';
COMMENT ON COLUMN public.nw_chat_session_workspace_imports.evidence IS '导入执行回执、目标路径、产物和附件清单摘要，无文件正文';
COMMENT ON COLUMN public.nw_chat_session_workspace_imports.created_at IS '来源证据登记时间';
