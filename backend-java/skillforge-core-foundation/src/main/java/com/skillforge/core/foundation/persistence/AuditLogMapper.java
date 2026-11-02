package com.skillforge.core.foundation.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.foundation.persistence.AuditLogDO;
import com.skillforge.core.foundation.dto.audit.AuditLogQuery;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;

/** 审计日志 MyBatis-Plus 映射接口。 */
public interface AuditLogMapper extends BaseMapper<AuditLogDO> {
    Page<AuditLogDO> selectLogs(Page<AuditLogDO> page, @Param("query") AuditLogQuery query,
                               @Param("tenantId") Long tenantId);
}
