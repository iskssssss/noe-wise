package com.skillforge.institution.controller;

import com.skillforge.institution.chat.context.OpenPlatformContextMessageService;
import com.skillforge.institution.chat.context.OpenPlatformContextMessageService.Command;
import com.skillforge.institution.chat.context.OpenPlatformContextMessageService.Receipt;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 只归档外部已记录背景，不能启动或恢复执行。 */
@RestController
@RequestMapping("/api/v1/open-platform/sessions/context-messages")
public final class OpenPlatformContextMessageController {
    private final OpenPlatformContextMessageService service;
    public OpenPlatformContextMessageController(OpenPlatformContextMessageService service) { this.service=service; }
    @PostMapping
    public ApiResponse<Receipt> append(@Valid @RequestBody Command command) { return ApiResponses.wrap(service.append(command)); }
}
