package com.skillforge.core.foundation.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.foundation.persistence.EndUserDO;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * C 端用户服务。
 *
 * <p>运行时执行链只接受入口显式提供并校验归属的 end_user_id。</p>
 */
@Service
public class EndUserService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(EndUserService.class);

    /** C 端用户表映射器。 */
    private final EndUserMapper endUserMapper;
    private final com.skillforge.core.foundation.workflowtest.WorkflowTestRegistry tests;

    /**
     * 构造 C 端用户服务。
     *
     * @param endUserMapper C 端用户表映射器
     */
    public EndUserService(EndUserMapper endUserMapper,com.skillforge.core.foundation.workflowtest.WorkflowTestRegistry tests) {
        this.endUserMapper = endUserMapper;this.tests=tests;
    }

    /**
     * 列出租户下全部 C 端用户。
     *
     * @param tenantId 租户 ID
     * @return 用户列表（按创建时间升序）
     */
    public List<EndUserDO> listByTenant(String tenantId) {
        return endUserMapper.selectList(
                new LambdaQueryWrapper<EndUserDO>()
                        .eq(EndUserDO::getTenantId, Long.parseLong(tenantId))
                        .apply("NOT EXISTS (SELECT 1 FROM nw_workflow_test_sessions wt WHERE wt.tenant_id=nw_end_users.tenant_id AND wt.end_user_id=nw_end_users.id)")
                        .orderByAsc(EndUserDO::getCreatedAt));
    }

    /** 管理端显式登记终端用户；不作为会话或运行时的默认身份回退。 */
    @Transactional
    public EndUserDO create(String tenantId, String displayName) {
        if (displayName == null || displayName.isBlank() || displayName.trim().length() > 128) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "终端用户名称须为 1 至 128 个字符");
        }
        EndUserDO user = new EndUserDO();
        user.setId(SnowflakeIds.nextId());
        user.setTenantId(Long.parseLong(tenantId));
        user.setDisplayName(displayName.trim());
        user.setStatus("active");
        Instant now = Instant.now();
        user.setFirstSeenAt(now);
        user.setLastActiveAt(now);
        user.setCreatedAt(now);
        endUserMapper.insert(user);
        return user;
    }

    public record TestIdentity(long endUserId) {}
    @Transactional
    public TestIdentity createTestIdentity(String tenantId,long testId) {return new TestIdentity(create(tenantId,"工作流隔离测试 "+testId).getId());}
    @Transactional
    public void retireTestIdentity(long tenant,long endUserId) {
        var test=tests.byEndUser(tenant,endUserId);
        if(test==null||!"closing".equals(test.status()))throw new SecurityException("workflow_test_close_not_requested");
        var user=endUserMapper.selectOne(new LambdaQueryWrapper<EndUserDO>().eq(EndUserDO::getTenantId,tenant).eq(EndUserDO::getId,endUserId));
        if(user==null)throw new IllegalStateException("workflow_test_end_user_missing");user.setStatus("deleted");
        if(endUserMapper.updateById(user)!=1)throw new IllegalStateException("workflow_test_end_user_retirement_failed");
    }

    /** 按已认证 Tenant/integration 确保外部 EndUser；并发首次请求复用同一主键，停用身份不得重建。 */
    @Transactional
    public EndUserDO ensureExternal(String tenantId, String integrationId, String externalUserRef, String displayName) {
        LOG.debug("外部终端用户登记进入 tenant={} integration={}", tenantId, integrationId);
        try {
            if (integrationId == null || integrationId.isBlank() || integrationId.length() > 128
                    || externalUserRef == null || externalUserRef.isBlank() || externalUserRef.length() > 512
                    || displayName != null && displayName.length() > 128) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "外部用户标识或显示名称无效");
            }
            var query = new LambdaQueryWrapper<EndUserDO>().eq(EndUserDO::getTenantId, Long.parseLong(tenantId))
                    .eq(EndUserDO::getExternalIntegrationId, integrationId)
                    .eq(EndUserDO::getExternalUserRef, externalUserRef);
            var existing = endUserMapper.selectOne(query);
            if (existing == null) {
                var user = new EndUserDO();
                user.setId(SnowflakeIds.nextId()); user.setTenantId(Long.parseLong(tenantId));
                user.setExternalIntegrationId(integrationId); user.setExternalUserRef(externalUserRef);
                user.setDisplayName(displayName); user.setStatus("active");
                var now = Instant.now(); user.setFirstSeenAt(now); user.setLastActiveAt(now); user.setCreatedAt(now);
                endUserMapper.insertExternal(user);
                existing = endUserMapper.selectOne(query);
                if (existing == null) throw new IllegalStateException("external_end_user_registration_missing");
            }
            if (!"active".equals(existing.getStatus())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "外部用户已停用");
            }
            return existing;
        } finally { LOG.debug("外部终端用户登记退出 tenant={} integration={}", tenantId, integrationId); }
    }

    /** 精确校验已登记的 Tenant/integration 映射；退出投影允许保留已停用的真实用户。 */
    public EndUserDO requireExternal(String tenantId, String integrationId, Long endUserId, boolean requireActive) {
        if (tenantId == null || tenantId.isBlank() || integrationId == null || integrationId.isBlank()
                || endUserId == null || endUserId <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "必须提供有效的外部 EndUser 映射");
        }
        EndUserDO user = endUserMapper.selectOne(new LambdaQueryWrapper<EndUserDO>()
                .eq(EndUserDO::getTenantId, Long.parseLong(tenantId))
                .eq(EndUserDO::getExternalIntegrationId, integrationId)
                .eq(EndUserDO::getId, endUserId));
        if (user == null) throw new ApiException(HttpStatus.NOT_FOUND, "EndUser 不属于当前执行域");
        if (requireActive && !"active".equals(user.getStatus()))
            throw new ApiException(HttpStatus.FORBIDDEN, "外部用户已停用");
        return user;
    }

    /**
     * 校验入口显式提供的终端用户归属；该方法不会创建或选择任何用户。
     *
     * @param tenantId 租户 ID
     * @param endUserId 入口 end_user_id
     * @return 已存在且属于租户的用户
     */
    public EndUserDO require(String tenantId, Long endUserId) {
        if (tenantId == null || tenantId.isBlank() || endUserId == null || endUserId <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "入口必须提供有效 end_user_id");
        }
        EndUserDO user = endUserMapper.selectOne(new LambdaQueryWrapper<EndUserDO>()
                .eq(EndUserDO::getTenantId, Long.parseLong(tenantId))
                .eq(EndUserDO::getId, endUserId)
                .last("LIMIT 1"));
        if (user == null) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "end_user_id 不属于当前租户: " + endUserId);
        }
        if (!"active".equals(user.getStatus())) throw new ApiException(HttpStatus.FORBIDDEN, "终端用户已停用");
        return user;
    }

}
