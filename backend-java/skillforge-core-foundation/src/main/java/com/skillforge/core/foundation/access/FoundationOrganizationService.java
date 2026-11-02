package com.skillforge.core.foundation.access;

import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import com.skillforge.core.foundation.catalog.entitlements.ResourceKind;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 组织与正式权限的事务所有者。账号体系行锁保护最后管理员与部门树，
 * 编辑版本防止覆盖并发修改，权限和范围必须是操作者当前权限的子集。
 */
@Service
public class FoundationOrganizationService {
    private final FoundationAccess access;
    private final FoundationOrganizationMapper mapper;
    private final FoundationAccessMapper authorization;
    private final FoundationPermissionCatalogue catalogue;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final List<AccessChangeListener> listeners;
    private final FeatureGate features;
    private final ResourceQuota quota;

    /** 注入所属存储、当前授权与事务提交后的安全监听者。 */
    public FoundationOrganizationService(FoundationAccess access, FoundationOrganizationMapper mapper,
            FoundationAccessMapper authorization, FoundationPermissionCatalogue catalogue,
            PasswordEncoder passwordEncoder, AuditService audit, List<AccessChangeListener> listeners,
            FeatureGate features, ResourceQuota quota) {
        this.access = access; this.mapper = mapper; this.authorization = authorization;
        this.catalogue = catalogue; this.passwordEncoder = passwordEncoder; this.audit = audit;
        this.listeners = List.copyOf(listeners);
        this.features=features; this.quota=quota;
    }

