package com.skillforge.core.foundation.institution;

import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 独立部署管理许可仓储；读取先过滤双目标，执行时锁定许可和当前直属双方。 */
@Mapper
public interface InstitutionDeploymentManagementMapper {
    /** 平台列表在 SQL 中限制总部与目标都可见后分页。 */
    List<GrantRow> rows(@Param("allowed") Set<Long> allowed, @Param("actor") Long actor,
            @Param("target") Long target, @Param("limit") int limit, @Param("offset") long offset);
    /** 与分页共用相同双目标过滤。 */
    long count(@Param("allowed") Set<Long> allowed, @Param("actor") Long actor, @Param("target") Long target);
    /** 不向平台返回超出其双目标范围的许可。 */
    GrantRow visible(@Param("id") long id, @Param("allowed") Set<Long> allowed);
    /** 锁定既有许可，以 CAS 撤销。 */
    GrantRow lockGrant(@Param("id") long id);
    /** 顺序锁定总部与目标状态，防止许可受理期间机构被停用或关系迁移。 */
    List<TenantRow> lockTenants(@Param("actor") long actor, @Param("target") long target);
    /** 仅返回精确当前直接关系，不接受祖先关系。 */
    RelationRow currentRelation(@Param("actor") long actor, @Param("target") long target);
    /** 串行同关系、同动作的新许可创建，最终唯一性仍由数据库保障。 */
    void lockGrantKey(@Param("actor") long actor, @Param("target") long target,
            @Param("action") String action, @Param("relation") long relation);
    /** 当前已有许可使新创建明确冲突，不将新幂等键合并到旧记录。 */
    boolean activeExists(@Param("actor") long actor, @Param("target") long target,
            @Param("action") String action, @Param("relation") long relation);
    /** 插入新 ID 的许可；旧 revoked 许可不被改写。 */
    int insert(@Param("id") long id, @Param("actor") long actor, @Param("target") long target,
            @Param("action") String action, @Param("relation") long relation);
    /** 只有精确 active 修订能进入 revoked 终态。 */
    int revoke(@Param("id") long id, @Param("expected") long expected);
    /** 在原总部上下文中锁定精确有效许可及当前父子双方，直到领域事务结束。 */
    GrantRow lockAuthorization(@Param("actor") long actor, @Param("target") long target, @Param("action") String action);
    /** 返回当前精确许可的目标交集；状态和直属修订在数据库过滤。 */
    Set<Long> authorizedTargets(@Param("actor") long actor, @Param("action") String action);

    /**
     * 安全许可持久化投影，actor 来自不可变关系父机构与可信 actor_ref 的一致性关联。
     * @param grantId 稳定许可 ID
     * @param actorTenantId 获许可的总部
     * @param targetTenantId 精确目标
     * @param action 完整部署动作
     * @param status active 或 revoked
     * @param revision 许可修订
     * @param relationRef 不可变关系依据
     * @param relationRevision 绑定的关系修订
     */
    record GrantRow(long grantId, long actorTenantId, long targetTenantId, String action, String status,
            long revision, long relationRef, long relationRevision) { }
    /** @param tenantId 被锁定的机构 @param status 当前运营状态 */
    record TenantRow(long tenantId, String status) { }
    /** @param relationRef 当前关系依据 ID @param revision 当前关系修订 */
    record RelationRow(long relationRef, long revision) { }
}
