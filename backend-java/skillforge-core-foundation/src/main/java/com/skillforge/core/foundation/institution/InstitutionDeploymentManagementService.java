package com.skillforge.core.foundation.institution;

import static com.skillforge.core.foundation.institution.InstitutionDeploymentManagementContracts.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ManagementContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.shared.security.TokenHash;
import com.skillforge.core.foundation.application.AuditService;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 总部管理目标部署及用量许可的生命周期；Agent 来源许可不授予目标管理权。
 * 许可只覆盖精确当前直属关系，执行期间共享锁阻止撤销和关系变更穿过本次受理。
 */
@Service
public class InstitutionDeploymentManagementService {
    /** 仅开放已经批准的部署元数据动作，禁止用任意权限编码扩大窗口。 */
    private static final Set<String> ACTIONS = Set.of("tenant.agent_deployments.read", "tenant.agent_deployments.create",
            "tenant.agent_deployments.bind", "tenant.agent_deployments.switch_authorization");
    /** 仅本服务在检查当前人员权限和真实数据库许可后绑定，作用域结束必须恢复。 */
    private static final ThreadLocal<TargetAuthorization> TARGET = new ThreadLocal<>();
    private final InstitutionDeploymentManagementMapper mapper;
    private final InstitutionRelationMapper receipts;
    private final PlatformManagementService management;
    private final FoundationAccess foundation;
    private final InstitutionIntegrationService institutions;
    private final AuditService audit;
    private final ObjectMapper json;

    /** 复用机构关系的持久回执和现有 IAM，不引入第二套总部身份或角色。 */
    public InstitutionDeploymentManagementService(InstitutionDeploymentManagementMapper mapper, InstitutionRelationMapper receipts,
            PlatformManagementService management, FoundationAccess foundation, InstitutionIntegrationService institutions,
            AuditService audit, ObjectMapper json) {
        this.mapper = mapper; this.receipts = receipts; this.management = management; this.foundation = foundation;
        this.institutions = institutions; this.audit = audit; this.json = json;
    }

    /**
     * 当前事务内经验证的精确目标凭据；不包含来源 Agent 授权，也不替代人员当前 IAM。
     * @param actorTenantId 真实总部人员所属机构
     * @param targetTenantId 当前精确部署目标
     * @param action 本次允许的完整部署动作
     * @param actorRef 真实总部成员引用，不能从临时目标上下文构造
     * @param grantId 本次锁定的独立许可 ID
     * @param grantRevision 锁定时的许可修订
     * @param relationRef 当前精确直属关系依据 ID
     * @param relationRevision 当前直属关系修订
     */
    public record TargetAuthorization(String actorTenantId, String targetTenantId, String action, String actorRef,
            String grantId, long grantRevision, String relationRef, long relationRevision) { }

    /** RLS 及部署消费者只能读取当前受控窗口的凭据，不能自行创建绑定。 */
    public static TargetAuthorization currentTarget() { return TARGET.get(); }

