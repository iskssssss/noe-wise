ALTER TABLE public.nw_chat_sessions
    ADD CONSTRAINT uq_skillforge_chat_sessions_tenant_session UNIQUE (tenant_id, session_id);

CREATE TABLE public.nw_chat_session_attachments (
    id uuid PRIMARY KEY,
    tenant_id bigint NOT NULL,
    session_id varchar(64) NOT NULL,
    name text NOT NULL,
    input_path text NOT NULL,
    size bigint NOT NULL,
    sha256 char(64) NOT NULL,
    content bytea,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CONSTRAINT fk_chat_session_attachment_session
        FOREIGN KEY (tenant_id, session_id)
        REFERENCES public.nw_chat_sessions(tenant_id, session_id) ON DELETE CASCADE,
    CONSTRAINT uq_chat_session_attachment_path UNIQUE (tenant_id, session_id, input_path),
    CONSTRAINT ck_chat_session_attachment_name_nonblank CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_chat_session_attachment_size CHECK (size BETWEEN 0 AND 2097152),
    CONSTRAINT ck_chat_session_attachment_content_size CHECK (
        content IS NULL OR octet_length(content) = size
    ),
    CONSTRAINT ck_chat_session_attachment_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_chat_session_attachment_input_path CHECK (
        input_path ~ ('^attachments/' || id::text || '/input\.(csv|tsv|txt|md|json)$')
    ),
    CONSTRAINT ck_chat_session_attachment_expiry CHECK (
        expires_at > created_at AND expires_at <= created_at + interval '7 days'
    )
);

CREATE INDEX idx_skillforge_chat_session_attachments_session
    ON public.nw_chat_session_attachments(tenant_id, session_id, created_at, id);
CREATE INDEX idx_skillforge_chat_session_attachments_expiry
    ON public.nw_chat_session_attachments(tenant_id, expires_at, id);

ALTER TABLE public.nw_chat_session_attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_chat_session_attachments FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_chat_session_attachments_tenant_isolation ON public.nw_chat_session_attachments
    USING (tenant_id::text = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id::text = COALESCE(current_setting('app.tenant_id', true), ''));

COMMENT ON TABLE public.nw_chat_session_attachments IS '用户上传并绑定到产品会话的短期输入附件，与执行产物分离';
COMMENT ON COLUMN public.nw_chat_session_attachments.id IS '客户端稳定上传 UUID，经服务端校验后不可重新绑定内容';
COMMENT ON COLUMN public.nw_chat_session_attachments.tenant_id IS '附件所属租户';
COMMENT ON COLUMN public.nw_chat_session_attachments.session_id IS '附件绑定的产品会话，不允许跨会话读取';
COMMENT ON COLUMN public.nw_chat_session_attachments.name IS '用户上传时的安全展示文件名';
COMMENT ON COLUMN public.nw_chat_session_attachments.input_path IS '服务端生成的沙箱相对输入路径';
COMMENT ON COLUMN public.nw_chat_session_attachments.size IS '正文 UTF-8 字节数，上限 2 MiB';
COMMENT ON COLUMN public.nw_chat_session_attachments.sha256 IS '正文 SHA-256 小写十六进制摘要';
COMMENT ON COLUMN public.nw_chat_session_attachments.content IS '原子保存的 UTF-8 文件正文；到期后清空但保留 UUID 墓碑';
COMMENT ON COLUMN public.nw_chat_session_attachments.created_at IS '附件创建时间';
COMMENT ON COLUMN public.nw_chat_session_attachments.expires_at IS '最迟创建七天后失效，查询必须拒绝已失效附件';
COMMENT ON CONSTRAINT uq_skillforge_chat_sessions_tenant_session ON public.nw_chat_sessions IS '支持会话子资源使用同租户复合外键';
COMMENT ON CONSTRAINT fk_chat_session_attachment_session ON public.nw_chat_session_attachments IS '附件必须绑定同一租户内存在的产品会话';
COMMENT ON CONSTRAINT uq_chat_session_attachment_path ON public.nw_chat_session_attachments IS '一个会话内的沙箱输入路径不可覆盖';
COMMENT ON CONSTRAINT ck_chat_session_attachment_content_size ON public.nw_chat_session_attachments IS '正文存在时，声明字节数必须与正文一致';
COMMENT ON CONSTRAINT ck_chat_session_attachment_input_path ON public.nw_chat_session_attachments IS '输入路径由附件 UUID 和允许的文本扩展名确定';
