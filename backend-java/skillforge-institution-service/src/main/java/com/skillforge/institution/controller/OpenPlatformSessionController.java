package com.skillforge.institution.controller;

import java.util.Locale;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformActionRequest;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformCallbackRequest;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformCommandRequest;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformCreateSessionRequest;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformMessageRequest;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformInboundReceiptResponse;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformSessionResponse;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.identity.EndUserIdentity;
import com.skillforge.core.chat.inbound.InboundKind;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.institution.runtime.AgentRuntimeExecutionGateway;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tenant Open Platform 的代理会话与统一消息入口。 */
@RestController
@RequestMapping(value = "/api/v1/open-platform/sessions", produces = "application/json")
public class OpenPlatformSessionController {

    public static final String SESSION_WRITE_SCOPE = "sessions.write";
    public static final String CLOSE_SESSION_COMMAND = "close_session";
    public static final String CANCEL_EXECUTION_COMMAND = "cancel_execution";
    private static final Set<String> RESERVED_EXTERNAL_FIELDS = Set.of(
            "tenant_id", "user_id", "end_user_id", "service_principal_id", "checkpoint", "credentials",
            "application_id", "integration_id", "business_scope_id", "business_scope_ref", "business_scope_type",
            "delegation_requester_id", "delegation_channel_id", "root_input_id", "trace_id", "span_id");

    private final SessionService sessions;
    private final EndUserService endUsers;
    private final AgentRuntimeExecutionGateway runtime;
    private final com.skillforge.core.chat.access.OpenPlatformSessionAccessService access;
    private final com.skillforge.core.foundation.integration.InstitutionIntegrationService integrations;

    public OpenPlatformSessionController(
            SessionService sessions, EndUserService endUsers, AgentRuntimeExecutionGateway runtime,
            com.skillforge.core.chat.access.OpenPlatformSessionAccessService access,
            com.skillforge.core.foundation.integration.InstitutionIntegrationService integrations) {
        this.sessions = sessions;
        this.endUsers = endUsers;
        this.runtime = runtime;
        this.access = access;
        this.integrations = integrations;
    }

