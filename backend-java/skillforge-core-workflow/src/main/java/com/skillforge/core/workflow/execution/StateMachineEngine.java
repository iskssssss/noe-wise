package com.skillforge.core.workflow.execution;

import com.skillforge.core.shared.constant.Messages;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.identity.EndUserIdentity;
import com.skillforge.core.workflow.execution.ControlledWorkflowExecution.Boundary;
import com.skillforge.core.workflow.execution.ControlledWorkflowExecution.Hooks;
import com.skillforge.core.workflow.execution.ControlledWorkflowExecution.LoopLimit;
import com.skillforge.core.workflow.execution.ControlledWorkflowExecution.Request;
import com.skillforge.core.workflow.execution.WorkflowModels.Workflow;
import com.skillforge.core.workflow.execution.WorkflowModels.WorkflowEdge;
import com.skillforge.core.workflow.execution.WorkflowModels.WorkflowNode;
import com.skillforge.core.workflow.execution.node.AssignNodeExecutor;
import com.skillforge.core.workflow.execution.node.CardNodeExecutor;
import com.skillforge.core.workflow.execution.node.HumanInputNodeExecutor;
import com.skillforge.core.workflow.execution.node.MenuNodeExecutor;
import com.skillforge.core.workflow.execution.node.NodeExecutionContext;
import com.skillforge.core.workflow.execution.node.NodeOutcome;
import com.skillforge.core.workflow.execution.node.OutputNodeExecutor;
import com.skillforge.core.workflow.execution.node.WorkflowExpressions;
import com.skillforge.core.workflow.execution.node.WorkflowNodeExecutor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 新 Runtime 专用的受控工作流状态机。
 *
 * <p>本类只负责确定性的状态推进与纯节点执行。所有外部能力、租约、取消和持久化
 * 都由 {@link Hooks} 提供；不存在旧执行器、旧 checkpoint 或旧运行时表的回退路径。</p>
 */
@Service
public class StateMachineEngine {
    private static final Logger log = LoggerFactory.getLogger(StateMachineEngine.class);
    private static final int MAX_STEPS = 100;
    private static final String ACTION_VISITS_STATE_KEY = "__workflow_action_visits";

    @Value("${skillforge.workflow.max-node-revisits:6}")
    private int maxNodeRevisits = 6;

    private final Map<String, WorkflowNodeExecutor> pureExecutors = Map.of(
            "assign", new AssignNodeExecutor(),
            "output", new OutputNodeExecutor(),
            "card", new CardNodeExecutor(),
            "menu", new MenuNodeExecutor(),
            "human_input", new HumanInputNodeExecutor());

    /** 会话级工作流初始状态。 */
    public record InitialState(Map<String, Object> state, Map<String, Object> extracted) {}

    /** 使用唯一受控边界执行工作流。 */
    public Map<String, Object> runControlled(Request request, Hooks hooks) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(hooks, "hooks");

        Workflow workflow = request.workflow();
        Map<String, Object> state = new LinkedHashMap<>();
        Map<String, Object> extracted = new LinkedHashMap<>();
        if (request.initialState() != null) {
            if (request.initialState().state() != null) state.putAll(request.initialState().state());
            if (request.initialState().extracted() != null) extracted.putAll(request.initialState().extracted());
        }
        state.put("user_input", request.userInput());
        List<Map<String, Object>> messages = new ArrayList<>();
        String current = workflow.entry();
        boolean resume = false;
        if (request.checkpoint() != null) {
            resume = true;
            current = request.checkpoint().nodeId();
            state.putAll(request.checkpoint().state());
            extracted.putAll(request.checkpoint().extracted());
            messages.addAll(request.checkpoint().messages());
        }
        Map<String, Integer> actionVisits = restoreActionVisits(state);
        if (resume && request.response() != null) {
            extracted.put("action", String.valueOf(request.response().getOrDefault("action", "confirm")));
        }
        state.put("user_input", request.userInput());
        boolean skipResumeNode = resume && !request.checkpoint().resumeAtNext();
        ExecutionCursor cursor = new ExecutionCursor(resume ? request.checkpoint().cursor() : ControlledWorkflowExecution.Cursor.initial());
        Map<String, Object> extraVars = buildExtraVars(request.userProfile(), request.sessionId(),
                request.executionId(), request.workflowInstanceId(), actionVisits);
        WorkflowEdge incomingEdge = cursor.incomingEdge;
        if(incomingEdge!=null&&!workflow.edges().contains(incomingEdge))throw new IllegalArgumentException("workflow_incoming_edge_mismatch");
        String lastNode = null;

