package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.TypedPayload;

/** 保存 Action attempt 私有、版本化且可确定重放的 Provider 终态载荷。 */
public record PortalOutcomeSnapshot(
        PortalCallKind callKind,
        PortalResolution resolution,
        String outcomeType,
        TypedPayload outcome) {

    /** 拒绝缺失 discriminator、空白 subtype 或与注册表不匹配的 schema identity。 */
    public PortalOutcomeSnapshot {
        callKind = PortContracts.requireNonNull(callKind, "callKind");
        resolution = PortContracts.requireNonNull(resolution, "resolution");
        outcomeType = PortContracts.requireText(outcomeType, "outcomeType");
        outcome = PortContracts.requireNonNull(outcome, "outcome");
        String expectedSchemaId = schemaId(callKind);
        if (!expectedSchemaId.equals(outcome.schemaId())) {
            throw new IllegalArgumentException("outcome schemaId must match callKind registry");
        }
        if (!supports(callKind, resolution, outcomeType)) {
            throw new IllegalArgumentException(
                    "callKind, resolution and outcomeType must match portal outcome registry");
        }
    }

    /** Provider 结果正文是私有重放载荷，普通日志只暴露判别字段。 */
    @Override
    public String toString() {
        return "PortalOutcomeSnapshot[callKind=" + callKind
                + ",resolution=" + resolution
                + ",outcomeType=" + outcomeType + ",outcome=REDACTED]";
    }

    /** 返回 §8.2 为调用种类登记的唯一私有 outcome schema identity。 */
    private static String schemaId(PortalCallKind callKind) {
        return switch (callKind) {
            case MODEL_GENERATE, MODEL_STREAM -> "skillforge.runtime.portal.model-outcome.v5";
            case TOOL_INVOKE -> "skillforge.runtime.portal.tool-outcome.v2";
            case WORKFLOW_DISPATCH, WORKFLOW_RESUME ->
                    "skillforge.runtime.portal.workflow-invoke-outcome.v2";
            case WORKFLOW_RECOVER_RESULT ->
                    "skillforge.runtime.portal.workflow-recover-outcome.v2";
            case WORKFLOW_CANCEL ->
                    "skillforge.runtime.portal.workflow-control-outcome.v1";
            case KNOWLEDGE_EXECUTE ->
                    "skillforge.runtime.portal.knowledge-outcome.v2";
        };
    }

    /** 校验调用种类、分辨率和公开 Port result subtype 的封闭映射。 */
    private static boolean supports(
            PortalCallKind callKind,
            PortalResolution resolution,
            String outcomeType) {
        return switch (callKind) {
            case MODEL_GENERATE, MODEL_STREAM -> switch (resolution) {
                case KNOWN_RESULT -> outcomeType.equals("Completed");
                case INPUT_REQUIRED -> false;
                case KNOWN_FAILURE -> outcomeType.equals("Failed");
                case OUTCOME_UNKNOWN -> outcomeType.equals("OutcomeUnknown");
            };
            case TOOL_INVOKE -> switch (resolution) {
                case KNOWN_RESULT -> outcomeType.equals("Succeeded");
                case INPUT_REQUIRED -> outcomeType.equals("InputRequired");
                case KNOWN_FAILURE -> outcomeType.equals("Failed");
                case OUTCOME_UNKNOWN -> outcomeType.equals("OutcomeUnknown");
            };
            case WORKFLOW_DISPATCH, WORKFLOW_RESUME -> switch (resolution) {
                case KNOWN_RESULT -> outcomeType.equals("Accepted")
                        || outcomeType.equals("Suspended")
                        || outcomeType.equals("Completed")
                        || outcomeType.equals("Terminated")
                        || outcomeType.equals("Cancelled");
                case INPUT_REQUIRED -> false;
                case KNOWN_FAILURE -> outcomeType.equals("Failed");
                case OUTCOME_UNKNOWN -> outcomeType.equals("OutcomeUnknown");
            };
            case WORKFLOW_RECOVER_RESULT -> switch (resolution) {
                case KNOWN_RESULT -> outcomeType.equals("Suspended") || outcomeType.equals("Completed")
                        || outcomeType.equals("Terminated")
                        || outcomeType.equals("Cancelled")
                        || outcomeType.equals("Absent");
                case INPUT_REQUIRED -> false;
                case KNOWN_FAILURE -> outcomeType.equals("Failed")
                        || outcomeType.equals("Rejected");
                case OUTCOME_UNKNOWN -> false;
            };
            case WORKFLOW_CANCEL -> switch (resolution) {
                case KNOWN_RESULT -> outcomeType.equals("Applied") || outcomeType.equals("Replayed");
                case INPUT_REQUIRED -> false;
                case KNOWN_FAILURE -> outcomeType.equals("Conflict") || outcomeType.equals("Rejected");
                case OUTCOME_UNKNOWN -> outcomeType.equals("OutcomeUnknown");
            };
            case KNOWLEDGE_EXECUTE -> switch (resolution) {
                case KNOWN_RESULT -> outcomeType.equals("Retrieved");
                case INPUT_REQUIRED -> false;
                case KNOWN_FAILURE -> outcomeType.equals("Failed");
                case OUTCOME_UNKNOWN -> outcomeType.equals("OutcomeUnknown");
            };
        };
    }
}
