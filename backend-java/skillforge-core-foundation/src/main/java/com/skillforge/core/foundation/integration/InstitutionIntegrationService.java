package com.skillforge.core.foundation.integration;

import static com.skillforge.core.foundation.integration.IntegrationContracts.*;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.ServicePrincipalDO;
import com.skillforge.core.foundation.persistence.TenantDO;
import com.skillforge.core.shared.security.TokenHash;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 平台直管机构开通、执行凭证及固定业务范围；每次写入仅锁当前对象与幂等键。 */
@Service
public class InstitutionIntegrationService {
    private static final Logger LOG = LoggerFactory.getLogger(InstitutionIntegrationService.class);
    /** 目前真正开放的执行能力；平台管理动作不能进入机构执行凭证。 */
    public static final Set<String> EXECUTION_SCOPES = Set.of("sessions.write", "sessions.read", "sessions.events.read",
            "end_users.write", "groups.read", "groups.write", "groups.members.write", "groups.agents.write",
            "groups.messages.write", "groups.triggers.write", "groups.triggers.read",
            "groups.runs.read", "groups.runs.respond", "groups.runs.control",
            "groups.history.read", "groups.memory.read", "groups.memory.write",
            "groups.controls.write", "groups.controls.read", "groups.controls.reconcile",
            "groups.controls.release", "groups.controls.ack", "groups.senders.manage",
            "resources.read", "resources.write", "artifacts.read", "control.operations.read",
            "control.operations.write", "usage.read", "business.scopes.write", "tenant.sub_institutions.read", "tenant.sub_institutions.provision",
            com.skillforge.core.foundation.institution.usage.HeadquartersUsageActions.SUMMARY,
            com.skillforge.core.foundation.institution.usage.HeadquartersUsageActions.DETAILS,
            com.skillforge.core.foundation.institution.usage.HeadquartersUsageActions.EVENTS,
            "tenant.agent_definitions.read","tenant.agent_definitions.create","tenant.agent_definitions.edit","tenant.agent_definitions.validate",
            "tenant.agent_definitions.publish","tenant.agent_definitions.deprecate","tenant.agent_definitions.restore",
            "tenant.agent_grants.read","tenant.agent_grants.create","tenant.agent_grants.narrow","tenant.agent_grants.revoke");
    private final InstitutionIntegrationMapper mapper;
    private final TenantMapper tenants;
    private final ServicePrincipalService principals;
    private final PlatformManagementService management;

    /** 注入同库事务参与者，管理身份只拥有管理动作，不转换为机构执行主体。 */
    public InstitutionIntegrationService(InstitutionIntegrationMapper mapper, TenantMapper tenants,
            ServicePrincipalService principals, PlatformManagementService management) {
        this.mapper = mapper; this.tenants = tenants; this.principals = principals; this.management = management;
    }

    /** 当前授权目标先过滤再计数与分页，不向仅有namespace创建权的机器披露任意机构。 */
    @Transactional(readOnly = true)
    public Page<Institution> institutions(String namespace, String tenantId, int page, int pageSize) {
        page(page, pageSize);
        Set<Long> allowed = management.allowedTenantIds("platform.institutions.read");
        Long tenant = tenantId == null ? null : id(tenantId, "tenant_id");
        if (tenant != null) management.requireTenant("platform.institutions.read", tenant);
        String ns = namespace == null ? null : required(namespace, "namespace", 128);
        var rows = mapper.mappings(allowed, ns, tenant, pageSize, ((long) page - 1) * pageSize);
        return new Page<>(rows.stream().map(this::view).toList(), mapper.mappingCount(allowed, ns, tenant), page, pageSize);
    }

