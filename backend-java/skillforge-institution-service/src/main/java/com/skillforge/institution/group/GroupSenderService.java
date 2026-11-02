package com.skillforge.institution.group;

import static com.skillforge.institution.group.GroupControlTypes.*;
import static com.skillforge.institution.group.GroupControlService.*;
import static com.skillforge.institution.group.GroupSenderContracts.*;
import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 发送端注册世代、持久隔离及ACK；不发送网络消息、不把注册或失联当作退役证明。 */
@Service
public class GroupSenderService {
    private final GroupControlService controls;
    private final GroupControlMapper mapper;
    public GroupSenderService(GroupControlService controls,GroupControlMapper mapper){this.controls=controls;this.mapper=mapper;}

    /** 退役与新资格共用群锁；新代先REGISTERING，旧代明确保留为RETIRING。 */
    @Transactional(rollbackFor=Exception.class)
    public Registration register(Register command,String key) {
        Objects.requireNonNull(command);var access=controls.access("groups.senders.manage",command.groupBindingId());var group=controls.lock(access);controls.requireOpen(access);
        text(command.endpointId(),128);text(command.ledgerRef(),256);positive(command.generation());requireRevision(command.expectedGeneration());
        if(Set.of(RUNTIME,OUTBOX,MISSING_SENDER).contains(command.endpointId()))throw bad("端点身份为系统保留值");
        String fingerprint=controls.fingerprint(command);var prior=controls.prior(access,Operation.REGISTER,key,fingerprint,Registration.class);if(prior!=null)return prior;
        var endpoints=controls.endpoints(access);
        // 首版一个群只接一个逻辑发送账本，不能另取逻辑ID或空ledger绕过交接与重复发送保护。
        if(endpoints.stream().anyMatch(value->!value.endpointId().equals(command.endpointId())))throw conflict("该群已有逻辑发送端点，不能改名绕过账本交接");
        var previous=endpoints.stream().max(Comparator.comparingLong(GroupControlMapper.EndpointRow::generation));long generation=previous.map(GroupControlMapper.EndpointRow::generation).orElse(0L);
        if(generation!=command.expectedGeneration()||command.generation()!=Math.incrementExact(generation))throw conflict("端点世代CAS冲突");
        if(previous.isPresent()&&!previous.orElseThrow().ledgerRef().equals(command.ledgerRef()))throw conflict("换代必须接续原持久ledger");
        if(previous.isPresent()&&!EndpointStatus.RETIRED.name().equals(previous.orElseThrow().status()))mapper.retirePending(access.tenant(),access.integration(),access.group(),command.endpointId(),generation);
        var endpoint=new GroupControlMapper.EndpointRow(access.tenant(),access.integration(),access.group(),command.endpointId(),command.generation(),command.ledgerRef(),access.principal(),EndpointStatus.REGISTERING.name(),-1,0,null,null,"[]",null);
        mapper.endpoint(endpoint);
        var latest=mapper.latest(access.tenant(),access.integration(),access.group());String requiredCommand=latest==null?INITIAL_COMMAND:latest.commandId();
        controls.requireParticipant(access,requiredCommand,group.revision(),command.endpointId(),command.generation(),"required");
        var result=new Registration(command.endpointId(),command.generation(),command.ledgerRef(),endpoint.status(),group.revision(),controls.endpoints(access).stream().filter(value->EndpointStatus.RETIRING.name().equals(value.status())).map(GroupControlMapper.EndpointRow::generation).toList(),controls.snapshot(access));
        controls.saveRequest(access,Operation.REGISTER,key,fingerprint,result);controls.record(access,"sender.register",command.endpointId(),group.revision());return result;
    }

