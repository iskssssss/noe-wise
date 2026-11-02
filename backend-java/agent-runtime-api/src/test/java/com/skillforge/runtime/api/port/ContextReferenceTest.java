package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContextReferenceTest {
    private static final SessionOwner OWNER = SessionOwner.user(new SessionRef(new TenantId("1"), "s"),
            new AgentId("a"), new EndUserId(1));

    @Test void onlyInternalKeysAreAcceptedAndCollectionCompletenessIsIndependentOfLength() {
        var digest = new ResultRef("sha256:" + "a".repeat(64));
        for (String invalid : new String[]{"https://example.test/data", "C:/data/result", "../result", "ticket:secret"}) {
            assertThrows(IllegalArgumentException.class, () -> new ContextReference(invalid, OWNER,
                    new EventId("source"), digest, 4, true));
        }
        var reference = new ContextReference("context:" + "b".repeat(64), OWNER, new EventId("source"), digest, 4, false);
        assertFalse(reference.sourceComplete());
        assertEquals(4, reference.characters());
        assertFalse(reference.toString().contains(reference.referenceId()));
        assertNotEquals(reference, new ContextReference(reference.referenceId(),
                SessionOwner.user(OWNER.sessionRef(), OWNER.target().requireAgent(), new EndUserId(2)),
                reference.sourceEventId(), digest, 4, false));
    }
}
