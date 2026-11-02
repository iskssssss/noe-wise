package com.skillforge.core.foundation.catalog.entitlements;

import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.catalog.CatalogScope;
import com.skillforge.core.foundation.catalog.CatalogValidation;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.skillforge.core.foundation.catalog.entitlements.EntitlementContracts.*;

/** 平台套餐功能/配额与机构只读有效授权；数据库计划关系是唯一功能事实源。 */
@Service
public class EntitlementService {
    private final EntitlementMapper mapper; private final FoundationAccess access; private final CatalogScope scope;
    /** 绑定授权、RLS 范围和领域存储。 */
    public EntitlementService(EntitlementMapper mapper,FoundationAccess access,CatalogScope scope) { this.mapper=mapper;this.access=access;this.scope=scope; }
    /** 功能目录由平台管理，机构只读启用能力。 */
    public List<Feature> features() { access.require("foundation.entitlements.read"); return mapper.features(); }
    /** 平台使用现有套餐，不另建套餐主表。 */
    public List<Plan> plans() { platformRead();return mapper.plans(); }
    /** CAS 保存功能目录；停用保留关系及稳定编码。 */
    @Transactional public Feature saveFeature(SaveFeature input) {
        platformManage(); if(input==null || input.expectedRevision()<0) throw CatalogValidation.invalid("修订不合法");
        String description=input.description()==null?"":input.description(); if(description.codePointCount(0,description.length())>512) throw CatalogValidation.invalid("说明最多 512 字符");
        var command=new SaveFeature(CatalogValidation.code(input.code()),CatalogValidation.text(input.name(),128),description,input.enabled(),input.expectedRevision());
        var old=mapper.lockFeature(command.code()); int changed=old==null?command.expectedRevision()==0?mapper.insertFeature(command,scope.actor()):0:old.revision()==command.expectedRevision()?mapper.updateFeature(command,scope.actor()):0;
        if(changed!=1) throw CatalogValidation.conflict(); return mapper.lockFeature(command.code());
    }
    /** 计划当前授权关系。 */
    public List<Grant> planGrants(String planId) { platformRead();CatalogScope.id(planId);return mapper.planGrants(planId); }
    /** 计划限额原值；null 对应项表示不限。 */
    public Limits planLimits(String planId) { platformRead();CatalogScope.id(planId);return limits(mapper.planLimits(planId)); }
    /** 套餐限额与其修订在同一计划锁下读取，客户端不能拼接不同时刻的CAS值。 */
    @Transactional public PlanQuota planQuota(String planId) {platformRead();CatalogScope.id(planId);var plan=mapper.lockPlan(planId);if(plan==null) throw CatalogValidation.missing();return new PlanQuota(planId,limits(mapper.planLimits(planId)),plan.revision());}
    /** 套餐行锁保护授权创建和 CAS 更新。 */
    @Transactional public Grant savePlanGrant(SavePlanGrant input) {
        platformManage(); CatalogScope.id(input.planId());CatalogValidation.code(input.featureCode());
        if(mapper.lockPlan(input.planId())==null || mapper.lockFeature(input.featureCode())==null) throw CatalogValidation.missing();
        var old=mapper.planGrants(input.planId()).stream().filter(g->g.featureCode().equals(input.featureCode())).findFirst().orElse(null);
        int changed=old==null?input.expectedRevision()==0?mapper.insertPlanGrant(input,scope.actor()):0:old.revision()==input.expectedRevision()?mapper.updatePlanGrant(input,scope.actor()):0;
        if(changed!=1) throw CatalogValidation.conflict(); return mapper.planGrants(input.planId()).stream().filter(g->g.featureCode().equals(input.featureCode())).findFirst().orElseThrow(CatalogValidation::missing);
    }
    /** 机构授权覆盖只允许平台写；null 明确删除关系恢复继承。 */
    @Transactional public void saveTenantGrant(SaveTenantGrant input) {
        platformManage();CatalogValidation.code(input.featureCode());
        if(mapper.features().stream().noneMatch(f->f.code().equals(input.featureCode()))) throw CatalogValidation.missing();
        scope.inTenant(input.tenantId(),()->{
            if(mapper.lockQuota(input.tenantId())==null) throw CatalogValidation.missing();
            var old=mapper.tenantGrants(input.tenantId()).stream().filter(g->g.featureCode().equals(input.featureCode())).findFirst().orElse(null);
            int changed;
            if(input.enabled()==null) { if(old==null && input.expectedRevision()==0) return null;changed=mapper.deleteTenantGrant(input); }
            else changed=old==null?input.expectedRevision()==0?mapper.insertTenantGrant(input,scope.actor()):0:old.revision()==input.expectedRevision()?mapper.updateTenantGrant(input,scope.actor()):0;
            if(changed!=1) throw CatalogValidation.conflict(); return null;
        });
    }
    /** 平台可查看明确目标覆盖；机构只能查看自己的覆盖。 */
    public List<Grant> tenantGrants(String tenantId) { access.require("foundation.entitlements.read");String id=target(tenantId);return scope.inTenant(id,()->mapper.tenantGrants(id)); }
    /** 套餐配额 CAS，与资源受理共享计划行锁；变小允许已有资源保留但阻止新增。 */
    @Transactional public Limits savePlanQuota(SavePlanQuota input) {
        platformManage();CatalogScope.id(input.planId());validate(input.limits());
        var plan=mapper.lockPlan(input.planId());if(plan==null) throw CatalogValidation.missing();
        if(plan.revision()!=input.expectedRevision() || mapper.updatePlanQuota(input)!=1) throw CatalogValidation.conflict(); return limits(mapper.planLimits(input.planId()));
    }
    /** 平台完整更新机构覆盖，null 对应项恢复继承；与创建同租户行锁。 */
    @Transactional public TenantEntitlements saveTenantQuota(SaveTenantQuota input) {
        platformManage();validate(input.limits());
        return scope.inTenant(input.tenantId(),()->{var quota=mapper.lockQuota(input.tenantId());if(quota==null) throw CatalogValidation.missing(); if(quota.revision()!=input.expectedRevision() || mapper.updateTenantQuota(input)!=1) throw CatalogValidation.conflict();return inspect(input.tenantId());});
    }
    /** 真实当前用量与授权，机构不能请求另一个租户。 */
    @Transactional(readOnly=true) public TenantEntitlements inspect(String tenantId) {
        access.require("foundation.entitlements.read");String id=target(tenantId);
        return scope.inTenant(id,()->{
            var q=mapper.quota(id);if(q==null) throw CatalogValidation.missing();var usage=new ArrayList<Usage>();
            for(var kind:ResourceKind.values()) {Long limit=q.limit(kind);usage.add(new Usage(kind,limit==null?null:limit.toString(),Long.toString(mapper.used(id,kind.name()))));}
            return new TenantEntitlements(id,new Limits(q.agents(),q.workflows(),q.knowledgeBases(),q.members(),q.channels(),q.fileBytes()==null?null:q.fileBytes().toString(),q.adminTasks(),q.subInstitutions()),limits(mapper.tenantLimits(id)),q.revision(),usage,mapper.effectiveFeatures(id));
        });
    }
    /** 全NULL限额是有效值；只有查询没有真实行时才返回不存在。 */
    private static Limits limits(EntitlementMapper.StoredLimits row) {if(row==null)throw CatalogValidation.missing();return row.limits();}
    /** 平台显式目标必填；机构请求省略时取可信当前租户。 */
    private String target(String requested) { return requested==null?scope.tenant():scope.target(requested); }
    /** 所有 typed 配额允许零和 null，计数不超过现有 integer 存储范围。 */
    private void validate(Limits input) {
        if(input==null) throw CatalogValidation.invalid("limits 必填");
        for(Long count:new Long[]{input.agents(),input.workflows(),input.knowledgeBases(),input.members(),input.channels(),input.adminTasks(),input.subInstitutions()}) if(count!=null && (count<0 || count>Integer.MAX_VALUE)) throw CatalogValidation.invalid("数量配额超出允许范围");
        if(input.fileBytes()!=null) try {if(!input.fileBytes().matches("0|[1-9][0-9]{0,18}") || Long.parseLong(input.fileBytes())<0) throw CatalogValidation.invalid("文件字节配额格式错误");} catch(NumberFormatException e) {throw CatalogValidation.invalid("文件字节配额超出范围");}
    }
    /** 平台只读权限。 */
    private void platformRead() { scope.requirePlatform();access.require("foundation.entitlements.read"); }
    /** 平台维护权限，机构管理员仍不能修改自己的授权。 */
    private void platformManage() { scope.requirePlatform();access.require("foundation.entitlements.manage"); }
}
