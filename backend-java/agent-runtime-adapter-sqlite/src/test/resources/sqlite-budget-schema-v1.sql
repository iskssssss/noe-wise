-- Frozen pre-policy schema, used only to verify data-preserving migration.
CREATE TABLE agent_runtime_budget_roots (
 tenant_id TEXT NOT NULL, root_id TEXT NOT NULL, scenario TEXT NOT NULL, source_version TEXT NOT NULL,
 active_limit_seconds INTEGER NOT NULL CHECK(active_limit_seconds>0),
 closing_active_seconds INTEGER NOT NULL DEFAULT 0 CHECK(closing_active_seconds>=0), closing_run_id TEXT,
 phase TEXT NOT NULL DEFAULT 'BUSINESS' CHECK(phase IN ('BUSINESS','CLOSING')), PRIMARY KEY(tenant_id,root_id)
) STRICT;
CREATE TABLE agent_runtime_budget_runs (
 tenant_id TEXT NOT NULL, run_id TEXT NOT NULL, root_id TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('PRIMARY','MEMBER')),
 source_version TEXT NOT NULL, active_limit_seconds INTEGER NOT NULL CHECK(active_limit_seconds>0), PRIMARY KEY(tenant_id,run_id),
 FOREIGN KEY(tenant_id,root_id) REFERENCES agent_runtime_budget_roots(tenant_id,root_id)
) STRICT;
CREATE INDEX agent_runtime_budget_runs_root ON agent_runtime_budget_runs(tenant_id,root_id);
CREATE TABLE agent_runtime_budget_balances (
 tenant_id TEXT NOT NULL, scope_kind TEXT NOT NULL CHECK(scope_kind IN ('ROOT','RUN','STAGE_BASE','STAGE','CLOSING','CLOSING_RUN')),
 scope_id TEXT NOT NULL, dimension TEXT NOT NULL, maximum INTEGER NOT NULL CHECK(maximum>=0),
 consumed INTEGER NOT NULL DEFAULT 0 CHECK(consumed>=0), reserved INTEGER NOT NULL DEFAULT 0 CHECK(reserved>=0),
 PRIMARY KEY(tenant_id,scope_kind,scope_id,dimension)
) STRICT;
CREATE TABLE agent_runtime_budget_attempts (
 tenant_id TEXT NOT NULL, attempt_id TEXT NOT NULL, run_id TEXT NOT NULL, fingerprint TEXT NOT NULL, category TEXT NOT NULL,
 closing INTEGER NOT NULL CHECK(closing IN (0,1)), state TEXT NOT NULL CHECK(state IN ('RESERVED','SENT','RESULT_SAVED','SETTLED')),
 result_reference TEXT, PRIMARY KEY(tenant_id,attempt_id), FOREIGN KEY(tenant_id,run_id) REFERENCES agent_runtime_budget_runs(tenant_id,run_id)
) STRICT;
CREATE INDEX agent_runtime_budget_attempts_recovery ON agent_runtime_budget_attempts(tenant_id,run_id,state);
CREATE TABLE agent_runtime_budget_result_receipts (
 tenant_id TEXT NOT NULL, attempt_id TEXT NOT NULL, result_reference TEXT NOT NULL, PRIMARY KEY(tenant_id,attempt_id,result_reference),
 FOREIGN KEY(tenant_id,attempt_id) REFERENCES agent_runtime_budget_attempts(tenant_id,attempt_id)
) STRICT;
CREATE TABLE agent_runtime_budget_closing_targets (
 tenant_id TEXT NOT NULL, run_id TEXT NOT NULL, target_handle TEXT NOT NULL, PRIMARY KEY(tenant_id,run_id,target_handle),
 FOREIGN KEY(tenant_id,run_id) REFERENCES agent_runtime_budget_runs(tenant_id,run_id)
) STRICT;
CREATE TABLE agent_runtime_budget_attempt_amounts (
 tenant_id TEXT NOT NULL, attempt_id TEXT NOT NULL, dimension TEXT NOT NULL, reserved INTEGER NOT NULL CHECK(reserved>=0),
 reported INTEGER CHECK(reported>=0), settled INTEGER NOT NULL DEFAULT 0 CHECK(settled IN (0,1)), PRIMARY KEY(tenant_id,attempt_id,dimension),
 FOREIGN KEY(tenant_id,attempt_id) REFERENCES agent_runtime_budget_attempts(tenant_id,attempt_id)
) STRICT;
CREATE TABLE agent_runtime_budget_activity (
 tenant_id TEXT NOT NULL, run_id TEXT NOT NULL, interval_id TEXT NOT NULL, started_at TEXT NOT NULL,
 confirmed_through TEXT NOT NULL CHECK(confirmed_through>=started_at), PRIMARY KEY(tenant_id,run_id,interval_id),
 FOREIGN KEY(tenant_id,run_id) REFERENCES agent_runtime_budget_runs(tenant_id,run_id)
) STRICT;
