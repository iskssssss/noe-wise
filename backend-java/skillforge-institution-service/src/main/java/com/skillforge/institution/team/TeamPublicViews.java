package com.skillforge.institution.team;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse.ProcessItem;
import java.util.List;

/** 协作只读契约：运行身份用于关联，用户文字只来自显式业务字段。 */
public final class TeamPublicViews {
    private TeamPublicViews() { }

    public static final int DEFAULT_DETAIL_PAGE_SIZE=50;
    public static final int MAX_DETAIL_PAGE_SIZE=200;
    public static final int WORK_HISTORY_LIMIT=100;
    public static final int DELEGATION_HISTORY_LIMIT=1000;
    public static final String DETAIL_DEFAULT_LIMIT="50";
    public enum TaskKind {
        PLAN("plan"),DIRECT("direct");
        private final String value;TaskKind(String value){this.value=value;}
        @com.fasterxml.jackson.annotation.JsonValue public String wire(){return value;}
        public static TaskKind parse(String value){for(var kind:values())if(kind.value.equals(value))return kind;throw new IllegalArgumentException("invalid task kind");}
    }
    public enum DetailsAccess {
        NOT_STARTED("not_started"),AVAILABLE("available"),REVOKED("revoked");
        private final String value;DetailsAccess(String value){this.value=value;}
        @com.fasterxml.jackson.annotation.JsonValue public String wire(){return value;}
    }
    public static final class State {
        public static final String PENDING="pending", BLOCKED="blocked", RUNNING="running", WAITING="waiting", COMPLETED="completed",
                FAILED="failed", CANCELLED="cancelled", OUTCOME_UNKNOWN="outcome_unknown", CANCELLING="cancelling",
                READY="ready", ACTIVE="active", QUEUED="queued", TERMINATED="terminated";
        private State() { }
    }
    public static final class Stage {
        public static final String FINISHED="finished",SUMMARIZING="summarizing",WORKING="working";
        private Stage() { }
    }
    public static final class PendingKind {
        public static final String INPUT="input",OPERATION="operation",WORKFLOW="workflow",REJECTED="rejected",TOOL_INPUT="tool_input";
        private PendingKind() { }
    }

    public record Member(@JsonProperty("agent_id") String agentId, String name) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Pending(@JsonProperty("pending_id") String pendingId,
            @JsonProperty("parameters_version") String parametersVersion, String prompt, String kind,
            @JsonProperty("execution_id") String executionId,
            @JsonProperty("tool_wait") Wait toolWait,
            @JsonProperty("run_revision") String runRevision, JsonNode card) { }
    public record Wait(@JsonProperty("action_id") String actionId,String generation) { }

    public record Task(@JsonProperty("task_id") String taskId,
            @JsonProperty("task_kind") TaskKind taskKind, String title,
            @JsonProperty("agent_id") String agentId, @JsonProperty("agent_name") String agentName,
            String status, @JsonProperty("run_id") String runId,
            @JsonProperty("depends_on") List<String> dependsOn,
            @JsonProperty("result_ids") List<String> resultIds, String phase, Pending pending,
            @JsonProperty("wait_reason") String waitReason,
            @JsonProperty("replaces_task_id") String replacesTaskId,
            @JsonProperty("details_access") DetailsAccess detailsAccess,
            @JsonProperty("executor_type") String executorType, String goal,
            @JsonProperty("expected_output") String expectedOutput, List<String> constraints) {
        public Task(String taskId,TaskKind taskKind,String title,String agentId,String agentName,String status,String runId,
                List<String> dependsOn,List<String> resultIds,String phase,Pending pending,String waitReason,String replacesTaskId,DetailsAccess detailsAccess) {
            this(taskId,taskKind,title,agentId,agentName,status,runId,dependsOn,resultIds,phase,pending,waitReason,replacesTaskId,detailsAccess,"MEMBER","","",List.of());
        }
    }

    /** 操作的核验身份、授权摘要和原始观察不进入公开成果。 */
    public record Result(@JsonProperty("result_id") String resultId,
            @JsonProperty("task_id") String taskId, String title, String kind, String status,
            String summary, String content, List<TeamTaskResult.Representation> representations,
            List<String> limitations, @JsonProperty("created_at") String createdAt) { }

    public record Work(@JsonProperty("work_id") String workId,
            @JsonProperty("session_id") String sessionId, long revision,
            @JsonProperty("input_id") String inputId, @JsonProperty("run_id") String runId,
            @JsonProperty("run_revision") Long runRevision, @JsonProperty("run_status") String runStatus,
            String stage, String status, @JsonProperty("plan_revision") long planRevision,
            @JsonProperty("approved_revision") Long approvedRevision,
            @JsonProperty("approval_required") boolean approvalRequired, String handoff, Member coordinator,
            List<Member> members, List<Result> results, List<Task> tasks, Pending pending,
            @JsonProperty("queued_count") int queuedCount,
            @JsonProperty("control_inputs") List<TeamWorkInputs.Receipt> controlInputs, String title,
            @JsonProperty("change_reason") String changeReason) {
        public Work(String workId,String sessionId,long revision,String inputId,String runId,Long runRevision,String runStatus,String stage,String status,long planRevision,Long approvedRevision,boolean approvalRequired,String handoff,Member coordinator,List<Member> members,List<Result> results,List<Task> tasks,Pending pending,int queuedCount,List<TeamWorkInputs.Receipt> controlInputs) {
            this(workId,sessionId,revision,inputId,runId,runRevision,runStatus,stage,status,planRevision,approvedRevision,approvalRequired,handoff,coordinator,members,results,tasks,pending,queuedCount,controlInputs,handoff,"");
        }
        public Work withCollections(List<Result> newResults, List<Task> newTasks, List<TeamWorkInputs.Receipt> controls) {
            return new Work(workId,sessionId,revision,inputId,runId,runRevision,runStatus,stage,status,
                    planRevision,approvedRevision,approvalRequired,handoff,coordinator,members,
                    List.copyOf(newResults),List.copyOf(newTasks),pending,0,List.copyOf(controls),title,changeReason);
        }
    }

    public record History(List<Work> items) { }
    public record Run(String status, long revision, @JsonProperty("cancel_requested") boolean cancelRequested) { }
    public record MemberDetail(Task task, Run run, List<ProcessItem> processes,
            @JsonProperty("next_cursor") String nextCursor, @JsonProperty("has_more") boolean hasMore,
            @JsonProperty("caught_up") boolean caughtUp, Pending pending, List<Result> results) { }
}
