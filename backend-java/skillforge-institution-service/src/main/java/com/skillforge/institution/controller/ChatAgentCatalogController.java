package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import com.skillforge.institution.service.ChatChannelAccess;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 独立聊天端的渠道智能体选择入口。 */
@RestController
public class ChatAgentCatalogController {
    private final ChatChannelAccess access;
    public ChatAgentCatalogController(ChatChannelAccess access) { this.access = access; }

    @GetMapping("/api/v1/chat/agents")
    public ApiResponse<ChatChannelAccess.Catalog> agents() { return ApiResponses.wrap(access.catalog()); }
}