    /** 平台列表同时过滤总部和目标；许可历史不向单边目标授权泄露。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page platformList(Long actorTenantId, Long targetTenantId, int page, int pageSize) {
        var scope = management.authorizedTenantScope("platform.institution_management_grants.read");
        if (page < 1 || pageSize < 1 || pageSize > 200) throw bad("page/page_size非法");
        if (actorTenantId != null) requirePlatformTarget(scope.tenantIds(), actorTenantId);
        if (targetTenantId != null) requirePlatformTarget(scope.tenantIds(), targetTenantId);
        var rows = mapper.rows(scope.tenantIds(), actorTenantId, targetTenantId, pageSize, ((long) page - 1) * pageSize);
        return new Page(rows.stream().map(this::view).toList(), mapper.count(scope.tenantIds(), actorTenantId, targetTenantId), page, pageSize);
    }

    /** 创建新许可、不可变安全回执和审计同事务提交；同键重读前仍验证当前双目标权限。 */
    @Transactional(rollbackFor = Exception.class)
    public Grant create(Create command, String requestKey) {
        if (command == null || command.actorTenantId() == null || command.actorTenantId() < 1
                || command.targetTenantId() == null || command.targetTenantId() < 1
                || command.expectedRelationRevision() == null || command.expectedRelationRevision() < 1)
            throw bad("actor_tenant_id、target_tenant_id与expected_relation_revision必须为正数");
        action(command.action());
        if (command.actorTenantId().equals(command.targetTenantId())) throw bad("部署管理许可必须指定独立直属目标");
        var scope = management.authorizedTenantScope("platform.institution_management_grants.create");
        requirePlatformTarget(scope.tenantIds(), command.actorTenantId());
        requirePlatformTarget(scope.tenantIds(), command.targetTenantId());
        String operation = "deployment_management_grant.create", key = key(requestKey), digest = fingerprint(command);
        receipts.lockRequest(scope.actorRef(), operation, key);
        Grant original = prior(scope.actorRef(), operation, key, digest);
        if (original != null) return original;
        // 锁当前双方，再读取对应关系；迁移或停用不能穿过新许可创建事务。
        requireAvailable(command.actorTenantId(), command.targetTenantId());
        var relation = mapper.currentRelation(command.actorTenantId(), command.targetTenantId());
        if (relation == null || relation.revision() != command.expectedRelationRevision()) throw conflict("当前直属关系或修订已变化");
        mapper.lockGrantKey(command.actorTenantId(), command.targetTenantId(), command.action(), relation.relationRef());
        if (mapper.activeExists(command.actorTenantId(), command.targetTenantId(), command.action(), relation.relationRef()))
            throw conflict("当前直属关系已存在相同动作许可");
        long id = SnowflakeIds.nextId();
        if (mapper.insert(id, command.actorTenantId(), command.targetTenantId(), command.action(), relation.relationRef()) != 1)
            throw new IllegalStateException("institution_deployment_management_grant_insert_failed");
        Grant result = new Grant(Long.toString(id), command.actorTenantId().toString(), command.targetTenantId().toString(),
                command.action(), "active", 1, Long.toString(relation.relationRef()), relation.revision());
        complete(scope.actorRef(), operation, key, digest, "create", result);
        return result;
    }

    /** 撤销为 CAS 终态；旧幂等回执不复活许可，显式重授必须创建新 ID。 */
    @Transactional(rollbackFor = Exception.class)
    public Grant revoke(Revoke command, String requestKey) {
        if (command == null || command.grantId() == null || command.grantId() < 1
                || command.expectedRevision() == null || command.expectedRevision() < 1)
            throw bad("grant_id与expected_revision必须为正数");
        var scope = management.authorizedTenantScope("platform.institution_management_grants.revoke");
        var visible = mapper.visible(command.grantId(), scope.tenantIds());
        if (visible == null) throw missing();
        requirePlatformTarget(scope.tenantIds(), visible.actorTenantId());
        requirePlatformTarget(scope.tenantIds(), visible.targetTenantId());
        String operation = "deployment_management_grant.revoke", key = key(requestKey), digest = fingerprint(command);
        receipts.lockRequest(scope.actorRef(), operation, key);
        Grant original = prior(scope.actorRef(), operation, key, digest);
        if (original != null) return original;
        var row = mapper.lockGrant(command.grantId());
        if (row == null) throw missing();
        if (!"active".equals(row.status()) || row.revision() != command.expectedRevision()) throw conflict("许可状态或修订已变化");
        if (mapper.revoke(command.grantId(), command.expectedRevision()) != 1) throw conflict("许可修订竞争");
        Grant result = new Grant(Long.toString(row.grantId()), Long.toString(row.actorTenantId()), Long.toString(row.targetTenantId()),
                row.action(), "revoked", Math.addExact(row.revision(), 1), Long.toString(row.relationRef()), row.relationRevision());
        complete(scope.actorRef(), operation, key, digest, "revoke", result);
        return result;
    }

