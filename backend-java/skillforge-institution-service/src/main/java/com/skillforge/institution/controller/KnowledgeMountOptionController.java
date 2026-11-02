package com.skillforge.institution.controller;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.service.KnowledgeMountOptionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 选中知识库的当前发布挂载身份。 */
@RestController
@RequestMapping("/api/v1/knowledge/bases")
public class KnowledgeMountOptionController {
    private final KnowledgeMountOptionService options;
    public KnowledgeMountOptionController(KnowledgeMountOptionService options) { this.options = options; }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/mount-option")
    public ApiResponse<KnowledgeMountOptionService.Option> option(@RequestParam("kbId") Long kbId) {
        return ApiResponses.wrap(options.option(kbId));
    }
}
