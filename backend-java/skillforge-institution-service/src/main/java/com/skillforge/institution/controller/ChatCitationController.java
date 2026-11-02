package com.skillforge.institution.controller;

import com.skillforge.core.chat.trajectory.ChatCitationReadService;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 机构成员按已公开的完成消息读取受当前来源授权约束的引用片段。 */
@RestController
@RequestMapping("/api/v1/chat/citations")
public class ChatCitationController {
    private final ChatCitationReadService citations;

    public ChatCitationController(ChatCitationReadService citations) { this.citations = citations; }

    @RequirePermission("foundation.sessions.read")
    @GetMapping("/content")
    public ApiResponse<ChatCitationReadService.Citation> content(@RequestParam("session_id") String session,
            @RequestParam("message_id") String message, @RequestParam("citation_id") String citation,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponses.wrap(citations.read(session, message, citation));
    }
}
