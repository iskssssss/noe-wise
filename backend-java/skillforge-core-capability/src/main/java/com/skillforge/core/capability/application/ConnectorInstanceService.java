package com.skillforge.core.capability.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.IService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.capability.persistence.ConnectorInstanceMapper;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordMapper;
import com.skillforge.core.capability.persistence.ConnectorInstanceDO;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordDO;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 连接器配置与删除规则；部署引用由当前 ToolDeployment 和数据库外键共同保护。 */
@Service
public class ConnectorInstanceService extends ServiceImpl<ConnectorInstanceMapper, ConnectorInstanceDO>
        implements IService<ConnectorInstanceDO> {
    private final ToolDeploymentRecordMapper deployments;

    public ConnectorInstanceService(ConnectorInstanceMapper instances, ToolDeploymentRecordMapper deployments) {
        this.baseMapper = Objects.requireNonNull(instances, "instances");
        this.deployments = Objects.requireNonNull(deployments, "deployments");
    }

    /** 同租户锁定实例后删除；停用部署仍保留引用，并发新增 MCP 引用由外键拒绝。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteUnused(Long tenantId, String instanceId) {
        if (tenantId == null || tenantId <= 0 || instanceId == null || instanceId.isBlank()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "连接器实例不存在");
        }
        ConnectorInstanceDO instance = getOne(new LambdaQueryWrapper<ConnectorInstanceDO>()
                .eq(ConnectorInstanceDO::getTenantId, tenantId)
                .eq(ConnectorInstanceDO::getInstanceId, instanceId)
                .last("LIMIT 1 FOR UPDATE"));
        if (instance == null) throw new ApiException(HttpStatus.NOT_FOUND, "连接器实例不存在");
        Long references = deployments.selectCount(new LambdaQueryWrapper<ToolDeploymentRecordDO>()
                .eq(ToolDeploymentRecordDO::getTenantId, tenantId)
                .eq(ToolDeploymentRecordDO::getProviderRef, instance.getInstanceId())
                .in(ToolDeploymentRecordDO::getProviderType, "MCP", "CONNECTOR"));
        if (references == null) throw new IllegalStateException("连接器部署引用计数缺失");
        if (references > 0) throw referenced();
        try {
            if (baseMapper.deleteById(instance.getId()) != 1) {
                throw new IllegalStateException("连接器删除未写入");
            }
        } catch (DataIntegrityViolationException referenced) {
            throw referenced();
        }
    }

    private ApiException referenced() {
        return new ApiException(HttpStatus.CONFLICT, "CONNECTOR_REFERENCED",
                "连接器仍被工具部署或其他资源引用，无法删除", false);
    }
}
