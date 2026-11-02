package com.skillforge.core.foundation.institution;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.core.shared.web.DecimalStringIdDeserializer;
import com.skillforge.core.shared.web.StrictLongDeserializer;
import com.skillforge.core.shared.web.StrictStringDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/** 独立目标部署或用量管理许可的安全协议，不包含 Agent 来源授权或下级成员信息。 */
public final class InstitutionDeploymentManagementContracts {
    private InstitutionDeploymentManagementContracts() { }

    /**
     * 精确直属关系下的一项部署或用量动作许可；撤销为终态，关系变化不会改写本记录。
     * @param grantId 稳定许可 ID
     * @param actorTenantId 获许可的总部机构
     * @param targetTenantId 被管理的精确直属机构
     * @param action 独立许可的完整部署或用量动作
     * @param status active 或 revoked；active 仍须匹配当前关系和机构状态
     * @param revision 许可 CAS 修订
     * @param relationRef 许可绑定的不可变关系依据 ID
     * @param relationRevision 许可绑定的关系修订
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Grant(String grantId, String actorTenantId, String targetTenantId, String action,
            String status, long revision, String relationRef, long relationRevision) { }

    /**
     * 当前平台双目标权限过滤后的许可列表，按稳定 ID 降序。
     * @param items 本页安全许可
     * @param total 过滤后的总数
     * @param page 从 1 开始的页号
     * @param pageSize 每页条数，最大 200
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Page(List<Grant> items, long total, int page, int pageSize) {
        /** 防止调用者改变本次授权过滤的结果。 */
        public Page { items = List.copyOf(items); }
    }

    /**
     * 为当前精确直属关系创建一项许可；同关系动作已有 active 许可时冲突。
     * @param actorTenantId 获许可的总部 ID，必须为 JSON 十进制字符串
     * @param targetTenantId 直属目标 ID，必须为 JSON 十进制字符串
     * @param action 完整部署动作或独立 usage_summary/usage_details/usage_events.read 动作
     * @param expectedRelationRevision 调用者确认的当前关系修订，必须为 JSON 整数
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Create(
            @Schema(type = "string", pattern = "[1-9][0-9]*")
            @JsonDeserialize(using = DecimalStringIdDeserializer.class) @NotNull @Positive Long actorTenantId,
            @Schema(type = "string", pattern = "[1-9][0-9]*")
            @JsonDeserialize(using = DecimalStringIdDeserializer.class) @NotNull @Positive Long targetTenantId,
            @JsonDeserialize(using = StrictStringDeserializer.class) @NotNull String action,
            @JsonDeserialize(using = StrictLongDeserializer.class) @NotNull @Min(1) Long expectedRelationRevision) { }

    /**
     * 撤销精确许可；同一键可重读原结果，新的键不能重复撤销或重新激活。
     * @param grantId 稳定许可 ID，必须为 JSON 十进制字符串
     * @param expectedRevision 调用者确认的许可 CAS 修订，必须为 JSON 整数
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Revoke(
            @Schema(type = "string", pattern = "[1-9][0-9]*")
            @JsonDeserialize(using = DecimalStringIdDeserializer.class) @NotNull @Positive Long grantId,
            @JsonDeserialize(using = StrictLongDeserializer.class) @NotNull @Min(1) Long expectedRevision) { }
}
