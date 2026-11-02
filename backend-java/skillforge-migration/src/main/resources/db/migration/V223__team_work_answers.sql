-- 每个团队工作只有一个可向会话披露的答复；成员 Run 输出不能直接成为聊天消息。
CREATE TABLE public.agent_runtime_team_work_answers (
    tenant_id varchar(160) NOT NULL,
    session_id varchar(240) NOT NULL,
    root_input_id varchar(240) NOT NULL,
    result_ref varchar(240) NOT NULL,
    answer_type varchar(20) NOT NULL CHECK (answer_type IN ('DIRECT','RESULT','SUMMARY','CANCELLED')),
    answer_text text NOT NULL,
    source_control_run_id varchar(240),
    source_control_revision bigint CHECK (source_control_revision > 0),
    source_result_ref varchar(240),
    source_agent_id varchar(240),
    source_run_refs jsonb NOT NULL CHECK (jsonb_typeof(source_run_refs) = 'array'),
    answer_revision bigint NOT NULL CHECK (answer_revision > 0),
    message_id varchar(240) NOT NULL,
    message_event_id varchar(240) NOT NULL,
    completion_event_id varchar(240) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('RECORDED','DELIVERED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    delivered_at timestamptz,
    PRIMARY KEY (tenant_id, session_id, root_input_id),
    UNIQUE (tenant_id, result_ref),
    UNIQUE (tenant_id, message_id),
    UNIQUE (tenant_id, message_event_id),
    UNIQUE (tenant_id, completion_event_id),
    FOREIGN KEY (tenant_id, session_id, root_input_id)
        REFERENCES public.agent_runtime_dag_works(tenant_id, session_id, root_input_id),
    FOREIGN KEY (tenant_id, source_control_run_id)
        REFERENCES public.agent_runtime_dag_control_runs(tenant_id, run_id),
    FOREIGN KEY (tenant_id, source_result_ref)
        REFERENCES public.agent_runtime_team_task_results(tenant_id, result_id),
    CHECK (btrim(result_ref) <> '' AND btrim(answer_text) <> '' AND btrim(message_id) <> ''
        AND btrim(message_event_id) <> '' AND btrim(completion_event_id) <> ''),
    CHECK ((status = 'RECORDED' AND delivered_at IS NULL)
        OR (status = 'DELIVERED' AND delivered_at IS NOT NULL)),
    CHECK ((answer_type = 'RESULT' AND source_result_ref IS NOT NULL AND source_agent_id IS NOT NULL)
        OR (answer_type <> 'RESULT' AND source_result_ref IS NULL)),
    CHECK ((source_control_run_id IS NULL) = (source_control_revision IS NULL)),
    CHECK (source_control_run_id IS NOT NULL OR answer_type = 'CANCELLED')
);

CREATE INDEX agent_runtime_team_work_answers_session
    ON public.agent_runtime_team_work_answers(tenant_id, session_id, created_at, root_input_id);

COMMENT ON TABLE public.agent_runtime_team_work_answers IS '团队工作唯一公开答复及其幂等 Memory/Outbox 投递状态';
COMMENT ON COLUMN public.agent_runtime_team_work_answers.tenant_id IS '租户隔离键';
COMMENT ON COLUMN public.agent_runtime_team_work_answers.session_id IS '答复所属产品会话';
COMMENT ON COLUMN public.agent_runtime_team_work_answers.root_input_id IS '唯一对应的团队工作根输入';
COMMENT ON COLUMN public.agent_runtime_team_work_answers.result_ref IS '协调关闭引用的稳定答复标识';
COMMENT ON COLUMN public.agent_runtime_team_work_answers.answer_type IS '直接答复、成员成果、确定性缺口说明或取消说明';
COMMENT ON COLUMN public.agent_runtime_team_work_answers.answer_text IS '唯一允许进入聊天历史的最终文本';
COMMENT ON COLUMN public.agent_runtime_team_work_answers.source_control_run_id IS '已终态的接待控制 Run';
COMMENT ON COLUMN public.agent_runtime_team_work_answers.source_control_revision IS '结案时核验的控制 Run revision';
COMMENT ON COLUMN public.agent_runtime_team_work_answers.source_result_ref IS '成员最终答复成果引用；仅 RESULT 类型存在';
COMMENT ON COLUMN public.agent_runtime_team_work_answers.source_agent_id IS '答复内容来源成员；披露时重新校验当前成员权限';
COMMENT ON COLUMN public.agent_runtime_team_work_answers.source_run_refs IS '结案时全部真实 Run 的 ID、revision 与终态快照';
COMMENT ON COLUMN public.agent_runtime_team_work_answers.answer_revision IS '登记答复时的 DAG 工作 revision';
COMMENT ON COLUMN public.agent_runtime_team_work_answers.message_id IS 'Memory 幂等消息标识';
COMMENT ON COLUMN public.agent_runtime_team_work_answers.message_event_id IS 'message.delta Outbox 幂等事件标识';
COMMENT ON COLUMN public.agent_runtime_team_work_answers.completion_event_id IS 'execution.completed Outbox 幂等事件标识';
COMMENT ON COLUMN public.agent_runtime_team_work_answers.status IS 'RECORDED 已先提交答复；DELIVERED 表示 Memory 与两条 Outbox 已接受';
COMMENT ON COLUMN public.agent_runtime_team_work_answers.created_at IS '数据库登记时间';
COMMENT ON COLUMN public.agent_runtime_team_work_answers.delivered_at IS '完成幂等投递的数据库时间';

-- 正文和来源一经登记不可改变，只允许在投递副作用全部成功后单向标记 DELIVERED。
CREATE FUNCTION public.guard_team_work_answer_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.session_id <> OLD.session_id
       OR NEW.root_input_id <> OLD.root_input_id OR NEW.result_ref <> OLD.result_ref
       OR NEW.answer_type <> OLD.answer_type OR NEW.answer_text <> OLD.answer_text
       OR NEW.source_control_run_id IS DISTINCT FROM OLD.source_control_run_id
       OR NEW.source_control_revision IS DISTINCT FROM OLD.source_control_revision
       OR NEW.source_result_ref IS DISTINCT FROM OLD.source_result_ref
       OR NEW.source_agent_id IS DISTINCT FROM OLD.source_agent_id
       OR NEW.source_run_refs <> OLD.source_run_refs OR NEW.answer_revision <> OLD.answer_revision
       OR NEW.message_id <> OLD.message_id OR NEW.message_event_id <> OLD.message_event_id
       OR NEW.completion_event_id <> OLD.completion_event_id OR NEW.created_at <> OLD.created_at
       OR OLD.status <> 'RECORDED' OR NEW.status <> 'DELIVERED' OR NEW.delivered_at IS NULL THEN
        RAISE EXCEPTION 'team work answer is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER guard_team_work_answer_update
BEFORE UPDATE ON public.agent_runtime_team_work_answers
FOR EACH ROW EXECUTE FUNCTION public.guard_team_work_answer_mutation();

CREATE FUNCTION public.reject_team_work_answer_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'team work answer is immutable';
END;
$$;

CREATE TRIGGER reject_team_work_answer_delete
BEFORE DELETE ON public.agent_runtime_team_work_answers
FOR EACH ROW EXECUTE FUNCTION public.reject_team_work_answer_delete();

ALTER TABLE public.agent_runtime_team_work_answers ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_team_work_answers FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_team_work_answers
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
