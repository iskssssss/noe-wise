package com.skillforge.institution.group;

import static com.skillforge.institution.group.GroupContracts.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.core.chat.session.SessionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 群生命周期、对象CAS及外部来源投影；不创建Run或业务消息。 */
@Service
public class GroupBindingService {
    private final GroupBindingMapper mapper;
    private final InstitutionIntegrationService integrations;
    private final EndUserService endUsers;
    private final SessionService sessions;
    private final GroupAgentEligibility agents;
    private final AuditService audit;
    private final ObjectMapper json;
    public GroupBindingService(GroupBindingMapper mapper,InstitutionIntegrationService integrations,EndUserService endUsers,
            SessionService sessions,GroupAgentEligibility agents,AuditService audit,ObjectMapper json) {
        this.mapper=mapper;this.integrations=integrations;this.endUsers=endUsers;this.sessions=sessions;
        this.agents=agents;this.audit=audit;this.json=json;
    }
    /** 原键严格读原响应；新键定位同生命周期时读取当前事实，closed绝不复活。 */
    @Transactional(rollbackFor=Exception.class)
    public GroupView ensure(Ensure command,String requestKey) {
        var actor=actor("groups.write");if(command==null)throw bad("群绑定请求不能为空");
        var normalized=new Ensure(text(command.namespace(),"namespace",128),text(command.externalGroupRef(),"external_group_ref",256),
                text(command.lifecycleRef(),"lifecycle_ref",128),text(command.businessScopeId(),"business_scope_id",64));
        positiveId(normalized.businessScopeId(),"business_scope_id");
        String key=key(requestKey),fp=fingerprint(normalized),operation="ensure",object="new";
        lockCommand(actor,operation,object,key);
        var prior=prior(actor,operation,object,key,fp,GroupView.class);if(prior!=null)return prior;
        mapper.lockKey("group.external:"+fingerprint(List.of(actor.tenant(),actor.integration(),normalized.namespace(),normalized.externalGroupRef())));
        var existing=mapper.lifecycle(actor.tenant(),actor.integration(),normalized.namespace(),normalized.externalGroupRef(),normalized.lifecycleRef());
        if(existing!=null) {
            if(!existing.businessScopeId().equals(normalized.businessScopeId()))throw conflict("群生命周期已绑定不同业务范围");
            var result=view(existing);save(actor,operation,object,key,fp,existing.groupBindingId(),null,null,null,result);return result;
        }
        if(mapper.activeExternal(actor.tenant(),actor.integration(),normalized.namespace(),normalized.externalGroupRef())!=null)
            throw conflict("外部群仍有活跃生命周期，须先明确关闭");
        integrations.requireBusinessScopeById(actor.tenantText(),actor.integration(),normalized.businessScopeId());
        long id=SnowflakeIds.nextId();var session=sessions.createGroup(actor.tenantText(),actor.integration(),normalized.businessScopeId(),Long.toString(id));
        if(session==null||session.getSessionId()==null)throw new IllegalStateException("group_session_creation_missing");
        var row=new GroupBindingMapper.Row(id,actor.tenant(),actor.integration(),normalized.namespace(),normalized.externalGroupRef(),normalized.lifecycleRef(),normalized.businessScopeId(),session.getSessionId(),"active",1);
        if(mapper.insert(row)!=1)throw new IllegalStateException("group_binding_insert_failed");
        var result=view(row);save(actor,operation,object,key,fp,id,null,null,null,result);record(actor,operation,id,1,object,fp);return result;
    }
    /** close可在业务范围停用后执行；只终结群身份，不删除历史。 */
    @Transactional(rollbackFor=Exception.class)
    public GroupView close(Close command,String requestKey) {
        var actor=actor("groups.write");if(command==null)throw bad("关闭请求不能为空");
        long id=positiveId(command.groupBindingId(),"group_binding_id"),expected=revision(command.expectedRevision(),1);
        String operation="close",object=Long.toString(id),key=key(requestKey),fp=fingerprint(new Close(object,expected));
        lockCommand(actor,operation,object,key);
        var prior=prior(actor,operation,object,key,fp,GroupView.class);if(prior!=null)return prior;
        var row=required(mapper.lock(actor.tenant(),actor.integration(),id));
        active(row);if(row.revision()!=expected)throw conflict("群生命周期修订已变化");
        if(mapper.close(actor.tenant(),actor.integration(),id,expected)!=1)throw conflict("群关闭CAS冲突");
        sessions.closeGroup(actor.tenantText(),actor.integration(),object,row.sessionId());
        var result=view(required(mapper.find(actor.tenant(),actor.integration(),id)));
        save(actor,operation,object,key,fp,id,null,null,null,result);record(actor,operation,id,result.revision(),object,fp);return result;
    }
    /** 治理详情只返回元数据，不等于群历史或成员私有资料读取权。 */
    @Transactional(readOnly=true)
    public GroupView detail(String groupBindingId) {
        var actor=actor("groups.read");var row=required(mapper.find(actor.tenant(),actor.integration(),positiveId(groupBindingId,"group_binding_id")));
        return view(row);
    }
    /** 目录只返回当前集成域的治理元数据，已退出成员仍可供历史事实定位。 */
    @Transactional(readOnly=true)
    public MembersPage members(String groupBindingId,String afterId,int limit) {
        var actor=actor("groups.read");long group=positiveId(groupBindingId,"group_binding_id");
        required(mapper.find(actor.tenant(),actor.integration(),group));
        long after=afterId==null?0:positiveId(afterId,"after_id");requirePageLimit(limit);
        var rows=mapper.members(actor.tenant(),actor.integration(),group,after,limit+1);
        boolean more=rows.size()>limit;
        var items=rows.stream().limit(limit).map(GroupBindingService::memberView).toList();
        return new MembersPage(items,more?items.getLast().endUserId().toString():null,more);
    }
    /** 服务 Agent 目录不暴露定义、工具、凭据或私有执行材料。 */
    @Transactional(readOnly=true)
    public AgentsPage agents(String groupBindingId,String afterId,int limit) {
        var actor=actor("groups.read");long group=positiveId(groupBindingId,"group_binding_id");
        required(mapper.find(actor.tenant(),actor.integration(),group));
        long after=afterId==null?0:positiveId(afterId,"after_id");requirePageLimit(limit);
        var rows=mapper.agents(actor.tenant(),actor.integration(),group,after,limit+1);
        boolean more=rows.size()>limit;
        var items=rows.stream().limit(limit).map(GroupBindingService::agentView).toList();
        return new AgentsPage(items,more?items.getLast().agentId().toString():null,more);
    }
    private static void requirePageLimit(int limit) {
        if(limit<1||limit>MAX_DIRECTORY_PAGE_SIZE)throw bad("目录分页数量必须为1至200");
    }
    @Transactional(rollbackFor=Exception.class)
    public MemberView updateMember(UpdateMember command,String requestKey) {
        var actor=actor("groups.members.write");if(command==null)throw bad("成员请求不能为空");
        long group=positiveId(command.groupBindingId(),"group_binding_id"),user=objectId(command.endUserId(),"end_user_id");
        String role=choice(command.role(),Set.of("member","manager"),"role"),status=choice(command.status(),Set.of("active","left"),"status");
        long expected=revision(command.expectedRevision(),0),sourceRevision=revision(command.sourceRevision(),1);String event=text(command.sourceEventId(),"source_event_id",256);
        var normalized=new UpdateMember(Long.toString(group),user,role,status,expected,event,sourceRevision);
        String operation="member.update",object=group+":"+user,key=key(requestKey),fp=fingerprint(normalized);
        String sourceFp=fingerprint(List.of(group,user,role,status,event,sourceRevision));
        lockCommand(actor,operation,object,key);
        var prior=prior(actor,operation,object,key,fp,MemberView.class);if(prior!=null)return prior;
        var binding=required(mapper.share(actor.tenant(),actor.integration(),group));
        mapper.lockKey("group.member:"+actor.tenant()+":"+actor.integration()+":"+object);
        var source=mapper.source(actor.tenant(),actor.integration(),group,operation,object,event,sourceRevision);
        if(source!=null) {
            if(!sourceFp.equals(source.sourceFingerprint()))throw conflict("成员来源事件对应不同内容");
            var result=decode(source.response(),MemberView.class);save(actor,operation,object,key,fp,group,null,null,null,result);return result;
        }
        active(binding);
        var current=mapper.member(actor.tenant(),actor.integration(),group,user);
        checkObject(current==null?0:current.revision(),current==null?0:current.sourceRevision(),expected,sourceRevision);
        boolean joining="active".equals(status);
        if(joining)integrations.requireBusinessScopeById(actor.tenantText(),actor.integration(),binding.businessScopeId());
        endUsers.requireExternal(actor.tenantText(),actor.integration(),user,joining);
        var row=new GroupBindingMapper.Member(actor.tenant(),actor.integration(),group,user,role,status,expected+1,event,sourceRevision,actor.ref());
        int changed=current==null?mapper.insertMember(row):mapper.updateMember(row,expected);
        if(changed!=1)throw conflict("成员对象CAS冲突");
        var result=memberView(row);save(actor,operation,object,key,fp,group,event,sourceRevision,sourceFp,result);record(actor,operation,group,row.revision(),object,fp);return result;
    }
    @Transactional(rollbackFor=Exception.class)
    public AgentView updateAgent(UpdateAgent command,String requestKey) {
        var actor=actor("groups.agents.write");if(command==null)throw bad("服务Agent请求不能为空");
        long group=positiveId(command.groupBindingId(),"group_binding_id"),agent=objectId(command.agentId(),"agent_id");
        String status=choice(command.status(),Set.of("active","removed"),"status"),event=text(command.sourceEventId(),"source_event_id",256);
        long expected=revision(command.expectedRevision(),0),sourceRevision=revision(command.sourceRevision(),1);
        var normalized=new UpdateAgent(Long.toString(group),agent,status,expected,event,sourceRevision);
        String operation="agent.update",object=group+":"+agent,key=key(requestKey),fp=fingerprint(normalized);
        String sourceFp=fingerprint(List.of(group,agent,status,event,sourceRevision));
        lockCommand(actor,operation,object,key);
        var prior=prior(actor,operation,object,key,fp,AgentView.class);if(prior!=null)return prior;
        var binding=required(mapper.share(actor.tenant(),actor.integration(),group));
        mapper.lockKey("group.agent:"+actor.tenant()+":"+actor.integration()+":"+object);
        var source=mapper.source(actor.tenant(),actor.integration(),group,operation,object,event,sourceRevision);
        if(source!=null) {
            if(!sourceFp.equals(source.sourceFingerprint()))throw conflict("Agent来源事件对应不同内容");
            var result=decode(source.response(),AgentView.class);save(actor,operation,object,key,fp,group,null,null,null,result);return result;
        }
        active(binding);
        var current=mapper.agent(actor.tenant(),actor.integration(),group,agent);
        checkObject(current==null?0:current.revision(),current==null?0:current.sourceRevision(),expected,sourceRevision);
        if("active".equals(status)) {
            integrations.requireBusinessScopeById(actor.tenantText(),actor.integration(),binding.businessScopeId());
            agents.requireAvailable(actor.tenantText(),actor.integration(),agent);
        } else agents.requireIdentity(actor.tenantText(),actor.integration(),agent);
        var row=new GroupBindingMapper.Agent(actor.tenant(),actor.integration(),group,agent,status,expected+1,event,sourceRevision,actor.ref());
        int changed=current==null?mapper.insertAgent(row):mapper.updateAgent(row,expected);
        if(changed!=1)throw conflict("Agent对象CAS冲突");
        var result=agentView(row);save(actor,operation,object,key,fp,group,event,sourceRevision,sourceFp,result);record(actor,operation,group,row.revision(),object,fp);return result;
    }
    /** 内部当前资格查询；入口先验证具体用途scope，不从参数创建认证身份。 */
    @Transactional(readOnly=true,noRollbackFor=ApiException.class)
    public GroupView requireActive(String tenant,String integration,String groupBindingId) {
        domain(tenant,integration);var row=required(mapper.find(positiveId(tenant,"tenant_id"),integration,positiveId(groupBindingId,"group_binding_id")));
        active(row);integrations.requireBusinessScopeById(tenant,integration,row.businessScopeId());return view(row);
    }
    @Transactional(readOnly=true,noRollbackFor=ApiException.class)
    public MemberView requireMember(String tenant,String integration,String groupBindingId,long endUserId,boolean manager) {
        requireActive(tenant,integration,groupBindingId);endUsers.requireExternal(tenant,integration,endUserId,true);
        var member=mapper.member(positiveId(tenant,"tenant_id"),integration,positiveId(groupBindingId,"group_binding_id"),endUserId);
        if(member==null||!"active".equals(member.status())||(manager&&!"manager".equals(member.role())))throw denied("当前群成员资格不足");return memberView(member);
    }
    /** 描述对象只需属于本群历史，退出不抹除其事实；不以其身份授予读取者权限。 */
    @Transactional(readOnly=true,noRollbackFor=ApiException.class)
    public MemberView requireHistoricalMember(String tenant,String integration,String groupBindingId,long endUserId) {
        requireActive(tenant,integration,groupBindingId);
        var member=mapper.member(positiveId(tenant,"tenant_id"),integration,positiveId(groupBindingId,"group_binding_id"),endUserId);
        if(member==null)throw denied("memory_group_subject_not_in_group");
        endUsers.requireExternal(tenant,integration,endUserId,false);
        return memberView(member);
    }
    @Transactional(readOnly=true,noRollbackFor=ApiException.class)
    public AgentView requireServiceAgent(String tenant,String integration,String groupBindingId,long agentId) {
        requireActive(tenant,integration,groupBindingId);
        var member=mapper.agent(positiveId(tenant,"tenant_id"),integration,positiveId(groupBindingId,"group_binding_id"),agentId);
        if(member==null||!"active".equals(member.status()))throw denied("Agent当前未获准服务本群");
        agents.requireAvailable(tenant,integration,agentId);return agentView(member);
    }
    /** 后台来源消费只验证当前群与部署资格，已退出的原作者不成为当前请求者。 */
    @Transactional(readOnly=true,noRollbackFor=ApiException.class)
    public GroupView requireActiveForExtraction(String tenant, String groupBindingId, String sessionId, long agentId) {
        if (!Objects.equals(tenant, TenantContext.getTenantId())) throw denied("群查询机构与当前身份不一致");
        var row = required(mapper.extractionGroup(positiveId(tenant,"tenant_id"), positiveId(groupBindingId,"group_binding_id")));
        var current = requireActive(tenant, row.integrationId(), groupBindingId);
        if (!current.sessionId().equals(sessionId)) throw denied("群提取来源会话不匹配");
        requireServiceAgent(tenant, row.integrationId(), groupBindingId, agentId);
        return current;
    }

