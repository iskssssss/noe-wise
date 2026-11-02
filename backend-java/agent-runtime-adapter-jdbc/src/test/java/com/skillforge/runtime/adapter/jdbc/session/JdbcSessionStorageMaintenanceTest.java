package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.json.RuntimeJson;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** 接口的批次与游标边界在打开数据库连接前拒绝；真实引用竞态由PostgreSQL契约测试验收。 */
class JdbcSessionStorageMaintenanceTest {
    private static final TenantId TENANT = new TenantId("t");
    private static final SessionOwner OWNER = SessionOwner.user(new SessionRef(TENANT,"s"),new AgentId("a"),new EndUserId(1));
    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00.000000019Z");

    @Test void everyLiveUnknownMemoryAndDeliveryProtectionStopsRetirementBeforeItCreatesAFact() {
        for (String reason : List.of("ACTIVE_OR_WAITING_RUN","UNKNOWN_EFFECT","ACTIVE_OR_WAITING_ACTION","MEMORY_SOURCE_REF",
                "MEMORY_EXTRACTION_JOB_REF","MEMORY_EXTRACTION_EVIDENCE_REF","PUBLICATION_NOT_ACKNOWLEDGED","EVENT_DELIVERY_NOT_SETTLED",
                "EXECUTION_PUBLICATION_NOT_ACKNOWLEDGED","ACTION_PUBLICATION_NOT_ACKNOWLEDGED","SESSION_OUTBOX_NOT_SETTLED")) {
            var fixture = fixture();
            when(fixture.mapper.ownerProtection(OWNER)).thenReturn(List.of(reason));
            var result = fixture.maintenance.retireHistory(TENANT,4,8,null);
            assertEquals(0,result.rows()); assertTrue(result.protectedReasons().contains(reason));
            verify(fixture.mapper,never()).ensureHistoryRetirement(any(),anyLong(),anyLong(),anyInt());
            verify(fixture.session).commit();
        }
    }

    @Test void authoritativeCloseAndConfiguredCutoffAreRequiredAndChangedSourceRollsBack() {
        var fixture = fixture();
        when(fixture.mapper.lockClosedOwner(eq(OWNER),anyLong(),anyInt())).thenReturn(null);
        assertTrue(fixture.maintenance.retireHistory(TENANT,4,8,null).protectedReasons().contains("OWNER_NOT_CLOSED_OR_NOT_EXPIRED"));
        var cutoff = NOW.minus(Duration.ofDays(30));
        verify(fixture.mapper).scanClosedOwners("t",cutoff.getEpochSecond(),cutoff.getNano(),4,null);
        verify(fixture.mapper,never()).ensureHistoryRetirement(any(),anyLong(),anyLong(),anyInt());
        var changed = fixture(); changed.retirement.sourceSessionRevision++;
        assertThrows(IllegalStateException.class,()->changed.maintenance.retireHistory(TENANT,4,8,null));
        verify(changed.session).rollback(); verify(changed.session,never()).commit();
    }

    @Test void completedEmptyRoundDoesNotTouchProgressAndProtectedEventsKeepIdentity() {
        var fixture = fixture(); fixture.retirement.status="RETIRED";
        page(fixture,List.of());
        assertEquals(0,fixture.maintenance.retireHistory(TENANT,4,8,null).rows());
        verify(fixture.mapper,never()).advanceHistoryRetirement(any(),anyLong(),anyLong(),anyLong(),any(),anyLong(),anyLong(),anyLong(),anyBoolean());
        var protectedFixture = fixture(); var reasoning=row("reasoning",9,"agent.model-reasoning","reasoning.v1","{}","1");
        page(protectedFixture,List.of(reasoning));
        when(protectedFixture.mapper.eventProtected(OWNER,"reasoning")).thenReturn(true);
        assertTrue(protectedFixture.maintenance.retireHistory(TENANT,4,8,null).protectedReasons().contains("EVENT_REFERENCE"));
        verify(protectedFixture.mapper,never()).insertEventTombstone(any(),anyString());
        verify(protectedFixture.mapper).advanceHistoryRetirement(OWNER,0,9,8,null,0,0,0,false);
    }

