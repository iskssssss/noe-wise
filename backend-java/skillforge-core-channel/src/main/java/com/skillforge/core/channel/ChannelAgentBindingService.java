package com.skillforge.core.channel;

import com.skillforge.core.channel.mapper.ChannelAgentBindingMapper;
import com.skillforge.core.channel.mapper.ChannelMapper;
import com.skillforge.core.channel.dto.request.ChannelAgentBindingRequest;
import com.skillforge.core.channel.model.ChannelAgentBindingDO;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.agent.constant.AgentConstants;
import com.skillforge.core.channel.constant.ChannelConstants;
import com.skillforge.core.shared.exception.ApiException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 渠道-单 Agent 绑定 MyBatis-Plus 服务。
 *
 * <p>当前路由集合由本服务在租户事务中整批替换；渠道锁序列化替换与解绑，
 * 插入失败必须恢复完整旧集合，不放宽默认绑定与重复 Agent 的数据库约束。
 */
@Service
public class ChannelAgentBindingService {
    private final ChannelAgentBindingMapper bindings;
    private final ChannelMapper channels;

    public ChannelAgentBindingService(ChannelAgentBindingMapper bindings, ChannelMapper channels) {
        this.bindings = bindings;
        this.channels = channels;
    }

    /** PUT 表示完整替换；空集合明确移除全部绑定，任何校验或写入失败均不提交局部结果。 */
    @Transactional(rollbackFor = Exception.class)
    public int replaceBindings(long tenantId, long channelId, List<ChannelAgentBindingRequest> requests) {
        validate(requests);
        requireChannel(tenantId, channelId);
        if (!requests.isEmpty()) {
            var ids = requests.stream().map(ChannelAgentBindingRequest::agentId).toList();
            var published = bindings.lockPublishedAgents(tenantId, ids, AgentConstants.STATUS_PUBLISHED);
            if (!new HashSet<>(published).equals(new HashSet<>(ids)))
                throw invalid("只能绑定本机构已发布的 Agent");
        }
        var now = Instant.now();
        var replacements = requests.stream().map(item -> {
            var binding = new ChannelAgentBindingDO();
            binding.setId(SnowflakeIds.nextId()); binding.setTenantId(tenantId); binding.setChannelId(channelId);
            binding.setAgentId(item.agentId()); binding.setCreatedAt(now);
            binding.setPriority(item.priority() == null ? ChannelConstants.DEFAULT_PRIORITY : item.priority());
            binding.setIsDefault(Boolean.TRUE.equals(item.isDefault()));
            binding.setTrafficPercentage(item.trafficPercentage() == null
                    ? ChannelConstants.DEFAULT_TRAFFIC_PERCENTAGE : item.trafficPercentage());
            return binding;
        }).toList();
        bindings.deleteForChannel(tenantId, channelId);
        if (!replacements.isEmpty() && bindings.insertBindings(replacements) != replacements.size())
            throw new IllegalStateException("渠道绑定集合写入不完整");
        return replacements.size();
    }

    /** 与集合替换争用同一渠道行锁；解绑不存在的关系保持幂等。 */
    @Transactional(rollbackFor = Exception.class)
    public void unbind(long tenantId, long channelId, long agentId) {
        requireChannel(tenantId, channelId);
        bindings.deleteForAgent(tenantId, channelId, agentId);
    }

    private void requireChannel(long tenantId, long channelId) {
        if (tenantId <= 0 || channelId <= 0 || channels.lockOwnedChannel(tenantId, channelId) == null)
            throw new ApiException(HttpStatus.NOT_FOUND, "渠道不存在");
    }

    private void validate(List<ChannelAgentBindingRequest> requests) {
        if (requests == null) throw invalid("必须提供完整 Agent 绑定集合");
        var ids = new HashSet<Long>();
        boolean defaultSeen = false;
        for (var item : requests) {
            if (item == null || item.agentId() == null || item.agentId() <= 0 || !ids.add(item.agentId()))
                throw invalid("Agent 绑定不能缺少目标或重复目标");
            if (Boolean.TRUE.equals(item.isDefault())) {
                if (defaultSeen) throw invalid("渠道只能有一个默认 Agent");
                defaultSeen = true;
            }
            if (item.trafficPercentage() != null && (item.trafficPercentage() < ChannelConstants.MIN_TRAFFIC_PERCENTAGE
                    || item.trafficPercentage() > ChannelConstants.DEFAULT_TRAFFIC_PERCENTAGE))
                throw invalid("流量百分比超出有效范围");
        }
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
