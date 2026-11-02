ALTER TABLE public.agent_runtime_model_bindings
    DROP CONSTRAINT agent_runtime_model_bindings_reasoning_check;

COMMENT ON COLUMN public.agent_runtime_model_bindings.reasoning IS
    '受信宿主冻结且经 Provider profile 校验的推理内容协议能力';
