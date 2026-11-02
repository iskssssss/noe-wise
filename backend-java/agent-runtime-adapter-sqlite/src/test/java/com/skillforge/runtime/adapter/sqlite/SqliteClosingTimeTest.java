package com.skillforge.runtime.adapter.sqlite;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort.*;
import com.skillforge.runtime.api.port.ExecutionBudgetScope;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteClosingTimeTest {
    @TempDir Path directory;
    @Test void boundedFileClosingWindowSurvivesReopenWithoutReleasingUnknownOrResettingAttempts() {
        var file=directory.resolve("closing-window.db");var run=new RunRef(new TenantId("desktop-local"),"closing-file");
        var capacity=new Resources(8,4,8,4,100_000,100_000,200_000);
        var policy=new Policy.Enforced(new Limits(Scenario.STANDARD,capacity,capacity,capacity,300,300,"closing-window"));
        var spec=new ClosingSpec(2,1,120,0,List.of(),Set.of());
        var sizing=new ExecutionBudgetScope.ClosingSizing(Category.BUSINESS_MODEL,20,10,Duration.ofSeconds(120));
        Snapshot before;
        try(var store=new SqliteExecutionStore(file,Clock.systemUTC())) {
            var ledger=store.budgets();ledger.register(new Registration(run,run,Role.PRIMARY,policy));
            var closing=sizing.size(spec);assertEquals(240,closing.activeSeconds());
            assertFalse(ledger.admitClosingAndChoosePhase(run,new ClosingAdmission(closing,closing),Set.of(),Resources.call(Category.BUSINESS_MODEL,20,10)));
            ledger.recordActivity(run,"ordinary",Instant.EPOCH,Instant.EPOCH.plusSeconds(60));ledger.enterClosing(run);
            ledger.recordActivity(run,ExecutionBudgetScope.CLOSING_MODEL_INTERVAL_PREFIX+"one",Instant.EPOCH.plusSeconds(60),Instant.EPOCH.plusSeconds(100));
            ledger.recordActivity(run,ExecutionBudgetScope.CLOSING_MODEL_INTERVAL_PREFIX+"two",Instant.EPOCH.plusSeconds(90),Instant.EPOCH.plusSeconds(130));
            var unknown=new Demand(run,"unknown","same-fingerprint",Category.BUSINESS_MODEL,Resources.call(Category.BUSINESS_MODEL,20,10),true);
            assertInstanceOf(Reservation.Granted.class,ledger.reserve(unknown));assertTrue(ledger.markSent(run,"unknown"));
            before=ledger.snapshot(run);assertEquals(70_000,before.closingModelActiveMillis());
        }
        try(var store=new SqliteExecutionStore(file,Clock.systemUTC())) {
            var ledger=store.budgets();ledger.recover(run);assertEquals(before,ledger.snapshot(run));
            ledger.recordActivity(run,"ordinary-later",Instant.EPOCH.plusSeconds(130),Instant.EPOCH.plusSeconds(150));
            var remaining=sizing.size(spec,ledger.snapshot(run).closingModelActiveMillis());assertEquals(170,remaining.activeSeconds());
            var previous=ledger.snapshot(run);
            var failure=assertThrows(ClosingInsufficient.class,()->ledger.admitClosing(run,new ClosingAdmission(remaining,remaining)));
            assertEquals(Phase.CLOSING,failure.phase());
            assertTrue(failure.shortfalls().stream().allMatch(item->item.dimension().equals("ACTIVE_MILLIS")&&item.required()==320_000&&item.available()==300_000&&item.deficit()==20_000));
            assertEquals(previous,ledger.snapshot(run));assertEquals(70_000,ledger.snapshot(run).closingModelActiveMillis());
        }
    }
}
