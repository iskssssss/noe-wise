package com.skillforge.institution.artifact;

import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ExecutionBudgetScope;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.session.spi.SessionOutputContract.ClosingPlan;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 成果收尾需求取自冻结配置；候选生成准入与 Session 收尾声明共用同一预算。 */
@Component
public final class ArtifactClosingBudget {
    /** 冻结与发布回执的输入 Token 上界，不能用本次计划回执大小替代。 */
    private static final long RECEIPT_INPUT_UPPER_BOUND = 32_768;
    /** 候选生成前无法承担完整收尾资源或时间时的模型可见拒绝。 */
    public static final String REJECTION_CODE = "OUTPUT_CLOSING_BUDGET_INSUFFICIENT";
    /** 收尾只核验已传到宿主的文件；沙箱生成与传输属于普通工具阶段。 */
    public ClosingPlan plan(RunIdentity run, List<String> facts) {
        long seconds = ArtifactPublicInspection.MAX_SCAN_DURATION.toSeconds();
        return new ClosingPlan(2, 1, Duration.ofSeconds(seconds), RECEIPT_INPUT_UPPER_BOUND, facts);
    }

    /** 传输、解包和公开核验共用一个窗口；核验不是传输结束后另加的超时。 */
    public static PlatformToolExecutionContext deliveryContext(PlatformToolExecutionContext context, Duration configured) {
        Instant end = Instant.now().plus(configured);
        if (context.deadlineAt().isBefore(end)) end = context.deadlineAt();
        var original = context.callContext();
        return new PlatformToolExecutionContext(new PortCallContext(original.runIdentity(), original.identity(),
                new InvocationControl(end, original.control().cancellationSignal())),
                context.invocationId(), context.attemptId(), context.programChild(), context.workflowNode());
    }

    /** 一次冻结的所有文件共享核验窗口，不为每个文件重新取得完整120秒。 */
    public static PlatformToolExecutionContext finalizationContext(PlatformToolExecutionContext context) {
        return deliveryContext(context, ArtifactPublicInspection.MAX_SCAN_DURATION);
    }

    /**
     * 候选生成准入时完整保护模型调用、Tokens、冻结工具与收尾时间；仅确定预算拒绝转为业务反馈。
     * 计量、存储、作用域缺失均保持技术失败，不伪装成预算不足。
     */
    public void admit(RunIdentity run) {
        admit(run, false);
    }

    /**
     * 冻结 handler 已派发，当前模型和工具均已占账；CLOSING 只保护当前核验与随后一次最终模型及回执输入。
     * 必要资源停止按宿主契约先于最后冻结，其已知消耗或 UNKNOWN 预留仍由原账本独立保留。
     */
    public void admitFinalization(RunIdentity run) {
        admit(run, true);
    }

    /** 需求来源由可信宿主调用点确定，阶段选择在预算账本根锁内完成。 */
    private void admit(RunIdentity run, boolean finalization) {
        var binding = ExecutionBudgetScope.current().orElseThrow(() -> new IllegalStateException("output_budget_scope_required"));
        if (!binding.run().equals(run.runRef())) throw new IllegalStateException("output_budget_scope_mismatch");
        var planned = plan(run, List.of());
        var spec = new ExecutionBudgetPort.ClosingSpec(planned.modelCalls(), planned.finalizationCalls(),
                planned.localProcessing().toSeconds(), planned.receiptInputUpperBound(), planned.facts(), Set.of());
        var remaining = finalization ? new ExecutionBudgetPort.ClosingSpec(1, 0,
                planned.localProcessing().toSeconds(), planned.receiptInputUpperBound(), List.of(), Set.of()) : spec;
        try { binding.admitClosing(spec, remaining); }
        catch (ExecutionBudgetPort.ClosingInsufficient rejected) {
            throw new IllegalArgumentException(REJECTION_CODE, rejected);
        }
    }
}