        for (int step = 0; step < MAX_STEPS && current != null; step++) {
            lastNode = current;
            if (!cursor.enter(current)) break;
            hooks.beforeNode(current);
            WorkflowNode node = findNode(workflow, current);
            if (node == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "工作流节点不存在: " + current);
            }
            NodeOutcome outcome = null;
            if (skipResumeNode) {
                skipResumeNode = false;
                if ("human_input".equals(node.type()) && request.response() != null) {
                    // 恢复身份已由 Portal 校验；在跳过等待节点前保存原始响应，供后续节点和检查点使用。
                    writeHumanResponse(node, request.response(), state, extracted);
                }
                if ("menu".equals(node.type()) && request.response() != null) {
                    String target = actionTarget(node,
                            String.valueOf(request.response().getOrDefault("action", "")));
                    if (target != null) {
                        current = target;
                        incomingEdge = null;
                        cursor.complete(null);
                        checkpoint(hooks, Boundary.PROGRESS, node.id(), current, state, actionVisits, cursor,
                                extracted, messages, null, null, java.util.Optional.empty());
                        continue;
                    }
                }
            } else if ("start".equals(node.type()) || "end".equals(node.type()) || "condition".equals(node.type())) {
                if ("end".equals(node.type())) current = null;
                else {
                    incomingEdge = nextEdge(workflow, node, state, extracted, request.config(), extraVars);
                    current = incomingEdge == null ? null : incomingEdge.to();
                }
                cursor.complete(incomingEdge);
                if (current != null) {
                    checkpoint(hooks, Boundary.PROGRESS, node.id(), current, state, actionVisits, cursor,
                            extracted, messages, null, null, java.util.Optional.empty());
                }
                continue;
            } else {
                int visits = cursor.visits.get(current);
                if (visits > maxNodeRevisits) {
                    log.warn("工作流节点访问超限终止 workflow={} node={} visits={}", workflow.key(), current, visits);
                    messages.add(Map.of("role", "assistant", "content", Messages.WORKFLOW_LOOP_GUIDE));
                    checkpoint(hooks, Boundary.LOOP_TERMINATED, current, null, state, actionVisits, cursor,
                            extracted, messages, null, null, java.util.Optional.of(LoopLimit.NODE_REVISIT));
                    return result(WorkflowExecutionStatus.LOOP_TERMINATED.code(), request.sessionId(), state,
                            extracted, messages, null, null);
                }
                NodeExecutionContext context = new NodeExecutionContext(
                        workflow, node, request.config(), state, extracted, request.userInput(), messages,
                        request.workflowInstanceId(), request.tenantId(), request.history(),
                        request.executionId(), null, request.sessionId(), extraVars, incomingEdge,
                        new com.skillforge.core.workflow.execution.node.NodeCallIdentity(request.executionId(), current, visits, 1));
                WorkflowNodeExecutor pure = pureExecutors.get(node.type());
                if (pure == null) {
                    // 原节点访问在外调前持久化；崩溃恢复继续同一访问，不重新生成调用身份。
                    checkpoint(hooks, Boundary.PROGRESS, current, current, state, actionVisits, cursor,
                            extracted, messages, null, null, java.util.Optional.empty());
                    outcome = Objects.requireNonNull(hooks.executeExternalNode(context), "external node outcome");
                } else outcome = pure.execute(context);
                if (outcome.pending()) {
                    Map<String, Object> savedState = stateForCheckpoint(state, actionVisits);
                    Map<String, Object> pending = new LinkedHashMap<>();
                    pending.put("node_id", current);
                    pending.put("node_type", node.type());
                    pending.put("state", savedState);
                    pending.put("extracted", extracted);
                    pending.put("messages", messages);
                    if (node.timeoutSeconds() != null) pending.put("timeout_seconds", node.timeoutSeconds());
                    hooks.checkpoint(new ControlledWorkflowExecution.Checkpoint(Boundary.WAITING, current, current,
                            savedState, extracted, messages, outcome.card(), node.timeoutSeconds(), java.util.Optional.empty(), outcome.waiting(), cursor.snapshot()));
                    return result(outcome.waiting().orElseThrow() == NodeOutcome.WaitReason.HUMAN_INPUT ? WorkflowExecutionStatus.WAITING_HITL.code() : WorkflowExecutionStatus.WAITING_EXTERNAL.code(), request.sessionId(), state, extracted, messages,
                            outcome.card(), pending);
                }
            }

