package com.skillforge.institution.team;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
/** 成果批次持久化，锁与插入使用调用方同一事务，禁止记录正文或授权摘要。 */
@Mapper
public interface TeamResultMapper {
    /** lock：参数限定当前领域事实；返回匹配结果，SQL 异常交由事务处理。 */
    String lock(@Param("key") String key);
    /** workStatus：参数限定当前领域事实；返回匹配结果，SQL 异常交由事务处理。 */
    java.util.List<String> workStatus(@Param("tenant") String tenant, @Param("session") String session, @Param("root") String root, @Param("task") String task, @Param("run") String run, @Param("agent") String agent);
    /** insert：参数限定当前领域事实；返回匹配结果，SQL 异常交由事务处理。 */
    int insert(@Param("tenant") String tenant, @Param("session") String session, @Param("root") String root, @Param("task") String task, @Param("run") String run, @Param("output") String output, @Param("result") String result, @Param("schema") String schema, @Param("kind") String kind, @Param("status") String status, @Param("payload") String payload, @Param("payloadSha") String payloadSha, @Param("commandSha") String commandSha, @Param("source") String source, @Param("invocation") String invocation, @Param("attempt") String attempt, @Param("authorization") String authorization);
    /** result：参数限定当前领域事实；返回匹配结果，SQL 异常交由事务处理。 */
    java.util.List<Row> result(@Param("tenant") String tenant, @Param("session") String session, @Param("root") String root, @Param("result") String result);
    /** invocation：参数限定当前领域事实；返回匹配结果，SQL 异常交由事务处理。 */
    java.util.List<Row> invocation(@Param("tenant") String tenant, @Param("invocation") String invocation);
    /** rows：参数限定当前领域事实；返回匹配结果，SQL 异常交由事务处理。 */
    java.util.List<Row> rows(@Param("tenant") String tenant, @Param("session") String session, @Param("root") String root, @Param("task") String task, @Param("run") String run);
    /** 仅用于结果映射，摘要校验及公开投影由服务负责。 */
    final class Row {public String payload,payloadSha256,commandSha256,runId,attemptId;public java.time.Instant createdAt;}
}
