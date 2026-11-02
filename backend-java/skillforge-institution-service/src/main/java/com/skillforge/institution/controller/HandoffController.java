package com.skillforge.institution.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.chat.dto.handoff.CreateHandoffTicketRequest;
import com.skillforge.core.chat.dto.handoff.ResolveTicketRequest;
import com.skillforge.core.chat.dto.handoff.HandoffActionResponse;
import com.skillforge.core.chat.dto.handoff.HandoffTicketListResponse;
import com.skillforge.core.chat.dto.handoff.HandoffTicketResponse;
import com.skillforge.core.chat.enums.HandoffTicketStatus;
import com.skillforge.core.chat.persistence.HumanHandoffTicketDO;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.service.HandoffApplicationService;
import com.skillforge.institution.ws.AgentSessionRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 转人工工单 REST 接口。
 *
 * <p>列表是工单展示的唯一权威来源。WebSocket 只在服务端写入成功后发送变更通知，
 * 客户端收到通知后重新读取列表。
 */
@RestController
@RequestMapping("/api/v1/handoff")
public class HandoffController {

    private final HandoffApplicationService handoffs;
    private final AgentSessionRegistry sessions;
    private final com.skillforge.institution.service.HandoffReplyService replies;
    private final com.skillforge.core.chat.ChatSessionService chat;

    public HandoffController(HandoffApplicationService handoffs, AgentSessionRegistry sessions,
            com.skillforge.institution.service.HandoffReplyService replies, com.skillforge.core.chat.ChatSessionService chat) {
        this.handoffs = handoffs;
        this.sessions = sessions;
        this.replies = replies;
        this.chat = chat;
    }

    /** 审计员可读取当前租户工单，但不能执行状态写入。 */
    @RequirePermission("foundation.handoff.read")
    @GetMapping("/tickets")
    public ApiResponse<HandoffTicketListResponse> list(@RequestParam(defaultValue = "") String status) {
        long tenantId = tenantId();
        List<HumanHandoffTicketDO> tickets = handoffs.list(tenantId, status);
        return ApiResponses.wrap(new HandoffTicketListResponse(
                tickets.size(), tickets.stream().map(this::ticketItem).toList()));
    }

    /** 只有管理员和开发者可创建工单。 */
    @RequirePermission("foundation.handoff.create")
    @PostMapping("/tickets")
    public ApiResponse<HandoffApplicationService.CreateReceipt> create(@RequestBody CreateHandoffTicketRequest request) {
        String tenantId = TenantIds.requireTenantId();
        var result = handoffs.create(Long.parseLong(tenantId), request);
        if(result.created()) sessions.notifyTicketChanged(tenantId, result.id());
        return ApiResponses.wrap(result);
    }

    @RequirePermission("foundation.handoff.read")
    @GetMapping("/tickets/create-receipt")
    public ApiResponse<HandoffApplicationService.CreateReceipt> createReceipt(@RequestParam("command_id") String id) {
        return ApiResponses.wrap(handoffs.createReceipt(tenantId(),id));
    }

    /** 只有管理员和开发者可执行 pending -> assigned。 */
    @RequirePermission("foundation.handoff.update")
    @PostMapping("/tickets/accept")
    public ApiResponse<HandoffActionResponse> acceptEndpoint(
            @Valid @RequestBody AcceptCommand command) {
        return ApiResponses.wrap(accept(command.ticketId(), command.expectedRevision()));
    }

    public HandoffActionResponse accept(String ticketId, long revision) {
        String tenantId = TenantIds.requireTenantId();
        HandoffActionResponse result = handoffs.accept(
                Long.parseLong(tenantId), ticketId, Long.parseLong(StpUtil.getLoginIdAsString()), revision);
        sessions.notifyTicketChanged(tenantId, result.id());
        return result;
    }

    /** 只有管理员和开发者可执行 assigned -> resolved。 */
    @RequirePermission("foundation.handoff.update")
    @PostMapping("/tickets/resolve")
    public ApiResponse<HandoffActionResponse> resolveEndpoint(
            @Valid @RequestBody ResolveCommand command) {
        return ApiResponses.wrap(resolve(command.ticketId(), command.expectedRevision(), command.payload()));
    }

    public HandoffActionResponse resolve(String ticketId, long revision, ResolveTicketRequest request) {
        String tenantId = TenantIds.requireTenantId();
        HandoffActionResponse result = handoffs.resolve(Long.parseLong(tenantId), ticketId, Long.parseLong(StpUtil.getLoginIdAsString()), revision, request);
        sessions.notifyTicketChanged(tenantId, result.id());
        return result;
    }

