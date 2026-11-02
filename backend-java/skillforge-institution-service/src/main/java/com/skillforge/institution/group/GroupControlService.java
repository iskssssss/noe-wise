package com.skillforge.institution.group;

import static com.skillforge.institution.group.GroupControlContracts.*;
import static com.skillforge.institution.group.GroupControlTypes.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.port.RunIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.apache.ibatis.session.SqlSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 群期望门禁与命令传播权威；只提交控制事实，执行仍由既有Runtime推进。 */
@Service
public class GroupControlService {
    private static final String SCOPE_PREFIX="groups.controls.", SENDER_MANAGE_SCOPE="groups.senders.manage";
    /** 协议原回执data对象的UTF-8上限；超限整笔回滚，不截断参与方或在途。 */
    private static final int MAX_RECEIPT_BYTES=4*1024*1024;
    private final GroupControlMapper mapper;
    private final InstitutionIntegrationService integrations;
    private final AuditService audit;
    private final ObjectMapper json;

    /** 不依赖Runtime或final读取，避免宿主构造循环；公开final由独立Delivery服务延迟消费。 */
    public GroupControlService(GroupControlMapper mapper,InstitutionIntegrationService integrations,AuditService audit,ObjectMapper json) {
        this.mapper=Objects.requireNonNull(mapper);this.integrations=Objects.requireNonNull(integrations);this.audit=Objects.requireNonNull(audit);
        this.json=Objects.requireNonNull(json).copy().setSerializationInclusion(JsonInclude.Include.ALWAYS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /** 授权及CAS拒绝不发起Runtime动作；原成功/拒绝回执与命令内容均不可变。 */
    @Transactional(rollbackFor=Exception.class)
    public Receipt command(Command input,String key) {
        var command=normalize(input);if(!command.commandId().equals(text(key,256)))throw bad("Idempotency-Key必须等于command_id");
        var access=access(SCOPE_PREFIX+"write",command.groupBindingId());var group=lock(access);
        String fingerprint=commandFingerprint(command),target=target(command.runId());
        var prior=mapper.command(access.tenant(),access.integration(),access.group(),command.commandId());
        if(prior!=null){sameFingerprint(prior.fingerprint(),fingerprint);return decode(prior.responseJson(),Receipt.class);}
        requireOpen(access);requireRun(access,command.runId());
        var desired=Desired.parse(command.desiredState());var scopes=command.scopes().stream().map(Scope::parse).toList();
        if(group.revision()!=command.expectedRevision()) {
            var receipt=new Receipt(command.commandId(),command.groupBindingId(),command.runId(),fingerprint,group.revision(),desired.wire,command.scopes(),Status.REJECTED.name(),Result.REJECTED.wire,
                    resultRef(command.commandId()),List.of(),List.of(),flights(access,target),null,null);
            saveCommand(access,command,receipt,null,null);return receipt;
        }
        long revision=Math.incrementExact(group.revision());
        if(mapper.advance(access.tenant(),access.integration(),access.group(),group.revision())!=1)throw conflict("控制CAS冲突");
        for(var scope:scopes)mapper.state(new GroupControlMapper.State(access.tenant(),access.integration(),access.group(),target,scope.wire,desired==Desired.PAUSED,revision));
        String boundary=null;
        if(scopes.contains(Scope.SEND)) {
            if(desired==Desired.PAUSED) {
                boundary="control:"+access.group()+":"+revision;
                mapper.boundary(new GroupControlMapper.Boundary(access.tenant(),access.integration(),access.group(),boundary,target,null,revision));
            } else mapper.endBoundaries(access.tenant(),access.integration(),access.group(),target,group.latestTriggerSequence());
        }
        // 内部门禁与期望同事务持久化，发送端必须另交经过端点身份核实的持久ACK。
        if(scopes.stream().anyMatch(scope->scope!=Scope.SEND))internalAck(access,command.commandId(),revision,RUNTIME);
        if(scopes.contains(Scope.SEND)) {
            internalAck(access,command.commandId(),revision,OUTBOX);
            var endpoints=endpoints(access).stream().filter(row->!EndpointStatus.RETIRED.name().equals(row.status())).toList();
            if(endpoints.isEmpty())requireParticipant(access,command.commandId(),revision,MISSING_SENDER,0,"missing");
            for(var endpoint:endpoints)requireParticipant(access,command.commandId(),revision,endpoint.endpointId(),endpoint.generation(),"required");
        }
        var receipt=receipt(access,command.commandId(),target,fingerprint,revision,desired.wire,command.scopes(),Result.ACCEPTED,boundary,null,false);
        saveCommand(access,command,receipt,revision,null);return receipt;
    }

    /** 独立的对账动作可以由当前获准管理者接续，终结后迟到副本永远不能再受理原command。 */
    @Transactional(rollbackFor=Exception.class)
    public Receipt terminalize(Terminalize command,String key) {
        Objects.requireNonNull(command);var access=access(SCOPE_PREFIX+"reconcile",command.groupBindingId());var group=lock(access);
        text(command.commandId(),128);text(command.localIntentId(),128);if(command.fingerprint()==null||!command.fingerprint().matches("[0-9a-f]{64}"))throw bad("命令指纹无效");
        requireRevision(command.expectedRevision());String fingerprint=fingerprint(command);
        var replay=prior(access,Operation.TERMINALIZE,key,fingerprint,Receipt.class);if(replay!=null)return replay;
        var original=mapper.command(access.tenant(),access.integration(),access.group(),command.commandId());
        if(original!=null) {
            sameFingerprint(original.fingerprint(),command.fingerprint());
            if(!original.targetRef().equals(target(command.runId())))throw conflict("终结范围与原命令不一致");
            if(Result.ACCEPTED.wire.equals(original.resultKind())) {
                var result=detail(access,original,group.revision());saveRequest(access,Operation.TERMINALIZE,key,fingerprint,result);return result;
            }
            if(Result.TERMINAL_NOT_ACCEPTED.wire.equals(original.resultKind())) {
                var result=decode(original.responseJson(),Receipt.class);saveRequest(access,Operation.TERMINALIZE,key,fingerprint,result);return result;
            }
        }
        if(group.revision()!=command.expectedRevision())throw conflict("终结CAS已过时，须先读回当前控制");
        requireRun(access,command.runId());
        String boundary="local-intent:"+hash(command.commandId()+"\0"+command.localIntentId());
        var existingBoundary=boundaries(access).stream().filter(row->row.boundaryRef().equals(boundary)).findFirst();
        if(existingBoundary.isEmpty())mapper.boundary(new GroupControlMapper.Boundary(access.tenant(),access.integration(),access.group(),boundary,target(command.runId()),group.latestTriggerSequence(),group.revision()));
        long through=existingBoundary.map(GroupControlMapper.Boundary::throughSequence).orElse(group.latestTriggerSequence());
        long boundaryRevision=existingBoundary.map(GroupControlMapper.Boundary::controlRevision).orElse(group.revision());
        var result=new Receipt(command.commandId(),command.groupBindingId(),command.runId(),command.fingerprint(),boundaryRevision,null,List.of(),
                Status.TERMINAL_NOT_ACCEPTED.name(),Result.TERMINAL_NOT_ACCEPTED.wire,resultRef(command.commandId()),List.of(),List.of(),flights(access,target(command.runId())),boundary,through);
        if(original==null)mapper.commandReceipt(new GroupControlMapper.CommandRow(access.tenant(),access.integration(),access.group(),command.commandId(),target(command.runId()),command.fingerprint(),command.expectedRevision(),null,null,"[]",access.principal(),Result.TERMINAL_NOT_ACCEPTED.wire,result.resultRef(),receiptJson(result),command.localIntentId()));
        saveRequest(access,Operation.TERMINALIZE,key,fingerprint,result);record(access,"terminalize",command.commandId(),group.revision());return result;
    }

    /** 读取当前传播事实；不会把原POST快照误作当前期望。 */
    @Transactional(readOnly=true)
    public Receipt detail(String group,String command) {
        var access=access(SCOPE_PREFIX+"read",group);var state=currentGroup(access);var row=mapper.command(access.tenant(),access.integration(),access.group(),text(command,128));
        if(row==null)throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","控制命令尚无持久回执",false);return detail(access,row,state.revision());
    }
    /** 当前快照不创建控制版本；空端点集合从来不提供自动外发资格。 */
    @Transactional(readOnly=true)
    public Current current(String group) {return snapshot(access(SCOPE_PREFIX+"read",group));}
    /** 本地安全阻止写入前复验精确动作权限；结果不是长期令牌。 */
    @Transactional(readOnly=true)
    public Authorization authorize(Authorize command) {
        Objects.requireNonNull(command);access(SCOPE_PREFIX+"read",command.groupBindingId());
        String scope=switch(command.action()==null?"":command.action()) {
            case "write","read","reconcile","release","ack"->SCOPE_PREFIX+command.action();case "manage"->SENDER_MANAGE_SCOPE;default->throw bad("授权动作非法");};
        var access=access(scope,command.groupBindingId());var group=currentGroup(access);
        return new Authorization(command.groupBindingId(),command.action(),access.principal(),true,group.revision(),group.latestTriggerSequence());
    }

    /** 在原trigger事务里登记根资格；资格与暂停同群锁串行，不使用消息或客户端时间猜先后。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void registerTrigger(Long tenant,String integration,Long group,String trigger,long sequence) {
        if(tenant==null||group==null||sequence<1||!Long.toString(tenant).equals(TenantContext.getTenantId()))throw denied("根资格登记执行域非法");
        var access=new Access(tenant,text(integration,128),group,"runtime",null);var current=lock(access);
        var prior=mapper.root(tenant,group,text(trigger,64));
        if(prior!=null){if(prior.triggerSequence()!=sequence||!prior.integrationId().equals(integration))throw conflict("原trigger资格不可换绑");return;}
        if(sequence<=current.latestTriggerSequence())throw conflict("根触发序号未前进");
        if(mapper.eligibility(new GroupControlMapper.Eligibility(tenant,integration,group,trigger,sequence,current.revision(),null))!=1
                ||mapper.registeredSequence(tenant,integration,group,sequence)!=1)throw new IllegalStateException("group_trigger_eligibility_commit_failed");
    }

    /** 仅新派发资格事务调用；借用原SqlSession并持有控制锁到Attempt提交，既有Registered不重复核此门禁。 */
    public boolean allowDispatch(SqlSession session,RunIdentity identity,boolean businessWrite) {
        Objects.requireNonNull(session);Objects.requireNonNull(identity);
        if(!(identity.owner().subject() instanceof SessionSubject.Group group))return true;
        var store=session.getMapper(GroupControlMapper.class);var root=root(store,identity);
        if(root==null)return false;
        var access=new Access(root.tenantId(),root.integrationId(),root.groupBindingId(),RUNTIME,null);
        store.ensure(access.tenant(),access.integration(),access.group());if(store.lock(access.tenant(),access.integration(),access.group())==null)return false;
        return store.states(access.tenant(),access.integration(),access.group()).stream().noneMatch(state->state.paused()
                &&matches(state.targetRef(),identity.runRef().runId(),root.runId())&&(Scope.EXECUTION.wire.equals(state.scope())||businessWrite&&Scope.BUSINESS_WRITE.wire.equals(state.scope())));
    }

    /** 固定SP与具体群执行域复验；控制权不从成员资格或请求正文推导。 */
    Access access(String scope,String group) {
        var actor=ServicePrincipalContext.get();if(actor==null||!Objects.equals(actor.tenantId(),TenantContext.getTenantId()))throw denied("控制须使用当前机构固定执行身份");
        integrations.requirePrincipal(actor,scope);long tenant=id(actor.tenantId()),binding=id(group);
        var row=mapper.binding(tenant,actor.integrationId(),binding);if(row==null)throw denied("群不属于当前执行域");
        return new Access(tenant,actor.integrationId(),binding,actor.principalId(),row);
    }
    /** 先建立唯一群门禁行再锁定；所有中心写动作使用同一顺序。 */
    GroupControlMapper.ControlGroup lock(Access access) {
        mapper.ensure(access.tenant(),access.integration(),access.group());var group=mapper.lock(access.tenant(),access.integration(),access.group());
        if(group==null)throw new IllegalStateException("group_control_lock_missing");return group;
    }
    GroupControlMapper.ControlGroup currentGroup(Access access) {
        var group=mapper.current(access.tenant(),access.integration(),access.group());return group==null?new GroupControlMapper.ControlGroup(access.tenant(),access.integration(),access.group(),0,0):group;
    }
    /** 只沿正式RunOrigin和持久根trigger查找资格，来源缺失时失败关闭。 */
    GroupControlMapper.Eligibility root(GroupControlMapper source,RunIdentity identity) {
        if(!(identity.owner().subject() instanceof SessionSubject.Group group)||!identity.originRef().originId().startsWith(ORIGIN_PREFIX))return null;
        var root=source.root(id(identity.runRef().tenantId().value()),id(group.groupBindingRef().groupBindingId()),identity.originRef().originId().substring(ORIGIN_PREFIX.length()));
        return root!=null&&root.runId()!=null?root:null;
    }
    List<GroupControlMapper.State> states(Access access){return mapper.states(access.tenant(),access.integration(),access.group());}
    List<GroupControlMapper.Boundary> boundaries(Access access){return mapper.boundaries(access.tenant(),access.integration(),access.group());}
    List<GroupControlMapper.EndpointRow> endpoints(Access access){return mapper.endpoints(access.tenant(),access.integration(),access.group());}
    void requireOpen(Access access){if(access.binding()!=null&&!"active".equals(access.binding().status()))throw conflict("群生命周期已关闭");}
    void requireRun(Access access,String run){if(run!=null&&!mapper.runBelongs(access.tenant(),access.integration(),access.group(),text(run,128)))throw denied("定向Run不属于当前群");}
    static boolean matches(String target,String run,String root){return GROUP_TARGET.equals(target)||target.equals(run)||target.equals(root);}

    /** 同一命令修订的内部原子门禁ACK，不伪造远端确认。 */
    private void internalAck(Access access,String command,long revision,String endpoint) {
        requireParticipant(access,command,revision,endpoint,0,"required");
        mapper.ack(new GroupControlMapper.AckRow(access.tenant(),access.integration(),access.group(),command,revision,endpoint,0,"skillforge:"+access.group(),"control:"+revision,revision,RUNTIME));
    }
    void requireParticipant(Access access,String command,long revision,String endpoint,long generation,String status){mapper.participant(new GroupControlMapper.Required(access.tenant(),access.integration(),access.group(),command,revision,endpoint,generation,status));}
    /** 当前端点、ACK与退役依据共同形成实际状态；空sender占位只能由真实已注册参与方接续。 */
    private Receipt receipt(Access access,String command,String target,String fingerprint,long revision,String desired,List<String> scopes,Result result,String boundary,Long through,boolean superseded) {
        var acks=mapper.acks(access.tenant(),access.integration(),access.group(),command);var endpoints=endpoints(access);
        var required=mapper.participants(access.tenant(),access.integration(),access.group(),command).stream().map(participant->{
            var ack=acks.stream().filter(value->value.endpointId().equals(participant.endpointId())&&value.generation()==participant.generation()&&value.revision()==revision).findFirst();
            var endpoint=endpoints.stream().filter(value->value.endpointId().equals(participant.endpointId())&&value.generation()==participant.generation()).findFirst();
            boolean retired=endpoint.filter(value->EndpointStatus.RETIRED.name().equals(value.status())&&value.retirementReceiptRef()!=null).isPresent()
                    ||MISSING_SENDER.equals(participant.endpointId())&&endpoints.stream().anyMatch(value->!EndpointStatus.RETIRED.name().equals(value.status()));
            return new Participant(participant.endpointId(),participant.generation(),participant.revision(),retired?EndpointStatus.RETIRED.name():ack.isPresent()?Status.APPLIED.name():Status.ACCEPTED.name(),ack.map(GroupControlMapper.AckRow::appliedBoundaryRef).orElse(null));
        }).toList();
        boolean all=!required.isEmpty()&&required.stream().allMatch(value->Status.APPLIED.name().equals(value.status())||EndpointStatus.RETIRED.name().equals(value.status()));
        var status=superseded?Status.SUPERSEDED:all?Status.APPLIED:acks.isEmpty()?Status.ACCEPTED:Status.PARTIAL;
        return new Receipt(command,Long.toString(access.group()),run(target),fingerprint,revision,desired,scopes,status.name(),result.wire,resultRef(command),required,
                acks.stream().map(value->new DurableAck(value.endpointId(),value.generation(),value.revision(),value.appliedBoundaryRef(),value.ledgerRef(),value.fenceRevision())).toList(),flights(access,target),boundary,through);
    }
    Receipt detail(Access access,GroupControlMapper.CommandRow command,long currentRevision) {
        var original=decode(command.responseJson(),Receipt.class);if(!Result.ACCEPTED.wire.equals(command.resultKind()))return original;
        return receipt(access,command.commandId(),command.targetRef(),command.fingerprint(),command.acceptedRevision(),command.desiredState(),strings(command.scopesJson()),Result.ACCEPTED,original.boundaryRef(),original.throughTriggerSequence(),currentRevision>command.acceptedRevision());
    }
    Current snapshot(Access access) {
        var group=currentGroup(access);var latest=mapper.latest(access.tenant(),access.integration(),access.group());
        return new Current(Long.toString(access.group()),group.revision(),group.latestTriggerSequence(),states(access).stream().map(value->new StateView(run(value.targetRef()),value.paused()?Desired.PAUSED.wire:Desired.RUNNING.wire,List.of(value.scope()),value.revision())).toList(),
                boundaries(access).stream().map(value->new BoundaryView(value.boundaryRef(),run(value.targetRef()),value.throughSequence())).toList(),endpoints(access).stream().map(GroupSenderService::view).toList(),latest==null?null:detail(access,latest,group.revision()));
    }
    private List<Flight> flights(Access access,String target) {
        var result=new LinkedHashMap<String,Flight>();
        for(var flight:mapper.runtimeFlights(access.tenant(),access.integration(),access.group(),target))result.putIfAbsent(flight.deliveryId(),flight);
        // 各代报告来自同一共享ledger；新代已确认的完整空集合也会覆盖旧代当前视图，原退役证据仍保留。
        var reports=new TreeMap<String,GroupControlMapper.EndpointRow>();
        for(var endpoint:endpoints(access)) {
            if(endpoint.lastConfirmedAt()==null&&endpoint.retirementReceiptRef()==null)continue;
            reports.merge(endpoint.endpointId(),endpoint,(before,current)->current.generation()>before.generation()?current:before);
        }
        for(var endpoint:reports.values())for(var flight:decode(endpoint.inFlightJson(),new TypeReference<List<Flight>>(){}))result.putIfAbsent(flight.deliveryId(),flight);
        return List.copyOf(result.values());
    }
    private void saveCommand(Access access,Command command,Receipt receipt,Long revision,String intent) {
        mapper.commandReceipt(new GroupControlMapper.CommandRow(access.tenant(),access.integration(),access.group(),command.commandId(),target(command.runId()),receipt.fingerprint(),command.expectedRevision(),revision,command.desiredState(),write(command.scopes()),access.principal(),receipt.resultKind(),receipt.resultRef(),receiptJson(receipt),intent));
        for(String scope:command.scopes())mapper.commandScope(new GroupControlMapper.CommandScope(access.tenant(),access.integration(),access.group(),command.commandId(),scope));
        record(access,"command",command.commandId(),receipt.controlRevision());
    }
    <T>T prior(Access access,Operation operation,String key,String fingerprint,Class<T> type) {
        var prior=mapper.request(access.tenant(),access.integration(),access.group(),access.principal(),operation.name(),text(key,256));
        if(prior==null)return null;sameFingerprint(prior.fingerprint(),fingerprint);return decode(prior.responseJson(),type);
    }
    void saveRequest(Access access,Operation operation,String key,String fingerprint,Object result) {
        mapper.requestReceipt(new GroupControlMapper.RequestRow(access.tenant(),access.integration(),access.group(),access.principal(),operation.name(),text(key,256),fingerprint,receiptJson(result)));
    }
    void record(Access access,String action,String object,long revision){audit.record(Long.toString(access.tenant()),"service_principal:"+access.principal(),"group.control."+action,"group_binding",Long.toString(access.group()),"群控制变更",Map.of("command_ref",object,"revision",revision));}
    /** 跨语言指纹固定为排序字段JSON，包含null run_id与字典序scope，UTF-8紧凑编码。 */
    String commandFingerprint(Command command) {
        var value=new TreeMap<String,Object>();value.put("command_id",command.commandId());value.put("desired_state",command.desiredState());value.put("expected_revision",command.expectedRevision());value.put("group_binding_id",command.groupBindingId());value.put("run_id",command.runId());value.put("scopes",command.scopes().stream().sorted().toList());return fingerprint(value);
    }
    private Command normalize(Command command) {
        if(command==null)throw bad("控制命令缺失");text(command.commandId(),128);if(INITIAL_COMMAND.equals(command.commandId()))throw bad("保留命令身份不可使用");id(command.groupBindingId());if(command.runId()!=null)text(command.runId(),128);requireRevision(command.expectedRevision());Desired.parse(command.desiredState());
        if(command.scopes()==null||command.scopes().isEmpty()||command.scopes().size()>Scope.values().length)throw bad("控制范围缺失或重复");
        command.scopes().forEach(Scope::parse);var scopes=command.scopes().stream().distinct().sorted().toList();if(scopes.size()!=command.scopes().size())throw bad("控制范围不可重复");
        return new Command(command.commandId(),command.groupBindingId(),command.runId(),command.expectedRevision(),command.desiredState(),scopes);
    }
    String fingerprint(Object value){return hash(write(value));}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}}
    String write(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException failure){throw new IllegalStateException("group_control_json_invalid",failure);}}
    private String receiptJson(Object value){String encoded=write(value);if(encoded.getBytes(StandardCharsets.UTF_8).length>MAX_RECEIPT_BYTES)throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"VALIDATION_ERROR","控制原回执超过协议容量，不能截断后受理",false);return encoded;}
    <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(JsonProcessingException failure){throw new IllegalStateException("group_control_receipt_invalid",failure);}}
    <T>T decode(String value,TypeReference<T> type){try{return json.readValue(value,type);}catch(JsonProcessingException failure){throw new IllegalStateException("group_control_receipt_invalid",failure);}}
    List<String> strings(String value){return decode(value,new TypeReference<List<String>>(){});}
    static void sameFingerprint(String original,String current){if(!Objects.equals(original,current))throw conflict("同一命令身份对应不同内容");}
    static void requireRevision(Long revision){if(revision==null||revision<0)throw bad("expected_revision必须显式提供非负整数");}
    static long id(String value){if(value==null||!value.matches("[1-9][0-9]{0,18}"))throw bad("身份必须为正十进制字符串");try{return Long.parseLong(value);}catch(NumberFormatException failure){throw bad("身份超出范围");}}
    static String text(String value,int max){if(value==null||value.isBlank()||value.codePointCount(0,value.length())>max||value.chars().anyMatch(Character::isISOControl))throw bad("控制引用格式非法");return value;}
    static String target(String run){return run==null?GROUP_TARGET:run;}
    static String run(String target){return GROUP_TARGET.equals(target)?null:target;}
    private static String resultRef(String command){return "group-control-result:"+command;}
    static ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message,false);}
    static ApiException conflict(String message){return new ApiException(HttpStatus.CONFLICT,"CONFLICT",message,false);}
    static ApiException denied(String message){return new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN",message,false);}
    /** 包内可信执行域；binding仅公开认证入口持有，Runtime/trigger调用复用原已认证事务。 */
    record Access(long tenant,String integration,long group,String principal,GroupBindingMapper.Row binding) { }
}
