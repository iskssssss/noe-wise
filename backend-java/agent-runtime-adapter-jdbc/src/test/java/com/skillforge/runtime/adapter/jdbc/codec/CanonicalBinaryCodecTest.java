package com.skillforge.runtime.adapter.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.GroupBindingRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.port.SessionOwner;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class CanonicalBinaryCodecTest {

    private static final List<FormatFixture> FORMATS = List.of(
            new FormatFixture("SFQ1", "work_queue_codec_failure",
                    "U0ZRMQEAAAAHZml4dHVyZQ"),
            new FormatFixture("SFS1", "work_queue_codec_failure",
                    "U0ZTMQEAAAAHZml4dHVyZQ"),
            new FormatFixture("SFC1", "callback_codec_failure",
                    "U0ZDMQEAAAAHZml4dHVyZQ"),
            new FormatFixture("SFP1", "runtime_event_codec_failure",
                    "U0ZQMQEAAAAHZml4dHVyZQ"));

    /** 封闭群 subject 采用当前 record 结构，不需伪造 EndUser 或注册另一份私有群类型。 */
    @Test void groupSubjectUsesCanonicalCurrentRecordShape() {
        var tenant = new TenantId("tenant");
        var group = new GroupBindingRef(tenant, "opaque-group");
        var owner = new SessionOwner(new SessionRef(tenant, "shared"), SessionSubject.group(group), SessionTarget.group(group));
        for (FormatFixture format : FORMATS) {
            var codec = format.codec();
            String encoded = codec.encode(owner);
            assertEquals(owner, codec.decode(encoded, SessionOwner.class));
            assertEquals(encoded, codec.encode(codec.decode(encoded, SessionOwner.class)));
        }
    }

    @Test
    void preservesEveryPersistedFormatMagicAndCanonicalGoldenValue() {
        for (FormatFixture fixture : FORMATS) {
            CanonicalBinaryCodec codec = fixture.codec();
            assertEquals(fixture.golden(), codec.encode("fixture"), fixture.magic());
            assertEquals("fixture", codec.decode(fixture.golden(), String.class),
                    fixture.magic());
        }
    }

    @Test
    void roundTripsTheFrozenRuntimeTypesAndContainerTagsCanonically() {
        CanonicalBinaryCodec codec = FORMATS.get(0).codec();
        List<?> value = List.of(
                "完整-🧭",
                42L,
                7,
                true,
                Instant.ofEpochSecond(123, 456),
                Duration.ofSeconds(9, 10),
                Optional.of(new TenantId("tenant-main")),
                Set.of("beta", "alpha"),
                new TenantId("tenant-main"));

        String encoded = codec.encode(value);
        List<?> decoded = codec.decode(encoded, List.class);

        assertEquals(value, decoded);
        assertEquals(encoded, codec.encode(decoded));
    }

    @Test
    void rejectsWrongMagicTrailingBytesAndNonCanonicalBase64WithFormatError() {
        for (int index = 0; index < FORMATS.size(); index++) {
            FormatFixture fixture = FORMATS.get(index);
            CanonicalBinaryCodec codec = fixture.codec();
            String wrongMagic = FORMATS.get((index + 1) % FORMATS.size()).golden();
            assertFailure(codec, wrongMagic, fixture.failureCode(), "invalid codec magic");

            byte[] bytes = Base64.getUrlDecoder().decode(fixture.golden());
            String trailing = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(Arrays.copyOf(bytes, bytes.length + 1));
            assertFailure(codec, trailing, fixture.failureCode(), "trailing codec data");

            assertFailure(codec, fixture.golden() + "==", fixture.failureCode(),
                    "non-canonical codec value");
        }
    }

    @Test
    void rejectsTypesOutsideTheFrozenRuntimeApiAllowlist() {
        for (FormatFixture fixture : FORMATS) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> fixture.codec().encode(new LocalRecord("forbidden")));
            assertEquals(fixture.failureCode(), failure.getMessage());
            assertTrue(hasCauseMessage(failure,
                    "codec type is outside the frozen Runtime API and Session SPI"));
        }
    }

    private static void assertFailure(CanonicalBinaryCodec codec, String encoded,
            String failureCode, String causeMessage) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> codec.decode(encoded, String.class));
        assertEquals(failureCode, failure.getMessage());
        assertTrue(hasCauseMessage(failure, causeMessage));
    }

    private static boolean hasCauseMessage(Throwable failure, String expected) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(expected)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record FormatFixture(String magic, String failureCode, String golden) {
        CanonicalBinaryCodec codec() {
            return new CanonicalBinaryCodec(magic.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    failureCode);
        }
    }

    private record LocalRecord(String value) {}
}
