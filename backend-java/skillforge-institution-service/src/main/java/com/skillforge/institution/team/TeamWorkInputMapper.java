package com.skillforge.institution.team;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
/** DAG 控制输入持久化；所有操作参加调用方事务，锁用于幂等登记串行化。 */
@Mapper
public interface TeamWorkInputMapper {
    /** 参数是带租户的锁键；仅获取事务锁，失败由事务边界传播。 */
    String lock(@Param("key") String key);
    /** 保存输入，重复键不写入；返回插入行数，输入内容由服务核对。 */
    int insert(@Param("tenant") String tenant,@Param("session") String session,@Param("root") String root,@Param("input") String input,@Param("payload") String payload);
    /** 按租户、会话和输入键读取幂等事实；不存在返回 null。 */
    Row find(@Param("tenant") String tenant,@Param("session") String session,@Param("input") String input);
    /** 读取当前工作指定状态的输入，按接收顺序返回；无匹配返回空列表。 */
    List<Row> pending(@Param("tenant") String tenant,@Param("session") String session,@Param("root") String root,@Param("state") String state);
    /** 返回当前工作最近的有限回执，不跨工作查询。 */
    List<Row> recent(@Param("tenant") String tenant,@Param("session") String session,@Param("root") String root,@Param("limit") int limit);
    /** 仅从预期状态完成回执；重复完成返回零，禁止覆盖已经终结的事实。 */
    int settle(@Param("state") String state,@Param("detail") String detail,@Param("tenant") String tenant,@Param("session") String session,@Param("root") String root,@Param("input") String input,@Param("expected") String expected);
    /** 数据库行映射，正文不进入日志；时间使用 MyBatis Instant 映射。 */
    final class Row {public String rootInputId,inputId,payload,state,detail;public Instant receivedAt;}
}
