package com.skillforge.institution.group;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 群控制权威状态与持久传播记录；所有查询显式固定Tenant/integration/群。 */
@Mapper
public interface GroupControlMapper {
    /** Runtime构建期登记同一正式Mapper，借用原SqlSession，不另建事务或运行期修改配置。 */
    static void configure(org.apache.ibatis.session.Configuration configuration) {
        if(configuration.hasMapper(GroupControlMapper.class))return;
        String resource="mapper/GroupControlMapper.xml";
        try(var stream=GroupControlMapper.class.getClassLoader().getResourceAsStream(resource)) {
            if(stream==null)throw new IllegalStateException("group_control_mapper_missing");
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(stream,configuration,resource,configuration.getSqlFragments()).parse();
        } catch(java.io.IOException failure){throw new IllegalStateException("group_control_mapper_invalid",failure);}
    }
    GroupBindingMapper.Row binding(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group);
    int ensure(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group);
    ControlGroup lock(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group);
    ControlGroup current(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group);
    int advance(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("expected")long expected);
    List<State> states(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group);
    int state(State row);
    List<Boundary> boundaries(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group);
    int boundary(Boundary row);
    int endBoundaries(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("target")String target,@Param("through")long through);
    CommandRow command(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("id")String id);
    CommandRow latest(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group);
    int commandReceipt(CommandRow row);
    int commandScope(CommandScope row);
    int participant(Required row);
    List<Required> participants(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("command")String command);
    List<AckRow> acks(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("command")String command);
    int ack(AckRow row);
    List<EndpointRow> endpoints(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group);
    int endpoint(EndpointRow row);
    int retirePending(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("endpoint")String endpoint,@Param("generation")long generation);
    int retired(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("endpoint")String endpoint,@Param("generation")long generation,@Param("fence")long fence,@Param("receipt")String receipt,@Param("boundary")String boundary);
    int endpointApplied(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("endpoint")String endpoint,@Param("generation")long generation,@Param("revision")long revision,@Param("fence")long fence,@Param("boundary")String boundary);
    int clearFlights(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("endpoint")String endpoint,@Param("generation")long generation);
    int flight(EndpointFlight row);
    int eligibility(Eligibility row);
    int registeredSequence(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("sequence")long sequence);
    Eligibility root(@Param("tenant")long tenant,@Param("group")long group,@Param("trigger")String trigger);
    boolean runBelongs(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("run")String run);
    List<GroupControlContracts.Flight> runtimeFlights(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("target")String target);
    RequestRow request(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("actor")String actor,@Param("operation")String operation,@Param("key")String key);
    int requestReceipt(RequestRow row);
    Delivery delivery(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("id")String id);
    Delivery messageDelivery(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("run")String run,@Param("message")String message,@Param("version")long version);
    int insertDelivery(Delivery row);
    int release(ReleaseRow row);
    int releaseBoundary(ReleaseBoundary row);
    List<String> releases(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("delivery")String delivery);

    /** 单调命令版本与根登记序号；该行是群控制、发送边界和资格登记的串行边界。 */
    record ControlGroup(long tenantId,String integrationId,long groupBindingId,long revision,long latestTriggerSequence) { }
    /** 单个target/scope的当前期望；不覆盖同target其他scope。 */
    record State(long tenantId,String integrationId,long groupBindingId,String targetRef,String scope,boolean paused,long revision) { }
    /** 暂停保留历史覆盖；through=null只表示仍在暂停，恢复固化而不删除。 */
    record Boundary(long tenantId,String integrationId,long groupBindingId,String boundaryRef,String targetRef,Long throughSequence,long controlRevision) { }
    /** 原命令与首次回执不可变，acceptedRevision对未受理结果为null。 */
    record CommandRow(long tenantId,String integrationId,long groupBindingId,String commandId,String targetRef,String fingerprint,long expectedRevision,Long acceptedRevision,String desiredState,String scopesJson,String actorPrincipal,String resultKind,String resultRef,String responseJson,String localIntentId) { }
    /** 固定命令范围的一对多关系；scopesJson只是在SQL读取时生成的wire投影。 */
    record CommandScope(long tenantId,String integrationId,long groupBindingId,String commandId,String scope) { }
    /** 参与方由注册集合生成，不能由命令正文指定或删减。 */
    record Required(long tenantId,String integrationId,long groupBindingId,String commandId,long revision,String endpointId,long generation,String status) { }
    /** 经过注册principal核实的持久应用事实，重复同身份必须内容一致。 */
    record AckRow(long tenantId,String integrationId,long groupBindingId,String commandId,long revision,String endpointId,long generation,String ledgerRef,String appliedBoundaryRef,long fenceRevision,String actorPrincipal) { }
    /** 端点世代独立于凭据版本；retirement不清除原在途/UNKNOWN。 */
    record EndpointRow(long tenantId,String integrationId,long groupBindingId,String endpointId,long generation,String ledgerRef,String principalId,String status,long appliedRevision,long fenceRevision,String appliedBoundaryRef,String retirementReceiptRef,String inFlightJson,java.time.Instant lastConfirmedAt) { }
    /** 该端点最后持久ACK/退役报告的一项，中央不据此重新派发或改判外部UNKNOWN。 */
    record EndpointFlight(long tenantId,String integrationId,long groupBindingId,String endpointId,long generation,String deliveryId,String status,String resultRef) { }
    /** 原trigger的直接资格登记，不复制Run执行状态。 */
    record Eligibility(long tenantId,String integrationId,long groupBindingId,String triggerId,long triggerSequence,long registeredRevision,String runId) { }
    /** 独立管理动作的原回执，避免响应丢失后重复推进世代或重复放行。 */
    record RequestRow(long tenantId,String integrationId,long groupBindingId,String actorPrincipal,String operation,String requestKey,String fingerprint,String responseJson) { }
    /** 稳定delivery绑定唯一正式消息，换代不改变该身份。 */
    record Delivery(long tenantId,String integrationId,long groupBindingId,String deliveryId,String runId,String messageId,long messageVersion,String triggerId,String contentDigest) { }
    /** 只覆盖指定delivery的明确边界集合，后续暂停不会被旧release覆盖。 */
    record ReleaseRow(long tenantId,String integrationId,long groupBindingId,String releaseId,String deliveryId,long controlRevision,String actorPrincipal) { }
    /** 放行与所覆盖边界的类型化关系，含完整域FK，不通过JSON决定发送权。 */
    record ReleaseBoundary(long tenantId,String integrationId,long groupBindingId,String releaseId,String deliveryId,String boundaryRef) { }
}
