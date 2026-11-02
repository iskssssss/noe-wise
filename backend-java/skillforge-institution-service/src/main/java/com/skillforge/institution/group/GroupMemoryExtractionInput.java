package com.skillforge.institution.group;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.contract.ChatContent;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.port.PortCallContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 后台群提取只消费正式 EndUser 来源；作者、事实描述对象与系统执行主体分别保留。 */
@Service
public class GroupMemoryExtractionInput {
    private final GroupMemorySources sources;
    private final GroupMemoryExtractionMapper mapper;
    private final GroupControlMapper controls;

    public GroupMemoryExtractionInput(GroupMemorySources sources, GroupMemoryExtractionMapper mapper,
            GroupControlMapper controls) {
        this.sources = sources;
        this.mapper = mapper;
        this.controls = controls;
    }

    /** 原来源入库已核作者；退群不抹掉历史作者，也不能让系统任务冒充其当前身份。 */
    public Material source(PortCallContext context, String integration, String group, EventId sourceEvent) {
        List<Long> subjects = mapper.subjects(context.runIdentity().owner().sessionRef().tenantId().value(), integration, group);
        return source(context,integration,group,sourceEvent,subjects);
    }

    /** 恢复只核原正式来源并复用已冻结描述对象，不让后来成员目录改变原模型输入。 */
    public Material source(PortCallContext context, String integration, String group, EventId sourceEvent,
            List<Long> subjects) {
        var event = sources.event(context, sourceEvent);
        var message = sources.require(event, integration, group);
        if (!subjects.contains(message.authorEndUserId())) throw new IllegalArgumentException("memory_group_author_missing");
        try {
            return new Material(ChatContent.textValue(sources.payload(message).content()), event.occurredAt(),
                    message.authorEndUserId(), subjects);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("memory_group_source_invalid", invalid);
        }
    }

    /** 与控制命令持有同一群锁；仅明确 memory_extraction 暂停阻止新资格，send 暂停不影响。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean allowed(String tenant, String integration, String group, String agent, String runId) {
        if (!Objects.equals(tenant, TenantContext.getTenantId())) return false;
        long tenantId = Long.parseLong(tenant), groupId = Long.parseLong(group);
        controls.ensure(tenantId, integration, groupId);
        if (controls.lock(tenantId, integration, groupId) == null || !mapper.eligible(tenant, integration, group, agent)) return false;
        return controls.states(tenantId, integration, groupId).stream().noneMatch(state -> state.paused()
                && GroupControlTypes.Scope.MEMORY_EXTRACTION.wire.equals(state.scope())
                && (GroupControlTypes.GROUP_TARGET.equals(state.targetRef()) || Objects.equals(runId, state.targetRef())));
    }

    public record Material(String text, Instant occurredAt, long authorEndUserId, List<Long> subjectEndUserIds) {
        public Material { subjectEndUserIds = List.copyOf(subjectEndUserIds); }
    }
}
