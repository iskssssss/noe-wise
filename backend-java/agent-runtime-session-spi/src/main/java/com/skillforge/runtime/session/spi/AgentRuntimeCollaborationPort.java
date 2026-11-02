package com.skillforge.runtime.session.spi;

import com.skillforge.runtime.api.SessionQuery;
import com.skillforge.runtime.api.RunQuery;
import java.util.List;

/** 会话协作状态的授权入口；不改变工具权限、审批策略或 Run 执行算法。 */
public interface AgentRuntimeCollaborationPort {
    /** 受信宿主提交人工协助；只追加原产品会话事实，不调度模型或新 Run。 */
    default HumanReceipt appendHumanAssistance(SessionQuery query, HumanAssistance command) {
        throw new UnsupportedOperationException("runtime_human_assistance_unavailable");
    }
    record HumanReceipt(String sourceEventId, long sessionSequence) { }
    record HumanAssistance(String commandId, String ticketId, String operatorId, String operatorName, String text) {
        public HumanAssistance {
            for (String value : List.of(commandId, ticketId, operatorId, operatorName, text))
                if (value.isBlank()) throw new IllegalArgumentException("human_assistance_input_invalid");
            if(commandId.length()>200 || ticketId.length()>200 || operatorId.length()>200 || operatorName.length()>120 || text.length()>8192)
                throw new IllegalArgumentException("human_assistance_input_invalid");
        }
    }
    Snapshot collaboration(SessionQuery query);
    Snapshot selectPlan(SessionQuery query, String commandId, boolean active);
    SteeringSnapshot steer(RunQuery query, String commandId, String text);
    SteeringSnapshot steering(RunQuery query);

    /** 回执表示已保存；INCLUDED 表示已进入冻结请求，CONSUMED 表示该请求的模型结果已消费。 */
    enum SteeringStatus { PENDING, INCLUDED, CONSUMED }
    record SteeringItem(String commandId, String text, SteeringStatus status) { }
    record SteeringSnapshot(String runId, boolean accepting, List<SteeringItem> items) {
        public SteeringSnapshot { items = List.copyOf(items); }
    }

    record Snapshot(boolean active, boolean pending, boolean selectedActive) { }
}
