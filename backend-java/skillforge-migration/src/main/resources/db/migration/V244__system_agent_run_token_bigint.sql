-- Provider usage is Long throughout the Runtime; system-agent run snapshots must not narrow it.
ALTER TABLE nw_platform_system_agent_runs
    ALTER COLUMN input_tokens TYPE bigint,
    ALTER COLUMN output_tokens TYPE bigint;
