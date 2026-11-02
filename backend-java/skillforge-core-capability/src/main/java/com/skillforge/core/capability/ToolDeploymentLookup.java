package com.skillforge.core.capability;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordMapper;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordDO;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 租户内工具部署身份查询；挂载领域只持有稳定 ID 与业务键。 */
@Service
@Transactional(readOnly = true)
public class ToolDeploymentLookup {
    private final ToolDeploymentRecordMapper deployments;
    public ToolDeploymentLookup(ToolDeploymentRecordMapper deployments) { this.deployments = deployments; }

    public Identity findByKey(long tenantId, String key) {
        return identity(deployments.selectOne(new LambdaQueryWrapper<ToolDeploymentRecordDO>()
                .eq(ToolDeploymentRecordDO::getTenantId, tenantId)
                .eq(ToolDeploymentRecordDO::getDeploymentKey, key).last("LIMIT 1")));
    }

    public Identity findById(long tenantId, long id) {
        return identity(deployments.selectOne(new LambdaQueryWrapper<ToolDeploymentRecordDO>()
                .eq(ToolDeploymentRecordDO::getTenantId, tenantId)
                .eq(ToolDeploymentRecordDO::getId, id).last("LIMIT 1")));
    }

    public List<Identity> findByIds(long tenantId, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return deployments.selectList(new LambdaQueryWrapper<ToolDeploymentRecordDO>()
                .eq(ToolDeploymentRecordDO::getTenantId, tenantId)
                .in(ToolDeploymentRecordDO::getId, ids)).stream().map(this::identity).toList();
    }

    private Identity identity(ToolDeploymentRecordDO row) {
        return row == null ? null : new Identity(row.getId(), row.getDeploymentKey());
    }

    public record Identity(Long id, String deploymentKey) { }
}
