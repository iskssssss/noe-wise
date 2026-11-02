package com.skillforge.core.capability;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Catalog 的事务级数据库安全上下文；授权检查由数据库上下文组件完成。 */
@Mapper
public interface CapabilityCatalogContextMapper {
    /**
     * 设置调用方当前事务的安全变量；仅接收数据库上下文组件选择的固定变量名。
     * @param key 固定的租户或平台授权变量名，不接收外部请求提供的变量名
     * @param value 经业务授权核验的上下文值
     * @return PostgreSQL 实际设置的值，仅用于完成语句，不写入日志
     */
    String bind(@Param("key") String key, @Param("value") String value);
}
