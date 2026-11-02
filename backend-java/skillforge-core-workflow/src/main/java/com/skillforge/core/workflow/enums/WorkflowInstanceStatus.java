package com.skillforge.core.workflow.enums;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
/**
 * 工作流实例状态枚举。
 *
 * <p>与 {@code workflow_instances.status} 列取值一致，JSON 序列化输出 code 值，
 * 反序列化按 code 匹配。
 */
public enum WorkflowInstanceStatus {
    /** 草稿。 */
    DRAFT("draft"),
    /** 已发布。 */
    PUBLISHED("published"),
    /** 已停用。 */
    DISABLED("disabled"),
    /** 已归档。 */
    ARCHIVED("archived");
    /** 状态字符串值。 */
    private final String code;
    /**
     * 构造状态枚举。
     *
     * @param code 状态字符串值
     */
    WorkflowInstanceStatus(String code) {
        this.code = code;
    }
    /**
     * 获取状态字符串值。
     *
     * @return 状态字符串值
     */
    @JsonValue
    public String code() {
        return code;
    }
    /**
     * 按字符串值解析状态，未知值返回 null。
     *
     * @param code 状态字符串值
     * @return 状态枚举；未匹配时返回 null
     */
    @JsonCreator
    public static WorkflowInstanceStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (WorkflowInstanceStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}
