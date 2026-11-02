package com.skillforge.core.foundation.runtimeconfig;

import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 宿主辅助模型与 Runtime 共用同一个持久资源端口。 */
@Configuration(proxyBeanMethods = false)
public class ExecutionBudgetConfiguration {
    @Bean @ConditionalOnMissingBean(ExecutionBudgetPort.class)
    ExecutionBudgetPort executionBudgets(DataSource source) { return new JdbcExecutionBudgets(source); }
}
