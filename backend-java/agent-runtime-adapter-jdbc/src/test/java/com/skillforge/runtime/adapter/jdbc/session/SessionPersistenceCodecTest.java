package com.skillforge.runtime.adapter.jdbc.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalDouble;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.skillforge.runtime.api.port.ModelPort.GenerationOptions;
import com.skillforge.runtime.api.GroupBindingRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.spi.SessionCapabilities.Skill;
import org.junit.jupiter.api.Test;

/** Adapter 私有 codec 的无截断文本边界与损坏帧防御。 */
final class SessionPersistenceCodecTest {

    private static final int FORMER_TEXT_LIMIT_BYTES = 64 * 1024 * 1024;

    /** 新协议不会把旧冻结Profile按当前实现重解释，旧帧仍保留供只读调查。 */
    @Test void oldPreparationProfileIsRejectedWithoutRewritingItsFrame() {
        var tenant = new TenantId("profile-version-test");
        var profile = new com.skillforge.runtime.session.spi.SessionPreparations.Profile(
                new com.skillforge.runtime.api.ResultRef("profile:version-test"),
                com.skillforge.runtime.session.spi.SessionPreparations.PROFILE_VERSION,
                com.skillforge.runtime.session.spi.SessionPreparations.AGENT_INPUT_RENDERER_VERSION,
                com.skillforge.runtime.session.spi.SessionAdmissionFacts.ContextKind.AGENT_INPUT,
                com.skillforge.runtime.session.spi.SessionPrompt.single("host",
                        com.skillforge.runtime.session.spi.SessionPrompt.Type.HOST, "fixture", "测试正文"),
                new com.skillforge.runtime.api.port.ModelPort.ModelBindingRef(tenant, "binding", 1),
                new com.skillforge.runtime.api.port.ModelPort.RequestedModelStrategy("strategy", "1"),
                new com.skillforge.runtime.api.port.ModelPort.ResponseContract(
                        new com.skillforge.runtime.api.port.ModelPort.ResponseFormat.Text(),
                        com.skillforge.runtime.api.port.ModelPort.EnforcementLevel.STRICT, "fixture"),
                new GenerationOptions(OptionalInt.of(128), OptionalDouble.empty()),
                com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy.DISABLED,
                com.skillforge.runtime.api.port.PortalCallKind.MODEL_GENERATE,
                new com.skillforge.runtime.api.ResultRef("tokenizer:fixture"),
                com.skillforge.runtime.session.spi.SessionContextPolicy.defaults(),
                100_000, 200_000, 100, false, java.util.Set.of(com.skillforge.runtime.api.InboundKind.MESSAGE));
        var encoded = SessionPersistenceCodec.encode(profile);
        assertEquals(profile, SessionPersistenceCodec.decode(encoded, profile.getClass()));
        var original = Base64.getUrlDecoder().decode(encoded);
        var text = new String(original, java.nio.charset.StandardCharsets.ISO_8859_1);
        var old = text.replace("skillforge.runtime.session.preparation-profile.v4", "skillforge.runtime.session.preparation-profile.v3");
        assertTrue(!text.equals(old));
        var rejected = frame(old.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        assertThrows(IllegalStateException.class, () -> SessionPersistenceCodec.decode(rejected, profile.getClass()));
        assertEquals(encoded, frame(original));
    }

    /** 新 subject record 规范读回且复用正式构造器拒绝篡改，不回退旧 owner 字段。 */
    @Test void groupOwnerSubjectRoundTripsAndRejectsChangedTenantOrTarget() {
        var tenant = new TenantId("tenant-one");
        var group = new GroupBindingRef(tenant, "opaque-group");
        var owner = new SessionOwner(new SessionRef(tenant, "shared"), SessionSubject.group(group), SessionTarget.group(group));
        String encoded = SessionPersistenceCodec.encode(owner);
        assertEquals(owner, SessionPersistenceCodec.decode(encoded, SessionOwner.class));
        assertEquals(encoded, SessionPersistenceCodec.encode(SessionPersistenceCodec.decode(encoded, SessionOwner.class)));
        for (var drift : java.util.List.of(new String[]{"tenant-one", "tenant-two"}, new String[]{"opaque-group", "opaque-other"})) {
            String bytes = new String(Base64.getUrlDecoder().decode(encoded), java.nio.charset.StandardCharsets.ISO_8859_1);
            int position = bytes.lastIndexOf(drift[0]);
            assertTrue(position > 0);
            String changed = bytes.substring(0, position) + drift[1] + bytes.substring(position + drift[0].length());
            assertThrows(IllegalStateException.class, () -> SessionPersistenceCodec.decode(
                    frame(changed.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)), SessionOwner.class));
        }
    }