    private Actor actor(String scope) {
        var identity=ServicePrincipalContext.get();
        if(identity==null||!Objects.equals(identity.tenantId(),TenantContext.getTenantId()))throw denied("群治理须使用当前机构固定执行身份");
        integrations.requirePrincipal(identity,scope);
        return new Actor(positiveId(identity.tenantId(),"tenant_id"),identity.integrationId(),"service_principal:"+identity.principalId());
    }
    private record Actor(long tenant,String integration,String ref){String tenantText(){return Long.toString(tenant);}}
    private void domain(String tenant,String integration) {
        if(!Objects.equals(tenant,TenantContext.getTenantId()))throw denied("群查询机构与当前身份不一致");
        integrations.requireIntegration(tenant,integration);
    }
    private void lockCommand(Actor actor,String operation,String object,String key) {
        mapper.lockKey("group.command:"+fingerprint(List.of(actor.tenant(),actor.integration(),actor.ref(),operation,object,key)));
    }
    private <T>T prior(Actor actor,String operation,String object,String key,String fingerprint,Class<T> type) {
        var receipt=mapper.command(actor.tenant(),actor.integration(),actor.ref(),operation,object,key);if(receipt==null)return null;
        if(!fingerprint.equals(receipt.fingerprint()))throw conflict("群命令幂等键对应不同内容");return decode(receipt.response(),type);
    }
    private void save(Actor actor,String operation,String object,String key,String fp,long group,String event,Long sourceRevision,String sourceFp,Object result) {
        if(mapper.receipt(new GroupBindingMapper.Receipt(actor.tenant(),actor.integration(),actor.ref(),operation,object,key,fp,group,event,sourceRevision,sourceFp,write(result)))!=1)
            throw new IllegalStateException("group_command_receipt_failed");
    }
    private void record(Actor actor,String operation,long group,long revision,String object,String fingerprint) {
        audit.record(actor.tenantText(),actor.ref(),"group."+operation,"group_binding",Long.toString(group),"群治理变更",Map.of("revision",revision,"operation",operation,"object_scope",object,"request_fingerprint",fingerprint));
    }
    private static GroupView view(GroupBindingMapper.Row r){String id=r.groupBindingId().toString();return new GroupView(id,r.sessionId(),r.namespace(),r.externalGroupRef(),r.lifecycleRef(),r.businessScopeId(),r.status(),r.revision(),new Subject("group",id));}
    private static MemberView memberView(GroupBindingMapper.Member r){return new MemberView(r.groupBindingId().toString(),r.endUserId(),r.role(),r.status(),r.revision(),r.sourceEventId(),r.sourceRevision());}
    private static AgentView agentView(GroupBindingMapper.Agent r){return new AgentView(r.groupBindingId().toString(),r.agentId(),r.status(),r.revision(),r.sourceEventId(),r.sourceRevision());}
    private static GroupBindingMapper.Row required(GroupBindingMapper.Row row){if(row==null)throw denied("群不属于当前执行域或不存在");return row;}
    private static void active(GroupBindingMapper.Row row){if(!"active".equals(row.status()))throw conflict("群生命周期已关闭");}
    private static void checkObject(long current,long lastSource,long expected,long source) {
        if(current!=expected)throw conflict("群对象修订已变化");if(source<=lastSource)throw conflict("群对象来源修订已过时");
        if(expected==Long.MAX_VALUE)throw conflict("群对象修订已达上限");
    }
    private static long revision(Long value,long minimum){if(value==null||value<minimum)throw bad("revision缺失或超出允许范围");return value;}
    private static long objectId(Long value,String field){if(value==null||value<1)throw bad(field+"须为正整数身份");return value;}
    private static long positiveId(String value,String field){if(value==null||!value.matches("[1-9][0-9]{0,18}"))throw bad(field+"无效");try{return Long.parseLong(value);}catch(NumberFormatException failure){throw bad(field+"超出身份范围");}}
    private static String choice(String value,Set<String> allowed,String field){if(value==null||!allowed.contains(value))throw bad(field+"非法");return value;}
    private static String text(String value,String field,int max){if(value==null||value.isBlank()||value.length()>max||value.chars().anyMatch(Character::isISOControl))throw bad(field+"格式非法");return value;}
    private static String key(String value){return text(value,"Idempotency-Key",256);}
    private String fingerprint(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(value).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException failure){throw new IllegalStateException("group_json_encoding_failed",failure);}}
    private <T>T decode(String value,Class<T> type){try{var result=json.readValue(value,type);if(result==null)throw new IllegalStateException("group_receipt_invalid");return result;}catch(JsonProcessingException failure){throw new IllegalStateException("group_receipt_invalid",failure);}}
    private static ApiException bad(String text){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",text,false);}
    private static ApiException conflict(String text){return new ApiException(HttpStatus.CONFLICT,"CONFLICT",text,false);}
    private static ApiException denied(String text){return new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN",text,false);}
}
