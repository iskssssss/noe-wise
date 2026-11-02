package com.skillforge.platform.controller;

import com.skillforge.core.chat.observation.SessionObservationAuthorizer;
import com.skillforge.core.chat.trajectory.TrajectoryListQuery;
import com.skillforge.core.chat.trajectory.TrajectoryListService;
import com.skillforge.core.chat.trajectory.TrajectoryProjectionService;
import com.skillforge.core.chat.trajectory.TrajectoryQuery;
import com.skillforge.core.chat.trajectory.TrajectoryView;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 平台跨机构轨迹读取；每个深层资源必须同时指定机构、会话及根输入。 */
@RestController
@RequestMapping("/api/v1/platform/trajectories")
public class PlatformTrajectoryController {
    private final SessionObservationAuthorizer authorizer;
    private final TrajectoryListService lists;
    private final TrajectoryProjectionService projection;

    public PlatformTrajectoryController(SessionObservationAuthorizer authorizer, TrajectoryListService lists,
                                        TrajectoryProjectionService projection) {
        this.authorizer = authorizer; this.lists = lists; this.projection = projection;
    }

    @RequirePermission("foundation.sessions.read")
    @GetMapping
    public ApiResponse<TrajectoryView.ListPage> list(
            @RequestParam(name = "tenant_id", required = false) Long tenantId,
            @RequestParam(name = "trace_id", required = false) String traceId,
            @RequestParam(name = "session_id", required = false) String sessionId,
            @RequestParam(name = "agent_id", required = false) Long agentId,
            @RequestParam(name = "model", required = false) String model,
            @RequestParam(name = "tool", required = false) String tool,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "started_from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startedFrom,
            @RequestParam(name = "started_to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startedTo,
            @RequestParam(name = "duration_min_ms", required = false) Long durationMinMs,
            @RequestParam(name = "duration_max_ms", required = false) Long durationMaxMs,
            @RequestParam(name = "input_keyword", required = false) String inputKeyword,
            @RequestParam(name = "output_keyword", required = false) String outputKeyword,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponses.wrap(lists.list(authorizer.platformAdmin(), new TrajectoryListQuery(tenantId,
                traceId, sessionId, agentId, model, tool, status, startedFrom, startedTo,
                durationMinMs, durationMaxMs, inputKeyword, outputKeyword, page, pageSize)));
    }

    @RequirePermission("foundation.sessions.read")
    @GetMapping("/detail")
    public ApiResponse<TrajectoryView.Detail> detail(
            @RequestParam(name = "tenant_id") long tenantId,
            @RequestParam(name = "session_id") String sessionId,
            @RequestParam(name = "root_input_id") String rootInputId) {
        return ApiResponses.wrap(projection.detail(authorizer.platformAdmin(),
                new TrajectoryQuery.Resource(tenantId, sessionId, rootInputId)));
    }

    @RequirePermission("foundation.sessions.read")
    @GetMapping("/nodes")
    public ApiResponse<TrajectoryView.ObservationPage> nodes(
            @RequestParam(name = "tenant_id") long tenantId,
            @RequestParam(name = "session_id") String sessionId,
            @RequestParam(name = "root_input_id") String rootInputId,
            @RequestParam(name = "snapshot", required = false) String snapshot,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize,
            @RequestParam(name = "node_id", required = false) String nodeId,
            @RequestParam(name = "query", required = false) String query) {
        return ApiResponses.wrap(projection.nodes(authorizer.platformAdmin(), new TrajectoryQuery.Nodes(
                tenantId, sessionId, rootInputId, snapshot, cursor, pageSize, nodeId, query)));
    }

    @RequirePermission("foundation.sessions.read")
    @GetMapping("/content")
    public ApiResponse<TrajectoryView.Content> content(
            @RequestParam(name = "tenant_id") long tenantId,
            @RequestParam(name = "session_id") String sessionId,
            @RequestParam(name = "root_input_id") String rootInputId,
            @RequestParam(name = "content_ref") String contentRef) {
        return ApiResponses.wrap(projection.content(authorizer.platformAdmin(),
                new TrajectoryQuery.Content(tenantId, sessionId, rootInputId, contentRef)));
    }
}
