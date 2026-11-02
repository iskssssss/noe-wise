-- V6: 机构级 Embedding 与 Rerank 服务配置。

ALTER TABLE public.nw_tenants
    ADD COLUMN embedding_provider          character varying(32),
    ADD COLUMN embedding_base_url          character varying(512),
    ADD COLUMN embedding_api_key_encrypted text,
    ADD COLUMN embedding_model             character varying(128),
    ADD COLUMN embedding_dimensions        integer,
    ADD COLUMN embedding_enabled           boolean DEFAULT false NOT NULL;

ALTER TABLE public.nw_tenants
    ADD COLUMN rerank_provider             character varying(32),
    ADD COLUMN rerank_base_url             character varying(512),
    ADD COLUMN rerank_api_key_encrypted    text,
    ADD COLUMN rerank_model                character varying(128),
    ADD COLUMN rerank_path                 character varying(128) DEFAULT '/rerank',
    ADD COLUMN rerank_enabled              boolean DEFAULT false NOT NULL;
