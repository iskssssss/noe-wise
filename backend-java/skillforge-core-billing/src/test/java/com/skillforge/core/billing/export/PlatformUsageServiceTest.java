package com.skillforge.core.billing.export;

import com.skillforge.core.foundation.institution.usage.HeadquartersUsageAccess;
import com.skillforge.core.foundation.institution.usage.HeadquartersUsageActions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.integration.OpenPlatformAccessService;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.application.CryptoService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 游标认证绑定授权和筛选；空页可推进，旧授权不能复用原扫描位置。 */
class PlatformUsageServiceTest {
    private final PlatformUsageMapper mapper=mock(PlatformUsageMapper.class);
    private final PlatformManagementService management=mock(PlatformManagementService.class);
    private final HeadquartersUsageAccess headquarters=mock(HeadquartersUsageAccess.class);
    private final PlatformUsageService service;
    PlatformUsageServiceTest() {
        var config=new SkillForgeProperties();config.setSecretKey("synthetic-platform-usage-cursor-secret-32");
        service=new PlatformUsageService(mapper,mock(PlatformUsagePublisher.class),management,
                new org.springframework.beans.factory.support.StaticListableBeanFactory(java.util.Map.of()).getBeanProvider(OpenPlatformAccessService.class),mock(FoundationAccess.class),new CryptoService(config),new ObjectMapper().findAndRegisterModules(),
                new org.springframework.beans.factory.support.StaticListableBeanFactory(java.util.Map.of("headquarters",headquarters)).getBeanProvider(HeadquartersUsageAccess.class));
        when(management.authorizedTenantScope(anyString())).thenReturn(new PlatformManagementService.TenantScope("management:1",1L,Set.of(7L)));when(mapper.streamRevision()).thenReturn("stream");
        when(mapper.streamTail()).thenReturn(2_000L);when(mapper.list(anySet(),isNull(),any(),anyLong(),anyLong(),anyInt())).thenReturn(List.of());
        doAnswer(invocation->((java.util.function.Supplier<?>)invocation.getArgument(1)).get())
                .when(headquarters).read(any(),any());
    }
    @Test void emptyFilteredWindowAdvancesAndDoesNotDiscloseOtherTenantEvents() {
        var first=service.eventsPlatform(null);assertTrue(first.items().isEmpty());assertTrue(first.hasMore());
        var second=service.eventsPlatform(query(first.nextCursor()));assertFalse(second.hasMore());
        verify(mapper).list(eq(Set.of(7L)),isNull(),any(),eq(1_000L),eq(2_000L),eq(101));
        assertNotEquals("1000",first.nextCursor());
        verify(management,times(2)).authorizedTenantScope("platform.usage.events.read");
    }
    @Test void changedAuthorizationOrFilterRequiresExplicitRebuild() {
        var page=service.eventsPlatform(null);when(management.authorizedTenantScope(anyString())).thenReturn(new PlatformManagementService.TenantScope("management:1",2L,Set.of(7L)));
        var denied=assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->service.eventsPlatform(query(page.nextCursor())));
        assertNotNull(denied);
    }
    @Test void changedFilterRequiresExplicitRebuildRatherThanReusingPosition() {
        var page=service.eventsPlatform(null);
        var different=new PlatformUsageQuery(null,null,null,null,null,null,null,"another-run",null,null,null,null,page.nextCursor(),100,null);
        assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->service.eventsPlatform(different));
    }
    @Test void tamperedCursorCannotChooseScanPosition() {
        assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->service.eventsPlatform(query("0")));
    }
    @Test void headquartersRelationRevisionInvalidatesOldCursorBeforeReadingFacts() {
        var original=new HeadquartersUsageAccess.Authorization("6","tenant_member:6:61","grant-and-relation-1",HeadquartersUsageActions.EVENTS,Set.of(7L));
        when(headquarters.authorize(HeadquartersUsageActions.EVENTS)).thenReturn(original);
        var first=service.eventsHeadquarters(null);
        when(headquarters.authorize(HeadquartersUsageActions.EVENTS)).thenReturn(new HeadquartersUsageAccess.Authorization(
                "6","tenant_member:6:61","grant-and-relation-2",HeadquartersUsageActions.EVENTS,Set.of(7L)));
        var failure=assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->service.eventsHeadquarters(query(first.nextCursor())));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT,failure.getStatus());
        verify(headquarters,times(1)).read(any(),any());
    }
    @Test void knownNonTokenToolQuantityRemainsMeasuredDespiteEmptyLlmUsage() {
        var row=toolRow(1);when(mapper.list(anySet(),isNull(),any(),anyLong(),anyLong(),anyInt())).thenReturn(List.of(row));
        var event=service.eventsPlatform(null).items().getFirst();
        assertEquals("measured",event.kind());assertEquals(1,event.quantity());assertEquals("call",event.measurementUnit());
        assertNull(event.tokens());assertNull(event.amountCents());assertEquals("unpriced",event.amountStatus());
        row.setQuantity(null);
        assertEquals("unknown",service.eventsPlatform(null).items().getFirst().kind());
    }
    @Test void finalMeasurementKeepsItsUsageSnapshotWatermarkAfterPartialReports() {
        var row=toolRow(1);row.setEventType("llm.chat");row.setUsageSource("PROVIDER_REPORTED");
        row.setUsageSnapshotRevision(7L);row.setPromptTokens(100L);row.setCompletionTokens(50L);
        when(mapper.list(anySet(),isNull(),any(),anyLong(),anyLong(),anyInt())).thenReturn(List.of(row));
        var measured=service.eventsPlatform(null).items().getFirst();
        assertEquals("measured",measured.kind());assertEquals(7L,measured.revision());
        assertEquals(50L,measured.completionTokens());
        row.setUsageEventKind("usage_report");row.setReportRevision(8L);row.setCompletionTokens(null);
        var report=service.eventsPlatform(null).items().getFirst();
        assertEquals("usage_report",report.kind());assertEquals(8L,report.revision());
        assertNull(report.completionTokens());
    }
    @Test void removedHeadquartersTargetConflictsWithOldCursorButRejectsFreshUnauthorizedFilter() {
        when(headquarters.authorize(HeadquartersUsageActions.EVENTS)).thenReturn(new HeadquartersUsageAccess.Authorization(
                "6","tenant_member:6:61","grant-1",HeadquartersUsageActions.EVENTS,Set.of(7L)));
        var query=new PlatformUsageQuery(null,null,7L,null,null,null,null,null,null,null,null,null,null,100,null);
        var first=service.eventsHeadquarters(query);
        when(headquarters.authorize(HeadquartersUsageActions.EVENTS)).thenReturn(new HeadquartersUsageAccess.Authorization(
                "6","tenant_member:6:61","grant-2",HeadquartersUsageActions.EVENTS,Set.of()));
        var old=new PlatformUsageQuery(null,null,7L,null,null,null,null,null,null,null,null,null,first.nextCursor(),100,null);
        assertEquals(org.springframework.http.HttpStatus.CONFLICT,assertThrows(com.skillforge.core.shared.exception.ApiException.class,
                ()->service.eventsHeadquarters(old)).getStatus());
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN,assertThrows(com.skillforge.core.shared.exception.ApiException.class,
                ()->service.eventsHeadquarters(query)).getStatus());
    }
    @Test void originalSourceAndEveryGrantHopKeepExactDecimalStringIds() throws Exception {
        long large=9_007_199_254_740_993L;
        var row=toolRow(1);row.setSourceOwnerKind("tenant");row.setSourceOwnerTenantId(large);
        row.setAgentDefinitionId(large+1);row.setAgentRevisionId(large+2);row.setAgentRevision(2);
        row.setDeploymentRevision(3L);row.setGrantId(large+3);row.setGrantRevision(4L);
        var hop=new PlatformUsageGrantHopRow();hop.setUsageId(row.getUsageId());hop.setOrdinal(0);hop.setGrantId(large+3);
        hop.setRevision(4L);hop.setGrantorKind("tenant");hop.setGrantorTenantId(large);hop.setTargetTenantId(7L);
        hop.setParentGrantId(large+4);hop.setParentGrantRevision(1L);hop.setRelationRef(large+5);hop.setRelationRevision(2L);
        when(mapper.list(anySet(),isNull(),any(),anyLong(),anyLong(),anyInt())).thenReturn(List.of(row));
        when(mapper.grantHops(Set.of(7L),List.of(row.getUsageId()))).thenReturn(List.of(hop));
        var event=service.eventsPlatform(null).items().getFirst();
        var wire=new ObjectMapper().findAndRegisterModules().readTree(new ObjectMapper().findAndRegisterModules().writeValueAsString(event));
        assertEquals(Long.toString(large),wire.path("source_owner_tenant_id").textValue());
        assertEquals(Long.toString(large+1),wire.path("agent_definition_id").textValue());
        assertEquals(Long.toString(large+2),wire.path("agent_revision_id").textValue());
        var chain=wire.path("grant_chain").get(0);
        assertEquals(Long.toString(large+3),chain.path("grant_id").textValue());
        assertEquals(Long.toString(large+4),chain.path("parent_grant_id").textValue());
        assertEquals(Long.toString(large+5),chain.path("relation_ref").textValue());
        assertTrue(chain.path("revision").isIntegralNumber());
        verify(mapper,times(1)).grantHops(Set.of(7L),List.of(row.getUsageId()));
    }
    @Test void groupFilterCannotReuseCursorFromAnotherGroup() {
        var firstQuery=new PlatformUsageQuery(null,null,null,null,null,null,null,null,null,null,null,null,null,100,91L);
        var first=service.eventsPlatform(firstQuery);
        var changed=new PlatformUsageQuery(null,null,null,null,null,null,null,null,null,null,null,null,first.nextCursor(),100,92L);
        assertEquals(org.springframework.http.HttpStatus.CONFLICT,assertThrows(com.skillforge.core.shared.exception.ApiException.class,
                ()->service.eventsPlatform(changed)).getStatus());
    }

    @Test void groupBackgroundReferencesAreExactStringsAndRemainUnknownWithoutProviderReport() {
        long large=9_007_199_254_740_993L;
        var row=toolRow(null);row.setEventType("llm.chat");row.setGroupBindingId(large);
        row.setRequesterKind("END_USER");row.setRequesterRef("end_user:17");row.setOriginSessionId("shared-session");
        row.setOriginEventId("source-17");row.setResponsibilityAgentId(large+1);row.setSystemAgentRunId(large+2);
        row.setSystemAgentConfigurationRef("configuration:88:2");row.setSystemAgentInvocationRef("invocation:71");
        when(mapper.list(anySet(),isNull(),any(),anyLong(),anyLong(),anyInt())).thenReturn(List.of(row));
        var event=service.eventsPlatform(null).items().getFirst();
        assertEquals(Long.toString(large),event.groupBindingId());assertEquals(Long.toString(large+1),event.responsibilityAgentId());
        assertEquals(Long.toString(large+2),event.systemAgentRunId());assertEquals("end_user:17",event.requesterRef());
        assertEquals("source-17",event.originEventId());assertEquals("unknown",event.kind());assertNull(event.tokens());
        assertEquals("configuration:88:2",event.systemAgentConfigurationRef());assertEquals("invocation:71",event.systemAgentInvocationRef());
    }

    @Test void mediaMetricsKeepExactStringsAndDoNotInferTokenOrPriceFromKnownCallCount() throws Exception {
        long large=9_007_199_254_740_993L;
        var row=toolRow(1);row.setEventType("media.transcription");row.setUsageSource("PROVIDER_REPORTED");
        row.setCharacters(large);row.setDurationSeconds(new java.math.BigDecimal("2.125000"));
        row.setPricingModelId(large+1);row.setPricingModelRevision(3L);
        when(mapper.list(anySet(),isNull(),any(),anyLong(),anyLong(),anyInt())).thenReturn(List.of(row));
        var event=service.eventsPlatform(null).items().getFirst();
        var wire=new ObjectMapper().findAndRegisterModules().readTree(new ObjectMapper().findAndRegisterModules().writeValueAsString(event));
        assertEquals(Long.toString(large),wire.path("characters").textValue());
        assertEquals("2.125000",wire.path("duration_seconds").textValue());
        assertEquals(Long.toString(large+1),wire.path("pricing_model_id").textValue());
        assertTrue(wire.path("pricing_model_revision").isIntegralNumber());
        assertTrue(wire.path("image_count").isNull());assertTrue(wire.path("prompt_tokens").isNull());
        assertEquals("unpriced",event.amountStatus());assertNull(event.amountCents());
    }

    @Test void mediaUnknownAndKnownNotSubmittedAreDistinctDespiteTheirInvocationQuantity() {
        var row=toolRow(1);row.setEventType("media.image");row.setUsageEventKind("usage_report");
        when(mapper.list(anySet(),isNull(),any(),anyLong(),anyLong(),anyInt())).thenReturn(List.of(row));
        assertEquals("unknown",service.eventsPlatform(null).items().getFirst().kind());
        row.setUsageEventKind("measured");row.setQuantity(0);row.setAmountStatus("not_applicable");
        var notSent=service.eventsPlatform(null).items().getFirst();
        assertEquals("measured",notSent.kind());assertEquals(0,notSent.quantity());
        assertEquals("not_applicable",notSent.amountStatus());assertEquals("UNKNOWN",notSent.usageSource());
    }

    private PlatformUsageRow toolRow(Integer quantity) {
        var row=new PlatformUsageRow();row.setUsageId(73L);row.setTenantId(7L);row.setPublicationCursor(1L);
        row.setEventId("7:tool-call");row.setEventType("tool.call");row.setUsageEventKind("measured");
        row.setUsageSource("UNKNOWN");row.setAmountStatus("unpriced");row.setQuantity(quantity);row.setMeasurementUnit("call");
        return row;
    }
    private PlatformUsageQuery query(String cursor) { return new PlatformUsageQuery(null,null,null,null,null,null,null,null,null,null,null,null,cursor,100,null); }
}
