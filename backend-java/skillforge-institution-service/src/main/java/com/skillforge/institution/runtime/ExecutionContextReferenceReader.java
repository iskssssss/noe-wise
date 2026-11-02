package com.skillforge.institution.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.chat.contract.ContextReferenceLimits;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.AgentExecutionContextViews;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.SessionContextReferenceReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** 受控上下文读取仅消费新Run原输入、已采纳安全工具反馈及正式公开事件。 */
@Component
public final class ExecutionContextReferenceReader {
    private final ExecutionStore store;
    private final AgentExecutionCodec codec;
    private final JdbcIdentityPolicyPort identities;
    private final AgentRuntimeExecutionProjection projection;
    private final PublicOutputGuard guard;
    private final ObjectMapper json;
    public ExecutionContextReferenceReader(ExecutionStore store,AgentExecutionCodec codec,JdbcIdentityPolicyPort identities,
            AgentRuntimeExecutionProjection projection,PublicOutputGuard guard,ObjectMapper json) {
        this.store=Objects.requireNonNull(store);this.codec=Objects.requireNonNull(codec);this.identities=Objects.requireNonNull(identities);
        this.projection=Objects.requireNonNull(projection);this.guard=Objects.requireNonNull(guard);this.json=Objects.requireNonNull(json);
    }

    /** 每次工具调用按实际Registered动作、Attempt、调用主体及当前IAM建立只读作用域。 */
    public SessionContextReferenceReader forCall(PlatformToolExecutionContext context) {
        var expected=context.run();var run=store.load(expected.runRef()).orElseThrow(ExecutionContextReferenceReader::rejected);
        var action=store.action(new ActionRef(expected.runRef().tenantId(),context.invocationId())).orElseThrow(ExecutionContextReferenceReader::rejected);
        var attempt=store.attempt(expected.runRef(),context.attemptId()).orElseThrow(ExecutionContextReferenceReader::rejected);
        if(!run.identity().equals(expected)||!action.run().equals(expected.runRef())||action.kind()!=ExecutionStore.Kind.TOOL
                ||!action.currentAttempt().equals(attempt.id())||!attempt.action().equals(action.reference())||!attempt.run().equals(expected.runRef())
                ||attempt.result().isPresent()||attempt.fence()!=run.fence()||run.cancelled()||run.phase()!=ExecutionStore.Phase.AWAITING_RESULT
                ||!context.callContext().identity().owner().equals(expected.owner())||!context.callContext().identity().verificationRef().equals(run.actorVerificationRef())
                ||context.cancellationSignal().isCancellationRequested()||!Instant.now().isBefore(context.deadlineAt()))throw rejected();
        var owner=expected.owner();var operation="context-read:"+context.attemptId();
        try {
            var resource=new TypedPayload("skillforge.runtime.facade-access.v1",json.writeValueAsString(List.of(owner.sessionRef().tenantId().value(),
                    owner.target().kind().name(),owner.target().id(),owner.sessionRef().sessionId(),com.skillforge.runtime.adapter.jdbc.identity.OwnerPolicyFields.subject(owner),
                    "runtime.events.read","session",owner.sessionRef().sessionId(),"0",operation)));
            if(!(identities.authorize(new IdentityPolicyPort.AuthorizationRequest(context.callContext().identity(),"runtime.events.read",resource,
                    expected.trace().traceId(),expected.trace().spanId())) instanceof IdentityPolicyPort.AuthorizationResult.Allowed))throw rejected();
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException("context_reference_resource_invalid",invalid);}
        return new Bound(expected);
    }

