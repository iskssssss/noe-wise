package com.skillforge.core.knowledge.integration.runtime;

import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgeBindings;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgePortal;
import com.skillforge.runtime.starter.AgentRuntimePayloadValidation;
import com.skillforge.runtime.codec.spi.KnowledgeDocumentCodec;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 平台知识冻结绑定与门户；链接读取器由产品宿主显式提供。 */
@Configuration(proxyBeanMethods = false)
public class AgentRuntimeKnowledgeConfiguration {
    @Bean
    JdbcKnowledgeBindings agentRuntimeKnowledgeBindings(DataSource source) {
        return new JdbcKnowledgeBindings(source);
    }

    @Bean
    JdbcKnowledgePortal agentRuntimeKnowledgePortal(DataSource source, JdbcKnowledgeBindings bindings,
            Clock clock, AgentRuntimePayloadValidation payloads, JdbcKnowledgePortal.LinkReader links,
            KnowledgeDocumentCodec documents) {
        return JdbcKnowledgePortal.registered(source, bindings,
                bundle -> bundle.items().stream().allMatch(item -> payloads.accepts(item.content())), links, clock, documents);
    }
}
