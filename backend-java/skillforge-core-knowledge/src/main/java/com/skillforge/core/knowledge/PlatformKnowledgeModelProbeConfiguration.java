package com.skillforge.core.knowledge;

import com.skillforge.core.model.embedding.PlatformModelHttpSupport;

import com.skillforge.core.foundation.application.CryptoService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 向平台宿主显式导出知识模型连接探测能力，不扩大组件扫描范围。 */
@Configuration(proxyBeanMethods = false)
public class PlatformKnowledgeModelProbeConfiguration {

    @Bean
    PlatformModelHttpSupport platformModelHttpSupport(CryptoService cryptoService) {
        return new PlatformModelHttpSupport(cryptoService);
    }

    @Bean
    PlatformKnowledgeModelProbe platformKnowledgeModelProbe(PlatformModelHttpSupport http) {
        return new PlatformKnowledgeModelProbe(http);
    }
}
