CREATE TABLE agent_runtime_budget_roots (
 tenant_id varchar(64) NOT NULL, root_id varchar(256) NOT NULL,
 scenario varchar(32) NOT NULL, source_version text NOT NULL,
 active_limit_seconds bigint NOT NULL CHECK (active_limit_seconds > 0),
 closing_active_seconds bigint NOT NULL DEFAULT 0 CHECK (closing_active_seconds >= 0),
 closing_run_id varchar(256),
 phase varchar(16) NOT NULL DEFAULT 'BUSINESS' CHECK (phase IN ('BUSINESS','CLOSING')),
 PRIMARY KEY (tenant_id,root_id)
);
CREATE TABLE agent_runtime_budget_runs (
 tenant_id varchar(64) NOT NULL, run_id varchar(256) NOT NULL, root_id varchar(256) NOT NULL,
 role varchar(16) NOT NULL CHECK (role IN ('PRIMARY','MEMBER')),
 source_version text NOT NULL, active_limit_seconds bigint NOT NULL CHECK (active_limit_seconds > 0),
 PRIMARY KEY (tenant_id,run_id),
 FOREIGN KEY (tenant_id,root_id) REFERENCES agent_runtime_budget_roots(tenant_id,root_id)
);
CREATE INDEX agent_runtime_budget_runs_root ON agent_runtime_budget_runs(tenant_id,root_id);
CREATE TABLE agent_runtime_budget_balances (
 tenant_id varchar(64) NOT NULL, scope_kind varchar(16) NOT NULL CHECK (scope_kind IN ('ROOT','RUN','STAGE_BASE','STAGE','CLOSING','CLOSING_RUN')),
 scope_id varchar(256) NOT NULL, dimension varchar(32) NOT NULL,
 maximum bigint NOT NULL CHECK (maximum >= 0), consumed bigint NOT NULL DEFAULT 0 CHECK (consumed >= 0),
 reserved bigint NOT NULL DEFAULT 0 CHECK (reserved >= 0),
 PRIMARY KEY (tenant_id,scope_kind,scope_id,dimension)
);
CREATE TABLE agent_runtime_budget_attempts (
 tenant_id varchar(64) NOT NULL, attempt_id varchar(256) NOT NULL, run_id varchar(256) NOT NULL,
 fingerprint text NOT NULL, category varchar(32) NOT NULL, closing boolean NOT NULL,
 state varchar(32) NOT NULL CHECK (state IN ('RESERVED','SENT','RESULT_SAVED','SETTLED')),
 result_reference text, PRIMARY KEY (tenant_id,attempt_id),
 FOREIGN KEY (tenant_id,run_id) REFERENCES agent_runtime_budget_runs(tenant_id,run_id)
);
CREATE INDEX agent_runtime_budget_attempts_recovery ON agent_runtime_budget_attempts(tenant_id,run_id,state);
CREATE TABLE agent_runtime_budget_result_receipts (
 tenant_id varchar(64) NOT NULL, attempt_id varchar(256) NOT NULL, result_reference text NOT NULL,
 PRIMARY KEY (tenant_id,attempt_id,result_reference),
 FOREIGN KEY (tenant_id,attempt_id) REFERENCES agent_runtime_budget_attempts(tenant_id,attempt_id)
);
CREATE TABLE agent_runtime_budget_closing_targets (
 tenant_id varchar(64) NOT NULL, run_id varchar(256) NOT NULL, target_handle text NOT NULL,
 PRIMARY KEY (tenant_id,run_id,target_handle),
 FOREIGN KEY (tenant_id,run_id) REFERENCES agent_runtime_budget_runs(tenant_id,run_id)
);
CREATE TABLE agent_runtime_budget_attempt_amounts (
 tenant_id varchar(64) NOT NULL, attempt_id varchar(256) NOT NULL, dimension varchar(32) NOT NULL,
 reserved bigint NOT NULL CHECK (reserved >= 0), reported bigint CHECK (reported >= 0),
 settled boolean NOT NULL DEFAULT false,
 PRIMARY KEY (tenant_id,attempt_id,dimension),
 FOREIGN KEY (tenant_id,attempt_id) REFERENCES agent_runtime_budget_attempts(tenant_id,attempt_id)
);
CREATE TABLE agent_runtime_budget_activity (
 tenant_id varchar(64) NOT NULL, run_id varchar(256) NOT NULL, interval_id varchar(256) NOT NULL,
 started_at timestamptz NOT NULL, confirmed_through timestamptz NOT NULL CHECK (confirmed_through >= started_at),
 PRIMARY KEY (tenant_id,run_id,interval_id),
 FOREIGN KEY (tenant_id,run_id) REFERENCES agent_runtime_budget_runs(tenant_id,run_id)
);
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['agent_runtime_budget_roots','agent_runtime_budget_runs','agent_runtime_budget_balances',
 'agent_runtime_budget_attempts','agent_runtime_budget_attempt_amounts','agent_runtime_budget_activity',
 'agent_runtime_budget_result_receipts','agent_runtime_budget_closing_targets'] LOOP
 EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
 EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
 EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = COALESCE(current_setting(''app.tenant_id'',true),'''')) WITH CHECK (tenant_id = COALESCE(current_setting(''app.tenant_id'',true),''''))',t);
 END LOOP;
END $$;
COMMENT ON TABLE agent_runtime_budget_roots IS '宿主受理时冻结的任务根预算，不拥有 Run 或 Goal 状态';
COMMENT ON TABLE agent_runtime_budget_attempt_amounts IS '缺报维度保留预留；实际用量和幂等结算回执';
COMMENT ON TABLE agent_runtime_budget_activity IS '可信执行区间；根账以区间并集计算活跃时长';
