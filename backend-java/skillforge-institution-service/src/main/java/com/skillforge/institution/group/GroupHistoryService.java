package com.skillforge.institution.group;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.dto.chat.SessionOutboxCursorResponse;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.session.SessionOutboxEventProjector;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.chat.message.ChatMessageRecord;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.session.SessionOutboxEntry;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.session.SessionOutputNotifications;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.institution.runtime.AgentRuntimeExecutionProjection;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 群公开历史、游标补拉与SSE共用当前成员授权和唯一Outbox。 */
@Service
public class GroupHistoryService {
    public static final String READ_SCOPE="groups.history.read";
    public static final int DEFAULT_PAGE_SIZE=20;
    public static final int MAX_PAGE_SIZE=200;
    private static final int HISTORY_CURSOR_VERSION=1;
    private static final int MAX_CURSOR_LENGTH=4096;
    public static final String IDENTITY_UNDISCLOSABLE="GROUP_EVENT_ID_UNDISCLOSABLE";
    private static final String IDENTITY_MESSAGE="群事件身份无法公开，请保留原游标并联系机构管理员人工核对。";
    private final GroupBindingService groups;
    private final InstitutionIntegrationService integrations;
    private final SessionService sessions;
    private final GroupHistoryMapper mapper;
    private final GroupHistorySourcePublisher sources;
    private final AgentRuntimeExecutionProjection projection;
    private final SessionMailboxService mailbox;
    private final ObjectMapper json;
    private final GroupHistoryProperties properties;
    private final PublicOutputGuard guard;

    public GroupHistoryService(GroupBindingService groups,InstitutionIntegrationService integrations,SessionService sessions,
            GroupHistoryMapper mapper,GroupHistorySourcePublisher sources,AgentRuntimeExecutionProjection projection,
            SessionMailboxService mailbox,ObjectMapper json,GroupHistoryProperties properties,PublicOutputGuard guard) {
        this.groups=groups;this.integrations=integrations;this.sessions=sessions;this.mapper=mapper;
        this.sources=sources;this.projection=projection;this.mailbox=mailbox;this.json=json;this.properties=properties;this.guard=guard;
    }