    @Test void generationOptionsPreserveAbsentAndExactFiniteDoubleBits() {
        for (var options : java.util.List.of(new GenerationOptions(OptionalInt.empty(), OptionalDouble.empty()),
                new GenerationOptions(OptionalInt.of(4096), OptionalDouble.of(0.125)))) {
            var encoded = SessionPersistenceCodec.encode(options);
            assertEquals(options, SessionPersistenceCodec.decode(encoded, GenerationOptions.class));
        }
        for (double value : new double[]{0.0, -0.0, Double.MIN_VALUE, Double.MAX_VALUE, -1.25}) {
            var original = OptionalDouble.of(value);
            var encoded = SessionPersistenceCodec.encode(original);
            assertEquals(Double.doubleToRawLongBits(value), Double.doubleToRawLongBits(
                    SessionPersistenceCodec.decode(encoded, OptionalDouble.class).orElseThrow()));
        }
        assertEquals(OptionalInt.of(Integer.MIN_VALUE), SessionPersistenceCodec.decode(
                SessionPersistenceCodec.encode(OptionalInt.of(Integer.MIN_VALUE)), OptionalInt.class));
        // SFP1旧STRING标签与正文保持原字节，新增标签不重写已有编码。
        assertEquals("U0ZQMQEAAAABeA", SessionPersistenceCodec.encode("x"));
    }

