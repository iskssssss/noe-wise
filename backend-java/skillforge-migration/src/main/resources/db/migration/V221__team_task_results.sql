-- 团队任务成果是不可变权威记录；协调层在本事务提交后把 ResultRef 投影到 Run/SessionLog。
ALTER TABLE public.agent_runtime_dag_admissions
    ADD CONSTRAINT uq_agent_runtime_dag_admission_task_run
    UNIQUE (tenant_id, session_id, root_input_id, task_id, run_id);

CREATE TABLE public.agent_runtime_team_task_results (
    tenant_id varchar(160) NOT NULL,
    session_id varchar(240) NOT NULL,
    root_input_id varchar(240) NOT NULL,
    task_id varchar(240) NOT NULL,
    run_id varchar(240) NOT NULL,
    output_name varchar(240) NOT NULL,
    result_id varchar(240) NOT NULL,
    schema_id varchar(100) NOT NULL CHECK (schema_id = 'team-task-result.v1'),
    kind varchar(40) NOT NULL CHECK (kind IN ('text','document','spreadsheet','presentation','image',
        'design','website','application','code','media','operation','configuration')),
    status varchar(40) NOT NULL CHECK (status IN ('succeeded','partial','failed')),
    payload jsonb NOT NULL,
    payload_sha256 varchar(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    command_sha256 varchar(64) NOT NULL CHECK (command_sha256 ~ '^[0-9a-f]{64}$'),
    source_event_ref varchar(240),
    invocation_id varchar(240) NOT NULL,
    attempt_id varchar(240) NOT NULL,
    authorization_summary jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, result_id),
    UNIQUE (tenant_id, invocation_id, output_name),
    FOREIGN KEY (tenant_id, session_id, root_input_id, task_id, run_id)
        REFERENCES public.agent_runtime_dag_admissions(tenant_id, session_id, root_input_id, task_id, run_id),
    CHECK (btrim(session_id) <> '' AND btrim(root_input_id) <> '' AND btrim(task_id) <> ''
        AND btrim(run_id) <> '' AND btrim(output_name) <> '' AND btrim(result_id) <> ''
        AND btrim(invocation_id) <> '' AND btrim(attempt_id) <> ''),
    CHECK (jsonb_typeof(payload) = 'object' AND payload->>'schema_id' = schema_id
        AND payload->>'result_id' = result_id AND payload->>'task_id' = task_id
        AND payload->>'output_name' = output_name AND payload->>'kind' = kind AND payload->>'status' = status),
    CHECK (jsonb_typeof(authorization_summary) = 'object')
);

CREATE INDEX agent_runtime_team_task_results_work
    ON public.agent_runtime_team_task_results(tenant_id, session_id, root_input_id, task_id, output_name, created_at, result_id);
CREATE INDEX agent_runtime_team_task_results_run
    ON public.agent_runtime_team_task_results(tenant_id, run_id, created_at, result_id);

COMMENT ON TABLE public.agent_runtime_team_task_results IS '团队任务不可变成果事实；不是任务生命周期或 SessionLog 的第二事实源';
COMMENT ON COLUMN public.agent_runtime_team_task_results.tenant_id IS '租户隔离键';
COMMENT ON COLUMN public.agent_runtime_team_task_results.session_id IS '成果所属 Session';
COMMENT ON COLUMN public.agent_runtime_team_task_results.root_input_id IS '成果所属 DAG 工作根输入';
COMMENT ON COLUMN public.agent_runtime_team_task_results.task_id IS '已准入的任务标识';
COMMENT ON COLUMN public.agent_runtime_team_task_results.run_id IS '实际执行该任务的不可变 Run';
COMMENT ON COLUMN public.agent_runtime_team_task_results.output_name IS 'DagPlan 中预先声明的输出名称';
COMMENT ON COLUMN public.agent_runtime_team_task_results.result_id IS '公开 ResultRef 的不可变标识';
COMMENT ON COLUMN public.agent_runtime_team_task_results.schema_id IS '成果载荷 schema 标识';
COMMENT ON COLUMN public.agent_runtime_team_task_results.kind IS '成果业务种类';
COMMENT ON COLUMN public.agent_runtime_team_task_results.status IS '该成果记录自身状态；失败修订不会覆盖既有成功成果';
COMMENT ON COLUMN public.agent_runtime_team_task_results.payload IS '不可变成果 schema 载荷，不保存文件正文或完整授权凭据';
COMMENT ON COLUMN public.agent_runtime_team_task_results.payload_sha256 IS '规范化成果载荷 SHA-256，供冻结输入绑定';
COMMENT ON COLUMN public.agent_runtime_team_task_results.command_sha256 IS '同一可信 invocation 的完整命令指纹';
COMMENT ON COLUMN public.agent_runtime_team_task_results.source_event_ref IS '可信 Run 或 Action 来源事件引用；工具 attempt 来源可为空';
COMMENT ON COLUMN public.agent_runtime_team_task_results.invocation_id IS '可信 Runtime 逻辑调用标识';
COMMENT ON COLUMN public.agent_runtime_team_task_results.attempt_id IS '首次提交成果的可信 Runtime attempt 标识';
COMMENT ON COLUMN public.agent_runtime_team_task_results.authorization_summary IS '登记时授权结论摘要，不含令牌、密钥或许可原文';
COMMENT ON COLUMN public.agent_runtime_team_task_results.created_at IS '数据库记录时间';

-- 成果只追加；修订必须创建新的 result_id，不能原地覆盖或删除历史结果。
CREATE FUNCTION public.reject_team_task_result_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'team task results are immutable';
END;
$$;

CREATE TRIGGER reject_team_task_result_update
BEFORE UPDATE OR DELETE ON public.agent_runtime_team_task_results
FOR EACH ROW EXECUTE FUNCTION public.reject_team_task_result_mutation();

ALTER TABLE public.agent_runtime_team_task_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_team_task_results FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_team_task_results
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
