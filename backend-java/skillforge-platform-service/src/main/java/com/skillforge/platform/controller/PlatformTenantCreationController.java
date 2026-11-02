package com.skillforge.platform.controller;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/create-with-receipt")
public class PlatformTenantCreationController {
    private final PlatformTenantCreationService service;
    public PlatformTenantCreationController(PlatformTenantCreationService service) { this.service = service; }

    @PostMapping
    public ApiResponse<PlatformTenantCreationService.Receipt> create(
            @Valid @RequestBody PlatformTenantCreationService.Command command) {
        return ApiResponses.wrap(service.create(command));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> invalidRequest(Exception failure) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.failure(
                "TENANT_CREATE_PRE_DISPATCH_REJECTED", "创建请求无效，请检查输入后重试。", false, Map.of()));
    }

    @RequirePermission("foundation.tenants.read")
    @GetMapping("/result")
    public ResponseEntity<ApiResponse<PlatformTenantCreationService.Receipt>> result(@RequestParam("request_id") UUID requestId) {
        var receipt = service.result(requestId);
        return receipt == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(receipt));
    }
}
