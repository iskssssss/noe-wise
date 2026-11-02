package com.skillforge.runtime.starter;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent Runtime 通用基础装配的静态边界配置。 */
@ConfigurationProperties("agent-runtime")
public final class AgentRuntimeProperties {
    /** 普通终态展示历史保留期，关闭且无保护引用后才回收；不改变幂等或恢复期限。 */
    private Duration historyRetention = Duration.ofDays(30);
    private boolean jdbcObservationEnabled;
    private int maxPayloadCodeUnits = 8 * 1024 * 1024;
    private int maxPayloadNodes = 100_000;
    private int maxPayloadDepth = 128;
    private final Workers workers = new Workers();
    private final Memory memory = new Memory();
    private String workerId = "agent-runtime-worker";
    private Duration workerLeaseDuration = Duration.ofSeconds(30);
    private int httpMaximumResponseBytes = 1_000_000;
    // SSE 小增量包含重复协议字段；模型传输预算独立于工具 HTTP 和解码后 payload 预算。
    private int modelMaximumResponseBytes = 16_000_000;

    public boolean isJdbcObservationEnabled() { return jdbcObservationEnabled; }
    public void setJdbcObservationEnabled(boolean value) { jdbcObservationEnabled = value; }
    public Duration getHistoryRetention() { return historyRetention; }
    public void setHistoryRetention(Duration value) { historyRetention = value; }
    public int getMaxPayloadCodeUnits() { return maxPayloadCodeUnits; }
    public void setMaxPayloadCodeUnits(int maxPayloadCodeUnits) { this.maxPayloadCodeUnits = maxPayloadCodeUnits; }
    public int getMaxPayloadNodes() { return maxPayloadNodes; }
    public void setMaxPayloadNodes(int maxPayloadNodes) { this.maxPayloadNodes = maxPayloadNodes; }
    public int getMaxPayloadDepth() { return maxPayloadDepth; }
    public void setMaxPayloadDepth(int maxPayloadDepth) { this.maxPayloadDepth = maxPayloadDepth; }
    public Workers getWorkers() { return workers; }
    public Memory getMemory() { return memory; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public Duration getWorkerLeaseDuration() { return workerLeaseDuration; }
    public void setWorkerLeaseDuration(Duration workerLeaseDuration) { this.workerLeaseDuration = workerLeaseDuration; }
    public int getHttpMaximumResponseBytes() { return httpMaximumResponseBytes; }
    public int getModelMaximumResponseBytes() { return modelMaximumResponseBytes; }
    public void setModelMaximumResponseBytes(int value) { modelMaximumResponseBytes = value; }
    public void setHttpMaximumResponseBytes(int httpMaximumResponseBytes) {
        this.httpMaximumResponseBytes = httpMaximumResponseBytes;
    }

    void validate() {
        requirePositive(historyRetention, "history-retention");
        if (maxPayloadCodeUnits <= 0 || maxPayloadNodes <= 0 || maxPayloadDepth <= 0
                || httpMaximumResponseBytes <= 0 || modelMaximumResponseBytes <= 0) {
            throw new IllegalStateException("agent-runtime numeric limits must be positive");
        }
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalStateException("agent-runtime.worker-id must not be blank");
        }
        requirePositive(workerLeaseDuration, "worker-lease-duration");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("agent-runtime." + name + " must be positive");
        }
    }

    /** 记忆能力默认启用；只消费其他能力的宿主可显式关闭整组记忆装配。 */
    public static final class Memory {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** Worker 生命周期；auto-startup 必须显式绑定，不能靠扁平别名误关。 */
    public static final class Workers {
        private boolean autoStartup = true;

        public boolean isAutoStartup() { return autoStartup; }
        public void setAutoStartup(boolean autoStartup) { this.autoStartup = autoStartup; }
    }

}
