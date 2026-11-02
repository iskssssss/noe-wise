package com.skillforge.core.foundation.access;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.exception.SaTokenException;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 宿主公共授权入口。每次读取数据库当前账号与角色，撤权立即影响新的受理和后台任务。
 * 历史 Run 的冻结事实不由本组件改写；平台账号不能隐式获得机构资源访问权。
 */
@Service
public class FoundationAccess {
    private final FoundationAccessMapper mapper;
    private final FoundationPermissionCatalogue catalogue;
    private final TransactionTemplate reads;
    /** 仅供已经持久保存可信主体的内部后台任务短暂绑定，不能由 HTTP 请求体直接调用。 */
    private static final ThreadLocal<Subject> TRUSTED_SUBJECT = new ThreadLocal<>();

    /** 构造当前授权组件。 */
    public FoundationAccess(FoundationAccessMapper mapper, FoundationPermissionCatalogue catalogue,
            PlatformTransactionManager transactions) {
        this.mapper = mapper;
        this.catalogue = catalogue;
        this.reads = new TransactionTemplate(transactions);
        this.reads.setReadOnly(true);
    }

    /**
     * 已认证主体，不携带权限或角色事实。
     * @param platform 是否平台账号
     * @param tenantId 机构 ID，平台必须为空
     * @param memberId 账号十进制 ID
     */
    public record Subject(boolean platform, String tenantId, String memberId) {
        /** 拒绝混合账号体系与非法身份。 */
        public Subject {
            AccessValidation.id(memberId, "member_id");
            if (platform && tenantId != null) throw new IllegalArgumentException("平台主体不能携带租户");
            if (!platform) AccessValidation.id(tenantId, "tenant_id");
        }
        /** 返回数据库隔离维度，平台使用保留编码。 */
        public String scope() { return platform ? "platform" : tenantId; }
    }

    /**
     * 数据库当前授权投影，可用于 me；集合不允许修改。
     * @param roles 当前启用角色编码
     * @param permissions 当前有效且已注册权限编码
     * @param authorizationVersion 成员与角色/目录变更共同形成的版本
     */
    public record Snapshot(List<String> roles, List<String> permissions, long authorizationVersion) {
        /** 保护不可变查询结果。 */
        public Snapshot { roles = List.copyOf(roles); permissions = List.copyOf(permissions); }
    }

    /**
     * 本人及部门范围的联合，授权过滤由真实所有权字段完成。
     * @param all 是否全租户
     * @param memberId 当前账号 ID
     * @param departmentIds 授权部门集合，空集合仍保留本人范围
     */
    public record DataScope(boolean all, String memberId, Set<String> departmentIds) {
        /** 固定集合避免调用方放大授权。 */
        public DataScope { departmentIds = Set.copyOf(departmentIds); }
        /** 检查已有资源归属是否在当前授权范围内。 */
        public boolean includes(String ownerMemberId, String departmentId) {
            return all || memberId.equals(ownerMemberId) || (departmentId != null && departmentIds.contains(departmentId));
        }
    }

    /** 从已验证 JWT 读取身份，仅使用账号和租户声明，不信角色声明。 */
    public Subject currentSubject() {
        Subject trusted = TRUSTED_SUBJECT.get();
        if (trusted != null) return trusted;
        try {
            if (!StpUtil.isLogin()) throw authenticationRequired();
            Object tenant = StpUtil.getExtra("tenant_id");
            return new Subject(tenant == null, tenant == null ? null : tenant.toString(), StpUtil.getLoginIdAsString());
        } catch (SaTokenException missingContextOrIdentity) {
            throw authenticationRequired();
        }
    }

    /** 缺失可信主体及缺失 HTTP 认证上下文形成同一明确认证拒绝，不能从租户线程推导用户。 */
    private ApiException authenticationRequired() { return new ApiException(HttpStatus.UNAUTHORIZED, "foundation.authentication.required", "请先登录", false); }

    /** 返回当前主体的实时授权投影。 */
    public Snapshot currentSnapshot() { return snapshot(currentSubject()); }

    /**
     * 平台管理动作的一次当前授权投影；仅供平台治理领域消费，不授予机构业务读取权。
     * @param subject 已验证平台人员
     * @param snapshot 同一次读取的动作、角色及授权版本
     * @param dataScope 同一次读取的人员和部门范围
     */
    public record PlatformAuthorization(Subject subject, Snapshot snapshot, DataScope dataScope) { }

