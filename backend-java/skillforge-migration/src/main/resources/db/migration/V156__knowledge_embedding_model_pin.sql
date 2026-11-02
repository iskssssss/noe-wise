-- 知识库向量索引固定到建库时的平台 Embedding 模型版本与维度。
ALTER TABLE nw_knowledge_bases
    ADD COLUMN embedding_model_config_id bigint NOT NULL,
    ADD COLUMN embedding_model_revision bigint NOT NULL CHECK (embedding_model_revision > 0),
    ADD COLUMN embedding_dimensions integer NOT NULL CHECK (embedding_dimensions > 0),
    ADD CONSTRAINT skillforge_knowledge_bases_embedding_model_version_fk
        FOREIGN KEY (embedding_model_config_id, embedding_model_revision)
        REFERENCES nw_platform_model_versions(model_id, revision);

COMMENT ON COLUMN nw_knowledge_bases.embedding_model_config_id IS
    '建库时冻结的平台Embedding模型标识；机构默认变更不影响现有索引';
COMMENT ON COLUMN nw_knowledge_bases.embedding_model_revision IS
    '索引与查询共同使用的平台Embedding不可变版本';
COMMENT ON COLUMN nw_knowledge_bases.embedding_dimensions IS
    '冻结模型的向量维度，用于拒绝不一致向量';

CREATE INDEX skillforge_knowledge_bases_embedding_model_version_idx
    ON nw_knowledge_bases(embedding_model_config_id, embedding_model_revision);
COMMENT ON INDEX skillforge_knowledge_bases_embedding_model_version_idx IS
    '按平台Embedding模型版本定位知识库索引';
