package com.skillforge.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import org.junit.jupiter.api.Test;

class KnowledgeProviderConfigServiceTest {

    private final PlatformModelCatalogService catalog = mock(PlatformModelCatalogService.class);
    private final KnowledgeProviderConfigService service = new KnowledgeProviderConfigService(catalog);

    @Test
    void resolvesTenantDefaultsOnlyThroughPlatformCatalog() {
        PlatformModelConfig embedding = model("81", 3, "EMBEDDING", 768, null);
        PlatformModelConfig rerank = model("82", 2, "RERANK", null, "/rerank");
        when(catalog.resolveDefault("7", "EMBEDDING")).thenReturn(embedding);
        when(catalog.resolveDefault("7", "RERANK")).thenReturn(rerank);

        assertThat(service.embedding("7")).isSameAs(embedding);
        assertThat(service.rerank("7")).isSameAs(rerank);
    }

    @Test
    void readsFrozenEmbeddingRevisionAndRejectsWrongKind() {
        PlatformModelConfig embedding = model("81", 3, "EMBEDDING", 768, null);
        when(catalog.readRevision("81", 3)).thenReturn(embedding);

        assertThat(service.embedding("81", 3)).isSameAs(embedding);
        verify(catalog).readRevision("81", 3);

        when(catalog.readRevision("82", 1))
                .thenReturn(model("82", 1, "RERANK", null, "/rerank"));
        assertThatThrownBy(() -> service.embedding("82", 1))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("platform_model_kind_mismatch");
    }

    private PlatformModelConfig model(
            String id, long revision, String kind, Integer dimensions, String path) {
        return new PlatformModelConfig(id, revision, kind, kind, true,
                "provider", null, null, null, "bearer-api-key", null,
                "https://model.example/v1", "model", "encrypted",
                null, null, null, null, null, 20, dimensions, path,
                null, null, null, "CNY");
    }
}
