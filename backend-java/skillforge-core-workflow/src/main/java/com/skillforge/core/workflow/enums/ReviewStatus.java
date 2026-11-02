package com.skillforge.core.workflow.enums;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
/**
 * 工作流审核 UI 状态枚举。
 *
 * <p>审核队列对外暴露 pending / approved / rejected 三种状态，
 * pending 来自待审核模板，approved/rejected 来自已保存的审核决定。
 */
public enum ReviewStatus {
    /** 待审核。 */
    PENDING("pending"),
    /** 已通过。 */
    APPROVED("approved"),
    /** 已驳回。 */
    REJECTED("rejected");
    /** 状态字符串值。 */
    private final String code;
    /**
     * 构造状态枚举。
     *
     * @param code 状态字符串值
     */
    ReviewStatus(String code) {
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
    public static ReviewStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ReviewStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}
