-- 团队版本和工作快照是配置/协调投影；Run 与 Action 继续保存真实执行事实。
CREATE TABLE public.nw_agent_teams (
    tenant_id bigint NOT NULL REFERENCES public.nw_tenants(id),
    id varchar(100) NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    published_revision bigint,
    status varchar(20) NOT NULL CHECK (status IN ('draft', 'published', 'disabled')),
    definition jsonb NOT NULL,
    PRIMARY KEY (tenant_id, id)
);
CREATE TABLE public.nw_agent_team_versions (
    tenant_id bigint NOT NULL,
    team_id varchar(100) NOT NULL,
    revision bigint NOT NULL,
    definition jsonb NOT NULL,
    published_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, team_id, revision),
    FOREIGN KEY (tenant_id, team_id) REFERENCES public.nw_agent_teams(tenant_id, id)
);

CREATE TABLE public.agent_runtime_dag_works (
    work_sequence bigint GENERATED ALWAYS AS IDENTITY,
    tenant_id varchar(160) NOT NULL,
    session_id varchar(240) NOT NULL,
    root_input_id varchar(240) NOT NULL,
    target_kind varchar(10) NOT NULL,
    target_id varchar(240) NOT NULL,
    end_user_id bigint NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    status varchar(20) NOT NULL CHECK (status IN ('QUEUED', 'ACTIVE', 'CANCELLING', 'COMPLETED', 'CANCELLED')),
    snapshot text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, session_id, root_input_id)
);
CREATE UNIQUE INDEX agent_runtime_dag_active_session
    ON public.agent_runtime_dag_works(tenant_id, session_id)
    WHERE status IN ('ACTIVE', 'CANCELLING');
CREATE INDEX agent_runtime_dag_recovery ON public.agent_runtime_dag_works(tenant_id, updated_at)
    WHERE status IN ('ACTIVE', 'CANCELLING');

-- 委派的稳定业务准入：容量释放/恢复不得修改这一记录或生成另一 child Run。
CREATE TABLE public.agent_runtime_dag_admissions (
    tenant_id varchar(160) NOT NULL,
    session_id varchar(240) NOT NULL,
    root_input_id varchar(240) NOT NULL,
    task_id varchar(240) NOT NULL,
    admission_id varchar(240) NOT NULL,
    run_id varchar(240) NOT NULL,
    agent_id varchar(240) NOT NULL,
    plan_revision bigint NOT NULL,
    binding text NOT NULL,
    PRIMARY KEY (tenant_id, admission_id),
    UNIQUE (tenant_id, run_id),
    UNIQUE (tenant_id, session_id, root_input_id, task_id),
    FOREIGN KEY (tenant_id, session_id, root_input_id)
        REFERENCES public.agent_runtime_dag_works(tenant_id, session_id, root_input_id)
);
CREATE TABLE public.agent_runtime_dag_commands (
    tenant_id varchar(160) NOT NULL,
    session_id varchar(240) NOT NULL,
    root_input_id varchar(240) NOT NULL,
    command_id varchar(240) NOT NULL,
    fingerprint varchar(128) NOT NULL,
    PRIMARY KEY (tenant_id, session_id, command_id),
    FOREIGN KEY (tenant_id, session_id, root_input_id)
        REFERENCES public.agent_runtime_dag_works(tenant_id, session_id, root_input_id)
);
CREATE TABLE public.agent_runtime_dag_control_runs (
    tenant_id varchar(160) NOT NULL,
    session_id varchar(240) NOT NULL,
    root_input_id varchar(240) NOT NULL,
    run_id varchar(240) NOT NULL,
    agent_id varchar(240) NOT NULL,
    role varchar(20) NOT NULL CHECK(role IN ('ROUTING','WORKING')),
    PRIMARY KEY(tenant_id,run_id),
    FOREIGN KEY(tenant_id,session_id,root_input_id)
        REFERENCES public.agent_runtime_dag_works(tenant_id,session_id,root_input_id)
);

CREATE TABLE public.agent_runtime_dag_permits (
    tenant_id varchar(160) NOT NULL,
    run_id varchar(240) NOT NULL,
    fence bigint NOT NULL CHECK (fence > 0),
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, run_id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES public.agent_runtime_dag_admissions(tenant_id, run_id)
);
CREATE TABLE public.agent_runtime_dag_inputs (
    sequence bigint GENERATED ALWAYS AS IDENTITY,
    tenant_id varchar(160) NOT NULL,
    session_id varchar(240) NOT NULL,
    root_input_id varchar(240) NOT NULL,
    input_id varchar(240) NOT NULL,
    payload text NOT NULL,
    state varchar(20) NOT NULL DEFAULT 'ACCEPTED' CHECK(state IN ('ACCEPTED','APPLIED','REJECTED')),
    detail text NOT NULL DEFAULT '',
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,session_id,input_id),
    FOREIGN KEY(tenant_id,session_id,root_input_id)
        REFERENCES public.agent_runtime_dag_works(tenant_id,session_id,root_input_id)
);
ALTER TABLE public.agent_runtime_session_inbox ADD COLUMN lane varchar(240) NOT NULL DEFAULT '';
CREATE INDEX agent_runtime_inbox_lanes ON public.agent_runtime_session_inbox(tenant_id,session_id,lane,inbox_sequence)
    WHERE status IN ('QUEUED','PROCESSING');

-- 挂起事实与容量释放同事务；恢复输入只能在重新取得原任务容量后被领取。
CREATE FUNCTION public.release_dag_capacity_on_run_yield() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status IN ('SUSPENDED','COMPLETED','FAILED','TERMINATED','CANCELLED') AND OLD.status <> NEW.status THEN
        DELETE FROM public.agent_runtime_dag_permits WHERE tenant_id=NEW.tenant_id AND run_id=NEW.run_id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_runtime_dag_yield AFTER UPDATE OF status ON public.agent_runtime_runs
    FOR EACH ROW EXECUTE FUNCTION public.release_dag_capacity_on_run_yield();

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['nw_agent_teams','nw_agent_team_versions','agent_runtime_dag_works',
        'agent_runtime_dag_admissions','agent_runtime_dag_commands','agent_runtime_dag_permits','agent_runtime_dag_control_runs','agent_runtime_dag_inputs']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (tenant_id::text = current_setting(''app.tenant_id'', true)) WITH CHECK (tenant_id::text = current_setting(''app.tenant_id'', true))', table_name);
    END LOOP;
END $$;
