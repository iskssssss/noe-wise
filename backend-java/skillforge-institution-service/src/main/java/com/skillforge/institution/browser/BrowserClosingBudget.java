package com.skillforge.institution.browser;

import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy;
import com.skillforge.runtime.session.spi.SessionOutputContract.ClosingPlan;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 浏览器宿主按 Run 的可信冻结挂载保护停止资源，不查询页面或自动清理任务。 */
@Component
public final class BrowserClosingBudget {
    /** 停止工具有界公开回执及 Runtime 封装的保守输入 Token 上界。 */
    static final long RECEIPT_INPUT_UPPER_BOUND = 32_768;
    private final SessionToolDirectory tools;

    /** 目录负责原 Run 挂载、执行 Agent、部署与当前授权的核验。 */
    public BrowserClosingBudget(SessionToolDirectory tools) {
        this.tools = Objects.requireNonNull(tools);
    }

    /** 只认平台自身的停止 handler，发现名称或第三方同名能力不能取得收尾资格。 */
    public static boolean stopBinding(ToolBinding binding) {
        return binding.providerType() == ToolBinding.ProviderType.PLATFORM
                && "browser_stop".equals(binding.providerRef()) && "browser_stop".equals(binding.toolName());
    }

    /**
     * 在启动之前按原冻结挂载保护一次停止、其回执和后续模型；无停止挂载不增加预留。
     * 停止须先于成果最后冻结；已有任务引用和停止结果仍由实际工具回执证明。
     */
    public ClosingPlan extend(RunIdentity run, ClosingPlan outputs) {
        return extendFrozen(tools.list(run),outputs);
    }

    /** 新执行链只消费当前Run的原冻结目录，不在模型准备时重新发布能力或采用当前挂载。 */
    public ClosingPlan extendFrozen(java.util.List<SessionToolDirectory.Entry> frozen,ClosingPlan outputs) {
        Duration timeout = frozen.stream()
                .filter(entry -> stopBinding(entry.binding()))
                .filter(entry -> entry.target().modelInvocable()
                        && entry.target().confirmation() == ToolConfirmationPolicy.NEVER)
                .map(entry -> entry.target().timeout()).max(Duration::compareTo).orElse(null);
        if (timeout == null) return outputs;
        // 多挂载也只保护一次停止；取最大冻结超时，不能用当前默认值低估原目标。
        long seconds = Math.addExact(timeout.getSeconds(), timeout.getNano() == 0 ? 0 : 1);
        var facts = new ArrayList<>(outputs.facts());
        facts.add("原 Run 已挂载平台 browser_stop，宿主已预留一次既有任务停止及回执处理；"
                + "仅对本次已确认的准确 task，在成果最后冻结和最终答复之前停止。没有已启动任务则不调用；"
                + "不得为收尾新建 browser_start 或用 browser_call 扩展业务，停止失败或 UNKNOWN 如实保留，不能声称已结束或重放未知停止。");
        // 停止回执在其后的每一轮模型中均可能再次出现，不能只为两工具回执各计一次。
        long receiptInput = Math.addExact(outputs.receiptInputUpperBound(),
                Math.multiplyExact(RECEIPT_INPUT_UPPER_BOUND, outputs.modelCalls()));
        return new ClosingPlan(Math.addExact(outputs.modelCalls(), 1), Math.addExact(outputs.finalizationCalls(), 1),
                outputs.localProcessing().plusSeconds(seconds), receiptInput, facts);
    }
}
