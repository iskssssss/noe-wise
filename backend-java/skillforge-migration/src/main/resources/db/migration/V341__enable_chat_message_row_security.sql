-- V4 已定义 FORCE RLS 和租户读写策略；同时启用行安全使既有策略实际生效。
ALTER TABLE public.nw_chat_messages ENABLE ROW LEVEL SECURITY;