    /** 一次短事务内复用当前账号和角色，避免平台动作与对象过滤重复加载或信任 JWT 角色。 */
    public PlatformAuthorization currentPlatformAuthorization() {
        Subject subject = currentSubject();
        if (!subject.platform()) throw new ApiException(HttpStatus.FORBIDDEN, "仅平台人员可以执行平台管理动作");
        return read(subject, () -> {
            AccessRows.Member member = activeMember(subject);
            List<AccessRows.Role> roles = mapper.memberRoles(subject.scope(), id(subject.memberId()));
            Snapshot current = new Snapshot(roles.stream().map(AccessRows.Role::getCode).sorted().toList(),
                    permissions(subject, roles).stream().sorted().toList(),
                    Math.addExact(member.getAuthorizationVersion(), mapper.scopeVersion(subject.scope())));
            return new PlatformAuthorization(subject, current, dataScope(subject, member, roles));
        });
    }

    /** 为受信后台任务绑定已重新认证的当前主体，结束及异常时恢复原主体和租户。 */
    public <T> T withSubject(Subject subject, Supplier<T> operation) {
        snapshot(subject);
        Subject previous = TRUSTED_SUBJECT.get();
        try {
            TRUSTED_SUBJECT.set(subject);
            return inScope(subject, operation);
        } finally {
            if (previous == null) TRUSTED_SUBJECT.remove(); else TRUSTED_SUBJECT.set(previous);
        }
    }

    /**
     * 为已验证登录凭据或持久后台任务主体查询当前权限；调用方负责主体真实性。
     * 自动绑定并恢复租户，不把请求体中的任意主体当作当前身份。
     */
    public Snapshot snapshot(Subject subject) {
        return read(subject, () -> {
            AccessRows.Member member = activeMember(subject);
            List<AccessRows.Role> roles = mapper.memberRoles(subject.scope(), Long.parseLong(subject.memberId()));
            Set<String> permissions = permissions(subject, roles);
            long version = Math.addExact(member.getAuthorizationVersion(), mapper.scopeVersion(subject.scope()));
            return new Snapshot(roles.stream().map(AccessRows.Role::getCode).sorted().toList(), permissions.stream().sorted().toList(), version);
        });
    }

    /** 根据本次刚读取的当前角色计算动作权限，不能跨授权调用保存这些事实。 */
    private Set<String> permissions(Subject subject, List<AccessRows.Role> roles) {
        Set<String> permissions = new LinkedHashSet<>();
        boolean administrator = roles.stream().anyMatch(AccessRows.Role::isAdministrator);
        if (administrator) catalogue.permissions(subject.platform()).forEach(p -> permissions.add(p.code()));
        else mapper.memberPermissions(subject.scope(), id(subject.memberId())).stream()
                .filter(code -> catalogue.supports(code, subject.platform())).forEach(permissions::add);
        // 本人安全、本人展示偏好及本机构公开品牌只操作固定边界，是明确固有动作。
        for (String code : List.of("foundation.security.self", "foundation.preferences.manage", "foundation.settings.read"))
            if (catalogue.supports(code, subject.platform())) permissions.add(code);
        return permissions;
    }

    /** 当前权限不足、账号停用或编码未注册时拒绝操作。 */
    public void require(String permission) { require(currentSubject(), permission); }

    /** 后台受信主体每次副作用派发前执行实时权限检查。 */
    public void require(Subject subject, String permission) {
        if (!allowed(subject, permission)) throw new ApiException(HttpStatus.FORBIDDEN, "foundation.permission.denied", "没有操作权限", false);
    }

    /** 查询当前主体是否拥有权限；账号失效仍返回认证错误。 */
    public boolean allowed(String permission) { return allowed(currentSubject(), permission); }

    /** 检查受信主体的当前权限；不存在的动作不放行。 */
    public boolean allowed(Subject subject, String permission) {
        Snapshot current = snapshot(subject);
        return catalogue.supports(permission, subject.platform()) && current.permissions().contains(permission);
    }

    /** 返回当前角色联合的数据范围。 */
    public DataScope currentDataScope() { return dataScope(currentSubject()); }

