-- 用量账本注释只描述当前 Runtime 身份和调用点。
COMMENT ON COLUMN public.nw_usage_events.agent_id IS 'Agent ID；可空';
COMMENT ON COLUMN public.nw_usage_events.call_point IS
    '调用点标识；由当前生产调用方写入，用于定位用量事实来源';
