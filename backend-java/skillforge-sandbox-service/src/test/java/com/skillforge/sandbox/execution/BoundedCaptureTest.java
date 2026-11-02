package com.skillforge.sandbox.execution;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.execution.ExecutionPolicy;
import com.skillforge.execution.ExecutionProfile;
import com.skillforge.execution.ExecutionState;
import com.skillforge.execution.Language;
import com.skillforge.sandbox.api.InternalExecutionController;
import com.skillforge.sandbox.persistence.TaskRecord;
import com.skillforge.sandbox.persistence.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import jakarta.validation.Validation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 字节容量切口、原始输出及实际持久日志消费者的定向回归。 */
class BoundedCaptureTest {

    /** 文件持久验证使用每次测试独占的临时目录。 */
    @TempDir Path temporary;

    /** 未超限和刚好等于上限的 ASCII 不应误报截断。 */
    @Test
    void asciiBelowAndAtLimitRemainsCompleteWithoutTruncation() throws Exception {
        for (int limit : new int[]{3, 8}) {
            byte[] source = utf8("abc");
            BoundedCapture capture = capture(source, limit, 2);

            assertEquals("abc", capture.text());
            assertArrayEquals(source, capture.bytes());
            assertFalse(capture.truncated());
        }
    }

    /** InputStream 分块不能破坏完整 UTF-8 内容。 */
    @Test
    void completeUtf8IsIndependentOfOneByteReadBoundaries() throws Exception {
        byte[] source = utf8("A中😀Z");
        BoundedCapture capture = capture(source, source.length, 1);

        assertEquals("A中😀Z", capture.text());
        assertArrayEquals(source, capture.bytes());
        assertFalse(capture.truncated());
    }

    /** 两/三字节字符的各个不完整切口只从文本舍弃，raw 前缀必须保持。 */
    @Test
    void truncatedTwoAndThreeByteCharactersAreExcludedFromTextButRetainedAsRawPrefix() throws Exception {
        for (String character : new String[]{"é", "中"}) {
            byte[] source = utf8("A" + character + "Z");
            for (int limit = 2; limit < utf8("A" + character).length; limit++) {
                BoundedCapture capture = capture(source, limit, 1);

                assertEquals("A", capture.text());
                assertArrayEquals(Arrays.copyOf(source, limit), capture.bytes());
                assertTrue(capture.truncated());
                assertTrue(utf8(capture.text()).length <= limit);
            }
        }
    }

    /** 四字节字符的任意未完成切口不得生成替代字符扩张输出。 */
    @Test
    void truncatedFourByteCharacterIsExcludedAtEveryIncompleteBoundary() throws Exception {
        byte[] source = utf8("ok😀done");
        for (int limit : new int[]{3, 4, 5}) {
            BoundedCapture capture = capture(source, limit, 2);

            assertEquals("ok", capture.text());
            assertArrayEquals(Arrays.copyOf(source, limit), capture.bytes());
            assertTrue(capture.truncated());
            assertTrue(utf8(capture.text()).length <= limit);
        }
    }

    /** 真实输入中的 U+FFFD 是完整字符，不能因修复被删除。 */
    @Test
    void truncationAtCompleteCharacterBoundaryRetainsRealReplacementCharacter() throws Exception {
        byte[] source = utf8("中😀�tail");
        BoundedCapture capture = capture(source, 10, 3);

        assertEquals("中😀�", capture.text());
        assertArrayEquals(Arrays.copyOf(source, 10), capture.bytes());
        assertEquals(10, utf8(capture.text()).length);
        assertTrue(capture.truncated());
    }

    /** 原 AT02 程序的确定性新重现，不冒充缺失的历史 raw 输出。 */
    @Test
    void at02OutputPatternStaysWithin4096BytesWithoutSyntheticReplacement() throws Exception {
        byte[] source = utf8("复杂日志✓".repeat(4000) + "\n");
        BoundedCapture capture = capture(source, 4096, 7);

        assertEquals("复杂日志✓".repeat(273), capture.text());
        assertEquals(4095, utf8(capture.text()).length);
        assertArrayEquals(Arrays.copyOf(source, 4096), capture.bytes());
        assertFalse(capture.text().contains("�"));
        assertTrue(capture.truncated());
    }

    /** 超限后仍排空进程管道，但不继续累积正文。 */
    @Test
    void drainsAllOverflowAndNeverCachesBytesAfterTheLimit() throws Exception {
        byte[] source = utf8("A中😀Z" + "x".repeat(100_000));
        ChunkedInputStream input = new ChunkedInputStream(source, 17);
        BoundedCapture capture = new BoundedCapture(5);

        capture.drain(input);

        assertEquals(source.length, input.consumed());
        assertEquals(0, input.available());
        assertArrayEquals(Arrays.copyOf(source, 5), capture.bytes());
        assertEquals("A中", capture.text());
        assertTrue(capture.truncated());
        capture.drain(new ByteArrayInputStream(utf8("later output")));
        assertArrayEquals(Arrays.copyOf(source, 5), capture.bytes());
        assertEquals("A中", capture.text());
    }

