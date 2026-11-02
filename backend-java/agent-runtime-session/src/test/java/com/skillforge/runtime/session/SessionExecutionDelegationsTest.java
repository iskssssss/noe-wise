package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.DelegationCommand;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.spi.SessionDelegations;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SessionExecutionDelegationsTest {
    private final SessionExecutionDelegations codec = new SessionExecutionDelegations(new CodecLimits(100_000, 10_000, 100));

    @Test void requestRoundTripUsesActualDelegationContractAndNativeCallIdentity() {
        var command = DelegationCommand.delegate("member:2", "核对真实来源", false);
        var request = new SessionExecutionDelegations.Request(command, "call:real", "delegate");
        assertEquals(request, codec.decodeRequest(codec.encodeRequest(request)));
        var encoded = codec.encodeRequest(request);
        assertThrows(IllegalArgumentException.class, () -> codec.decodeRequest(new TypedPayload(encoded.schemaId(),
                encoded.canonicalJson().replace("\"callId\":", "\"unknown\":\"field\",\"callId\":"))));
    }

    @Test void originalBusinessWaitRetainsOrderedRealChildrenAndRejectsDuplicates() {
        var first = new RunRef(new TenantId("1"), "child:one"); var second = new RunRef(new TenantId("1"), "child:two");
        var dependency = new SessionExecutionDelegations.Dependency("delegation-wait:action", List.of("one", "two"), List.of(first, second));
        var result = new SessionExecutionDelegations.Result(new TypedPayload("application/json", "{\"status\":\"waiting\"}"), Optional.of(dependency));
        assertEquals(result, codec.decodeResult(codec.encodeResult(result)));
        assertThrows(IllegalArgumentException.class, () -> new SessionExecutionDelegations.Dependency("wait", List.of("one", "two"), List.of(first, first)));
        assertThrows(IllegalArgumentException.class, () -> new SessionExecutionDelegations.Dependency("wait", List.of("one"), List.of(first, second)));
    }

    @Test void systemResponseHasDifferentSchemaAndCannotCarryAnotherWaitingDeclaration() {
        var value = new TypedPayload("application/json", "{\"results\":[]}");
        assertEquals(value, codec.decodeResponse(codec.encodeResponse(value)));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeResult(codec.encodeResponse(value)));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeResponse(codec.encodeResult(new SessionExecutionDelegations.Result(value, Optional.empty()))));
    }

    @Test void reportedLowerBoundDoesNotClaimCompleteHistoricalUsage() {
        var result = new SessionDelegations.Result(SessionDelegations.State.FAILED, "", "external_effect_outcome_unknown",
                new SessionDelegations.TokenUsage(17, false), List.of("actual-result"));
        assertEquals(17, result.usage().reportedTokens()); assertFalse(result.usage().complete());
        assertTrue(SessionDelegations.TokenUsage.known(0).complete());
        assertFalse(SessionDelegations.TokenUsage.unreported().complete());
    }
}
