package com.skillforge.institution.controller;

import com.skillforge.core.billing.export.PlatformUsageContracts.Page;
import com.skillforge.core.billing.export.PlatformUsageContracts.Summary;
import com.skillforge.core.billing.export.PlatformUsageQuery;
import com.skillforge.core.billing.export.PlatformUsageService;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;

/** 当前Tenant授权域内读取同一平台发布事实，身份和游标范围由服务端绑定。 */
@RestController
@RequestMapping(value="/api/v1/admin/usage",produces="application/json")
public class TenantUsageController {
    private final PlatformUsageService usage;
    /** 复用同一原子账本、发布流和当前授权查询。 */
    public TenantUsageController(PlatformUsageService usage) { this.usage=usage; }
    /** events仅在当前授权集合内过滤，cursor变化要求显式重建。 */
    @com.skillforge.core.foundation.access.RequirePermission("foundation.usage.read")
    @GetMapping("/events")
    @Operation(summary="读取用量events",description="固定当前身份与目标；cursor为防篡改不透明值，缺省从历史起点开始，授权或过滤变化409")
    public ApiResponse<Page> events(@ModelAttribute PlatformUsageQuery query) {
        return ApiResponses.wrap(usage.eventsTenant(query));
    }
    /** summary仅在当前授权集合内过滤，cursor变化要求显式重建。 */
    @com.skillforge.core.foundation.access.RequirePermission("foundation.usage.read")
    @GetMapping("/summary")
    @Operation(summary="读取用量summary",description="固定当前身份与目标；cursor为防篡改不透明值，缺省从历史起点开始，授权或过滤变化409")
    public ApiResponse<Summary> summary(@ModelAttribute PlatformUsageQuery query) {
        return ApiResponses.wrap(usage.summaryTenant(query));
    }
    /** details仅在当前授权集合内过滤，cursor变化要求显式重建。 */
    @com.skillforge.core.foundation.access.RequirePermission("foundation.usage.read")
    @GetMapping("/details")
    @Operation(summary="读取用量details",description="固定当前身份与目标；cursor为防篡改不透明值，缺省从历史起点开始，授权或过滤变化409")
    public ApiResponse<Page> details(@ModelAttribute PlatformUsageQuery query) {
        return ApiResponses.wrap(usage.detailsTenant(query));
    }

    /** 搜索或精确回显当前用量视图授权范围中的历史身份。 */
    @com.skillforge.core.foundation.access.RequirePermission("foundation.usage.read")
    @GetMapping("/filter-options")
    @Operation(summary="读取用量历史筛选候选", description="只返回当前视图获准历史记录中的身份；支持名称或标识搜索及精确回显，不扩大当前目录权限")
    public ApiResponse<java.util.List<com.skillforge.core.billing.export.PlatformUsageContracts.FilterOption>> filterOptions(
            @ModelAttribute com.skillforge.core.billing.export.PlatformUsageOptionsQuery query) {
        return ApiResponses.wrap(usage.optionsTenant(query));
    }
}
