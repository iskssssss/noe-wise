package com.skillforge.platform.controller;

import com.skillforge.platform.operationoptions.PlatformOperationOptionService;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 平台表单候选入口；精确动作、目标范围与数据最小化由操作服务统一校验。 */
@RestController
@RequestMapping("/api/v1/platform/operation-options")
public class PlatformOperationOptionController {
    private final PlatformOperationOptionService options;

    public PlatformOperationOptionController(PlatformOperationOptionService options) { this.options = options; }

    @GetMapping("/tenants")
    public ApiResponse<PlatformOperationOptionService.Page> tenants(
            @RequestParam("operation") String operation, @RequestParam(name="q", defaultValue="") String query,
            @RequestParam(name="id", required=false) String id,
            @RequestParam(name="page", defaultValue="1") int page,
            @RequestParam(name="page_size", defaultValue="200") int pageSize) {
        return ApiResponses.wrap(options.tenants(operation, query, id, page, pageSize));
    }

    @GetMapping("/definitions")
    public ApiResponse<PlatformOperationOptionService.Page> definitions(
            @RequestParam("operation") String operation, @RequestParam(name="q", defaultValue="") String query,
            @RequestParam(name="id", required=false) String id,
            @RequestParam(name="page", defaultValue="1") int page,
            @RequestParam(name="page_size", defaultValue="200") int pageSize) {
        return ApiResponses.wrap(options.definitions(operation, query, id, page, pageSize));
    }

    @GetMapping("/integrations")
    public ApiResponse<PlatformOperationOptionService.Page> integrations(
            @RequestParam("operation") String operation, @RequestParam("tenant_id") String tenant,
            @RequestParam(name="q", defaultValue="") String query,
            @RequestParam(name="id", required=false) String id,
            @RequestParam(name="page", defaultValue="1") int page,
            @RequestParam(name="page_size", defaultValue="200") int pageSize) {
        return ApiResponses.wrap(options.integrations(operation, tenant, query, id, page, pageSize));
    }
}
