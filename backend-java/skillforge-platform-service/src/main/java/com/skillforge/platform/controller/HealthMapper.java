package com.skillforge.platform.controller;

import org.apache.ibatis.annotations.Mapper;

/** 平台唯一数据源的只读健康探测，不查询机构业务数据。 */
@Mapper
public interface HealthMapper {
    /** 返回 SELECT 1 的结果；连接或 SQL 失败由健康路由转换为固定不可用分类。 */
    int ping();
}
