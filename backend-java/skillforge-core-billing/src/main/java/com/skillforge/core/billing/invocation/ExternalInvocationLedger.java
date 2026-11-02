package com.skillforge.core.billing.invocation;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.billing.persistence.ExternalInvocationMapper;
import com.skillforge.core.shared.tracing.ControlOperationContext;
import com.skillforge.core.shared.tracing.InvocationContext;
import com.skillforge.core.shared.tracing.TraceContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import com.skillforge.core.billing.metering.CostRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ExternalInvocation 的权威尝试账本。
 *
 * <p>开始事实必须先提交，外部客户端才可被调用；同一 tenant+attemptId 只允许一次。
 * 完成状态以 CAS 迁移，不能让迟到线程覆盖已完成尝试。</p>
 */
@Service
public class ExternalInvocationLedger {

    private static final Logger log = LoggerFactory.getLogger(ExternalInvocationLedger.class);
    private final ExternalInvocationMapper mapper;
    private final CostRecorder costs;

    /** 生产辅助调用正式注入计量器；Runtime chat 继续由独立 audit 写初始报告。 */
    public ExternalInvocationLedger(ExternalInvocationMapper mapper,CostRecorder costs) {
        log.debug("外部调用尝试账本装配进入");
        this.mapper = java.util.Objects.requireNonNull(mapper);
        this.costs = java.util.Objects.requireNonNull(costs);
        log.debug("外部调用尝试账本装配退出");
    }

    /** 辅助 Guard 的开始事实和未知计量共用事务，任何写入失败均不允许发送网络请求。 */
    @Transactional(rollbackFor = Exception.class,propagation=Propagation.REQUIRES_NEW)
    public boolean startAuxiliary(String tenantId,ExternalInvocationRequest request,Instant now) {
        log.debug("辅助调用准入进入 tenant={} operation={}",tenantId,request.operationType());
        boolean started=start(tenantId,request,now);
        if(started)costs.recordAuxiliaryStarted(tenantId,request);
        log.debug("辅助调用准入退出 tenant={} started={}",tenantId,started);
        return started;
    }

    /**
     * 登记一个开始事实。
     *
     * @return true 表示当前调用取得该 attempt 的唯一执行权；false 表示已登记，不得再次发起网络调用
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean start(String tenantId, ExternalInvocationRequest request, Instant now) {
        log.debug("外部调用开始事实进入 tenant={} operation={}",tenantId,request.operationType());
        long tenant = parseTenant(tenantId);
        if (find(tenant, request.attemptId()) != null) {
            log.debug("外部调用开始事实退出 tenant={} started=false",tenantId);
            return false;
        }
        Instant startedAt = now == null ? Instant.now() : now;
        ExternalInvocationDO row = new ExternalInvocationDO();
        row.setId(SnowflakeIds.nextId());
        row.setTenantId(tenant);
        row.setInvocationId(request.invocationId());
        row.setAttemptId(request.attemptId());
        row.setOperationType(request.operationType().value());
        row.setProvider(request.provider());
        row.setService(request.service());
        row.setIdempotencyKey(request.idempotencyKey());
        row.setStatus(ExternalInvocationStatus.STARTED.name());
        applyContext(row, request.context());
        row.setMetadata(sanitizedMetadata(request.metadata()));
        row.setStartedAt(startedAt);
        row.setCreatedAt(startedAt);
        row.setUpdatedAt(startedAt);
        // XML ON CONFLICT 保留数据库唯一执行权，竞争落败不使 PostgreSQL 事务中止。
        boolean started=mapper.insert(row)>0;
        log.debug("外部调用开始事实退出 tenant={} started={}",tenantId,started);
        return started;
    }

    /** 完成已登记尝试；只有 STARTED 可进入终态。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean finish(
            String tenantId,
            String attemptId,
            ExternalInvocationStatus status,
            String providerRequestId,
            String errorCode,
            Instant now) {
        if (status != ExternalInvocationStatus.SUCCEEDED
                && status != ExternalInvocationStatus.FAILED
                && status != ExternalInvocationStatus.UNRESOLVED) {
            throw new IllegalArgumentException("finish status must be terminal");
        }
        Instant endedAt = now == null ? Instant.now() : now;
        return mapper.update(null, new UpdateWrapper<ExternalInvocationDO>()
                .eq("tenant_id", parseTenant(tenantId))
                .eq("attempt_id", attemptId)
                .eq("status", ExternalInvocationStatus.STARTED.name())
                .set("status", status.name())
                .set("provider_request_id", blankToNull(providerRequestId))
                .set("error_code", blankToNull(errorCode))
                .set("ended_at", endedAt)
                .set("updated_at", endedAt)) > 0;
    }

    private ExternalInvocationDO find(long tenantId, String attemptId) {
        return mapper.selectOne(new QueryWrapper<ExternalInvocationDO>()
                .eq("tenant_id", tenantId)
                .eq("attempt_id", attemptId));
    }

    private void applyContext(ExternalInvocationDO row, InvocationContext context) {
        if (context instanceof TraceContext trace) {
            row.setInputId(trace.inputId());
            row.setRootInputId(trace.rootInputId());
            row.setTraceId(trace.traceId());
            row.setSpanId(trace.spanId());
            row.setSourceEventId(trace.eventId());
            row.setCausedByEventId(trace.causedByEventId());
            return;
        }
        ControlOperationContext operation = (ControlOperationContext) context;
        row.setOperationId(operation.operationId());
        row.setRootOperationId(operation.rootOperationId());
        row.setTraceId(operation.traceId());
        row.setSpanId(operation.spanId());
        row.setSourceEventId(operation.eventId());
        row.setCausedByEventId(operation.causedByEventId());
    }

    private Map<String, Object> sanitizedMetadata(Map<String, Object> metadata) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null
                    && !entry.getKey().toLowerCase(java.util.Locale.ROOT)
                    .matches(".*(token|secret|credential|authorization|payload|prompt|content).*$")) {
                safe.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(safe);
    }

    private long parseTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        return Long.parseLong(tenantId);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