    /** 非容量截断的非法 raw 仍须供 Workflow 严格解码拒绝。 */
    @Test
    void untruncatedMalformedRawRemainsAvailableToStrictConsumers() throws Exception {
        byte[] source = new byte[]{(byte) 0xc3, 0x28};
        BoundedCapture capture = capture(source, 16, 1);

        assertEquals("�(", capture.text());
        assertArrayEquals(source, capture.bytes());
        assertFalse(capture.truncated());
        assertThrows(CharacterCodingException.class,
                () -> StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(capture.bytes())));
    }

    /** 实际文件仓库重建及日志 JSON 读回保持完整前缀、截断标志和原字符游标。 */
    @Test
    void persistentStdoutAndStderrLogReadbackKeepsUtf8LimitAndCharacterCursor() throws Exception {
        byte[] source = utf8("复杂日志✓".repeat(4000) + "\n");
        BoundedCapture stdout = capture(source, 4096, 7);
        BoundedCapture stderr = capture(source, 4096, 1);
        String expected = "复杂日志✓".repeat(273);
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        var properties = TestSandboxProperties.create(temporary);
        var base = TestSandboxProperties.policy();
        var policy = new ExecutionPolicy(base.revision(), base.executionTimeoutSeconds(),
                base.dockerControlTimeoutSeconds(), base.workspaceRetentionHours(),
                base.workspaceCleanupIntervalMinutes(), 4096, base.maxArtifactFiles(),
                base.maxConcurrentExecutions(), base.memoryLimit(), base.cpuLimit(), base.pidsLimit());
        Instant now = Instant.parse("2026-10-14T00:00:00Z");
        var record = new TaskRecord("utf8-readback", "digest", Language.PYTHON, false, List.of(),
                now.plusSeconds(60), ExecutionState.COMPLETED, "completed", null, 0,
                stdout.text(), stderr.text(), stdout.truncated(), stderr.truncated(),
                null, null, List.of(), null, now, now, policy, ExecutionProfile.STANDARD,
                null, properties.pythonImage(), "sha256:" + "1".repeat(64), null);
        assertTrue(new TaskRepository(json, properties).register(record).created());
        var repository = new TaskRepository(json, properties);
        var reloaded = repository.find(record.taskId()).orElseThrow();
        assertEquals(record, reloaded);
        var workspace = new TaskWorkspace(properties);
        Files.createDirectories(workspace.output(record.taskId()));
        var artifacts = new ArtifactCollector(workspace);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var validators = Validation.buildDefaultValidatorFactory()) {
            // 只调用终态日志读取；不装配执行器/会话项目，不调用 submit 或恢复生命周期。
            var executions = new ExecutionCoordinator(new RequestPolicy(json), repository, workspace,
                    null, artifacts, new ProgramToolExchange(json, workspace), executor,
                    Clock.fixed(now, ZoneOffset.UTC), null);
            var controller = new InternalExecutionController(executions, artifacts,
                    json, validators.getValidator());
            for (String stream : List.of("stdout", "stderr")) {
                var page = controller.logs(record.taskId(), stream, 0, 32768);
                var serialized = json.readTree(json.writeValueAsBytes(page));
                assertEquals(expected, serialized.get("text").asText());
                assertEquals(4095, utf8(serialized.get("text").asText()).length);
                assertEquals(1365, page.nextOffset());
                assertTrue(page.truncated());
                assertFalse(page.hasMore());
                var end = controller.logs(record.taskId(), stream, page.nextOffset(), 32768);
                assertEquals("", end.text());
                assertEquals(page.nextOffset(), end.nextOffset());
            }
        }
    }

    /** 用指定分块大小读取合法或负向的 raw 输入。 */
    private static BoundedCapture capture(byte[] source, int limit, int chunkSize) throws Exception {
        BoundedCapture capture = new BoundedCapture(limit);
        capture.drain(new ChunkedInputStream(source, chunkSize));
        return capture;
    }

    /** 断言依据为 UTF-8 字节数量，不使用 Java String 长度替代。 */
    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 可观察全部消费位置的有限分块流，不使用时间等待推断排空。 */
    private static final class ChunkedInputStream extends ByteArrayInputStream {
        private final int chunkSize;

        /** 每次 read 只返回不超过 chunkSize 的可用字节。 */
        private ChunkedInputStream(byte[] source, int chunkSize) {
            super(source);
            this.chunkSize = chunkSize;
        }

        /** 在真实 ByteArrayInputStream 上约束分块，不改变 EOF 语义。 */
        @Override
        public synchronized int read(byte[] target, int offset, int length) {
            return super.read(target, offset, Math.min(length, chunkSize));
        }

        /** 返回已经从输入流消费的原始字节数量。 */
        private int consumed() {
            return pos;
        }
    }
}
