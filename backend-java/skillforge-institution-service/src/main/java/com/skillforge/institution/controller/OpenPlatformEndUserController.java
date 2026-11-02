package com.skillforge.institution.controller;

import com.skillforge.core.foundation.integration.OpenPlatformAccessService;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

/** 外部用户注册只绑定认证 Tenant/integration；相同引用的并发重试返回同一 EndUser。 */
@RestController
@RequestMapping(value="/api/v1/open-platform/end-users",produces="application/json")
public class OpenPlatformEndUserController {
    private final OpenPlatformAccessService access;
    private final EndUserService users;
    public OpenPlatformEndUserController(OpenPlatformAccessService access,EndUserService users) {
        this.access=access; this.users=users;
    }
    @PostMapping("/ensure")
    public ApiResponse<ExternalEndUser> ensure(@Valid @RequestBody EnsureEndUser request) {
        var principal=access.require("end_users.write").principal();
        var user=users.ensureExternal(principal.tenantId(),principal.integrationId(),request.externalUserRef(),request.displayName());
        return ApiResponses.wrap(new ExternalEndUser(user.getId().toString(),principal.integrationId(),user.getExternalUserRef()));
    }
    public record EnsureEndUser(@NotBlank @Size(max=512) String externalUserRef,@Size(max=128) String displayName) {}
    public record ExternalEndUser(
            @io.swagger.v3.oas.annotations.media.Schema(description="精确十进制EndUser身份",pattern="[1-9][0-9]{0,18}",maxLength=19)
            String endUserId,String integrationId,String externalUserRef) {}
}
