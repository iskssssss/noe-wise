package com.skillforge.core.foundation.access;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 双宿主固定路径选择接口；动作权限由应用服务负责，无额外 access.read 要求。 */
public abstract class FoundationOptionEndpoints {
    private final FoundationOptionService service;
    private final boolean platform;

    /** 宿主固定账号域。 */
    protected FoundationOptionEndpoints(FoundationOptionService service, boolean platform) {
        this.service = service;
        this.platform = platform;
    }

    /** 可管理资源，支持按名称搜索和精确解析已选身份。 */
    @GetMapping("/resources")
    public ApiResponse<FoundationOptionService.Page> resources(@RequestParam String kind,
            @RequestParam(defaultValue = "") String keyword, @RequestParam(required = false) List<String> ids,
            @RequestParam(defaultValue = "1") int page) {
        service.requireHost(platform);
        return ApiResponses.wrap(service.resources(kind, keyword, ids, page));
    }

    /** 安全管理或消息发布的成员选择投影。 */
    @GetMapping("/members")
    public ApiResponse<FoundationOptionService.Page> members(@RequestParam String purpose,
            @RequestParam(defaultValue = "") String keyword, @RequestParam(required = false) List<String> ids,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) String kind, @RequestParam(name = "resource_id", required = false) String resourceId) {
        service.requireHost(platform);
        if ("resource-owner".equals(purpose) || "resource-grantee".equals(purpose))
            return ApiResponses.wrap(service.resourceMembers(purpose, kind, resourceId, keyword, ids, page));
        return ApiResponses.wrap(service.members(purpose, keyword, ids, page));
    }

    /** 消息发布可选部门和角色。 */
    @GetMapping("/recipient-groups")
    public ApiResponse<FoundationOptionService.Page> recipientGroups(@RequestParam String kind,
            @RequestParam(defaultValue = "") String keyword, @RequestParam(required = false) List<String> ids,
            @RequestParam(defaultValue = "1") int page) {
        service.requireHost(platform);
        return ApiResponses.wrap(service.recipientGroups(kind, keyword, ids, page));
    }

    /** 平台功能授权的机构选择。 */
    @GetMapping("/tenants")
    public ApiResponse<FoundationOptionService.Page> tenants(@RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) List<String> ids, @RequestParam(defaultValue = "1") int page) {
        service.requireHost(platform);
        return ApiResponses.wrap(service.tenants(keyword, ids, page));
    }
}