    /** 只有绑定principal可确认共享ledger内的隔离，保留原资格在途与UNKNOWN集合。 */
    @Transactional(rollbackFor=Exception.class)
    public Endpoint retire(Retire command,String key) {
        Objects.requireNonNull(command);var access=controls.access("groups.senders.manage",command.groupBindingId());var group=controls.lock(access);
        positive(command.oldGeneration());positive(command.newGeneration());positive(command.fenceRevision());text(command.retirementReceiptRef(),256);text(command.appliedBoundaryRef(),256);validateFlights(command.inFlight());
        var rows=controls.endpoints(access);var old=endpoint(rows,command.endpointId(),command.oldGeneration());var replacement=endpoint(rows,command.endpointId(),command.newGeneration());
        if(!access.principal().equals(old.principalId())&&!access.principal().equals(replacement.principalId()))throw denied("退役确认主体未绑定该端点");
        if(!old.ledgerRef().equals(command.ledgerRef())||!replacement.ledgerRef().equals(command.ledgerRef())||command.oldGeneration()>=command.newGeneration()
                ||rows.stream().anyMatch(row->row.generation()>command.newGeneration()))throw conflict("隔离证明的ledger或新旧世代不匹配");
        var fingerprint=controls.fingerprint(command);var replay=controls.prior(access,Operation.RETIRE,key,fingerprint,Endpoint.class);if(replay!=null)return replay;
        if(EndpointStatus.RETIRED.name().equals(old.status())) {
            if(!Objects.equals(old.retirementReceiptRef(),command.retirementReceiptRef())||old.fenceRevision()!=command.fenceRevision()
                    ||!Objects.equals(old.appliedBoundaryRef(),command.appliedBoundaryRef())||!new HashSet<>(command.inFlight()).equals(new HashSet<>(controls.decode(old.inFlightJson(),new TypeReference<List<GroupControlContracts.Flight>>(){}))))throw conflict("原退役依据不可替换");
        } else {
            report(access,command.endpointId(),command.oldGeneration(),command.inFlight());
            if(mapper.retired(access.tenant(),access.integration(),access.group(),command.endpointId(),command.oldGeneration(),command.fenceRevision(),command.retirementReceiptRef(),command.appliedBoundaryRef())!=1)throw conflict("旧代尚未隔离或隔离版本未前进");
        }
        var result=view(endpoint(controls.endpoints(access),command.endpointId(),command.oldGeneration()));controls.saveRequest(access,Operation.RETIRE,key,fingerprint,result);controls.record(access,"sender.retire",command.retirementReceiptRef(),group.revision());return result;
    }

    /** 按原命令/端点世代核实持久门禁；旧ACK可落事实但绝不降低应用版本或重新激活退役代。 */
    @Transactional(rollbackFor=Exception.class)
    public GroupControlContracts.DurableAck ack(Ack command,String key) {
        Objects.requireNonNull(command);var access=controls.access("groups.controls.ack",command.groupBindingId());var group=controls.lock(access);
        positive(command.generation());positive(command.fenceRevision());requireRevision(command.controlRevision());text(command.appliedBoundaryRef(),256);validateFlights(command.inFlight());
        var endpoint=bound(access,command.endpointId(),command.generation(),command.ledgerRef());
        if(EndpointStatus.RETIRED.name().equals(endpoint.status())||EndpointStatus.RETIRING.name().equals(endpoint.status()))throw conflict("退役代不能用ACK重新取得资格");
        var fingerprint=controls.fingerprint(command);var replay=controls.prior(access,Operation.ACK,key,fingerprint,GroupControlContracts.DurableAck.class);if(replay!=null)return replay;
        if(command.controlRevision()>group.revision())throw conflict("ACK不能确认未来控制版本");
        if(command.controlRevision()==0) {if(!INITIAL_COMMAND.equals(command.commandId()))throw conflict("初态ACK身份不匹配");}
        else {
            var row=mapper.command(access.tenant(),access.integration(),access.group(),command.commandId());
            if(row==null||!Result.ACCEPTED.wire.equals(row.resultKind())||!Objects.equals(row.acceptedRevision(),command.controlRevision()))throw conflict("ACK与原受理修订不匹配");
        }
        var rows=controls.endpoints(access);
        if(rows.stream().anyMatch(row->row.generation()<endpoint.generation()&&!EndpointStatus.RETIRED.name().equals(row.status())))throw conflict("旧代隔离尚待确认");
        long retiredFence=rows.stream().filter(row->row.generation()<endpoint.generation()).mapToLong(GroupControlMapper.EndpointRow::fenceRevision).max().orElse(0);
        if(command.fenceRevision()<Math.max(endpoint.fenceRevision(),retiredFence))throw conflict("ACK未接续持久隔离边界");
        controls.requireParticipant(access,command.commandId(),command.controlRevision(),command.endpointId(),command.generation(),"required");
        var fact=new GroupControlMapper.AckRow(access.tenant(),access.integration(),access.group(),command.commandId(),command.controlRevision(),command.endpointId(),command.generation(),command.ledgerRef(),command.appliedBoundaryRef(),command.fenceRevision(),access.principal());
        var prior=mapper.acks(access.tenant(),access.integration(),access.group(),command.commandId()).stream().filter(value->value.endpointId().equals(command.endpointId())&&value.generation()==command.generation()).findFirst();
        if(prior.isPresent()&&!prior.orElseThrow().equals(fact))throw conflict("原持久ACK内容不可替换");if(prior.isEmpty())mapper.ack(fact);
        if(command.controlRevision()==group.revision()) {
            if(mapper.endpointApplied(access.tenant(),access.integration(),access.group(),command.endpointId(),command.generation(),command.controlRevision(),command.fenceRevision(),command.appliedBoundaryRef())!=1)throw conflict("端点应用版本已变化");
            report(access,command.endpointId(),command.generation(),command.inFlight());
        }
        var result=new GroupControlContracts.DurableAck(command.endpointId(),command.generation(),command.controlRevision(),command.appliedBoundaryRef(),command.ledgerRef(),command.fenceRevision());controls.saveRequest(access,Operation.ACK,key,fingerprint,result);return result;
    }

