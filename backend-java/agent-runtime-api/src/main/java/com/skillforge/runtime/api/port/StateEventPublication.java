package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import java.time.Instant;
import java.util.Set;

/** 保存领域状态与其待投影 Session/Runtime 事件之间的 durable outbox 事实。 */
public record StateEventPublication(
        TenantId tenantId,
        String sourceKind,
        String sourceId,
        long sourceRevision,
        ResultRef committedFactRef,
        SessionProjectionTarget projectionTarget,
        LedgerEvent event,
        Set<String> runtimeEventDestinationIds,
        long publicationRevision,
        Instant availableAt) {
    /** 拒绝不完整、跨租户或 sourceKind/sourceId 不一致的 publication。 */
    public StateEventPublication {
        tenantId = PortContracts.requireNonNull(tenantId, "tenantId");
        sourceKind = PortContracts.requireSourceKind(sourceKind);
        sourceId = PortContracts.requireText(sourceId, "sourceId");
        sourceRevision = PortContracts.requirePositive(sourceRevision, "sourceRevision");
        committedFactRef = PortContracts.requireNonNull(committedFactRef, "committedFactRef");
        projectionTarget = PortContracts.requireNonNull(projectionTarget, "projectionTarget");
        event = PortContracts.requireNonNull(event, "event");
        runtimeEventDestinationIds = PortContracts.immutableTextSet(
                runtimeEventDestinationIds, "runtimeEventDestinationIds");
        publicationRevision = PortContracts.requirePositive(publicationRevision, "publicationRevision");
        availableAt = PortContracts.requireNonNull(availableAt, "availableAt");
        PortContracts.requireSameTenant(
                projectionTarget.owner().sessionRef().tenantId(), tenantId, "tenantId");
        validateSource(sourceKind, sourceId, projectionTarget);
        if (!runtimeEventDestinationIds.isEmpty()
                && (!sourceKind.equals("run")
                        || !isRunLifecycleEventType(event.sessionEvent().eventType()))) {
            throw new IllegalArgumentException(
                    "runtime event destinations require a facade run lifecycle event");
        }
    }

    /** 将 canonical sourceKind 与投影目标中的同域引用逐项绑定。 */
    private static void validateSource(
            String sourceKind, String sourceId, SessionProjectionTarget target) {
        String expectedId = switch (sourceKind) {
            case "run" -> target.runRef().orElseThrow(
                    () -> new IllegalArgumentException("run source requires runRef")).runId();
            case "execution" -> target.executionRef().orElseThrow(
                    () -> new IllegalArgumentException("execution source requires executionRef")).executionId();
            case "action" -> target.actionRef().orElseThrow(
                    () -> new IllegalArgumentException("action source requires actionRef")).actionId();
            case "delegation" -> target.delegationRef().orElseThrow(
                    () -> new IllegalArgumentException("delegation source requires delegationRef")).delegationId();
            default -> throw new IllegalStateException("validated sourceKind is not canonical");
        };
        if (!expectedId.equals(sourceId)) {
            throw new IllegalArgumentException("sourceId must match projection target");
        }
    }

    /** 判断 Session eventType 是否属于 façade 冻结的九类 Run lifecycle 投影。 */
    private static boolean isRunLifecycleEventType(String eventType) {
        return switch (eventType) {
            case "run.queued", "run.started", "run.progressed", "run.suspended",
                    "run.resumed", "run.completed", "run.failed", "run.terminated",
                    "run.cancelled" -> true;
            default -> false;
        };
    }
}
