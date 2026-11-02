package com.skillforge.core.foundation.integration;

import com.skillforge.core.foundation.persistence.ServicePrincipalDO;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 平台映射专用仓储及机构固定执行域存储；所有租户数据查询都使用明确 Tenant/integration。 */
@Mapper
public interface InstitutionIntegrationMapper {
    /** 事务内只序列化一个外部键或幂等键，不建立全局治理锁。 */
    void lockKey(@Param("key") String key);
    /** 新机构是树的新节点；锁顺序固定在外部键之前。 */
    long lockInstitutionTree();
    /** 平台部署范围唯一的外部机构键。 */
    InstitutionMappingRow externalMapping(@Param("namespace") String namespace, @Param("externalRef") String externalRef);
    /** 已经获得对象权限后读取映射。 */
    InstitutionMappingRow mapping(@Param("id") long id);
    /** 新映射与机构开通同一事务。 */
    int insertMapping(InstitutionMappingRow row);
    /** 在SQL内过滤当前目标后分页。 */
    List<InstitutionMappingRow> mappings(@Param("allowed") Set<Long> allowed, @Param("namespace") String namespace,
            @Param("tenantId") Long tenantId, @Param("limit") int limit, @Param("offset") long offset);
    /** 计数与目录使用同一目标谓词。 */
    long mappingCount(@Param("allowed") Set<Long> allowed, @Param("namespace") String namespace, @Param("tenantId") Long tenantId);
    /** 查询启用套餐，默认策略取开通时显式套餐。 */
    Long activePlanId(@Param("code") String code);
    /** 已通过开通动作授权后读取启用套餐的最小投影。 */
    List<ProvisioningPlanOption> activePlans(@Param("query") String query, @Param("code") String code,
            @Param("limit") int limit, @Param("offset") long offset);
    /** 相同启用和搜索条件的套餐数量。 */
    long activePlanCount(@Param("query") String query, @Param("code") String code);
    /** 机构状态是独立执行门禁。 */
    String tenantStatus(@Param("tenantId") long tenantId);
    /** 已登记执行域的固定归属；管理部署不以先签发秘密为前提，执行凭据另验当前状态。 */
    boolean integrationExists(@Param("tenantId") long tenantId, @Param("integrationId") String integrationId);
    /** 映射与稳定执行域同事务建立，不签发任何ServicePrincipal或秘密。 */
    int registerIntegration(@Param("tenantId") long tenantId, @Param("integrationId") String integrationId);
    /** 同库安全回执，不保存或重发秘密。 */
    InstitutionOperationRow operation(@Param("actor") String actor, @Param("kind") String kind, @Param("key") String key);
    /** 操作结果与业务写入原子提交。 */
    int insertOperation(InstitutionOperationRow row);
    /** 执行范围回执与平台管理回执分属RLS边界。 */
    InstitutionOperationRow scopeOperation(@Param("tenantId") long tenantId, @Param("integrationId") String integrationId,
            @Param("actor") String actor, @Param("kind") String kind, @Param("key") String key);
    /** 机构执行范围回执同当前范围写事务提交。 */
    int insertScopeOperation(InstitutionOperationRow row);
    /** 锁定指定机构/执行域内的主体，保护凭据CAS。 */
    ServicePrincipalDO lockPrincipal(@Param("tenantId") long tenantId, @Param("integrationId") String integrationId, @Param("id") long id);
    /** 正式凭证目录先过滤固定执行域再分页。 */
    List<ServicePrincipalDO> credentials(@Param("tenantId") long tenantId, @Param("integrationId") String integrationId,
            @Param("limit") int limit, @Param("offset") long offset);
    /** 同一凭证目录的总数。 */
    long credentialCount(@Param("tenantId") long tenantId, @Param("integrationId") String integrationId);
    /** 当前域内查询可信外部范围。 */
    IntegrationBusinessScopeRow businessScope(@Param("tenantId") long tenantId, @Param("integrationId") String integrationId,
            @Param("scopeType") String scopeType, @Param("externalRef") String externalRef);
    /** 所有范围消费者复用同一固定归属谓词。 */
    IntegrationBusinessScopeRow businessScopeById(@Param("tenantId") long tenantId, @Param("integrationId") String integrationId,
            @Param("scopeId") String scopeId);
    /** 声明范围，不能改变授权闭包。 */
    int insertBusinessScope(IntegrationBusinessScopeRow row);
    /** 精确旧revision保护并发状态更新。 */
    int updateBusinessScope(@Param("row") IntegrationBusinessScopeRow row, @Param("expected") long expected);
}
