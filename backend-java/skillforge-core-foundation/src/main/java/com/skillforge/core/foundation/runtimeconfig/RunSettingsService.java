package com.skillforge.core.foundation.runtimeconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 每个 Run 冻结完整运行配置；发布变更在下一次独立执行生效。 */
@Service
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class RunSettingsService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RunSettingsService.class);
    private final RunSettingsMapper mapper;
    private final OperationalSettingsService settings;
    private final ObjectMapper json;
    public RunSettingsService(RunSettingsMapper mapper, OperationalSettingsService settings, ObjectMapper json) {
        this.mapper=mapper; this.settings=settings; this.json=json;
    }
    /** 准备 Profile 与预算登记在同一事务形成后才发布完整冻结引用。 */
    public FrozenConfig capture(String tenant, String run, String modelId, long modelRevision,
            AgentExecutionConfig executionConfig,
            java.util.function.Function<CaptureConfig, com.skillforge.runtime.api.ResultRef> prepare) {
        LOG.debug("捕获运行冻结 tenant={} run={} model={} revision={}", tenant, run, modelId, modelRevision);
        bindTenant(tenant);
        String existing = mapper.find(tenant, run);
        if (existing != null) {
            var original = decode(existing);
            LOG.debug("复用运行冻结 tenant={} run={} profile={}", tenant, run, original.preparationProfile());
            return original;
        }
        Map<String, Long> groups = new LinkedHashMap<>();
        Map<String, OperationalSettingsService.Snapshot> snapshots = new LinkedHashMap<>();
        for (String group : List.of("task_budget", "prompts", "calls", "sandbox", "workspace", "artifacts")) {
            var snapshot = settings.read(group);
            groups.put(group, snapshot.revision());
            snapshots.put(group, snapshot);
        }
        // 只在同步准备回调内复用刚读到的值；持久 FrozenConfig 仍仅保存精确修订。
        var source = new CaptureConfig(modelId, modelRevision, groups, executionConfig, snapshots);
        var profile = java.util.Objects.requireNonNull(prepare.apply(source), "preparationProfile");
        var candidate = new FrozenConfig(modelId, modelRevision, groups, null, executionConfig, profile.value());
        mapper.insert(tenant, run, encode(candidate));
        var actual = decode(mapper.find(tenant, run));
        if (!candidate.equals(actual)) throw new IllegalStateException("run_settings_snapshot_mismatch");
        LOG.debug("完成运行冻结 tenant={} run={} profile={} groups={}", tenant, run, profile.value(), groups);
        return actual;
    }
    public OperationalSettingsService.Snapshot read(String tenant,String run,String group) {
        return read(requireFrozen(tenant, run),group);
    }
    /** 动作键由可信宿主生成；重复执行只读取已捕获版本，不能重写历史。 */
    public FrozenConfig captureOperation(String tenant, String run, String operation,
            String modelId, long modelRevision) {
        return captureOperation(tenant, run, operation, modelId, modelRevision, null);
    }

    public java.util.Optional<FrozenConfig> findOperation(String tenant, String run, String operation) {
        bindTenant(tenant);
        return java.util.Optional.ofNullable(mapper.findOperation(tenant, run, operation)).map(this::decode);
    }

    /** 所有执行的新动作登记原 Run 冻结配置，不因动作开始或恢复读取最新版本。 */
    public FrozenConfig captureFrozenOperation(String tenant, String run, String operation, String roleInstructions) {
        LOG.debug("捕获原运行动作冻结 tenant={} run={} operation={}", tenant, run, operation);
        if (operation == null || operation.isBlank()) throw new IllegalArgumentException("operation_key_required");
        FrozenConfig source = requireFrozen(tenant, run);
        FrozenConfig expected = new FrozenConfig(source.modelId(), source.modelRevision(), source.groups(), roleInstructions, source.executionConfig(), source.preparationProfile());
        if (mapper.findOperation(tenant, run, operation) == null) {
            mapper.insertOperation(tenant, run, operation, encode(expected));
        }
        FrozenConfig actual = decode(mapper.findOperation(tenant, run, operation));
        if (!expected.equals(actual)) throw new IllegalStateException("operation_settings_snapshot_mismatch");
        LOG.debug("完成动作冻结 tenant={} run={} operation={} profile={}", tenant, run, operation, actual.preparationProfile());
        return actual;
    }

    public FrozenConfig captureOperation(String tenant, String run, String operation,
            String modelId, long modelRevision, String roleInstructions) {
        bindTenant(tenant);
        if (operation == null || operation.isBlank()) throw new IllegalArgumentException("operation_key_required");
        String existing = mapper.findOperation(tenant, run, operation);
        if (existing != null) return decode(existing);
        FrozenConfig source = requireFrozen(tenant, run);
        // 工具/脚本动作不声明模型；模型动作继承原 Run，不能借新动作切换发布配置。
        mapper.insertOperation(tenant, run, operation, encode(new FrozenConfig(modelId == null ? null : source.modelId(),
                modelId == null ? 0 : source.modelRevision(),
                source.groups(), roleInstructions, source.executionConfig(), source.preparationProfile())));
        return decode(mapper.findOperation(tenant, run, operation));
    }

    /** 工具动作不使用模型；模型引用留空，不借用 Run 初始模型。 */
    public FrozenConfig captureTool(String tenant, String run, String invocation) {
        return captureOperation(tenant, run, "tool:" + invocation, null, 0);
    }
    /** 读取首次受理时保存的完整冻结引用，不回退当前模型或配置。 */
    public FrozenConfig requireFrozen(String tenant, String run) {
        LOG.debug("读取运行冻结 tenant={} run={}", tenant, run);
        bindTenant(tenant);
        String source=mapper.find(tenant,run);
        if(source==null) throw new IllegalStateException("run_settings_snapshot_missing");
        var frozen = decode(source);
        LOG.debug("读取运行冻结完成 tenant={} run={} profile={}", tenant, run, frozen.preparationProfile());
        return frozen;
    }
    public OperationalSettingsService.Snapshot read(FrozenConfig source,String group) {
        Long revision=source.groups().get(group);
        if(revision==null) throw new IllegalStateException("run_settings_group_missing");
        return settings.readRevision(group, revision);
    }
    /** 构建过程只能读取本次事务捕获的修订。 */
    public OperationalSettingsService.Snapshot read(CaptureConfig source, String group) {
        LOG.debug("读取受理配置修订 group={}", group);
        var snapshot = source.snapshots().get(group);
        if (snapshot == null) throw new IllegalStateException("run_settings_group_missing");
        LOG.debug("读取受理配置修订完成 group={} revision={}", group, snapshot.revision());
        return snapshot;
    }
    private void bindTenant(String tenant) {
        String current = com.skillforge.common.context.TenantContext.getTenantId();
        if (tenant == null || tenant.isBlank() || (current != null && !current.equals(tenant)))
            throw new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "tenant_scope_mismatch");
        mapper.bindTenant(tenant);
    }
    private FrozenConfig decode(String value) {
        try{return json.readValue(value,FrozenConfig.class);}
        catch(Exception invalid){throw new IllegalStateException("run_settings_snapshot_invalid");}
    }
    private String encode(FrozenConfig value) {
        try{return json.writeValueAsString(value);}
        catch(Exception invalid){throw new IllegalStateException("run_settings_snapshot_invalid");}
    }
    /**
     * 本次同步构建输入，不持久化也不跨 Run 复用。
     * @param modelId 本次精确模型标识
     * @param modelRevision 本次精确模型修订
     * @param groups 最终持久化的分组修订
     * @param executionConfig 本 Run 的 Agent 执行覆盖
     * @param snapshots 本次已读取且与 groups 完全对应的临时配置值
     */
    public record CaptureConfig(String modelId, long modelRevision, Map<String, Long> groups,
            AgentExecutionConfig executionConfig, Map<String, OperationalSettingsService.Snapshot> snapshots) {
        /** 临时值和持久修订必须一一对应，不能在准备阶段混入另一版配置。 */
        public CaptureConfig {
            groups = Map.copyOf(groups); snapshots = Map.copyOf(snapshots);
            java.util.Objects.requireNonNull(executionConfig);
            if (!groups.keySet().equals(snapshots.keySet()))
                throw new IllegalArgumentException("run_settings_capture_snapshot_mismatch");
            for (var entry : groups.entrySet()) if (entry.getValue().longValue() != snapshots.get(entry.getKey()).revision())
                throw new IllegalArgumentException("run_settings_capture_snapshot_mismatch");
        }
    }
    public record FrozenConfig(String modelId, long modelRevision, Map<String, Long> groups,
            String roleInstructions, AgentExecutionConfig executionConfig, String preparationProfile) {
        public FrozenConfig {
            groups = Map.copyOf(groups);
            java.util.Objects.requireNonNull(executionConfig, "executionConfig");
            if (preparationProfile == null || preparationProfile.isBlank())
                throw new IllegalArgumentException("run_preparation_reference_required");
        }
    }
}
