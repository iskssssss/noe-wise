package com.skillforge.sandbox.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.execution.WorkspaceBinding;
import com.skillforge.execution.WorkspaceCommand;
import com.skillforge.execution.WorkspacePolicy;
import com.skillforge.execution.WorkspaceSnapshot;
import com.skillforge.sandbox.api.SandboxConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 真实项目卷与持久回执验证；云端只隔离外部网络，不替代本地文件恢复事实。 */
class SessionWorkspacesTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
    private final WorkspaceBinding binding = new WorkspaceBinding("a".repeat(64), "tenant-test", "runtime-test",
            1L, "product-test", new WorkspacePolicy(1, 300, 24, 10));
    private final List<SessionWorkspaces> services = new ArrayList<>();

    /** 每个用例拥有后台调度器，失败也关闭；工作测试使用手动入口避免定时竞争。 */
    @AfterEach
    void closeServices() {
        services.forEach(SessionWorkspaces::shutdown);
    }

    /** 同 operationId 回读事实；过期基准和不同请求重用不会执行文件修改。 */
    @Test
    void returnsReceiptWithoutRepeatingWriteAndRejectsStaleRevision() throws IOException {
        SessionWorkspaces service = service();
        WorkspaceCommand first = command("create", "write", "a.txt", "initial", null, null);
        Map<String, Object> original = service.operate(first);
        long revision = service.status(binding.workspaceId()).revision();
        Map<String, Object> repeated = service.operate(first);
        assertThat(repeated.get("sha256")).isEqualTo(original.get("sha256"));
        assertThat(((Number) repeated.get("revision")).longValue()).isEqualTo(revision);
        assertThat(service.status(binding.workspaceId()).revision()).isEqualTo(revision);
        assertThatThrownBy(() -> service.operate(command("create", "write", "a.txt", "different", revision, null)))
                .isInstanceOf(SandboxConflictException.class).hasMessageContaining("操作标识");
        assertThatThrownBy(() -> service.operate(command("stale", "write", "a.txt", "stale", revision - 1, null)))
                .isInstanceOf(SandboxConflictException.class).hasMessageContaining("修订");
        assertThat(Files.readString(project().resolve("a.txt"))).isEqualTo("initial");
        assertThat(service.status(binding.workspaceId()).revision()).isEqualTo(revision);
    }

    /** 准入写入失败仍推进修订；同请求只能返回失败事实，不能在稍后重试修改。 */
    @Test
    void retainsFailedWriteRevisionAndDoesNotReplayFailedOperation() throws IOException {
        SessionWorkspaces service = service();
        service.operate(command("create", "write", "a.txt", "old", null, null));
        long before = service.status(binding.workspaceId()).revision();
        WorkspaceCommand rejected = command("missing-baseline", "write", "a.txt", "new", null, null);
        assertThatThrownBy(() -> service.operate(rejected)).isInstanceOf(SandboxConflictException.class);
        long after = service.status(binding.workspaceId()).revision();
        assertThat(after).isEqualTo(before + 1);
        assertThat(service.operate(rejected)).containsEntry("outcome", "failed_or_partial");
        assertThat(service.status(binding.workspaceId()).revision()).isEqualTo(after);
        assertThat(Files.readString(project().resolve("a.txt"))).isEqualTo("old");
    }

    /** 用等待回调证明锁竞争，取消等待者不写入；之后写者才获得排他权。 */
    @Test
    void cancelsRealLockWaitBeforeAllowingSubsequentWriter() throws Exception {
        SessionWorkspaces service = service();
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch waiting = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            try (var first = service.acquire(binding, deadline(), () -> false)) {
                first.beginWrite("first-writer");
                var blocked = executor.submit(() -> {
                    try (var ignored = service.acquire(binding, deadline(), () -> {
                        waiting.countDown();
                        return cancelled.get();
                    })) {
                        return "unexpected-acquire";
                    }
                });
                assertThat(waiting.await(10, TimeUnit.SECONDS)).isTrue();
                cancelled.set(true);
                assertThatThrownBy(() -> blocked.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasRootCauseInstanceOf(SandboxConflictException.class);
                Files.writeString(project().resolve("order.txt"), "first");
            }
            var second = executor.submit(() -> {
                try (var lease = service.acquire(binding, deadline(), () -> false)) {
                    String previous = Files.readString(project().resolve("order.txt"));
                    lease.beginWrite("second-writer");
                    Files.writeString(project().resolve("order.txt"), previous + ",second");
                    return previous;
                }
            });
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("first");
        }
        assertThat(Files.readString(project().resolve("order.txt"))).isEqualTo("first,second");
    }

    /** 重新构造服务读取真实 state/receipt，不依赖前一个实例内存。 */
    @Test
    void rebuildsFromPersistentFactsAndKeepsOriginalReceipt() throws IOException {
        SessionWorkspaces original = service();
        WorkspaceBinding initialized = new WorkspaceBinding(binding.workspaceId(), binding.tenantId(), binding.sessionId(),
                binding.endUserId(), binding.productSessionId(), binding.policy(), false);
        assertThatThrownBy(() -> original.acquire(initialized, deadline(), () -> false))
                .isInstanceOf(SandboxConflictException.class)
                .satisfies(failed -> assertThat(((SandboxConflictException) failed).code()).isEqualTo("WORKSPACE_STATE_MISSING"));
        assertThat(Files.exists(project())).isFalse();
        WorkspaceCommand write = command("durable-write", "write", "a.txt", "durable", null, null);
        Map<String, Object> response = original.operate(write);
        long revision = original.status(binding.workspaceId()).revision();
        original.shutdown();
        SessionWorkspaces rebuilt = service();
        Map<String, Object> repeated = rebuilt.operate(write);
        assertThat(repeated.get("sha256")).isEqualTo(response.get("sha256"));
        assertThat(rebuilt.status(binding.workspaceId()).revision()).isEqualTo(revision);
        assertThat(Files.readString(project().resolve("a.txt"))).isEqualTo("durable");
        assertThat(rebuilt.operate(command("read-after-rebuild", "read", "a.txt", null, null, null)))
                .containsEntry("content", "durable");
        try (var ignored = rebuilt.acquire(initialized, deadline(), () -> false)) {
            assertThat(Files.readString(project().resolve("a.txt"))).isEqualTo("durable");
        }
    }

    /** 恢复保留当前版本快照；合法 ZIP 整体摘要不符时不能覆盖可用项目。 */
    @Test
    void restoresSnapshotAndRejectsWrongObjectWithoutOverwritingProject() throws IOException {
        SessionWorkspaces service = service();
        service.operate(command("first", "write", "a.txt", "version-one", null, null));
        service.operate(command("snapshot", "checkpoint_create", null, null, null, null));
        WorkspaceSnapshot selected = service.status(binding.workspaceId()).snapshots().getFirst();
        Path snapshot = root.resolve(binding.workspaceId()).resolve("snapshots").resolve(selected.id() + ".zip");
        String originalSha = WorkspaceFiles.sha256(snapshot);
        long firstRevision = service.status(binding.workspaceId()).revision();
        service.operate(command("second", "write", "a.txt", "version-two", firstRevision, null));
        long beforeRestore = service.status(binding.workspaceId()).revision();
        service.operate(command("restore", "checkpoint_restore", null, null, beforeRestore, selected.id()));
        assertThat(Files.readString(project().resolve("a.txt"))).isEqualTo("version-one");
        assertThat(WorkspaceFiles.sha256(snapshot)).isEqualTo(originalSha);
        assertThat(service.status(binding.workspaceId()).snapshots()).hasSizeGreaterThanOrEqualTo(2);

        Path otherProject = Files.createDirectory(root.resolve("other-project"));
        Files.writeString(otherProject.resolve("a.txt"), "untrusted-version");
        Path otherZip = root.resolve("other.zip");
        new WorkspaceArchive(json).freeze(otherProject, otherZip, 1);
        Files.copy(otherZip, snapshot, StandardCopyOption.REPLACE_EXISTING);
        long beforeRejectedRestore = service.status(binding.workspaceId()).revision();
        assertThatThrownBy(() -> service.operate(command("reject-restore", "checkpoint_restore", null, null,
                beforeRejectedRestore, selected.id())))
                .isInstanceOf(SandboxConflictException.class).hasMessageContaining("整体摘要");
        assertThat(Files.readString(project().resolve("a.txt"))).isEqualTo("version-one");
    }

    /** 归档释放项目和已上传 ZIP；重新访问只从云端完整快照恢复原文件。 */
    @Test
    void archivesLocalFilesAndDownloadsVerifiedSnapshotOnNextAcquire() throws IOException {
        UpyunWorkspaceStore cloud = mock(UpyunWorkspaceStore.class);
        when(cloud.configured()).thenReturn(true);
        AtomicReference<Instant> now = new AtomicReference<>(clock.instant());
        Clock movingClock = mock(Clock.class);
        when(movingClock.instant()).thenAnswer(ignored -> now.get());
        Path remoteZip = root.resolve("remote-snapshot.zip");
        AtomicReference<String> remoteKey = new AtomicReference<>();
        doAnswer(invocation -> {
            remoteKey.set(invocation.getArgument(0, String.class));
            Files.copy(invocation.getArgument(1, Path.class), remoteZip, StandardCopyOption.REPLACE_EXISTING);
            return null;
        }).when(cloud).upload(anyString(), any(Path.class));
        doAnswer(invocation -> {
            assertThat(invocation.getArgument(0, String.class)).isEqualTo(remoteKey.get());
            Files.copy(remoteZip, invocation.getArgument(1, Path.class), StandardCopyOption.REPLACE_EXISTING);
            return null;
        }).when(cloud).download(anyString(), any(Path.class));
        SessionWorkspaces service = service(cloud, movingClock);
        service.operate(command("write", "write", "a.txt", "cloud-backed", null, null));
        service.operate(command("snapshot", "checkpoint_create", null, null, null, null));
        service.maintain();
        WorkspaceSnapshot saved = service.status(binding.workspaceId()).snapshots().getFirst();
        assertThat(saved.ready()).isTrue();
        Path localZip = root.resolve(binding.workspaceId()).resolve("snapshots").resolve(saved.id() + ".zip");
        assertThat(Files.exists(localZip)).isTrue();
        now.set(now.get().plusSeconds(binding.policy().localIdleHours() * 3600L + 1));
        service.maintain();
        assertThat(service.status(binding.workspaceId()).localAvailable()).isFalse();
        assertThat(Files.exists(project())).isFalse();
        assertThat(Files.exists(localZip)).isFalse();
        try (var ignored = service.acquire(binding, now.get().plusSeconds(600), () -> false)) {
            assertThat(Files.readString(project().resolve("a.txt"))).isEqualTo("cloud-backed");
        }
        assertThat(service.status(binding.workspaceId()).localAvailable()).isTrue();
        assertThat(WorkspaceFiles.sha256(localZip)).isEqualTo(saved.sha256());
        verify(cloud).download(eq(remoteKey.get()), eq(localZip));
    }

    /** 配置缺失不能遗忘远端对象；恢复配置后清理并保留墓碑阻止重新创建。 */
    @Test
    void retainsRemoteCleanupIntentUntilCloudConfigurationReturns() throws IOException {
        UpyunWorkspaceStore cloud = mock(UpyunWorkspaceStore.class);
        AtomicBoolean configured = new AtomicBoolean(true);
        when(cloud.configured()).thenAnswer(ignored -> configured.get());
        SessionWorkspaces service = service(cloud, clock);
        service.operate(command("write", "write", "a.txt", "keep-until-cleaned", null, null));
        service.operate(command("snapshot", "checkpoint_create", null, null, null, null));
        service.maintain();
        assertThat(service.status(binding.workspaceId()).snapshots().getFirst().ready()).isTrue();
        configured.set(false);
        assertThat(service.delete(binding.workspaceId())).isFalse();
        service.maintain();
        assertThat(service.status(binding.workspaceId()).phase()).isEqualTo("deleting");
        assertThat(service.status(binding.workspaceId()).snapshots()).hasSize(1);
        assertThat(Files.readString(project().resolve("a.txt"))).isEqualTo("keep-until-cleaned");
        verify(cloud, never()).delete(anyString());

        configured.set(true);
        service.maintain();
        assertThat(service.status(binding.workspaceId()).phase()).isEqualTo("deleted");
        assertThat(service.status(binding.workspaceId()).snapshots()).isEmpty();
        assertThat(Files.exists(project())).isFalse();
        verify(cloud).delete(anyString());
        assertThat(Files.exists(root.resolve(binding.workspaceId()).resolve("deleted.json"))).isTrue();
        SessionWorkspaces rebuilt = service(cloud, clock);
        assertThatThrownBy(() -> rebuilt.acquire(binding, deadline(), () -> false))
                .isInstanceOf(SandboxConflictException.class).hasMessageContaining("撤销写权");
        assertThat(Files.exists(project())).isFalse();
    }

    /** 仅云端边界 Mock；项目卷、清单与状态/回执序列化都使用真实组件。 */
    private SessionWorkspaces service() {
        return service(mock(UpyunWorkspaceStore.class), clock);
    }

    /** 注入可控云端和时钟，维护入口由用例手动触发，不依赖定时等待。 */
    private SessionWorkspaces service(UpyunWorkspaceStore cloud, Clock executionClock) {
        WorkspaceCloudProperties properties = new WorkspaceCloudProperties(root, "test-projects",
                URI.create("https://v0.api.upyun.com"), "", "", "", 1);
        var result = new SessionWorkspaces(json, properties, new WorkspaceFiles(json), new WorkspaceArchive(json),
                cloud, executionClock);
        services.add(result);
        result.recoverAfterProcessesStopped();
        result.shutdown();
        return result;
    }

    /** 当前用例项目子目录，不允许测试借助其他会话文件。 */
    private Path project() {
        return root.resolve(binding.workspaceId()).resolve("project");
    }

    /** 保留同请求的稳定身份，deadline 不参与持久幂等摘要。 */
    private WorkspaceCommand command(String id, String operation, String path, String content, Long revision, String snapshot) {
        return new WorkspaceCommand(id, binding, operation, path, null, content, revision, null, null, null, snapshot, deadline());
    }

    /** 从当前冻结时钟取得宽于测试预算的期限，文件工具也可用真实时钟检查。 */
    private Instant deadline() {
        return clock.instant().plusSeconds(600);
    }
}