    /** Tenant、显式套餐、默认策略、映射及幂等回执同事务提交；此入口绝不签发执行秘密。 */
    @Transactional(rollbackFor = Exception.class)
    public Institution provision(ProvisionInstitution request, String key) {
        if (request == null) throw bad("机构开通请求不能为空");
        String ns = required(request.namespace(), "namespace", 128);
        String external = required(request.externalInstitutionRef(), "external_institution_ref", 256);
        String name = required(request.name(), "name", 200);
        String plan = required(request.defaultPlanCode(), "default_plan_code", 64);
        quota(request.quotaApiCallsPerMonth()); quota(request.quotaMaxKnowledgeBases()); quota(request.quotaMaxMembers());
        quota(request.quotaMaxChannels()); quota(request.rateLimitPerMinute());
        management.requireNamespace("platform.institutions.provision", ns);
        if(TenantContext.getTenantId()!=null)throw denied("平台开通不可携带已有机构上下文");
        String fingerprint = fingerprint(ns, external, name, plan, request.quotaApiCallsPerMonth(),
                request.quotaMaxKnowledgeBases(), request.quotaMaxMembers(), request.quotaMaxChannels(), request.rateLimitPerMinute());
        String actor = management.actorRef();
        var prior = operation(actor, "institution.provision", key, fingerprint);
        // 原创建者在namespace权限仍有效时可读取自己的原始安全回执，无需重新开通或伪造目标授权。
        if (prior != null) return view(requireMapping(prior.getResultRef()));
        mapper.lockInstitutionTree();
        mapper.lockKey("institution.external:" + fingerprint(ns, external));
        var existing = mapper.externalMapping(ns, external);
        if (existing != null) {
            management.requireTenant("platform.institutions.provision", existing.getTenantId());
            if (!fingerprint.equals(existing.getProvisionFingerprint())) throw conflict("外部机构键已绑定不同开通内容");
            receipt(actor, "institution.provision", key, fingerprint, existing.getTenantId(),
                    existing.getId().toString(), existing.getRevision(), null);
            return view(existing);
        }
        var created=createInstitution(request,ns,external,name,plan,fingerprint,actor,null);
        receipt(actor, "institution.provision", key, fingerprint, Long.parseLong(created.tenantId()),created.mappingId(),1L,null);
        return created;
    }

    /** 已获总部直属创建权的同事务开通内核；全程保留总部身份，不认领已有外部键。 */
    @Transactional(rollbackFor=Exception.class)
    public Institution createSubInstitution(ProvisionInstitution request,long parent,String actor) {
        var principal=ServicePrincipalContext.get();
        boolean exactActor=principal==null ? actor!=null&&actor.startsWith("tenant:"+parent+":member:")
                : Long.toString(parent).equals(principal.tenantId())&&("service_principal:"+principal.principalId()).equals(actor)
                    &&principal.scopes().contains("tenant.sub_institutions.provision");
        if(!Long.toString(parent).equals(TenantContext.getTenantId())
                || com.skillforge.common.context.ManagementContext.get()!=null || !exactActor)
            throw denied("直属创建必须保留可信总部主体");
        if(request==null)throw bad("机构开通请求不能为空");
        String ns=required(request.namespace(),"namespace",128),external=required(request.externalInstitutionRef(),"external_institution_ref",256);
        String name=required(request.name(),"name",200),plan=required(request.defaultPlanCode(),"default_plan_code",64);
        quota(request.quotaApiCallsPerMonth());quota(request.quotaMaxKnowledgeBases());quota(request.quotaMaxMembers());
        quota(request.quotaMaxChannels());quota(request.rateLimitPerMinute());
        mapper.lockKey("institution.external:"+fingerprint(ns,external));
        if(mapper.externalMapping(ns,external)!=null)throw conflict("外部机构键已被使用");
        String fp=fingerprint(ns,external,name,plan,request.quotaApiCallsPerMonth(),request.quotaMaxKnowledgeBases(),request.quotaMaxMembers(),request.quotaMaxChannels(),request.rateLimitPerMinute());
        return createInstitution(request,ns,external,name,plan,fp,actor,parent);
    }

    private Institution createInstitution(ProvisionInstitution request,String ns,String external,String name,String plan,
            String fingerprint,String actor,Long parent) {
        Long planId = mapper.activePlanId(plan);
        if (planId == null) throw conflict("所选默认套餐未启用");
        long tenantId = SnowflakeIds.nextId();
        TenantDO tenant = new TenantDO();
        tenant.setId(tenantId); tenant.setName(name); tenant.setSlug("institution-" + tenantId);
        tenant.setPlanId(planId); tenant.setStatus("active"); tenant.setDeploymentMode("cloud"); tenant.setSettings(Map.of());
        tenant.setParentTenantId(parent);tenant.setRelationRevision(1L);
        tenant.setOverrideQuotaApiCallsPerMonth(request.quotaApiCallsPerMonth());
        tenant.setOverrideQuotaMaxKnowledgeBases(request.quotaMaxKnowledgeBases());
        tenant.setOverrideQuotaMaxMembers(request.quotaMaxMembers()); tenant.setOverrideQuotaMaxChannels(request.quotaMaxChannels());
        tenant.setOverrideRateLimitPerMinute(request.rateLimitPerMinute());
        // 映射触发器只初始化固定执行域；Java不切换到下级，不创建成员或凭据。
        if(tenants.insert(tenant)!=1)throw new IllegalStateException("institution_create_failed");
        var row = mapping(tenantId, ns, external, fingerprint, actor);
        if(mapper.insertMapping(row)!=1)throw new IllegalStateException("institution_mapping_create_failed");
        LOG.info("平台机构已开通 tenantId={} mappingId={}", tenantId, row.getId());
        return view(requireMapping(row.getId().toString()));
    }

