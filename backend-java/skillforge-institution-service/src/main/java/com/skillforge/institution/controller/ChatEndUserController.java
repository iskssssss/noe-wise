package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import com.skillforge.core.chat.dto.response.ChatEndUserItemResponse;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.institution.common.TenantIds;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 机构端对话工作区的终端用户选择接口。
 *
 * <p>仅返回当前租户内已有用户，供管理员或开发者显式冻结
 * {@code end_user_id}；支持管理人员显式登记，不创建或选择默认用户。</p>
 */
@Tag(name = "对话", description = "机构端对话工作区终端用户选择")
@RestController
@RequestMapping("/api/v1/chat")
public class ChatEndUserController {

    /** 终端用户查询服务。 */
    private final EndUserService endUserService;

    /**
     * 构造终端用户选择接口。
     *
     * @param endUserService 终端用户查询服务
     */
    public ChatEndUserController(EndUserService endUserService) {
        this.endUserService = endUserService;
    }

    /**
     * 列出当前租户可用于对话的终端用户。
     *
     * @return 终端用户列表，按创建时间升序
     */
    @Operation(summary = "列出对话终端用户",
            description = "返回当前租户已有终端用户，供机构端对话显式选择 end_user_id；不会推导默认用户")
    @RequirePermission("foundation.sessions.read")
    @GetMapping("/end-users")
    public ApiResponse<List<ChatEndUserItemResponse>> endUsers() {
        return ApiResponses.wrap(endUserService.listByTenant(TenantIds.requireTenantId()).stream()
                .map(user -> new ChatEndUserItemResponse(
                        user.getId(), user.getDisplayName(), user.getStatus()))
                .toList());
    }

    /** 显式新增当前租户的终端用户，供对话前选择。 */
    @RequirePermission("foundation.sessions.create")
    @PostMapping("/end-users")
    public ApiResponse<ChatEndUserItemResponse> create(@Valid @RequestBody CreateEndUserRequest request) {
        var user = endUserService.create(TenantIds.requireTenantId(), request.displayName());
        return ApiResponses.wrap(new ChatEndUserItemResponse(user.getId(), user.getDisplayName(), user.getStatus()));
    }

    public record CreateEndUserRequest(@NotBlank @Size(max = 128) String displayName) {}
}
