package com.skillforge.institution.service;

import com.skillforge.core.chat.application.HumanHandoffTicketService;
import com.skillforge.core.chat.dto.handoff.CreateHandoffTicketRequest;
import com.skillforge.core.chat.dto.handoff.HandoffActionResponse;
import com.skillforge.core.chat.dto.handoff.ResolveTicketRequest;
import com.skillforge.core.chat.persistence.HumanHandoffTicketDO;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.foundation.persistence.TenantMemberDO;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.chat.enums.HandoffTicketStatus;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import com.skillforge.core.foundation.access.FoundationAccess;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** 工单归属、本人写权与状态修订的唯一应用边界；不直接修改 Runtime 状态。 */
@Service
public class HandoffApplicationService {
    private final HumanHandoffTicketService tickets;
    private final SessionService sessions;
    private final TenantMemberMapper members;
    private final com.skillforge.institution.runtime.AgentRuntimeInboundGateway runtime;
    private final FoundationAccess access;
    private final HandoffCreateMapper creates;
    public HandoffApplicationService(HumanHandoffTicketService tickets, SessionService sessions, TenantMemberMapper members,
            com.skillforge.institution.runtime.AgentRuntimeInboundGateway runtime, FoundationAccess access, HandoffCreateMapper creates) {
        this.tickets=tickets; this.sessions=sessions; this.members=members; this.runtime=runtime; this.access=access; this.creates=creates;
    }
    public List<HumanHandoffTicketDO> list(long tenant, String status) {
        access.require("foundation.handoff.read"); requireSubject(tenant, null);
        var query=new LambdaQueryWrapper<HumanHandoffTicketDO>().eq(HumanHandoffTicketDO::getTenantId,tenant)
                .orderByDesc(HumanHandoffTicketDO::getCreatedAt);
        var visible=access.visibleSessionIds(access.currentSubject(),"read");
        if(visible!=null&&visible.isEmpty())return List.of();
        if(visible!=null)query.in(HumanHandoffTicketDO::getSessionId,visible);
        if(status!=null&&!status.isBlank())query.eq(HumanHandoffTicketDO::getStatus,status);
        return tickets.list(query);
    }
    public HumanHandoffTicketDO require(long tenant, String id) {
        access.require("foundation.handoff.read"); requireSubject(tenant, null);
        var ticket=find(tenant,id);
        sessions.require(Long.toString(tenant),ticket.getSessionId());
        return ticket;
    }
    /** 写命令只按当前写动作与会话写权受理，不通过只读入口借用权限。 */
    public HumanHandoffTicketDO requireForWrite(long tenant,String id) {
        access.require("foundation.handoff.update"); requireSubject(tenant, null);
        var ticket=find(tenant,id);
        sessions.requireWritable(Long.toString(tenant),ticket.getSessionId());
        return ticket;
    }
    /** 仅供已通过当前动作授权的应用方法读取本租户事实。 */
    private HumanHandoffTicketDO find(long tenant,String id) {
        var ticket=tickets.getOne(new LambdaQueryWrapper<HumanHandoffTicketDO>()
                .eq(HumanHandoffTicketDO::getTenantId,tenant).eq(HumanHandoffTicketDO::getTicketId,id));
        if(ticket==null)throw error(HttpStatus.NOT_FOUND,"工单不存在");
        return ticket;
    }
    /** 坐席身份来自认证，不接受客户端 role/operator；管理员可处理同租户他人工单。 */
    public TenantMemberDO requireWriter(long tenant,long member,HumanHandoffTicketDO ticket,boolean requireAssignee) {
        access.require("foundation.handoff.update"); requireSubject(tenant, member);
        var actor=members.selectById(member);
        if(actor==null||!Objects.equals(actor.getTenantId(),tenant)||!"active".equals(actor.getStatus())
                ||!Objects.equals(ticket.getTenantId(),tenant))throw error(HttpStatus.FORBIDDEN,"当前坐席无工单写权限");
        if(requireAssignee&&!access.allowed("foundation.handoff.manage")&&!Objects.equals(ticket.getAssignedTo(),member))
            throw error(HttpStatus.FORBIDDEN,"仅接单坐席或管理员可处理此工单");
        sessions.requireWritable(Long.toString(tenant),ticket.getSessionId());
        return actor;
    }
    /** 详情正文使用当前会话读权，读取不要求坐席写权限。 */
    public SessionView sourceSessionForRead(long tenant,String id) {
        if(id==null||id.isBlank()||id.startsWith("delegation:"))throw error(HttpStatus.UNPROCESSABLE_ENTITY,"必须选择产品会话");
        return sessions.require(Long.toString(tenant),id);
    }
    /** 只接受真正产品会话；终端用户必须取自可信 Session，不能由工单请求指定。 */
    public SessionView sourceSession(long tenant,String id) {
        if(id==null||id.isBlank()||id.startsWith("delegation:"))throw error(HttpStatus.UNPROCESSABLE_ENTITY,"必须选择产品会话");
        var session=sessions.requireWritable(Long.toString(tenant),id);
        if(session.getEndUserId()==null||session.getEndUserId()<=0)throw error(HttpStatus.UNPROCESSABLE_ENTITY,"会话缺少可信终端用户");
        if(session.getMetadata()!=null && (session.getMetadata().containsKey("closed_at")
                || session.getMetadata().containsKey("open_platform_closed_at")))
            throw error(HttpStatus.CONFLICT,"会话已关闭");
        return session;
    }
    /** 恢复只读原归属，不绕过新消息的生命周期检查；删除后没有可恢复产品 Session。 */
    public SessionView sourceSessionForRecovery(long tenant,String id) {
        if(id==null||id.isBlank()||id.startsWith("delegation:"))return null;
        try {
            return sessions.requireForSystem(Long.toString(tenant),id);
        } catch (ApiException unavailable) {
            if (unavailable.getStatus() == HttpStatus.NOT_FOUND) return null;
            throw unavailable;
        }
    }
    @Transactional
    public CreateReceipt create(long tenant,CreateHandoffTicketRequest request) {
        access.require("foundation.handoff.create"); requireSubject(tenant, null);
        String commandId=commandId(request.commandId());
        long member=Long.parseLong(access.currentSubject().memberId());
        if(request.reason()!=null&&request.reason().length()>32)throw error(HttpStatus.UNPROCESSABLE_ENTITY,"转人工原因不能超过32字");
        if(request.summary()!=null&&request.summary().length()>2000)throw error(HttpStatus.UNPROCESSABLE_ENTITY,"工单摘要不能超过2000字");
        var session=sourceSession(tenant,request.sessionId());
        String executionId=request.executionId()==null||request.executionId().isBlank()?null:request.executionId();
        if(executionId!=null)runtime.requireWorkflowExecution(session,executionId);
        var ticket=new HumanHandoffTicketDO();
        ticket.setId(SnowflakeIds.nextId());ticket.setTicketId(Long.toString(SnowflakeIds.nextId()));ticket.setTenantId(tenant);
        ticket.setEndUserId(session.getEndUserId());ticket.setSessionId(session.getSessionId());
        ticket.setExecutionId(executionId);
        ticket.setReason(request.reason()==null||request.reason().isBlank()?"user_request":request.reason());
        ticket.setSummary(request.summary());ticket.setPriority(request.priority()==null?0:request.priority());
        ticket.setStatus("pending");ticket.setContext(Map.of());ticket.setRevision(1L);ticket.setCreatedAt(Instant.now());ticket.setUpdatedAt(Instant.now());
        ticket.setCreateCommandId(commandId);ticket.setCreatedBy(member);
        ticket.setCreateFingerprint(createFingerprint(tenant,member,request,commandId));
        boolean created=creates.insert(ticket)==1;
        if(!created) {
            var original=findCreate(tenant,commandId);
            if(original==null || !Objects.equals(original.getCreatedBy(),member)
                    || !Objects.equals(original.getCreateFingerprint(),ticket.getCreateFingerprint()))
                throw error(HttpStatus.CONFLICT,"创建命令已用于其他请求");
            ticket=original;
        }
        return new CreateReceipt(ticket.getTicketId(),"pending",ticket.getSessionId(),commandId,created);
    }
    /** 只读取原创建事实，不授予创建或修改工单的权利。 */
    @Transactional(readOnly=true)
    public CreateReceipt createReceipt(long tenant,String id) {
        access.require("foundation.handoff.read"); requireSubject(tenant,null);
        String command=commandId(id);
        var ticket=findCreate(tenant,command);
        if(ticket==null || !Objects.equals(String.valueOf(ticket.getCreatedBy()),access.currentSubject().memberId()))
            throw error(HttpStatus.NOT_FOUND,"创建回执不存在");
        sessions.require(Long.toString(tenant),ticket.getSessionId());
        return new CreateReceipt(ticket.getTicketId(),"pending",ticket.getSessionId(),command,false);
    }
    private HumanHandoffTicketDO findCreate(long tenant,String id) {
        return tickets.getOne(new LambdaQueryWrapper<HumanHandoffTicketDO>()
                .eq(HumanHandoffTicketDO::getTenantId,tenant).eq(HumanHandoffTicketDO::getCreateCommandId,id));
    }
    private String commandId(String value) {
        try {
            if(value==null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
            return value;
        } catch(IllegalArgumentException invalid) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY,"创建命令必须是标准 UUID");
        }
    }
    private String createFingerprint(long tenant,long member,CreateHandoffTicketRequest request,String id) {
        try {
            String body=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Arrays.asList(
                    tenant,member,id,request.sessionId(),request.executionId(),request.reason(),request.summary(),request.priority()));
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException|com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException("handoff_create_fingerprint_failed",impossible);
        }
    }
    public record CreateReceipt(String id,String status,String sessionId,String commandId,
            @com.fasterxml.jackson.annotation.JsonIgnore boolean created) { }
    @Transactional
    public HandoffActionResponse accept(long tenant,String id,long member,long revision) {
        var ticket=requireForWrite(tenant,id);requireWriter(tenant,member,ticket,false);
        boolean changed=tickets.update(cas(tenant,id,revision,"pending").set(HumanHandoffTicketDO::getStatus,"assigned")
                .set(HumanHandoffTicketDO::getAssignedTo,member).set(HumanHandoffTicketDO::getAssignedAt,Instant.now()));
        requireChanged(changed);return action(id,"assigned");
    }
    @Transactional
    public HandoffActionResponse resolve(long tenant,String id,long member,long revision,ResolveTicketRequest request) {
        var ticket=requireForWrite(tenant,id);var actor=requireWriter(tenant,member,ticket,true);
        String note=request==null?null:request.resolutionNote();
        if(note!=null&&note.length()>2000)throw error(HttpStatus.UNPROCESSABLE_ENTITY,"解决说明不能超过2000字");
        var update=cas(tenant,id,revision,"assigned").set(HumanHandoffTicketDO::getStatus,"resolved")
                .set(HumanHandoffTicketDO::getResolutionNote,note).set(HumanHandoffTicketDO::getResolvedAt,Instant.now());
        if(!access.allowed("foundation.handoff.manage"))update.eq(HumanHandoffTicketDO::getAssignedTo,member);
        requireChanged(tickets.update(update));return action(id,"resolved");
    }
    public boolean reserveReply(long tenant,String id,long member,long revision) {
        var ticket=requireForWrite(tenant,id);var actor=requireWriter(tenant,member,ticket,true);
        var update=cas(tenant,id,revision,"assigned");
        if(!access.allowed("foundation.handoff.manage"))update.eq(HumanHandoffTicketDO::getAssignedTo,member);
        return tickets.update(update);
    }
    private LambdaUpdateWrapper<HumanHandoffTicketDO> cas(long tenant,String id,long revision,String status) {
        if(revision<=0)throw error(HttpStatus.BAD_REQUEST,"工单修订无效");
        return new LambdaUpdateWrapper<HumanHandoffTicketDO>().eq(HumanHandoffTicketDO::getTenantId,tenant)
                .eq(HumanHandoffTicketDO::getTicketId,id).eq(HumanHandoffTicketDO::getStatus,status)
                .eq(HumanHandoffTicketDO::getRevision,revision).set(HumanHandoffTicketDO::getRevision,revision+1)
                .set(HumanHandoffTicketDO::getUpdatedAt,Instant.now());
    }
    private void requireChanged(boolean changed){if(!changed)throw error(HttpStatus.CONFLICT,"工单状态或修订已变化，请刷新后重试");}
    /** 写入目标与操作者必须来自当前机构身份，不接受内部调用者伪造member参数。 */
    private void requireSubject(long tenant, Long member) {
        FoundationAccess.Subject subject=access.currentSubject();
        if(subject.platform() || !Long.toString(tenant).equals(subject.tenantId())
                || member!=null&&!Long.toString(member).equals(subject.memberId()))
            throw error(HttpStatus.FORBIDDEN,"工单操作者归属不匹配");
    }
    private HandoffActionResponse action(String id,String status){return new HandoffActionResponse(id,HandoffTicketStatus.fromCode(status));}
    private ApiException error(HttpStatus status,String message){return new ApiException(status,message);}
}