    /** 新成员读取完整既有公开消息；分页只选固定through之前的最新消息版本。 */
    public GroupHistoryContracts.HistoryPage history(String group,String reader,String cursor,int pageSize) {
        pageSize(pageSize);var access=authorize(ServicePrincipalContext.get(),group,reader);
        prepare(access);HistoryCursor position;
        if(cursor==null) {
            String through=mailbox.committedCursor(access.tenant(),access.session().getSessionId());
            position=new HistoryCursor(HISTORY_CURSOR_VERSION,access.tenant(),access.integration(),group,
                    access.session().getSessionId(),access.reader(),pageSize,through,Long.MAX_VALUE);
        } else {
            position=decode(cursor);requireCursor(position,access,pageSize);
        }
        long through=position.throughEventId()==null?0:mailbox.resolveOutputSequence(access.tenant(),position.sessionId(),position.throughEventId());
        var rows=mapper.messagePage(Long.parseLong(access.tenant()),position.sessionId(),through,position.beforeSequence(),pageSize+1);
        boolean more=rows.size()>pageSize;var page=rows.subList(0,Math.min(rows.size(),pageSize));
        var items=new ArrayList<ChatMessageRecord>();
        try {
            for(var row:page) {
                var output=output(row);
                requireDisclosableIdentities(SessionOutboxEventProjector.project(output));
                items.add(projection.publicMessage(output));
            }
            if(guard.capture().rejects(position.throughEventId()))
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,IDENTITY_UNDISCLOSABLE,IDENTITY_MESSAGE,false);
        } catch(ApiException blocked) {
            if(IDENTITY_UNDISCLOSABLE.equals(blocked.getCode()))recheck(access);
            throw blocked;
        }
        Collections.reverse(items);
        String next=more?encode(new HistoryCursor(position.version(),position.tenant(),position.integration(),position.group(),
                position.sessionId(),position.reader(),position.pageSize(),position.throughEventId(),page.getLast().firstSequence())):null;
        recheck(access);
        return new GroupHistoryContracts.HistoryPage(group,position.sessionId(),items,next,more,position.throughEventId());
    }

    /** 事件游标是已提交Outbox身份，不接受客户端裸序号。 */
    public GroupHistoryContracts.EventsPage events(String group,String reader,String cursor,int pageSize) {
        pageSize(pageSize);optionalCursor(cursor);var access=authorize(ServicePrincipalContext.get(),group,reader);prepare(access);
        long sequence=mailbox.resolveOutputSequence(access.tenant(),access.session().getSessionId(),cursor);
        var rows=mailbox.listOutputsAfterSequence(access.tenant(),access.session().getSessionId(),sequence,pageSize+1);
        boolean more=rows.size()>pageSize;var page=rows.subList(0,Math.min(rows.size(),pageSize));
        var items=new ArrayList<SessionOutboxCursorResponse.Event>();
        try { for(var row:page)items.add(event(row)); }
        catch(ApiException blocked) {
            if(IDENTITY_UNDISCLOSABLE.equals(blocked.getCode()))recheck(access);
            throw blocked;
        }
        String next=page.isEmpty()?cursor:page.getLast().getEventId();recheck(access);
        return new GroupHistoryContracts.EventsPage(group,access.session().getSessionId(),items,next,more);
    }

    /** 请求线程先核当前身份及游标，提交SSE之后不再追加普通JSON错误。 */
    public ResponseEntity<SseEmitter> stream(String group,String reader,String cursor,String lastEventId) {
        optionalCursor(cursor);optionalCursor(lastEventId);
        if(cursor!=null&&lastEventId!=null&&!cursor.equals(lastEventId))throw validation("cursor与Last-Event-ID不一致");
        var access=authorize(ServicePrincipalContext.get(),group,reader);prepare(access);
        long after=mailbox.resolveOutputSequence(access.tenant(),access.session().getSessionId(),cursor==null?lastEventId:cursor);
        var first=mailbox.listOutputsAfterSequence(access.tenant(),access.session().getSessionId(),after,1);
        if(!first.isEmpty()) {
            try { requireDisclosableIdentities(SessionOutboxEventProjector.project(first.getFirst())); }
            catch(ApiException blocked) {
                if(!IDENTITY_UNDISCLOSABLE.equals(blocked.getCode()))throw blocked;
                recheck(access);
                return ResponseEntity.noContent().build();
            }
        }
        var emitter=new SseEmitter(properties.streamLifetime().toMillis());var closed=new AtomicBoolean();
        var changes=mailbox.observeOutputs(access.tenant(),access.session().getSessionId());
        emitter.onCompletion(()->{closed.set(true);changes.close();});
        emitter.onTimeout(()->{closed.set(true);changes.close();});
        emitter.onError(failure->{closed.set(true);changes.close();});
        Thread.ofVirtual().name("group-public-events-").start(()->follow(access,after,emitter,closed,changes));
        return ResponseEntity.ok().header("Cache-Control","no-store, no-cache, no-transform")
                .header("X-Accel-Buffering","no").contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    private void follow(Access access,long sequence,SseEmitter emitter,AtomicBoolean closed,SessionOutputNotifications.Subscription changes) {
        try {
            TenantContext.setTenantId(access.tenant());ServicePrincipalContext.set(access.principal());
            long deadline=System.nanoTime()+properties.streamLifetime().toNanos();long heartbeat=System.nanoTime();long next=sequence;
            while(!closed.get()&&System.nanoTime()<deadline) {
                long observed=changes.version();recheck(access);projection.reconcile(access.session());
                var rows=mailbox.listOutputsAfterSequence(access.tenant(),access.session().getSessionId(),next,MAX_PAGE_SIZE);
                for(var row:rows) {
                    if(closed.get())return;
                    // 披露前重新取当前成员/SP事实；页已读到不构成后续发送授权。
                    recheck(access);var event=event(row);
                    emitter.send(SseEmitter.event().id(event.eventId()).name(event.event()).data(json.writeValueAsString(event.data())));
                    next=event.sessionSequence();
                }
                if(System.nanoTime()-heartbeat>=properties.heartbeat().toNanos()) {
                    emitter.send(SseEmitter.event().comment("keep-alive"));heartbeat=System.nanoTime();
                }
                if(rows.isEmpty())changes.awaitChange(observed,properties.recheckInterval());
            }
            if(!closed.get())emitter.complete();
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();if(!closed.get())emitter.complete();}
        catch(ApiException failure) {
            if(!closed.get()&&IDENTITY_UNDISCLOSABLE.equals(failure.getCode())) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(json.writeValueAsString(Map.of(
                            "error_code",IDENTITY_UNDISCLOSABLE,"message",IDENTITY_MESSAGE,"retryable",false))));
                    emitter.complete();
                } catch(java.io.IOException disconnected) {emitter.completeWithError(disconnected);}
            } else if(!closed.get())emitter.completeWithError(failure);
        }
        catch(Exception failure) {if(!closed.get())emitter.completeWithError(failure);}
        finally {changes.close();ServicePrincipalContext.clear();TenantContext.clear();}
    }

    /** 仅将可信固定执行域与当前真实成员映射到原群Session，不放开个人Session入口。 */
    Access authorize(ServicePrincipalContext.Identity principal,String group,String reader) {
        long groupId=decimal(group,"group_binding_id"),user=decimal(reader,"reader_end_user_id");
        if(principal==null||!Objects.equals(principal.tenantId(),TenantContext.getTenantId()))throw denied();
        integrations.requirePrincipal(principal,READ_SCOPE);
        var binding=groups.requireActive(principal.tenantId(),principal.integrationId(),group);
        groups.requireMember(principal.tenantId(),principal.integrationId(),group,user,false);
        var session=sessions.requireForSystem(principal.tenantId(),binding.sessionId());
        if(!Objects.equals(session.getGroupBindingId(),groupId)||!Objects.equals(session.getIntegrationId(),principal.integrationId())
                ||!Objects.equals(session.getBusinessScopeId(),binding.businessScopeId())||!session.getSessionId().equals(binding.sessionId()))throw denied();
        return new Access(principal,group,user,session);
    }

    void recheck(Access access) {
        var current=authorize(access.principal(),access.group(),Long.toString(access.reader()));
        if(!current.session().getSessionId().equals(access.session().getSessionId()))throw denied();
    }

    private void prepare(Access access){sources.reconcile(access.session());projection.reconcile(access.session());}

    SessionOutboxCursorResponse.Event event(SessionOutboxEntry row) {
        requireDisclosableIdentities(SessionOutboxEventProjector.project(row));
        var event=projection.publicEvent(row);
        return new SessionOutboxCursorResponse.Event(event.event(),event.eventId(),event.sessionId(),event.sessionSequence(),
                event.inputId(),event.rootInputId(),event.causedByEventId(),event.eventVersion(),event.occurredAt(),event.data());
    }

    /** 身份不得通过显示脱敏改写；不可披露时保持原游标，由人工核对原事件。 */
    private void requireDisclosableIdentities(SessionOutboxEventProjector.Event event) {
        var scope=guard.capture();
        if(scope.rejects(event.eventId())||scope.rejects(event.sessionId())||scope.rejects(event.inputId())
                ||scope.rejects(event.rootInputId())||scope.rejects(event.causedByEventId())
                ||rejectsIdentity(json.valueToTree(event.data()),scope))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,IDENTITY_UNDISCLOSABLE,IDENTITY_MESSAGE,false);
    }

    private boolean rejectsIdentity(com.fasterxml.jackson.databind.JsonNode value,PublicOutputGuard.Scope scope) {
        if(value.isArray()) {
            for(var item:value)if(rejectsIdentity(item,scope))return true;
        } else if(value.isObject()) {
            var fields=value.fields();
            while(fields.hasNext()) {
                var field=fields.next();var key=field.getKey();var child=field.getValue();
                boolean identity=key.equals("id")||key.endsWith("_id")||key.endsWith("_ids")
                        ||key.endsWith("_ref")||key.endsWith("_refs")||key.endsWith("Id")
                        ||key.endsWith("Ids")||key.endsWith("Ref")||key.endsWith("Refs");
                if(identity && child.isTextual() && scope.rejects(child.textValue()))return true;
                if(identity && child.isArray())for(var item:child)
                    if(item.isTextual() && scope.rejects(item.textValue()))return true;
                if(rejectsIdentity(child,scope))return true;
            }
        }
        return false;
    }

    SessionOutboxEntry output(GroupHistoryMapper.Snapshot row) {
        try {
            return SessionOutboxEntry.builder().tenantId(row.tenantId()).sessionId(row.sessionId())
                    .eventId(row.eventId()).runtimeRunId(row.runtimeRunId()).sessionSequence(row.sessionSequence())
                    .eventType(row.eventType()).payload(json.readValue(row.payloadJson(),new TypeReference<Map<String,Object>>() { }))
                    .createdAt(row.createdAt()).firstMessageSequence(row.firstSequence())
                    .firstMessageCreatedAt(row.firstCreatedAt()).build();
        } catch(JsonProcessingException malformed){throw new IllegalStateException("group_public_snapshot_invalid",malformed);}
    }

    private void requireCursor(HistoryCursor cursor,Access access,int pageSize) {
        if(cursor.version()!=HISTORY_CURSOR_VERSION||!Objects.equals(cursor.tenant(),access.tenant())
                ||!Objects.equals(cursor.integration(),access.integration())||!Objects.equals(cursor.group(),access.group())
                ||!Objects.equals(cursor.sessionId(),access.session().getSessionId())||cursor.reader()!=access.reader()
                ||cursor.pageSize()!=pageSize||cursor.beforeSequence()<1
                ||cursor.throughEventId()!=null&&cursor.throughEventId().isBlank())throw cursorInvalid();
    }

    private String encode(HistoryCursor cursor) {
        try{return Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(cursor));}
        catch(JsonProcessingException impossible){throw new IllegalStateException("group_history_cursor_encoding_failed",impossible);}
    }

    private HistoryCursor decode(String cursor) {
        optionalCursor(cursor);
        try{var value=json.readValue(Base64.getUrlDecoder().decode(cursor),HistoryCursor.class);if(value==null)throw cursorInvalid();return value;}
        catch(IllegalArgumentException|java.io.IOException malformed){throw cursorInvalid();}
    }

    public static int parsePageSize(String value) {
        if(value==null)return DEFAULT_PAGE_SIZE;
        if(!value.matches("[1-9][0-9]*"))throw validation("page_size须为1至200的整数");
        try{int parsed=Integer.parseInt(value);pageSize(parsed);return parsed;}
        catch(NumberFormatException malformed){throw validation("page_size超出范围");}
    }
    private static void pageSize(int value){if(value<1||value>MAX_PAGE_SIZE)throw validation("page_size须为1至200");}
    private static long decimal(String value,String field){try{if(value==null||!value.matches("[1-9][0-9]{0,18}"))throw validation(field+"须为正整数十进制字符串");return Long.parseLong(value);}catch(NumberFormatException malformed){throw validation(field+"超出范围");}}
    private static void optionalCursor(String value){if(value!=null&&(value.isBlank()||value.codePointCount(0,value.length())>MAX_CURSOR_LENGTH||value.chars().anyMatch(Character::isISOControl)))throw validation("cursor格式非法");}
    private static ApiException validation(String message){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message,false);}
    private static ApiException denied(){return new ApiException(HttpStatus.FORBIDDEN,"group_history_access_denied","没有当前群公开历史访问权",false);}
    private static ApiException cursorInvalid(){return new ApiException(HttpStatus.CONFLICT,"EVENT_CURSOR_EXPIRED","历史游标不属于当前查询或已失效，请重新读取历史",false);}

    record Access(ServicePrincipalContext.Identity principal,String group,long reader,SessionView session) {
        String tenant(){return principal.tenantId();}String integration(){return principal.integrationId();}
    }
    private record HistoryCursor(int version,String tenant,String integration,String group,String sessionId,long reader,
            int pageSize,String throughEventId,long beforeSequence) { }
}
