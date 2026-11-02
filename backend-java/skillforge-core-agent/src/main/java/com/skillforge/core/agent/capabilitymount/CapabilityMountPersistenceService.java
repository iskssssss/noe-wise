package com.skillforge.core.agent.capabilitymount;

import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CapabilityMount 持久化事务边界；不接入 Agent Runtime 或管理 API。 */
@Service
public class CapabilityMountPersistenceService {

    private final CapabilityMountPersistenceRepository repository;

    public CapabilityMountPersistenceService(CapabilityMountPersistenceRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /** 读取当前装配；空装配首次读取时建立 generation=1。 */
    @Transactional(rollbackFor = Exception.class)
    public CapabilityMountSetResult read(Long tenantId, Long agentId) {
        repository.ensureAndLock(tenantId, agentId);
        return repository.readState(tenantId, agentId);
    }

    /** 读取已存在装配；不为创建前重复检查隐式建立 generation。 */
    @Transactional(readOnly = true)
    public Optional<CapabilityMountSetResult> readIfPresent(Long tenantId, Long agentId) {
        return repository.readStateIfPresent(tenantId, agentId);
    }

    /** 授权目录批量读取已存在装配；缺失项不初始化 generation。 */
    @Transactional(readOnly = true)
    public Map<Long, CapabilityMountSetResult> readAllIfPresent(Long tenantId, List<Long> agentIds) {
        return repository.readAllIfPresent(tenantId, agentIds);
    }

    /** 显式确保装配代存在并返回当前装配。 */
    @Transactional(rollbackFor = Exception.class)
    public CapabilityMountSetResult ensure(Long tenantId, Long agentId) {
        repository.ensureAndLock(tenantId, agentId);
        return repository.readState(tenantId, agentId);
    }

    /** 创建 mount；没有 mount-set 时本次成功创建使用 generation=1。 */
    @Transactional(rollbackFor = Exception.class)
    public CapabilityMountSetResult create(CreateCapabilityMountCommand command) {
        Objects.requireNonNull(command, "command");
        CapabilityMountPersistenceRepository.MountSetLock mountSet =
                repository.ensureAndLock(command.tenantId(), command.agentId());
        CapabilityMount mount = new CapabilityMount(
                Long.toString(SnowflakeIds.nextId()),
                command.tenantId(), command.agentId(), command.capability(), command.config(),
                command.priority(), 1L, command.status().active());
        repository.insert(mount);
        if (!mountSet.created()) {
            repository.incrementGeneration(command.tenantId(), command.agentId(),
                    generation(mountSet));
        }
        return repository.readState(command.tenantId(), command.agentId());
    }

    /** Update mount；要求 expected revision，成功时 mount revision 与 generation 各加一。 */
    @Transactional(rollbackFor = Exception.class)
    public CapabilityMountSetResult update(UpdateCapabilityMountCommand command) {
        Objects.requireNonNull(command, "command");
        AgentCapabilityMountSetDO mountSet = repository.lockExisting(
                command.tenantId(), command.agentId());
        CapabilityMount current = requireMount(repository.findForUpdate(
                command.tenantId(), command.agentId(), command.mountId()));
        requireExpectedRevision(current, command.expectedRevision());
        requireStableIdentity(current, command.capability());
        CapabilityMount updated = new CapabilityMount(
                current.mountId(), command.tenantId(), command.agentId(),
                command.capability(), command.config(), command.priority(),
                current.revision() + 1, command.status().active());
        repository.update(updated, command.expectedRevision());
        repository.incrementGeneration(command.tenantId(), command.agentId(),
                generation(mountSet));
        return repository.readState(command.tenantId(), command.agentId());
    }

    /** Delete mount；要求 expected revision，成功时 generation 加一。 */
    @Transactional(rollbackFor = Exception.class)
    public CapabilityMountSetResult delete(DeleteCapabilityMountCommand command) {
        Objects.requireNonNull(command, "command");
        AgentCapabilityMountSetDO mountSet = repository.lockExisting(
                command.tenantId(), command.agentId());
        CapabilityMount current = requireMount(repository.findForUpdate(
                command.tenantId(), command.agentId(), command.mountId()));
        requireExpectedRevision(current, command.expectedRevision());
        repository.delete(command.tenantId(), command.agentId(), command.mountId(),
                command.expectedRevision());
        repository.incrementGeneration(command.tenantId(), command.agentId(),
                generation(mountSet));
        return repository.readState(command.tenantId(), command.agentId());
    }

    private CapabilityMount requireMount(CapabilityMount mount) {
        if (mount == null) {
            throw new CapabilityMountNotFoundException("capability mount not found");
        }
        return mount;
    }

    private void requireExpectedRevision(CapabilityMount mount, long expectedRevision) {
        if (mount.revision() != expectedRevision) {
            throw new CapabilityMountRevisionConflictException(
                    "capability mount revision is stale");
        }
    }

    private void requireStableIdentity(
            CapabilityMount current,
            CapabilityRef requested) {
        if (current.capability().type() != requested.type()
                || !current.capability().capabilityId().equals(requested.capabilityId())) {
            throw new IllegalArgumentException(
                    "capability mount type and definition identity are immutable");
        }
    }

    private long generation(CapabilityMountPersistenceRepository.MountSetLock mountSet) {
        return generation(mountSet.value());
    }

    private long generation(AgentCapabilityMountSetDO mountSet) {
        if (mountSet.getGeneration() == null || mountSet.getGeneration() <= 0) {
            throw new CapabilityMountPersistenceException(
                    "capability mount set generation is not positive");
        }
        return mountSet.getGeneration();
    }
}
