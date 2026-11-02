package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ExecutionBudgetPort.*;
import com.skillforge.runtime.json.CodecLimits;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExecutionTerminationObservationTest {
    private final AgentExecutionCodec codec=new AgentExecutionCodec(new CodecLimits(100_000,10_000,32));
    @Test void readsOriginalLockedNumbersAndFormulaWithoutCurrentConfiguration() {
        var failure=new ClosingFailure(Phase.BUSINESS,List.of(new ClosingShortfall(BalanceScope.ROOT,"ACTIVE_MILLIS",360_000,300_000,60_000,120_000,0,300_000)),
                new ClosingTimeCalculation(2,120_000,120_000,120_000,240_000));
        var value=AgentExecutionCodec.object();value.put("status","TERMINATED");value.put("reason",ExecutionTerminationObservation.BUDGET_FAILURE_CODE);
        value.set("budget_failure",ExecutionTerminationObservation.encode(failure));
        var payload=codec.encode(AgentExecutionProgram.COMPLETED_SCHEMA,value);
        var termination=ExecutionTerminationObservation.read(AgentExecutionProgram.COMPLETED_EVENT,payload).orElseThrow();
        assertEquals(failure,termination.budgetFailure().orElseThrow());
        assertEquals("ms",failure.shortfalls().getFirst().unit());
        assertTrue(ExecutionTerminationObservation.read("another.event",payload).isEmpty());
        assertThrows(IllegalArgumentException.class,()->ExecutionTerminationObservation.read(AgentExecutionProgram.COMPLETED_EVENT,
                new TypedPayload("application/json",payload.canonicalJson())));
        var corrupt=payload.canonicalJson().replace("\"deficit\":\"60000\"","\"deficit\":\"1\"");
        assertThrows(IllegalArgumentException.class,()->ExecutionTerminationObservation.read(AgentExecutionProgram.COMPLETED_EVENT,new TypedPayload(payload.schemaId(),corrupt)));
        var wrongUnit=payload.canonicalJson().replace("\"unit\":\"ms\"","\"unit\":\"tokens\"");
        assertThrows(IllegalArgumentException.class,()->ExecutionTerminationObservation.read(AgentExecutionProgram.COMPLETED_EVENT,new TypedPayload(payload.schemaId(),wrongUnit)));
    }
    @Test void oldCompletionKeepsMissingBudgetEvidenceAndDoesNotReinterpretReasonText() {
        var payload=new TypedPayload(AgentExecutionProgram.COMPLETED_SCHEMA,"{\"reason\":\"execution_output_closing_budget_insufficient\",\"status\":\"TERMINATED\"}");
        assertTrue(ExecutionTerminationObservation.read(AgentExecutionProgram.COMPLETED_EVENT,payload).orElseThrow().budgetFailure().isEmpty());
    }

    @Test void largeOriginalTextEvidenceAndLegalDepthDoNotLimitTerminationFacts() {
        var failure=new ClosingFailure(Phase.BUSINESS,List.of(new ClosingShortfall(BalanceScope.ROOT,"ACTIVE_MILLIS",360_000,300_000,60_000,120_000,0,300_000)),
                new ClosingTimeCalculation(2,120_000,120_000,120_000,240_000));
        String text="原".repeat(2*1024*1024);
        String evidence="["+String.join(",",java.util.Collections.nCopies(10_001,"null"))+"]";
        String nested="[".repeat(64)+"null"+"]".repeat(64);
        String json="{\"budget_failure\":"+ExecutionTerminationObservation.encode(failure)
                +",\"evidence\":"+evidence+",\"nested\":"+nested+",\"reason\":\""
                +ExecutionTerminationObservation.BUDGET_FAILURE_CODE+"\",\"status\":\"TERMINATED\",\"text\":\""+text+"\"}";
        var original=new TypedPayload(AgentExecutionProgram.COMPLETED_SCHEMA,json);
        var actual=ExecutionTerminationObservation.read(AgentExecutionProgram.COMPLETED_EVENT,original).orElseThrow();
        assertEquals("TERMINATED",actual.status());assertEquals(ExecutionTerminationObservation.BUDGET_FAILURE_CODE,actual.reason());
        assertEquals(failure,actual.budgetFailure().orElseThrow());
        assertEquals(json,original.canonicalJson(),"原持久正文不能被截断或改写");
    }
}
