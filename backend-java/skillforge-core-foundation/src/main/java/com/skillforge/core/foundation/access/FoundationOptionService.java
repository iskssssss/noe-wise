package com.skillforge.core.foundation.access;

import com.skillforge.core.foundation.security.SecurityPermissions;
import com.skillforge.core.shared.exception.ApiException;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理动作所属的最小选择目录；候选和已选解析共用实时权限、账号域与数据范围。 */
@Service
@Transactional(readOnly = true)
public class FoundationOptionService {
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGE = 1_000_000;
    private static final int MAX_SELECTED = 100;
    private static final String PUBLISH = "foundation.messaging.publish";
    private static final String ENTITLEMENTS_READ = "foundation.entitlements.read";
    private final FoundationAccess access;
    private final FoundationOptionMapper mapper;

    /** 只通过所属 Mapper 读取公开选择投影，不调用需要额外组织读取权限的管理列表。 */
    public FoundationOptionService(FoundationAccess access, FoundationOptionMapper mapper) {
        this.access = access;
        this.mapper = mapper;
    }

    /** @param id 精确十进制身份 @param name 名称 @param code 区分同名对象的业务编码 @param status 当前状态 */
    public record Option(String id, String name, String code, String status) { }
    /** @param items 当前页候选 @param hasMore 当前查询是否还有后续页 */
    public record Page(List<Option> items, boolean hasMore) {
        /** 固定公开投影。 */
        public Page { items = List.copyOf(items); }
    }
    /** @param keyword 名称或编码搜索 @param ids 精确解析已选身份，不能绕过同一过滤 @param offset 分页偏移 @param limit 包含探测项的读取上限 */
    public record Query(String keyword, List<String> ids, int offset, int limit) { }

    /** 宿主由固定控制器绑定，客户端不能切换平台或机构身份。 */
    public void requireHost(boolean platform) {
        if (access.currentSubject().platform() != platform) throw denied("账号体系不匹配");
    }

    /** 资源管理候选沿用资源 manage 的明确共享与部门范围，不能由组织权限扩张。 */
    public Page resources(String kind, String keyword, List<String> ids, int page) {
        access.require(AccessPermissions.RESOURCE_MANAGE);
        AccessValidation.resource(kind, "manage");
        var actor = access.currentSubject();
        if (actor.platform()) throw denied("平台主体不能管理机构资源授权");
        Query query = query(keyword, ids, page);
        var filter = access.resourceFilter(kind, "manage");
        return FoundationAccess.inScope(actor, () -> result(mapper.resources(actor.tenantId(), kind, filter, query), query));
    }

    /** 安全目录只返回当前范围成员；公告目录只返回当前发送范围内有效成员。 */
    public Page members(String purpose, String keyword, List<String> ids, int page) {
        AccessValidation.choice(purpose, Set.of("security", "messaging"), "purpose");
        if ("messaging".equals(purpose)) access.require(PUBLISH);
        else {
            var permissions = access.currentSnapshot().permissions();
            if (permissions.stream().noneMatch(Set.of(SecurityPermissions.INVITE, SecurityPermissions.REVOKE,
                    SecurityPermissions.RESET_MFA)::contains)) throw denied("没有成员安全管理权限");
        }
        var actor = access.currentSubject();
        // 安全管理动作按账号体系授权；公告按实际发送目录的范围，平台发送覆盖自身体系。
        var filter = "security".equals(purpose) || actor.platform()
                ? new FoundationAccess.DataScope(true, actor.memberId(), Set.of()) : access.dataScope(actor);
        Query query = query(keyword, ids, page);
        return FoundationAccess.inScope(actor, () -> result(mapper.members(actor.scope(), filter,
                "messaging".equals(purpose), query), query));
    }

    /** 资源授权表单的成员候选；所有者服从当前数据范围，共享目标沿实际服务允许的同机构成员范围。 */
    public Page resourceMembers(String purpose, String kind, String resourceId, String keyword, List<String> ids, int page) {
        AccessValidation.choice(purpose, Set.of("resource-owner", "resource-grantee"), "purpose");
        access.require(AccessPermissions.RESOURCE_MANAGE);
        AccessValidation.resource(kind, "manage");
        AccessValidation.id(resourceId, "resource_id");
        var actor = access.currentSubject();
        if (actor.platform()) throw denied("平台主体不能管理机构资源授权");
        access.requireResource(kind, resourceId, "manage");
        boolean owner = "resource-owner".equals(purpose);
        var filter = owner ? access.dataScope(actor) : new FoundationAccess.DataScope(true, actor.memberId(), Set.of());
        Query query = query(keyword, ids, page);
        return FoundationAccess.inScope(actor, () -> result(mapper.members(actor.scope(), filter, owner, query), query));
    }

    /** 仅显示当前发送范围内确有有效收件人的启用部门或角色，不泄露范围外目录。 */
    public Page recipientGroups(String kind, String keyword, List<String> ids, int page) {
        access.require(PUBLISH);
        AccessValidation.choice(kind, Set.of("department", "role"), "kind");
        var actor = access.currentSubject();
        var filter = actor.platform() ? new FoundationAccess.DataScope(true, actor.memberId(), Set.of()) : access.dataScope(actor);
        Query query = query(keyword, ids, page);
        return FoundationAccess.inScope(actor, () -> result(mapper.recipientGroups(actor.scope(), kind, filter, query), query));
    }

    /** 平台功能授权拥有明确机构查询权，仅返回机构标识、名称、编码和状态。 */
    public Page tenants(String keyword, List<String> ids, int page) {
        if (!access.currentSubject().platform()) throw denied("仅平台主体可选择授权机构");
        access.require(ENTITLEMENTS_READ);
        Query query = query(keyword, ids, page);
        return FoundationAccess.inScope(access.currentSubject(), () -> result(mapper.tenants(query), query));
    }

    private Query query(String keyword, List<String> ids, int page) {
        if (page < 1 || page > MAX_PAGE) throw AccessValidation.bad("分页范围无效");
        String text = keyword == null ? "" : keyword.trim();
        if (text.codePointCount(0, text.length()) > 128) throw AccessValidation.bad("搜索词最多128字符");
        List<String> selected = ids == null ? List.of() : List.copyOf(ids);
        if (selected.size() > MAX_SELECTED) throw AccessValidation.bad("一次最多解析100个已选项目");
        selected.forEach(id -> AccessValidation.id(id, "ids"));
        // 已选解析不分页，但仍使用与候选相同的权限和对象过滤。
        return new Query(text, selected, selected.isEmpty() ? Math.multiplyExact(page - 1, PAGE_SIZE) : 0,
                selected.isEmpty() ? PAGE_SIZE + 1 : MAX_SELECTED + 1);
    }

    private Page result(List<Option> rows, Query query) {
        if (!query.ids().isEmpty()) return new Page(rows, false);
        boolean more = rows.size() > PAGE_SIZE;
        return new Page(more ? rows.subList(0, PAGE_SIZE) : rows, more);
    }

    private static ApiException denied(String message) { return new ApiException(HttpStatus.FORBIDDEN, message); }
}
