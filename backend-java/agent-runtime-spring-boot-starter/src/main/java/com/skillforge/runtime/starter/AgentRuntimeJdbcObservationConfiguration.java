package com.skillforge.runtime.starter;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** JDBC 计数仅在显式诊断时装配，日常运行不代理连接。 */
@AutoConfiguration(before = AgentRuntimeAutoConfiguration.class)
@ConditionalOnProperty(prefix = "agent-runtime", name = "jdbc-observation-enabled", havingValue = "true")
public class AgentRuntimeJdbcObservationConfiguration {
    @Bean
    static BeanPostProcessor agentRuntimeJdbcObservationPostProcessor() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof DataSource source ? AgentRuntimeJdbcObservation.observe(source) : bean;
            }
        };
    }
}
