package com.skillforge.core.model.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.media.ModelMediaAssetStore;
import com.skillforge.core.model.media.ModelMediaJobMapper;
import com.skillforge.core.model.media.ModelMediaJobService;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProviders;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** 两宿主共用的媒体任务与正文装配，不在构造时执行供应商任务。 */
@Configuration
public class ModelMediaConfiguration {
    @Bean
    ModelMediaAssetStore modelMediaAssetStore(@Qualifier("modelMediaHttpClient") HttpClient http,
            @Value("${skillforge.model-media.root:${SKILLFORGE_MODEL_MEDIA_ROOT:./data/model-media}}") String root,
            @Value("${skillforge.model-media.maximum-asset-bytes:209715200}") long maximumBytes) {
        if (root.isBlank()) throw new IllegalArgumentException("model_media_storage_root_required");
        return new FileModelMediaAssetStore(http, Path.of(root), maximumBytes);
    }

    @Bean
    ModelMediaJobService modelMediaJobService(PlatformModelCatalogService models, CryptoService crypto,
            ModelMediaJobMapper mapper, ModelMediaAssetStore assets, PlatformTransactionManager manager,
            ObjectMapper json, @Qualifier("modelMediaHttpClient") HttpClient http,
            ExternalInvocationLedger invocations, CostRecorder costs) {
        var providers = new DomesticMediaProviders(http, Clock.systemUTC(), 16 * 1024 * 1024);
        return new ModelMediaJobService(models, crypto, mapper, assets, providers, manager, json, invocations, costs);
    }

    @Bean(destroyMethod = "close")
    HttpClient modelMediaHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
}
