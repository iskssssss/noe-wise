package com.skillforge.institution.team;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
/** 所属领域持久化入口；SQL 位于 XML，参与调用方事务，执行日志由统一拦截器记录。 */
@Mapper
public interface TeamDefinitionMapper {
    /** definitions：参数限定查询范围；返回数据库结果，数据库错误交由事务边界传播。 */
    java.util.List<String> definitions(@Param("tenant") long tenant);
    /** disable：参数限定查询范围；返回数据库结果，数据库错误交由事务边界传播。 */
    int disable(@Param("revision") long revision, @Param("definition") String definition, @Param("tenant") long tenant, @Param("id") String id);
    /** insertDraft：参数限定查询范围；返回数据库结果，数据库错误交由事务边界传播。 */
    int insertDraft(@Param("tenant") long tenant, @Param("id") String id, @Param("revision") long revision, @Param("status") String status, @Param("definition") String definition);
    /** updateDraft：参数限定查询范围；返回数据库结果，数据库错误交由事务边界传播。 */
    int updateDraft(@Param("revision") long revision, @Param("status") String status, @Param("definition") String definition, @Param("tenant") long tenant, @Param("id") String id);
    /** insertVersion：参数限定查询范围；返回数据库结果，数据库错误交由事务边界传播。 */
    int insertVersion(@Param("tenant") long tenant, @Param("id") String id, @Param("revision") long revision, @Param("definition") String definition);
    /** publish：参数限定查询范围；返回数据库结果，数据库错误交由事务边界传播。 */
    int publish(@Param("revision") long revision, @Param("publishedRevision") long publishedRevision, @Param("definition") String definition, @Param("tenant") long tenant, @Param("id") String id);
    /** published：参数限定查询范围；返回数据库结果，数据库错误交由事务边界传播。 */
    java.util.List<String> published(@Param("tenant") long tenant, @Param("id") String id);
    /** version：参数限定查询范围；返回数据库结果，数据库错误交由事务边界传播。 */
    java.util.List<String> version(@Param("tenant") long tenant, @Param("id") String id, @Param("revision") long revision);
    java.util.List<String> versions(@Param("tenant") long tenant, @Param("id") String id);
    /** revokedChannelMember：参数限定查询范围；返回数据库结果，数据库错误交由事务边界传播。 */
    boolean revokedChannelMember(@Param("tenant") long tenant, @Param("team") String team, @Param("agent") String agent);
    /** definition：参数限定查询范围；返回数据库结果，数据库错误交由事务边界传播。 */
    java.util.List<String> definition(@Param("tenant") long tenant, @Param("id") String id, @Param("lock") boolean lock);
}