    /** 认领已有机构必须同时拥有namespace与目标Tenant授权；外部键不可改绑。 */
    @Transactional(rollbackFor = Exception.class)
    public Institution associate(AssociateInstitution request, String key) {
        if (request == null) throw bad("机构关联请求不能为空");
        long tenant = id(request.tenantId(), "tenant_id");
        String ns = required(request.namespace(), "namespace", 128);
        String external = required(request.externalInstitutionRef(), "external_institution_ref", 256);
        management.requireNamespace("platform.institutions.associate", ns);
        management.requireTenant("platform.institutions.associate", tenant);
        String fingerprint = fingerprint(tenant, ns, external);
        String actor = management.actorRef();
        var prior = operation(actor, "institution.associate", key, fingerprint);
        if (prior != null) return view(requireMapping(prior.getResultRef()));
        mapper.lockKey("institution.external:" + fingerprint(ns, external));
        var row = mapper.externalMapping(ns, external);
        if (row != null && row.getTenantId() != tenant) throw conflict("外部机构键已绑定其他机构");
        if (row == null) {
            requireTenantAvailable(tenant);
            row = mapping(tenant, ns, external, null, actor);
            String integrationId = row.getIntegrationId();
            inTenant(tenant, () -> mapper.registerIntegration(tenant, integrationId));
            mapper.insertMapping(row);
            row = requireMapping(row.getId().toString());
        }
        receipt(actor, "institution.associate", key, fingerprint, tenant, row.getId().toString(), row.getRevision(), null);
        return view(row);
    }

    /** 当前凭据与真实机构执行域共同验证；不依赖Session聚合，供部署、范围和公开读取边界复用。 */
    @Transactional(readOnly = true)
    public void requirePrincipal(ServicePrincipalContext.Identity identity, String scope) {
        if (identity == null) throw denied("缺少已认证执行身份");
        inTenant(id(identity.tenantId(), "tenant_id"), () -> {
            principals.requireCurrent(identity, scope);
            requireIntegration(identity.tenantId(), identity.integrationId());
            return null;
        });
    }

    /** 固定执行域归属与Tenant运营状态分别验证；执行身份当前状态由requirePrincipal独立校验。 */
    @Transactional(readOnly = true)
    public void requireIntegration(String tenantId, String integrationId) {
        long tenant = id(tenantId, "tenant_id");
        String integration = required(integrationId, "integration_id", 128);
        if (!Objects.equals(TenantContext.getTenantId(), tenantId)) throw denied("执行机构与当前上下文不一致");
        var actor = ServicePrincipalContext.get();
        if (actor != null && (!tenantId.equals(actor.tenantId()) || !integration.equals(actor.integrationId())))
            throw denied("执行域与当前ServicePrincipal不一致");
        requireTenantAvailable(tenant);
        if (!mapper.integrationExists(tenant, integration)) throw denied("执行域未登记或不属于当前机构");
    }

    /** 管理查询先授权固定Tenant，再进入对应RLS上下文读取安全目录。 */
    @Transactional(readOnly = true)
    public Page<ExecutionCredential> credentials(String tenantId, String integrationId, int page, int pageSize) {
        long tenant = id(tenantId, "tenant_id"); String integration = required(integrationId, "integration_id", 128);
        page(page, pageSize); management.requireTenant("platform.institution_credentials.read", tenant);
        return inTenant(tenant, () -> new Page<>(mapper.credentials(tenant, integration, pageSize, ((long) page - 1) * pageSize)
                .stream().map(this::credential).toList(), mapper.credentialCount(tenant, integration), page, pageSize));
    }