    /** 产品公开对话的只读脱敏上下文；不返回系统消息、私有 checkpoint 或内部子会话。 */
    @RequirePermission("foundation.handoff.read")
    @GetMapping("/tickets/detail")
    public ApiResponse<Detail> detail(@RequestParam("ticket_id") String id,
            @RequestParam(value="before_message_id",required=false) String before) {
        long tenant=tenantId();var ticket=handoffs.require(tenant,id);
        java.util.List<ContextMessage> context=java.util.List.of();String next=null;boolean hasMore=false;
        if(ticket.getSessionId()!=null&&!ticket.getSessionId().isBlank()) {
            var source=handoffs.sourceSessionForRead(tenant,ticket.getSessionId());
            if(!java.util.Objects.equals(source.getEndUserId(),ticket.getEndUserId()))
                throw new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.CONFLICT,"工单会话归属已变化");
            var history=chat.history(Long.toString(tenant),source.getSessionId(),30,before);
            context=history.messages().stream().filter(m->java.util.Set.of("user","assistant").contains(m.role()))
                    .map(m->new ContextMessage(m.messageId(),m.role(),redact(m.content()),m.createdAt(),
                            m.metadata()!=null&&"human_operator".equals(m.metadata().get("author_type"))
                                    ? String.valueOf(m.metadata().get("author_display_name")):null)).toList();
            next=history.nextBeforeMessageId();hasMore=history.hasMore();
        }
        return ApiResponses.wrap(new Detail(ticketItem(ticket),context,hasMore,next,replies.list(tenant,id)));
    }
    @RequirePermission("foundation.handoff.update")
    @PostMapping("/tickets/replies")
    public ApiResponse<com.skillforge.institution.service.HandoffReplyService.Reply> reply(@Valid @RequestBody ReplyCommand command) {
        long tenant=tenantId();var result=replies.accept(tenant,Long.parseLong(StpUtil.getLoginIdAsString()),
                command.ticketId(),command.expectedRevision(),command.commandId(),command.content());
        sessions.notifyTicketChanged(Long.toString(tenant),command.ticketId());
        return ApiResponses.wrap(result);
    }
    @RequirePermission("foundation.handoff.read")
    @GetMapping("/tickets/reply-receipt")
    public ApiResponse<com.skillforge.institution.service.HandoffReplyService.Reply> replyReceipt(
            @RequestParam("ticket_id") String ticketId, @RequestParam("command_id") String commandId) {
        return ApiResponses.wrap(replies.receipt(tenantId(),ticketId,commandId));
    }
    static String redact(String value) {
        if(value==null)return "";
        return value.replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}","[邮箱已隐藏]")
                .replaceAll("(?<![0-9])1[3-9][0-9]{9}(?![0-9])","[电话已隐藏]")
                .replaceAll("(?i)(bearer\\s+|(?:api[_-]?key|password|token|secret)\\s*[:=]\\s*)[^\\s,;]+","$1[凭证已隐藏]");
    }
    public record ContextMessage(String messageId,String role,String content,java.time.Instant createdAt,String humanAuthor) { }
    public record Detail(HandoffTicketResponse ticket,java.util.List<ContextMessage> messages,boolean hasMore,
                         String nextBeforeMessageId,java.util.List<com.skillforge.institution.service.HandoffReplyService.Reply> replies) { }
    public record ReplyCommand(@JsonProperty("ticket_id") @jakarta.validation.constraints.NotBlank String ticketId,
            @JsonProperty("expected_revision") @jakarta.validation.constraints.Positive long expectedRevision,
            @JsonProperty("command_id") @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=200) String commandId,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=8192) String content) { }

    private long tenantId() {
        return Long.parseLong(TenantIds.requireTenantId());
    }

    private HandoffTicketResponse ticketItem(HumanHandoffTicketDO ticket) {
        return new HandoffTicketResponse(
                ticket.getTicketId(),
                ticket.getEndUserId() == null ? "" : String.valueOf(ticket.getEndUserId()),
                ticket.getReason(),
                ticket.getSummary(),
                ticket.getPriority(),
                HandoffTicketStatus.fromCode(ticket.getStatus()),
                ticket.getSessionId(),
                ticket.getCreatedAt(), ticket.getAssignedTo()==null?null:ticket.getAssignedTo().toString(),
                ticket.getRevision(), ticket.getResolutionNote());
    }

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record AcceptCommand(@JsonProperty("ticket_id") @NotNull String ticketId,
            @JsonProperty("expected_revision") @jakarta.validation.constraints.Positive long expectedRevision) {
    }

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record ResolveCommand(
            @JsonProperty("ticket_id") @NotNull String ticketId,
            @JsonProperty("expected_revision") @jakarta.validation.constraints.Positive long expectedRevision,
            @JsonProperty("payload") @Valid ResolveTicketRequest payload) {
    }
}
