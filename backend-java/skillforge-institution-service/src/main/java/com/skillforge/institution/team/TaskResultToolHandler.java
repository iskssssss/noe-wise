package com.skillforge.institution.team;

import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort;
import com.skillforge.runtime.api.dag.DagWork;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 只按可信 Run 绑定登记当前团队任务成果，不接受模型提供 work、task 或 agent 身份。 */
@Component
public final class TaskResultToolHandler implements PlatformToolHandler {
    private static final Set<String> ROOT_FIELDS = Set.of("outputs");
    private static final Set<String> OUTPUT_FIELDS = Set.of("output_name", "title", "kind", "status",
            "summary", "content", "representations", "limitations", "operation_receipt");
    private static final Set<String> REPRESENTATION_FIELDS = Set.of("label", "media_type", "artifact_id", "path", "role");
    private static final Set<String> OPERATION_RECEIPT_FIELDS = Set.of(
            "action_ref", "invocation_id", "attempt_id", "fact_ref");
    private final JdbcDagWorkPort works;
    private final TeamResultService results;

    public TaskResultToolHandler(JdbcDagWorkPort works, TeamResultService results) {
        this.works = works;
        this.results = results;
    }

    @Override
    public String toolName() {
        return "submit_task_result";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> arguments, PlatformToolExecutionContext context) {
        try {
            List<TeamResultService.Submission> submissions = parse(arguments);
            DagWork work = works.workForRun(context.run().runRef())
                    .flatMap(works::read)
                    .orElseThrow(() -> new IllegalArgumentException("task_result_run_not_admitted"));
            List<DagWork.Execution> matches = work.executions().values().stream()
                    .filter(execution -> execution.binding().runRef().equals(context.run().runRef())).toList();
            if (matches.size() != 1) throw new IllegalArgumentException("task_result_run_binding_ambiguous");
            List<Map<String, Object>> saved = results.submit(work, matches.getFirst(), submissions, context).stream()
                    .map(result -> Map.<String, Object>of(
                            "output_name", result.outputName(),
                            "result_id", result.resultRef().value(),
                            "digest", result.digest(),
                            "status", result.status()))
                    .toList();
            return Map.of("success", true, "results", saved);
        } catch (ApiException | IllegalArgumentException rejected) {
            return Map.of("success", false, "error", rejected.getMessage());
        }
    }

    private static List<TeamResultService.Submission> parse(Map<String, Object> arguments) {
        if (arguments == null || !arguments.keySet().equals(ROOT_FIELDS)
                || !(arguments.get("outputs") instanceof List<?> outputs) || outputs.isEmpty() || outputs.size() > 32)
            throw new IllegalArgumentException("task_result_arguments_rejected");
        List<TeamResultService.Submission> parsed = new ArrayList<>();
        for (Object raw : outputs) {
            if (!(raw instanceof Map<?, ?> output) || !OUTPUT_FIELDS.containsAll(stringKeys(output))
                    || !Set.of("output_name", "title", "kind", "status", "summary", "representations", "limitations")
                            .stream().allMatch(output::containsKey))
                throw new IllegalArgumentException("task_result_output_rejected");
            List<TeamResultService.RepresentationInput> representations = parseRepresentations(output.get("representations"));
            parsed.add(new TeamResultService.Submission(text(output, "output_name"), text(output, "title"),
                    text(output, "kind"), text(output, "status"), optional(output, "summary"),
                    optional(output, "content"), representations, strings(output.get("limitations")),
                    operationReceipt(output.get("operation_receipt"))));
        }
        return List.copyOf(parsed);
    }

    private static TeamResultService.OperationReceiptInput operationReceipt(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> receipt) || !stringKeys(receipt).equals(OPERATION_RECEIPT_FIELDS))
            throw new IllegalArgumentException("task_result_operation_receipt_rejected");
        return new TeamResultService.OperationReceiptInput(text(receipt, "action_ref"),
                text(receipt, "invocation_id"), text(receipt, "attempt_id"), text(receipt, "fact_ref"));
    }

    private static List<TeamResultService.RepresentationInput> parseRepresentations(Object raw) {
        if (!(raw instanceof List<?> values) || values.size() > 32)
            throw new IllegalArgumentException("task_result_representations_rejected");
        List<TeamResultService.RepresentationInput> parsed = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> representation)
                    || !REPRESENTATION_FIELDS.containsAll(stringKeys(representation))
                    || !Set.of("label", "media_type", "role").stream().allMatch(representation::containsKey))
                throw new IllegalArgumentException("task_result_representation_rejected");
            parsed.add(new TeamResultService.RepresentationInput(text(representation, "label"),
                    text(representation, "media_type"), optional(representation, "artifact_id"),
                    optional(representation, "path"), text(representation, "role")));
        }
        return List.copyOf(parsed);
    }

    private static Set<String> stringKeys(Map<?, ?> value) {
        if (value.keySet().stream().anyMatch(key -> !(key instanceof String)))
            throw new IllegalArgumentException("task_result_field_rejected");
        return value.keySet().stream().map(String.class::cast).collect(java.util.stream.Collectors.toSet());
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> values) || values.size() > 20 || values.stream().anyMatch(value -> !(value instanceof String)))
            throw new IllegalArgumentException("task_result_limitations_rejected");
        return values.stream().map(String.class::cast).toList();
    }

    private static String text(Map<?, ?> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof String text) || text.isBlank() || !text.equals(text.trim()))
            throw new IllegalArgumentException("task_result_" + field + "_rejected");
        return text;
    }

    private static String optional(Map<?, ?> value, String field) {
        Object raw = value.get(field);
        if (raw == null) return "";
        if (!(raw instanceof String text) || !text.equals(text.trim()))
            throw new IllegalArgumentException("task_result_" + field + "_rejected");
        return text;
    }
}
