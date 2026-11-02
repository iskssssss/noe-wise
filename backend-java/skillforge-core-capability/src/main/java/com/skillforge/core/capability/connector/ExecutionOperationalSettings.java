package com.skillforge.core.capability.connector;

import com.skillforge.core.foundation.settings.OperationalSettingsContributor;
import com.skillforge.core.foundation.settings.OperationalSettingsRegistry.GroupDefinition;
import com.skillforge.core.foundation.settings.OperationalSettingsRegistry.InvalidSettingsException;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.bool;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.decimal;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.group;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.integer;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.string;
import java.util.*;
import org.springframework.stereotype.Component;

/** 由所属领域注册运营配置定义和校验，基础存储不认识业务字段。 */
@Component
public final class ExecutionOperationalSettings implements OperationalSettingsContributor {
    @Override
    public List<GroupDefinition> groups() {
        var groups = new ArrayList<GroupDefinition>();
        groups.add(group("sandbox", "代码沙箱", values -> {
                    if (!((String) values.get("memory_limit")).matches("[1-9][0-9]*[kKmMgG]"))
                        throw new InvalidSettingsException("memory_limit 必须是正整数加 k/m/g 单位");
                }, stored -> false,
                bool("enabled", "启用代码执行", false),
                integer("execution_timeout_seconds", "执行超时", "秒", 1, com.skillforge.execution.ExecutionLimits.MAX_TIMEOUT_SECONDS, com.skillforge.execution.ExecutionLimits.DEFAULT_TIMEOUT_SECONDS),
                integer("workspace_retention_hours", "工作区保留时间", "小时", 1, 8_760, 24),
                integer("output_capture_bytes", "输出采集上限", "字节", 1_024, 100_000_000, com.skillforge.execution.ExecutionLimits.OUTPUT_CAPTURE_BYTES),
                integer("max_artifact_files", "单次最大产物文件数", "个", 1, 100_000, com.skillforge.execution.ExecutionLimits.MAX_FILES),
                integer("max_concurrent_executions", "最大并发执行数", "个", 1, 10_000, com.skillforge.execution.ExecutionLimits.CONCURRENT_EXECUTIONS),
                string("memory_limit", "容器内存上限", com.skillforge.execution.ExecutionLimits.MEMORY_LIMIT, false, false, 32),
                decimal("cpu_limit", "容器 CPU 上限", "核", "0.1", "256", com.skillforge.execution.ExecutionLimits.CPU_LIMIT.toPlainString()),
                integer("pids_limit", "容器进程数上限", "个", 16, 1_000_000, com.skillforge.execution.ExecutionLimits.PIDS_LIMIT)));
        groups.add(group("artifacts", "运行产物",
                integer("retention_days", "产物保留时间", "天", 1, 3_650, 7),
                integer("transfer_timeout_seconds", "产物传输超时", "秒", 1, com.skillforge.execution.ExecutionLimits.MAX_TRANSFER_TIMEOUT_SECONDS, com.skillforge.execution.ExecutionLimits.DEFAULT_TRANSFER_TIMEOUT_SECONDS),
                integer("max_files", "单份产物最大文件数", "个", 1, 100_000, com.skillforge.execution.ExecutionLimits.MAX_FILES)));
        return List.copyOf(groups);
    }
}
