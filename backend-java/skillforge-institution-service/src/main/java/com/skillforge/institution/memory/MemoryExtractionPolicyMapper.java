package com.skillforge.institution.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 提取前读取当前发布状态和显式开关；队列中的历史准入不授予新的模型调用许可。 */
@Mapper
public interface MemoryExtractionPolicyMapper {
    boolean automaticExtractionEnabled(@Param("tenant") String tenant, @Param("agent") String agent);
}
