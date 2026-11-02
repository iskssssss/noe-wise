package com.skillforge.core.channel.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/**
 * 渠道-单 Agent 绑定实体，对应数据库表 {@code channel_agent_bindings}。
 *
 * <p>控制渠道可直连的单个 Agent，支持路由
 * 优先级、默认 Agent 与灰度流量百分比；运行时校验 Agent 为 published 状态。
 */
@Data
@TableName("nw_channel_agent_bindings")
public class ChannelAgentBindingDO {

    /** 主键（雪花 bigint）。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 渠道 ID。 */
    private Long channelId;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** Agent ID。 */
    private Long agentId;

    /** 路由优先级。 */
    private Integer priority;

    /** 是否为渠道默认 Agent。 */
    private Boolean isDefault;

    /** 灰度流量百分比（0-100）。 */
    private Integer trafficPercentage;

    /** 创建时间。 */
    private Instant createdAt;
}
