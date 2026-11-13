package com.skillforge.institution.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/** 工具接入与 Agent 选用视图；不暴露连接凭据，也不表示执行授权。 */
public final class ToolOnboardingViews {
    private ToolOnboardingViews() {}

    public enum Status { AVAILABLE, MISSING, UNAVAILABLE }
    public enum OperationStatus { PROCESSING, SUCCEEDED, REJECTED }

    public record Selection(
            @JsonProperty("connector_id") String connectorId,
            List<Item> items) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Receipt(
            @JsonProperty("request_id") UUID requestId,
            OperationStatus status,
            @JsonProperty("connector_id") String connectorId,
            @JsonProperty("tool_names") List<String> toolNames,
            List<ReceiptItem> items,
            @JsonProperty("error_code") String errorCode,
            @JsonProperty("error_message") String errorMessage) {}

    public record ReceiptItem(
            @JsonProperty("tool_name") String toolName,
            @JsonProperty("definition_id") String definitionId,
            @JsonProperty("version_id") String versionId,
            @JsonProperty("deployment_key") String deploymentKey,
            String environment) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            @JsonProperty("tool_name") String toolName,
            Status status,
            @JsonProperty("definition_id") String definitionId,
            @JsonProperty("version_id") String versionId,
            @JsonProperty("deployment_key") String deploymentKey,
            String name,
            String description,
            @JsonProperty("provider_ref") String providerRef,
            @JsonProperty("provider_label") String providerLabel,
            String environment,
            @JsonProperty("policy_summary") String policySummary) {}

    public record Available(List<AvailableTool> items) {}

    public record AvailableTool(
            @JsonProperty("definition_id") String definitionId,
            @JsonProperty("default_version_id") String defaultVersionId,
            String name,
            String title,
            String description,
            @JsonProperty("tool_name") String toolName,
            List<Deployment> deployments) {}

    public record Deployment(
            @JsonProperty("deployment_key") String deploymentKey,
            @JsonProperty("provider_type") String providerType,
            @JsonProperty("provider_ref") String providerRef,
            @JsonProperty("provider_label") String providerLabel,
            String environment) {}
}
