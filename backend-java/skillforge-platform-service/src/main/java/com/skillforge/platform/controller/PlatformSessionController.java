package com.skillforge.platform.controller;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.observation.SessionObservationAuthorizer;
import com.skillforge.core.chat.observation.SessionObservationQuery;
import com.skillforge.core.chat.observation.SessionObservationService;
import com.skillforge.core.chat.observation.SessionObservationView;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 平台会话只读观测入口；所有资源定位均同时携带机构与会话身份。 */
@RestController
@RequestMapping("/api/v1/platform/sessions")
public class PlatformSessionController {
    private final SessionObservationAuthorizer authorizer;
    private final SessionObservationService observations;

    public PlatformSessionController(
            SessionObservationAuthorizer authorizer,
            SessionObservationService observations) {
        this.authorizer = authorizer;
        this.observations = observations;
    }

    @RequirePermission("foundation.sessions.read")
    @GetMapping
    public ApiResponse<SessionObservationView.SessionPage> sessions(
            @RequestParam(name = "tenant_id", required = false) String tenantId,
            @RequestParam(name = "session_id", required = false) String sessionId,
            @RequestParam(name = "user_id", required = false) String userId,
            @RequestParam(name = "agent_id", required = false) String agentId,
            @RequestParam(name = "team_id", required = false) String teamId,
            @RequestParam(name = "channel", required = false) String channel,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "started_from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startedFrom,
            @RequestParam(name = "started_to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startedTo,
            @RequestParam(name = "summary", required = false) String summary,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponses.wrap(observations.sessions(authorizer.platformAdmin(),
                new SessionObservationQuery.Sessions(optionalId(tenantId, "tenant_id"),
                        trim(sessionId), optionalId(userId, "user_id"), optionalId(agentId, "agent_id"),
                        trim(teamId), trim(channel), trim(status), startedFrom, startedTo,
                        trim(summary), page, pageSize)));
    }

    @RequirePermission("foundation.sessions.read")
    @GetMapping("/detail")
    public ApiResponse<SessionObservationView.SessionDetail> detail(
            @RequestParam(name = "tenant_id") String tenantId,
            @RequestParam(name = "session_id") String sessionId) {
        return ApiResponses.wrap(observations.detail(authorizer.platformAdmin(),
                new SessionObservationQuery.Resource(requiredId(tenantId, "tenant_id"), sessionId)));
    }

    @RequirePermission("foundation.sessions.read")
    @GetMapping("/rounds")
    public ApiResponse<SessionObservationView.RoundPage> rounds(
            @RequestParam(name = "tenant_id") String tenantId,
            @RequestParam(name = "session_id") String sessionId,
            @RequestParam(name = "root_input_id", required = false) String rootInputId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return ApiResponses.wrap(observations.rounds(authorizer.platformAdmin(),
                new SessionObservationQuery.Rounds(requiredId(tenantId, "tenant_id"), sessionId,
                        trim(rootInputId), trim(cursor), pageSize)));
    }

    @RequirePermission("foundation.sessions.read")
    @GetMapping("/messages")
    public ApiResponse<SessionObservationView.MessagePage> messages(
            @RequestParam(name = "tenant_id") String tenantId,
            @RequestParam(name = "session_id") String sessionId,
            @RequestParam(name = "root_input_id", required = false) String rootInputId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return ApiResponses.wrap(observations.messages(authorizer.platformAdmin(),
                new SessionObservationQuery.Messages(requiredId(tenantId, "tenant_id"), sessionId,
                        trim(rootInputId), trim(cursor), pageSize)));
    }

    @RequirePermission("foundation.sessions.read")
    @GetMapping("/trace")
    public ApiResponse<SessionObservationView.TracePage> trace(
            @RequestParam(name = "tenant_id") String tenantId,
            @RequestParam(name = "session_id") String sessionId,
            @RequestParam(name = "root_input_id") String rootInputId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize,
            @RequestParam(name = "node_id", required = false) String nodeId) {
        return ApiResponses.wrap(observations.trace(authorizer.platformAdmin(),
                new SessionObservationQuery.Trace(requiredId(tenantId, "tenant_id"), sessionId,
                        rootInputId, trim(cursor), pageSize, trim(nodeId))));
    }

    @RequirePermission("foundation.sessions.read")
    @GetMapping("/trace/content")
    public ApiResponse<SessionObservationView.Content> traceContent(
            @RequestParam(name = "tenant_id") String tenantId,
            @RequestParam(name = "session_id") String sessionId,
            @RequestParam(name = "root_input_id") String rootInputId,
            @RequestParam(name = "content_ref") String contentRef) {
        return ApiResponses.wrap(observations.content(authorizer.platformAdmin(),
                new SessionObservationQuery.Content(requiredId(tenantId, "tenant_id"), sessionId,
                        rootInputId, contentRef)));
    }

    @RequirePermission("foundation.sessions.read")
    @GetMapping("/usage")
    public ApiResponse<SessionObservationView.Usage> usage(
            @RequestParam(name = "tenant_id") String tenantId,
            @RequestParam(name = "session_id") String sessionId,
            @RequestParam(name = "root_input_id", required = false) String rootInputId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return ApiResponses.wrap(observations.usage(authorizer.platformAdmin(),
                new SessionObservationQuery.Usage(requiredId(tenantId, "tenant_id"), sessionId,
                        trim(rootInputId), trim(cursor), pageSize)));
    }

    private static Long optionalId(String value, String field) {
        return value == null || value.isBlank() ? null : requiredId(value, field);
    }

    private static long requiredId(String value, String field) {
        try {
            long parsed = Long.parseLong(value == null ? "" : value.trim());
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException failure) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " 格式无效");
        }
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
