package com.skillforge.sandbox.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.sandbox.config.SandboxProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** 以每任务一个 JSON 文件保存事实，更新使用同目录原子替换。 */
@Component
public class TaskRepository {

    private final ObjectMapper objectMapper;
    private final Path recordsRoot;
    private final Map<String, TaskRecord> records = new LinkedHashMap<>();
    // 由持久事实重建的扫描索引；已删除工作区的身份仍永久留在 records 中。
    private final Map<String, TaskRecord> workspaceCandidates = new LinkedHashMap<>();

    public TaskRepository(ObjectMapper objectMapper, SandboxProperties properties) {
        this.objectMapper = objectMapper;
        this.recordsRoot = properties.dataRoot().resolve("records").toAbsolutePath().normalize();
        load();
    }

    public synchronized Registration register(TaskRecord candidate) {
        TaskRecord existing = records.get(candidate.taskId());
        if (existing != null) {
            return new Registration(existing, false);
        }
        persist(candidate);
        remember(candidate);
        return new Registration(candidate, true);
    }

    public synchronized Optional<TaskRecord> find(String taskId) {
        return Optional.ofNullable(records.get(taskId));
    }

    public synchronized List<TaskRecord> findAll() {
        return new ArrayList<>(records.values());
    }

    /** 仅返回尚未标记工作区删除的任务快照；静止、期限和策略节拍由 Janitor 判断。 */
    public synchronized List<TaskRecord> findWorkspaceCandidates() {
        return new ArrayList<>(workspaceCandidates.values());
    }

    private void remember(TaskRecord record) {
        records.put(record.taskId(), record);
        if (record.workspaceDeletedAt() == null) {
            workspaceCandidates.put(record.taskId(), record);
        } else {
            workspaceCandidates.remove(record.taskId());
        }
    }

    public synchronized TaskRecord update(String taskId, UnaryOperator<TaskRecord> update) {
        TaskRecord current = records.get(taskId);
        if (current == null) {
            throw new IllegalStateException("任务不存在: " + taskId);
        }
        TaskRecord next = update.apply(current);
        persist(next);
        remember(next);
        return next;
    }

    private void load() {
        try {
            Files.createDirectories(recordsRoot);
            try (var paths = Files.list(recordsRoot)) {
                paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .forEach(path -> {
                            try {
                                TaskRecord record = objectMapper.readValue(path.toFile(), TaskRecord.class);
                                remember(record);
                            } catch (IOException invalidRecord) {
                                throw new IllegalStateException("任务记录损坏，拒绝在不确定状态下启动: " + path,
                                        invalidRecord);
                            }
                        });
            }
        } catch (IOException failure) {
            throw new IllegalStateException("无法初始化任务记录目录: " + recordsRoot, failure);
        }
    }

    private void persist(TaskRecord record) {
        Path target = recordsRoot.resolve(record.taskId() + ".json");
        Path temporary = recordsRoot.resolve(record.taskId() + ".json.tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), record);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("无法持久化任务记录: " + record.taskId(), failure);
        }
    }

    public record Registration(TaskRecord record, boolean created) {
    }
}
