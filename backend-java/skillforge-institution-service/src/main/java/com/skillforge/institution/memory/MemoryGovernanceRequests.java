package com.skillforge.institution.memory;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 治理提交前独立冻结原请求身份和受理时间，后续Runtime失败也保留相同恢复入口。 */
@Service
public class MemoryGovernanceRequests {
    private final MemoryGovernanceMapper mapper;
    private final TransactionTemplate transactions;

    public MemoryGovernanceRequests(MemoryGovernanceMapper mapper, PlatformTransactionManager manager) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        transactions = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 原key只能重投同一完整请求；缺省key也由完整请求摘要稳定派生。 */
    public Receipt accept(MemoryGovernanceRequest request) {
        Objects.requireNonNull(request, "request");
        String key = request.key();
        return TenantContext.callWithTenant(request.tenantId(), () -> transactions.execute(ignored -> {
            mapper.insertGovernanceReceipt(request, key);
            var row = mapper.governanceReceipt(request.tenantId(), key);
            if (row == null) throw new ApiException(HttpStatus.CONFLICT, "memory_governance_revision_missing");
            if (!key.equals(row.requestKey()) || !request.factId().equals(row.factId())
                    || request.expectedRevision() != row.expectedRevision() || request.operation() != row.operation()
                    || !request.fingerprint().equals(row.requestFingerprint())) {
                throw new ApiException(HttpStatus.CONFLICT, "memory_governance_idempotency_conflict");
            }
            return new Receipt(row.requestKey(), row.requestFingerprint(), Objects.requireNonNull(row.acceptedAt()));
        }));
    }

    /** 只保存治理身份时间，不复制Runtime状态或操作结果。 */
    public record Receipt(String key, String fingerprint, Instant acceptedAt) {
        public Receipt {
            if (key == null || key.isBlank() || fingerprint == null || fingerprint.isBlank()) {
                throw new IllegalArgumentException("memory_governance_receipt_identity_missing");
            }
            Objects.requireNonNull(acceptedAt, "acceptedAt");
        }
    }
}
