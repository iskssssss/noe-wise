package com.skillforge.core.foundation.catalog.entitlements;

/** 租户资源配额的封闭种类；SQL 从此枚举选择固定表，禁止动态标识符拼接。 */
public enum ResourceKind {
    /** 智能体数。 */ AGENT,
    /** Builder 工作流容器数。 */ WORKFLOW,
    /** 知识库数。 */ KNOWLEDGE_BASE,
    /** 机构账号数，包括停用账号。 */ MEMBER,
    /** 渠道数。 */ CHANNEL,
    /** ready/deleting 公共文件字节。 */ FILE_BYTES,
    /** 排队、运行和申请取消的后台任务数。 */ ADMIN_TASK,
    /** 当前直属子机构数，包括停用子机构；解除关系后释放，不累计后代。 */ SUB_INSTITUTION
}