    /** 最终资格检查仍须核有效代与已应用版本，注册/ACK的旧缓存不能授权旧进程。 */
    GroupControlMapper.EndpointRow bound(GroupControlService.Access access,String id,long generation,String ledger) {
        var row=endpoint(controls.endpoints(access),text(id,128),generation);
        if(!row.principalId().equals(access.principal())||ledger!=null&&!row.ledgerRef().equals(ledger))throw denied("认证主体或ledger未绑定该端点世代");return row;
    }
    private static GroupControlMapper.EndpointRow endpoint(List<GroupControlMapper.EndpointRow> rows,String id,long generation){return rows.stream().filter(row->row.endpointId().equals(id)&&row.generation()==generation).findFirst().orElseThrow(()->denied("端点世代未注册"));}
    static Endpoint view(GroupControlMapper.EndpointRow row){return new Endpoint(row.endpointId(),row.generation(),row.ledgerRef(),row.status(),row.appliedRevision(),row.fenceRevision(),row.retirementReceiptRef(),row.lastConfirmedAt()==null?null:row.lastConfirmedAt().toString(),ConnectionState.UNKNOWN.name());}
    /** 最后报告是外部账本的受控投影，逐项类型化保存，退役后不能替换原集合。 */
    private void report(GroupControlService.Access access,String endpoint,long generation,List<GroupControlContracts.Flight> flights){mapper.clearFlights(access.tenant(),access.integration(),access.group(),endpoint,generation);for(var flight:flights)mapper.flight(new GroupControlMapper.EndpointFlight(access.tenant(),access.integration(),access.group(),endpoint,generation,flight.deliveryId(),flight.status(),flight.resultRef()));}
    private static void positive(Long value){if(value==null||value<1)throw bad("端点世代或隔离版本必须显式为正整数");}
    private static void validateFlights(List<GroupControlContracts.Flight> flights){if(flights==null||flights.size()>1000)throw bad("在途集合缺失或过大");var ids=new HashSet<String>();for(var flight:flights){if(flight==null||!ids.add(text(flight.deliveryId(),128))||!Set.of("DISPATCHING","UNKNOWN").contains(flight.status()))throw bad("在途集合必须保留精确派发或未知身份");if(flight.resultRef()!=null)text(flight.resultRef(),256);}}
}