    @Test void numericOptionalFramesRejectNonFiniteNoncanonicalTruncatedAndUnknownValues() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalStateException.class, () -> SessionPersistenceCodec.encode(OptionalDouble.of(value)));
            byte[] bytes = ByteBuffer.allocate(14).put(new byte[]{'S','F','P','1',13,1}).putLong(Double.doubleToRawLongBits(value)).array();
            assertThrows(IllegalStateException.class, () -> SessionPersistenceCodec.decode(frame(bytes), OptionalDouble.class));
        }
        for (byte[] bytes : java.util.List.of(new byte[]{'S','F','P','1',12,2},
                new byte[]{'S','F','P','1',13,1,0},new byte[]{'S','F','P','1',99},
                new byte[]{'S','F','P','1',12,0,0})) {
            assertThrows(IllegalStateException.class, () -> SessionPersistenceCodec.decode(frame(bytes), Object.class));
        }
    }
    private static String frame(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }

    @Test
    void recordMetadataCacheSupportsConcurrentRoundTrips() throws Exception {
        int workerCount = 8;
        var value = new Skill("ALWAYS", 1024);
        var ready = new CountDownLatch(workerCount);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(workerCount)) {
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (int worker = 0; worker < workerCount; worker++) {
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    String canonical = null;
                    for (int iteration = 0; iteration < 64; iteration++) {
                        String encoded = SessionPersistenceCodec.encode(value);
                        assertEquals(value, SessionPersistenceCodec.decode(encoded, Skill.class));
                        if (canonical != null) {
                            assertEquals(canonical, encoded);
                        }
                        canonical = encoded;
                    }
                    return canonical;
                }));
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            String canonical = futures.getFirst().get(10, TimeUnit.SECONDS);
            for (var future : futures) {
                assertEquals(canonical, future.get(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void cachedRecordMetadataDoesNotRelaxRecordValidation() throws Exception {
        var value = new GenerationOptions(OptionalInt.empty(), OptionalDouble.empty());
        String canonical = SessionPersistenceCodec.encode(value);
        assertEquals(canonical, generationOptionsFrame(2, "maxOutputTokens"));

        var encodeFailure = assertThrows(IllegalStateException.class,
                () -> SessionPersistenceCodec.encode(new ExternalRecord("forbidden")));
        assertEquals("session_persistence_codec_failure", encodeFailure.getMessage());
        assertEquals("codec type is outside the frozen Runtime API and Session SPI",
                assertInstanceOf(IOException.class, encodeFailure.getCause()).getMessage());

        assertEquals("codec type is outside the frozen Runtime API and Session SPI",
                decodeFailure(recordHeaderFrame(ExternalRecord.class)).getMessage());
        assertEquals("codec record type is not a record",
                decodeFailure(recordHeaderFrame(GenerationOptions.class.getEnclosingClass())).getMessage());
        assertEquals("record component count changed",
                decodeFailure(generationOptionsFrame(1, "maxOutputTokens")).getMessage());
        assertEquals("record component identity changed",
                decodeFailure(generationOptionsFrame(2, "changedComponent")).getMessage());

        byte[] nonCanonicalOptional = {'S', 'F', 'P', '1', 8, 2, 1, 0, 0, 0, 1, 'x'};
        assertEquals("non-canonical codec value",
                decodeFailure(frame(nonCanonicalOptional), Optional.class).getMessage());
    }

    @Test
    void writeTextAcceptsAsciiBeyondTheFormerLimitWithoutBufferingTheFrame()
            throws Exception {
        long[] written = {0};
        try (DataOutputStream output = new DataOutputStream(new OutputStream() {
            @Override
            public void write(int value) {
                written[0]++;
            }

            @Override
            public void write(byte[] values, int offset, int length) {
                written[0] += length;
            }
        })) {
            SessionPersistenceCodec.writeText(
                    output, "x".repeat(FORMER_TEXT_LIMIT_BYTES + 1));
        }

        assertEquals(Integer.BYTES + (long) FORMER_TEXT_LIMIT_BYTES + 1,
                written[0]);
    }

    @Test
    void readTextRejectsAForgedLengthBeforeAllocatingItsClaimedPayload()
            throws Exception {
        byte[] forged = ByteBuffer.allocate(Integer.BYTES)
                .putInt(Integer.MAX_VALUE).array();
        try (DataInputStream input = new DataInputStream(
                new java.io.ByteArrayInputStream(forged))) {
            IOException failure = assertThrows(
                    IOException.class,
                    () -> SessionPersistenceCodec.readText(input));
            assertEquals("invalid codec length", failure.getMessage());
        }
    }

    private static String recordHeaderFrame(Class<?> type) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.write(new byte[]{'S', 'F', 'P', '1'});
            output.writeByte(11);
            SessionPersistenceCodec.writeText(output, type.getName());
        }
        return frame(bytes.toByteArray());
    }

    private static String generationOptionsFrame(int componentCount, String firstComponentName)
            throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.write(new byte[]{'S', 'F', 'P', '1'});
            output.writeByte(11);
            SessionPersistenceCodec.writeText(output, GenerationOptions.class.getName());
            output.writeInt(componentCount);
            if (componentCount == 2) {
                SessionPersistenceCodec.writeText(output, firstComponentName);
                output.writeByte(12);
                output.writeBoolean(false);
                SessionPersistenceCodec.writeText(output, "temperature");
                output.writeByte(13);
                output.writeBoolean(false);
            }
        }
        return frame(bytes.toByteArray());
    }

    private static IOException decodeFailure(String encoded) {
        return decodeFailure(encoded, Object.class);
    }

    private static IOException decodeFailure(String encoded, Class<?> expectedType) {
        var failure = assertThrows(IllegalStateException.class,
                () -> SessionPersistenceCodec.decode(encoded, expectedType));
        assertEquals("session_persistence_codec_failure", failure.getMessage());
        return assertInstanceOf(IOException.class, failure.getCause());
    }

    private record ExternalRecord(String value) {}
}
