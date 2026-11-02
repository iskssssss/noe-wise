package com.skillforge.institution.team;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
/** 所属领域持久化入口；SQL 位于 XML，参与调用方事务，执行日志由统一拦截器记录。 */
@Mapper
public interface TeamRecoveryMapper {
    /** activeTenants：参数限定查询范围；返回数据库结果，数据库错误交由事务边界传播。 */
    java.util.List<String> activeTenants();
}
