package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.MemoryRetrievalPolicy;
import com.skillforge.runtime.api.MemoryRetrievalDiagnostics;
import com.skillforge.runtime.api.MemoryTaskConstraint;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.GroupBindingRef;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.institution.group.GroupBindingService;
import com.skillforge.core.shared.exception.ApiException;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.MemoryQuery;
import com.skillforge.runtime.api.MemoryQueryProfile;
import com.skillforge.runtime.api.MemoryRetrievalTrace;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RetentionClassRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.memory.MemoryRuntime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MemorySearchToolHandlerTest {
    private static final Instant NOW = Instant.parse("2026-09-24T08:00:00Z");
    private static final TenantId TENANT = new TenantId("tenant");
    private static final AgentId AGENT = new AgentId("agent");
    private static final EndUserId USER = new EndUserId(1);
    private static final SessionRef SESSION = new SessionRef(TENANT, "session");
    private static final String QUERY_ID = "search-memory:invocation";

    private final MemoryRuntime runtime = mock(MemoryRuntime.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final MemorySearchProperties settings=new MemorySearchProperties();
    private final GroupBindingService groups=mock(GroupBindingService.class);
    private final MemorySearchToolHandler handler = new MemorySearchToolHandler(runtime, clock,settings,groups);

    @Test void groupDefaultSearchUsesRequesterAndSharedGroupOwnerWithoutPersonalScopes() {
        var expected=groupQuery("remember",8,NOW,new EndUserId(1));
        var injected=receipt(MemoryRuntime.Phase.INJECTED,trace(List.of("fact-1"),List.of("fact-1")),
                expected,event("group-injected",NOW),List.of());
        when(runtime.findLatestRetrieval(any(),eq(QUERY_ID))).thenReturn(Optional.empty());
        when(runtime.retrieve(any())).thenReturn(new MemoryRuntime.RetrievalResult.Recorded(injected));
        var result=handler.execute(Map.of("query","remember"),groupContext(USER));
        assertEquals(true,result.get("success"));
        var calls=ArgumentCaptor.forClass(MemoryRuntime.RetrievalCall.class);
        verify(runtime).retrieve(calls.capture());
        assertEquals(expected,calls.getValue().query());
        assertEquals(Set.of(MemoryScope.GROUP,MemoryScope.GROUP_MEMBER),calls.getValue().query().scopes());
        assertEquals(Optional.of(USER),calls.getValue().query().subjectEndUserId());
        verify(groups).requireHistoricalMember(TENANT.value(),"integration","group",USER.value());
    }

    @Test void explicitGroupSubjectNarrowsOneMemberAndFrozenInvocationCannotSwitchSubjects() {
        var selected=new EndUserId(9);
        var expected=groupQuery("remember",8,NOW.minusSeconds(30),selected);
        var injected=receipt(MemoryRuntime.Phase.INJECTED,trace(List.of("fact-1"),List.of("fact-1")),
                expected,event("group-frozen",NOW),List.of());
        when(runtime.findLatestRetrieval(any(),eq(QUERY_ID))).thenReturn(Optional.of(injected));
        assertEquals(true,handler.execute(Map.of("query","remember","subject_end_user_id","9"),groupContext(USER)).get("success"));
        var changed=handler.execute(Map.of("query","remember","subject_end_user_id","10"),groupContext(USER));
        assertEquals(false,changed.get("success"));
        assertEquals("memory_search_retrieval_mismatch",changed.get("error"));
        verify(runtime,never()).retrieve(any());
        verify(runtime,never()).advance(any(),any(),any(),any());
    }

    @Test void groupSubjectAndOwnerOverridesAreRejectedBeforeRuntimeAccess() {
        for(var arguments:List.<Map<String,Object>>of(
                Map.of("query","remember","subject_end_user_id",9),
                Map.of("query","remember","subject_end_user_id","0"),
                Map.of("query","remember","subject_end_user_id","09"),
                Map.of("query","remember","subject_end_user_id","9223372036854775808"),
                Map.of("query","remember","group_binding_id","another-group"),
                Map.of("query","remember","scope","USER"))) {
            var rejected=handler.execute(arguments,groupContext(USER));
            assertEquals("search_memory_arguments_rejected",rejected.get("error"));
            assertFalse(rejected.containsKey("facts"));
        }
        assertEquals("search_memory_arguments_rejected",handler.execute(
                Map.of("query","remember","subject_end_user_id","9"),context(false,NOW.plusSeconds(60))).get("error"));
        verifyNoInteractions(runtime);
    }

    @Test void unrelatedGroupSubjectStopsBeforeRetrievalEvenWithValidNumericIdentity() {
        when(groups.requireHistoricalMember(TENANT.value(),"integration","group",9))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN,"memory_group_subject_not_in_group"));
        var rejected=handler.execute(Map.of("query","remember","subject_end_user_id","9"),groupContext(USER));
        assertEquals("memory_group_subject_not_in_group",rejected.get("error"));
        assertFalse(rejected.containsKey("facts"));
        verifyNoInteractions(runtime);
    }

    @Test
    void retrievesThroughTheRuntimeAndOnlyReturnsInjectedSafeFields() {
        MemoryFact fact = fact();
        var candidate = receipt(MemoryRuntime.Phase.CANDIDATE,
                trace(List.of(), List.of()), query("remember colors", 8, NOW),
                event("candidate", NOW), List.of());
        var selected = receipt(MemoryRuntime.Phase.SELECTED,
                trace(List.of("fact-1"), List.of()), query("remember colors", 8, NOW),
                event("selected", NOW), List.of());
        var injected = receipt(MemoryRuntime.Phase.INJECTED,
                trace(List.of("fact-1"), List.of("fact-1")), query("remember colors", 8, NOW),
                event("injected", NOW), List.of(fact));
        when(runtime.findLatestRetrieval(any(), eq(QUERY_ID))).thenReturn(Optional.empty());
        when(runtime.retrieve(any())).thenReturn(new MemoryRuntime.RetrievalResult.Recorded(candidate));
        when(runtime.advance(eq(candidate), eq(MemoryRuntime.Phase.SELECTED), any(), any()))
                .thenReturn(new MemoryRuntime.RetrievalResult.Recorded(selected));
        when(runtime.advance(eq(selected), eq(MemoryRuntime.Phase.INJECTED), any(), any()))
                .thenReturn(new MemoryRuntime.RetrievalResult.Recorded(injected));

        Map<String, Object> result = handler.execute(
                Map.of("query", "remember colors"), context(false, NOW.plusSeconds(60)));

        ArgumentCaptor<MemoryRuntime.RetrievalCall> call =
                ArgumentCaptor.forClass(MemoryRuntime.RetrievalCall.class);
        verify(runtime).retrieve(call.capture());
        MemoryQuery actual = call.getValue().query();
        assertEquals(QUERY_ID, actual.queryId());
        assertEquals(Set.of(MemoryScope.SESSION, MemoryScope.USER_AGENT, MemoryScope.USER), actual.scopes());
        assertEquals(Set.of(new MemoryTypeRef("preference"), new MemoryTypeRef("episode")), actual.types());
        assertEquals(MemoryQueryProfile.DEFAULT, actual.profile());
        assertEquals(MemoryRetrievalPolicy.lexicalV1(), actual.retrievalPolicy());
        assertEquals(Optional.empty(),actual.taskConstraint());
        assertEquals(8, actual.maxItems());
        assertEquals(true, actual.requireProvenance());
        assertEquals("injected", result.get("retrieval_event_id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> projected = ((List<Map<String, Object>>) result.get("facts")).getFirst();
        assertEquals("fact-1", projected.get("fact_id"));
        assertEquals("{\"color\":\"blue\"}", projected.get("object"));
        assertFalse(projected.containsKey("source_session_ref"));
        assertFalse(projected.containsKey("source_event_ids"));
        assertFalse(projected.containsKey("tenant_id"));
        assertFalse(result.containsKey("session_id"));
    }

    @Test
    void reusesAnInjectedFrozenReceiptWithoutChangingItsAsOf() {
        Instant frozenAt = NOW.minusSeconds(30);
        var injected = receipt(MemoryRuntime.Phase.INJECTED,
                trace(List.of("fact-1"), List.of("fact-1")),
                query("find preference", 3, frozenAt), event("frozen", frozenAt), List.of(fact()));
        when(runtime.findLatestRetrieval(any(), eq(QUERY_ID))).thenReturn(Optional.of(injected));

        Map<String, Object> result = handler.execute(
                Map.of("query", "find preference", "limit", 3),
                context(false, NOW.plusSeconds(60)));

        assertEquals(frozenAt.toString(), result.get("as_of"));
        assertEquals("frozen", result.get("retrieval_event_id"));
        verify(runtime, never()).retrieve(any());
        verify(runtime, never()).advance(any(), any(), any(), any());
    }

    @Test
    void rejectsFrozenContractDriftInsteadOfIssuingAnotherQuery() {
        var frozen = receipt(MemoryRuntime.Phase.INJECTED,
                trace(List.of("fact-1"), List.of("fact-1")),
                query("different intent", 8, NOW), event("frozen", NOW), List.of(fact()));
        when(runtime.findLatestRetrieval(any(), eq(QUERY_ID))).thenReturn(Optional.of(frozen));

        Map<String, Object> failure = handler.execute(
                Map.of("query", "current intent"), context(false, NOW.plusSeconds(60)));

        assertEquals(false, failure.get("success"));
        assertEquals("memory_search_retrieval_mismatch", failure.get("error"));
        assertFalse(failure.containsKey("facts"));
        verify(runtime, never()).retrieve(any());
    }

    @Test
    void frozenPolicyTaskAndAsOfSurviveCurrentConfigurationChange() {
        var original=query("remember",3,NOW.minusSeconds(60));
        var fixed=new MemoryQuery(original.queryId(),original.owner(),original.subjectEndUserId(),original.intent(),
                Optional.of(new MemoryTaskConstraint("trusted-task")),original.scopes(),original.types(),original.profile(),
                original.asOf(),original.retrievalPolicy(),original.maxItems(),original.tokenBudget(),original.characterBudget(),true);
        var frozen=receipt(MemoryRuntime.Phase.INJECTED,trace(List.of("fact-1"),List.of("fact-1")),fixed,event("original",NOW),List.of(fact()));
        when(runtime.findLatestRetrieval(any(),eq(QUERY_ID))).thenReturn(Optional.of(frozen));
        settings.setMode(MemorySearchProperties.Mode.HYBRID);
        // 当前配置故意尚未填模型；恢复不能因读当前 defaults 而失败或发起新检索。
        var result=handler.execute(Map.of("query","remember","limit",3),context(false,NOW.plusSeconds(60)));
        assertEquals(true,result.get("success"));
        assertEquals(fixed.asOf().toString(),result.get("as_of"));
        verify(runtime,never()).retrieve(any());
    }

    @Test
    void reportsDeclaredLexicalFallbackAndCurrentFactRefusalWithoutTurningItIntoUnknown() {
        var frozen=receipt(MemoryRuntime.Phase.INJECTED,trace(List.of("fact-1"),List.of("fact-1")),
                query("remember",8,NOW),event("frozen",NOW),List.of(fact()));
        when(frozen.diagnostics()).thenReturn(new MemoryRetrievalDiagnostics(MemoryRetrievalDiagnostics.Mode.LEXICAL,
                Optional.of("memory_vector_unavailable"),1,0,List.of(),List.of()));
        when(runtime.findLatestRetrieval(any(),eq(QUERY_ID))).thenReturn(Optional.of(frozen));
        var result=handler.execute(Map.of("query","remember"),context(false,NOW.plusSeconds(60)));
        assertEquals("degraded",result.get("retrieval_status"));
        assertEquals("lexical",result.get("retrieval_mode"));
        assertEquals("memory_vector_unavailable",result.get("degradation_reason"));
        when(frozen.injectedFacts()).thenThrow(new IllegalStateException("memory_frozen_fact_forgotten"));
        var denied=handler.execute(Map.of("query","remember"),context(false,NOW.plusSeconds(60)));
        assertEquals(false,denied.get("success"));
        assertEquals("memory_frozen_fact_forgotten",denied.get("error"));
        assertFalse(denied.containsKey("facts"));
    }

    @Test
    void rejectsInvalidArgumentsIdentityOverridesAndInactiveCallsBeforeRuntimeAccess() {
        List<Map<String, Object>> invalid = List.of(
                Map.of(),
                Map.of("query", ""),
                Map.of("query", "x".repeat(2_001)),
                Map.of("query", "ok", "limit", 0),
                Map.of("query", "ok", "limit", 1.5d),
                Map.of("query", "ok", "limit", 17),
                Map.of("query", "ok", "limit", "8"),
                Map.of("query", "ok", "tenant_id", "other"));
        for (Map<String, Object> arguments : invalid) {
            Map<String, Object> result = handler.execute(
                    arguments, context(false, NOW.plusSeconds(60)));
            assertEquals(false, result.get("success"), arguments::toString);
            assertEquals("search_memory_arguments_rejected", result.get("error"), arguments::toString);
            assertFalse(result.containsKey("facts"), arguments::toString);
        }
        assertEquals(false, handler.execute(Map.of("query", "ok"),
                context(true, NOW.plusSeconds(60))).get("success"));
        assertEquals(false, handler.execute(Map.of("query", "ok"),
                context(false, NOW)).get("success"));
        verifyNoInteractions(runtime);
    }

    @Test
    void returnsStoppedAsASafeFailureWithoutFacts() {
        when(runtime.findLatestRetrieval(any(), eq(QUERY_ID))).thenReturn(Optional.empty());
        when(runtime.retrieve(any())).thenReturn(new MemoryRuntime.RetrievalResult.Stopped(
                new MemoryRuntime.Failure("memory_access_denied", Optional.of(new ResultRef("audit")))));

        Map<String, Object> result = handler.execute(
                Map.of("query", "remember"), context(false, NOW.plusSeconds(60)));

        assertEquals(false, result.get("success"));
        assertEquals("memory_access_denied", result.get("error"));
        assertFalse(result.containsKey("facts"));
    }

    @Test
    void returnsCurrentRevocationWithoutFactsButDoesNotHideUnexpectedRuntimeFailures() {
        when(runtime.findLatestRetrieval(any(), eq(QUERY_ID)))
                .thenThrow(new IllegalStateException("memory_access_denied"));

        Map<String, Object> denied = handler.execute(
                Map.of("query", "remember"), context(false, NOW.plusSeconds(60)));

        assertEquals(false, denied.get("success"));
        assertEquals("memory_access_denied", denied.get("error"));
        assertFalse(denied.containsKey("facts"));

        MemoryRuntime unexpectedRuntime = mock(MemoryRuntime.class);
        var unexpectedHandler = new MemorySearchToolHandler(unexpectedRuntime, clock,settings,groups);
        when(unexpectedRuntime.findLatestRetrieval(any(), eq(QUERY_ID))).thenReturn(Optional.empty());
        when(unexpectedRuntime.retrieve(any())).thenThrow(new IllegalArgumentException("unexpected bug detail"));

        IllegalArgumentException unexpected = assertThrows(IllegalArgumentException.class,
                () -> unexpectedHandler.execute(Map.of("query", "remember"),
                        context(false, NOW.plusSeconds(60))));
        assertEquals("unexpected bug detail", unexpected.getMessage());
    }

    @Test
    void retriesAPendingAppendOnlyOnce() {
        var pending = mock(MemoryRuntime.PendingRetrieval.class);
        var candidate = receipt(MemoryRuntime.Phase.CANDIDATE,
                trace(List.of(), List.of()), query("remember", 8, NOW), event("candidate", NOW), List.of());
        var selected = receipt(MemoryRuntime.Phase.SELECTED,
                trace(List.of("fact-1"), List.of()), query("remember", 8, NOW), event("selected", NOW), List.of());
        var injected = receipt(MemoryRuntime.Phase.INJECTED,
                trace(List.of("fact-1"), List.of("fact-1")), query("remember", 8, NOW),
                event("injected", NOW), List.of(fact()));
        when(runtime.findLatestRetrieval(any(), eq(QUERY_ID))).thenReturn(Optional.empty());
        when(runtime.retrieve(any())).thenReturn(new MemoryRuntime.RetrievalResult.Pending(
                pending, new MemoryRuntime.Failure("memory_session_log_outcome_unknown", Optional.empty())));
        when(runtime.retry(pending)).thenReturn(new MemoryRuntime.RetrievalResult.Recorded(candidate));
        when(runtime.advance(eq(candidate), eq(MemoryRuntime.Phase.SELECTED), any(), any()))
                .thenReturn(new MemoryRuntime.RetrievalResult.Recorded(selected));
        when(runtime.advance(eq(selected), eq(MemoryRuntime.Phase.INJECTED), any(), any()))
                .thenReturn(new MemoryRuntime.RetrievalResult.Recorded(injected));

        assertTrue((Boolean) handler.execute(Map.of("query", "remember"),
                context(false, NOW.plusSeconds(60))).get("success"));

        verify(runtime).retry(pending);
    }

    @Test
    void keepsTheOutcomeUnknownWhenTheAppendIsStillPendingAfterOneReplay() {
        var pending = mock(MemoryRuntime.PendingRetrieval.class);
        var failure = new MemoryRuntime.Failure(
                "memory_session_log_outcome_unknown", Optional.empty());
        when(runtime.findLatestRetrieval(any(), eq(QUERY_ID))).thenReturn(Optional.empty());
        when(runtime.retrieve(any())).thenReturn(
                new MemoryRuntime.RetrievalResult.Pending(pending, failure));
        when(runtime.retry(pending)).thenReturn(
                new MemoryRuntime.RetrievalResult.Pending(pending, failure));

        RuntimeException unknown = assertThrows(RuntimeException.class,
                () -> handler.execute(Map.of("query", "remember"),
                        context(false, NOW.plusSeconds(60))));

        assertEquals("memory_search_append_outcome_unknown", unknown.getMessage());
        verify(runtime).retry(pending);
    }

    private MemoryRuntime.RetrievalReceipt receipt(
            MemoryRuntime.Phase phase,
            MemoryRetrievalTrace trace,
            MemoryQuery query,
            CommittedSessionEvent event,
            List<MemoryFact> injectedFacts) {
        var receipt = mock(MemoryRuntime.RetrievalReceipt.class);
        when(receipt.phase()).thenReturn(phase);
        when(receipt.trace()).thenReturn(trace);
        when(receipt.query()).thenReturn(query);
        when(receipt.committedEvent()).thenReturn(event);
        when(receipt.injectedFacts()).thenReturn(injectedFacts);
        when(receipt.diagnostics()).thenReturn(MemoryRetrievalDiagnostics.empty());
        return receipt;
    }

    private static MemoryRetrievalTrace trace(List<String> selected, List<String> injected) {
        return new MemoryRetrievalTrace(
                QUERY_ID, List.of("fact-1"), selected, injected, List.of());
    }

    private static MemoryQuery query(String intent, int limit, Instant asOf) {
        return new MemoryQuery(
                QUERY_ID,
                new MemoryOwner.Session(SESSION),
                Optional.empty(),
                intent,
                Optional.empty(),
                Set.of(MemoryScope.SESSION, MemoryScope.USER_AGENT, MemoryScope.USER),
                Set.of(new MemoryTypeRef("preference"), new MemoryTypeRef("episode")),
                MemoryQueryProfile.DEFAULT,
                asOf,
                MemoryRetrievalPolicy.lexicalV1(),
                limit,
                4_096,
                16_384,
                true);
    }

    private static MemoryQuery groupQuery(String intent,int limit,Instant asOf,EndUserId subject) {
        return new MemoryQuery(QUERY_ID,new MemoryOwner.Group(new GroupBindingRef(TENANT,"group")),Optional.of(subject),
                intent,Optional.empty(),Set.of(MemoryScope.GROUP,MemoryScope.GROUP_MEMBER),
                Set.of(new MemoryTypeRef("preference"),new MemoryTypeRef("episode")),MemoryQueryProfile.DEFAULT,
                asOf,MemoryRetrievalPolicy.lexicalV1(),limit,4_096,16_384,true);
    }

    private static PlatformToolExecutionContext groupContext(EndUserId requester) {
        var group=new GroupBindingRef(TENANT,"group");
        var owner=new SessionOwner(SESSION,SessionSubject.group(group),SessionTarget.group(group));
        var input=new InputId("input");
        var run=new RunIdentity(owner,AGENT,input,new RunRef(TENANT,"run"),new TraceEnvelope("trace","span",input,Optional.empty()),
                new ResultRef("identity"),new RunRequester.EndUser(requester),Optional.empty(),new RunOriginRef(TENANT,"input:input"));
        var identity=new VerifiedIdentity(new PrincipalRef.ServicePrincipal(TENANT,"service","integration",1),owner,
                Set.of("runtime:memory"),1,new ResultRef("verification"));
        return new PlatformToolExecutionContext(new PortCallContext(run,identity,new InvocationControl(NOW.plusSeconds(60),()->false)),
                "invocation","attempt");
    }

    private static MemoryFact fact() {
        return new MemoryFact(
                new MemoryFactRef(TENANT, "fact-1"),
                new MemoryOwner.Session(SESSION),
                new MemoryTypeRef("preference"),
                new RetentionClassRef("standard"),
                "v1",
                "user",
                "favorite_color",
                new TypedPayload("preference.v1", "{\"color\":\"blue\"}"),
                List.of(new EventId("source")),
                SESSION,
                Optional.empty(),
                Optional.empty(),
                "extractor-v1",
                2,
                NOW.minusSeconds(60),
                NOW.minusSeconds(60),
                Optional.empty(),
                Optional.empty(),
                0.9d,
                0.8d,
                false,
                Optional.empty(),
                Optional.empty(),
                0d,
                FactStatus.ACTIVE);
    }

    private static CommittedSessionEvent event(String id, Instant occurredAt) {
        CommittedSessionEvent event = mock(CommittedSessionEvent.class);
        when(event.eventId()).thenReturn(new EventId(id));
        when(event.occurredAt()).thenReturn(occurredAt);
        return event;
    }

    private static PlatformToolExecutionContext context(boolean cancelled, Instant deadline) {
        var owner = SessionOwner.user(SESSION, AGENT, USER);
        var input = new InputId("input");
        var run = new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(TENANT, "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var identity = new VerifiedIdentity(
                new PrincipalRef.EndUserPrincipal(TENANT, USER), owner, Set.of("runtime:memory"), 1,
                new ResultRef("verification"));
        return new PlatformToolExecutionContext(
                new PortCallContext(run, identity, new InvocationControl(deadline, () -> cancelled)),
                "invocation", "attempt");
    }
}
