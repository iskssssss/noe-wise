package com.skillforge.institution.controller;

import com.skillforge.core.foundation.institution.InstitutionRelationContracts.*;
import com.skillforge.core.foundation.institution.InstitutionRelationService;
import com.skillforge.core.foundation.integration.IntegrationContracts.ProvisionInstitution;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import org.springframework.web.bind.annotation.*;

/** 总部仅创建新直属机构与读取明确获准的安全目录，不认领已有机构。 */
@RestController
@RequestMapping("/api/v1/tenant/sub-institutions")
public class SubInstitutionController {
    private final InstitutionRelationService relations;
    public SubInstitutionController(InstitutionRelationService relations){this.relations=relations;}
    @GetMapping public ApiResponse<Page> list(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int pageSize) {
        return ApiResponses.wrap(relations.subInstitutions(page,pageSize));
    }
    @PostMapping("/provision") public ApiResponse<Relation> provision(@RequestBody ProvisionInstitution request,@RequestHeader("Idempotency-Key")String key) {
        return ApiResponses.wrap(relations.provisionSubInstitution(request,key));
    }
}
