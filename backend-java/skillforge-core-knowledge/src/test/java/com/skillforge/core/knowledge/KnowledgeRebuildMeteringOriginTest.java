package com.skillforge.core.knowledge;

import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.knowledge.model.KnowledgeRebuildJobDO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 后台重建从持久来源恢复费用归属，不能借用运行线程中的其他用户或 Run。 */
class KnowledgeRebuildMeteringOriginTest {
    @AfterEach void clear() {MeterScope.clear();}

    @Test void restoredSnapshotIgnoresUnrelatedThreadScopeAndRestoresCallerAfterClose() {
        var snapshot=new KnowledgeRebuildJobDO();
        try(var ignored=MeterScope.open(CostContext.builder().runId("original-run").sessionId("session")
                .requestId("request").endUserId(9007199254740993L).agentId(10L)
                .scopeType("control_operation").scopeId("operation").build())) {
            KnowledgeRebuildService.captureMeteringOrigin(snapshot);
        }
        assertEquals(9007199254740993L,snapshot.getMeteringEndUserId());
        try(var caller=MeterScope.open(CostContext.builder().runId("foreign-run").endUserId(42L).build())) {
            try(var task=MeterScope.openIsolated(KnowledgeRebuildService.restoreMeteringOrigin(snapshot,"batch"))) {
                assertEquals("original-run",MeterScope.current().runId());
                assertEquals(9007199254740993L,MeterScope.current().endUserId());
                assertEquals("operation",MeterScope.current().scopeId());assertEquals("batch",MeterScope.current().eventId());
            }
            assertEquals("foreign-run",MeterScope.current().runId());
        }
        assertNull(MeterScope.current());
    }

    @Test void independentTaskWithNoOriginCannotAcquireThreadIdentity() {
        var snapshot=new KnowledgeRebuildJobDO();KnowledgeRebuildService.captureMeteringOrigin(snapshot);
        assertNull(snapshot.getMeteringRunId());
        try(var caller=MeterScope.open(CostContext.builder().runId("foreign").build());
            var task=MeterScope.openIsolated(KnowledgeRebuildService.restoreMeteringOrigin(snapshot,"batch"))) {
            assertNull(MeterScope.current().runId());assertNull(MeterScope.current().sessionId());
        }
    }
}