    /** 查询当前账号体系部门树，不暴露其他机构节点。 */
    public List<AccessViews.Department> departments() {
        return read(() -> mapper.departments(subject().scope()).stream().map(this::departmentView).toList());
    }
    /** 查询当前账号体系岗位。 */
    public List<AccessViews.Position> positions() {
        return read(() -> mapper.positions(subject().scope()).stream().map(this::positionView).toList());
    }
    /** 查询当前体系已注册权限目录，用于明确选择真实动作。 */
    public List<FoundationPermission> permissions() { return read(() -> catalogue.permissions(subject().platform())); }
    /** 分页查询实时数据范围内的成员，列表不返回凭据。 */
    public AccessViews.MemberPage members(String keyword, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100 || page > 1_000_000) throw AccessValidation.bad("分页范围无效");
        if (keyword != null && keyword.codePointCount(0, keyword.length()) > 128) throw AccessValidation.bad("搜索词过长");
        return read(() -> {
            FoundationAccess.Subject actor = subject(); FoundationAccess.DataScope filter = filter(actor);
            List<AccessRows.Member> rows = mapper.members(actor.scope(), filter, keyword, Math.multiplyExact(page-1,pageSize), pageSize);
            return new AccessViews.MemberPage(memberViews(actor.scope(), rows), mapper.memberCount(actor.scope(), filter, keyword), page, pageSize);
        });
    }
    /** 返回自定义角色和不可变内置角色的完整定义，采用批量权限与部门投影。 */
    public List<AccessViews.Role> roles() { return read(() -> roleViews(subject().scope())); }

    /** 正式角色投影可读取停用目标；跨机构仅真实平台tenants.read治理入口可调用。 */
    public List<String> memberRoleCodes(FoundationAccess.Subject target) {
        FoundationAccess.Subject actor=subject();
        if(actor.platform() && !target.platform()) access.require("foundation.tenants.read");
        else { access.require(AccessPermissions.READ); if(!actor.scope().equals(target.scope())) throw denied("不能读取其他账号体系成员角色"); }
        return FoundationAccess.inScope(target,()-> {
            AccessRows.Member member=member(target.scope(),id(target.memberId())); if(!actor.platform()) requireMemberRange(actor,member,false);
            return mapper.memberRoleRows(target.scope(),List.of(member.getId())).stream().map(AccessRows.MemberRole::getCode).sorted().toList();
        });
    }

    /** 在当前体系创建部门，父节点必须同体系且启用。 */
    @Transactional public AccessViews.Department createDepartment(AccessCommands.DepartmentCreate command) {
        return write(AccessPermissions.ORGANIZATION_MANAGE, () -> {
            FoundationAccess.Subject actor = subject(); lock(actor.scope());
            AccessRows.Department row = new AccessRows.Department(); row.setId(SnowflakeIds.nextId());
            row.setCode(AccessValidation.code(command.code())); row.setName(AccessValidation.text(command.name(),128,"name"));
            row.setParentId(optionalId(command.parentId(),"parent_id")); row.setEnabled(true); row.setLockVersion(1);
            validateDepartment(actor.scope(), row.getParentId()); mapper.insertDepartment(actor.scope(),row,id(actor.memberId()));
            record(actor,"permission.department.create","department",row.getId().toString()); return departmentView(row);
        });
    }
    /** 完整替换部门；树行锁下沿父链拒绝循环，未通过检查不写入。 */
    @Transactional public AccessViews.Department updateDepartment(AccessCommands.DepartmentUpdate command) {
        return write(AccessPermissions.ORGANIZATION_MANAGE, () -> {
            FoundationAccess.Subject actor = subject(); lock(actor.scope()); AccessRows.Department row = department(actor.scope(),id(command.id()));
            version(command.lockVersion()); row.setName(AccessValidation.text(command.name(),128,"name"));
            row.setParentId(optionalId(command.parentId(),"parent_id")); row.setEnabled(flag(command.enabled())); row.setLockVersion(command.lockVersion());
            validateDepartment(actor.scope(),row.getParentId()); Long cursor=row.getParentId(); Set<Long> visited=new HashSet<>();
            while(cursor!=null) {
                if(cursor.equals(row.getId()) || !visited.add(cursor)) throw AccessValidation.bad("部门不能形成循环");
                cursor=department(actor.scope(),cursor).getParentId();
            }
            changed(mapper.updateDepartment(actor.scope(),row,id(actor.memberId()))); mapper.bumpScope(actor.scope()); row.setLockVersion(row.getLockVersion()+1);
            record(actor,"permission.department.update","department",row.getId().toString()); return departmentView(row);
        });
    }
    /** 只删除没有子节点、成员、资源或角色范围引用的部门，数据库 FK 是最终保护。 */
    @Transactional public void deleteDepartment(AccessCommands.Delete command) {
        write(AccessPermissions.ORGANIZATION_MANAGE, () -> {
            FoundationAccess.Subject actor=subject(); lock(actor.scope()); department(actor.scope(),id(command.id())); version(command.lockVersion());
            changed(mapper.deleteDepartment(actor.scope(),id(command.id()),command.lockVersion())); mapper.bumpScope(actor.scope());
            record(actor,"permission.department.delete","department",command.id()); return null;
        });
    }
    /** 创建当前体系岗位。 */
    @Transactional public AccessViews.Position createPosition(AccessCommands.PositionCreate command) {
        return write(AccessPermissions.ORGANIZATION_MANAGE, () -> {
            FoundationAccess.Subject actor=subject(); lock(actor.scope()); AccessRows.Position row=new AccessRows.Position(); row.setId(SnowflakeIds.nextId());
            row.setCode(AccessValidation.code(command.code())); row.setName(AccessValidation.text(command.name(),128,"name")); row.setEnabled(true); row.setLockVersion(1);
            mapper.insertPosition(actor.scope(),row,id(actor.memberId())); record(actor,"permission.position.create","position",row.getId().toString()); return positionView(row);
        });
    }
    /** CAS 替换岗位定义。 */
    @Transactional public AccessViews.Position updatePosition(AccessCommands.PositionUpdate command) {
        return write(AccessPermissions.ORGANIZATION_MANAGE, () -> {
            FoundationAccess.Subject actor=subject(); lock(actor.scope()); AccessRows.Position row=position(actor.scope(),id(command.id()));
            version(command.lockVersion()); row.setName(AccessValidation.text(command.name(),128,"name")); row.setEnabled(flag(command.enabled())); row.setLockVersion(command.lockVersion());
            changed(mapper.updatePosition(actor.scope(),row,id(actor.memberId()))); row.setLockVersion(row.getLockVersion()+1);
            mapper.bumpScope(actor.scope()); record(actor,"permission.position.update","position",command.id()); return positionView(row);
        });
    }
    /** 引用中的岗位不允许删除。 */
    @Transactional public void deletePosition(AccessCommands.Delete command) {
        write(AccessPermissions.ORGANIZATION_MANAGE, () -> {
            FoundationAccess.Subject actor=subject(); lock(actor.scope()); position(actor.scope(),id(command.id())); version(command.lockVersion());
            changed(mapper.deletePosition(actor.scope(),id(command.id()),command.lockVersion())); mapper.bumpScope(actor.scope());
            record(actor,"permission.position.delete","position",command.id()); return null;
        });
    }

    /**
     * 创建真实账号、资料和多角色；缺省密码为待邀请状态，随机密码只用于不可登录哈希。
     * 平台和机构分别写入自己的成员表。
     */
    @Transactional public AccessViews.Member createMember(AccessCommands.MemberCreate command) {
        return write(AccessPermissions.ORGANIZATION_MANAGE, () -> {
            FoundationAccess.Subject actor=subject(); access.require(AccessPermissions.ROLE_MANAGE); lock(actor.scope());
            if(!actor.platform()) { features.require(actor.tenantId(),"members"); quota.requireCapacity(actor.tenantId(),ResourceKind.MEMBER,1); }
            AccessRows.Member row=new AccessRows.Member(); row.setId(SnowflakeIds.nextId());
            row.setUsername(AccessValidation.text(command.username(),128,"username")); row.setDisplayName(AccessValidation.text(command.displayName(),128,"display_name")); row.setEmail(email(command.email()));
            row.setDepartmentId(optionalId(command.departmentId(),"department_id")); row.setPositionId(optionalId(command.positionId(),"position_id"));
            validateDepartment(actor.scope(),row.getDepartmentId()); validatePosition(actor.scope(),row.getPositionId()); requireMemberRange(actor,row,true);
            List<AccessRows.Role> roles=validateAssignedRoles(actor,row,command.roleIds()); if(roles.isEmpty()) throw AccessValidation.bad("创建成员至少需要一个角色");
            String password=command.password();
            if(password==null) {
                if(row.getEmail()==null) throw AccessValidation.bad("邀请成员必须提供邮箱"); row.setStatus("pending");
                password=java.util.UUID.randomUUID().toString()+java.util.UUID.randomUUID().toString();
            } else { validatePassword(password); row.setStatus("active"); }
            row.setLockVersion(1); row.setAuthorizationVersion(1); mapper.insertMember(actor.scope(),row,passwordEncoder.encode(password));
            mapper.ensureProfile(actor.scope(),row.getId(),row.getDisplayName()); changed(mapper.updateProfile(actor.scope(),row));
            for(AccessRows.Role role:roles) mapper.insertMemberRole(actor.scope(),row.getId(),role.getId());
            record(actor,"permission.member.create","member",row.getId().toString());
            return memberViews(actor.scope(),List.of(authorization.member(actor.scope(),row.getId()))).getFirst();
        });
    }
    /** 完整替换资料并原子变更状态；最后 active 管理员不能停用。 */
    @Transactional public AccessViews.Member updateMember(AccessCommands.MemberUpdate command) {
        return write(AccessPermissions.ORGANIZATION_MANAGE, () -> {
            FoundationAccess.Subject actor=subject(); lock(actor.scope()); AccessRows.Member existing=member(actor.scope(),id(command.id())); requireMemberRange(actor,existing,false); version(command.lockVersion());
            String status=AccessValidation.choice(command.status(),Set.of("active","disabled","pending","invited"),"status");
            // 邀请激活及凭据写入归安全服务；资料接口不能直接启用未激活账号。
            if(Set.of("pending","invited").contains(existing.getStatus()) && "active".equals(status)) throw AccessValidation.bad("待邀请成员必须先通过邀请激活");
            if("invited".equals(status) && !"invited".equals(existing.getStatus())) throw AccessValidation.bad("邀请状态只能通过安全邀请入口写入");
            if("pending".equals(status) && !"pending".equals(existing.getStatus())) throw AccessValidation.bad("已激活账号不能回到待邀请状态");
            protectLastAdministrator(actor.scope(),existing.getId(),!"active".equals(status));
            AccessRows.Member row=new AccessRows.Member(); row.setId(existing.getId()); row.setLockVersion(command.lockVersion());
            row.setDisplayName(AccessValidation.text(command.displayName(),128,"display_name")); row.setEmail(email(command.email()));
            row.setDepartmentId(optionalId(command.departmentId(),"department_id")); row.setPositionId(optionalId(command.positionId(),"position_id")); row.setStatus(status);
            validateDepartment(actor.scope(),row.getDepartmentId()); validatePosition(actor.scope(),row.getPositionId()); requireMemberRange(actor,row,false);
            changed(mapper.updateProfile(actor.scope(),row)); mapper.updateMemberAccount(actor.scope(),row); notifyChanged(actor,row.getId());
            record(actor,"permission.member.update","member",command.id()); return memberViews(actor.scope(),List.of(member(actor.scope(),row.getId()))).getFirst();
        });
    }
    /** CSV 预检复用真实资料范围与引用校验，不锁写、不更改任何账号事实。 */
    public void validateMemberProfile(AccessCommands.MemberUpdate command) {
        write(AccessPermissions.ORGANIZATION_MANAGE, () -> {
            FoundationAccess.Subject actor=subject(); AccessRows.Member existing=member(actor.scope(),id(command.id()));
            requireMemberRange(actor,existing,false); version(command.lockVersion());
            if(existing.getLockVersion()!=command.lockVersion()) throw conflict();
            if(!existing.getStatus().equals(command.status())) throw AccessValidation.bad("成员资料导入不能改变账号状态");
            AccessRows.Member row=new AccessRows.Member(); row.setId(existing.getId());
            row.setDisplayName(AccessValidation.text(command.displayName(),128,"display_name")); row.setEmail(email(command.email()));
            row.setDepartmentId(optionalId(command.departmentId(),"department_id")); row.setPositionId(optionalId(command.positionId(),"position_id"));
            validateDepartment(actor.scope(),row.getDepartmentId()); validatePosition(actor.scope(),row.getPositionId()); requireMemberRange(actor,row,false);
            return null;
        });
    }
    /** 完整替换多角色；账号体系锁和 active 管理员计数保护并发降级。 */
    @Transactional public AccessViews.Member assignRoles(AccessCommands.MemberRoles command) {
        return write(AccessPermissions.ROLE_MANAGE, () -> {
            FoundationAccess.Subject actor=subject(); lock(actor.scope()); AccessRows.Member member=member(actor.scope(),id(command.memberId())); requireMemberRange(actor,member,false); version(command.lockVersion());
            List<AccessRows.Role> roles=validateAssignedRoles(actor,member,command.roleIds()); protectLastAdministrator(actor.scope(),member.getId(),roles.stream().noneMatch(AccessRows.Role::isAdministrator));
            changed(mapper.bumpMember(actor.scope(),member.getId(),command.lockVersion())); mapper.deleteMemberRoles(actor.scope(),member.getId());
            for(AccessRows.Role role:roles) mapper.insertMemberRole(actor.scope(),member.getId(),role.getId());
            notifyChanged(actor,member.getId()); record(actor,"permission.member.roles","member",command.memberId()); return memberViews(actor.scope(),List.of(member(actor.scope(),member.getId()))).getFirst();
        });
    }
    /** 新建自定义角色，仅授予操作者已经拥有的权限和数据范围。 */
    @Transactional public AccessViews.Role createRole(AccessCommands.RoleCreate command) {
        return write(AccessPermissions.ROLE_MANAGE, () -> {
            FoundationAccess.Subject actor=subject(); lock(actor.scope()); AccessRows.Role role=new AccessRows.Role(); role.setId(SnowflakeIds.nextId());
            role.setCode(AccessValidation.code(command.code())); role.setName(AccessValidation.text(command.name(),128,"name"));
            role.setDataScope(validateRoleDefinition(actor,command.dataScope(),command.permissions(),command.departmentIds())); role.setEnabled(true); role.setLockVersion(1);
            mapper.insertRole(actor.scope(),role); replaceRoleDetails(actor.scope(),role.getId(),command.permissions(),command.departmentIds()); mapper.bumpScope(actor.scope());
            record(actor,"permission.role.create","role",role.getId().toString()); return roleViews(actor.scope()).stream().filter(v->v.id().equals(role.getId().toString())).findFirst().orElseThrow();
        });
    }
    /** 替换自定义角色定义；内置角色包含管理员均不可改。 */
    @Transactional public AccessViews.Role updateRole(AccessCommands.RoleUpdate command) {
        return write(AccessPermissions.ROLE_MANAGE, () -> {
            FoundationAccess.Subject actor=subject(); lock(actor.scope()); AccessRows.Role role=role(actor.scope(),id(command.id()));
            if(role.isBuiltIn()) throw denied("内置角色不可修改"); version(command.lockVersion());
            role.setName(AccessValidation.text(command.name(),128,"name")); role.setDataScope(validateRoleDefinition(actor,command.dataScope(),command.permissions(),command.departmentIds()));
            role.setEnabled(flag(command.enabled())); role.setLockVersion(command.lockVersion()); changed(mapper.updateRole(actor.scope(),role));
            replaceRoleDetails(actor.scope(),role.getId(),command.permissions(),command.departmentIds()); mapper.bumpScope(actor.scope());
            for(Long memberId:mapper.roleMembers(actor.scope(),role.getId())) notifyChanged(actor,memberId);
            record(actor,"permission.role.update","role",command.id()); return roleViews(actor.scope()).stream().filter(v->v.id().equals(command.id())).findFirst().orElseThrow();
        });
    }
    /** 删除无成员引用的自定义角色，内置角色永久保留。 */
    @Transactional public void deleteRole(AccessCommands.Delete command) {
        write(AccessPermissions.ROLE_MANAGE, () -> {
            FoundationAccess.Subject actor=subject(); lock(actor.scope()); AccessRows.Role role=role(actor.scope(),id(command.id()));
            if(role.isBuiltIn()) throw denied("内置角色不可删除"); version(command.lockVersion());
            changed(mapper.deleteRole(actor.scope(),role.getId(),command.lockVersion())); mapper.bumpScope(actor.scope()); record(actor,"permission.role.delete","role",command.id()); return null;
        });
    }
    /** 获取当前有 manage 访问权限资源的授权详情。 */
    public AccessViews.Resource resource(String kind,String resourceId) {
        access.require(AccessPermissions.RESOURCE_MANAGE); access.requireResource(kind,resourceId,"manage");
        return FoundationAccess.inScope(subject(), () -> resourceView(subject().scope(),kind,resourceId));
    }
    /**
     * 完整替换资源所有权及明确共享；对象和目标必须同机构。
     * 新登记资源需全租户管理者初始化，后续 CAS 防止丢失授权编辑。
     */
    @Transactional public AccessViews.Resource authorizeResource(AccessCommands.ResourceAuthorization command) {
        return write(AccessPermissions.RESOURCE_MANAGE, () -> {
            FoundationAccess.Subject actor=subject(); if(actor.platform()) throw denied("平台账号不能授予机构资源权限");
            AccessValidation.resource(command.kind(),"manage"); id(command.resourceId()); lock(actor.scope()); access.requireResource(command.kind(),command.resourceId(),"manage");
            AccessRows.Member owner=member(actor.scope(),id(command.ownerMemberId())); requireMemberRange(actor,owner,false);
            if(!"active".equals(owner.getStatus())) throw AccessValidation.bad("资源所有者必须为启用成员");
            Long departmentId=optionalId(command.departmentId(),"department_id"); validateDepartment(actor.scope(),departmentId); FoundationAccess.DataScope scope=access.currentDataScope();
            if(!scope.all() && departmentId!=null && !scope.departmentIds().contains(departmentId.toString())) throw denied("不能把资源转移到范围外部门");
            List<AccessCommands.ResourceGrant> grants=command.grants(); if(grants==null || grants.size()>200) throw AccessValidation.bad("共享目标必须为不超过200条的数组");
            Set<String> distinct=new HashSet<>(); List<AccessRows.Grant> rows=new ArrayList<>();
            for(AccessCommands.ResourceGrant grant:grants) {
                if(grant==null) throw AccessValidation.bad("共享条目不能为空"); AccessValidation.resource(command.kind(),grant.action());
                AccessValidation.choice(grant.granteeType(),Set.of("member","role","department"),"grantee_type"); long grantee=id(grant.granteeId());
                switch(grant.granteeType()) { case "member" -> member(actor.scope(),grantee); case "role" -> role(actor.scope(),grantee); case "department" -> department(actor.scope(),grantee); default -> throw AccessValidation.bad("共享类型无效"); }
                if(!distinct.add(grant.granteeType()+":"+grant.granteeId()+":"+grant.action())) throw AccessValidation.bad("共享条目重复");
                AccessRows.Grant row=new AccessRows.Grant(); row.setId(SnowflakeIds.nextId()); row.setGranteeType(grant.granteeType()); row.setGranteeId(grantee); row.setAction(grant.action()); rows.add(row);
            }
            AccessRows.Resource row=new AccessRows.Resource(); row.setKind(command.kind()); row.setResourceId(command.resourceId()); row.setOwnerMemberId(owner.getId()); row.setDepartmentId(departmentId);
            AccessRows.Resource previous=authorization.resource(actor.scope(),command.kind(),command.resourceId());
            if(previous==null) { if(command.lockVersion()!=0) throw conflict(); mapper.insertResource(actor.scope(),row); }
            else { version(command.lockVersion()); row.setLockVersion(command.lockVersion()); changed(mapper.updateResource(actor.scope(),row)); }
            mapper.deleteResourceGrants(actor.scope(),command.kind(),command.resourceId()); for(AccessRows.Grant grant:rows) mapper.insertResourceGrant(actor.scope(),command.kind(),command.resourceId(),grant,id(actor.memberId()));
            mapper.bumpScope(actor.scope()); record(actor,"permission.resource.authorize",command.kind(),command.resourceId()); return resourceView(actor.scope(),command.kind(),command.resourceId());
        });
    }
    /** 新建领域资源后由同库事务调用，登记当前创建人及主部门，不授予额外动作。 */
    @Transactional public void registerResource(String kind,String resourceId) {
        FoundationAccess.Subject actor=subject(); if(actor.platform()) throw denied("机构资源只能由机构主体登记");
        AccessValidation.resource(kind,"manage"); access.require("foundation.resource."+kind+".manage");
        FoundationAccess.inScope(actor, () -> {
            if(!authorization.resourceExists(id(actor.tenantId()),kind,id(resourceId))) throw missing(); if(authorization.resource(actor.scope(),kind,resourceId)!=null) throw conflict();
            AccessRows.Member member=member(actor.scope(),id(actor.memberId())); AccessRows.Resource row=new AccessRows.Resource();
            row.setKind(kind); row.setResourceId(resourceId); row.setOwnerMemberId(member.getId()); row.setDepartmentId(member.getDepartmentId()); mapper.insertResource(actor.scope(),row); return null;
        });
    }
    /** 新建真实会话后在同库事务登记创建人与当前部门；不存在或重复的会话不写入。 */
    @Transactional public void registerSessionOwnership(String sessionId) {
        FoundationAccess.Subject actor=subject(); if(actor.platform()) throw denied("平台主体不能登记机构会话所有权");
        access.require("foundation.sessions.write"); AccessValidation.text(sessionId,128,"session_id");
        FoundationAccess.inScope(actor,()-> {
            Long primaryId=authorization.sessionPrimaryId(actor.scope(),sessionId); if(primaryId==null) throw missing();
            if(authorization.resource(actor.scope(),"session",primaryId.toString())!=null) throw conflict();
            AccessRows.Member member=member(actor.scope(),id(actor.memberId())); AccessRows.Resource row=new AccessRows.Resource();
            row.setKind("session"); row.setResourceId(primaryId.toString()); row.setOwnerMemberId(member.getId()); row.setDepartmentId(member.getDepartmentId()); mapper.insertResource(actor.scope(),row); return null;
        });
    }
    /** 领域删除真实资源后在同一事务清除授权，禁止旧共享用于新对象。 */
    @Transactional public void forgetResource(String kind,String resourceId) {
        FoundationAccess.Subject actor=subject(); if(actor.platform()) throw denied("平台账号不能清除机构资源授权");
        AccessValidation.resource(kind,"manage"); access.require("foundation.resource."+kind+".manage");
        FoundationAccess.inScope(actor, () -> { mapper.deleteResource(actor.scope(),kind,resourceId); return null; });
    }
    /**
     * 已认证宿主在完成独立外部域授权并删除真实资源后清理授权；不伪造机构成员身份。
     * 显式目标必须等于宿主已绑定 TenantContext，仅删除该机构授权，不暴露 HTTP 入口。
     */
    @Transactional public void forgetResourceForTenant(String tenantId,String kind,String resourceId) {
        AccessValidation.id(tenantId,"tenant_id"); AccessValidation.resource(kind,"manage"); id(resourceId);
        if(!tenantId.equals(com.skillforge.common.context.TenantContext.getTenantId())) throw denied("宿主清理目标必须为当前已认证机构");
        mapper.deleteResource(tenantId,kind,resourceId);
    }
    /**
     * 受信机构注册入口初始化首位管理员，不能用于已有管理机构的角色更改。
     * 调用方拥有真实机构/账号创建事务；此 API 不向 HTTP 控制器暴露。
     */
    @Transactional public void initializeTenant(String tenantId,String administratorMemberId) {
        FoundationAccess.Subject target=new FoundationAccess.Subject(false,tenantId,administratorMemberId);
        FoundationAccess.inScope(target, () -> {
            mapper.ensureScope(target.scope(),id(tenantId)); lock(target.scope()); AccessRows.Member member=member(target.scope(),id(administratorMemberId));
            if(mapper.activeAdministratorsExcept(target.scope(),member.getId())>0) throw new ApiException(HttpStatus.CONFLICT,"机构已经存在管理员");
            mapper.ensureProfile(target.scope(),member.getId(),member.getUsername()); ensureBuiltInRoles(target);
            mapper.insertMemberRole(target.scope(),member.getId(),mapper.roleByCode(target.scope(),"tenant_admin").getId()); mapper.bumpScope(target.scope()); return null;
        });
    }
    /**
     * 受信平台首次启动入口；空账号体系的检查、真实账号创建与管理员建制共用 scope 行锁。
     * 已有账号返回 false 并且不调用创建回调，不能用作登录修复或重复提升权限的旁路。
     */
    @Transactional public boolean initializePlatformAdministrator(String administratorMemberId,Runnable createAccount) {
        FoundationAccess.Subject target=new FoundationAccess.Subject(true,null,administratorMemberId);
        id(administratorMemberId); if(createAccount==null) throw AccessValidation.bad("首次管理员创建回调不能为空");
        return FoundationAccess.inScope(target, () -> {
            mapper.ensureScope("platform",null); lock("platform");
            if(mapper.memberCount("platform",new FoundationAccess.DataScope(true,administratorMemberId,Set.of()),"")>0) return false;
            createAccount.run(); AccessRows.Member member=member("platform",id(administratorMemberId));
            if(!"active".equals(member.getStatus())) throw AccessValidation.bad("首次平台管理员必须为启用账号");
            mapper.ensureProfile("platform",member.getId(),member.getUsername()); ensureBuiltInRoles(target);
            mapper.insertMemberRole("platform",member.getId(),mapper.roleByCode("platform","platform_admin").getId()); mapper.bumpScope("platform"); return true;
        });
    }
    /**
     * 已认证机构开通/邀请入口初始化真实账号角色；平台开通者可使用 tenants.create，
     * 其他更改需 role.manage。跨机构只允许真实平台管理员。
     */
    @Transactional public void bootstrapMember(FoundationAccess.Subject target,String roleCode) {
        FoundationAccess.Subject actor=subject();
        if(!(actor.platform() && access.allowed("foundation.tenants.create"))) access.require(AccessPermissions.ROLE_MANAGE);
        if(!actor.platform() && (!actor.scope().equals(target.scope()) || target.platform())) throw denied("不能初始化其他账号体系");
        boolean administrator=isAdministrator(actor);
        FoundationAccess.inScope(target, () -> {
            mapper.ensureScope(target.scope(),target.platform()?null:id(target.tenantId())); lock(target.scope()); AccessRows.Member member=member(target.scope(),id(target.memberId()));
            mapper.ensureProfile(target.scope(),member.getId(),member.getUsername()); ensureBuiltInRoles(target); AccessRows.Role role=mapper.roleByCode(target.scope(),roleCode);
            if(role==null || !role.isBuiltIn()) throw AccessValidation.bad("初始角色编码无效"); if(role.isAdministrator() && !administrator) throw denied("只有管理员可以初始化管理员");
            mapper.insertMemberRole(target.scope(),member.getId(),role.getId()); mapper.bumpScope(target.scope()); notifyChanged(target,member.getId()); return null;
        });
    }
    /** 平台账号服务完整替换目标机构角色编码，旧单 role 字段不再形成授权事实。 */
    @Transactional public void replaceTenantMemberRoles(FoundationAccess.Subject target,List<String> roleCodes) {
        FoundationAccess.Subject actor=subject(); if(!actor.platform() || target.platform()) throw denied("仅平台可使用机构角色治理入口");
        access.require("foundation.tenants.update"); boolean administrator=isAdministrator(actor);
        if(!administrator) throw denied("机构角色治理需要平台管理员");
        FoundationAccess.inScope(target, () -> {
            lock(target.scope()); AccessRows.Member member=member(target.scope(),id(target.memberId()));
            if(roleCodes==null || roleCodes.size()>32 || new HashSet<>(roleCodes).size()!=roleCodes.size()) throw AccessValidation.bad("角色编码数组无效");
            List<AccessRows.Role> roles=new ArrayList<>(); for(String code:roleCodes) { AccessRows.Role role=mapper.roleByCode(target.scope(),code); if(role==null || !role.isEnabled()) throw missing(); roles.add(role); }
            protectLastAdministrator(target.scope(),member.getId(),roles.stream().noneMatch(AccessRows.Role::isAdministrator));
            changed(mapper.bumpMember(target.scope(),member.getId(),member.getLockVersion())); mapper.deleteMemberRoles(target.scope(),member.getId());
            for(AccessRows.Role role:roles) mapper.insertMemberRole(target.scope(),member.getId(),role.getId()); notifyChanged(target,member.getId());
            record(actor,"permission.member.roles","member",target.memberId()); return null;
        });
    }
    /**
     * 旧账号状态/删除入口事务包装，调用方完成对象授权。
     * 最后管理员检查、真实写入和授权版本提升属于同一事务。
     */
    @Transactional public <T> T changeAccount(FoundationAccess.Subject target,boolean removesActiveAccount,Supplier<T> mutation) {
        FoundationAccess.Subject actor=subject();
        if(!(actor.platform() && access.allowed("foundation.tenants.update"))) access.require(AccessPermissions.ORGANIZATION_MANAGE);
        if(!actor.platform() && !actor.scope().equals(target.scope())) throw denied("不能变更其他机构成员");
        return FoundationAccess.inScope(target, () -> {
            lock(target.scope()); AccessRows.Member member=member(target.scope(),id(target.memberId())); if(!actor.platform()) requireMemberRange(actor,member,false);
            protectLastAdministrator(target.scope(),member.getId(),removesActiveAccount); T result=mutation.get();
            mapper.bumpMember(target.scope(),member.getId(),member.getLockVersion()); notifyChanged(target,member.getId()); return result;
        });
    }
    /** 导出当前数据范围内最多10000条成员资料，秘密字段不进入导出。 */
    public List<AccessViews.Member> exportMembers() {
        access.require(AccessPermissions.ORGANIZATION_MANAGE);
        return FoundationAccess.inScope(subject(), () -> {
            FoundationAccess.Subject actor=subject(); FoundationAccess.DataScope filter=filter(actor);
            if(mapper.memberCount(actor.scope(),filter,null)>10_000) throw AccessValidation.bad("成员导出最多10000条，请先缩小范围");
            return memberViews(actor.scope(),mapper.members(actor.scope(),filter,null,0,10_000));
        });
    }
    /** CSV 资料更新仅匹配当前范围中的真实用户名，不生成无凭据账号。 */
    public AccessViews.Member findMemberByUsername(String username) {
        access.require(AccessPermissions.ORGANIZATION_MANAGE);
        return FoundationAccess.inScope(subject(), () -> {
            FoundationAccess.Subject actor=subject(); List<AccessRows.Member> matches=mapper.members(actor.scope(),filter(actor),AccessValidation.text(username,128,"username"),0,100).stream().filter(row->row.getUsername().equals(username)).toList();
            if(matches.size()!=1) throw new ApiException(HttpStatus.NOT_FOUND,"成员不存在或不在授权范围"); return memberViews(actor.scope(),matches).getFirst();
        });
    }
    /** 当前请求或可信后台任务的主体。 */
    private FoundationAccess.Subject subject() { return access.currentSubject(); }
    /** 带实时 read 权限和租户恢复的查询入口。 */
    private <T> T read(Supplier<T> operation) { access.require(AccessPermissions.READ); return FoundationAccess.inScope(subject(),operation); }
    /** 数据库唯一/引用冲突形成稳定业务错误，代理事务仍负责全部回滚。 */
    private <T> T write(String permission,Supplier<T> operation) {
        access.require(permission);
        try { return FoundationAccess.inScope(subject(),operation); }
        catch(DataIntegrityViolationException failure) { throw new ApiException(HttpStatus.CONFLICT,"编码重复或对象仍被引用",failure); }
    }
    /** 获取体系行锁，缺少授权根不自动扩大归属。 */
    private void lock(String scope) { if(mapper.lockScope(scope)==null) throw missing(); }
    /** 读取本体系部门。 */
    private AccessRows.Department department(String scope,long id) { AccessRows.Department row=mapper.department(scope,id); if(row==null) throw missing(); return row; }
    /** 读取本体系岗位。 */
    private AccessRows.Position position(String scope,long id) { AccessRows.Position row=mapper.position(scope,id); if(row==null) throw missing(); return row; }
    /** 管理读取真实成员，不要求其当前启用。 */
    private AccessRows.Member member(String scope,long id) { AccessRows.Member row=authorization.member(scope,id); if(row==null) throw missing(); return row; }
    /** 读取本体系角色。 */
    private AccessRows.Role role(String scope,long id) { AccessRows.Role row=mapper.role(scope,id); if(row==null) throw missing(); return row; }
    /** 可选部门必须同体系且启用。 */
    private void validateDepartment(String scope,Long departmentId) { if(departmentId!=null && !department(scope,departmentId).isEnabled()) throw AccessValidation.bad("部门已停用"); }
    /** 可选岗位必须同体系且启用。 */
    private void validatePosition(String scope,Long positionId) { if(positionId!=null && !position(scope,positionId).isEnabled()) throw AccessValidation.bad("岗位已停用"); }
    /** 平台只拥有平台成员；机构取实时角色联合范围。 */
    private FoundationAccess.DataScope filter(FoundationAccess.Subject actor) { return actor.platform()?new FoundationAccess.DataScope(true,actor.memberId(),Set.of()):access.dataScope(actor); }
    /** 本人数据范围不允许任意创建其他成员。 */
    private void requireMemberRange(FoundationAccess.Subject actor,AccessRows.Member member,boolean creation) {
        FoundationAccess.DataScope scope=filter(actor);
        if(!scope.all() && ((creation && member.getDepartmentId()==null) || !scope.includes(member.getId().toString(),string(member.getDepartmentId())))) throw denied("成员不在当前数据范围");
    }
    /** 分配角色时检查目标、操作者权限与有效部门范围，避免角色管理者自我扩权。 */
    private List<AccessRows.Role> validateAssignedRoles(FoundationAccess.Subject actor,AccessRows.Member target,List<String> roleIds) {
        if(roleIds==null || roleIds.size()>32 || new HashSet<>(roleIds).size()!=roleIds.size()) throw AccessValidation.bad("角色必须为不重复且最多32项的数组");
        Set<String> owned=new HashSet<>(access.currentSnapshot().permissions()); FoundationAccess.DataScope range=filter(actor);
        List<AccessRows.RolePermission> permissionRows=mapper.rolePermissionRows(actor.scope()); List<AccessRows.RoleDepartment> departmentRows=mapper.roleDepartmentRows(actor.scope());
        List<AccessRows.Role> result=new ArrayList<>();
        for(String roleId:roleIds) {
            AccessRows.Role role=role(actor.scope(),id(roleId)); if(!role.isEnabled()) throw AccessValidation.bad("不能分配已停用角色");
            if(role.isAdministrator() && !isAdministrator(actor)) throw denied("只有管理员可以分配管理员角色");
            if(!role.isAdministrator() && permissionRows.stream().filter(p->p.getRoleId().equals(role.getId())).anyMatch(p->!owned.contains(p.getPermissionCode()))) throw denied("不能分配超出自身动作权限的角色");
            if(!range.all()) {
                if("all".equals(role.getDataScope())) throw denied("不能分配全租户范围"); Set<String> delegated=new HashSet<>();
                if(target.getDepartmentId()!=null && "department".equals(role.getDataScope())) delegated.add(target.getDepartmentId().toString());
                if(target.getDepartmentId()!=null && "descendants".equals(role.getDataScope())) authorization.descendantDepartments(actor.scope(),target.getDepartmentId()).forEach(v->delegated.add(v.toString()));
                if("specified".equals(role.getDataScope())) departmentRows.stream().filter(r->r.getRoleId().equals(role.getId())).forEach(r->delegated.add(r.getDepartmentId().toString()));
                if(!range.departmentIds().containsAll(delegated)) throw denied("不能分配超出自身部门范围的角色");
            }
            result.add(role);
        }
        return result;
    }
    /** 校验角色定义，非法动作和范围不得默认放行。 */
    private String validateRoleDefinition(FoundationAccess.Subject actor,String dataScope,List<String> permissions,List<String> departmentIds) {
        AccessValidation.choice(dataScope,Set.of("self","department","descendants","specified","all"),"data_scope");
        if(permissions==null || permissions.size()>1000 || new HashSet<>(permissions).size()!=permissions.size()) throw AccessValidation.bad("权限必须为不重复且最多1000项的数组");
        Set<String> owned=new HashSet<>(access.currentSnapshot().permissions());
        for(String permission:permissions) if(!catalogue.supports(permission,actor.platform()) || !owned.contains(permission)) throw denied("不能授予未注册或未持有的权限");
        if(departmentIds==null || departmentIds.size()>200 || new HashSet<>(departmentIds).size()!=departmentIds.size()) throw AccessValidation.bad("指定部门必须为不重复且最多200项的数组");
        if(!"specified".equals(dataScope) && !departmentIds.isEmpty()) throw AccessValidation.bad("非指定部门模式不能携带指定部门");
        if("specified".equals(dataScope) && departmentIds.isEmpty()) throw AccessValidation.bad("指定部门模式至少需要一个部门"); FoundationAccess.DataScope range=filter(actor);
        if(!range.all() && !Set.of("self","specified").contains(dataScope)) throw denied("受限管理者只能创建本人或已有指定部门范围");
        for(String departmentId:departmentIds) { validateDepartment(actor.scope(),id(departmentId)); if(!range.all() && !range.departmentIds().contains(departmentId)) throw denied("指定部门超出自身范围"); }
        return dataScope;
    }
    /** 角色 CAS 成功后原子替换明细。 */
    private void replaceRoleDetails(String scope,long roleId,List<String> permissions,List<String> departmentIds) {
        mapper.deleteRolePermissions(scope,roleId); for(String permission:permissions) mapper.insertRolePermission(scope,roleId,permission);
        mapper.deleteRoleDepartments(scope,roleId); for(String departmentId:departmentIds) mapper.insertRoleDepartment(scope,roleId,id(departmentId));
    }
    /** 体系锁下保护最后一个启用管理员，包括停用、删除及角色降级。 */
    private void protectLastAdministrator(String scope,long memberId,boolean removing) {
        if(removing && authorization.memberRoles(scope,memberId).stream().anyMatch(AccessRows.Role::isAdministrator)
                && "active".equals(member(scope,memberId).getStatus()) && mapper.activeAdministratorsExcept(scope,memberId)==0)
            throw new ApiException(HttpStatus.CONFLICT,"foundation.access.last_administrator","必须保留至少一个启用的管理员",false);
    }
    /** 正式角色属性识别管理员，不信旧 role 列或 JWT。 */
    private boolean isAdministrator(FoundationAccess.Subject actor) { return FoundationAccess.inScope(actor,()->authorization.memberRoles(actor.scope(),id(actor.memberId())).stream().anyMatch(AccessRows.Role::isAdministrator)); }
    /** 新机构建制的明确内置角色和持久权限，只有注册动作才能进入授权表。 */
    private void ensureBuiltInRoles(FoundationAccess.Subject target) {
        List<String> codes=target.platform()?List.of("platform_admin"):List.of("tenant_admin","developer","auditor");
        for(String code:codes) {
            AccessRows.Role role=mapper.roleByCode(target.scope(),code); if(role!=null) continue;
            role=new AccessRows.Role(); role.setId(SnowflakeIds.nextId()); role.setCode(code);
            role.setName(code.equals("platform_admin")?"平台管理员":code.equals("tenant_admin")?"机构管理员":code.equals("developer")?"开发成员":"审计成员");
            role.setBuiltIn(true); role.setAdministrator(code.endsWith("_admin")); role.setEnabled(true); role.setDataScope(role.isAdministrator()?"all":"self"); mapper.insertRole(target.scope(),role);
            if(!role.isAdministrator()) for(FoundationPermission p:catalogue.permissions(target.platform())) if(AccessRoleDefaults.accepts(code,p.code())) mapper.insertRolePermission(target.scope(),role.getId(),p.code());
        }
    }
    /** 角色列表通过两次明细查询批量组装。 */
    private List<AccessViews.Role> roleViews(String scope) {
        Map<Long,List<String>> permissions=new HashMap<>(); mapper.rolePermissionRows(scope).forEach(row->permissions.computeIfAbsent(row.getRoleId(),key->new ArrayList<>()).add(row.getPermissionCode()));
        Map<Long,List<String>> departments=new HashMap<>(); mapper.roleDepartmentRows(scope).forEach(row->departments.computeIfAbsent(row.getRoleId(),key->new ArrayList<>()).add(row.getDepartmentId().toString()));
        return mapper.roles(scope).stream().map(row->new AccessViews.Role(row.getId().toString(),row.getCode(),row.getName(),row.getDataScope(),row.isBuiltIn(),row.isAdministrator(),row.isEnabled(),
                row.isAdministrator()?catalogue.permissions(scope.equals("platform")).stream().map(FoundationPermission::code).toList():List.copyOf(permissions.getOrDefault(row.getId(),List.of())),
                List.copyOf(departments.getOrDefault(row.getId(),List.of())),row.getLockVersion())).toList();
    }
    /** 成员列表单次批量读取角色，无 N+1 查询。 */
    private List<AccessViews.Member> memberViews(String scope,List<AccessRows.Member> rows) {
        if(rows.isEmpty()) return List.of(); Map<Long,List<AccessRows.MemberRole>> roles=new HashMap<>();
        mapper.memberRoleRows(scope,rows.stream().map(AccessRows.Member::getId).toList()).forEach(row->roles.computeIfAbsent(row.getMemberId(),key->new ArrayList<>()).add(row));
        return rows.stream().map(row->new AccessViews.Member(row.getId().toString(),row.getUsername(),row.getDisplayName(),row.getEmail(),row.getStatus(),string(row.getDepartmentId()),string(row.getPositionId()),
                roles.getOrDefault(row.getId(),List.of()).stream().map(r->r.getRoleId().toString()).toList(),roles.getOrDefault(row.getId(),List.of()).stream().map(AccessRows.MemberRole::getCode).toList(),row.getLockVersion(),row.getAuthorizationVersion(),row.getCreatedAt())).toList();
    }
    /** 部门字符串身份投影。 */
    private AccessViews.Department departmentView(AccessRows.Department row) { return new AccessViews.Department(row.getId().toString(),row.getCode(),row.getName(),string(row.getParentId()),row.isEnabled(),row.getLockVersion()); }
    /** 岗位投影。 */
    private AccessViews.Position positionView(AccessRows.Position row) { return new AccessViews.Position(row.getId().toString(),row.getCode(),row.getName(),row.isEnabled(),row.getLockVersion()); }
    /** 未登记资源明确返回空所有权和版本0。 */
    private AccessViews.Resource resourceView(String scope,String kind,String resourceId) {
        AccessRows.Resource row=authorization.resource(scope,kind,resourceId); if(row==null) return new AccessViews.Resource(kind,resourceId,null,null,List.of(),0);
        return new AccessViews.Resource(kind,resourceId,row.getOwnerMemberId().toString(),string(row.getDepartmentId()),mapper.resourceGrants(scope,kind,resourceId).stream().map(g->new AccessViews.Grant(g.getId().toString(),g.getGranteeType(),g.getGranteeId().toString(),g.getAction())).toList(),row.getLockVersion());
    }
    /** 提交后撤销受影响主体安全会话，回滚不产生撤权副作用。 */
    private void notifyChanged(FoundationAccess.Subject scopeSubject,long memberId) {
        FoundationAccess.Subject changed=new FoundationAccess.Subject(scopeSubject.platform(),scopeSubject.tenantId(),Long.toString(memberId)); Runnable callback=()->listeners.forEach(listener->listener.onChanged(changed));
        if(TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){callback.run();}}); else callback.run();
    }
    /** 审计只写对象与动作，不包含凭据或完整个人资料。 */
    private void record(FoundationAccess.Subject actor,String action,String type,String resourceId) { audit.record(actor.tenantId(),actor.memberId(),action,type,resourceId,"组织与权限配置已变更",Map.of()); }
    /** 密码按 Unicode 字符及 BCrypt 字节上限校验。 */
    private void validatePassword(String password) { if(password.codePointCount(0,password.length())<12 || password.getBytes(StandardCharsets.UTF_8).length>72 || !password.matches("(?s).*[A-Za-z].*") || !password.matches("(?s).*[0-9].*")) throw AccessValidation.bad("密码至少12个字符、包含字母和数字，且最多72个UTF-8字节"); }
    /** 邮箱允许显式清空，空白或非法地址拒绝。 */
    private String email(String value) { if(value==null) return null; if(value.isBlank() || value.length()>255 || !value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw AccessValidation.bad("邮箱格式无效"); return value; }
    /** 解析必填 ID。 */
    private long id(String value) { return AccessValidation.id(value,"id"); }
    /** 空字符串不等同于清空引用。 */
    private Long optionalId(String value,String field) { return value==null?null:AccessValidation.id(value,field); }
    /** 保留可空 ID 语义。 */
    private String string(Long value) { return value==null?null:value.toString(); }
    /** 布尔字段必须显式提供，缺失不得默认为停用。 */
    private boolean flag(Boolean value) { if(value==null) throw AccessValidation.bad("enabled 必须显式提供"); return value; }
    /** 预期版本必须为正。 */
    private void version(long value) { if(value<1) throw AccessValidation.bad("lock_version 必须大于0"); }
    /** CAS 未命中禁止无条件覆盖。 */
    private void changed(int count) { if(count!=1) throw conflict(); }
    /** 稳定版本冲突。 */
    private ApiException conflict() { return new ApiException(HttpStatus.CONFLICT,"foundation.access.version_conflict","资料已变化，请刷新后重试",false); }
    /** 不存在与跨体系对象统一拒绝。 */
    private ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"对象不存在或不属于当前账号体系"); }
    /** 当前授权拒绝。 */
    private ApiException denied(String message) { return new ApiException(HttpStatus.FORBIDDEN,"foundation.access.denied",message,false); }
}
