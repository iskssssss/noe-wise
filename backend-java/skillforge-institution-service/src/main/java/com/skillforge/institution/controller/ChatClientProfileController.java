package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import com.skillforge.institution.chat.ChatClientProfileService;
import com.skillforge.institution.chat.ChatEndUserTokens;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 开放聊天入口的本机身份登记，不提供管理端用户列表能力。 */
@RestController
public class ChatClientProfileController {
    private final ChatClientProfileService profiles;

    public ChatClientProfileController(ChatClientProfileService profiles) {
        this.profiles = profiles;
    }

    @Operation(summary = "登记或恢复本机聊天身份", description = "使用已认证渠道与 client_ref 幂等登记，恢复时保留首次昵称")
    @PostMapping("/api/v1/chat/client-profile")
    public ApiResponse<ChatClientProfileService.Profile> register(@Valid @RequestBody RegisterRequest request,
            @RequestHeader(value = ChatEndUserTokens.HEADER, required = false) String identityToken) {
        return ApiResponses.wrap(profiles.register(request.clientRef(), request.displayName(), identityToken));
    }

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String clientRef,
            @NotBlank @Size(max = 128) String displayName) {}
}
