CREATE TABLE public.nw_system_agent_create_receipts (
    request_id uuid PRIMARY KEY,
    actor_ref varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    agent_id bigint,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.nw_system_agent_create_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_system_agent_create_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY system_agent_create_receipts_admin ON public.nw_system_agent_create_receipts
    USING (current_setting('app.platform_admin', true) = 'true')
    WITH CHECK (current_setting('app.platform_admin', true) = 'true');
GRANT SELECT, INSERT, UPDATE ON public.nw_system_agent_create_receipts TO skillforge;
COMMENT ON TABLE public.nw_system_agent_create_receipts IS '平台系统智能体创建原请求身份及结果，与草稿同事务提交';
