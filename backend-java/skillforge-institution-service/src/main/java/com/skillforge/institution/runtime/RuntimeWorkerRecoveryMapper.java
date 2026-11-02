package com.skillforge.institution.runtime;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 启动时恢复持久在途输入与 Workflow 所属租户，SQL 统一归 Mapper XML。 */
@Mapper
public interface RuntimeWorkerRecoveryMapper {
    List<String> activeTenants();
    /** 有界扫描当前活跃租户，after为空从第一页开始；文本ID排序与游标比较使用同一表达式。 */
    List<String> activeTenantPage(@Param("after") String after, @Param("limit") int limit);
    String bindTenant(@Param("tenant") String tenant);
    long unfinished();
}