    /**
     * 检查真实原总部 IAM 与精确目标许可；本机构路径只验证本机构当前动作，不启用跨域例外。
     * 跨域调用须位于可写事务以持有共享锁；固定 ServicePrincipal 永远不能跨机构管理。
     * @return 跨域返回精确许可，本机构返回 null，消费者继续使用普通本机构 ACL
     */
    @Transactional(rollbackFor = Exception.class)
    public TargetAuthorization requireTarget(String targetTenantId, String fullAction) {
        deploymentAction(fullAction); long target = id(targetTenantId);
        if (TARGET.get() != null) throw denied("不能嵌套目标部署管理窗口");
        if (ManagementContext.get() != null) throw denied("平台管理身份不能进入机构部署管理域");
        var principal = ServicePrincipalContext.get();
        if (principal != null) {
            if (!principal.tenantId().equals(TenantContext.getTenantId()) || !principal.tenantId().equals(targetTenantId))
                throw denied("固定机构凭据不能管理其他机构部署");
            institutions.requirePrincipal(principal, fullAction);
            return null;
        }
        var subject = headquarters();
        foundation.require(subject, fullAction);
        if (subject.tenantId().equals(targetTenantId)) return null;
        var grant = mapper.lockAuthorization(id(subject.tenantId()), target, fullAction);
        if (grant == null) throw denied("没有当前直属目标的独立部署管理许可");
        return new TargetAuthorization(subject.tenantId(), targetTenantId, fullAction,
                "tenant:" + subject.tenantId() + ":member:" + subject.memberId(), Long.toString(grant.grantId()),
                grant.revision(), Long.toString(grant.relationRef()), grant.relationRevision());
    }

    /**
     * 仅为 source impact 求可见目标交集；缺当前动作返回空，不代表放行部署端点。
     * 账号失效和权限权威故障继续传播；固定机构机器不获得任何跨目标许可。
     */
    @Transactional(readOnly = true)
    public Set<Long> authorizedTargets(String fullAction) {
        deploymentAction(fullAction);
        if (ManagementContext.get() != null) throw denied("平台管理身份不能进入机构部署管理域");
        if (ServicePrincipalContext.get() != null) return Set.of();
        var subject = headquarters();
        if (!foundation.allowed(subject, fullAction)) return Set.of();
        return Set.copyOf(mapper.authorizedTargets(id(subject.tenantId()), fullAction));
    }

    /** 候选只含本机构及本次真实部署动作当前获准的直属目标，不借用目录读取许可。 */
    @Transactional(readOnly = true)
    public TargetOptionScope requireTargetOptions(String fullAction) {
        deploymentAction(fullAction);
        if (TARGET.get() != null) throw denied("不能嵌套目标部署管理窗口");
        if (ManagementContext.get() != null) throw denied("平台管理身份不能进入机构部署管理域");
        var principal = ServicePrincipalContext.get();
        if (principal != null) {
            requireTarget(principal.tenantId(), fullAction);
            long tenant = id(principal.tenantId());
            return new TargetOptionScope(tenant, Set.of(tenant));
        }
        var subject = headquarters();
        foundation.require(subject, fullAction);
        long actor = id(subject.tenantId());
        var targets = new java.util.HashSet<>(mapper.authorizedTargets(actor, fullAction));
        targets.add(actor);
        return new TargetOptionScope(actor, targets);
    }

    /** 当前调用者机构与精确动作目标；集合不可被候选消费者扩大。 */
    public record TargetOptionScope(long actorTenantId, Set<Long> tenantIds) {
        public TargetOptionScope { tenantIds = Set.copyOf(tenantIds); }
    }

    /**
     * 在精确目标中执行单个部署元数据事务片段，并保留原总部真实 Foundation 主体。
     * 先在总部上下文锁授权，再开启 RLS 极窄窗口；任意异常均恢复凭据和租户。
     */
    @Transactional(rollbackFor = Exception.class)
    public <T> T withTarget(String targetTenantId, String fullAction, Supplier<T> work) {
        if (work == null) throw new IllegalArgumentException("institution_deployment_work_required");
        TargetAuthorization target = requireTarget(targetTenantId, fullAction);
        if (target == null) return work.get();
        var originalSubject = foundation.currentSubject();
        return foundation.withSubject(originalSubject, () -> {
            TargetAuthorization previous = TARGET.get();
            try {
                TARGET.set(target);
                return TenantRlsInterceptor.withInstitutionDeploymentTarget(target.actorTenantId(), target.targetTenantId(), work);
            } finally {
                if (previous == null) TARGET.remove(); else TARGET.set(previous);
            }
        });
    }

    /** 只接受原始已认证人员，不能把临时 TenantContext 当作总部人员身份来源。 */
    private FoundationAccess.Subject headquarters() {
        var subject = foundation.currentSubject();
        if (subject.platform() || subject.tenantId() == null || !subject.tenantId().equals(TenantContext.getTenantId()))
            throw denied("部署管理必须使用可信当前总部人员");
        return subject;
    }

