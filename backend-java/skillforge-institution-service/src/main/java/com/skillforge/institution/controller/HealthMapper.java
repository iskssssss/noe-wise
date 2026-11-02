package com.skillforge.institution.controller;
import org.apache.ibatis.annotations.Mapper;
/** 无业务数据访问的健康探测入口，使用宿主唯一数据源。 */
@Mapper
public interface HealthMapper {
    /** 无入参，返回 1；连接或 SQL 失败向控制器传播，由就绪响应转为不可用。 */
    int ping();
}
