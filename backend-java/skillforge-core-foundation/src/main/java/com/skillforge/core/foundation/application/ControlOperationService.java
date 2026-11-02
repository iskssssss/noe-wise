package com.skillforge.core.foundation.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.contract.ControlOperation;
import com.skillforge.core.foundation.contract.ControlOperationItem;
import com.skillforge.core.foundation.contract.ControlOperationStatus;
import com.skillforge.core.foundation.persistence.ControlOperationMapper;
import com.skillforge.core.foundation.persistence.ControlOperationDO;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ControlOperation 的唯一受理入口；不执行资源发布或创建会话输入。 */
@Service
public class ControlOperationService {

    private final ControlOperationMapper operations;
    private final com.skillforge.core.foundation.integration.InstitutionIntegrationService integrations;

    public ControlOperationService(ControlOperationMapper operations,
            com.skillforge.core.foundation.integration.InstitutionIntegrationService integrations) {
        this.operations = operations;
        this.integrations = integrations;
    }

    /** 受理一条非会话控制操作；同幂等键只返回同一事实。 */
    @Transactional(rollbackFor = Exception.class)
    public Acceptance accept(String tenantId, String integrationId, String operationId,
            String rootOperationId, String operationType, String idempotencyKey,
            String requestFingerprint, List<ControlOperationItem> items) {
        String tenant = required(tenantId, "tenantId");
        String integration = required(integrationId, "integrationId");
        String opId = required(operationId, "operationId");
        String root = rootOperationId == null || rootOperationId.isBlank() ? opId : rootOperationId;
        ControlOperation requested = new ControlOperation(opId, root, tenant, integration,
                operationType, ControlOperationStatus.ACCEPTED, idempotencyKey, requestFingerprint,
                items == null ? List.of() : items, Instant.now(), null);
        ControlOperationDO existing = findByIdempotency(Long.parseLong(tenant), integration, requested.idempotencyKey());
        if (existing != null) {
            return replay(existing, requested.requestFingerprint());
        }

        ControlOperationDO row = toRow(requested);
        integrations.requireIntegration(tenant, integration);
        row.setOriginKind("external");
        var principal = com.skillforge.common.context.ServicePrincipalContext.get();
        if (principal == null || !tenant.equals(principal.tenantId()) || !integration.equals(principal.integrationId()))
            throw new SecurityException("control_operation_authenticated_principal_required");
        row.setActorRef("service_principal:" + principal.principalId());
        row.setResponsibilityRef("integration:" + integration);
        if (operations.insertAccepted(row) == 1) return new Acceptance(requested, true);
        ControlOperationDO raced = findByIdempotency(Long.parseLong(tenant), integration, requested.idempotencyKey());
        if (raced == null) throw new IllegalStateException("control_operation_receipt_missing");
        return replay(raced, requested.requestFingerprint());
    }

    /** 按当前租户和 integration 读取受理账本，不读取其他 integration 的 operation。 */
    @Transactional(readOnly = true)
    public ControlOperation find(String tenantId, String integrationId, String operationId) {
        ControlOperationDO row = operations.selectOne(new QueryWrapper<ControlOperationDO>()
                .eq("tenant_id", Long.parseLong(required(tenantId, "tenantId")))
                .eq("integration_id", required(integrationId, "integrationId"))
                .eq("operation_id", required(operationId, "operationId")));
        return row == null ? null : fromRow(row);
    }

    /** 列出当前租户待消费的 accepted 操作；调度器随后逐条交给 worker CAS 抢占。 */
    @Transactional(readOnly = true)
    public List<ControlOperation> listAccepted(String tenantId, int limit) {
        String tenant = required(tenantId, "tenantId");
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        List<ControlOperationDO> rows = operations.selectList(new QueryWrapper<ControlOperationDO>()
                .eq("tenant_id", Long.parseLong(tenant))
                .eq("status", ControlOperationStatus.ACCEPTED.value())
                .orderByAsc("accepted_at")
                .last("LIMIT " + boundedLimit));
        return rows == null ? List.of() : rows.stream().map(this::fromRow).toList();
    }

