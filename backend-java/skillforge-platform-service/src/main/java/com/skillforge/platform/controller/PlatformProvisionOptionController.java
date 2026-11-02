package com.skillforge.platform.controller;

import com.skillforge.platform.operationoptions.PlatformOperationOptionService;
import com.skillforge.platform.operationoptions.PlatformProvisionOptionService;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

/** 开通与签发命名空间候选，不开放其他目录正文。 */
@RestController
@RequestMapping("/api/v1/platform/operation-options")
public class PlatformProvisionOptionController {
    private final PlatformProvisionOptionService options;
    public PlatformProvisionOptionController(PlatformProvisionOptionService options) { this.options = options; }

    @GetMapping("/provision/plans")
    public ApiResponse<PlatformOperationOptionService.Page> plans(@RequestParam(name="q",defaultValue="") String query,
            @RequestParam(name="id",required=false) String id, @RequestParam(name="page",defaultValue="1") int page,
            @RequestParam(name="page_size",defaultValue="200") int size) {
        return ApiResponses.wrap(options.plans(query,id,page,size));
    }

    @GetMapping("/namespaces")
    public ApiResponse<PlatformProvisionOptionService.Namespaces> namespaces(@RequestParam("operation") String operation,
            @RequestParam(name="scopes",required=false) Set<String> scopes, @RequestParam(name="q",defaultValue="") String query,
            @RequestParam(name="page",defaultValue="1") int page, @RequestParam(name="page_size",defaultValue="200") int size) {
        return ApiResponses.wrap(options.namespaces(operation, scopes == null ? Set.of() : scopes, query, page, size));
    }
}
