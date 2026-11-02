package com.skillforge.platform.models.media.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.media.ModelMediaAssetStore;
import com.skillforge.core.model.media.realtime.ModelRealtimeMapper;
import com.skillforge.core.model.media.realtime.ModelRealtimeService;
import com.skillforge.runtime.adapter.model.media.BailianRealtimeTranscription;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProviders;
import java.net.http.HttpClient;
import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** 实时入口独立装配；构造 Bean 不连接供应商，正文复用已配置的 E 盘受控媒体存储。 */
@Configuration
@MapperScan(basePackageClasses = ModelRealtimeMapper.class, annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class PlatformModelRealtimeConfiguration {
    @Bean(destroyMethod = "close")
    ModelRealtimeService modelRealtimeService(PlatformModelCatalogService models, CryptoService crypto,
            ModelRealtimeMapper mapper, ModelMediaAssetStore assets, PlatformTransactionManager manager, ObjectMapper json,
            @Qualifier("modelMediaHttpClient") HttpClient http) {
        var clock = Clock.systemUTC();
        return new ModelRealtimeService(models, crypto, mapper, assets,
                new BailianRealtimeTranscription(http, clock, 1048576),
                new DomesticMediaProviders(http, clock, 16 * 1024 * 1024), manager, json, clock);
    }
}
