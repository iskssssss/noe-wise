-- V9: 机构 LLM 启用开关
-- custom_llm_endpoint / custom_llm_api_key_encrypted / custom_llm_model 列已存在（V1），
-- 补充启用开关，与 embedding_enabled / rerank_enabled 的服务商配置形态一致。
ALTER TABLE public.nw_tenants
    ADD COLUMN custom_llm_enabled boolean DEFAULT false NOT NULL;
