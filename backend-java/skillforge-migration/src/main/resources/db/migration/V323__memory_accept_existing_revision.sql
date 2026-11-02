-- 确认既有待审提案时，复用原事实、来源和操作日志，在一个事务内推进两个 head。
ALTER TABLE public.agent_runtime_memory_operations
    DROP CONSTRAINT ck_agent_runtime_memory_operation_kind;
ALTER TABLE public.agent_runtime_memory_operations
    ADD CONSTRAINT ck_agent_runtime_memory_operation_kind
    CHECK (command_kind IN ('CREATE','SUPERSEDE','TRANSITION','ACCEPT_REVISION'));
COMMENT ON CONSTRAINT ck_agent_runtime_memory_operation_kind ON public.agent_runtime_memory_operations
    IS '冻结的创建、替代、状态迁移与既有提案确认 mutation';