            if (outcome != null && outcome.gotoNode() != null) {
                incomingEdge = null;
                current = outcome.gotoNode();
            } else {
                incomingEdge = nextEdge(workflow, node, state, extracted, request.config(), extraVars);
                current = incomingEdge == null ? null : incomingEdge.to();
            }
            cursor.complete(incomingEdge);
            if (current != null) {
                checkpoint(hooks, Boundary.PROGRESS, node.id(), current, state, actionVisits, cursor,
                        extracted, messages, null, null, java.util.Optional.empty());
            }
        }

        boolean completed = current == null;
        checkpoint(hooks, completed ? Boundary.COMPLETED : Boundary.LOOP_TERMINATED,
                lastNode, null, state, actionVisits, cursor, extracted, messages, null, null,
                completed ? java.util.Optional.empty() : java.util.Optional.of(LoopLimit.MAX_STEPS));
        return result(completed ? WorkflowExecutionStatus.COMPLETED.code() : WorkflowExecutionStatus.LOOP_TERMINATED.code(),
                request.sessionId(), state, extracted, messages, null, null);
    }

    private Map<String, Object> result(String status, String sessionId, Map<String, Object> state,
            Map<String, Object> extracted, List<Map<String, Object>> messages,
            Map<String, Object> card, Map<String, Object> pending) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("session_id", sessionId);
        result.put("messages", messages);
        result.put("output", lastMessageContent(messages));
        result.put("card", card);
        if (pending != null) result.put("pending", pending);
        result.put("state", state);
        result.put("extracted", extracted);
        return result;
    }

    private static void writeHumanResponse(WorkflowNode node, Map<String, Object> response,
            Map<String, Object> state, Map<String, Object> extracted) {
        String target = Objects.requireNonNullElse(node.resultTo(), "state");
        if (!WorkflowDefinitionContract.isHumanResponseDestination(target)) {
            throw new com.skillforge.core.workflow.execution.node.WorkflowNodeFailure("workflow_result_destination_rejected");
        }
        Map<String, Object> destination = target.startsWith("extracted") ? extracted : state;
        String key = target.contains(".") ? target.substring(target.indexOf('.') + 1) : node.id();
        destination.put(key, new LinkedHashMap<>(response));
    }

    private void checkpoint(Hooks hooks, Boundary boundary, String nodeId, String nextNode,
            Map<String, Object> state, Map<String, Integer> visits, ExecutionCursor cursor, Map<String, Object> extracted,
            List<Map<String, Object>> messages, Map<String, Object> card, Integer timeoutSeconds,
            java.util.Optional<LoopLimit> loopLimit) {
        hooks.checkpoint(new ControlledWorkflowExecution.Checkpoint(boundary, nodeId, nextNode,
                stateForCheckpoint(state, visits), extracted, messages, card, timeoutSeconds, loopLimit, java.util.Optional.empty(), cursor.snapshot()));
    }

    private static final class ExecutionCursor {
        private final Map<String, Integer> visits;
        private String activeNode;
        private WorkflowEdge incomingEdge;
        private int steps;
        private ExecutionCursor(ControlledWorkflowExecution.Cursor saved) {
            visits = new HashMap<>(saved.visits()); activeNode = saved.activeNode(); steps = saved.steps(); incomingEdge = saved.incomingEdge();
        }
        private boolean enter(String node) {
            if(incomingEdge!=null&&!incomingEdge.to().equals(node))throw new IllegalStateException("workflow_cursor_edge_mismatch");
            if (node.equals(activeNode)) return true;
            if (activeNode != null) throw new IllegalStateException("workflow_cursor_node_mismatch");
            if (steps >= MAX_STEPS) return false;
            steps++;
            visits.merge(node, 1, Math::addExact);
            activeNode = node;
            return true;
        }
        private void complete(WorkflowEdge nextEdge) { activeNode = null; incomingEdge = nextEdge; }
        private ControlledWorkflowExecution.Cursor snapshot() {
            return new ControlledWorkflowExecution.Cursor(visits, activeNode, steps, incomingEdge);
        }
    }

    private WorkflowEdge nextEdge(Workflow workflow, WorkflowNode node, Map<String, Object> state,
            Map<String, Object> extracted, Map<String, Object> config, Map<String, Object> extraVars) {
        return workflow.edges().stream()
                .filter(edge -> edge.from().equals(node.id()))
                .sorted((a, b) -> Integer.compare(b.priority(), a.priority()))
                .filter(edge -> evalCondition(edge.condition(), state, extracted, config, extraVars))
                .findFirst().orElse(null);
    }

    private WorkflowNode findNode(Workflow workflow, String id) {
        return workflow.nodes().stream().filter(node -> node.id().equals(id)).findFirst().orElse(null);
    }

    private boolean evalCondition(String condition, Map<String, Object> state,
            Map<String, Object> extracted, Map<String, Object> config, Map<String, Object> extraVars) {
        if (condition == null || condition.isBlank()) return true;
        String expression = condition.trim();
        boolean negate = false;
        while (expression.startsWith("!")) {
            negate = !negate;
            expression = expression.substring(1).trim();
        }
        for (String orPart : splitTopLevel(expression, "||")) {
            boolean matches = true;
            for (String andPart : splitTopLevel(orPart, "&&")) {
                if (!evalUnit(andPart.trim(), state, extracted, config, extraVars)) {
                    matches = false;
                    break;
                }
            }
            if (matches) return !negate;
        }
        return negate;
    }

    private boolean evalUnit(String unit, Map<String, Object> state, Map<String, Object> extracted,
            Map<String, Object> config, Map<String, Object> extraVars) {
        if (unit.isBlank()) return true;
        for (String operator : new String[]{"contains", "matches", ">=", "<=", "==", "!=", "in", ">", "<"}) {
            int index = findOperator(unit, operator);
            if (index >= 0) {
                String leftText = unit.substring(0, index).trim();
                Object left = leftText.startsWith("${") && leftText.endsWith("}")
                        ? WorkflowExpressions.resolve(leftText.substring(2, leftText.length() - 1),
                                config, state, extracted, extraVars)
                        : WorkflowExpressions.parseLiteral(leftText);
                Object right = WorkflowExpressions.parseLiteral(unit.substring(index + operator.length()).trim());
                return applyOperator(operator, left, right);
            }
        }
        Object value = WorkflowExpressions.resolve(unit, config, state, extracted, extraVars);
        return value != null && !Boolean.FALSE.equals(value);
    }

    private List<String> splitTopLevel(String expression, String operator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && i + 1 < expression.length()) current.append(expression.charAt(++i));
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
                current.append(c);
            } else if (c == '$' && i + 1 < expression.length() && expression.charAt(i + 1) == '{') {
                depth++;
                current.append(c);
            } else if (c == '}' && depth > 0) {
                depth--;
                current.append(c);
            } else if (depth == 0 && expression.startsWith(operator, i)) {
                parts.add(current.toString());
                current.setLength(0);
                i += operator.length() - 1;
            } else current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }

    private int findOperator(String text, String operator) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\' && i + 1 < text.length()) i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') quote = c;
            else if (c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '{') depth++;
            else if (c == '}' && depth > 0) depth--;
            else if (depth == 0 && text.startsWith(operator, i)) return i;
        }
        return -1;
    }

    private boolean applyOperator(String operator, Object left, Object right) {
        return switch (operator) {
            case "==" -> equalValues(left, right);
            case "!=" -> !equalValues(left, right);
            case ">" -> compareValues(left, right) > 0;
            case ">=" -> compareValues(left, right) >= 0;
            case "<" -> compareValues(left, right) < 0;
            case "<=" -> compareValues(left, right) <= 0;
            case "contains" -> left instanceof List<?> list
                    ? right != null && list.stream().anyMatch(item -> equalValues(item, right))
                    : left != null && right != null && String.valueOf(left).contains(String.valueOf(right));
            case "matches" -> left != null && right != null
                    && Pattern.compile(String.valueOf(right)).matcher(String.valueOf(left)).find();
            case "in" -> right instanceof List<?> list && list.stream().anyMatch(item -> equalValues(item, left));
            default -> false;
        };
    }

    private boolean equalValues(Object left, Object right) {
        if (left instanceof Number l && right instanceof Number r) {
            return new BigDecimal(l.toString()).compareTo(new BigDecimal(r.toString())) == 0;
        }
        return Objects.equals(left, right);
    }

    private int compareValues(Object left, Object right) {
        if (left instanceof Number l && right instanceof Number r) return new BigDecimal(l.toString()).compareTo(new BigDecimal(r.toString()));
        if (left instanceof String l && right instanceof String r) return l.compareTo(r);
        return 0;
    }

    public static Map<String, Object> buildExtraVars(Map<String, Object> userProfile, String sessionId,
            String executionId, String workflowInstanceId, Map<String, Integer> actionVisits) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("end_user_id", EndUserIdentity.require(userProfile.get("end_user_id"), "Workflow runtime"));
        if (userProfile.get("display_name") != null) user.put("display_name", userProfile.get("display_name"));
        Map<String, Object> runtime = new LinkedHashMap<>();
        if (sessionId != null) runtime.put("session_id", sessionId);
        if (executionId != null) runtime.put("execution_id", executionId);
        if (workflowInstanceId != null) runtime.put("workflow_instance_id", workflowInstanceId);
        runtime.put("action_visits", actionVisits);
        return Map.of("user", user, "runtime", runtime);
    }

    public static Map<String, Integer> restoreActionVisits(Map<String, Object> state) {
        Map<String, Integer> visits = new ConcurrentHashMap<>();
        Object raw = state.remove(ACTION_VISITS_STATE_KEY);
        if (raw instanceof Map<?, ?> values) {
            values.forEach((key, value) -> {
                if (key instanceof String text && value instanceof Number number && number.intValue() > 0
                        && number.intValue() == number.doubleValue()) visits.put(text, number.intValue());
            });
        }
        return visits;
    }

    private Map<String, Object> stateForCheckpoint(Map<String, Object> state, Map<String, Integer> visits) {
        Map<String, Object> saved = new LinkedHashMap<>(state);
        if (!visits.isEmpty()) saved.put(ACTION_VISITS_STATE_KEY, Map.copyOf(visits));
        return saved;
    }

    private String lastMessageContent(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Object content = messages.get(i).get("content");
            if (content != null && !String.valueOf(content).isBlank()) return String.valueOf(content);
        }
        return null;
    }

    private String actionTarget(WorkflowNode node, String actionId) {
        if (node.actions() == null || actionId == null || actionId.isBlank()) return null;
        for (Map<String, Object> action : node.actions()) {
            if (actionId.equals(String.valueOf(action.get("id")))) {
                return action.get("to") == null ? null : String.valueOf(action.get("to"));
            }
        }
        return null;
    }
}