    /** 独立签发执行凭证，同键重读只返回原安全回执，永不重新生成或恢复明文。 */
    @Transactional(rollbackFor = Exception.class)
    public IssuedExecutionCredential issue(IssueExecutionCredential request, String key) {
        if (request == null) throw bad("执行凭证请求不能为空");
        long tenant = id(request.tenantId(), "tenant_id"); String integration = required(request.integrationId(), "integration_id", 128);
        Set<String> scopes = scopes(request.scopes()); management.requireTenant("platform.institution_credentials.issue", tenant);
        String kind = "credential.issue:" + tenant + ":" + integration;
        String fp = fingerprint(tenant, integration, scopes.stream().sorted().toList());
        String actor = management.actorRef();
        var prior = operation(actor, kind, key, fp);
        if (prior != null) return new IssuedExecutionCredential(credential(prior), null, null);
        requireTenantAvailable(tenant);
        return inTenant(tenant, () -> {
            var issued = principals.issue(Long.toString(tenant), integration, scopes);
            var safe = credential(issued.principal());
            credentialReceipt(actor, kind, key, fp, safe);
            LOG.info("机构执行凭证已签发 tenantId={} principalId={}", tenant, safe.principalId());
            return new IssuedExecutionCredential(safe, issued.apiKey(), issued.signingSecret());
        });
    }

    /** 精确凭证版本CAS轮换，旧秘密立即失效，同键重读保留原安全版本并排除秘密。 */
    @Transactional(rollbackFor = Exception.class)
    public IssuedExecutionCredential rotate(ExecutionCredentialCommand request, String key) {
        var result = mutateCredential(request, key, true);
        return result;
    }

    /** 撤销是终态；相同请求得到原回执，其他旧revision请求409。 */
    @Transactional(rollbackFor = Exception.class)
    public ExecutionCredential revoke(ExecutionCredentialCommand request, String key) {
        return mutateCredential(request, key, false).principal();
    }

    /** 固定Tenant/integration中声明范围，不能接受正文身份、重绑或默默重启撤销范围。 */
    @Transactional(rollbackFor = Exception.class)
    public BusinessScope ensureBusinessScope(EnsureBusinessScope request, String key) {
        if (request == null) throw bad("业务范围请求不能为空");
        var actor = scopeActor();
        String type = required(request.scopeType(), "scope_type", 64), external = required(request.externalRef(), "external_ref", 256);
        String name = required(request.displayName(), "display_name", 200);
        String kind = "business.scope.ensure:" + actor.tenantId() + ":" + actor.integrationId();
        String fp = fingerprint(type, external, name);
        var prior = operation(actorRef(actor), kind, key, fp);
        if (prior != null) return originalScope(actor, prior);
        mapper.lockKey("business.scope:" + fingerprint(actor.tenantId(), actor.integrationId(), type, external));
        var row = mapper.businessScope(id(actor.tenantId(), "tenant_id"), actor.integrationId(), type, external);
        if (row != null && !name.equals(row.getDisplayName())) throw conflict("业务范围外部键已绑定不同内容");
        if (row == null) {
            row = new IntegrationBusinessScopeRow(); row.setScopeId(Long.toString(SnowflakeIds.nextId()));
            row.setTenantId(Long.valueOf(actor.tenantId())); row.setIntegrationId(actor.integrationId());
            row.setScopeType(type); row.setExternalRef(external); row.setDisplayName(name); row.setStatus("active");
            row.setRevision(1L); row.setActorRef(actorRef(actor)); mapper.insertBusinessScope(row);
        }
        receipt(actorRef(actor), kind, key, fp, row.getTenantId(), row.getScopeId(), row.getRevision(), row.getStatus());
        return scope(row);
    }

