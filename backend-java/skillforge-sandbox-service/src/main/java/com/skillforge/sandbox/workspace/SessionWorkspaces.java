package com.skillforge.sandbox.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.execution.WorkspaceBinding;
import com.skillforge.execution.WorkspaceCommand;
import com.skillforge.execution.WorkspaceSnapshot;
import com.skillforge.execution.WorkspaceStatus;
import com.skillforge.sandbox.api.SandboxConflictException;
import com.skillforge.sandbox.api.SandboxRequestException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** 单节点工作区事实所有者：排他写权、持久操作回执、版本快照与删除墓碑。 */
@Service
public class SessionWorkspaces {
    private static final Logger log = LoggerFactory.getLogger(SessionWorkspaces.class);
    private final ObjectMapper json;
    private final WorkspaceCloudProperties properties;
    private final WorkspaceFiles files;
    private final WorkspaceArchive archive;
    private final UpyunWorkspaceStore cloud;
    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService worker = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("workspace-backup").factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean recovered;

    /** 读取项目卷的当前格式；进程确认停止前不开放上次未结束的写权。 */
    public SessionWorkspaces(ObjectMapper json, WorkspaceCloudProperties properties, WorkspaceFiles files,
                             WorkspaceArchive archive, UpyunWorkspaceStore cloud, Clock clock) {
        log.debug("初始化会话工作区事实入口");
        this.json = json; this.properties = properties; this.files = files;
        this.archive = archive; this.cloud = cloud; this.clock = clock;
        try {
            Files.createDirectories(properties.projectRoot());
            try (var paths = Files.list(properties.projectRoot())) {
                for (Path path : paths.filter(p -> p.getFileName().toString().matches("[0-9a-f]{64}")).toList()) {
                    WorkspaceFiles.requireDirectory(path);
                    Path record = path.resolve("state.json");
                    if (Files.exists(record, LinkOption.NOFOLLOW_LINKS)) {
                        WorkspaceFiles.requireFile(record);
                        State state = json.readValue(record.toFile(), State.class);
                        if (!path.getFileName().toString().equals(state.binding.workspaceId()))
                            throw new IOException("工作区持久身份不符");
                        entries.put(state.binding.workspaceId(), new Entry(state));
                    }
                }
            }
        } catch (IOException failure) { throw new IllegalStateException("工作区事实无法读取，拒绝不确定启动", failure); }
        log.debug("会话工作区事实初始化完成 count={}", entries.size());
    }

    /** 由执行协调器在已确认全部自有容器停止后调用；中断修改永不重放。 */
    public void recoverAfterProcessesStopped() {
        log.debug("开始恢复工作区静止事实");
        for (Entry entry : entries.values()) {
            entry.lock.lock();
            try {
                State state = entry.state;
                if (state.restoring) rollbackRestore(state);
                state.activeOperation = null;
                state.blocked = false;
                state.uploading = false;
                persist(state);
            } finally { entry.lock.unlock(); }
        }
        if (!recovered) {
            recovered = true;
            worker.scheduleWithFixedDelay(this::maintain, 1, 5, TimeUnit.SECONDS);
        }
        log.debug("工作区静止事实恢复完成");
    }