    /**
     * 列表查询前的对象过滤条件，不由前端提供。
     * @param all 是否允许当前租户全部真实资源；仍须在领域 SQL 限定 tenant_id
     * @param resourceIds 非全租户时明确允许的字符串 ID，空集合必须生成 false 条件
     */
    public record ResourceFilter(boolean all, List<String> resourceIds) {
        /** 固定授权集合，不允许消费者修改它扩大范围。 */
        public ResourceFilter { resourceIds = List.copyOf(resourceIds); }
    }

    /** 返回当前角色范围与共享联合的资源过滤，领域必须先过滤再分页与计数。 */
    public ResourceFilter resourceFilter(String kind, String action) { return resourceFilter(currentSubject(), kind, action); }

    /** 后台任务和普通 HTTP 共用批量对象过滤，未知或缺失动作返回空授权。 */
    public ResourceFilter resourceFilter(Subject subject, String kind, String action) {
        AccessValidation.resource(kind, action);
        if (subject.platform()) return new ResourceFilter(false, List.of());
        return read(subject, () -> {
            AccessRows.Member member = activeMember(subject);
            List<AccessRows.Role> roles = mapper.memberRoles(subject.scope(), id(subject.memberId()));
            if (!allowed(subject, roles, "foundation.resource." + kind + "." + action)) return new ResourceFilter(false, List.of());
            DataScope filter = dataScope(subject, member, roles);
            if (filter.all()) return new ResourceFilter(true, List.of());
            return new ResourceFilter(false, mapper.authorizedResourceIds(subject.scope(), kind, action,
                    id(subject.memberId()), member.getDepartmentId(), filter));
        });
    }

    /** 返回受信主体实时数据范围，平台没有机构数据范围。 */
    public DataScope dataScope(Subject subject) {
        if (subject.platform()) throw new ApiException(HttpStatus.FORBIDDEN, "平台主体没有机构数据范围");
        return read(subject, () -> {
            AccessRows.Member member = activeMember(subject);
            List<AccessRows.Role> roles = mapper.memberRoles(subject.scope(), Long.parseLong(subject.memberId()));
            return dataScope(subject, member, roles);
        });
    }

    /** 动作权限和数据范围可共用同一次成员/角色读取，范围仍查询当前有效部门。 */
    private DataScope dataScope(Subject subject, AccessRows.Member member, List<AccessRows.Role> roles) {
        Set<String> departments = new LinkedHashSet<>();
        boolean all = roles.stream().anyMatch(role -> role.isAdministrator() || "all".equals(role.getDataScope()));
        for (AccessRows.Role role : roles) {
            if (member.getDepartmentId() != null && "department".equals(role.getDataScope())
                    && mapper.enabledDepartment(subject.scope(), member.getDepartmentId())) departments.add(member.getDepartmentId().toString());
            if (member.getDepartmentId() != null && "descendants".equals(role.getDataScope()))
                mapper.descendantDepartments(subject.scope(), member.getDepartmentId()).forEach(id -> departments.add(id.toString()));
        }
        mapper.specifiedDepartments(subject.scope(), id(subject.memberId())).forEach(id -> departments.add(id.toString()));
        return new DataScope(all, subject.memberId(), departments);
    }

    /** 当前主体通过动作权限、对象存在性及数据范围/明确共享联合判定后方可访问资源。 */
    public void requireResource(String kind, String resourceId, String action) {
        if (!canAccessResource(currentSubject(), kind, resourceId, action))
            throw new ApiException(HttpStatus.FORBIDDEN, "foundation.resource.denied", "没有资源访问权限", false);
    }

    /** 后台任务和请求入口复用同一当前资源授权。 */
    public boolean canAccessResource(Subject subject, String kind, String resourceId, String action) {
        AccessValidation.resource(kind, action);
        long id = AccessValidation.id(resourceId, "resource_id");
        if (subject.platform()) return false;
        return read(subject, () -> {
            AccessRows.Member member = activeMember(subject);
            List<AccessRows.Role> roles = mapper.memberRoles(subject.scope(), id(subject.memberId()));
            if (!allowed(subject, roles, "foundation.resource." + kind + "." + action)) return false;
            if (!mapper.resourceExists(Long.parseLong(subject.tenantId()), kind, id)) return false;
            DataScope scope = dataScope(subject, member, roles);
            AccessRows.Resource resource = mapper.resource(subject.scope(), kind, resourceId);
            if (scope.all()) return true;
            if (resource != null && scope.includes(resource.getOwnerMemberId().toString(),
                    resource.getDepartmentId() == null ? null : resource.getDepartmentId().toString())) return true;
            return mapper.hasResourceGrant(subject.scope(), id(subject.memberId()), member.getDepartmentId(), kind, resourceId, action);
        });
    }

