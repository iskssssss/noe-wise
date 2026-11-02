package com.skillforge.core.capability;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.capability.persistence.CapabilityDefinitionMapper;
import com.skillforge.core.capability.persistence.CapabilityVersionMapper;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordMapper;
import com.skillforge.core.capability.persistence.CapabilityDefinitionDO;
import com.skillforge.core.capability.persistence.CapabilityVersionDO;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordDO;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 四类 Capability Catalog 的唯一生命周期服务。
 *
 * <p>服务同时服务机构与平台控制器，但调用者必须显式提供
 * {@link CapabilityCatalogScope}。它不会从请求体推断资源归属，也不会把平台资源
 * 当成某个租户资源写入。所有 Descriptor 创建先通过严格 Codec，再进入 JSONB。
 */
@Service
public class CapabilityCatalogService {

    private static final String DRAFT = "DRAFT";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String ARCHIVED = "ARCHIVED";
    private static final String ACTIVE = "ACTIVE";
    private static final String DISABLED = "DISABLED";

    private final CapabilityDefinitionMapper definitionMapper;
    private final CapabilityVersionMapper versionMapper;
    private final ToolDeploymentRecordMapper deploymentMapper;
    private final CapabilityDescriptorCodec descriptorCodec;
    private final CapabilityCatalogDatabaseContext databaseContext;
    private final Clock clock;
    private final com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation tests;

    @Autowired
    public CapabilityCatalogService(
            CapabilityDefinitionMapper definitionMapper,
            CapabilityVersionMapper versionMapper,
            ToolDeploymentRecordMapper deploymentMapper,
            CapabilityDescriptorCodec descriptorCodec,
            CapabilityCatalogDatabaseContext databaseContext,com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation tests) {
        this(definitionMapper, versionMapper, deploymentMapper, descriptorCodec,
                databaseContext, Clock.systemUTC(),tests);
    }

    public CapabilityCatalogService(
            CapabilityDefinitionMapper definitionMapper,
            CapabilityVersionMapper versionMapper,
            ToolDeploymentRecordMapper deploymentMapper,
            CapabilityDescriptorCodec descriptorCodec,
            CapabilityCatalogDatabaseContext databaseContext,
            Clock clock,com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation tests) {
        this.definitionMapper = Objects.requireNonNull(definitionMapper, "definitionMapper");
        this.versionMapper = Objects.requireNonNull(versionMapper, "versionMapper");
        this.deploymentMapper = Objects.requireNonNull(deploymentMapper, "deploymentMapper");
        this.descriptorCodec = Objects.requireNonNull(descriptorCodec, "descriptorCodec");
        this.databaseContext = Objects.requireNonNull(databaseContext, "databaseContext");
        this.clock = Objects.requireNonNull(clock, "clock");this.tests=Objects.requireNonNull(tests);
    }

    /** 创建平台或租户 Definition；归属由 scope 决定。 */
    @Transactional(rollbackFor = Exception.class)
    public CapabilityDefinitionView createDefinition(
            CapabilityCatalogScope scope,
            CapabilityType capabilityType,
            String capabilityKey,
            String name,
            String description) {
        bind(scope);
        CapabilityType type = requireType(capabilityType);
        String key = requireText(capabilityKey, "capabilityKey");
        String displayName = requireText(name, "name");
        CapabilityDefinitionDO definition = new CapabilityDefinitionDO();
        definition.setId(SnowflakeIds.nextId());
        definition.setTenantId(scope.tenantId());
        definition.setCapabilityType(type.name());
        definition.setCapabilityKey(key);
        definition.setName(displayName);
        definition.setDescription(description == null ? "" : description);
        definition.setTrustLevel(scope.isPlatform() ? "PLATFORM" : "TENANT");
        definition.setStatus(DRAFT);
        definition.setRevision(1L);
        Instant now = clock.instant();
        definition.setCreatedAt(now);
        definition.setUpdatedAt(now);
        try {
            if (definitionMapper.insert(definition) != 1) {
                throw new IllegalStateException("capability definition insert returned no row");
            }
        } catch (DuplicateKeyException exception) {
            throw duplicate("capability definition key already exists", exception);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("capability definition violates catalog constraints", exception);
        }
        return definitionView(definition);
    }

    /** 查询在当前作用域可见的 Definition 列表。 */
    @Transactional(readOnly = true)
    public List<CapabilityDefinitionView> listDefinitions(
            CapabilityCatalogScope scope, CapabilityType capabilityType, String status) {
        bind(scope);
        LambdaQueryWrapper<CapabilityDefinitionDO> query = definitionVisibility(scope)
                .apply("NOT EXISTS (SELECT 1 FROM nw_workflow_test_sessions wt WHERE wt.capability_definition_id=nw_capability_definitions.id)");
        if (capabilityType != null) {
            query.eq(CapabilityDefinitionDO::getCapabilityType, capabilityType.name());
        }
        if (status != null && !status.isBlank()) {
            query.eq(CapabilityDefinitionDO::getStatus, normalizeStatus(status));
        }
        query.orderByDesc(CapabilityDefinitionDO::getUpdatedAt);
        return definitionMapper.selectList(query).stream().map(this::definitionView).toList();
    }

    /** 平台工作台目录保留全部 Skill 定义状态，默认标签只取精确指针。 */
    @Transactional(readOnly = true)
    public List<SkillCatalogSummary> listPlatformSkills() {
        bind(CapabilityCatalogScope.platform());
        return definitionMapper.listPlatformSkills();
    }

    /** 按类型一次读取可选依赖及其精确当前发布版本，不读取 Descriptor。 */
    @Transactional(readOnly = true)
    public List<CapabilityRequirementOption> listRequirementOptions(CapabilityCatalogScope scope, CapabilityType type) {
        bind(scope);
        return definitionMapper.listRequirementOptions(scope.tenantId(), requireType(type));
    }

