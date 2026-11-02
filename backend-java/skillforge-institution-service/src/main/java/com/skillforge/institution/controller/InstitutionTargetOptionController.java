package com.skillforge.institution.controller;

import com.skillforge.institution.service.InstitutionTargetOptionService;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import org.springframework.web.bind.annotation.*;

/** 真实机构动作的最小目标选项，不能作为通用机构目录查询。 */
@RestController
@RequestMapping("/api/v1/tenant/operation-options")
public class InstitutionTargetOptionController {
    private final InstitutionTargetOptionService options;
    public InstitutionTargetOptionController(InstitutionTargetOptionService options) { this.options = options; }

    @GetMapping("/targets")
    public ApiResponse<InstitutionTargetOptionService.Page> targets(@RequestParam(name="operation") String operation,
            @RequestParam(name="q", defaultValue="") String query,
            @RequestParam(name="id", required=false) String id,
            @RequestParam(name="page", defaultValue="1") int page,
            @RequestParam(name="page_size", defaultValue="200") int size) {
        return ApiResponses.wrap(options.targets(operation, query, id, page, size));
    }
}
