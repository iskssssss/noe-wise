package com.skillforge.platform.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import cn.dev33.satoken.stp.StpUtil;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.platform.billing.BillingScanReceipt;
import com.skillforge.platform.billing.PlatformBillingScanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 平台全机构扫描及其只读回执；普通机构角色不拥有全平台操作权限。 */
@RestController
@RequestMapping("/api/v1/platform/billing")
public class PlatformBillingController {
    private final PlatformBillingScanService scans;

    public PlatformBillingController(PlatformBillingScanService scans) { this.scans = scans; }

    @RequirePermission("foundation.billing.run")
    @PostMapping("/process-states")
    public ApiResponse<BillingScanReceipt> processStates(@Valid @RequestBody ScanCommand command) {
        return ApiResponses.wrap(scans.process(Long.parseLong(StpUtil.getLoginIdAsString()), command.operationId()));
    }

    @RequirePermission("foundation.billing.read")
    @GetMapping("/result")
    public ResponseEntity<ApiResponse<BillingScanReceipt>> result(@RequestParam("operationId") String operation) {
        var receipt = scans.find(Long.parseLong(StpUtil.getLoginIdAsString()), operation);
        return ApiResponses.wrap(receipt == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(receipt));
    }

    public record ScanCommand(@NotBlank @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String operationId) {}
}
