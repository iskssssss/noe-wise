package com.skillforge.core.knowledge;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 知识库模型配置门面，只从平台目录解析机构选择或索引冻结版本。 */
@Service
public class KnowledgeProviderConfigService {

    private final PlatformModelCatalogService modelCatalog;

    public KnowledgeProviderConfigService(PlatformModelCatalogService modelCatalog) {
        this.modelCatalog = modelCatalog;
    }

    /** 解析机构当前选择的已启用 Embedding 模型。 */
    public PlatformModelConfig embedding(String tenantId) {
        return modelCatalog.resolveDefault(tenantId, "EMBEDDING");
    }

    /** 读取知识库索引冻结的 Embedding 模型版本。 */
    public PlatformModelConfig embedding(String modelConfigId, long revision) {
        PlatformModelConfig config = modelCatalog.readRevision(modelConfigId, revision);
        return requireKind(config, "EMBEDDING");
    }

    /** 解析机构当前选择的已启用 Rerank 模型。 */
    public PlatformModelConfig rerank(String tenantId) {
        return modelCatalog.resolveDefault(tenantId, "RERANK");
    }

    private static PlatformModelConfig requireKind(PlatformModelConfig config, String kind) {
        if (config == null || !kind.equals(config.kind())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "platform_model_kind_mismatch");
        }
        return config;
    }
}
