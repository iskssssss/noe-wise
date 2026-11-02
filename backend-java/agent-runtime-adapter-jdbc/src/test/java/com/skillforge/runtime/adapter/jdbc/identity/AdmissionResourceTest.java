package com.skillforge.runtime.adapter.jdbc.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.TypedPayload;
import java.util.List;
import org.junit.jupiter.api.Test;

/** admission wire 的固定 shape、规范文本和领域身份负向矩阵。 */
class AdmissionResourceTest {
    private static final String SCHEMA = "skillforge.runtime.session.admission-resource.v2";
    private static final String VALID = "[\"tenant\",\"AGENT\",\"agent\",\"agent\",\"session\",\"42\",\"input\",\"event\",\"MESSAGE\","
            + "[\"new\"],\"channel\",\"source\",\"trace\",\"span\",\"different-root\",[],\"payload.v1\","
            + "\"{\\\"text\\\":\\\"hello\\\"}\",\"2026-09-10T01:02:03.123456789Z\",\"user:42\",\"\",\"\",\"origin\"]";

    @Test
    void acceptsCanonicalNewAndExistingRunsWithoutInventingTraceEquality() {
        var parsed = AdmissionResource.parse(payload(VALID)).orElseThrow();
        assertEquals("tenant", parsed.owner().sessionRef().tenantId().value());
        assertEquals("session", parsed.owner().sessionRef().sessionId());
        assertEquals("agent", parsed.owner().target().requireAgent().value());
        assertEquals(42, parsed.owner().requireUser().value());
        assertEquals("channel", parsed.channel());
        assertEquals("source", parsed.source());
        assertTrue(AdmissionResource.parse(payload(VALID.replace("[\"new\"]",
                "[\"existing\",\"tenant\",\"run\"]").replace("\"different-root\",[]",
                "\"different-root\",[\"cause\"]"))).isPresent());
        assertTrue(AdmissionResource.parse(payload(VALID.replace("\"channel\"", "\"渠道🛰️\"")
                .replace("\"source\"", "\"line\\nsource\""))).isPresent());
    }

    @Test
    void rejectsUnknownSchemaShapeAndNonCanonicalJson() {
        assertTrue(AdmissionResource.parse(null).isEmpty());
        assertTrue(AdmissionResource.parse(new TypedPayload("other.v1", VALID)).isEmpty());
        assertTrue(AdmissionResource.parse(new TypedPayload("skillforge.runtime.session.admission-resource.v1", VALID)).isEmpty());
        assertTrue(AdmissionResource.parse(payload(VALID.replace("\"AGENT\",\"agent\",", ""))).isEmpty());
        for (String invalid : List.of(
                " " + VALID, VALID + " ", VALID + "[]", VALID.substring(0, VALID.length() - 1),
                VALID.replace(",\"input\"", ""), VALID.replace("\"MESSAGE\"", "\"MESSAGE\",\"extra\""),
                VALID.replace("\"42\"", "42"), VALID.replace("\"channel\"", "null"),
                VALID.replace("[\"new\"]", "{}"), VALID.replace("[\"new\"]", "[[\"new\"]]"),
                VALID.replace("\"source\"", "\"\\u0073ource\""),
                VALID.replace("\"source\"", "\"source\\/path\""),
                VALID.replace("\"source\"", "\"source\\u000a\""),
                VALID.replace("\"source\"", "\"source\\u001F\""),
                VALID.replace("\"source\"", "\"source\n\""),
                VALID.replace("\"source\"", "\"source" + (char) 0xd800 + "\""),
                VALID.replace("\"source\"", "\"source" + (char) 0xdc00 + "\""))) {
            assertFalse(AdmissionResource.parse(payload(invalid)).isPresent(), "must reject malformed wire");
        }
    }

    @Test
    void rejectsWrongIdentityTypesTargetTraceAndTime() {
        for (String invalid : List.of(
                VALID.replace("\"42\"", "\"0\""), VALID.replace("\"42\"", "\"-1\""),
                VALID.replace("\"42\"", "\"042\""), VALID.replace("\"42\"", "\"+42\""),
                VALID.replace("\"42\"", "\"9223372036854775808\""),
                VALID.replace("\"user:42\"", "\"user:43\""),
                VALID.replace("\"user:42\"", "\"user:042\""),
                VALID.replace("\"\",\"\",\"origin\"", "\"session\",\"\",\"origin\""),
                VALID.replace("\"\",\"\",\"origin\"", "\"other\",\"message\",\"origin\""),
                VALID.replace("\"origin\"", "\"\""),
                VALID.replace("\"MESSAGE\"", "\"UNKNOWN\""),
                VALID.replace("[\"new\"]", "[]"),
                VALID.replace("[\"new\"]", "[\"new\",\"unexpected\"]"),
                VALID.replace("[\"new\"]", "[\"existing\",\"other-tenant\",\"run\"]"),
                VALID.replace("[\"new\"]", "[\"existing\",\"tenant\",\"\"]"),
                VALID.replace("\"trace\"", "\" \""),
                VALID.replace("\"span\"", "\"\""),
                VALID.replace("\"different-root\"", "\"\""),
                VALID.replace("\"different-root\",[]", "\"different-root\",[\"\"]"),
                VALID.replace("\"different-root\",[]", "\"different-root\",[\"a\",\"b\"]"),
                VALID.replace("\"payload.v1\"", "\"\""),
                VALID.replace("2026-09-10T01:02:03.123456789Z", "2026-09-10T01:02:03+00:00"),
                VALID.replace("2026-09-10T01:02:03.123456789Z", "not-an-instant"))) {
            assertTrue(AdmissionResource.parse(payload(invalid)).isEmpty(), "must reject invalid domain value");
        }
    }

    @Test
    void rejectsDeepAdversarialStructureWithoutRecursiveParsing() {
        String nested = VALID.replace("[\"new\"]", "[".repeat(20_000) + "]".repeat(20_000));
        assertTrue(AdmissionResource.parse(payload(nested)).isEmpty());
        assertTrue(AdmissionResource.parse(payload(VALID.replace("\"source\"", "\"control\\u0001ok\""))).isPresent());
    }

    private static TypedPayload payload(String wire) { return new TypedPayload(SCHEMA, wire); }
}
