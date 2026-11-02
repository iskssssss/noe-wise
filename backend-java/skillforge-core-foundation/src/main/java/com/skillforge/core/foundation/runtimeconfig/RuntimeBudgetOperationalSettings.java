package com.skillforge.core.foundation.runtimeconfig;

import com.skillforge.core.foundation.settings.OperationalSettingsContributor;
import com.skillforge.core.foundation.settings.OperationalSettingsService.DerivedBudget;
import com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot;
import com.skillforge.core.foundation.settings.OperationalSettingsRegistry.GroupDefinition;
import com.skillforge.core.foundation.settings.OperationalSettingsRegistry.FieldDefinition;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** 由所属领域注册运营配置定义和校验，基础存储不认识业务字段。 */
@Component
public final class RuntimeBudgetOperationalSettings implements OperationalSettingsContributor {
    @Override
    public List<GroupDefinition> groups() {
        var groups = new ArrayList<GroupDefinition>();
        var budgetFields = new ArrayList<FieldDefinition>();
        for (var profile : com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings.Profile.values()) {
            for (var dimension : com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings.editableDimensions())
                budgetFields.add(integer(profile.field(dimension), profile.label()+" "+switch(dimension){case BUSINESS_MODELS->"业务模型次数";case TOOLS->"工具次数";case KNOWLEDGE->"知识次数";default->"累计总 Tokens";},
                        dimension.name().endsWith("TOKENS") ? "token" : "次",1,dimension.name().endsWith("TOKENS")?Long.MAX_VALUE:Integer.MAX_VALUE,profile.defaults().value(dimension)));
            if (profile.activeSeconds()>0) budgetFields.add(integer(profile.activeKey(),profile.label()+" 活跃执行时长","秒",
                    1,com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings.MAX_ACTIVE_SECONDS,profile.activeSeconds()));
        }
        groups.add(group(com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings.GROUP,"分层任务预算",budgetFields.toArray(FieldDefinition[]::new)).withDerived(RuntimeBudgetOperationalSettings::derived));
        return List.copyOf(groups);
    }
    /** 由后端计算只读派生说明，前端不维护比例算法。 */
    private static List<DerivedBudget> derived(Snapshot snapshot) {
        var result = new ArrayList<DerivedBudget>();
        for (var scenario : List.of(com.skillforge.runtime.api.port.ExecutionBudgetPort.Scenario.STANDARD,
                com.skillforge.runtime.api.port.ExecutionBudgetPort.Scenario.DELEGATION,
                com.skillforge.runtime.api.port.ExecutionBudgetPort.Scenario.TEAM)) {
            for (var role : com.skillforge.runtime.api.port.ExecutionBudgetPort.Role.values()) {
                var limits = com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings.freeze(snapshot, scenario, role,
                        com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig.inherited());
                var run = limits.run();
                result.add(new DerivedBudget(scenario.name(), role.name(), run.businessModels(), run.auxiliaryModels(),
                        run.tools(), run.knowledge(), run.totalTokens(), limits.runActiveSeconds()));
            }
        }
        return List.copyOf(result);
    }

}
