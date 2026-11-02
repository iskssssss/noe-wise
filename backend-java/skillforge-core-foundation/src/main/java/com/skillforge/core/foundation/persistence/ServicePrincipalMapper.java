package com.skillforge.core.foundation.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.foundation.persistence.ServicePrincipalDO;
import org.apache.ibatis.annotations.Param;

/** ServicePrincipal Mapper；认证前查询必须使用受限 SECURITY DEFINER 函数。 */
public interface ServicePrincipalMapper extends BaseMapper<ServicePrincipalDO> {

    String lockIntegration(@Param("tenant") String tenant, @Param("integration") String integration);

    ServicePrincipalDO selectForAuthByApiKeyHash(@Param("apiKeyHash") String apiKeyHash);
}