    @Test void blockedWatermarkDoesNotLoseEarlierReferencesAndStableBlockedPollingDoesNotWrite() {
        var fixture = fixture(); fixture.retirement.scanAfterSequence=8; fixture.retirement.resumeAfterSequence=8;
        fixture.retirement.recheckAfterSequence=0L;
        var source=row("reasoning",9,"agent.model-reasoning","reasoning.v1","{}","1");
        page(fixture,List.of(source));
        when(fixture.mapper.eventProtected(OWNER,"reasoning")).thenReturn(true);
        fixture.maintenance.retireHistory(TENANT,4,8,null);
        verify(fixture.mapper).advanceHistoryRetirement(OWNER,8,9,0,null,0,0,0,false);
        var stable=fixture(); stable.retirement.scanAfterSequence=9; stable.retirement.resumeAfterSequence=8;
        page(stable,List.of(source));
        when(stable.mapper.eventProtected(OWNER,"reasoning")).thenReturn(true);
        assertFalse(stable.maintenance.retireHistory(TENANT,4,8,null).moreWork());
        verify(stable.mapper,never()).advanceHistoryRetirement(any(),anyLong(),anyLong(),anyLong(),any(),anyLong(),anyLong(),anyLong(),anyBoolean());
        when(stable.mapper.eventProtected(OWNER,"reasoning")).thenReturn(false);
        when(stable.mapper.insertEventTombstone(any(),anyString())).thenReturn(1);
        var deleted=new StorageReclaimedRow(); deleted.rows=1; deleted.bytes=2;
        when(stable.mapper.deleteHistoryEvent(OWNER,"reasoning",9)).thenReturn(deleted);
        assertEquals(1,stable.maintenance.retireHistory(TENANT,4,8,null).rows());
        verify(stable.mapper).advanceHistoryRetirement(OWNER,8,9,9,null,1,2,1,true);
    }

    @Test void diagnosticPurgeAlsoRequiresClosedSourceAndKeepsExactReplayTombstoneAndSeparateDeleteMetrics() {
        var fixture=fixture(); String canonical=RuntimeJson.writeCanonical(Map.of("kind","stage","operationKey","operation","metrics",Map.of("duration","1")));
        var target=new SessionProjectionTarget(OWNER,new InputId("input"),Optional.of(new RunRef(TENANT,"run")),Optional.empty(),Optional.empty(),Optional.empty());
        var draft=new SessionEventDraft(new EventId("runtime-diagnostic:test"),"agent.runtime-diagnostic","1","fingerprint",
                new TraceEnvelope("trace","span",new InputId("input"),Optional.empty()),
                new TypedPayload("skillforge.runtime.agent.runtime-diagnostic.v1",canonical),NOW.minus(Duration.ofDays(32)));
        var command=new SessionLogPort.AppendSessionEvent(target,draft); var source=SessionEventRow.from(command,9);
        var cutoff=NOW.minus(Duration.ofDays(30));
        when(fixture.mapper.lockDiagnosticEvents(OWNER,cutoff.getEpochSecond(),cutoff.getNano(),8)).thenReturn(List.of(source));
        when(fixture.mapper.insertEventTombstone(any(),anyString())).thenReturn(1);
        var deleted=new StorageReclaimedRow(); deleted.rows=1; deleted.bytes=canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        when(fixture.mapper.deleteHistoryEvent(OWNER,source.getEventId(),9)).thenReturn(deleted);
        when(fixture.mapper.advanceDiagnosticRetirement(OWNER,1,deleted.bytes)).thenReturn(1);
        var result=fixture.maintenance.purgeDiagnostics(OWNER,NOW.minus(Duration.ofDays(1)),8);
        assertEquals(1,result.rows()); assertEquals(deleted.bytes,result.bytes());
        verify(fixture.mapper).insertEventTombstone(source,SessionHistoryDigest.digest(command,9));
        verify(fixture.mapper,never()).advanceHistoryRetirement(any(),anyLong(),anyLong(),anyLong(),any(),anyLong(),anyLong(),anyLong(),anyBoolean());
        var active=fixture(); when(active.mapper.lockClosedOwner(eq(OWNER),anyLong(),anyInt())).thenReturn(null);
        assertEquals(0,active.maintenance.purgeDiagnostics(OWNER,NOW.minusSeconds(1),8).rows());
        verify(active.mapper,never()).ensureHistoryRetirement(any(),anyLong(),anyLong(),anyInt());
        assertThrows(IllegalArgumentException.class,()->new JdbcSessionStorageMaintenance(mock(SqlSessionFactory.class),Duration.ofDays(29)));
    }