    /** 显式状态CAS；撤销不能恢复，旧来源不得覆盖当前范围状态。 */
    @Transactional(rollbackFor = Exception.class)
    public BusinessScope setBusinessScopeStatus(BusinessScopeStatus request, String key) {
        if (request == null) throw bad("业务范围状态请求不能为空");
        var actor = scopeActor(); String scopeId = Long.toString(id(request.scopeId(), "scope_id"));
        String status = required(request.status(), "status", 16);
        if (!Set.of("active", "suspended", "revoked").contains(status)) throw bad("业务范围status非法");
        long expected = revision(request.expectedRevision());
        String kind = "business.scope.status:" + actor.tenantId() + ":" + actor.integrationId() + ":" + scopeId;
        String fp = fingerprint(scopeId, status, expected);
        var prior = operation(actorRef(actor), kind, key, fp);
        if (prior != null) return originalScope(actor, prior);
        var row = mapper.businessScopeById(Long.parseLong(actor.tenantId()), actor.integrationId(), scopeId);
        if (row == null) throw missing("业务范围不存在");
        if (row.getRevision() != expected || "revoked".equals(row.getStatus())) throw conflict("业务范围修订已变化或已撤销");
        row.setStatus(status); row.setRevision(expected + 1); row.setActorRef(actorRef(actor));
        if (mapper.updateBusinessScope(row, expected) != 1) throw conflict("业务范围并发更新冲突");
        receipt(actorRef(actor), kind, key, fp, row.getTenantId(), scopeId, row.getRevision(), status);
        return scope(row);
    }

    /** Session创建通过外部键查询已授权范围；外部键不创建授权。 */
    public BusinessScope requireBusinessScope(String tenantId, String integrationId, String type, String external) {
        requireIntegration(tenantId, integrationId);
        var row = mapper.businessScope(id(tenantId, "tenant_id"), integrationId,
                required(type, "scope_type", 64), required(external, "external_ref", 256));
        return activeScope(row);
    }

    /** 当前固定执行域内复验范围状态；跨域范围与不存在范围均拒绝。 */
    public BusinessScope requireBusinessScopeById(String tenantId, String integrationId, String scopeId) {
        requireIntegration(tenantId, integrationId);
        return activeScope(mapper.businessScopeById(id(tenantId, "tenant_id"), integrationId,
                Long.toString(id(scopeId, "business_scope_id"))));
    }

    /** 当前管理权先于任何主体查询；凭据行锁仅保护本次实际轮换或撤销。 */
    private IssuedExecutionCredential mutateCredential(ExecutionCredentialCommand request, String key, boolean rotate) {
        if (request == null) throw bad("执行凭证操作请求不能为空");
        long tenant = id(request.tenantId(), "tenant_id"), principal = id(request.principalId(), "principal_id");
        String integration = required(request.integrationId(), "integration_id", 128);
        long expected = revision(request.expectedRevision());
        String action = rotate ? "rotate" : "revoke";
        management.requireTenant("platform.institution_credentials." + action, tenant);
        String kind = "credential." + action + ":" + tenant + ":" + integration + ":" + principal;
        String fp = fingerprint(tenant, integration, principal, expected);
        String actor = management.actorRef();
        var prior = operation(actor, kind, key, fp);
        if (prior != null) return new IssuedExecutionCredential(credential(prior), null, null);
        return inTenant(tenant, () -> {
            var row = mapper.lockPrincipal(tenant, integration, principal);
            if (row == null) throw missing("执行凭证不存在");
            if (!Objects.equals(row.getCredentialVersion(), expected) || !"active".equals(row.getStatus()))
                throw conflict("执行凭证修订已变化或已停用");
            if (rotate) {
                var issued = principals.rotate(Long.toString(tenant), principal); var safe = credential(issued.principal());
                credentialReceipt(actor, kind, key, fp, safe);
                return new IssuedExecutionCredential(safe, issued.apiKey(), issued.signingSecret());
            }
            if (!principals.revoke(Long.toString(tenant), principal)) throw conflict("执行凭证撤销竞争冲突");
            row.setStatus("revoked"); var safe = credential(row); credentialReceipt(actor, kind, key, fp, safe);
            return new IssuedExecutionCredential(safe, null, null);
        });
    }

    /** 当前已认证执行身份是唯一范围写入来源，不接受ManagementPrincipal或正文伪造。 */
    private ServicePrincipalContext.Identity scopeActor() {
        var actor = ServicePrincipalContext.get();
        requirePrincipal(actor, "business.scopes.write");
        return actor;
    }

    /** 单个幂等键锁保护首次结果，同键异参在写入之前409。 */
    private InstitutionOperationRow operation(String actor, String kind, String key, String fingerprint) {
        String normalized = required(key, "Idempotency-Key", 256);
        mapper.lockKey("institution.operation:" + fingerprint(actor, kind, normalized));
        var principal = ServicePrincipalContext.get();
        var prior = kind.startsWith("business.scope.")
                ? mapper.scopeOperation(Long.parseLong(principal.tenantId()), principal.integrationId(), actor, kind, normalized)
                : mapper.operation(actor, kind, normalized);
        if (prior != null && !fingerprint.equals(prior.getFingerprint())) throw conflict("同一幂等键对应不同操作内容");
        return prior;
    }

