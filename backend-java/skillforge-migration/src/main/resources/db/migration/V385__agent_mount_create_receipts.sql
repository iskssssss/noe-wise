CREATE TABLE public.nw_agent_mount_create_receipts (
    request_id uuid NOT NULL,
    tenant_id bigint NOT NULL,
    actor_ref varchar(128) NOT NULL,
    agent_id bigint NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    mount_id bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT agent_mount_create_receipts_pk PRIMARY KEY (request_id),
    CONSTRAINT agent_mount_create_receipts_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT agent_mount_create_receipts_agent_positive CHECK (agent_id > 0),
    CONSTRAINT agent_mount_create_receipts_mount_positive CHECK (mount_id > 0)
);
ALTER TABLE public.nw_agent_mount_create_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_agent_mount_create_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_mount_create_receipts_tenant ON public.nw_agent_mount_create_receipts
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT, UPDATE ON public.nw_agent_mount_create_receipts TO skillforge;
COMMENT ON TABLE public.nw_agent_mount_create_receipts IS '机构 Agent 挂载创建原请求回执，与挂载同事务提交；删除挂载不删除原成功事实，不设自动过期';
COMMENT ON COLUMN public.nw_agent_mount_create_receipts.request_id IS '客户端原创建请求身份，同身份同内容只创建一次';
COMMENT ON COLUMN public.nw_agent_mount_create_receipts.tenant_id IS '经认证的机构身份，受强制 RLS 隔离';
COMMENT ON COLUMN public.nw_agent_mount_create_receipts.actor_ref IS '原创建机构成员身份，仅该成员可核对原回执';
COMMENT ON COLUMN public.nw_agent_mount_create_receipts.agent_id IS '原目标 Agent 身份，与挂载独立保留，创建时核验当前资源权限';
COMMENT ON COLUMN public.nw_agent_mount_create_receipts.request_fingerprint IS '类型化挂载请求按对象键排序编码的 SHA-256，禁止同请求身份换内容';
COMMENT ON COLUMN public.nw_agent_mount_create_receipts.mount_id IS '原创建挂载身份；NULL 仅为尚未完成的当前事务占位，不提交半成品';
COMMENT ON COLUMN public.nw_agent_mount_create_receipts.created_at IS '原请求事务记录时间';
COMMENT ON CONSTRAINT agent_mount_create_receipts_pk ON public.nw_agent_mount_create_receipts IS '原请求身份全局唯一，跨机构冲突不暴露原回执';
COMMENT ON CONSTRAINT agent_mount_create_receipts_fingerprint ON public.nw_agent_mount_create_receipts IS '摘要必须为 64 位小写十六进制';
COMMENT ON CONSTRAINT agent_mount_create_receipts_agent_positive ON public.nw_agent_mount_create_receipts IS 'Agent 雪花身份必须为正数';
COMMENT ON CONSTRAINT agent_mount_create_receipts_mount_positive ON public.nw_agent_mount_create_receipts IS '已完成挂载雪花身份必须为正数';
