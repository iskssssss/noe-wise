package com.skillforge.institution.runtime;

import com.skillforge.runtime.starter.AgentRuntimeMemorySearchBindings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 所有 Memory 检索消费者共用宿主注册的正文和显式策略。 */
@Configuration
@EnableConfigurationProperties(MemorySearchProperties.class)
class MemorySearchConfiguration {
    @Bean
    AgentRuntimeMemorySearchBindings memorySearchBindings(AgentRuntimeMemoryTypeRegistry types,
            MemorySearchProperties settings,MemorySearchEmbeddingBridge embedding) {
        return new AgentRuntimeMemorySearchBindings(types::project,tenant->settings.freeze(),embedding);
    }
}