    /** 原子追加没有秘密的业务回执。 */
    private void receipt(String actor, String kind, String key, String fingerprint, long tenant, String result, long revision, String status) {
        var row = receiptRow(actor, kind, key, fingerprint, tenant, result, revision, status);
        if (kind.startsWith("business.scope.")) {
            row.setIntegrationId(ServicePrincipalContext.get().integrationId()); mapper.insertScopeOperation(row);
        } else mapper.insertOperation(row);
    }

    /** 保留原始凭证安全事实，后续轮换不改变先前操作回执。 */
    private void credentialReceipt(String actor, String kind, String key, String fingerprint, ExecutionCredential safe) {
        var row = receiptRow(actor, kind, key, fingerprint, Long.parseLong(safe.tenantId()), safe.id(),
                safe.credentialVersion(), safe.status());
        row.setIntegrationId(safe.integrationId()); row.setPrincipalRef(safe.principalId()); row.setApiKeyPrefix(safe.apiKeyPrefix());
        row.setScopes(safe.scopes()); row.setPrincipalCreatedAt(safe.createdAt()); mapper.insertOperation(row);
    }

    /** 固定列表达操作结果，创建/范围操作不带凭证字段。 */
    private InstitutionOperationRow receiptRow(String actor, String kind, String key, String fingerprint,
            long tenant, String result, long revision, String status) {
        var row = new InstitutionOperationRow(); row.setActorRef(actor); row.setOperationKind(kind); row.setIdempotencyKey(key.trim());
        row.setFingerprint(fingerprint); row.setTenantId(tenant); row.setResultRef(result); row.setResultRevision(revision);
        row.setResultStatus(status); return row;
    }

    /** 原状态与修订来自已提交回执，固定范围信息来自不可变归属行。 */
    private BusinessScope originalScope(ServicePrincipalContext.Identity actor, InstitutionOperationRow prior) {
        var row = mapper.businessScopeById(Long.parseLong(actor.tenantId()), actor.integrationId(), prior.getResultRef());
        if (row == null) throw new IllegalStateException("business_scope_receipt_missing");
        return new BusinessScope(row.getScopeId(), row.getTenantId().toString(), row.getIntegrationId(), row.getScopeType(),
                row.getExternalRef(), row.getDisplayName(), prior.getResultStatus(), prior.getResultRevision());
    }

    /** 映射创建者及固定归属来自已授权管理上下文。 */
    private InstitutionMappingRow mapping(long tenant, String ns, String external, String fingerprint, String actor) {
        var row = new InstitutionMappingRow(); row.setId(SnowflakeIds.nextId()); row.setTenantId(tenant);
        row.setNamespace(ns); row.setExternalInstitutionRef(external); row.setIntegrationId(Long.toString(SnowflakeIds.nextId()));
        row.setProvisionFingerprint(fingerprint); row.setRevision(1L); row.setCreatedBy(actor); return row;
    }

    /** 原回执存在而映射缺失表示持久不变量损坏，不能重新创建代替历史结果。 */
    private InstitutionMappingRow requireMapping(String id) {
        var row = mapper.mapping(Long.parseLong(id));
        if (row == null) throw new IllegalStateException("institution_mapping_receipt_missing"); return row;
    }

    /** 机构运营状态独立于是否存在外部映射。 */
    private void requireTenantAvailable(long tenant) {
        String status = mapper.tenantStatus(tenant);
        if (status == null) throw missing("机构不存在");
        if (!"active".equals(status) && !"warning".equals(status)) throw denied("机构当前不可执行");
    }

