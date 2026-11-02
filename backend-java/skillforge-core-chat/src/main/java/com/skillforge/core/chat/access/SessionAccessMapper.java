package com.skillforge.core.chat.access;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 外部渠道Session边界的当前启用投影，不读取后台成员角色。 */
@Mapper
public interface SessionAccessMapper {
    /** 认证渠道与租户必须一致且当前启用。 */ boolean activeChannel(@Param("tenant") String tenant,@Param("channel") String channel);
}