    private record Fixture(JdbcSessionStorageMaintenance maintenance,SessionStorageMaintenanceMapper mapper,SqlSession session,SessionHistoryScanRow retirement) { }
    /** 有界身份页与领取结果来自同一fixture，测试可单独模拟一条身份被其他引用写者锁住。 */
    private static void page(Fixture fixture,List<SessionEventRow> rows) {
        var keys=rows.stream().map(row->{var key=new SessionHistoryEventKey();key.eventId=row.getEventId();key.sessionSequence=row.getSessionSequence();return key;}).toList();
        when(fixture.mapper.historyEventKeys(eq(OWNER),anyLong(),anyInt())).thenReturn(keys);
        when(fixture.mapper.lockHistoryEvents(eq(OWNER),anyLong(),anyLong(),anyInt())).thenReturn(rows);
    }
    private static Fixture fixture() {
        var factory=mock(SqlSessionFactory.class); var session=mock(SqlSession.class); var mapper=mock(SessionStorageMaintenanceMapper.class);
        when(factory.openSession(false)).thenReturn(session); when(session.getMapper(SessionStorageMaintenanceMapper.class)).thenReturn(mapper);
        when(mapper.storageNow()).thenReturn(NOW); when(mapper.lockOwner(OWNER)).thenReturn(1L);
        var owner=new SessionHistoryOwnerRow(); owner.targetKind="AGENT"; owner.targetId="a"; owner.sessionId="s"; owner.endUserId=1;
        owner.sourceSessionRevision=7; owner.closedEpochSecond=NOW.minus(Duration.ofDays(31)).getEpochSecond(); owner.closedNano=19;
        when(mapper.scanClosedOwners(anyString(),anyLong(),anyInt(),anyInt(),any())).thenReturn(List.of(owner));
        when(mapper.lockClosedOwner(eq(OWNER),anyLong(),anyInt())).thenReturn(owner); when(mapper.ownerProtection(OWNER)).thenReturn(List.of());
        var retirement=new SessionHistoryScanRow(); retirement.sourceSessionRevision=7; retirement.closedEpochSecond=owner.closedEpochSecond;
        retirement.closedNano=19; retirement.status="RETIRING"; when(mapper.lockHistoryRetirement(OWNER)).thenReturn(retirement);
        when(mapper.advanceHistoryRetirement(any(),anyLong(),anyLong(),anyLong(),any(),anyLong(),anyLong(),anyLong(),anyBoolean())).thenReturn(1);
        return new Fixture(new JdbcSessionStorageMaintenance(factory,Duration.ofDays(30)),mapper,session,retirement);
    }
    private static SessionEventRow row(String eventId,long sequence,String type,String schema,String json,String version) {
        var target=new SessionProjectionTarget(OWNER,new InputId("input"),Optional.of(new RunRef(TENANT,"run")),Optional.empty(),
                Optional.of(new ActionRef(TENANT,"model-action:operation")),Optional.empty());
        return SessionEventRow.from(new SessionLogPort.AppendSessionEvent(target,new SessionEventDraft(new EventId(eventId),type,version,"fingerprint:"+eventId,
                new TraceEnvelope("trace","span",new InputId("input"),Optional.empty()),new TypedPayload(schema,json),NOW)),sequence);
    }
    @Test void invalidBatchAndCursorScopeNeverOpenADatabaseConnection() {
        var source = mock(DataSource.class);
        var maintenance = new JdbcSessionStorageMaintenance(source);
        assertThrows(IllegalArgumentException.class, () -> maintenance.collectOrphans(new TenantId("t"), 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> maintenance.collectOrphans(new TenantId("t"), 129, null, null));
        assertThrows(IllegalArgumentException.class, () -> new JdbcSessionStorageMaintenance.Cursor("", "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new JdbcSessionStorageMaintenance.Cursor("run", "invalid-hash"));
        assertThrows(IllegalArgumentException.class, () -> new JdbcSessionStorageMaintenance.EventCursor("AGENT", "a", "s", "invalid-hash"));
        verifyNoInteractions(source);
    }
}
