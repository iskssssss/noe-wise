package com.skillforge.institution.runtime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
/** Agent Run 不可变绑定；SQL 位于 AgentRunBindingsMapper.xml。 */
@Mapper
public interface AgentRunBindingsMapper {
    /** 核对当前租户渠道与目标授权，返回是否存在；失败直接传播。 */
    boolean channelTargetAvailable(@Param("tenant") long tenant,@Param("channel") long channel,@Param("agent") long agent);
    /** 核对会话绑定的终端用户仍有效，不将 Mapper 参数视为认证依据。 */
    boolean endUserAvailable(@Param("tenant") long tenant,@Param("session") String session,@Param("endUser") long endUser);
    /** 重验首次受理的服务主体仍有效，服务身份只接受可信入口传入。 */
    boolean serviceRequesterAvailable(@Param("tenant") long tenant, @Param("principal") String principal,
            @Param("integration") String integration, @Param("credentialVersion") long credentialVersion);
    /** 群原执行主体只需群trigger用途权限，不要求私聊sessions.write。 */
    boolean groupServiceRequesterAvailable(@Param("tenant") long tenant,@Param("principal") String principal,
            @Param("integration") String integration);
    /** 群资格沿原固定执行域和真实成员读取；目标Agent为空只做群/成员核验。 */
    boolean groupRequesterAvailable(@Param("tenant") long tenant,@Param("group") String group,
            @Param("integration") String integration,@Param("requester") long requester,@Param("agent") String agent);
    /** 群无主Agent；实际服务Agent必须显式在当前名单且群/业务范围有效。 */
    boolean groupActorAvailable(@Param("tenant") long tenant,@Param("group") String group,
            @Param("integration") String integration,@Param("agent") String agent);
    Row find(@Param("tenant") String tenant,@Param("runId") String runId);
    Row findLatestPrimary(@Param("tenant") String tenant,@Param("sessionId") String sessionId,
            @Param("targetKind") String targetKind,@Param("targetId") String targetId,@Param("endUserId") long endUserId);
    int insert(@Param("row") Row row);
    final class Row {
        public String tenantId,runId,inputId,sessionId,targetKind,targetId,actorAgentId,role,parentRunId,scope,frozenMember;
        public Long endUserId,requesterEndUserId;
    }
}
