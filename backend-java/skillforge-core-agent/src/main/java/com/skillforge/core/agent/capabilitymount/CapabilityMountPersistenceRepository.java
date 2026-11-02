package com.skillforge.core.agent.capabilitymount;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityMount.ToolMountConfig;
import com.skillforge.core.capability.ToolDeploymentLookup;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** CapabilityMount 持久化仓储；所有 Agent 读写均带 tenantId 与 agentId。 */
@Repository
public class CapabilityMountPersistenceRepository {

    private final AgentCapabilityMountSetMapper mountSetMapper;
    private final AgentCapabilityMountMapper mountMapper;
    private final AgentMapper agentMapper;
    private final ToolDeploymentLookup deploymentMapper;
    private final CapabilityMountPersistenceAssembler assembler;
    private final Clock clock;

    @Autowired
    public CapabilityMountPersistenceRepository(
            AgentCapabilityMountSetMapper mountSetMapper,
            AgentCapabilityMountMapper mountMapper,
            AgentMapper agentMapper,
            ToolDeploymentLookup deploymentMapper) {
        this(mountSetMapper, mountMapper, agentMapper, deploymentMapper,
                new CapabilityMountPersistenceAssembler(), Clock.systemUTC());
    }

    CapabilityMountPersistenceRepository(
            AgentCapabilityMountSetMapper mountSetMapper,
            AgentCapabilityMountMapper mountMapper,
            AgentMapper agentMapper,
            ToolDeploymentLookup deploymentMapper,
            CapabilityMountPersistenceAssembler assembler,
            Clock clock) {
        this.mountSetMapper = Objects.requireNonNull(mountSetMapper, "mountSetMapper");
        this.mountMapper = Objects.requireNonNull(mountMapper, "mountMapper");
        this.agentMapper = Objects.requireNonNull(agentMapper, "agentMapper");
        this.deploymentMapper = Objects.requireNonNull(deploymentMapper, "deploymentMapper");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 确保装配代存在并锁定它；空装配首次建立 generation=1。 */
    public MountSetLock ensureAndLock(Long tenantId, Long agentId) {
        requireOwnership(tenantId, agentId);
        AgentCapabilityMountSetDO existing = selectSet(tenantId, agentId, true);
        if (existing != null) {
            return new MountSetLock(existing, false);
        }

        lockAgent(tenantId, agentId);
        existing = selectSet(tenantId, agentId, true);
        if (existing != null) {
            return new MountSetLock(existing, false);
        }

        AgentCapabilityMountSetDO created = new AgentCapabilityMountSetDO();
        created.setTenantId(tenantId);
        created.setAgentId(agentId);
        created.setGeneration(1L);
        if (mountSetMapper.insert(created) != 1) {
            throw new CapabilityMountPersistenceException("failed to create capability mount set");
        }
        return new MountSetLock(created, true);
    }

    /** 锁定已存在的装配代；Update/Delete 不会隐式创建空装配。 */
    public AgentCapabilityMountSetDO lockExisting(Long tenantId, Long agentId) {
        requireOwnership(tenantId, agentId);
        AgentCapabilityMountSetDO value = selectSet(tenantId, agentId, true);
        if (value == null) {
            throw new CapabilityMountNotFoundException("capability mount set not found");
        }
        return value;
    }

    /** 读取指定 Agent 当前 mount；查询始终同时限定租户、Agent 和 mount ID。 */
    public CapabilityMount findForUpdate(Long tenantId, Long agentId, String mountId) {
        requireOwnership(tenantId, agentId);
        long id = positiveId(mountId, "mountId");
        AgentCapabilityMountDO row = mountMapper.selectOne(
                new LambdaQueryWrapper<AgentCapabilityMountDO>()
                        .eq(AgentCapabilityMountDO::getTenantId, tenantId)
                        .eq(AgentCapabilityMountDO::getAgentId, agentId)
                        .eq(AgentCapabilityMountDO::getId, id)
                        .last("FOR UPDATE"));
        if (row == null) {
            return null;
        }
        return assembler.toDomain(row, toolDeploymentKey(row));
    }

    /** 插入一个已分配 Snowflake identity 的 mount。 */
    public void insert(CapabilityMount mount) {
        Objects.requireNonNull(mount, "mount");
        AgentCapabilityMountDO row = assembler.toRow(mount, toolDeploymentId(mount));
        Instant now = clock.instant();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        if (mountMapper.insert(row) != 1) {
            throw new CapabilityMountPersistenceException("failed to insert capability mount");
        }
    }

    /** 按 expected revision 更新 mount，并显式清空旧类型化列。 */
    public void update(CapabilityMount mount, long expectedRevision) {
        Objects.requireNonNull(mount, "mount");
        AgentCapabilityMountDO row = assembler.toRow(mount, toolDeploymentId(mount));
        LambdaUpdateWrapper<AgentCapabilityMountDO> update = new LambdaUpdateWrapper<AgentCapabilityMountDO>()
                .eq(AgentCapabilityMountDO::getTenantId, mount.tenantId())
                .eq(AgentCapabilityMountDO::getAgentId, mount.agentId())
                .eq(AgentCapabilityMountDO::getId, positiveId(mount.mountId(), "mountId"))
                .eq(AgentCapabilityMountDO::getRevision, expectedRevision)
                .set(AgentCapabilityMountDO::getCapabilityType, row.getCapabilityType())
                .set(AgentCapabilityMountDO::getCapabilityDefinitionId, row.getCapabilityDefinitionId())
                .set(AgentCapabilityMountDO::getCapabilityVersionId, row.getCapabilityVersionId())
                .set(AgentCapabilityMountDO::getPriority, row.getPriority())
                .set(AgentCapabilityMountDO::getStatus, row.getStatus())
                .set(AgentCapabilityMountDO::getRevision, row.getRevision())
                .set(AgentCapabilityMountDO::getActivationMode, row.getActivationMode())
                .set(AgentCapabilityMountDO::getTokenBudget, row.getTokenBudget())
                .set(AgentCapabilityMountDO::getToolDeploymentId, row.getToolDeploymentId())
                .set(AgentCapabilityMountDO::getToolDeploymentRevision, row.getToolDeploymentRevision())
                .set(AgentCapabilityMountDO::getWorkflowInstanceId, row.getWorkflowInstanceId())
                .set(AgentCapabilityMountDO::getWorkflowInstanceRevision, row.getWorkflowInstanceRevision())
                .set(AgentCapabilityMountDO::getWorkflowInstallationRevision, row.getWorkflowInstallationRevision())
                .set(AgentCapabilityMountDO::getKnowledgeBindingMode, row.getKnowledgeBindingMode())
                .set(AgentCapabilityMountDO::getKnowledgePinnedRevisionId,
                        row.getKnowledgePinnedRevisionId())
                .set(AgentCapabilityMountDO::getUpdatedAt, clock.instant());
        if (mountMapper.update(null, update) != 1) {
            throw new CapabilityMountRevisionConflictException(
                    "capability mount revision is stale or mount is absent");
        }
    }

    /** 按 expected revision 删除 mount。 */
    public void delete(Long tenantId, Long agentId, String mountId, long expectedRevision) {
        requireOwnership(tenantId, agentId);
        long id = positiveId(mountId, "mountId");
        int deleted = mountMapper.delete(new LambdaQueryWrapper<AgentCapabilityMountDO>()
                .eq(AgentCapabilityMountDO::getTenantId, tenantId)
                .eq(AgentCapabilityMountDO::getAgentId, agentId)
                .eq(AgentCapabilityMountDO::getId, id)
                .eq(AgentCapabilityMountDO::getRevision, expectedRevision));
        if (deleted != 1) {
            throw new CapabilityMountRevisionConflictException(
                    "capability mount revision is stale or mount is absent");
        }
    }

    /** 在已锁定 mount-set 行的事务内推进 generation。 */
    public void incrementGeneration(Long tenantId, Long agentId, long expectedGeneration) {
        requireOwnership(tenantId, agentId);
        if (expectedGeneration <= 0) {
            throw new IllegalArgumentException("expectedGeneration must be positive");
        }
        int updated = mountSetMapper.update(null,
                new LambdaUpdateWrapper<AgentCapabilityMountSetDO>()
                        .eq(AgentCapabilityMountSetDO::getTenantId, tenantId)
                        .eq(AgentCapabilityMountSetDO::getAgentId, agentId)
                        .eq(AgentCapabilityMountSetDO::getGeneration, expectedGeneration)
                        .set(AgentCapabilityMountSetDO::getGeneration, expectedGeneration + 1));
        if (updated != 1) {
            throw new CapabilityMountPersistenceException(
                    "capability mount set generation is stale");
        }
    }

    /** 读取已存在装配的完整强类型结果。 */
    public CapabilityMountSetResult readState(Long tenantId, Long agentId) {
        requireOwnership(tenantId, agentId);
        AgentCapabilityMountSetDO mountSet = selectSet(tenantId, agentId, false);
        if (mountSet == null) {
            throw new CapabilityMountNotFoundException("capability mount set not found");
        }
        return readState(tenantId, agentId, mountSet);
    }

    /**
     * 读取已存在的装配，不为只读检查隐式创建 mount-set。
     *
     * <p>机构管理服务在创建前用此方法检查同 definition 重复挂载；若首次创建
     * 之前调用普通 {@link #readState(Long, Long)}，会错误地把首次成功 create
     * 的 generation 从 1 推进为 2。
     */
    public Optional<CapabilityMountSetResult> readStateIfPresent(Long tenantId, Long agentId) {
        requireOwnership(tenantId, agentId);
        AgentCapabilityMountSetDO mountSet = selectSet(tenantId, agentId, false);
        return mountSet == null
                ? Optional.empty()
                : Optional.of(readState(tenantId, agentId, mountSet));
    }

    /** 同租户授权目录共享三次批量查询，不加锁、不建立缺失装配代。 */
    public Map<Long, CapabilityMountSetResult> readAllIfPresent(Long tenantId, List<Long> agentIds) {
        List<Long> ids = agentIds.stream().distinct().toList();
        ids.forEach(id -> requireOwnership(tenantId, id));
        if (ids.isEmpty()) return Map.of();
        var sets = mountSetMapper.selectList(new LambdaQueryWrapper<AgentCapabilityMountSetDO>()
                .eq(AgentCapabilityMountSetDO::getTenantId, tenantId)
                .in(AgentCapabilityMountSetDO::getAgentId, ids));
        if (sets.isEmpty()) return Map.of();
        var rows = mountMapper.selectList(new LambdaQueryWrapper<AgentCapabilityMountDO>()
                .eq(AgentCapabilityMountDO::getTenantId, tenantId)
                .in(AgentCapabilityMountDO::getAgentId, sets.stream().map(AgentCapabilityMountSetDO::getAgentId).toList())
                .orderByDesc(AgentCapabilityMountDO::getPriority).orderByAsc(AgentCapabilityMountDO::getId));
        Map<Long, String> deploymentKeys = loadDeploymentKeys(tenantId, rows);
        var byAgent = rows.stream().collect(java.util.stream.Collectors.groupingBy(AgentCapabilityMountDO::getAgentId));
        Map<Long, CapabilityMountSetResult> result = new LinkedHashMap<>();
        for (var set : sets) {
            var mounted = byAgent.getOrDefault(set.getAgentId(), List.of()).stream()
                    .map(row -> assembler.toDomain(row, "TOOL".equals(row.getCapabilityType())
                            ? deploymentKeys.get(row.getToolDeploymentId()) : null)).toList();
            result.put(set.getAgentId(), new CapabilityMountSetResult(tenantId, set.getAgentId(),
                    requirePositive(set.getGeneration(), "generation"), mounted));
        }
        return Map.copyOf(result);
    }

    /** 使用已锁定或刚创建的装配代读取完整强类型结果。 */
    public CapabilityMountSetResult readState(
            Long tenantId, Long agentId, AgentCapabilityMountSetDO mountSet) {
        requireOwnership(tenantId, agentId);
        Objects.requireNonNull(mountSet, "mountSet");
        List<AgentCapabilityMountDO> rows = mountMapper.selectList(
                new LambdaQueryWrapper<AgentCapabilityMountDO>()
                        .eq(AgentCapabilityMountDO::getTenantId, tenantId)
                        .eq(AgentCapabilityMountDO::getAgentId, agentId)
                        .orderByDesc(AgentCapabilityMountDO::getPriority)
                        .orderByAsc(AgentCapabilityMountDO::getId));
        Map<Long, String> deploymentKeys = loadDeploymentKeys(tenantId, rows);
        List<CapabilityMount> mounts = rows.stream()
                .map(row -> assembler.toDomain(row,
                        "TOOL".equals(row.getCapabilityType())
                                ? deploymentKeys.get(row.getToolDeploymentId())
                                : null))
                .toList();
        return new CapabilityMountSetResult(
                tenantId, agentId, requirePositive(mountSet.getGeneration(), "generation"), mounts);
    }

    private AgentCapabilityMountSetDO selectSet(Long tenantId, Long agentId, boolean lock) {
        LambdaQueryWrapper<AgentCapabilityMountSetDO> query = new LambdaQueryWrapper<AgentCapabilityMountSetDO>()
                .eq(AgentCapabilityMountSetDO::getTenantId, tenantId)
                .eq(AgentCapabilityMountSetDO::getAgentId, agentId);
        query.last(lock ? "FOR UPDATE" : "LIMIT 1");
        return mountSetMapper.selectOne(query);
    }

    private void lockAgent(Long tenantId, Long agentId) {
        AgentDO agent = agentMapper.selectOne(new LambdaQueryWrapper<AgentDO>()
                .eq(AgentDO::getTenantId, tenantId)
                .eq(AgentDO::getId, agentId)
                .last("FOR UPDATE"));
        if (agent == null) {
            throw new CapabilityMountNotFoundException("agent not found for tenant");
        }
    }

    private Long toolDeploymentId(CapabilityMount mount) {
        if (!(mount.config() instanceof ToolMountConfig tool)) {
            return null;
        }
        ToolDeploymentLookup.Identity deployment = deploymentMapper.findByKey(mount.tenantId(), tool.toolDeploymentId());
        if (deployment == null || deployment.id() == null) {
            throw new CapabilityMountNotFoundException("tool deployment not found for tenant");
        }
        return deployment.id();
    }

    private String toolDeploymentKey(AgentCapabilityMountDO row) {
        if (!"TOOL".equals(row.getCapabilityType())) {
            return null;
        }
        if (row.getToolDeploymentId() == null) {
            throw new CapabilityMountPersistenceException("tool mount deployment FK is missing");
        }
        ToolDeploymentLookup.Identity deployment = deploymentMapper.findById(row.getTenantId(), row.getToolDeploymentId());
        if (deployment == null || deployment.deploymentKey() == null
                || deployment.deploymentKey().isBlank()) {
            throw new CapabilityMountPersistenceException(
                    "tool deployment key is missing for tenant-scoped FK");
        }
        return deployment.deploymentKey();
    }

    private Map<Long, String> loadDeploymentKeys(
            Long tenantId, List<AgentCapabilityMountDO> rows) {
        List<Long> deploymentIds = rows.stream()
                .filter(row -> "TOOL".equals(row.getCapabilityType()))
                .map(AgentCapabilityMountDO::getToolDeploymentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (deploymentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> keys = new HashMap<>();
        deploymentMapper.findByIds(tenantId, deploymentIds)
                .forEach(deployment -> {
                    if (deployment.id() == null || deployment.deploymentKey() == null
                            || deployment.deploymentKey().isBlank()) {
                        throw new CapabilityMountPersistenceException(
                                "tool deployment key is missing for tenant-scoped FK");
                    }
                    keys.put(deployment.id(), deployment.deploymentKey());
                });
        return keys;
    }

    private void requireOwnership(Long tenantId, Long agentId) {
        if (tenantId == null || tenantId <= 0 || agentId == null || agentId <= 0) {
            throw new IllegalArgumentException("tenantId and agentId must be positive");
        }
    }

    private long positiveId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        try {
            return requirePositive(Long.parseLong(value), field);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be positive", exception);
        }
    }

    private long requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new CapabilityMountPersistenceException(field + " is not positive in persistent row");
        }
        return value;
    }

    /** mount-set 行锁及是否在当前事务中首次创建的结果。 */
    public record MountSetLock(AgentCapabilityMountSetDO value, boolean created) {
        public MountSetLock {
            Objects.requireNonNull(value, "value");
        }
    }
}
