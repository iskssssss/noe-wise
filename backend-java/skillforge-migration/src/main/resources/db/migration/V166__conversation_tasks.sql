-- 通用认知任务：SessionLog 为事实，任务表为完整快照投影，根记录持有稳定 fencing。
CREATE TABLE public.agent_runtime_conversation_task_roots (
    tenant_id text NOT NULL,
    task_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    run_id text,
    fence bigint NOT NULL DEFAULT 0,
    until_second bigint,
    until_nano integer,
    CONSTRAINT pk_conversation_task_roots PRIMARY KEY (tenant_id, task_id),
    CONSTRAINT uq_conversation_task_owner UNIQUE (tenant_id, task_id, agent_id, end_user_id),
    CONSTRAINT ck_conversation_task_owner CHECK (btrim(tenant_id) <> '' AND btrim(task_id) <> '' AND btrim(agent_id) <> '' AND end_user_id > 0),
    CONSTRAINT ck_conversation_task_lease CHECK (fence >= 0 AND ((run_id IS NULL AND until_second IS NULL AND until_nano IS NULL) OR (run_id IS NOT NULL AND btrim(run_id) <> '' AND until_second IS NOT NULL AND until_nano IS NOT NULL AND until_nano BETWEEN 0 AND 999999999)))
);
CREATE TABLE public.agent_runtime_conversation_tasks (
    tenant_id text NOT NULL,
    task_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    revision bigint NOT NULL,
    status text NOT NULL,
    title text NOT NULL,
    summary text NOT NULL,
    schema_id text NOT NULL,
    snapshot text NOT NULL,
    updated_second bigint NOT NULL,
    updated_nano integer NOT NULL,
    CONSTRAINT pk_conversation_tasks PRIMARY KEY (tenant_id, task_id),
    CONSTRAINT uq_conversation_task_projection_owner UNIQUE (tenant_id, task_id, agent_id, end_user_id),
    CONSTRAINT fk_conversation_task_root FOREIGN KEY (tenant_id, task_id, agent_id, end_user_id) REFERENCES public.agent_runtime_conversation_task_roots (tenant_id, task_id, agent_id, end_user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_conversation_task_projection CHECK (revision > 0 AND status IN ('OPEN', 'WAITING_USER', 'COMPLETED', 'CANCELLED') AND char_length(title) BETWEEN 1 AND 200 AND char_length(summary) <= 8000 AND btrim(schema_id) <> '' AND updated_nano BETWEEN 0 AND 999999999)
);
CREATE INDEX ix_conversation_task_directory ON public.agent_runtime_conversation_tasks (tenant_id, agent_id, end_user_id, updated_second DESC, updated_nano DESC, task_id);
CREATE TABLE public.agent_runtime_conversation_task_operations (
    tenant_id text NOT NULL,
    task_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    session_id text NOT NULL,
    event_id text NOT NULL,
    expected_revision bigint NOT NULL,
    selected boolean NOT NULL,
    CONSTRAINT pk_conversation_task_operations PRIMARY KEY (tenant_id, agent_id, session_id, event_id),
    CONSTRAINT fk_conversation_task_operation_task FOREIGN KEY (tenant_id, task_id, agent_id, end_user_id) REFERENCES public.agent_runtime_conversation_tasks (tenant_id, task_id, agent_id, end_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_task_operation_event FOREIGN KEY (tenant_id, agent_id, session_id, event_id) REFERENCES public.agent_runtime_session_events (tenant_id, agent_id, session_id, event_id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_task_operation_owner FOREIGN KEY (tenant_id, agent_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_owners (tenant_id, agent_id, session_id, end_user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_conversation_task_operation_revision CHECK (expected_revision >= 0)
);
CREATE TABLE public.agent_runtime_conversation_task_bindings (
    tenant_id text NOT NULL,
    task_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    session_id text NOT NULL,
    CONSTRAINT pk_conversation_task_bindings PRIMARY KEY (tenant_id, agent_id, session_id),
    CONSTRAINT fk_conversation_task_binding_task FOREIGN KEY (tenant_id, task_id, agent_id, end_user_id) REFERENCES public.agent_runtime_conversation_tasks (tenant_id, task_id, agent_id, end_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_task_binding_owner FOREIGN KEY (tenant_id, agent_id, session_id, end_user_id) REFERENCES public.agent_runtime_session_owners (tenant_id, agent_id, session_id, end_user_id) ON DELETE RESTRICT
);

ALTER TABLE public.agent_runtime_conversation_task_roots ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_conversation_task_roots FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_conversation_task_roots USING (tenant_id = current_setting('app.tenant_id', true)) WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
COMMENT ON TABLE public.agent_runtime_conversation_task_roots IS '任务身份和可恢复推进租约';
COMMENT ON COLUMN public.agent_runtime_conversation_task_roots.tenant_id IS '租户隔离标识';
COMMENT ON COLUMN public.agent_runtime_conversation_task_roots.task_id IS '租户内唯一任务标识';
COMMENT ON COLUMN public.agent_runtime_conversation_task_roots.agent_id IS '任务所属助手';
COMMENT ON COLUMN public.agent_runtime_conversation_task_roots.end_user_id IS '操作者用户标识，不等同业务客户';
COMMENT ON COLUMN public.agent_runtime_conversation_task_roots.run_id IS '租约持有 Run，释放后为空';
COMMENT ON COLUMN public.agent_runtime_conversation_task_roots.fence IS '单调增长写权代次，释放不清零';
COMMENT ON COLUMN public.agent_runtime_conversation_task_roots.until_second IS '租约截止 Unix 秒';
COMMENT ON COLUMN public.agent_runtime_conversation_task_roots.until_nano IS '租约截止纳秒分量';

ALTER TABLE public.agent_runtime_conversation_tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_conversation_tasks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_conversation_tasks USING (tenant_id = current_setting('app.tenant_id', true)) WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
COMMENT ON TABLE public.agent_runtime_conversation_tasks IS '由 SessionLog 任务事件重建的完整任务投影';
COMMENT ON COLUMN public.agent_runtime_conversation_tasks.tenant_id IS '租户隔离标识';
COMMENT ON COLUMN public.agent_runtime_conversation_tasks.task_id IS '租户内唯一任务标识';
COMMENT ON COLUMN public.agent_runtime_conversation_tasks.agent_id IS '任务所属助手';
COMMENT ON COLUMN public.agent_runtime_conversation_tasks.end_user_id IS '操作者用户标识，不等同业务客户';
COMMENT ON COLUMN public.agent_runtime_conversation_tasks.revision IS '当前任务修订号';
COMMENT ON COLUMN public.agent_runtime_conversation_tasks.status IS '任务生命周期状态';
COMMENT ON COLUMN public.agent_runtime_conversation_tasks.title IS '任务展示与检索标题';
COMMENT ON COLUMN public.agent_runtime_conversation_tasks.summary IS '任务进展检索摘要';
COMMENT ON COLUMN public.agent_runtime_conversation_tasks.schema_id IS '完整任务快照 schema 标识';
COMMENT ON COLUMN public.agent_runtime_conversation_tasks.snapshot IS '完整规范 JSON 快照以 text 保存以避免 JSON 重排影响幂等与精度';
COMMENT ON COLUMN public.agent_runtime_conversation_tasks.updated_second IS '最后更新时间 Unix 秒';
COMMENT ON COLUMN public.agent_runtime_conversation_tasks.updated_nano IS '最后更新时间纳秒分量';

ALTER TABLE public.agent_runtime_conversation_task_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_conversation_task_operations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_conversation_task_operations USING (tenant_id = current_setting('app.tenant_id', true)) WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
COMMENT ON TABLE public.agent_runtime_conversation_task_operations IS '首次成功任务事件及命令幂等语义';
COMMENT ON COLUMN public.agent_runtime_conversation_task_operations.tenant_id IS '租户隔离标识';
COMMENT ON COLUMN public.agent_runtime_conversation_task_operations.task_id IS '租户内唯一任务标识';
COMMENT ON COLUMN public.agent_runtime_conversation_task_operations.agent_id IS '任务所属助手';
COMMENT ON COLUMN public.agent_runtime_conversation_task_operations.end_user_id IS '操作者用户标识，不等同业务客户';
COMMENT ON COLUMN public.agent_runtime_conversation_task_operations.session_id IS '操作或当前绑定的会话';
COMMENT ON COLUMN public.agent_runtime_conversation_task_operations.event_id IS '原子提交到 SessionLog 的事件标识';
COMMENT ON COLUMN public.agent_runtime_conversation_task_operations.expected_revision IS '首次提交预期的前置修订号';
COMMENT ON COLUMN public.agent_runtime_conversation_task_operations.selected IS '首次提交是否同时更新会话当前任务';

ALTER TABLE public.agent_runtime_conversation_task_bindings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_conversation_task_bindings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_conversation_task_bindings USING (tenant_id = current_setting('app.tenant_id', true)) WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
COMMENT ON TABLE public.agent_runtime_conversation_task_bindings IS '各会话当前选择的任务';
COMMENT ON COLUMN public.agent_runtime_conversation_task_bindings.tenant_id IS '租户隔离标识';
COMMENT ON COLUMN public.agent_runtime_conversation_task_bindings.task_id IS '租户内唯一任务标识';
COMMENT ON COLUMN public.agent_runtime_conversation_task_bindings.agent_id IS '任务所属助手';
COMMENT ON COLUMN public.agent_runtime_conversation_task_bindings.end_user_id IS '操作者用户标识，不等同业务客户';
COMMENT ON COLUMN public.agent_runtime_conversation_task_bindings.session_id IS '操作或当前绑定的会话';
COMMENT ON CONSTRAINT pk_conversation_task_roots ON public.agent_runtime_conversation_task_roots IS '唯一定位任务身份或提交记录';
COMMENT ON CONSTRAINT uq_conversation_task_owner ON public.agent_runtime_conversation_task_roots IS '唯一定位任务身份或提交记录';
COMMENT ON CONSTRAINT ck_conversation_task_owner ON public.agent_runtime_conversation_task_roots IS '限制任务行身份与状态合法性';
COMMENT ON CONSTRAINT ck_conversation_task_lease ON public.agent_runtime_conversation_task_roots IS '限制任务行身份与状态合法性';
COMMENT ON CONSTRAINT pk_conversation_tasks ON public.agent_runtime_conversation_tasks IS '唯一定位任务身份或提交记录';
COMMENT ON CONSTRAINT uq_conversation_task_projection_owner ON public.agent_runtime_conversation_tasks IS '唯一定位任务身份或提交记录';
COMMENT ON CONSTRAINT fk_conversation_task_root ON public.agent_runtime_conversation_tasks IS '保证关联任务或会话事实存在且归属一致';
COMMENT ON CONSTRAINT ck_conversation_task_projection ON public.agent_runtime_conversation_tasks IS '限制任务行身份与状态合法性';
COMMENT ON CONSTRAINT pk_conversation_task_operations ON public.agent_runtime_conversation_task_operations IS '唯一定位任务身份或提交记录';
COMMENT ON CONSTRAINT fk_conversation_task_operation_task ON public.agent_runtime_conversation_task_operations IS '保证关联任务或会话事实存在且归属一致';
COMMENT ON CONSTRAINT fk_conversation_task_operation_event ON public.agent_runtime_conversation_task_operations IS '保证关联任务或会话事实存在且归属一致';
COMMENT ON CONSTRAINT fk_conversation_task_operation_owner ON public.agent_runtime_conversation_task_operations IS '保证关联任务或会话事实存在且归属一致';
COMMENT ON CONSTRAINT ck_conversation_task_operation_revision ON public.agent_runtime_conversation_task_operations IS '限制任务行身份与状态合法性';
COMMENT ON CONSTRAINT pk_conversation_task_bindings ON public.agent_runtime_conversation_task_bindings IS '唯一定位任务身份或提交记录';
COMMENT ON CONSTRAINT fk_conversation_task_binding_task ON public.agent_runtime_conversation_task_bindings IS '保证关联任务或会话事实存在且归属一致';
COMMENT ON CONSTRAINT fk_conversation_task_binding_owner ON public.agent_runtime_conversation_task_bindings IS '保证关联任务或会话事实存在且归属一致';
COMMENT ON INDEX public.ix_conversation_task_directory IS '按完整任务归属和时间分页检索任务';