    @PostMapping
    public ApiResponse<OpenPlatformSessionResponse> create(
            @RequestBody OpenPlatformCreateSessionRequest request) {
        ServicePrincipalContext.Identity principal = requirePrincipal(SESSION_WRITE_SCOPE);
        if (request == null || blank(request.externalUserRef()) || blank(request.externalConversationRef())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "external user/conversation ref 不能为空");
        }
        Long endUserId = requireEntryEndUserId(request.endUserId());
        Long agentId = requireDecimalId(request.agentId(),"agent_id");
        var user=endUsers.require(principal.tenantId(), endUserId);
        if(!java.util.Objects.equals(principal.integrationId(),user.getExternalIntegrationId())
                || !java.util.Objects.equals(request.externalUserRef(),user.getExternalUserRef()))
            throw new ApiException(HttpStatus.FORBIDDEN,"externalUserRef 与已登记 EndUser 不一致");
        String scopeId=null;
        if(!blank(request.businessScopeType())||!blank(request.businessScopeRef())) {
            if(blank(request.businessScopeType()) || blank(request.businessScopeRef()))
                throw new ApiException(HttpStatus.BAD_REQUEST,"业务范围必须来自当前执行域已登记的完整映射");
            scopeId=integrations.requireBusinessScope(principal.tenantId(), principal.integrationId(),
                    request.businessScopeType(),request.businessScopeRef()).businessScopeId();
        }
        SessionView session = sessions.getOrCreateOpenPlatform(principal.tenantId(), principal.integrationId(),
                request.externalUserRef(), request.externalConversationRef(), request.sessionId(),
                endUserId, agentId,scopeId);
        return ApiResponses.wrap(new OpenPlatformSessionResponse(session.getSessionId(), principal.integrationId(),
                request.externalConversationRef(), endUserId.toString(), session.getAgentId()==null?null:session.getAgentId().toString(),
                new OpenPlatformSessionResponse.UserSubject("user", endUserId.toString())));
    }

    @PostMapping("/messages")
    public ApiResponse<OpenPlatformInboundReceiptResponse> messageEndpoint(
            @Valid @RequestBody MessageCommand command,
            @Parameter(schema=@Schema(maxLength=256)) @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponses.wrap(message(command.sessionId(), idempotencyKey, command.payload()));
    }

    public OpenPlatformInboundReceiptResponse message(String sessionId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, OpenPlatformMessageRequest request) {
        ServicePrincipalContext.Identity principal = requirePrincipal(SESSION_WRITE_SCOPE);
        if (blank(idempotencyKey) || idempotencyKey.length()>256 || request == null || request.content().isEmpty()
                && request.references().isEmpty() && !request.context().containsKey("attachment_ids")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Idempotency-Key 和消息正文不能为空");
        }
        SessionView session = requireOpenPlatformSession(principal, sessionId, true);
        validateExternal(request.context(),0);
        // 会话维度必须进入稳定键，避免同一 integration 下不同会话复用同一幂等键互相污染。
        return runtime.acceptMessage(principal, session, inputId(principal, session, InboundKind.MESSAGE, idempotencyKey),
                request);
    }

    /** 原等待卡片的准确响应交给唯一执行受理事务，不从当前状态补齐身份。 */
    @PostMapping("/actions")
    public ApiResponse<OpenPlatformInboundReceiptResponse> actionEndpoint(
            @Valid @RequestBody ActionCommand command,
            @Parameter(schema=@Schema(maxLength=256)) @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponses.wrap(action(command.sessionId(), idempotencyKey, command.payload()));
    }

    public OpenPlatformInboundReceiptResponse action(String sessionId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, OpenPlatformActionRequest request) {
        ServicePrincipalContext.Identity principal = requirePrincipal(SESSION_WRITE_SCOPE);
        SessionView session = requireOpenPlatformSession(principal, sessionId, true);
        if (blank(idempotencyKey) || idempotencyKey.length()>256 || request == null || blank(request.executionId())
                || blank(request.actionKey())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key、executionId 和 actionKey 不能为空");
        }
        putExternalContext(new LinkedHashMap<>(), request.context());
        return runtime.acceptAction(principal, session,
                inputId(principal, session, InboundKind.ACTION, idempotencyKey),
                request.executionId().trim(), request.actionKey().trim(), request.context());
    }

    /** 外部业务事件只提交新输入；执行完成仍由已注册的专用回调证明。 */
    @PostMapping("/callbacks")
    public ApiResponse<OpenPlatformInboundReceiptResponse> callbackEndpoint(
            @Valid @RequestBody CallbackCommand command,
            @Parameter(schema=@Schema(maxLength=256)) @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponses.wrap(callback(command.sessionId(), idempotencyKey, command.payload()));
    }

    public OpenPlatformInboundReceiptResponse callback(String sessionId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, OpenPlatformCallbackRequest request) {
        ServicePrincipalContext.Identity principal = requirePrincipal(SESSION_WRITE_SCOPE);
        SessionView session = requireOpenPlatformSession(principal, sessionId, true);
        if (blank(idempotencyKey) || idempotencyKey.length()>256 || request == null || blank(request.callbackId())
                || blank(request.eventType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key、callbackId 和 eventType 不能为空");
        }
        if (!"business_event".equals(request.eventType().trim())) throw callbackCompletionRejected();
        Map<String, Object> payload = sanitizeExternal(request.payload());
        validateBusinessEvent(payload);
        return runtime.acceptCallback(principal, session,
                inputId(principal, session, InboundKind.CALLBACK, idempotencyKey),
                request.callbackId().trim(), request.eventType().trim(),
                blank(request.executionId()) ? null : request.executionId().trim(),
                blank(request.workflowId()) ? null : request.workflowId().trim(),
                payload);
    }

    /** 提交平台声明命令；命令本身不携带任意内部服务参数。 */
    @PostMapping("/commands")
    public ApiResponse<OpenPlatformInboundReceiptResponse> commandEndpoint(
            @Valid @RequestBody CommandCommand command,
            @Parameter(schema=@Schema(maxLength=256)) @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponses.wrap(command(command.sessionId(), idempotencyKey, command.payload()));
    }

    public OpenPlatformInboundReceiptResponse command(String sessionId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, OpenPlatformCommandRequest request) {
        ServicePrincipalContext.Identity principal = requirePrincipal(SESSION_WRITE_SCOPE);
        SessionView session = requireOpenPlatformSession(principal, sessionId, false);
        if (blank(idempotencyKey) || idempotencyKey.length()>256 || request == null || blank(request.command())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Idempotency-Key 和 command 不能为空");
        }
        String command = request.command().trim();
        if (!CLOSE_SESSION_COMMAND.equals(command) && !CANCEL_EXECUTION_COMMAND.equals(command)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "不支持的 Open Platform command");
        }
        if (CANCEL_EXECUTION_COMMAND.equals(command) && blank(request.executionId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "cancel_execution 必须提供 executionId");
        }
        if (CLOSE_SESSION_COMMAND.equals(command) && !blank(request.executionId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "close_session 不接受 executionId");
        }
        if (session == null || session.getSessionId() == null || session.getEndUserId() == null
                || session.getEndUserId() <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Open Platform session 缺少入口 end_user_id");
        }
        String reason = blank(request.reason()) ? null : (request.reason().length() > 256
                ? request.reason().substring(0, 256) : request.reason());
        String inboundId = inputId(principal, session, InboundKind.COMMAND, idempotencyKey);
        if (CLOSE_SESSION_COMMAND.equals(command)) {
            return runtime.closeSession(principal, session, inboundId, reason);
        }
        return runtime.cancelExecution(principal, session, inboundId,
                request.executionId().trim(), reason);
    }

    private String inputId(
            ServicePrincipalContext.Identity principal, SessionView session,
            InboundKind kind, String idempotencyKey) {
        return OpenPlatformInputIds.create(principal,session.getSessionId(),kind.name().toLowerCase(Locale.ROOT),idempotencyKey);
    }

    private Long requireEntryEndUserId(String endUserId) {
        try {
            return EndUserIdentity.require(requireDecimalId(endUserId,"end_user_id"), "Open Platform 创建 Session 入口");
        } catch (IllegalArgumentException error) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage());
        }
    }

    /** 身份协议使用十进制字符串，精确转为宿主Long并在字段边界拒绝溢出、小数和非正数。 */
    private Long requireDecimalId(String value,String field) {
        if(value!=null&&value.matches("[1-9][0-9]{0,18}")) {
            try {return Long.valueOf(value);}
            catch(NumberFormatException invalid) { /* 19位字符串仍可能超过宿主Long上限。 */ }
        }
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"OPEN_PLATFORM_ID_INVALID",
                field+" 必须为Long范围内的正整数十进制字符串",false);
    }

    private SessionView requireOpenPlatformSession(
            ServicePrincipalContext.Identity principal, String sessionId, boolean rejectClosed) {
        SessionView session = access.session(access.require(principal,SESSION_WRITE_SCOPE),sessionId);
        if (rejectClosed && sessions.isOpenPlatformClosed(session)) {
            throw new ApiException(HttpStatus.CONFLICT, "会话已关闭");
        }
        return session;
    }

    private Map<String, Object> sanitizeExternal(Map<String, Object> context) {
        Map<String, Object> target = new LinkedHashMap<>();
        putExternalContext(target, context);
        Object nested = target.get("context");
        if (nested instanceof Map<?, ?> map) {
            Map<String, Object> payload = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                payload.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return Map.copyOf(payload);
        }
        return Map.of();
    }

    private void putExternalContext(Map<String, Object> target, Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return;
        }
        validateExternal(context,0);
        if (context.size() > 32) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "外部 payload 字段过多");
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || RESERVED_EXTERNAL_FIELDS.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "外部 payload 包含保留字段");
            }
            if (entry.getValue() == null) {
                continue;
            }
            sanitized.put(entry.getKey(), entry.getValue());
        }
        if (!sanitized.isEmpty()) {
            target.put("context", Map.copyOf(sanitized));
        }
    }

    private ServicePrincipalContext.Identity requirePrincipal(String scope) {
        return access.require(scope).principal();
    }

    /** 回复协议即使藏在嵌套业务对象中也不能经普通事件进入准确等待恢复。 */
    private void validateBusinessEvent(Object value) {
        if (value instanceof Map<?, ?> fields) {
            for (var entry : fields.entrySet()) {
                if (entry.getKey() instanceof String key && Set.of("workflow_input", "tool_input", "human_input")
                        .contains(key.toLowerCase(Locale.ROOT))) throw callbackCompletionRejected();
                validateBusinessEvent(entry.getValue());
            }
        } else if (value instanceof java.util.Collection<?> values) {
            values.forEach(this::validateBusinessEvent);
        }
    }

    private ApiException callbackCompletionRejected() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "callback_completion_requires_registered_delivery",
                "业务事件只接受 business_event；真实执行完成必须使用已注册的回调交付入口", false);
    }

    /** 业务上下文可嵌套，但身份、控制及凭据字段不能借嵌套对象绕过入口限制。 */
    private void validateExternal(Object value,int depth) {
        if(depth>8)throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"业务上下文嵌套过深");
        if(value instanceof Map<?,?> map) {
            if(map.size()>32)throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"外部 payload 字段过多");
            for(var entry:map.entrySet()) {
                if(!(entry.getKey() instanceof String key)||key.isBlank()
                        ||RESERVED_EXTERNAL_FIELDS.contains(key.toLowerCase(Locale.ROOT)))
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"外部 payload 包含保留字段");
                validateExternal(entry.getValue(),depth+1);
            }
        } else if(value instanceof java.util.Collection<?> values) {
            if(values.size()>128)throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"业务上下文列表过长");
            values.forEach(item->validateExternal(item,depth+1));
        } else if(value instanceof String text && text.length()>16384)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"业务上下文内容过长");
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record MessageCommand(
            @Schema(minLength=1) @JsonProperty("session_id") @NotNull String sessionId,
            @JsonProperty("payload") @NotNull @Valid OpenPlatformMessageRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record ActionCommand(
            @Schema(minLength=1) @JsonProperty("session_id") @NotNull String sessionId,
            @JsonProperty("payload") @NotNull @Valid OpenPlatformActionRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record CallbackCommand(
            @Schema(minLength=1) @JsonProperty("session_id") @NotNull String sessionId,
            @JsonProperty("payload") @NotNull @Valid OpenPlatformCallbackRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record CommandCommand(
            @Schema(minLength=1) @JsonProperty("session_id") @NotNull String sessionId,
            @JsonProperty("payload") @NotNull @Valid OpenPlatformCommandRequest payload) {}
}