    /** 公共文件/快照操作共用同一排他权；重复 operationId 回读原回执而不执行。 */
    public Map<String, Object> operate(WorkspaceCommand command) {
        validate(command);
        log.debug("开始工作区控制 operation={}", command.operation());
        try (Lease lease = acquire(command.workspace(), command.deadlineAt(), () -> false)) {
            State state = lease.entry.state;
            String digest = commandDigest(command);
            Path receiptPath = directory(state).resolve("operations").resolve(hash(command.operationId()) + ".json");
            Receipt existing = readReceipt(receiptPath);
            if (existing != null) {
                if (!digest.equals(existing.digest)) throw conflict("WORKSPACE_OPERATION_REUSED", "操作标识已用于其他请求");
                if (!existing.completed) throw conflict("WORKSPACE_OUTCOME_UNKNOWN", "先前操作结果未知，不允许重放");
                return existing.result;
            }
            if (command.expectedRevision() != null && command.expectedRevision() != state.revision)
                throw conflict("WORKSPACE_REVISION_CHANGED", "工作区修订与预期不符");
            Receipt receipt = new Receipt(digest, false, Map.of());
            writeJson(receiptPath, receipt);
            boolean write = !List.of("initialize", "list", "read", "checkpoint_status", "checkpoint_list", "checkpoint_create")
                    .contains(command.operation());
            if ("checkpoint_restore".equals(command.operation()) && command.expectedRevision() == null)
                throw conflict("WORKSPACE_BASELINE_REQUIRED", "恢复需要工作区预期修订");
            // 恢复前的旧内容使用旧修订保存；新修订只代表交换后的项目。
            if ("checkpoint_restore".equals(command.operation())) freeze(state, () -> !clock.instant().isBefore(command.deadlineAt()));
            if (write) lease.beginWrite("operation:" + hash(command.operationId()));
            try {
                Map<String, Object> result;
                switch (command.operation()) {
                    case "initialize" -> result = Map.of("success", true);
                    case "checkpoint_status", "checkpoint_list" -> result = statusMap(state);
                    case "checkpoint_create" -> {
                        freeze(state, () -> !clock.instant().isBefore(command.deadlineAt())); result = statusMap(state);
                    }
                    case "checkpoint_restore" -> {
                        restore(state, command.snapshotId(), () -> !clock.instant().isBefore(command.deadlineAt()));
                        result = statusMap(state);
                    }
                    default -> result = files.execute(project(state), command);
                }
                Map<String, Object> response = new LinkedHashMap<>(result);
                response.put("revision", state.revision);
                response.put("workspace_id", state.binding.workspaceId());
                writeJson(receiptPath, new Receipt(digest, true, response));
                return response;
            } catch (RuntimeException failed) {
                // 拒绝/部分修改也有持久回执；同标识不得靠重试再执行一次。
                String code = failed instanceof SandboxConflictException conflict ? conflict.code()
                        : failed instanceof SandboxRequestException rejected ? rejected.code() : "WORKSPACE_OPERATION_FAILED";
                writeJson(receiptPath, new Receipt(digest, true,
                        Map.of("error", code, "revision", state.revision, "outcome", "failed_or_partial")));
                throw failed;
            }
        } finally { log.debug("结束工作区控制 operation={}", command.operation()); }
    }

