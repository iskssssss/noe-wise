package com.skillforge.core.workflow.execution;

import com.skillforge.core.workflow.execution.WorkflowHistoryMessage;
import com.skillforge.core.workflow.execution.WorkflowModels.Workflow;
import com.skillforge.core.workflow.execution.node.NodeExecutionContext;
import com.skillforge.core.workflow.execution.node.NodeOutcome;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 显式受控执行边界；持久化、租约、取消及冻结外部能力由调用者提供。 */
public final class ControlledWorkflowExecution {
    private ControlledWorkflowExecution() {}

    public record Request(Workflow workflow, Map<String, Object> config, String userInput,
            String sessionId, Map<String, Object> response, Resume checkpoint,
            String workflowInstanceId, String tenantId, List<WorkflowHistoryMessage> history,
            String executionId, Map<String, Object> userProfile,
            StateMachineEngine.InitialState initialState) {
        public Request {
            Objects.requireNonNull(workflow, "workflow");
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(userProfile, "userProfile");
            history = history == null ? List.of() : List.copyOf(history);
        }
    }

    public record Resume(String nodeId, boolean resumeAtNext, Map<String, Object> state,
            Map<String, Object> extracted, List<Map<String, Object>> messages, Cursor cursor) {
        public Resume {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(cursor, "cursor");
            state = copy(state);
            extracted = copy(extracted);
            messages = messages.stream().map(ControlledWorkflowExecution::copy).toList();
        }

        Map<String, Object> pendingState() {
            return Map.of("node_id", nodeId, "resume_at_next", resumeAtNext,
                    "state", state, "extracted", extracted, "messages", messages);
        }
    }

    public enum Boundary { PROGRESS, WAITING, COMPLETED, LOOP_TERMINATED }

    public enum LoopLimit { NODE_REVISIT, MAX_STEPS }

    /** 私有执行位置不进入业务 state，节点表达式不能重写调用身份。 */
    public record Cursor(Map<String, Integer> visits, String activeNode, int steps,
            com.skillforge.core.workflow.execution.WorkflowModels.WorkflowEdge incomingEdge) {
        public Cursor {
            visits = Map.copyOf(visits);
            if (steps < 0 || visits.entrySet().stream().anyMatch(e -> e.getKey().isBlank() || e.getValue() <= 0)
                    || activeNode != null && !visits.containsKey(activeNode)
                    || incomingEdge != null && activeNode != null && !incomingEdge.to().equals(activeNode))
                throw new IllegalArgumentException("invalid workflow cursor");
        }
        public static Cursor initial() { return new Cursor(Map.of(), null, 0, null); }
    }

    public record Checkpoint(Boundary boundary, String nodeId, String nextNodeId,
            Map<String, Object> state, Map<String, Object> extracted,
            List<Map<String, Object>> messages, Map<String, Object> card, Integer timeoutSeconds,
            java.util.Optional<LoopLimit> loopLimit, java.util.Optional<NodeOutcome.WaitReason> waitReason, Cursor cursor) {
        public Checkpoint {
            Objects.requireNonNull(boundary, "boundary");
            Objects.requireNonNull(loopLimit, "loopLimit");
            Objects.requireNonNull(waitReason, "waitReason");
            Objects.requireNonNull(cursor, "cursor");
            if ((boundary == Boundary.WAITING) != waitReason.isPresent())
                throw new IllegalArgumentException("只有 WAITING checkpoint 必须含 waitReason");
            if ((boundary == Boundary.LOOP_TERMINATED) != loopLimit.isPresent()) {
                throw new IllegalArgumentException("loopLimit is required exactly for LOOP_TERMINATED");
            }
            state = copy(state);
            extracted = copy(extracted);
            messages = messages.stream().map(ControlledWorkflowExecution::copy).toList();
            card = card == null ? null : copy(card);
        }
    }

    public interface Hooks {
        /** 每个节点前校验租约与取消；抛出异常立即停止。 */
        void beforeNode(String nodeId);

        /** 必须显式执行冻结能力；禁止回退旧执行器或查找当前子图。 */
        default NodeOutcome executeExternalNode(NodeExecutionContext context) {
            throw new IllegalStateException("Controlled external node is not configured: " + context.node().type());
        }

        /** 节点后再次校验控制权并原子持久化；失败不得推进下一个节点。 */
        void checkpoint(Checkpoint checkpoint);
    }

    private static Map<String, Object> copy(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
