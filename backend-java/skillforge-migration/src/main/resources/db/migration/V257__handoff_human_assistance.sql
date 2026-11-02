-- 人工工单命令修订及持久受理；Runtime 会话正文唯一来源为 SessionLog。
ALTER TABLE nw_human_handoff_tickets ADD COLUMN revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0);
ALTER TABLE nw_human_handoff_tickets ADD CONSTRAINT uq_handoff_tenant_ticket UNIQUE(tenant_id,ticket_id);
ALTER TABLE nw_tenant_members ADD CONSTRAINT uq_handoff_member_tenant_id UNIQUE(tenant_id,id);
CREATE TABLE nw_handoff_reply_requests (
 tenant_id bigint NOT NULL REFERENCES nw_tenants(id), command_id varchar(200) NOT NULL,
 ticket_id varchar(64) NOT NULL, session_id varchar(64) NOT NULL, end_user_id bigint NOT NULL,
 operator_id bigint NOT NULL, operator_name varchar(120) NOT NULL,
 content text NOT NULL CHECK (length(content) BETWEEN 1 AND 8192),
 request_fingerprint char(64) NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'accepted' CHECK(status IN ('accepted','committed','rejected')),
 source_event_id varchar(200), source_sequence bigint, error_code varchar(100),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,command_id),
 FOREIGN KEY(tenant_id,ticket_id) REFERENCES nw_human_handoff_tickets(tenant_id,ticket_id) ON DELETE CASCADE,
 FOREIGN KEY(tenant_id,operator_id) REFERENCES nw_tenant_members(tenant_id,id),
 CHECK ((status='committed') = (source_event_id IS NOT NULL AND source_sequence IS NOT NULL)),
 CHECK (source_sequence IS NULL OR source_sequence > 0)
);
CREATE INDEX handoff_reply_pending ON nw_handoff_reply_requests(tenant_id,status,created_at);
ALTER TABLE nw_handoff_reply_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_handoff_reply_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY handoff_reply_tenant ON nw_handoff_reply_requests USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::bigint)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::bigint);
COMMENT ON COLUMN nw_human_handoff_tickets.revision IS '工单命令 CAS 修订，与会话及执行修订独立';
COMMENT ON TABLE nw_handoff_reply_requests IS '人工回复持久受理与恢复请求；SessionLog 为会话事实来源';
COMMENT ON COLUMN nw_handoff_reply_requests.tenant_id IS '可信工单所属租户';
COMMENT ON COLUMN nw_handoff_reply_requests.command_id IS '首次提交固定命令 ID，响应未知时沿用';
COMMENT ON COLUMN nw_handoff_reply_requests.ticket_id IS '同租户人工工单 ID';
COMMENT ON COLUMN nw_handoff_reply_requests.session_id IS '受理时冻结的原产品会话 ID';
COMMENT ON COLUMN nw_handoff_reply_requests.end_user_id IS '从原 Session 取得的终端用户身份';
COMMENT ON COLUMN nw_handoff_reply_requests.operator_id IS '受认证同租户坐席 ID';
COMMENT ON COLUMN nw_handoff_reply_requests.operator_name IS '受理时冻结的坐席公开名称';
COMMENT ON COLUMN nw_handoff_reply_requests.content IS '受理正文，Runtime 消费同一命令的 SessionLog 事实';
COMMENT ON COLUMN nw_handoff_reply_requests.request_fingerprint IS '租户、坐席、工单、原修订、命令与正文的 SHA-256';
COMMENT ON COLUMN nw_handoff_reply_requests.status IS 'accepted待提交、committed已提交、rejected确定性拒绝';
COMMENT ON COLUMN nw_handoff_reply_requests.source_event_id IS '已提交唯一 SessionLog eventId';
COMMENT ON COLUMN nw_handoff_reply_requests.source_sequence IS '真实 SessionLog sequence，不是 Outbox sequence';
COMMENT ON COLUMN nw_handoff_reply_requests.error_code IS '确定性拒绝的安全原因码';
COMMENT ON COLUMN nw_handoff_reply_requests.created_at IS '首次受理时间';
COMMENT ON COLUMN nw_handoff_reply_requests.updated_at IS '最后受理状态更新时间';
