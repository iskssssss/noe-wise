-- 会话消息归档。

CREATE TABLE public.nw_chat_messages (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    session_id character varying(64) NOT NULL,
    end_user_id bigint NOT NULL,
    message_id character varying(64) NOT NULL,
    role character varying(32) NOT NULL,
    content text NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT skillforge_chat_messages_pkey PRIMARY KEY (id),
    CONSTRAINT uq_skillforge_chat_messages_message UNIQUE (tenant_id, message_id),
    CONSTRAINT chk_skillforge_chat_messages_role CHECK ((role = ANY (ARRAY['user'::character varying, 'assistant'::character varying, 'system'::character varying]::text[])))
);

ALTER TABLE ONLY public.nw_chat_messages FORCE ROW LEVEL SECURITY;

CREATE INDEX idx_skillforge_chat_messages_session ON public.nw_chat_messages USING btree (tenant_id, session_id, created_at);
CREATE INDEX idx_skillforge_chat_messages_user_time ON public.nw_chat_messages USING btree (tenant_id, end_user_id, created_at DESC);

ALTER TABLE ONLY public.nw_chat_messages
    ADD CONSTRAINT fk_skillforge_chat_messages_tenant FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id);
ALTER TABLE ONLY public.nw_chat_messages
    ADD CONSTRAINT fk_skillforge_chat_messages_session FOREIGN KEY (session_id) REFERENCES public.nw_chat_sessions(session_id);
ALTER TABLE ONLY public.nw_chat_messages
    ADD CONSTRAINT fk_skillforge_chat_messages_user FOREIGN KEY (end_user_id) REFERENCES public.nw_end_users(id);

CREATE POLICY skillforge_chat_messages_tenant_isolation ON public.nw_chat_messages
    USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)))
    WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));

COMMENT ON TABLE public.nw_chat_messages IS '会话消息归档：全量消息逐行持久化，Redis 热消息与之写穿';
COMMENT ON COLUMN public.nw_chat_messages.id IS '主键雪花 ID';
COMMENT ON COLUMN public.nw_chat_messages.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN public.nw_chat_messages.session_id IS '会话 ID，引用 sessions.session_id';
COMMENT ON COLUMN public.nw_chat_messages.end_user_id IS '消息所属 C 端用户';
COMMENT ON COLUMN public.nw_chat_messages.message_id IS '消息幂等 ID：用户消息沿用 client_message_id，助手消息服务端生成';
COMMENT ON COLUMN public.nw_chat_messages.role IS '消息角色：user / assistant / system';
COMMENT ON COLUMN public.nw_chat_messages.content IS '消息正文';
COMMENT ON COLUMN public.nw_chat_messages.metadata IS '附加元数据：execution_id / plan_id / intent / card 等';
COMMENT ON COLUMN public.nw_chat_messages.created_at IS '消息创建时间';
