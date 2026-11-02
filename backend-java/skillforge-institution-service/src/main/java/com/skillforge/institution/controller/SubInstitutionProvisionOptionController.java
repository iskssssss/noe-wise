package com.skillforge.institution.controller;

import com.skillforge.institution.service.SubInstitutionProvisionOptionService;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import org.springframework.web.bind.annotation.*;

/** 总部直属机构开通选项入口，与普通资产候选 Controller 分离。 */
@RestController
@RequestMapping("/api/v1/tenant/sub-institutions/provision-options")
public class SubInstitutionProvisionOptionController {
    private final SubInstitutionProvisionOptionService options;
    public SubInstitutionProvisionOptionController(SubInstitutionProvisionOptionService options) { this.options = options; }
    @GetMapping("/plans")
    public ApiResponse<SubInstitutionProvisionOptionService.Page> plans(@RequestParam(name="q",defaultValue="") String query,
            @RequestParam(name="id",required=false) String id,@RequestParam(name="page",defaultValue="1") int page,
            @RequestParam(name="page_size",defaultValue="200") int size) {
        return ApiResponses.wrap(options.plans(query,id,page,size));
    }
}
