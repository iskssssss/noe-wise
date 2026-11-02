ALTER TABLE public.nw_chat_sessions
    ADD COLUMN team_id varchar(64);

ALTER TABLE public.nw_chat_sessions
    ADD CONSTRAINT ck_skillforge_chat_sessions_team_id_nonblank
        CHECK (team_id IS NULL OR btrim(team_id) <> ''),
    ADD CONSTRAINT ck_skillforge_chat_sessions_target_exactly_one
        CHECK ((agent_id IS NULL) <> (team_id IS NULL));

COMMENT ON COLUMN public.nw_chat_sessions.team_id IS 'Team 会话目标；与 agent_id 恰有一个非空';
