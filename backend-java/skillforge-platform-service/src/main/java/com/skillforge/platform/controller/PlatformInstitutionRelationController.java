package com.skillforge.platform.controller;

import com.skillforge.core.foundation.institution.InstitutionRelationContracts.*;
import com.skillforge.core.foundation.institution.InstitutionRelationService;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 平台关系管理；当前动作及child/parent目标由唯一领域服务验证。 */
@RestController
@RequestMapping("/api/v1/platform/institution-relations")
public class PlatformInstitutionRelationController {
    private final InstitutionRelationService relations;
    public PlatformInstitutionRelationController(InstitutionRelationService relations){this.relations=relations;}
    @GetMapping public ApiResponse<Page> list(@RequestParam(name="parent_tenant_id",required=false)Long parent,
            @RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int pageSize) {
        return ApiResponses.wrap(relations.platformList(parent,page,pageSize));
    }
    @PostMapping("/attach") public ApiResponse<Relation> attach(@Valid @RequestBody Change command,@RequestHeader("Idempotency-Key")String key) {
        return ApiResponses.wrap(relations.attach(command,key));
    }
    @PostMapping("/move") public ApiResponse<Relation> move(@Valid @RequestBody Change command,@RequestHeader("Idempotency-Key")String key) {
        return ApiResponses.wrap(relations.move(command,key));
    }
    @PostMapping("/detach") public ApiResponse<Relation> detach(@Valid @RequestBody Change command,@RequestHeader("Idempotency-Key")String key) {
        return ApiResponses.wrap(relations.detach(command,key));
    }
}