    /** 文件操作与命令均先等待工作区锁，再由执行层获取全局名额；等待可取消。 */
    public Lease acquire(WorkspaceBinding binding, Instant deadline, BooleanSupplier cancelled) {
        log.debug("等待工作区排他权 workspaceTag={}", tag(binding.workspaceId()));
        if (!recovered) throw conflict("WORKSPACE_RECOVERING", "工作区正在确认进程静止");
        Entry entry = entries.computeIfAbsent(binding.workspaceId(), ignored -> {
            if (!binding.initializeAllowed()) throw conflict("WORKSPACE_STATE_MISSING", "已有工作区持久事实缺失，需要人工恢复，不能创建空目录");
            return create(binding);
        });
        try {
            while (!entry.lock.tryLock(100, TimeUnit.MILLISECONDS)) checkWaiting(deadline, cancelled);
            boolean success = false;
            try {
                checkWaiting(deadline, cancelled);
                State state = entry.state;
                checkIdentity(state.binding, binding);
                if (state.deleted || state.deleting || Files.exists(directory(state).resolve("deleted.json"), LinkOption.NOFOLLOW_LINKS))
                    throw conflict("WORKSPACE_DELETED", "工作区已撤销写权");
                if (state.blocked) throw conflict("WORKSPACE_RECOVERY_REQUIRED", "先前命令未确认停止，工作区暂不可使用");
                state.binding = binding;
                ensureLocal(state, () -> cancelled.getAsBoolean() || !clock.instant().isBefore(deadline));
                checkWaiting(deadline, cancelled);
                state.lastUsed = clock.instant();
                persist(state);
                Lease result = new Lease(entry);
                success = true;
                log.debug("取得工作区排他权 workspaceTag={}", tag(binding.workspaceId()));
                return result;
            } finally { if (!success) entry.lock.unlock(); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw conflict("WORKSPACE_CANCELLED", "等待工作区已取消");
        }
    }

    /** 只读状态不会创建工作区，也不阻塞正在运行的 Bash；投影在持久写后发布。 */
    public WorkspaceStatus status(String workspaceId) {
        log.debug("读取工作区状态 workspaceTag={}", tag(workspaceId));
        Entry entry = entries.get(workspaceId);
        if (entry == null) return null;
        return entry.view;
    }

    /** 执行层确认未知任务的自有容器已停止后解除阻断；活动Lease由其finally负责。 */
    public void confirmStopped(String workspaceId) {
        log.debug("确认工作区命令停止 workspaceTag={}", tag(workspaceId));
        Entry entry = entries.get(workspaceId);
        if (entry == null || !entry.lock.tryLock()) return;
        try {
            if (entry.state.blocked) {
                entry.state.blocked = false;
                entry.state.activeOperation = null;
                persist(entry.state);
            }
        } finally { entry.lock.unlock(); }
        log.debug("工作区命令停止已确认");
    }

    /** 撤销写权意图先落盘；云端/本地清理由单后台线程重试，完成前返回 false。 */
    public boolean delete(String workspaceId) {
        log.debug("登记工作区删除 workspaceTag={}", tag(workspaceId));
        if (workspaceId == null || !workspaceId.matches("[0-9a-f]{64}"))
            throw new SandboxRequestException("WORKSPACE_ID_INVALID", "工作区标识无效");
        // 墓碑独立于长时间运行的命令锁，先撤销晚到请求的准入，再等待停止。
        writeJson(properties.projectRoot().resolve(workspaceId).resolve("deleted.json"), Map.of("deleted", true));
        Entry entry = entries.get(workspaceId);
        if (entry == null) {
            return true;
        }
        if (!entry.lock.tryLock()) return false;
        try {
            State state = entry.state;
            if (state.binding == null) return true;
            state.deleting = true;
            persist(state);
            return state.deleted;
        } finally { entry.lock.unlock(); }
    }

    /** 后台一次只处理一个云端副作用；上传时释放项目锁，删除意图仍能及时登记。 */
    void maintain() {
        if (!recovered) return;
        log.debug("开始工作区后台维护 count={}", entries.size());
        for (Entry entry : entries.values()) {
            try { maintainOne(entry); }
            catch (RuntimeException failed) { log.warn("工作区后台维护失败 reason={}", failed.getClass().getSimpleName()); }
        }
        log.debug("结束工作区后台维护");
    }

    /** 冻结/归档必须静止；网络操作按持久快照事实执行，可被新写和墓碑打断后续推进。 */
    private void maintainOne(Entry entry) {
        if (!entry.lock.tryLock()) return;
        Snapshot pending = null;
        try {
            State state = entry.state;
            if (state.binding == null || state.deleted || state.blocked || state.activeOperation != null) return;
            if (Files.exists(directory(state).resolve("deleted.json"), LinkOption.NOFOLLOW_LINKS)) state.deleting = true;
            if (state.deleting) { cleanup(state); return; }
            if (cloud.configured() && state.local && state.revision > state.backupRevision
                    && state.dirtySince != null && !clock.instant().isBefore(
                    state.dirtySince.plusSeconds(state.binding.policy().checkpointIntervalSeconds()))
                    && (state.prepareRetryAt == null || !clock.instant().isBefore(state.prepareRetryAt))
                    && state.snapshots.stream().noneMatch(s -> !s.ready && !s.retired)) {
                try {
                    freeze(state);
                    state.prepareRetryAt = null;
                } catch (RuntimeException rejected) {
                    state.backupError = "BACKUP_PREPARATION_FAILED";
                    state.prepareRetryAt = clock.instant().plusSeconds(60);
                    persist(state);
                    return;
                }
            }
            pending = state.snapshots.stream().filter(s -> !s.ready && !s.retired
                    && (s.retryAt == null || !clock.instant().isBefore(s.retryAt))).findFirst().orElse(null);
            if (pending != null && cloud.configured()) {
                pending.remote = true; // 远端发送前持久登记，超时后的对象也必须纳入清理。
                state.uploading = true;
                persist(state);
            } else {
                pending = null;
                prune(state);
                if (cloud.configured() && state.local && state.backupRevision == state.revision
                        && state.snapshots.stream().anyMatch(s -> s.ready && s.revision == state.revision && !s.retired)
                        && !clock.instant().isBefore(state.lastUsed.plusSeconds(state.binding.policy().localIdleHours() * 3600L))) {
                    state.local = false;
                    persist(state); // 归档事实先登记；重启后只从校验过的最新快照恢复。
                    deleteTree(project(state));
                    for (Snapshot snapshot : state.snapshots) {
                        if (snapshot.ready) try { Files.deleteIfExists(snapshotFile(state, snapshot)); }
                        catch (IOException failure) { throw WorkspaceFiles.ioFailure(failure); }
                    }
                }
            }
        } finally { entry.lock.unlock(); }
        if (pending == null) return;
        String error = null;
        Instant retryAt = clock.instant().plusSeconds(60);
        try { cloud.upload(pending.key, snapshotFile(entry.state, pending)); }
        catch (IOException | RuntimeException failed) {
            error = "CLOUD_BACKUP_FAILED";
            if (failed instanceof UpyunWorkspaceStore.DeferredRetryException deferred) retryAt = deferred.retryAt();
        }
        entry.lock.lock();
        try {
            State state = entry.state;
            state.uploading = false;
            if (error == null) {
                pending.ready = true;
                pending.retryAt = null;
                state.backupRevision = Math.max(state.backupRevision, pending.revision);
                state.lastBackup = clock.instant();
                state.backupError = null;
            } else {
                pending.retryAt = retryAt;
                state.backupError = error;
            }
            persist(state); // 即使收到墓碑，也保留上传对象事实供后续准确删除。
        } finally { entry.lock.unlock(); }
    }

    /** 保留当前修订的不可变 ZIP；相同修订重复检查点合并，不按每次修改上传。 */
    private Snapshot freeze(State state) {
        return freeze(state, () -> false);
    }

    /** 前台检查点在流式冻结期间持续检查调用期限，后台任务使用无期限入口。 */
    private Snapshot freeze(State state, BooleanSupplier cancelled) {
        log.debug("冻结会话工作区 revision={}", state.revision);
        Snapshot existing = state.snapshots.stream().filter(s -> s.revision == state.revision && !s.retired).findFirst().orElse(null);
        if (existing != null) return existing;
        Snapshot snapshot = new Snapshot();
        snapshot.id = UUID.randomUUID().toString(); snapshot.revision = state.revision;
        snapshot.key = "session-workspaces/" + state.binding.workspaceId() + "/" + snapshot.id + ".zip";
        snapshot.created = clock.instant();
        try { Files.createDirectories(directory(state).resolve("snapshots")); }
        catch (IOException failure) { throw WorkspaceFiles.ioFailure(failure); }
        var manifest = archive.freeze(project(state), snapshotFile(state, snapshot), state.revision, cancelled);
        snapshot.sha256 = manifest.sha256(); snapshot.size = manifest.size();
        state.snapshots.add(snapshot); state.dirtySince = null;
        persist(state);
        return snapshot;
    }

    /** 已归档项目必须从最新已验证云端快照恢复，不允许静默创建空目录。 */
    private void ensureLocal(State state, BooleanSupplier cancelled) {
        log.debug("确认工作区本地副本 revision={}", state.revision);
        if (state.local) {
            try { WorkspaceFiles.requireDirectory(project(state)); }
            catch (IOException missing) { throw conflict("WORKSPACE_LOCAL_MISSING", "本地副本缺失，请从检查点恢复"); }
        } else {
            Snapshot latest = state.snapshots.stream().filter(s -> s.ready && s.revision == state.revision && !s.retired)
                    .findFirst().orElseThrow(() -> conflict("WORKSPACE_LATEST_BACKUP_MISSING", "最新修订尚未备份，不能重建空工作区"));
            restore(state, latest.id, cancelled);
        }
    }

    /** 先验证到隔离暂存目录，再用目录交换替换；交换意图可在重启时回滚。 */
    private void restore(State state, String snapshotId, BooleanSupplier cancelled) {
        log.debug("恢复工作区检查点");
        Snapshot selected = state.snapshots.stream().filter(s -> s.id.equals(snapshotId) && !s.retired)
                .findFirst().orElseThrow(() -> conflict("WORKSPACE_SNAPSHOT_NOT_FOUND", "检查点不存在"));
        Path zip = snapshotFile(state, selected);
        Path staging = directory(state).resolve("restore-staging");
        Path old = directory(state).resolve("restore-old");
        try {
            deleteTree(staging); deleteTree(old);
            if (!Files.exists(zip, LinkOption.NOFOLLOW_LINKS)) {
                if (!selected.ready) throw conflict("WORKSPACE_SNAPSHOT_UNAVAILABLE", "检查点尚未在云端保存");
                cloud.download(selected.key, zip);
            }
            var verified = archive.restore(zip, staging, cancelled);
            if (!verified.sha256().equals(selected.sha256) || verified.size() != selected.size)
                throw conflict("WORKSPACE_SNAPSHOT_INVALID", "检查点整体摘要或大小不符");
            state.restoring = true; persist(state);
            if (Files.exists(project(state), LinkOption.NOFOLLOW_LINKS))
                Files.move(project(state), old, StandardCopyOption.ATOMIC_MOVE);
            Files.move(staging, project(state), StandardCopyOption.ATOMIC_MOVE);
            state.local = true; state.restoring = false;
            persist(state);
            deleteTree(old);
        } catch (IOException failure) {
            if (state.restoring) rollbackRestore(state);
            throw WorkspaceFiles.ioFailure(failure);
        } catch (RuntimeException failure) {
            if (state.restoring) rollbackRestore(state);
            throw failure;
        } finally { deleteTree(staging); }
        log.debug("恢复工作区检查点完成");
    }

    /** 交换未提交时优先还原旧目录；结果未知的命令/操作回执仍不得自动重试。 */
    private void rollbackRestore(State state) {
        log.debug("回滚中断的工作区恢复");
        Path old = directory(state).resolve("restore-old");
        try {
            if (Files.exists(old, LinkOption.NOFOLLOW_LINKS)) {
                deleteTree(project(state));
                Files.move(old, project(state), StandardCopyOption.ATOMIC_MOVE);
            }
            deleteTree(directory(state).resolve("restore-staging"));
            state.restoring = false;
            state.local = Files.isDirectory(project(state), LinkOption.NOFOLLOW_LINKS);
            persist(state);
        } catch (IOException failure) { throw WorkspaceFiles.ioFailure(failure); }
        log.debug("中断的工作区恢复回滚完成");
    }

    /** 超出保留数量先登记退休事实，再准确删除；删除失败仍保留可重试对象键。 */
    private void prune(State state) {
        log.debug("整理工作区历史快照");
        if (state.cleanupRetryAt != null && clock.instant().isBefore(state.cleanupRetryAt)) return;
        var ready = state.snapshots.stream().filter(s -> s.ready && !s.retired).toList();
        int excess = ready.size() - state.binding.policy().snapshotHistory();
        for (Snapshot snapshot : ready) {
            if (excess <= 0) break;
            if (snapshot.revision == state.backupRevision) continue;
            snapshot.retired = true; excess--;
        }
        persist(state);
        for (Snapshot snapshot : new ArrayList<>(state.snapshots)) {
            if (!snapshot.retired) continue;
            try {
                cloud.delete(snapshot.key);
                Files.deleteIfExists(snapshotFile(state, snapshot));
                state.snapshots.remove(snapshot); persist(state);
            } catch (IOException failure) {
                state.backupError = "CLOUD_CLEANUP_FAILED";
                state.cleanupRetryAt = failure instanceof UpyunWorkspaceStore.DeferredRetryException deferred
                        ? deferred.retryAt() : clock.instant().plusSeconds(60);
                persist(state); break;
            }
        }
        log.debug("工作区历史快照整理完成");
    }

    /** 删除墓碑永久保留身份；先清云端，再清本地，未知上传对象也按登记键删除。 */
    private void cleanup(State state) {
        log.debug("清理已撤销工作区");
        if (state.cleanupRetryAt != null && clock.instant().isBefore(state.cleanupRetryAt)) return;
        try {
            for (Snapshot snapshot : new ArrayList<>(state.snapshots)) {
                if (snapshot.remote && !cloud.configured()) throw new IOException("工作区云端配置不可用，保留清理意图");
                if (snapshot.remote) cloud.delete(snapshot.key);
                Files.deleteIfExists(snapshotFile(state, snapshot));
                state.snapshots.remove(snapshot); persist(state);
            }
            deleteTree(project(state));
            deleteTree(directory(state).resolve("snapshots"));
            deleteTree(directory(state).resolve("restore-staging"));
            deleteTree(directory(state).resolve("restore-old"));
            deleteTree(directory(state).resolve("operations"));
            state.local = false; state.deleted = true; state.backupError = null; persist(state);
        } catch (IOException failure) {
            state.backupError = "CLOUD_CLEANUP_FAILED";
            // 配置缺失无需延迟，恢复配置后下一轮即可继续；远端明确拒绝保留其时间。
            if (cloud.configured()) state.cleanupRetryAt = failure instanceof UpyunWorkspaceStore.DeferredRetryException deferred
                    ? deferred.retryAt() : clock.instant().plusSeconds(60);
            persist(state);
        }
        log.debug("已撤销工作区清理结束 completed={}", state.deleted);
    }

    /** 创建身份与初始目录；墓碑目录不允许被晚到任务重建。 */
    private Entry create(WorkspaceBinding binding) {
        log.debug("创建会话工作区 workspaceTag={}", tag(binding.workspaceId()));
        Path directory = properties.projectRoot().resolve(binding.workspaceId());
        if (Files.exists(directory.resolve("deleted.json"), LinkOption.NOFOLLOW_LINKS))
            throw conflict("WORKSPACE_DELETED", "工作区已撤销写权");
        State state = new State(); state.binding = binding; state.local = true; state.revision = 1;
        state.lastUsed = clock.instant(); state.dirtySince = clock.instant();
        try {
            Files.createDirectories(directory.resolve("project"));
            WorkspaceFiles.writable(directory.resolve("project"), true);
            writeJson(directory.resolve("state.json"), state);
        } catch (IOException failure) { throw WorkspaceFiles.ioFailure(failure); }
        return new Entry(state);
    }

    /** 持久化以同目录原子替换；投影发布只在落盘成功后进行。 */
    private void persist(State state) {
        log.debug("保存工作区事实 revision={}", state.revision);
        writeJson(directory(state).resolve("state.json"), state);
        Entry entry = entries.get(state.binding.workspaceId());
        if (entry != null) entry.view = projectStatus(state);
        log.debug("工作区事实保存完成");
    }

    /** 内部 JSON 不在用户挂载目录内，容器无权修改事实与回执。 */
    private void writeJson(Path path, Object value) {
        log.debug("开始保存工作区内部记录");
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), ".record-", ".tmp");
            json.writeValue(temporary.toFile(), value);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) { throw WorkspaceFiles.ioFailure(failure); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); }
            catch (IOException failed) { log.warn("工作区临时记录清理失败"); }
        }
        log.debug("工作区内部记录保存完成");
    }

    /** 回执缺失表示尚未准入；损坏事实拒绝执行，避免重复副作用。 */
    private Receipt readReceipt(Path path) {
        log.debug("读取工作区操作回执");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try { return json.readValue(path.toFile(), Receipt.class); }
        catch (IOException failure) { throw WorkspaceFiles.ioFailure(failure); }
    }

    /** 投影只有一个状态所有者；云端未配置时不会声称已经完成备份。 */
    private WorkspaceStatus projectStatus(State state) {
        log.debug("开始工作区状态投影");
        String phase = state.deleted ? "deleted" : state.deleting ? "deleting" : state.blocked ? "recovery_required"
                : state.restoring ? "restoring" : !state.local ? "archived" : state.uploading ? "backing_up"
                : state.backupError != null ? "backup_failed" : !cloud.configured() ? "local_only"
                : state.backupRevision < state.revision ? "backup_pending" : "ready";
        WorkspaceStatus result = new WorkspaceStatus(state.binding.workspaceId(), state.revision, state.backupRevision,
                state.local, phase, state.backupError, state.lastBackup, state.snapshots.stream()
                .filter(s -> !s.retired).map(s -> new WorkspaceSnapshot(s.id, s.revision, s.size, s.sha256, s.created, s.ready)).toList());
        log.debug("结束工作区状态投影 phase={}", phase);
        return result;
    }

    /** Tool 返回明确字段，不暴露对象键、归属身份或宿主路径。 */
    private Map<String, Object> statusMap(State state) {
        log.debug("生成工作区工具状态");
        try { return json.convertValue(projectStatus(state), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }); }
        finally { log.debug("工作区工具状态生成结束"); }
    }

    /** 请求重用摘要不包含易变deadline；绑定归属与实际内容仍全部参与比较。 */
    private String commandDigest(WorkspaceCommand command) {
        log.debug("计算工作区请求摘要");
        try { return hash(json.writeValueAsString(new WorkspaceCommand(command.operationId(), command.workspace(),
                command.operation(), command.path(), command.targetPath(), command.content(), command.expectedRevision(),
                command.expectedSha256(), command.offset(), command.limit(), command.snapshotId(), null))); }
        catch (IOException failure) { throw WorkspaceFiles.ioFailure(failure); }
        finally { log.debug("工作区请求摘要计算结束"); }
    }

    /** 入口只接受有界稳定操作标识与已授权类型，正文不写入日志。 */
    private void validate(WorkspaceCommand command) {
        log.debug("校验工作区控制请求");
        if (command == null || command.workspace() == null || command.operationId() == null
                || command.operationId().isBlank() || command.operationId().length() > 512
                || command.deadlineAt() == null || command.operation() == null || !List.of("initialize", "list", "read", "write", "patch", "mkdir", "move", "delete",
                "checkpoint_status", "checkpoint_list", "checkpoint_create", "checkpoint_restore").contains(command.operation()))
            throw new SandboxRequestException("WORKSPACE_REQUEST_INVALID", "工作区请求字段无效");
        log.debug("工作区控制请求校验结束");
    }

    /** 固定身份不能被下一次调用覆盖；运行冻结策略可更新，归属始终不变。 */
    private void checkIdentity(WorkspaceBinding old, WorkspaceBinding candidate) {
        log.debug("校验工作区固定归属");
        if (old == null || !old.tenantId().equals(candidate.tenantId()) || !old.sessionId().equals(candidate.sessionId())
                || old.endUserId() != candidate.endUserId() || !old.productSessionId().equals(candidate.productSessionId()))
            throw conflict("WORKSPACE_IDENTITY_MISMATCH", "工作区归属不符");
        log.debug("工作区固定归属校验完成");
    }

    /** 等待与恢复均计入调用deadline，不把排队变成无限等待。 */
    private void checkWaiting(Instant deadline, BooleanSupplier cancelled) {
        log.debug("校验工作区等待期限");
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw conflict("WORKSPACE_CANCELLED", "工作区操作已取消");
        if (deadline == null || !clock.instant().isBefore(deadline)) throw conflict("WORKSPACE_DEADLINE_EXPIRED", "工作区操作已超时");
        log.debug("工作区等待期限校验完成");
    }

    /** 仅删除已解析的项目卷内部目录，不跟随链接或触及卷根。 */
    private void deleteTree(Path path) {
        log.debug("清理工作区内部目录");
        Path root = properties.projectRoot().toAbsolutePath().normalize();
        Path target = path.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) throw new IllegalStateException("workspace_cleanup_escape");
        try {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
            try (var paths = Files.walk(target)) {
                for (Path item : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(item);
            }
        } catch (IOException failure) { throw WorkspaceFiles.ioFailure(failure); }
        log.debug("工作区内部目录清理完成");
    }

    /** 工作区项目在独立卷内，元数据与快照在挂载子目录之外。 */
    private Path directory(State state) {
        log.debug("解析工作区卷内目录");
        try { return properties.projectRoot().resolve(state.binding.workspaceId()); }
        finally { log.debug("工作区卷内目录解析结束"); }
    }
    /** 可写挂载只暴露project子目录。 */
    private Path project(State state) {
        log.debug("解析工作区项目目录");
        try { return directory(state).resolve("project"); }
        finally { log.debug("工作区项目目录解析结束"); }
    }
    /** 快照ID是服务生成的UUID，不接受模型路径参与拼接。 */
    private Path snapshotFile(State state, Snapshot snapshot) {
        log.debug("解析工作区快照文件");
        try { return directory(state).resolve("snapshots").resolve(snapshot.id + ".zip"); }
        finally { log.debug("工作区快照文件解析结束"); }
    }
    /** 回执文件名与日志标签均只采用摘要。 */
    private static String hash(String value) {
        log.debug("计算工作区内部标识摘要");
        try { return HexFormat.of().formatHex(WorkspaceFiles.digest().digest(value.getBytes(StandardCharsets.UTF_8))); }
        finally { log.debug("工作区内部标识摘要计算结束"); }
    }
    /** 关联身份不输出原值。 */
    private static String tag(String value) {
        log.debug("生成工作区安全标签");
        try { return value == null ? "none" : Integer.toHexString(value.hashCode()); }
        finally { log.debug("工作区安全标签生成结束"); }
    }
    /** 对外错误只包含固定代码和受控说明。 */
    private static SandboxConflictException conflict(String code, String message) {
        log.debug("生成工作区冲突回执 code={}", code);
        try { return new SandboxConflictException(code, message); }
        finally { log.debug("工作区冲突回执生成结束"); }
    }

    /** 工作区锁归获得线程所有；关闭必须在容器已停止或持久阻断事实后进行。 */
    public final class Lease implements AutoCloseable {
        private final Entry entry;
        private boolean writing;
        private boolean confirmed = true;
        private boolean closed;
        private Lease(Entry entry) { this.entry = entry; }

        /** 所有可能修改项目的命令在启动前推进修订；失败也保留此修订。 */
        public void beginWrite(String operationId) {
            log.debug("登记工作区写入开始");
            if (writing) return;
            State state = entry.state;
            state.revision++;
            state.activeOperation = operationId;
            if (state.dirtySince == null) state.dirtySince = clock.instant();
            writing = true; persist(state);
            log.debug("工作区写入开始已登记 revision={}", state.revision);
        }

        /** 未确认进程停止则永久阻断后续写者，等重启清理或执行层确认停止后解锁。 */
        public void terminationConfirmed(boolean value) { log.debug("记录工作区进程静止 confirmed={}", value); confirmed = value; }

        /** 写权释放不表示代码执行成功；只清理活动标识或保存恢复阻断。 */
        @Override public void close() {
            log.debug("释放工作区排他权 writing={} confirmed={}", writing, confirmed);
            if (closed) return;
            try {
                if (writing) {
                    State state = entry.state;
                    state.blocked = !confirmed;
                    if (confirmed) state.activeOperation = null;
                    if (Files.exists(directory(state).resolve("deleted.json"), LinkOption.NOFOLLOW_LINKS)) state.deleting = true;
                    state.lastUsed = clock.instant(); persist(state);
                }
            } finally { closed = true; entry.lock.unlock(); }
            log.debug("工作区排他权释放完成");
        }
    }

    /** 停止可中断后台网络请求；待办快照与清理事实已经持久化，下次启动继续。 */
    @PreDestroy public void shutdown() {
        log.debug("关闭工作区后台线程");
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) log.warn("工作区后台线程尚未静止，持久待办保留");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        log.debug("工作区后台线程关闭结束");
    }

    /** 当前节点内部状态仅由持锁线程修改；对外视图是不可变的volatile快照。 */
    private final class Entry {
        final ReentrantLock lock = new ReentrantLock(true);
        final State state;
        volatile WorkspaceStatus view;
        Entry(State state) { this.state = state; this.view = state.binding == null ? null : projectStatus(state); }
    }

    /** JSON持久事实；不公开为HTTP协议或传入任务容器。 */
    public static final class State {
        public WorkspaceBinding binding;
        public long revision;
        public long backupRevision;
        public boolean local;
        public boolean blocked;
        public boolean deleting;
        public boolean deleted;
        public boolean restoring;
        public boolean uploading;
        public String activeOperation;
        public String backupError;
        public Instant dirtySince;
        public Instant prepareRetryAt;
        public Instant cleanupRetryAt;
        public Instant lastUsed;
        public Instant lastBackup;
        public List<Snapshot> snapshots = new ArrayList<>();
    }

    /** 每个对象先有本地冻结事实，远端验证成功才推进ready与backupRevision。 */
    public static final class Snapshot {
        public String id;
        public String key;
        public String sha256;
        public long size;
        public long revision;
        public Instant created;
        public Instant retryAt;
        public boolean ready;
        public boolean retired;
        public boolean remote;
    }
    public record Receipt(String digest, boolean completed, Map<String, Object> result) { }
}
