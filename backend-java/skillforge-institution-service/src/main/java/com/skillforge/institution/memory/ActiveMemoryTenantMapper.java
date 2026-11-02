package com.skillforge.institution.memory;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 领域内部持久化入口；租户绑定由 MyBatis 拦截器在当前事务连接上完成。 */
@Mapper
public interface ActiveMemoryTenantMapper {
    /** 读取游标之后最多 200 个活跃租户；仅供受信任后台任务扫描全局目录。 */
    List<String> after(@Param("after") long after);
}
