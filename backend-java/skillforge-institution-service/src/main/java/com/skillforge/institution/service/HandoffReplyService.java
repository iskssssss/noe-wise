package com.skillforge.institution.service;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.institution.runtime.AgentRuntimeInboundGateway;
import com.skillforge.runtime.session.spi.AgentRuntimeCollaborationPort.HumanAssistance;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 持久回复受理与可恢复投递；正文仅通过受鉴权 Session 控制命令成为权威会话事实。 */
@Service
public class HandoffReplyService {
    private final HandoffReplyMapper mapper;
    private final HandoffApplicationService handoffs;
    private final FoundationAccess access;
    private final AgentRuntimeInboundGateway runtime;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    public HandoffReplyService(HandoffReplyMapper mapper,HandoffApplicationService handoffs,
            AgentRuntimeInboundGateway runtime,FoundationAccess access,org.springframework.transaction.PlatformTransactionManager manager) {
        this.mapper=mapper;this.handoffs=handoffs;this.access=access;this.runtime=runtime;
        this.transactions=new org.springframework.transaction.support.TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    /** 同键同文重放原受理；同键换工单/操作者/正文拒绝。先持久化请求再由 worker 提交。 */
    @Transactional
    public Reply accept(long tenant,long member,String ticketId,long revision,String commandId,String text) {
        if(commandId==null||commandId.isBlank()||commandId.length()>200||text==null||text.isBlank()||text.length()>8192)
            throw error(HttpStatus.UNPROCESSABLE_ENTITY,"人工回复标识或正文无效");
        access.require("foundation.handoff.update");
        var subject=access.currentSubject();
        if(subject.platform()||!Long.toString(tenant).equals(subject.tenantId())||!Long.toString(member).equals(subject.memberId()))
            throw error(HttpStatus.FORBIDDEN,"人工回复主体与当前认证身份不符");
        // 工单行锁串行领取、解决与重复请求，锁后查幂等记录，避免双请求重复推进 revision。
        mapper.lockTicket(tenant,ticketId);
        var ticket=handoffs.requireForWrite(tenant,ticketId);
        var actor=handoffs.requireWriter(tenant,member,ticket,false);
        String fingerprint=fingerprint(tenant,member,ticketId,revision,commandId,text);
        var old=Optional.ofNullable(mapper.find(tenant,commandId));
        if(old.isPresent()) {
            var value=old.orElseThrow();
            if(!value.getRequestFingerprint().equals(fingerprint))
                throw error(HttpStatus.CONFLICT,"人工回复幂等键已用于其他内容");
            return response(value);
        }
        handoffs.requireWriter(tenant,member,ticket,true);
        var session=handoffs.sourceSession(tenant,ticket.getSessionId());
        if(!Objects.equals(ticket.getEndUserId(),session.getEndUserId()))throw error(HttpStatus.CONFLICT,"工单会话归属已变化");
        if(!handoffs.reserveReply(tenant,ticketId,member,revision)) {
            // 工单锁内已确认原命令不存在；旧修订不会再被重新使用。
            if(ticket.getRevision()>=revision)
                throw new ApiException(HttpStatus.CONFLICT,"HANDOFF_REPLY_NOT_ACCEPTED","工单状态或修订已变化，回复未受理",false);
            throw error(HttpStatus.CONFLICT,"工单状态或修订已变化");
        }
        String name=actor.getUsername();
        if(name==null||name.isBlank())name="坐席";
        if(name.length()>120)name=name.substring(0,120);
        var request=new HandoffReplyRequest();request.setTenantId(tenant);request.setCommandId(commandId);request.setTicketId(ticketId);
        request.setSessionId(session.getSessionId());request.setEndUserId(session.getEndUserId());request.setOperatorId(member);
        request.setOperatorName(name);request.setContent(text);request.setRequestFingerprint(fingerprint);
        if(mapper.insert(request)!=1)throw error(HttpStatus.CONFLICT,"人工回复受理冲突");
        return response(Objects.requireNonNull(mapper.find(tenant,commandId)));
    }
    @Transactional(readOnly=true)
    public List<Reply> list(long tenant,String ticketId) {
        handoffs.require(tenant,ticketId);
        return mapper.list(tenant,ticketId).stream().map(HandoffReplyService::response).toList();
    }
    @Transactional(readOnly=true)
    public Reply receipt(long tenant,String ticketId,String commandId) {
        handoffs.require(tenant,ticketId);
        var request=mapper.find(tenant,commandId);
        if(request==null || !Objects.equals(request.getTicketId(),ticketId))
            throw error(HttpStatus.NOT_FOUND,"人工回复回执不存在");
        return response(request);
    }
    @Transactional(readOnly=true)
    public List<String> pending(long tenant) {
        return mapper.pending(tenant);
    }
    /** worker 可重试同一 command；提交响应丢失会读取同一 SessionLog event，不生成替代身份。 */
    public void dispatch(long tenant,String commandId) {
        try {
            transactions.executeWithoutResult(status -> dispatchOnce(tenant,commandId));
        } catch (DispatchRejected rejected) {
            transactions.executeWithoutResult(status -> reconcileRejected(tenant,commandId,rejected.code));
        }
    }
    private void dispatchOnce(long tenant,String commandId) {
        var claimed=mapper.claim(tenant,commandId);
        if(claimed==null||!"accepted".equals(claimed.getStatus()))return;
        var request=response(claimed);
        // 已关闭会话仍允许确认既成事实；只有创建新事实前才检查当前可写状态。
        var source=handoffs.sourceSessionForRecovery(tenant,request.sessionId());
        if(source==null){reject(tenant,commandId,"source_session_deleted");return;}
        if(!Objects.equals(source.getEndUserId(),request.endUserId())) {reject(tenant,commandId,"source_owner_changed");return;}
        var command=new HumanAssistance(commandId,request.ticketId(),Long.toString(request.operatorId()),request.operatorName(),request.content());
        var recovered=runtime.humanReceipt(source,command);
        if(recovered.isPresent()) {
            commit(tenant,commandId,recovered.orElseThrow());runtime.reconcileHuman(source);return;
        }
        // 后台主体只能来自上方claim取得的持久受理事实；每次新副作用前重新检查当前权限。
        try {
            access.withSubject(new FoundationAccess.Subject(false,Long.toString(tenant),Long.toString(request.operatorId())),() -> {
                access.require("foundation.handoff.update");
                dispatchAuthorized(tenant,commandId,request,command);
                return null;
            });
        } catch(ApiException error) {
            if(error.getStatus().is4xxClientError())throw new DispatchRejected("operator_access_revoked",error);
            throw error;
        }
    }
    /** 派发事务回滚后重新锁定原请求，先核对可能已提交的原事实再记录拒绝。 */
    private void reconcileRejected(long tenant,String commandId,String code) {
        var claimed=mapper.claim(tenant,commandId);
        if(claimed==null||!"accepted".equals(claimed.getStatus()))return;
        var request=response(claimed);var source=handoffs.sourceSessionForRecovery(tenant,request.sessionId());
        if(source!=null && Objects.equals(source.getEndUserId(),request.endUserId())) {
            var command=new HumanAssistance(commandId,request.ticketId(),Long.toString(request.operatorId()),request.operatorName(),request.content());
            var recovered=runtime.humanReceipt(source,command);
            if(recovered.isPresent()){commit(tenant,commandId,recovered.orElseThrow());runtime.reconcileHuman(source);return;}
        }
        reject(tenant,commandId,code);
    }
    /** 仅在持久主体实时授权上下文内提交新回复，既成事实恢复在派发前已完成。 */
    private void dispatchAuthorized(long tenant,String commandId,Reply request,HumanAssistance command) {
        var ticket=handoffs.requireForWrite(tenant,request.ticketId());
        handoffs.requireWriter(tenant,request.operatorId(),ticket,true);
        com.skillforge.core.chat.session.SessionView session;
        try { session=handoffs.sourceSession(tenant,request.sessionId()); }
        catch(ApiException error) {
            if(error.getStatus().is4xxClientError())throw new DispatchRejected("source_session_unavailable",error);
            throw error;
        }
        if(!Objects.equals(ticket.getSessionId(),request.sessionId())||!Objects.equals(session.getEndUserId(),request.endUserId())) {
            reject(tenant,commandId,"source_owner_changed");return;
        }
        com.skillforge.runtime.session.spi.AgentRuntimeCollaborationPort.HumanReceipt receipt;
        try { receipt=runtime.humanAssistance(session,command); }
        catch(ApiException error) {
            if(error.getStatus().is4xxClientError())throw new DispatchRejected("runtime_command_rejected",error);
            throw error;
        }
        commit(tenant,commandId,receipt);
        // 通知只投影同一原始事实；事务/进程中断后既有 projection recovery 会再次补齐。
        runtime.reconcileHuman(session);
    }
    private void commit(long tenant,String id,com.skillforge.runtime.session.spi.AgentRuntimeCollaborationPort.HumanReceipt receipt) {
        if(mapper.commit(tenant,id,receipt.sourceEventId(),receipt.sessionSequence())!=1)
            throw new IllegalStateException("handoff_reply_commit_conflict");
    }
    private void reject(long tenant,String id,String code){if(mapper.reject(tenant,id,code)!=1)throw new IllegalStateException("handoff_reply_reject_conflict");}
    static String fingerprint(long tenant,long member,String ticket,long revision,String id,String text) {
        try {
            String body=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(List.of(tenant,member,ticket,revision,id,text));
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }catch(java.security.NoSuchAlgorithmException|com.fasterxml.jackson.core.JsonProcessingException impossible){throw new IllegalStateException("handoff_fingerprint_failed",impossible);}
    }
    private static Reply response(HandoffReplyRequest row) {return new Reply(row.getCommandId(),row.getTicketId(),row.getSessionId(),
            row.getEndUserId(),row.getOperatorId(),row.getOperatorName(),row.getContent(),row.getStatus(),row.getSourceEventId(),row.getSourceSequence(),row.getErrorCode());}
    private ApiException error(HttpStatus status,String message){return new ApiException(status,message);}
    private static final class DispatchRejected extends RuntimeException {
        private final String code;
        private DispatchRejected(String code,ApiException cause){super(cause);this.code=code;}
    }
    public record Reply(String commandId,String ticketId,String sessionId,long endUserId,long operatorId,String operatorName,
                        String content,String status,String sourceEventId,Long sourceSequence,String errorCode) { }
}