    /** 查询单个 Definition；跨租户与平台草稿对租户均表现为不存在。 */
    @Transactional(readOnly = true)
    public CapabilityDefinitionView getDefinition(CapabilityCatalogScope scope, Long definitionId) {
        bind(scope);
        return definitionView(requireDefinition(scope, definitionId));
    }

    /**
     * 查找当前写作用域自有的稳定 Definition。
     *
     * <p>租户读取可以看到已发布的平台 Definition，但生产者发布只能复用当前租户
     * 自有行；平台与租户不能因为相同的 type+key 共享 Definition 身份。该方法把
     * 归属谓词放在 Catalog service 内，避免调用方通过可见列表自行推断写入归属。
     */
    @Transactional(readOnly = true)
    public Optional<CapabilityDefinitionView> findDefinitionForWrite(
            CapabilityCatalogScope scope, CapabilityType capabilityType, String capabilityKey) {
        bind(scope);
        CapabilityType type = requireType(capabilityType);
        String key = requireText(capabilityKey, "capabilityKey");
        LambdaQueryWrapper<CapabilityDefinitionDO> query = new LambdaQueryWrapper<CapabilityDefinitionDO>()
                .eq(CapabilityDefinitionDO::getCapabilityType, type.name())
                .eq(CapabilityDefinitionDO::getCapabilityKey, key)
                .last("LIMIT 1");
        if (scope.isPlatform()) {
            query.isNull(CapabilityDefinitionDO::getTenantId);
        } else {
            query.eq(CapabilityDefinitionDO::getTenantId, scope.tenantId());
        }
        return Optional.ofNullable(definitionMapper.selectOne(query)).map(this::definitionView);
    }

    /**
     * 在指定 Definition 下查找精确 versionKey；结果严格限制为 Definition 的归属。
     *
     * <p>该查询用于 producer bridge 的幂等/冲突判定，不把租户可见的平台版本当成
     * 租户自有版本，也不以名称或可变展示字段作为身份。
     */
    @Transactional(readOnly = true)
    public Optional<CapabilityVersionView> findVersionByKey(
            CapabilityCatalogScope scope, Long definitionId, String versionKey) {
        bind(scope);
        CapabilityDefinitionDO definition = requireDefinition(scope, definitionId);
        String key = requireText(versionKey, "versionKey");
        return findVersion(scope, definition, query -> query
                .eq(CapabilityVersionDO::getVersionKey, key)
                .last("ORDER BY CASE WHEN status = 'PUBLISHED' THEN 0 ELSE 1 END, created_at DESC LIMIT 1"));
    }

    /** 查找 Definition 下具有相同内容哈希的版本，用于阻止同一内容被换 key 重复注册。 */
    @Transactional(readOnly = true)
    public Optional<CapabilityVersionView> findVersionByContentHash(
            CapabilityCatalogScope scope, Long definitionId, String contentHash) {
        bind(scope);
        CapabilityDefinitionDO definition = requireDefinition(scope, definitionId);
        String hash = requireText(contentHash, "contentHash");
        return findVersion(scope, definition, query -> query
                .eq(CapabilityVersionDO::getContentHash, hash)
                .last("ORDER BY CASE WHEN status = 'PUBLISHED' THEN 0 ELSE 1 END, created_at DESC LIMIT 1"));
    }

