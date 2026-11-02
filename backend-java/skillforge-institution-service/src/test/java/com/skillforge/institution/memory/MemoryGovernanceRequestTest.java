package com.skillforge.institution.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.institution.memory.MemoryGovernanceRequest.Operation;
import org.junit.jupiter.api.Test;

/** 治理身份摘要只依赖明确字段与UTF-8字节边界，不受JSON配置或字段拼接歧义影响。 */
class MemoryGovernanceRequestTest {
    @Test
    void fingerprintsLengthPrefixedUtf8IncludingNullAndEmptyWithStableKnownDigest() {
        // 独立向量：00000004 00000001 41 00000003 e4b8ad ffffffff 00000000。
        assertEquals("8247699cc47fe5359cb198a4252bb36801320102203dfda7fb53042bbe6d19eb",
                MemoryGovernanceRequest.fingerprint("A", "中", null, ""));
        assertNotEquals(MemoryGovernanceRequest.fingerprint("ab", "c"), MemoryGovernanceRequest.fingerprint("a", "bc"));
        assertNotEquals(MemoryGovernanceRequest.fingerprint((String) null), MemoryGovernanceRequest.fingerprint(""));
        assertNotEquals(MemoryGovernanceRequest.fingerprint("a"), MemoryGovernanceRequest.fingerprint("a", ""));
    }

    @Test
    void completeTypedRequestKeepsExplicitKeyOrDerivesItFromFullFingerprint() {
        String fingerprint = MemoryGovernanceRequest.fingerprint(Operation.REVIEW.name(), "fact-1", "3", "true", "USER");
        var explicit = new MemoryGovernanceRequest("101", "fact-1", 3, Operation.REVIEW, "original-key", fingerprint);
        assertEquals(Operation.REVIEW, explicit.operation());
        assertEquals("original-key", explicit.key());
        var omitted = new MemoryGovernanceRequest("101", "fact-1", 3, Operation.REVIEW, null, fingerprint);
        assertEquals("memory-governance:auto:" + fingerprint, omitted.key());
        assertEquals(omitted.key(), new MemoryGovernanceRequest("101", "fact-1", 3,
                Operation.REVIEW, " ", fingerprint).key());
    }

    @Test
    void invalidRequestCannotEnterReceiptPersistence() {
        assertThrows(IllegalArgumentException.class, () -> new MemoryGovernanceRequest(" ", "fact", 1, Operation.REVIEW, null, "digest"));
        assertThrows(IllegalArgumentException.class, () -> new MemoryGovernanceRequest("101", " ", 1, Operation.REVIEW, null, "digest"));
        assertThrows(IllegalArgumentException.class, () -> new MemoryGovernanceRequest("101", "fact", 0, Operation.REVIEW, null, "digest"));
        assertThrows(NullPointerException.class, () -> new MemoryGovernanceRequest("101", "fact", 1, null, null, "digest"));
        assertThrows(IllegalArgumentException.class, () -> new MemoryGovernanceRequest("101", "fact", 1, Operation.REVIEW, null, " "));
    }
}