    /** 新许可只受理双方仍可管理的机构；锁按稳定 ID 顺序获取。 */
    private void requireAvailable(long actor, long target) {
        var tenants = mapper.lockTenants(actor, target);
        if (tenants.size() != 2 || tenants.stream().anyMatch(row -> !Set.of("active", "warning").contains(row.status())))
            throw denied("总部或目标机构当前不可管理");
    }

    /** 回执只保存安全 Grant；同键不同类型化参数明确冲突。 */
    private Grant prior(String actor, String operation, String key, String fingerprint) {
        var prior = receipts.request(actor, operation, key);
        if (prior == null) return null;
        if (!fingerprint.equals(prior.fingerprint())) throw conflict("同一幂等键参数不一致");
        try {
            Grant result = json.readValue(prior.result(), Grant.class);
            if (result == null) throw new IllegalStateException("institution_deployment_management_receipt_invalid");
            return result;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("institution_deployment_management_receipt_invalid", failure);
        }
    }

    /** 许可变更、成功回执和正式审计共用调用方事务，审计失败向外传播并整体回滚。 */
    private void complete(String actor, String operation, String key, String fingerprint, String verb, Grant result) {
        if (receipts.receipt(actor, operation, key, fingerprint, id(result.targetTenantId()), encode(result)) != 1)
            throw new IllegalStateException("institution_deployment_management_receipt_failed");
        audit.record(null, actor, "platform.institution_management_grants." + verb, "institution_management_grant",
                result.grantId(), "直属机构管理许可已提交", Map.of("actor_tenant_id", result.actorTenantId(),
                        "target_tenant_id", result.targetTenantId(), "action", result.action(), "revision", result.revision(),
                        "relation_ref", result.relationRef(), "relation_revision", result.relationRevision()));
    }

    /** 显式列投影转换成安全公开协议。 */
    private Grant view(InstitutionDeploymentManagementMapper.GrantRow row) {
        return new Grant(Long.toString(row.grantId()), Long.toString(row.actorTenantId()), Long.toString(row.targetTenantId()),
                row.action(), row.status(), row.revision(), Long.toString(row.relationRef()), row.relationRevision());
    }
    /** 固定动作白名单，非法编码没有默认许可。 */
    private void action(String action) {
        if (!ACTIONS.contains(action == null ? "" : action)
                && !com.skillforge.core.foundation.institution.usage.HeadquartersUsageActions.ALL.contains(action == null ? "" : action))
            throw bad("不支持的机构管理许可动作");
    }
    /** 扩展许可生命周期不扩大已有部署执行窗口。 */
    private void deploymentAction(String action) {
        if (!ACTIONS.contains(action == null ? "" : action)) throw bad("不支持的部署管理动作");
    }
    /** 目标 ID 采用正十进制字符串，不接受符号、空白或溢出。 */
    private long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw bad("机构ID必须为正十进制字符串");
        try { return Long.parseLong(value); } catch (NumberFormatException failure) { throw bad("机构ID超出范围"); }
    }
    /** 双目标授权不存在通配符，空集合不授予任何目标。 */
    private void requirePlatformTarget(Set<Long> allowed, long target) { if (target < 1 || !allowed.contains(target)) throw denied("没有目标机构权限"); }
    /** 幂等键长度按既有持久回执字段限制，禁止控制字符。 */
    private String key(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) throw bad("Idempotency-Key格式非法");
        return value.trim();
    }
    /** 对类型化命令编码，禁止将任意 Map 当作参数权威。 */
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException failure) { throw new IllegalStateException(failure); } }
    /** 指纹与安全回执共用序列化规则。 */
    private String fingerprint(Object value) { return TokenHash.sha256Hex(encode(value)); }
    /** 非法协议参数。 */
    private ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    /** 当前身份或权限拒绝。 */
    private ApiException denied(String message) { return new ApiException(HttpStatus.FORBIDDEN, message); }
    /** CAS、关系变化或幂等冲突。 */
    private ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
    /** 不区分未授权和不存在的许可，避免单边目标窥探。 */
    private ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "许可不存在或不可见"); }
}
