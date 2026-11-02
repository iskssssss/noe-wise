package com.skillforge.core.knowledge;

import com.skillforge.core.foundation.settings.OperationalSettingsContributor;
import com.skillforge.core.foundation.settings.OperationalSettingsRegistry.GroupDefinition;
import com.skillforge.core.foundation.settings.OperationalSettingsRegistry.InvalidSettingsException;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.decimal;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.group;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.integer;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.number;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.select;
import java.util.*;
import org.springframework.stereotype.Component;

/** 由所属领域注册运营配置定义和校验，基础存储不认识业务字段。 */
@Component
public final class KnowledgeSettingsContributor implements OperationalSettingsContributor {
    @Override
    public List<GroupDefinition> groups() {
        var groups = new ArrayList<GroupDefinition>();
        groups.add(group("knowledge", "知识库", values -> {
                    if (number(values, "chunk_overlap").longValueExact() >= number(values, "chunk_size").longValueExact())
                        throw new InvalidSettingsException("chunk_overlap 必须小于 chunk_size");
                }, stored -> stored.keySet().equals(Set.of("chunk_size", "chunk_overlap", "max_upload_mb",
                        "max_chunks_per_document", "default_indexing_mode", "default_retrieval_mode", "default_top_k",
                        "default_score_threshold", "default_vector_weight", "embedding_timeout_seconds", "rerank_timeout_seconds")),
                integer("chunk_size", "默认分段长度", "字符", 1, 100_000, 500),
                integer("chunk_overlap", "默认分段重叠", "字符", 0, 99_999, 50),
                integer("max_upload_mb", "单文件上传上限", "MB", 1, 10_240, 10),
                integer("max_chunks_per_document", "单文档最大分块数", "块", 1, 1_000_000, 2_000),
                select("default_indexing_mode", "默认索引方式", "high_quality",
                        "high_quality", "economical"),
                select("default_retrieval_mode", "默认检索方式", "semantic",
                        "semantic", "full_text", "hybrid"),
                integer("default_top_k", "默认检索条数", "条", 1, 10_000, 5),
                decimal("default_score_threshold", "默认相关度阈值", null, "0", "1", "0.5"),
                decimal("default_vector_weight", "默认向量权重", null, "0", "1", "0.5")));
        return List.copyOf(groups);
    }
}