    /** 仅绑定已通过当前目标授权的一个机构，并精确恢复线程上下文。 */
    private <T> T inTenant(long tenant, Supplier<T> work) {
        String previous = TenantContext.getTenantId();
        if (previous != null && !previous.equals(Long.toString(tenant))) throw denied("管理事务不可切换已有机构上下文");
        try { TenantContext.setTenantId(Long.toString(tenant)); return work.get(); }
        finally { if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous); }
    }

    /** 安全映射只公开必要机构引用。 */
    private Institution view(InstitutionMappingRow row) {
        return new Institution(row.getId().toString(), row.getTenantId().toString(), row.getNamespace(), row.getExternalInstitutionRef(),
                row.getIntegrationId(), row.getTenantName(), row.getRevision(), row.getCreatedAt());
    }
    /** 当前凭证安全视图明确排除所有秘密存储字段。 */
    private ExecutionCredential credential(ServicePrincipalDO row) {
        return new ExecutionCredential(row.getId().toString(), row.getPrincipalId(), row.getTenantId().toString(), row.getIntegrationId(),
                row.getApiKeyPrefix(), List.copyOf(row.getScopes()), row.getStatus(), row.getCredentialVersion(), row.getCreatedAt());
    }
    /** 幂等重读返回原安全事实，不能返回当前版本冒充原签发结果。 */
    private ExecutionCredential credential(InstitutionOperationRow row) {
        return new ExecutionCredential(row.getResultRef(), row.getPrincipalRef(), row.getTenantId().toString(), row.getIntegrationId(),
                row.getApiKeyPrefix(), List.copyOf(row.getScopes()), row.getResultStatus(), row.getResultRevision(), row.getPrincipalCreatedAt());
    }
    /** 映射存在不代表授权active；撤销/暂停范围拒绝消费。 */
    private BusinessScope activeScope(IntegrationBusinessScopeRow row) {
        if (row == null || !"active".equals(row.getStatus())) throw denied("业务范围未授权或已停用"); return scope(row);
    }
    /** 对外业务范围身份以business_scope_id传输。 */
    private BusinessScope scope(IntegrationBusinessScopeRow row) {
        return new BusinessScope(row.getScopeId(), row.getTenantId().toString(), row.getIntegrationId(), row.getScopeType(),
                row.getExternalRef(), row.getDisplayName(), row.getStatus(), row.getRevision());
    }
    /** 执行白名单禁止将管理动作签入执行身份。 */
    private Set<String> scopes(Set<String> values) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(scope -> scope == null || !EXECUTION_SCOPES.contains(scope)))
            throw bad("机构执行scopes非法"); return Set.copyOf(values);
    }
    /** 明确类型字段长度与控制字符检查；非法值不截断或回退。 */
    private String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > max || value.chars().anyMatch(Character::isISOControl))
            throw bad(field + "格式非法"); return value.trim();
    }
    /** 十进制ID必须处于宿主Long正数范围。 */
    private long id(String value, String field) {
        if (value != null && value.matches("[1-9][0-9]{0,18}")) {
            try { return Long.parseLong(value); } catch (NumberFormatException invalid) { /* 19位数仍可能溢出。 */ }
        }
        throw bad(field + "必须为正整数十进制字符串");
    }
    /** CAS版本从首次创建的1开始，不用缺失值推断当前版本。 */
    private long revision(Long value) { if (value == null || value < 1) throw bad("expected_revision必须为正整数"); return value; }
    /** 管理分页拒绝越界，不自动夹紧。 */
    private void page(int page, int size) { if (page < 1 || size < 1 || size > 200) throw bad("page或page_size非法"); }
    /** NULL继承套餐，零是明确容量；负值非法。 */
    private void quota(Integer value) { if (value != null && value < 0) throw bad("配额不能小于零"); }
    /** 长度分帧摘要防止字段连接碰撞，NULL和字符串null不同。 */
    private String fingerprint(Object... values) {
        var canonical = new StringBuilder();
        for (Object value : values) { String text = value == null ? null : value.toString(); canonical.append(text == null ? -1 : text.length()).append(':'); if (text != null) canonical.append(text); }
        return TokenHash.sha256Hex(canonical.toString());
    }
    /** 执行主体稳定身份独立于凭据版本。 */
    private String actorRef(ServicePrincipalContext.Identity actor) { return "service_principal:" + actor.principalId(); }
    /** 公开校验错误。 */ private ApiException bad(String text) { return new ApiException(HttpStatus.BAD_REQUEST, text); }
    /** 公开授权错误。 */ private ApiException denied(String text) { return new ApiException(HttpStatus.FORBIDDEN, text); }
    /** 公开冲突错误。 */ private ApiException conflict(String text) { return new ApiException(HttpStatus.CONFLICT, text); }
    /** 已授权目标不存在。 */ private ApiException missing(String text) { return new ApiException(HttpStatus.NOT_FOUND, text); }
}
