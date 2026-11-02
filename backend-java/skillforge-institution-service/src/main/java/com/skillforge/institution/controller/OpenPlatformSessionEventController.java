package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.chat.dto.chat.SessionOutboxCursorResponse;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.session.SessionOutboxEntry;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.session.SessionOutboxEventProjector;
import com.skillforge.core.chat.session.SessionOutputNotifications;
import com.skillforge.core.chat.access.OpenPlatformSessionAccessService;
import com.skillforge.institution.runtime.AgentRuntimeEventProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** ServicePrincipal 专用的只读 Session Outbox cursor 入口。 */
@RestController
@RequestMapping(value = "/api/v1/open-platform/sessions", produces = "application/json")
public class OpenPlatformSessionEventController {

    public static final String EVENTS_READ_SCOPE = "sessions.events.read";
    private static final Logger LOG=LoggerFactory.getLogger(OpenPlatformSessionEventController.class);

    private final OpenPlatformSessionAccessService access;
    private final SessionMailboxService mailbox;
    private final AgentRuntimeEventProjection projection;
    private final ObjectMapper json;

    public OpenPlatformSessionEventController(OpenPlatformSessionAccessService access,SessionMailboxService mailbox,
            AgentRuntimeEventProjection projection,ObjectMapper json) {
        this.access=access;this.projection=projection;this.json=json;
        this.mailbox = mailbox;
    }

    /** 只读消费同一冻结投影；不领取、不确认，也不创建 Input 或执行 Workflow。 */
    @GetMapping("/events")
    public ApiResponse<SessionOutboxCursorResponse> events(
            @RequestParam("sessionId") String sessionId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        var authorized=access.require(EVENTS_READ_SCOPE);
        ServicePrincipalContext.Identity principal=authorized.principal();
        String tenantId = principal.tenantId();
        projection.reconcile(access.session(authorized,sessionId));
        List<SessionOutboxCursorResponse.Event> events = mailbox.listOutputsAfter(
                tenantId, sessionId, cursor, Math.max(1, Math.min(limit, 500))).stream()
                .map(this::event).toList();
        return ApiResponses.wrap(new SessionOutboxCursorResponse(sessionId,
                events.isEmpty() ? cursor : events.getLast().eventId(), events));
    }

    /** 同一 Outbox 提供实时尾随和断线游标恢复；断开连接只停止输送，不取消运行。 */
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",useReturnTypeSchema=false,
            content=@io.swagger.v3.oas.annotations.media.Content(mediaType="text/event-stream",
                    schema=@io.swagger.v3.oas.annotations.media.Schema(type="string")))
    @GetMapping(value="/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestParam("sessionId") String sessionId,
            @RequestParam(value="cursor",required=false) String cursor,
            @RequestHeader(value="Last-Event-ID",required=false) String lastEventId) {
        var authorized=access.require(EVENTS_READ_SCOPE);
        projection.reconcile(access.session(authorized,sessionId));
        String after=cursor==null||cursor.isBlank()?lastEventId:cursor;
        // 先在请求线程验证游标归属，不能在响应已提交后把非法游标当成空历史。
        long sequence=mailbox.resolveOutputSequence(authorized.principal().tenantId(),sessionId,after);
        var emitter=new SseEmitter(Duration.ofSeconds(60).toMillis());
        var closed=new AtomicBoolean();
        var changes=mailbox.observeOutputs(authorized.principal().tenantId(),sessionId);
        emitter.onCompletion(()->{closed.set(true);changes.close();});
        emitter.onTimeout(()->{closed.set(true);changes.close();});
        emitter.onError(error->{closed.set(true);changes.close();});
        Thread.ofVirtual().name("open-platform-events-").start(()->follow(authorized.principal(),sessionId,sequence,emitter,closed,changes));
        return ResponseEntity.ok().header("Cache-Control","no-store, no-cache, no-transform")
                .header("X-Accel-Buffering","no").contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    /** 每轮复验当前凭证与应用授权，后台明确绑定并清理租户和服务主体。 */
    private void follow(ServicePrincipalContext.Identity principal,String sessionId,long sequence,
            SseEmitter emitter,AtomicBoolean closed,SessionOutputNotifications.Subscription changes) {
        LOG.debug("开放平台事件流开始 tenant={}",principal.tenantId());
        long deadline=System.nanoTime()+Duration.ofSeconds(55).toNanos();
        long next=sequence;long heartbeat=System.nanoTime();
        try {
            TenantContext.setTenantId(principal.tenantId());ServicePrincipalContext.set(principal);
            while(!closed.get()&&System.nanoTime()<deadline) {
                long observed=changes.version();
                var authorized=access.require(principal,EVENTS_READ_SCOPE);
                projection.reconcile(access.session(authorized,sessionId));
                var outputs=mailbox.listOutputsAfterSequence(principal.tenantId(),sessionId,next,500);
                for(var output:outputs) {
                    if(closed.get())return;
                    var event=SessionOutboxEventProjector.project(output);
                    emitter.send(SseEmitter.event().id(event.eventId()).name(event.event()).data(json.writeValueAsString(event.data())));
                    next=event.sessionSequence();
                }
                if(System.nanoTime()-heartbeat>=Duration.ofSeconds(15).toNanos()) {
                    emitter.send(SseEmitter.event().comment("keep-alive"));heartbeat=System.nanoTime();
                }
                if(outputs.isEmpty()) {
                    changes.awaitChange(observed,Duration.ofSeconds(5));
                }
            }
            if(!closed.get())emitter.complete();
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt();emitter.complete(); }
        catch(Exception failure) {
            LOG.debug("开放平台事件流结束 failureType={}",failure.getClass().getSimpleName());
            if(!closed.get())emitter.completeWithError(failure);
        } finally { changes.close();ServicePrincipalContext.clear();TenantContext.clear();LOG.debug("开放平台事件流退出"); }
    }

    private SessionOutboxCursorResponse.Event event(SessionOutboxEntry output) {
        SessionOutboxEventProjector.Event event = SessionOutboxEventProjector.project(output);
        return new SessionOutboxCursorResponse.Event(event.event(), event.eventId(), event.sessionId(),
                event.sessionSequence(), event.inputId(), event.rootInputId(), event.causedByEventId(),
                event.eventVersion(), event.occurredAt(), event.data());
    }
}
