package com.skillforge.institution.group;

import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.api.port.PortCallContext;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 只将已有群来源交给原提取队列，不建立新的模型执行器或候选生命周期。 */
@Service
public class GroupMemoryExtractionQueue {
    private final GroupMemoryExtractionMapper mapper;
    private final GroupMemoryExtractionInput input;
    private final GroupMemorySources sources;

    public GroupMemoryExtractionQueue(GroupMemoryExtractionMapper mapper, GroupMemoryExtractionInput input,
            GroupMemorySources sources) {
        this.mapper = mapper;
        this.input = input;
        this.sources = sources;
    }

    /** 日志投影独立提交时按精确 eventId 补读；通用 job 已确认存在后才删除 pending 交接行。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueue(String tenant, String extractor, String policy,
            Function<GroupMemoryExtractionMapper.Pending, PortCallContext> contextFactory) {
        if (!Objects.equals(tenant, TenantContext.getTenantId())) throw new IllegalArgumentException("memory_group_tenant_required");
        var pending = mapper.next(tenant);
        if (pending == null) return false;
        PortCallContext context = contextFactory.apply(pending);
        if (!input.allowed(tenant, pending.integrationId(), pending.groupBindingId(), pending.agentId(),
                context.runIdentity().runRef().runId())) return false;
        var projected = sources.project(context, pending.integrationId(), pending.groupBindingId(), List.of(pending.sourceMessageId()));
        String event = GroupMemorySources.eventId(pending.sourceMessageId(), pending.sourceRevision()).value();
        if (projected.size() != 1 || !projected.getFirst().value().equals(event))
            throw new IllegalStateException("memory_group_projection_mismatch");
        mapper.enqueue(pending, event, extractor, policy);
        if (!mapper.queued(pending, event, extractor, policy)) throw new IllegalStateException("memory_group_enqueue_conflict");
        if (mapper.acknowledge(pending) != 1) throw new IllegalStateException("memory_group_pending_fence_lost");
        return true;
    }
}
