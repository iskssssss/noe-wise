package com.skillforge.core.agent.systemagent;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.*;
import com.skillforge.core.agent.memory.MemoryJsonCodec;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 群协议明确区分作者、描述对象和公共事项，不授予模型跨群身份。 */
class GroupMemoryExtractionCandidatesTest {
    private final MemoryExtractionCandidates contract = new MemoryExtractionCandidates();
    private final Instant time = Instant.parse("2026-10-23T00:00:00Z");
    private ExtractionInput group() {
        return contract.typedGroupInput(7, new GroupContext("42", List.of(7L, 8L)),
                List.of(new Source("s0", "孩子 user:8 偏好短信。群通知在下午发送。", time)), List.of());
    }
    private Candidate candidate(String scope, String subject) {
        return new Candidate("preference", subject, "通知", JsonNodeFactory.instance.objectNode().put("text", "短信"),
                "observed", false, scope, new Temporal("stable", null, null), List.of(),
                List.of(new Evidence("s0", "偏好短信")), new Relation("new", null), List.of());
    }
    private String output(int version, Candidate candidate) {
        return MemoryJsonCodec.encode(new ExtractionOutput(version, List.of(candidate)));
    }
    @Test void thirdPartyAndPublicSubjectsRemainSeparateFromSourceAuthor() {
        var child = contract.parse(output(3, candidate("group_member", "user:8")), group()).getFirst();
        assertEquals("user:8", child.subject());
        assertEquals(7, group().userId());
        assertEquals("group:42", contract.parse(output(3, candidate("group", "group:42")), group()).getFirst().subject());
        assertEquals(group(), contract.decodeInput(contract.encodeInput(group())));
    }
    @Test void foreignMemberOtherGroupPersonalScopeAndWrongVersionAreRejected() {
        for (var candidate : List.of(candidate("group_member", "user:9"), candidate("group", "group:99"),
                candidate("user_agent", "user:7"), candidate("user", "user:7"))) {
            assertThrows(IllegalArgumentException.class, () -> contract.parse(output(3, candidate), group()));
        }
        assertThrows(IllegalArgumentException.class, () -> contract.parse(output(2, candidate("group_member", "user:8")), group()));
        var personal = contract.typedInput(7, group().sources(), List.of());
        assertFalse(contract.encodeInput(personal).contains("group"));
        assertThrows(IllegalArgumentException.class, () -> contract.parse(output(2, candidate("group_member", "user:7")), personal));
    }
    @Test void groupWithMoreThanSixtyFourMembersUsesTotalInputBudgetInsteadOfMemberCountGate() {
        var subjects = java.util.stream.LongStream.rangeClosed(1, 80).boxed().toList();
        var input = contract.typedGroupInput(7, new GroupContext("42", subjects), group().sources(), List.of());
        assertEquals(80, contract.decodeInput(contract.encodeInput(input)).group().subjectEndUserIds().size());
        assertEquals("user:80", contract.parse(output(3, candidate("group_member", "user:80")), input).getFirst().subject());
    }

    @Test void invalidSubjectSetsAndVersionsCannotEnterFrozenInput() {
        assertThrows(IllegalArgumentException.class, () -> new GroupContext("42", List.of(7L, 7L)));
        assertThrows(IllegalArgumentException.class, () -> new GroupContext("42", List.of(0L)));
        assertThrows(IllegalArgumentException.class, () -> new ExtractionInput(2, 7, group().sources(), List.of(), group().group()));
        assertThrows(IllegalArgumentException.class, () -> new ExtractionInput(3, 7, group().sources(), List.of()));
    }
}