    private final class Bound implements SessionContextReferenceReader {
        private final RunIdentity caller;
        private Bound(RunIdentity caller){this.caller=caller;}
        private List<Document> documents(RunIdentity run) {
            if(!caller.equals(run))throw rejected();var result=new ArrayList<Document>();var owners=new HashMap<RunRef,ExecutionStore.Run>();long after=0;
            while(true) {
                var page=store.events(caller.owner().sessionRef(),after,100);
                for(var event:page) {
                    if(event.sequence()<=after||!event.run().tenantId().equals(caller.runRef().tenantId()))throw rejected();after=event.sequence();
                    var document=document(event,owners);
                    if(document!=null)result.add(document);
                }
                if(page.size()<100)return List.copyOf(result);
            }
        }
        private Document document(ExecutionStore.SavedEvent event,Map<RunRef,ExecutionStore.Run> owners) {
            if(!event.run().tenantId().equals(caller.runRef().tenantId()))throw rejected();
            var source=owners.computeIfAbsent(event.run(),key->store.load(key).orElseThrow(ExecutionContextReferenceReader::rejected));
            if(!source.identity().owner().equals(caller.owner()))throw rejected();
            var text=content(source,event);
            return text==null||text.isEmpty()||guard.capture(caller.owner()).rejects(text) ? null
                    : new Document(event.event().id(),event.run(),event.sequence(),event.occurredAt(),text);
        }
        private Document source(RunIdentity run,String sourceEventId) {
            if(!caller.equals(run))throw rejected();
            var event=store.event(caller.owner().sessionRef(),new EventId(sourceEventId)).orElseThrow(ExecutionContextReferenceReader::rejected);
            if(!event.run().tenantId().equals(caller.runRef().tenantId()))throw rejected();
            var source=store.load(event.run()).orElseThrow(ExecutionContextReferenceReader::rejected);
            if(!source.identity().owner().equals(caller.owner()))throw rejected();
            var text=content(source,event);
            if(text==null||text.isEmpty()||guard.capture(caller.owner()).rejects(text))throw rejected();
            return new Document(event.event().id(),event.run(),event.sequence(),event.occurredAt(),text);
        }
        public Page read(RunIdentity run,String referenceId,long offset,int limit){return page(locate(documents(run),value->reference(value).referenceId().equals(referenceId)),offset,limit);}
        public Page readSource(RunIdentity run,String sourceEventId,long offset,int limit){return page(source(run,sourceEventId),offset,limit);}
        public LocatedPage find(RunIdentity run,String referenceId,String literal,long offset,int limit){return find(locate(documents(run),value->reference(value).referenceId().equals(referenceId)),literal,offset,limit);}
        public LocatedPage findSource(RunIdentity run,String sourceEventId,String literal,long offset,int limit){return find(source(run,sourceEventId),literal,offset,limit);}
        public HistoryPage history(RunIdentity run,HistoryQuery query) {
            if(query.afterSequence()<0||query.limit()<1||query.limit()>100||query.window()<0||query.window()>50
                    ||query.aroundSourceEventId().isPresent()&&(query.afterSequence()!=0||query.query().isPresent()||query.fromTime().isPresent()||query.toTime().isPresent()))throw rejected();
            if(!caller.equals(run))throw rejected();
            if(query.aroundSourceEventId().isPresent()) {
                var anchor=source(run,query.aroundSourceEventId().orElseThrow());
                if(query.runId().isPresent()&&!anchor.run.runId().equals(query.runId().orElseThrow()))throw rejected();
                long lower=Math.max(0,anchor.sequence-query.window()),upper=Math.addExact(anchor.sequence,query.window());
                long after=lower==0?0:lower-1;
                var events=new ArrayList<>(store.eventsInRange(caller.owner().sessionRef(),after,upper,100));
                if(events.size()==100 && events.getLast().sequence()<upper)
                    events.addAll(store.eventsInRange(caller.owner().sessionRef(),events.getLast().sequence(),upper,1));
                var owners=new HashMap<RunRef,ExecutionStore.Run>();
                var selected=new ArrayList<HistoryEntry>();
                long next=after;
                boolean foundAnchor=false;
                for(var event:events) {
                    if(event.sequence()<=next||event.sequence()>upper)throw rejected();
                    next=event.sequence();
                    var value=document(event,owners);
                    if(value!=null && (query.runId().isEmpty()||value.run.runId().equals(query.runId().orElseThrow()))) {
                        selected.add(entry(value));
                        if(value.event.equals(anchor.event))foundAnchor=true;
                    }
                }
                if(!foundAnchor)throw rejected();
                return new HistoryPage(List.copyOf(selected),next,true);
            }
            var events=store.events(caller.owner().sessionRef(),query.afterSequence(),query.limit());
            var owners=new HashMap<RunRef,ExecutionStore.Run>();
            var selected=new ArrayList<HistoryEntry>();
            long next=query.afterSequence();
            for(var event:events) {
                if(event.sequence()<=next)throw rejected();
                next=event.sequence();
                var value=document(event,owners);
                if(value!=null && (query.runId().isEmpty()||value.run.runId().equals(query.runId().orElseThrow()))
                        && query.fromTime().filter(bound->value.at.isBefore(bound)).isEmpty()
                        && query.toTime().filter(bound->value.at.isAfter(bound)).isEmpty()
                        && query.query().map(text->value.content.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))).orElse(true))
                    selected.add(entry(value));
            }
            boolean end=events.size()<query.limit() || store.events(caller.owner().sessionRef(),next,1).isEmpty();
            return new HistoryPage(List.copyOf(selected),next,end);
        }
        private static HistoryEntry entry(Document value){return new HistoryEntry(value.event.value(),value.run.runId(),value.sequence,value.at,value.content);}
        private ContextReference reference(Document value) {
            String digest=hash(value.content);var owner=caller.owner();String identity=hash(owner.sessionRef().tenantId().value()+"\0"+owner.sessionRef().sessionId()+"\0"
                    +owner.target().kind().name()+"\0"+owner.target().id()+"\0"+com.skillforge.runtime.adapter.jdbc.identity.OwnerPolicyFields.subject(owner)+"\0"+value.event.value()+"\0"+digest);
            return new ContextReference("context:"+identity,caller.owner(),value.event,new ResultRef("sha256:"+digest),value.content.codePointCount(0,value.content.length()),true);
        }
        private Page page(Document value,long offset,int limit) {
            long characters=value.content.codePointCount(0,value.content.length());if(offset<0||offset>characters||limit<1||limit>ContextReferenceLimits.MAX_PAGE_CHARACTERS)throw rejected();
            int count=(int)Math.min(limit,characters-offset);int start=value.content.offsetByCodePoints(0,(int)offset);int end=value.content.offsetByCodePoints(start,count);
            return new Page(reference(value),value.content.substring(start,end),offset+count,offset+count==characters);
        }
        private LocatedPage find(Document value,String literal,long offset,int limit) {
            int characters=value.content.codePointCount(0,value.content.length());if(offset<0||offset>characters||literal==null||literal.isBlank())throw rejected();
            int found=value.content.indexOf(literal,value.content.offsetByCodePoints(0,(int)offset));
            long position=found<0?characters:value.content.codePointCount(0,found);return new LocatedPage(page(value,position,limit),position,found>=0);
        }
    }

    private String content(ExecutionStore.Run source,ExecutionStore.SavedEvent saved) {
        try {
            if(AgentExecutionContextViews.EVENT.equals(saved.event().type())) {
                if(!AgentExecutionContextViews.SCHEMA.equals(saved.event().data().schemaId()))throw rejected();
                var body=json.readTree(saved.event().data().canonicalJson());
                if(body.size()!=2||!body.path("sourceContext").isTextual()||!body.path("messages").isObject())throw rejected();
                material(source.identity().runRef(),new ResultRef(body.path("sourceContext").asText()));
                var messages=body.path("messages");
                var exact=new TypedPayload(messages.path("schemaId").asText(),messages.path("canonicalJson").asText());
                codec.decodeInput(exact);
                return exact.canonicalJson();
            }
            if(AgentExecutionProgram.ACCEPTED_EVENT.equals(saved.event().type())) {
                var input=material(source.identity().runRef(),new ResultRef("execution-input:"+source.identity().runRef().runId()));
                return codec.decodeInput(input.content()).stream().filter(value->value.messageId().equals("input:"+source.identity().causationInputId().value()))
                        .flatMap(value->value.contents().stream()).filter(ModelContent.Text.class::isInstance).map(ModelContent.Text.class::cast).map(ModelContent.Text::text).reduce("",String::concat);
            }
            if(AgentExecutionProgram.TOOL_RESULT_EVENT.equals(saved.event().type())) {
                var body=json.readTree(saved.event().data().canonicalJson());var action=store.action(new ActionRef(saved.run().tenantId(),body.path("actionId").asText())).orElseThrow(ExecutionContextReferenceReader::rejected);
                if(!action.run().equals(saved.run())||action.kind()!=ExecutionStore.Kind.TOOL)throw rejected();
                var selected=store.attempt(saved.run(),action.selectedAttempt().orElseThrow(ExecutionContextReferenceReader::rejected)).orElseThrow(ExecutionContextReferenceReader::rejected);
                if(!selected.adopted()||!selected.action().equals(action.reference())||!selected.run().equals(saved.run()))throw rejected();
                if(selected.feedback().isEmpty())return null;
                var feedback=material(saved.run(),selected.feedback().orElseThrow());var request=codec.decodeTool(material(saved.run(),selected.request()).content());
                if(!codec.safeToolFeedback(request,codec.decodeToolOutcome(material(saved.run(),selected.result().orElseThrow(ExecutionContextReferenceReader::rejected)).content())).equals(Optional.of(feedback.content())))throw rejected();
                return feedback.content().canonicalJson();
            }
            return projection.project(source.identity().owner(),List.of(saved)).messages().stream().map(value->value.content()).filter(Objects::nonNull).reduce("",String::concat);
        }catch(java.io.IOException invalid){throw new IllegalStateException("context_reference_source_invalid",invalid);}
    }
    private ExecutionStore.Material material(RunRef run,ResultRef reference){return store.material(run.tenantId(),reference).filter(value->value.reference().equals(reference)).orElseThrow(ExecutionContextReferenceReader::rejected);}
    private record Document(EventId event,RunRef run,long sequence,Instant at,String content) { }
    private static Document locate(List<Document> values,java.util.function.Predicate<Document> selector){var found=values.stream().filter(selector).toList();if(found.size()!=1)throw rejected();return found.getFirst();}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException(unavailable);}}
    private static IllegalStateException rejected(){return new IllegalStateException("context_reference_read_rejected");}
}
