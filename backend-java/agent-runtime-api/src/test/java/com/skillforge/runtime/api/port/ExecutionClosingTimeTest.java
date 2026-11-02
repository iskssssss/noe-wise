package com.skillforge.runtime.api.port;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort.*;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExecutionClosingTimeTest {
    private static final ExecutionBudgetScope.ClosingSizing SIZING=new ExecutionBudgetScope.ClosingSizing(Category.BUSINESS_MODEL,100,20,Duration.ofSeconds(120));
    private static final ClosingSpec FILE=new ClosingSpec(2,1,120,32_768,List.of(),Set.of());
    private static final Resources CAPACITY=new Resources(8,4,8,4,100_000,100_000,200_000);

    @Test void threeHundredSecondFileTaskKeepsFullLocalWindowAndSixtySecondsForBusiness() {
        var protection=SIZING.size(FILE);
        assertEquals(240,protection.activeSeconds());assertEquals(240,FILE.activeSeconds(Duration.ofSeconds(120)));
        assertEquals(new Resources(2,0,1,0,32_968,40,33_008),protection.resources());
        assertEquals(Duration.ofSeconds(60),ExecutionBudgetScope.effectiveTimeout(snapshot(Phase.BUSINESS,0,0,protection),SIZING,FILE,Category.BUSINESS_MODEL,Duration.ofSeconds(120),0));
    }
    @Test void eachModelSharesTheOriginalWindowAndToolsProtectTheRemainingFinalModel() {
        var first=snapshot(Phase.CLOSING,60_000,0,SIZING.size(FILE));
        assertEquals(Duration.ofSeconds(60),ExecutionBudgetScope.effectiveTimeout(first,SIZING,FILE,Category.BUSINESS_MODEL,Duration.ofSeconds(120),0));
        var afterModel=snapshot(Phase.CLOSING,90_000,30_000,SIZING.size(FILE,30_000));
        assertEquals(Duration.ofSeconds(120),ExecutionBudgetScope.effectiveTimeout(afterModel,SIZING,FILE,Category.TOOL,Duration.ofSeconds(120),0));
        var finalSpec=new ClosingSpec(1,0,0,0,List.of(),Set.of());
        var afterFreeze=snapshot(Phase.CLOSING,210_000,30_000,SIZING.size(finalSpec,30_000));
        assertEquals(Duration.ofSeconds(90),ExecutionBudgetScope.effectiveTimeout(afterFreeze,SIZING,finalSpec,Category.BUSINESS_MODEL,Duration.ofSeconds(120),0));
        assertEquals(120_000,30_000+ExecutionBudgetScope.effectiveTimeout(afterFreeze,SIZING,finalSpec,Category.BUSINESS_MODEL,Duration.ofSeconds(120),0).toMillis());
    }
    @Test void spentWindowAndActualUnconfirmedTimeNeverObtainANewTimeout() {
        var finalSpec=new ClosingSpec(1,0,0,0,List.of(),Set.of());
        assertEquals(Duration.ZERO,ExecutionBudgetScope.effectiveTimeout(snapshot(Phase.CLOSING,240_000,120_000,Closing.ZERO),SIZING,finalSpec,Category.BUSINESS_MODEL,Duration.ofSeconds(120),0));
        assertEquals(Duration.ofSeconds(5),ExecutionBudgetScope.effectiveTimeout(snapshot(Phase.BUSINESS,55_000,0,SIZING.size(FILE)),SIZING,FILE,Category.BUSINESS_MODEL,Duration.ofSeconds(120),0));
        assertEquals(Duration.ZERO,ExecutionBudgetScope.effectiveTimeout(snapshot(Phase.BUSINESS,55_000,0,SIZING.size(FILE)),SIZING,FILE,Category.TOOL,Duration.ofSeconds(120),5_000));
    }
    private static Snapshot snapshot(Phase phase,long active,long modelActive,Closing closing) {
        var run=new RunRef(new TenantId("1"),"file-run");
        var registration=new Registration(run,run,Role.PRIMARY,new Policy.Enforced(new Limits(Scenario.STANDARD,CAPACITY,CAPACITY,CAPACITY,300,300,"closing-test")));
        return new Snapshot(registration,Resources.ZERO,Resources.ZERO,Resources.ZERO,Resources.ZERO,closing,phase,active,active,modelActive);
    }
}