    /** 创建 Draft Version；发布前正文可由 revision/If-Match 更新，发布后不可变。 */
    @Transactional(rollbackFor = Exception.class)
    public CapabilityVersionView createDraftVersion(
            CapabilityCatalogScope scope,
            Long definitionId,
            String versionKey,
            String name,
            String description,
            String contentHash,
            Map<String, Object> descriptor) {
        bind(scope);
        if(!scope.isPlatform())tests.requireOrdinary(scope.tenantId(),"capability",definitionId.toString());
        CapabilityDefinitionDO definition = requireDefinition(scope, definitionId);
        if (!Objects.equals(definition.getTenantId(), scope.tenantId())) {
            throw notFound("capability definition not found");
        }
        definitionMapper.selectOne(definitionVisibility(scope)
                .eq(CapabilityDefinitionDO::getId, definitionId).last("FOR UPDATE"));
        if (ARCHIVED.equals(definition.getStatus()) || "DEPRECATED".equals(definition.getStatus())) {
            throw conflict("closed capability definition cannot receive a new version");
        }
        CapabilityType type = type(definition.getCapabilityType());
        String key = requireText(versionKey, "versionKey");
        String displayName = requireText(name, "name");
        String hash = requireText(contentHash, "contentHash");
        Map<String, Object> safeDescriptor = descriptor == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(descriptor));
        CapabilityVersionDO working = versionMapper.selectOne(versionVisibility(scope)
                .eq(CapabilityVersionDO::getDefinitionId, definition.getId())
                .eq(CapabilityVersionDO::getStatus, DRAFT)
                .orderByDesc(CapabilityVersionDO::getCreatedAt).last("LIMIT 1 FOR UPDATE"));
        Long versionId = working == null ? SnowflakeIds.nextId() : working.getId();
        try {
            descriptorCodec.decode(
                    new CapabilityRef(type, Long.toString(definition.getId()), Long.toString(versionId)),
                    definition.getTenantId(), displayName, description, definition.getTrustLevel(), DRAFT,
                    hash, safeDescriptor);
        } catch (IllegalArgumentException exception) {
            throw invalid("descriptor validation failed", exception);
        }
        CapabilityVersionDO version = new CapabilityVersionDO();
        version.setId(versionId);
        version.setDefinitionId(definition.getId());
        version.setTenantId(definition.getTenantId());
        version.setCapabilityType(type.name());
        version.setVersionKey(key);
        version.setName(displayName);
        version.setDescription(description == null ? "" : description);
        version.setTrustLevel(definition.getTrustLevel());
        version.setDescriptor(safeDescriptor);
        version.setContentHash(hash);
        version.setStatus(DRAFT);
        version.setCreatedAt(working == null ? clock.instant() : working.getCreatedAt());
        try {
            int changed = working == null ? versionMapper.insert(version) : versionMapper.updateById(version);
            if (changed != 1) {
                throw new IllegalStateException("capability version insert returned no row");
            }
            if (working != null && definitionMapper.update(null, new LambdaUpdateWrapper<CapabilityDefinitionDO>()
                    .eq(CapabilityDefinitionDO::getId, definition.getId())
                    .eq(CapabilityDefinitionDO::getRevision, definition.getRevision())
                    .set(CapabilityDefinitionDO::getUpdatedAt, clock.instant())
                    .setSql("revision = revision + 1")) != 1) throw conflict("capability definition revision is stale");
        } catch (DuplicateKeyException exception) {
            throw duplicate("capability version key or content hash already exists", exception);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("capability version violates catalog constraints", exception);
        }
        return versionView(version);
    }

    /**
     * 对 Draft Version 执行实时、确定性的严格 Codec 校验。
     *
     * <p>校验结果不持久化，返回的 Definition revision 供随后发布的 If-Match 使用；
     * 发布会在同一事务内重新执行该校验，避免验证与发布之间出现语义漂移。
     */
    @Transactional(readOnly = true)
    public CapabilityValidationView validateVersion(
            CapabilityCatalogScope scope, Long definitionId, Long versionId) {
        bind(scope);
        CapabilityDefinitionDO definition = requireDefinition(scope, definitionId);
        CapabilityVersionDO version = requireVersion(scope, versionId);
        ensureVersionBelongs(definition, version);
        return validateVersionSnapshot(definition, version);
    }

    /** 更新 Draft 正文；Definition revision 作为 If-Match CAS 版本。 */
    @Transactional(rollbackFor = Exception.class)
    public CapabilityVersionView updateDraftVersion(
            CapabilityCatalogScope scope,
            Long definitionId,
            Long versionId,
            long expectedRevision,
            String name,
            String description,
            String contentHash,
            Map<String, Object> descriptor) {
        bind(scope);
        if (expectedRevision <= 0) {
            throw invalid("If-Match revision must be positive", null);
        }
        CapabilityDefinitionDO definition = requireDefinition(scope, definitionId);
        CapabilityVersionDO version = requireVersion(scope, versionId);
        if(!scope.isPlatform())tests.requireOrdinary(scope.tenantId(),"capability",version.getDefinitionId().toString());
        ensureVersionBelongs(definition, version);
        if (!DRAFT.equals(version.getStatus())) {
            throw conflict("only a draft capability version can be edited");
        }
        if (ARCHIVED.equals(definition.getStatus()) || "DEPRECATED".equals(definition.getStatus())) {
            throw conflict("closed capability definition cannot edit a version");
        }
        String displayName = requireText(name, "name");
        String hash = requireText(contentHash, "contentHash");
        Map<String, Object> safeDescriptor = descriptor == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(descriptor));
        validateDescriptor(definition, version, displayName, description, hash, safeDescriptor);
        Instant now = clock.instant();
        LambdaUpdateWrapper<CapabilityDefinitionDO> definitionUpdate =
                new LambdaUpdateWrapper<CapabilityDefinitionDO>()
                        .eq(CapabilityDefinitionDO::getId, definition.getId())
                        .eq(CapabilityDefinitionDO::getRevision, expectedRevision)
                        .set(CapabilityDefinitionDO::getUpdatedAt, now)
                        .setSql("revision = revision + 1");
        if (scope.isPlatform()) {
            definitionUpdate.isNull(CapabilityDefinitionDO::getTenantId);
        } else {
            definitionUpdate.eq(CapabilityDefinitionDO::getTenantId, scope.tenantId());
        }
        if (definitionMapper.update(null, definitionUpdate) != 1) {
            throw conflict("capability definition revision is stale");
        }
        LambdaUpdateWrapper<CapabilityVersionDO> versionUpdate =
                new LambdaUpdateWrapper<CapabilityVersionDO>()
                        .eq(CapabilityVersionDO::getId, version.getId())
                        .eq(CapabilityVersionDO::getDefinitionId, definition.getId())
                        .eq(CapabilityVersionDO::getStatus, DRAFT)
                        .set(CapabilityVersionDO::getName, displayName)
                        .set(CapabilityVersionDO::getDescription, description == null ? "" : description)
                        .set(CapabilityVersionDO::getContentHash, hash)
                        // Wrapper 不继承实体字段的 TypeHandler，必须显式绑定 JSONB。
                        .set(CapabilityVersionDO::getDescriptor, safeDescriptor,
                                "typeHandler=com.skillforge.core.shared.config.JsonbTypeHandler");
        if (scope.isPlatform()) {
            versionUpdate.isNull(CapabilityVersionDO::getTenantId);
        } else {
            versionUpdate.eq(CapabilityVersionDO::getTenantId, scope.tenantId());
        }
        try {
            if (versionMapper.update(null, versionUpdate) != 1) {
                throw conflict("capability version changed while editing");
            }
        } catch (DuplicateKeyException exception) {
            throw duplicate("capability version key or content hash already exists", exception);
        } catch (DataIntegrityViolationException exception) {
            throw invalid("capability version violates catalog constraints", exception);
        }
        return versionView(requireVersion(scope, versionId));
    }

    /** 查询当前作用域可见的 Version 列表。 */
    @Transactional(readOnly = true)
    public List<CapabilityVersionView> listVersions(
            CapabilityCatalogScope scope, Long definitionId, String status) {
        bind(scope);
        requireDefinition(scope, definitionId);
        LambdaQueryWrapper<CapabilityVersionDO> query = versionVisibility(scope)
                .eq(CapabilityVersionDO::getDefinitionId, definitionId);
        if (status != null && !status.isBlank()) {
            query.eq(CapabilityVersionDO::getStatus, normalizeStatus(status));
        }
        query.orderByDesc(CapabilityVersionDO::getCreatedAt);
        return versionMapper.selectList(query).stream().map(this::versionView).toList();
    }

    /** 通用详情版本卡片只读取元数据；定义可见性与完整版本列表一致。 */
    @Transactional(readOnly = true)
    public List<CapabilityVersionSummary> listVersionSummaries(CapabilityCatalogScope scope, Long definitionId) {
        bind(scope);
        var definition = requireDefinition(scope, definitionId);
        return versionMapper.listVersionSummaries(scope.tenantId(), definition.getId());
    }

    /** Skill 历史选择目录；定义不可见或类型不符均为不存在，正文在选定后读取。 */
    @Transactional(readOnly = true)
    public List<SkillVersionSummary> listSkillVersionSummaries(CapabilityCatalogScope scope, Long definitionId) {
        bind(scope);
        var definition = requireDefinition(scope, definitionId);
        if (type(definition.getCapabilityType()) != CapabilityType.SKILL) throw notFound("skill definition not found");
        return versionMapper.listSkillVersionSummaries(scope.tenantId(), definition.getId());
    }

    /** 查询当前作用域可见的单个 Version。 */
    @Transactional(readOnly = true)
    public CapabilityVersionView getVersion(CapabilityCatalogScope scope, Long versionId) {
        bind(scope);
        return versionView(requireVersion(scope, versionId));
    }

    /** 批量读取作用域内精确发布版本；失效引用不返回，不切换至默认版本。 */
    @Transactional(readOnly = true)
    public List<CapabilityVersionView> publishedVersions(CapabilityCatalogScope scope, List<Long> versionIds) {
        bind(scope);
        if (versionIds.isEmpty()) return List.of();
        var versions = versionMapper.selectList(versionVisibility(scope)
                .in(CapabilityVersionDO::getId, versionIds).eq(CapabilityVersionDO::getStatus, PUBLISHED));
        if (versions.isEmpty()) return List.of();
        var definitions = definitionMapper.selectList(definitionVisibility(scope)
                .in(CapabilityDefinitionDO::getId, versions.stream().map(CapabilityVersionDO::getDefinitionId).distinct().toList())
                .eq(CapabilityDefinitionDO::getStatus, PUBLISHED));
        return versions.stream().filter(version -> definitions.stream().anyMatch(definition ->
                Objects.equals(definition.getId(), version.getDefinitionId())
                && Objects.equals(definition.getTenantId(), version.getTenantId())
                && Objects.equals(definition.getCapabilityType(), version.getCapabilityType())))
                .map(this::versionView).toList();
    }

    /** 以 Definition revision CAS 切换 Skill 当前发布；后续执行采用该版本，已冻结执行保持原版本。 */
    @Transactional(rollbackFor = Exception.class)
    public CapabilityDefinitionView setDefaultSkillVersion(
            CapabilityCatalogScope scope,
            Long definitionId,
            Long versionId,
            long expectedRevision) {
        bind(scope);
        if (expectedRevision <= 0) throw invalid("If-Match revision must be positive", null);
        CapabilityDefinitionDO definition = requireDefinition(scope, definitionId);
        CapabilityVersionDO version = requireVersion(scope, versionId);
        ensureVersionBelongs(definition, version);
        if (type(definition.getCapabilityType()) != CapabilityType.SKILL
                || !PUBLISHED.equals(version.getStatus())) {
            throw conflict("skill default requires a published version");
        }
        LambdaUpdateWrapper<CapabilityDefinitionDO> update =
                new LambdaUpdateWrapper<CapabilityDefinitionDO>()
                        .eq(CapabilityDefinitionDO::getId, definition.getId())
                        .eq(CapabilityDefinitionDO::getRevision, expectedRevision)
                        .set(CapabilityDefinitionDO::getDefaultVersionId, version.getId())
                        .set(CapabilityDefinitionDO::getUpdatedAt, clock.instant())
                        .setSql("revision = revision + 1");
        if (scope.isPlatform()) update.isNull(CapabilityDefinitionDO::getTenantId);
        else update.eq(CapabilityDefinitionDO::getTenantId, scope.tenantId());
        if (definitionMapper.update(null, update) != 1) {
            throw conflict("capability definition revision is stale");
        }
        return definitionView(requireDefinition(scope, definitionId));
    }

    @Transactional(readOnly = true)
    public CapabilityVersionView getDefaultSkillVersion(
            CapabilityCatalogScope scope, Long definitionId) {
        bind(scope);
        CapabilityDefinitionDO definition = requireDefinition(scope, definitionId);
        if (type(definition.getCapabilityType()) != CapabilityType.SKILL
                || definition.getDefaultVersionId() == null) {
            throw notFound("skill default version not found");
        }
        CapabilityVersionDO version = requireVersion(scope, definition.getDefaultVersionId());
        ensureVersionBelongs(definition, version);
        if (!PUBLISHED.equals(version.getStatus())) {
            throw notFound("skill default version not found");
        }
        return versionView(version);
    }

    /** 管理绑定仅保存稳定 Definition，新执行通过该入口选择当前发布快照。 */
    @Transactional(readOnly = true)
    public CapabilityVersionView getCurrentPublishedVersion(
            CapabilityCatalogScope scope, Long definitionId) {
        bind(scope);
        CapabilityDefinitionDO definition = requireDefinition(scope, definitionId);
        if (!PUBLISHED.equals(definition.getStatus()) || definition.getDefaultVersionId() == null) {
            throw notFound("capability current publication not found");
        }
        CapabilityVersionDO version = requireVersion(scope, definition.getDefaultVersionId());
        ensureVersionBelongs(definition, version);
        if (!PUBLISHED.equals(version.getStatus())) throw notFound("capability current publication not found");
        return versionView(version);
    }

    /** 批量按稳定 Definition 解析当前发布；缺失或未发布资源不返回。 */
    @Transactional(readOnly = true)
    public List<CapabilityVersionView> currentPublishedVersions(CapabilityCatalogScope scope, List<Long> definitionIds) {
        bind(scope);
        if (definitionIds.isEmpty()) return List.of();
        var definitions = definitionMapper.selectList(definitionVisibility(scope)
                .in(CapabilityDefinitionDO::getId, definitionIds)
                .eq(CapabilityDefinitionDO::getStatus, PUBLISHED)
                .isNotNull(CapabilityDefinitionDO::getDefaultVersionId));
        if (definitions.isEmpty()) return List.of();
        var versions = versionMapper.selectList(versionVisibility(scope)
                .in(CapabilityVersionDO::getId, definitions.stream().map(CapabilityDefinitionDO::getDefaultVersionId).toList())
                .eq(CapabilityVersionDO::getStatus, PUBLISHED));
        return versions.stream().filter(version -> definitions.stream().anyMatch(definition ->
                Objects.equals(definition.getDefaultVersionId(), version.getId())
                && Objects.equals(definition.getId(), version.getDefinitionId())
                && Objects.equals(definition.getTenantId(), version.getTenantId())
                && Objects.equals(definition.getCapabilityType(), version.getCapabilityType())))
                .map(this::versionView).toList();
    }

    /**
     * Draft -> Published 的 Definition revision CAS。
     *
     * <p>先 CAS 更新 Definition，再更新 Version 状态；任一步失败都回滚。正文、哈希
     * 与身份字段没有更新路径，并由 V87 trigger 再次兜底。
     */
    @Transactional(rollbackFor = Exception.class)
    public CapabilityVersionView publishVersion(
            CapabilityCatalogScope scope, Long definitionId, Long versionId, long expectedRevision) {
        bind(scope);
        if(!scope.isPlatform())tests.requireOrdinary(scope.tenantId(),"capability",definitionId.toString());
        if (expectedRevision <= 0) {
            throw invalid("If-Match revision must be positive", null);
        }
        CapabilityDefinitionDO definition = requireDefinition(scope, definitionId);
        CapabilityVersionDO version = requireVersion(scope, versionId);
        if (!Objects.equals(version.getDefinitionId(), definition.getId())
                || !Objects.equals(version.getTenantId(), definition.getTenantId())
                || !Objects.equals(version.getCapabilityType(), definition.getCapabilityType())) {
            throw notFound("capability version not found");
        }
        if (!DRAFT.equals(version.getStatus())) {
            throw conflict("only a draft capability version can be published");
        }
        if (ARCHIVED.equals(definition.getStatus()) || "DEPRECATED".equals(definition.getStatus())) {
            throw conflict("closed capability definition cannot be published");
        }
        CapabilityValidationView validation = validateVersionSnapshot(definition, version);
        if (!validation.valid()) {
            CapabilityCatalogError first = validation.errors().get(0);
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, first.code(), first.message());
        }
        Instant now = clock.instant();
        LambdaUpdateWrapper<CapabilityDefinitionDO> definitionUpdate =
                new LambdaUpdateWrapper<CapabilityDefinitionDO>()
                        .eq(CapabilityDefinitionDO::getId, definition.getId())
                        .eq(CapabilityDefinitionDO::getRevision, expectedRevision)
                        .eq(CapabilityDefinitionDO::getCapabilityType, definition.getCapabilityType())
                        .set(CapabilityDefinitionDO::getStatus, PUBLISHED)
                        .set(CapabilityDefinitionDO::getUpdatedAt, now)
                        .setSql("revision = revision + 1");
        if (scope.isPlatform()) {
            definitionUpdate.isNull(CapabilityDefinitionDO::getTenantId);
        } else {
            definitionUpdate.eq(CapabilityDefinitionDO::getTenantId, scope.tenantId());
        }
        if (definitionMapper.update(null, definitionUpdate) != 1) {
            throw conflict("capability definition revision is stale");
        }
        LambdaUpdateWrapper<CapabilityVersionDO> versionUpdate =
                new LambdaUpdateWrapper<CapabilityVersionDO>()
                        .eq(CapabilityVersionDO::getId, version.getId())
                        .eq(CapabilityVersionDO::getDefinitionId, definition.getId())
                        .eq(CapabilityVersionDO::getStatus, DRAFT)
                        .set(CapabilityVersionDO::getStatus, PUBLISHED)
                        .set(CapabilityVersionDO::getPublishedAt, now);
        // 恢复或沿用历史展示标识时保留包内容，在发布边界分配新的内部修订标识。
        var previousKey = versionMapper.selectOne(versionVisibility(scope)
                .eq(CapabilityVersionDO::getDefinitionId, definition.getId())
                .eq(CapabilityVersionDO::getVersionKey, version.getVersionKey())
                .ne(CapabilityVersionDO::getStatus, DRAFT).last("LIMIT 1"));
        if (previousKey != null && !Objects.equals(previousKey.getId(), version.getId())) {
            versionUpdate.set(CapabilityVersionDO::getVersionKey, "revision-" + version.getId());
        }
        if (scope.isPlatform()) {
            versionUpdate.isNull(CapabilityVersionDO::getTenantId);
        } else {
            versionUpdate.eq(CapabilityVersionDO::getTenantId, scope.tenantId());
        }
        if (versionMapper.update(null, versionUpdate) != 1) {
            throw conflict("capability version changed while publishing");
        }
        // Definition 的 CAS 已锁定该稳定身份；先发布不可变快照，再推进指针以满足数据库发布守卫。
        if (definitionMapper.update(null, new LambdaUpdateWrapper<CapabilityDefinitionDO>()
                .eq(CapabilityDefinitionDO::getId, definition.getId())
                .eq(CapabilityDefinitionDO::getRevision, expectedRevision + 1)
                .set(CapabilityDefinitionDO::getDefaultVersionId, version.getId())
                .set(CapabilityDefinitionDO::getName, version.getName())
                .set(CapabilityDefinitionDO::getDescription, version.getDescription())) != 1) {
            throw conflict("capability current publication changed while publishing");
        }
        return versionView(requireVersion(scope, versionId));
    }

    /** 创建租户 ToolDeployment，只接受已发布且可访问的 TOOL Version。 */
    @Transactional(rollbackFor = Exception.class)
    public CapabilityDeploymentView createDeployment(
            CapabilityCatalogScope scope,
            String deploymentKey,
            Long toolVersionId,
            ToolDeployment.ProviderType providerType,
            String providerRef,
            ToolDeployment.Environment environment,
            String status) {
        bind(scope);
        if (scope.isPlatform()) {
            throw error(HttpStatus.FORBIDDEN, CapabilityCatalogErrors.SCOPE_FORBIDDEN,
                    "ToolDeployment 必须归属租户");
        }
        String key = requireText(deploymentKey, "deploymentKey");
        String ref = requireText(providerRef, "providerRef");
        if (providerType == null) {
            throw invalid("unsupported providerType", null);
        }
        if (environment == null) {
            throw invalid("unsupported environment", null);
        }
        String normalizedStatus = status == null || status.isBlank() ? ACTIVE : normalizeDeploymentStatus(status);
        CapabilityVersionDO version = versionMapper.selectOne(
                new LambdaQueryWrapper<CapabilityVersionDO>()
                        .eq(CapabilityVersionDO::getId, requirePositive(toolVersionId, "toolVersionId"))
                        .eq(CapabilityVersionDO::getCapabilityType, CapabilityType.TOOL.name())
                        .eq(CapabilityVersionDO::getStatus, PUBLISHED)
                        .and(query -> query.isNull(CapabilityVersionDO::getTenantId)
                                .or().eq(CapabilityVersionDO::getTenantId, scope.tenantId()))
                        .last("LIMIT 1"));
        if (version == null) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY,
                    CapabilityCatalogErrors.VERSION_NOT_PUBLISHED,
                    "published TOOL version not found");
        }
        // MCP 仅支持只读幂等；业务参数中的去重键不构成 Runtime 崩溃重放协议。
        if (providerType == ToolDeployment.ProviderType.MCP
                && Boolean.TRUE.equals(version.getDescriptor().get("idempotent"))
                && !"READ_ONLY".equals(version.getDescriptor().get("effect"))) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, CapabilityCatalogErrors.DEPLOYMENT_INVALID,
                    "MCP 暂不支持幂等写入重放，请使用非幂等写入契约");
        }
        // The domain record rejects production TEST_MOCK before the row is written.
        try {
            new ToolDeployment(key, scope.tenantId(),
                    new CapabilityRef(CapabilityType.TOOL,
                            Long.toString(version.getDefinitionId()), Long.toString(version.getId())),
                    providerType, ref, 1L, environment, ACTIVE.equals(normalizedStatus));
        } catch (IllegalArgumentException exception) {
            throw invalid("tool deployment violates deployment constraints", exception);
        }
        ToolDeploymentRecordDO deployment = new ToolDeploymentRecordDO();
        deployment.setId(SnowflakeIds.nextId());
        deployment.setTenantId(scope.tenantId());
        deployment.setDeploymentKey(key);
        deployment.setToolVersionId(version.getId());
        deployment.setProviderType(providerType.name());
        deployment.setProviderRef(ref);
        deployment.setEnvironment(environment.name());
        deployment.setStatus(normalizedStatus);
        deployment.setRevision(1L);
        Instant now = clock.instant();
        deployment.setCreatedAt(now);
        deployment.setUpdatedAt(now);
        try {
            if (deploymentMapper.insert(deployment) != 1) {
                throw new IllegalStateException("tool deployment insert returned no row");
            }
        } catch (DuplicateKeyException exception) {
            throw duplicate("tool deployment key already exists", exception);
        } catch (DataIntegrityViolationException exception) {
            throw invalid("tool deployment violates catalog constraints", exception);
        }
        return deploymentView(deployment);
    }

    /** 目录仅解析当前可自动调用的工具；部署旧版本保留稳定 Definition 身份。 */
    @Transactional(readOnly = true)
    public List<AvailableToolDeployment> availableToolDeployments(CapabilityCatalogScope scope) {
        bind(scope);
        if (scope.isPlatform()) {
            throw error(HttpStatus.FORBIDDEN, CapabilityCatalogErrors.SCOPE_FORBIDDEN,
                    "平台作用域不能读取租户 Deployment");
        }
        return deploymentMapper.listAvailableTools(scope.tenantId());
    }

    /** 租户查询自己的 Deployment。 */
    @Transactional(readOnly = true)
    public List<CapabilityDeploymentView> listDeployments(CapabilityCatalogScope scope, String status) {
        bind(scope);
        if (scope.isPlatform()) {
            throw error(HttpStatus.FORBIDDEN, CapabilityCatalogErrors.SCOPE_FORBIDDEN,
                    "平台作用域不能读取租户 Deployment");
        }
        LambdaQueryWrapper<ToolDeploymentRecordDO> query = new LambdaQueryWrapper<ToolDeploymentRecordDO>()
                .eq(ToolDeploymentRecordDO::getTenantId, scope.tenantId());
        if (status != null && !status.isBlank()) {
            query.eq(ToolDeploymentRecordDO::getStatus, normalizeDeploymentStatus(status));
        }
        query.orderByDesc(ToolDeploymentRecordDO::getUpdatedAt);
        return deploymentMapper.selectList(query).stream().map(this::deploymentView).toList();
    }

    /** 租户查询自己的 Deployment。 */
    @Transactional(readOnly = true)
    public CapabilityDeploymentView getDeployment(
            CapabilityCatalogScope scope, String deploymentKey) {
        bind(scope);
        if (scope.isPlatform()) {
            throw error(HttpStatus.FORBIDDEN, CapabilityCatalogErrors.SCOPE_FORBIDDEN,
                    "平台作用域不能读取租户 Deployment");
        }
        return deploymentView(requireDeployment(scope, deploymentKey));
    }

    /** 以 Deployment revision CAS 启停。 */
    @Transactional(rollbackFor = Exception.class)
    public CapabilityDeploymentView setDeploymentActive(
            CapabilityCatalogScope scope, String deploymentKey, long expectedRevision, boolean active) {
        bind(scope);
        if (scope.isPlatform()) {
            throw error(HttpStatus.FORBIDDEN, CapabilityCatalogErrors.SCOPE_FORBIDDEN,
                    "平台作用域不能修改租户 Deployment");
        }
        if (expectedRevision <= 0) {
            throw invalid("If-Match revision must be positive", null);
        }
        ToolDeploymentRecordDO current = requireDeployment(scope, deploymentKey);
        LambdaUpdateWrapper<ToolDeploymentRecordDO> update =
                new LambdaUpdateWrapper<ToolDeploymentRecordDO>()
                        .eq(ToolDeploymentRecordDO::getId, current.getId())
                        .eq(ToolDeploymentRecordDO::getTenantId, scope.tenantId())
                        .eq(ToolDeploymentRecordDO::getRevision, expectedRevision)
                        .set(ToolDeploymentRecordDO::getStatus, active ? ACTIVE : DISABLED)
                        .set(ToolDeploymentRecordDO::getUpdatedAt, clock.instant())
                        .setSql("revision = revision + 1");
        if (deploymentMapper.update(null, update) != 1) {
            throw conflict("tool deployment revision is stale");
        }
        return deploymentView(requireDeployment(scope, deploymentKey));
    }

    private void bind(CapabilityCatalogScope scope) {
        Objects.requireNonNull(scope, "scope");
        if (scope.isPlatform()) {
            databaseContext.bindPlatform();
        } else {
            databaseContext.bindTenant(scope.tenantId());
        }
    }

    private LambdaQueryWrapper<CapabilityDefinitionDO> definitionVisibility(
            CapabilityCatalogScope scope) {
        LambdaQueryWrapper<CapabilityDefinitionDO> query = new LambdaQueryWrapper<>();
        if (scope.isPlatform()) {
            query.isNull(CapabilityDefinitionDO::getTenantId);
        } else {
            query.and(value -> value.eq(CapabilityDefinitionDO::getTenantId, scope.tenantId())
                    .or(or -> or.isNull(CapabilityDefinitionDO::getTenantId)
                            .eq(CapabilityDefinitionDO::getStatus, PUBLISHED)));
        }
        return query;
    }

    private LambdaQueryWrapper<CapabilityVersionDO> versionVisibility(
            CapabilityCatalogScope scope) {
        LambdaQueryWrapper<CapabilityVersionDO> query = new LambdaQueryWrapper<>();
        if (scope.isPlatform()) {
            query.isNull(CapabilityVersionDO::getTenantId);
        } else {
            query.and(value -> value.eq(CapabilityVersionDO::getTenantId, scope.tenantId())
                    .or(or -> or.isNull(CapabilityVersionDO::getTenantId)
                            .eq(CapabilityVersionDO::getStatus, PUBLISHED)));
        }
        return query;
    }

    private CapabilityDefinitionDO requireDefinition(
            CapabilityCatalogScope scope, Long definitionId) {
        Long id = requirePositive(definitionId, "definitionId");
        CapabilityDefinitionDO definition = definitionMapper.selectOne(
                definitionVisibility(scope).eq(CapabilityDefinitionDO::getId, id).last("LIMIT 1"));
        if (definition == null) {
            throw notFound("capability definition not found");
        }
        return definition;
    }

    private void ensureVersionBelongs(
            CapabilityDefinitionDO definition, CapabilityVersionDO version) {
        if (!Objects.equals(version.getDefinitionId(), definition.getId())
                || !Objects.equals(version.getTenantId(), definition.getTenantId())
                || !Objects.equals(version.getCapabilityType(), definition.getCapabilityType())) {
            throw notFound("capability version not found");
        }
    }

    private CapabilityValidationView validateVersionSnapshot(
            CapabilityDefinitionDO definition, CapabilityVersionDO version) {
        List<CapabilityCatalogError> errors = new java.util.ArrayList<>();
        if (!DRAFT.equals(version.getStatus())) {
            errors.add(new CapabilityCatalogError(
                    CapabilityCatalogErrors.CONFLICT,
                    "only a draft capability version can be validated"));
        }
        try {
            validateDescriptor(definition, version, version.getName(), version.getDescription(),
                    version.getContentHash(), version.getDescriptor());
        } catch (ApiException exception) {
            if (exception.getCode().startsWith("CAPABILITY_") && exception.getDetail() instanceof String message) {
                errors.add(new CapabilityCatalogError(exception.getCode(), message));
            } else {
                errors.add(new CapabilityCatalogError(
                        CapabilityCatalogErrors.DESCRIPTOR_INVALID,
                        "descriptor validation failed"));
            }
        }
        return new CapabilityValidationView(definition.getId(), version.getId(),
                definition.getRevision(), errors.isEmpty(), errors);
    }

    private void validateDescriptor(
            CapabilityDefinitionDO definition,
            CapabilityVersionDO version,
            String name,
            String description,
            String contentHash,
            Map<String, Object> descriptor) {
        try {
            descriptorCodec.decode(
                    new CapabilityRef(type(definition.getCapabilityType()),
                            Long.toString(definition.getId()), Long.toString(version.getId())),
                    definition.getTenantId(), name, description, definition.getTrustLevel(),
                    DRAFT, contentHash, descriptor == null ? Map.of() : descriptor);
        } catch (IllegalArgumentException exception) {
            throw invalid("descriptor validation failed", exception);
        }
    }

    private CapabilityVersionDO requireVersion(CapabilityCatalogScope scope, Long versionId) {
        Long id = requirePositive(versionId, "versionId");
        CapabilityVersionDO version = versionMapper.selectOne(
                versionVisibility(scope).eq(CapabilityVersionDO::getId, id).last("LIMIT 1"));
        if (version == null) {
            throw notFound("capability version not found");
        }
        return version;
    }

    private Optional<CapabilityVersionView> findVersion(
            CapabilityCatalogScope scope,
            CapabilityDefinitionDO definition,
            Consumer<LambdaQueryWrapper<CapabilityVersionDO>> condition) {
        LambdaQueryWrapper<CapabilityVersionDO> query = new LambdaQueryWrapper<CapabilityVersionDO>()
                .eq(CapabilityVersionDO::getDefinitionId, definition.getId())
                .last("LIMIT 1");
        if (scope.isPlatform()) {
            query.isNull(CapabilityVersionDO::getTenantId);
        } else {
            query.eq(CapabilityVersionDO::getTenantId, scope.tenantId());
        }
        condition.accept(query);
        return Optional.ofNullable(versionMapper.selectOne(query)).map(this::versionView);
    }

    private ToolDeploymentRecordDO requireDeployment(
            CapabilityCatalogScope scope, String deploymentKey) {
        String key = requireText(deploymentKey, "deploymentKey");
        ToolDeploymentRecordDO deployment = deploymentMapper.selectOne(
                new LambdaQueryWrapper<ToolDeploymentRecordDO>()
                        .eq(ToolDeploymentRecordDO::getTenantId, scope.tenantId())
                        .eq(ToolDeploymentRecordDO::getDeploymentKey, key)
                        .last("LIMIT 1"));
        if (deployment == null) {
            throw notFound("tool deployment not found");
        }
        return deployment;
    }

    private CapabilityDefinitionView definitionView(CapabilityDefinitionDO value) {
        return new CapabilityDefinitionView(value.getId(), value.getTenantId(),
                type(value.getCapabilityType()), value.getCapabilityKey(), value.getName(),
                value.getDescription(), value.getTrustLevel(), value.getStatus(),
                value.getDefaultVersionId(), value.getRevision(),
                value.getCreatedAt(), value.getUpdatedAt());
    }

    private CapabilityVersionView versionView(CapabilityVersionDO value) {
        return new CapabilityVersionView(value.getId(), value.getDefinitionId(), value.getTenantId(),
                type(value.getCapabilityType()), value.getVersionKey(), value.getName(),
                value.getDescription(), value.getTrustLevel(),
                immutableDescriptor(value.getDescriptor()),
                value.getContentHash(), value.getStatus(), value.getCreatedAt(), value.getPublishedAt());
    }

    private CapabilityDeploymentView deploymentView(ToolDeploymentRecordDO value) {
        return new CapabilityDeploymentView(value.getId(), value.getTenantId(), value.getDeploymentKey(),
                value.getToolVersionId(), providerType(value.getProviderType()), value.getProviderRef(),
                environment(value.getEnvironment()), value.getStatus(), value.getRevision(),
                value.getCreatedAt(), value.getUpdatedAt());
    }

    private Map<String, Object> immutableDescriptor(Map<String, Object> descriptor) {
        return descriptor == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(descriptor));
    }

    private CapabilityType requireType(CapabilityType value) {
        if (value == null) {
            throw invalid("capabilityType is required", null);
        }
        return value;
    }

    private CapabilityType type(String value) {
        try {
            return CapabilityType.valueOf(
                    requireText(value, "capabilityType").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid("unsupported capability type", exception);
        }
    }

    private ToolDeployment.ProviderType providerType(String value) {
        try {
            return ToolDeployment.ProviderType.valueOf(
                    requireText(value, "providerType").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid("unsupported providerType", exception);
        }
    }

    private ToolDeployment.Environment environment(String value) {
        try {
            return ToolDeployment.Environment.valueOf(
                    requireText(value, "environment").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid("unsupported environment", exception);
        }
    }

    private String normalizeStatus(String value) {
        String normalized = requireText(value, "status").toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case DRAFT, PUBLISHED, "DEPRECATED", ARCHIVED -> normalized;
            default -> throw invalid("unsupported capability status", null);
        };
    }

    private String normalizeDeploymentStatus(String value) {
        String normalized = requireText(value, "status").toUpperCase(Locale.ROOT);
        if (!ACTIVE.equals(normalized) && !DISABLED.equals(normalized)) {
            throw invalid("unsupported deployment status", null);
        }
        return normalized;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required", null);
        }
        return value.trim();
    }

    private Long requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw invalid(field + " must be positive", null);
        }
        return value;
    }

    private ApiException notFound(String message) {
        return error(HttpStatus.NOT_FOUND, CapabilityCatalogErrors.NOT_FOUND, message);
    }

    private ApiException conflict(String message, Throwable cause) {
        String code = message.contains("revision")
                ? CapabilityCatalogErrors.REVISION_CONFLICT : CapabilityCatalogErrors.CONFLICT;
        return error(HttpStatus.CONFLICT, code, message, cause);
    }

    private ApiException conflict(String message) {
        return conflict(message, null);
    }

    private ApiException duplicate(String message, Throwable cause) {
        return error(HttpStatus.CONFLICT, CapabilityCatalogErrors.DUPLICATE, message, cause);
    }

    private ApiException invalid(String message, Throwable cause) {
        String code = message.contains("descriptor")
                ? CapabilityCatalogErrors.DESCRIPTOR_INVALID
                : message.contains("If-Match")
                        ? CapabilityCatalogErrors.IF_MATCH_INVALID
                : message.contains("provider") || message.contains("environment")
                        || message.contains("deployment")
                ? CapabilityCatalogErrors.DEPLOYMENT_INVALID
                : message.contains("capability type")
                        ? CapabilityCatalogErrors.INVALID_TYPE
                        : CapabilityCatalogErrors.INVALID_ARGUMENT;
        return error(HttpStatus.UNPROCESSABLE_ENTITY, code, message, cause);
    }

    private ApiException error(HttpStatus status, String code, String message) {
        return CapabilityCatalogErrors.exception(status, code, message);
    }

    private ApiException error(HttpStatus status, String code, String message, Throwable cause) {
        return CapabilityCatalogErrors.exception(status, code, message, cause);
    }
}