    /** 会话正文只受会话自身所有权与部门范围约束，Agent共享不授予历史会话访问权。 */
    public void requireSession(Subject subject, String sessionId, String action) {
        AccessValidation.choice(action, Set.of("read", "write"), "session_action"); AccessValidation.text(sessionId, 128, "session_id");
        if (subject.platform()) throw new ApiException(HttpStatus.FORBIDDEN, "平台主体不能获得机构会话正文权限");
        boolean visible = read(subject, () -> {
            // 一次会话检查仅加载一次当前成员和角色；不能复用订阅受理时或前一页的权限。
            AccessRows.Member member = activeMember(subject);
            List<AccessRows.Role> roles = mapper.memberRoles(subject.scope(), id(subject.memberId()));
            String permission = "foundation.sessions." + action;
            if (!allowed(subject, roles, permission))
                throw new ApiException(HttpStatus.FORBIDDEN, "foundation.permission.denied", "没有操作权限", false);
            Long primaryId = mapper.sessionPrimaryId(subject.scope(), sessionId); if (primaryId == null) return false;
            DataScope filter = dataScope(subject, member, roles); if (filter.all()) return true;
            AccessRows.Resource ownership = mapper.resource(subject.scope(), "session", primaryId.toString());
            return ownership != null && filter.includes(ownership.getOwnerMemberId().toString(), ownership.getDepartmentId() == null ? null : ownership.getDepartmentId().toString());
        });
        if (!visible) throw new ApiException(HttpStatus.FORBIDDEN, "foundation.session.denied", "没有会话访问权限", false);
    }
    /** 全租户返回 null，由领域继续限定 tenant_id；其他范围明确返回可见业务键，空集合必须拒绝全部。 */
    public List<String> visibleSessionIds(Subject subject, String action) {
        AccessValidation.choice(action, Set.of("read", "write"), "session_action"); if (subject.platform()) return List.of();
        return read(subject, () -> {
            AccessRows.Member member = activeMember(subject);
            List<AccessRows.Role> roles = mapper.memberRoles(subject.scope(), id(subject.memberId()));
            if (!allowed(subject, roles, "foundation.sessions." + action))
                throw new ApiException(HttpStatus.FORBIDDEN, "foundation.permission.denied", "没有操作权限", false);
            DataScope filter = dataScope(subject, member, roles);
            return filter.all() ? null : mapper.visibleSessionIds(subject.scope(), id(subject.memberId()), filter);
        });
    }

    /** 同一次对象授权复用本次刚读取的角色；不保存到主体、线程或后续调用。 */
    private boolean allowed(Subject subject, List<AccessRows.Role> roles, String permission) {
        return catalogue.supports(permission, subject.platform()) && permissions(subject, roles).contains(permission);
    }

    /**
     * 先绑定精确租户，再为一次授权读取建立短只读事务；已有领域事务继续使用其连接和隔离级别。
     * 使用显式事务模板覆盖本类内部调用，withSubject 不为任意业务回调建立事务。
     */
    private <T> T read(Subject subject, Supplier<T> operation) {
        return inScope(subject, () -> reads.execute(status -> operation.get()));
    }

    /** 检查真实账号状态，停用租户中的成员同时拒绝。 */
    private AccessRows.Member activeMember(Subject subject) {
        AccessRows.Member member = mapper.member(subject.scope(), id(subject.memberId()));
        if (member == null || !"active".equals(member.getStatus()))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "foundation.subject.inactive", "账号不存在、已停用或机构不可用", false);
        return member;
    }

    /** 绑定精确租户并恢复线程上下文，异常也不能污染连接下一次操作的身份。 */
    static <T> T inScope(Subject subject, Supplier<T> operation) {
        String previous = TenantContext.getTenantId();
        try {
            if (subject.platform()) TenantContext.clear(); else TenantContext.setTenantId(subject.tenantId());
            return operation.get();
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
        }
    }

    /** 解析已经校验的宿主 ID。 */
    private static long id(String value) { return Long.parseLong(value); }
}
