package com.skillforge.institution.controller;

import com.skillforge.core.billing.export.PlatformUsageContracts.Page;
import com.skillforge.core.billing.export.PlatformUsageContracts.Summary;
import com.skillforge.core.billing.export.PlatformUsageQuery;
import com.skillforge.core.billing.export.PlatformUsageService;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 总部读取当前独立获准下级；树关系或汇总权限均不授明细与事件读取。 */
@RestController
@RequestMapping(value="/api/v1/tenant/usage", produces="application/json")
public class HeadquartersUsageController {
    private final PlatformUsageService usage;
    public HeadquartersUsageController(PlatformUsageService usage) { this.usage = usage; }

    @GetMapping("/summary")
    @Operation(summary="读取获准下级用量汇总", description="当前总部主体与独立汇总目标许可，不授明细或增量事件")
    public ApiResponse<Summary> summary(@ModelAttribute PlatformUsageQuery query) {
        return ApiResponses.wrap(usage.summaryHeadquarters(query));
    }
    @GetMapping("/details")
    @Operation(summary="读取获准下级用量明细", description="独立明细动作及当前目标许可，授权或关系变化使原cursor冲突")
    public ApiResponse<Page> details(@ModelAttribute PlatformUsageQuery query) {
        return ApiResponses.wrap(usage.detailsHeadquarters(query));
    }
    @GetMapping("/events")
    @Operation(summary="读取获准下级可靠用量事件", description="独立事件动作及当前目标许可，同一原事件身份不因总部视图改变")
    public ApiResponse<Page> events(@ModelAttribute PlatformUsageQuery query) {
        return ApiResponses.wrap(usage.eventsHeadquarters(query));
    }
}