    /**
     * 按期望状态推进账本，供后续受控 worker 使用；该方法不执行任何资源副作用。
     */
    @Transactional(rollbackFor = Exception.class)
    public ControlOperation transition(String tenantId, String integrationId, String operationId,
                                       ControlOperationStatus expectedStatus,
                                       ControlOperationStatus nextStatus,
                                       List<ControlOperationItem> items) {
        String tenant = required(tenantId, "tenantId");
        String integration = required(integrationId, "integrationId");
        String opId = required(operationId, "operationId");
        Objects.requireNonNull(expectedStatus, "expectedStatus");
        Objects.requireNonNull(nextStatus, "nextStatus");
        if (!expectedStatus.canTransitionTo(nextStatus)) {
            throw new IllegalArgumentException("invalid control operation transition: "
                    + expectedStatus.value() + " -> " + nextStatus.value());
        }
        ControlOperationDO current = operations.selectOne(new QueryWrapper<ControlOperationDO>()
                .eq("tenant_id", Long.parseLong(tenant))
                .eq("integration_id", integration)
                .eq("operation_id", opId));
        if (current == null) {
            throw new IllegalArgumentException("control operation is not visible to tenant");
        }
        if (!expectedStatus.value().equals(current.getStatus())) {
            throw new IllegalStateException("control operation status conflict");
        }
        List<ControlOperationItem> nextItems = items == null ? fromRow(current).items() : List.copyOf(items);
        Instant now = Instant.now();
        ControlOperationDO patch = new ControlOperationDO();
        patch.setStatus(nextStatus.value());
        patch.setItems(nextItems);
        patch.setCompletedAt(nextStatus.terminal() ? now : null);
        patch.setUpdatedAt(now);
        int updated = operations.update(patch, new UpdateWrapper<ControlOperationDO>()
                .eq("tenant_id", Long.parseLong(tenant))
                .eq("integration_id", integration)
                .eq("operation_id", opId)
                .eq("status", expectedStatus.value()));
        if (updated != 1) {
            throw new IllegalStateException("control operation status conflict");
        }
        current.setStatus(nextStatus.value());
        current.setItems(nextItems);
        current.setCompletedAt(patch.getCompletedAt());
        current.setUpdatedAt(now);
        return fromRow(current);
    }

    private Acceptance replay(ControlOperationDO row, String requestFingerprint) {
        if (!Objects.equals(row.getRequestFingerprint(), requestFingerprint)) {
            throw new IllegalArgumentException("control operation idempotency key was reused with a different request");
        }
        return new Acceptance(fromRow(row), false);
    }

    private ControlOperationDO findByIdempotency(long tenantId, String integrationId, String idempotencyKey) {
        return operations.selectOne(new QueryWrapper<ControlOperationDO>()
                .eq("tenant_id", tenantId)
                .eq("integration_id", integrationId)
                .eq("idempotency_key", idempotencyKey));
    }

    private ControlOperationDO toRow(ControlOperation operation) {
        ControlOperationDO row = new ControlOperationDO();
        row.setId(SnowflakeIds.nextId());
        row.setTenantId(Long.parseLong(operation.tenantId()));
        row.setOperationId(operation.operationId());
        row.setRootOperationId(operation.rootOperationId());
        row.setIntegrationId(operation.integrationId());
        row.setOperationType(operation.operationType());
        row.setStatus(operation.status().value());
        row.setIdempotencyKey(operation.idempotencyKey());
        row.setRequestFingerprint(operation.requestFingerprint());
        row.setItems(operation.items());
        row.setAcceptedAt(operation.acceptedAt());
        row.setCompletedAt(operation.completedAt());
        row.setUpdatedAt(operation.acceptedAt());
        return row;
    }

    private ControlOperation fromRow(ControlOperationDO row) {
        return new ControlOperation(row.getOperationId(), row.getRootOperationId(),
                String.valueOf(row.getTenantId()), row.getIntegrationId(), row.getOperationType(),
                ControlOperationStatus.fromValue(row.getStatus()), row.getIdempotencyKey(), row.getRequestFingerprint(),
                row.getItems() == null ? List.of() : row.getItems(), row.getAcceptedAt(), row.getCompletedAt());
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    public record Acceptance(ControlOperation operation, boolean created) {
    }
}
