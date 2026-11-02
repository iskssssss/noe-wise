package com.skillforge.platform.dto.tenant;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import java.util.Set;

/**
 * 平台更新机构信息请求 DTO。
 *
 * <p>全部字段可空，空值表示不修改对应属性；resetQuotaOverrides 明确清除指定配额覆盖。
 *
 * @param name                            机构名称
 * @param planCode                        套餐编码：free / pro / enterprise
 * @param status                          机构状态：active / suspended / warning
 * @param deploymentMode                  部署模式：cloud / hybrid / on_premise
 * @param overrideQuotaApiCallsPerMonth   月度 API 调用配额覆盖
 * @param overrideRateLimitPerMinute      每分钟限流覆盖
 */
public record UpdateTenantRequest(
        @Size(max = 128) String name,
        String planCode,
        String status,
        String deploymentMode,
        @Min(0) Integer overrideQuotaApiCallsPerMonth,
        @Min(0) Integer overrideRateLimitPerMinute,
        Set<String> resetQuotaOverrides) {
}
