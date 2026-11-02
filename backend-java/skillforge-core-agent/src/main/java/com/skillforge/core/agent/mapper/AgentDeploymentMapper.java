package com.skillforge.core.agent.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Param;

/** 平台直授与机构部署的持久边界，租户对象读取显式限定 tenantId。 */
public interface AgentDeploymentMapper {
    DatabaseContext databaseContext();
    String restoreDatabaseContext(@Param("tenant")String tenant,@Param("platformAdmin")String platformAdmin);
    /** 外部映射键串行创建，不建立全局 IAM 锁。 */
    String lockExternal(@Param("integrationId")String integrationId,@Param("tenantId")long tenantId,@Param("externalAgentRef")String externalAgentRef);
    Row findExternal(@Param("integrationId")String integrationId,@Param("tenantId")long tenantId,@Param("externalAgentRef")String externalAgentRef);
    Row findAgent(@Param("tenantId")long tenantId,@Param("agentId")long agentId);
    Row findDeployment(@Param("tenantId")long tenantId,@Param("deploymentId")long deploymentId);
    Row lockDeployment(@Param("tenantId")long tenantId,@Param("deploymentId")long deploymentId);
    Row lockAgent(@Param("tenantId")long tenantId,@Param("agentId")long agentId);
    int switchAuthorization(@Param("tenantId")long tenantId,@Param("deploymentId")long deploymentId,@Param("expectedRevision")long expectedRevision,
            @Param("grantId")long grantId,@Param("grantRevision")long grantRevision,@Param("grantChain")String grantChain);
    int appendAuthorizationRevision(@Param("deploymentId")long deploymentId,@Param("actorRef")String actorRef,@Param("commandRef")String commandRef,@Param("fingerprint")String fingerprint);
    String findRevisionResponse(@Param("deploymentId")long deploymentId,@Param("revision")long revision);
    int saveRevisionResponse(@Param("deploymentId")long deploymentId,@Param("revision")long revision,@Param("response")String response);
    List<Row> list(@Param("tenantId")long tenantId,@Param("integrationId")String integrationId);
    /** 只投影声明匹配所需的键和类型；不读取资源引用、参数值、机构正文。 */
    List<ImpactRow> impact(@Param("tenantId")long tenantId,@Param("definitionId")long definitionId);
    int insert(Row row);
    int updateBindings(@Param("tenantId")long tenantId,@Param("agentId")long agentId,@Param("expectedRevision")long expectedRevision,@Param("bindings")String bindings);
    int appendRevision(@Param("deploymentId")long deploymentId,@Param("actorRef")String actorRef,@Param("commandRef")String commandRef);
    int appendBindingRevision(@Param("deploymentId")long deploymentId,@Param("actorRef")String actorRef,@Param("commandRef")String commandRef,@Param("fingerprint")String fingerprint);
    Revision findRevisionRequest(@Param("deploymentId")long deploymentId,@Param("actorRef")String actorRef,@Param("commandRef")String commandRef);
    String findCreateResponse(@Param("deploymentId")long deploymentId,@Param("tenantId")long tenantId,@Param("actorRef")String actorRef,@Param("commandRef")String commandRef);
    int recordCreateRequest(@Param("deploymentId")long deploymentId,@Param("tenantId")long tenantId,@Param("actorRef")String actorRef,@Param("commandRef")String commandRef);
    /** 与撤销竞争相同授权行，锁覆盖短事务而非外部网络调用。 */
    GrantRow lockGrant(@Param("grantId")long grantId);
    GrantRow findGrant(@Param("grantId")long grantId);
    GrantRow findManagedGrant(@Param("grantId")long grantId,@Param("targets")Set<Long> targets,@Param("issuer")Long issuer);
    GrantRow findGrantRequest(@Param("actorRef")String actorRef,@Param("requestKey")String requestKey);
    int insertGrant(GrantRow row);
    int revokeGrant(@Param("grantId")long grantId,@Param("expectedRevision")long expectedRevision,@Param("actorRef")String actorRef,@Param("requestKey")String requestKey,@Param("fingerprint")String fingerprint);
    List<GrantRow> grants(@Param("tenantId")long tenantId);
    GrantRow grantRevision(@Param("grantId")long grantId,@Param("revision")long revision);
    int narrowGrant(@Param("grantId")long grantId,@Param("expectedRevision")long expectedRevision,@Param("actions")String actions,
            @Param("expiresAt")Instant expiresAt,@Param("allowRegrant")boolean allowRegrant,@Param("remainingDepth")int remainingDepth);
    int appendGrantRevision(@Param("grantId")long grantId,@Param("actorRef")String actorRef,@Param("operation")String operation,@Param("key")String key);
    GrantReceipt grantReceipt(@Param("actor")String actor,@Param("key")String key);
    int saveGrantReceipt(@Param("actor")String actor,@Param("key")String key,@Param("fingerprint")String fingerprint,@Param("grantId")long grantId,@Param("response")String response);
    TenantRow grantTenant(@Param("tenantId")long tenantId);
    TenantRow lockGrantTenant(@Param("tenantId")long tenantId);
    RelationRow grantRelation(@Param("child")long child,@Param("parent")long parent);
    boolean chainAvailable(@Param("chain")String chain,@Param("tenantId")long tenantId,@Param("definitionId")long definitionId,@Param("action")String action);
    List<GrantRow> issuedGrants(@Param("tenantId")long tenantId);
    record GrantReceipt(String fingerprint,String response) { }
    record TenantRow(Long tenantId,Long parentTenantId,String status,long relationRevision) { }
    record RelationRow(Long relationRef,long revision) { }
    /** 部署持有固定 grant 修订，旧 Run 不从当前实例重选另一条链。 */
    record Row(Long deploymentId,String integrationId,Long tenantId,Long agentId,Long definitionId,Long grantId,long grantRevision,
            String externalAgentRef,String fingerprint,String bindings,long revision,Instant createdAt,String grantChain) { }
    /** 平台原授，保留原创建键及撤销回执。 */
    record GrantRow(Long grantId,Long definitionId,Long targetTenantId,String actions,Instant expiresAt,String status,long revision,
            String actorRef,String requestKey,String fingerprint,String revokeKey,String revokeFingerprint,Instant createdAt,
            String ownerKind,Long ownerTenantId,String grantorKind,Long grantorTenantId,Long parentGrantId,Long parentGrantRevision,
            Long relationRef,Long relationRevision,boolean allowRegrant,int remainingDepth) { }
    /** 临时租户绑定退出时恢复连接原值。 */
    record DatabaseContext(String tenant,String platformAdmin) { }
    /** 精确绑定命令回执；重投不覆盖后续修订。 */
    record Revision(long revision,Long grantId,long grantRevision,String bindings,String fingerprint,String grantChain,String response) { }
    /** bindingShape 是受控安全 JSON，不是完整 bindings。 */
    record ImpactRow(Long deploymentId,Long tenantId,Long agentId,long revision,String bindingShape) { }
}
