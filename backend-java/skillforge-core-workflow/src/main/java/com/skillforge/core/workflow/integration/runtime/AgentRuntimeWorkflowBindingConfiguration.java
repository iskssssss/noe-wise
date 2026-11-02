package com.skillforge.core.workflow.integration.runtime;

import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 平台 Workflow 定义与实例的精确冻结绑定装配。 */
@Configuration(proxyBeanMethods = false)
public class AgentRuntimeWorkflowBindingConfiguration {
    @Bean
    JdbcWorkflowBindings agentRuntimeWorkflowBindings(DataSource source) {
        return new JdbcWorkflowBindings(source);
    }
}
