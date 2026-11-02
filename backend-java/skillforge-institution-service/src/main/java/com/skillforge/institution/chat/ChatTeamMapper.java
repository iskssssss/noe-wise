package com.skillforge.institution.chat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
/** 所属领域持久化入口；SQL 位于 XML，参与调用方事务，执行日志由统一拦截器记录。 */
@Mapper
public interface ChatTeamMapper {
    /** teamIds：参数限定查询范围；返回数据库结果，数据库错误交由事务边界传播。 */
    java.util.List<String> teamIds(@Param("tenant") long tenant, @Param("channel") long channel, @Param("clientRef") String clientRef);
    /** insertOwner：参数限定查询范围；返回数据库结果，数据库错误交由事务边界传播。 */
    int insertOwner(@Param("tenant") long tenant, @Param("team") String team, @Param("channel") long channel, @Param("clientRef") String clientRef);
    /** available：参数限定查询范围；返回数据库结果，数据库错误交由事务边界传播。 */
    boolean available(@Param("tenant") long tenant, @Param("channel") long channel, @Param("team") String team, @Param("endUser") String endUser);
    /** authorizedAgent：参数限定查询范围；返回数据库结果，数据库错误交由事务边界传播。 */
    boolean authorizedAgent(@Param("tenant") long tenant, @Param("channel") long channel, @Param("agent") String agent);
    /** owns：参数限定查询范围；返回数据库结果，数据库错误交由事务边界传播。 */
    boolean owns(@Param("tenant") long tenant, @Param("channel") long channel, @Param("clientRef") String clientRef, @Param("team") String team);
}
