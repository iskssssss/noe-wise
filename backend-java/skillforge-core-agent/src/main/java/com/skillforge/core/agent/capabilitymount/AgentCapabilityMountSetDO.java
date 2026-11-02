package com.skillforge.core.agent.capabilitymount;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** Agent CapabilityMount 装配代记录，对应 {@code agent_capability_mount_sets}。 */
@Data
@TableName("nw_agent_capability_mount_sets")
public class AgentCapabilityMountSetDO {

    /** 租户所有权键。 */
    private Long tenantId;

    /** Agent 所有权键；与 tenantId 组成复合主键。 */
    @TableId(type = IdType.INPUT)
    private Long agentId;

    /** 当前装配代。 */
    private Long generation;
}
