package com.skillforge.core.capability;

import com.skillforge.core.shared.config.SnowflakeIds;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.error.MarkedYAMLException;

/** 技能维护台的编辑生命周期。未完成内容只留在草稿，发布事务才创建可挂载版本。 */
@Service
public class SkillDraftService {
    private final CapabilityCatalogService catalog;
    private final CapabilityCatalogDatabaseContext context;
    private final CapabilityDescriptorCodec codec;
    private final SkillDraftRepository drafts;
    private final SkillPackageValidator packages = new SkillPackageValidator();

    public SkillDraftService(CapabilityCatalogService catalog, CapabilityCatalogDatabaseContext context,
            CapabilityDescriptorCodec codec, SkillDraftRepository drafts) {
        this.catalog = catalog;
        this.context = context;
        this.codec = codec;
        this.drafts = drafts;
    }

    @Transactional(readOnly = true)
    public Optional<SkillDraftView> active(CapabilityCatalogScope scope, Long definitionId) {
        bind(scope);
        ownDefinition(scope, definitionId, false);
        return drafts.active(scope, definitionId).map(SkillDraftRepository.Stored::view);
    }

    /** 定义与初始工作草稿由本入口的同一事务创建；草稿失败时定义一并回滚，不发布或生成包。 */
    @Transactional(rollbackFor = Exception.class)
    public SkillDraftView createSkill(CapabilityCatalogScope scope, String capabilityKey, String name, String description) {
        var definition = catalog.createDefinition(scope, CapabilityType.SKILL, capabilityKey, name, description);
        return create(scope, definition.id(), null);
    }

    @Transactional
    public SkillDraftView create(CapabilityCatalogScope scope, Long definitionId, Long baseVersionId) {
        bind(scope);
        if (!drafts.lockDefinition(scope, definitionId)) throw missing();
        ownDefinition(scope, definitionId, true);
        var existing = drafts.active(scope, definitionId);
        if (existing.isPresent()) {
            if (baseVersionId != null) {
                var base = catalog.getVersion(scope, baseVersionId);
                if (!base.definitionId().equals(definitionId)
                        || !Objects.equals(base.tenantId(), scope.tenantId())) throw missing();
                var current = existing.get().view();
                if (!drafts.restore(scope, current.id(), current.revision(), baseVersionId,
                        versionContent(decodedVersion(base), "DRAFT".equals(base.status()) ? base.versionKey() : ""))) throw stale();
                return require(scope, current.id(), false).view();
            }
            return existing.get().view();
        }
        SkillDraftContent content = new SkillDraftContent("", Map.of(), true, true,
                SkillActivationMode.ON_DEMAND, null, List.of());
        if (baseVersionId != null) {
            var base = catalog.getVersion(scope, baseVersionId);
            if (!base.definitionId().equals(definitionId) || !Objects.equals(base.tenantId(), scope.tenantId())) throw missing();
            content = versionContent(decodedVersion(base), "DRAFT".equals(base.status()) ? base.versionKey() : "");
        }
        long id = SnowflakeIds.nextId();
        drafts.insert(scope, id, definitionId, baseVersionId, content);
        return require(scope, id, false).view();
    }

    @Transactional(readOnly = true)
    public SkillDraftView get(CapabilityCatalogScope scope, Long draftId) {
        bind(scope);
        return require(scope, draftId, false).view();
    }

    /** 只读版本可包含机构可见的平台公共包，不提供平台编辑草稿。 */
    @Transactional(readOnly = true)
    public SkillDraftView version(CapabilityCatalogScope scope, Long versionId) {
        bind(scope);
        var version = catalog.getVersion(scope, versionId);
        var skill = decodedVersion(version);
        return SkillDraftView.of(version.id(), version.definitionId(), version.id(), 0,
                versionContent(skill, version.versionKey()), "DRAFT".equals(version.status()) ? null : version.id(),
                version.createdAt(), skill.requiresPlan());
    }

    @Transactional
    public SkillDraftView save(CapabilityCatalogScope scope, Long draftId, long expectedRevision, SkillDraftContent content) {
        return saveSelected(scope, draftId, expectedRevision, content, content.requiredCapabilities().stream()
                .map(ref -> new SkillDependencySelection(ref.type(), ref.capabilityId())).toList());
    }

    @Transactional
    public SkillDraftView saveSelected(CapabilityCatalogScope scope, Long draftId, long expectedRevision,
            SkillDraftContent content, List<SkillDependencySelection> selections) {
        bind(scope);
        var current = require(scope, draftId, true).view();
        editable(scope, current, expectedRevision);
        var safe = prepareContent(scope, current, content, selections);
        if (!drafts.save(scope, draftId, expectedRevision, safe)) throw stale();
        return require(scope, draftId, false).view();
    }

    private SkillDraftContent prepareContent(CapabilityCatalogScope scope, SkillDraftView current,
            SkillDraftContent content, List<SkillDependencySelection> selections) {
        SkillDraftContent safe;
        try {
            safe = safeContent(content, packages.safeDirectory(decodeFiles(content.files())));
        } catch (IllegalArgumentException exception) {
            throw invalid(exception.getMessage());
        }
        var options = new EnumMap<CapabilityType, Map<String, String>>(CapabilityType.class);
        if (selections == null || selections.size() > 256) throw invalid("技能依赖数量无效");
        var dependencies = selections.stream().map(reference -> {
            var published = options.computeIfAbsent(reference.type(), type -> {
                var byDefinition = new LinkedHashMap<String, String>();
                catalog.listRequirementOptions(scope, type).forEach(option ->
                        byDefinition.put(option.definitionId().toString(), option.versionId().toString()));
                return byDefinition;
            });
            var currentVersion = published.get(reference.capabilityId());
            if (currentVersion == null) throw invalid("依赖能力不可用或没有已发布版本，请重新选择");
            return new CapabilityRef(reference.type(), reference.capabilityId(), currentVersion);
        }).toList();
        safe = new SkillDraftContent(safe.versionKey(), safe.files(), safe.modelInvocable(), safe.userInvocable(),
                safe.activationMode(), safe.conflictGroup(), dependencies);
        if (current.baseVersionId() != null) {
            var base = catalog.getVersion(scope, current.baseVersionId());
            if ("DRAFT".equals(base.status()) && !base.versionKey().equals(safe.versionKey())) {
                throw conflict("现有版本草稿的版本号不可更改");
            }
        }
        return safe;
    }

    @Transactional
    public SkillDraftView importZip(CapabilityCatalogScope scope, Long draftId, long expectedRevision, byte[] archive) {
        bind(scope);
        var current = require(scope, draftId, true).view();
        editable(scope, current, expectedRevision);
        SkillDraftContent safe;
        try {
            safe = safeContent(current.content(), packages.safeZip(archive));
        } catch (IllegalArgumentException exception) {
            throw invalid(exception.getMessage());
        }
        if (!drafts.save(scope, draftId, expectedRevision, safe)) throw stale();
        return require(scope, draftId, false).view();
    }

    @Transactional(readOnly = true)
    public SkillDraftValidation validate(CapabilityCatalogScope scope, Long draftId, long revision) {
        bind(scope);
        var draft = require(scope, draftId, false).view();
        requireRevision(draft, revision);
        return validation(scope, draft);
    }

    /** 校验编辑器当前内容，不改变草稿或其修订。 */
    @Transactional(readOnly = true)
    public SkillDraftValidation validateSelected(CapabilityCatalogScope scope, Long draftId, long revision,
            SkillDraftContent content, List<SkillDependencySelection> selections) {
        bind(scope);
        var current = require(scope, draftId, false).view();
        editable(scope, current, revision);
        var safe = prepareContent(scope, current, content, selections);
        return validation(scope, SkillDraftView.of(current.id(), current.definitionId(), current.baseVersionId(),
                current.revision(), safe, current.publishedVersionId(), current.updatedAt(), current.requiresPlan()));
    }

    /** 组合操作先锁定义，再保存并校验同一草稿；任何失败均回滚保存和发布。 */
    @Transactional
    public CombinedPublication saveAndPublish(CapabilityCatalogScope scope, Long draftId, long revision,
            long definitionRevision, SkillDraftContent content) {
        return saveAndPublishSelected(scope, draftId, revision, definitionRevision, content,
                content.requiredCapabilities().stream().map(ref -> new SkillDependencySelection(ref.type(), ref.capabilityId())).toList());
    }

    @Transactional
    public CombinedPublication saveAndPublishSelected(CapabilityCatalogScope scope, Long draftId, long revision,
            long definitionRevision, SkillDraftContent content, List<SkillDependencySelection> selections) {
        bind(scope);
        var known = require(scope, draftId, false).view();
        if (!drafts.lockDefinition(scope, known.definitionId())) throw missing();
        var saved = saveSelected(scope, draftId, revision, content, selections);
        var result = publishInternal(scope, draftId, saved.revision(), definitionRevision, true);
        return new CombinedPublication(saved.id(), saved.definitionId(), saved.revision(), result.publication(), result.validation());
    }

    public record CombinedPublication(long draftId, long definitionId, long revision,
            SkillDraftPublication publication, SkillDraftValidation validation) { }

    public static class ValidationFailure extends RuntimeException {
        private final SkillDraftValidation validation;
        public ValidationFailure(SkillDraftValidation validation) {
            super(validation.issues().getFirst().message());
            this.validation = validation;
        }
        public SkillDraftValidation validation() { return validation; }
    }

    /** 定义锁、草稿锁、版本变更和回执在同一事务内，失败时完整回滚。 */
    @Transactional
    public SkillDraftPublication publish(CapabilityCatalogScope scope, Long draftId, long revision, long definitionRevision) {
        return publishInternal(scope, draftId, revision, definitionRevision, false).publication();
    }

    /** 合并校验与发布，调用者可同时展示本次校验提示。 */
    @Transactional
    public PublicationOutcome publishChecked(CapabilityCatalogScope scope, Long draftId, long revision, long definitionRevision) {
        return publishInternal(scope, draftId, revision, definitionRevision, false);
    }

    public record PublicationOutcome(SkillDraftPublication publication, SkillDraftValidation validation) { }

    private PublicationOutcome publishInternal(CapabilityCatalogScope scope, Long draftId, long revision,
            long definitionRevision, boolean detailedFailure) {
        bind(scope);
        var known = require(scope, draftId, false).view();
        if (!drafts.lockDefinition(scope, known.definitionId())) throw missing();
        var stored = require(scope, draftId, true);
        var draft = stored.view();
        requireRevision(draft, revision);
        if (draft.publishedVersionId() != null) {
            return new PublicationOutcome(new SkillDraftPublication(draft.publishedVersionId(), stored.publishedDefinitionRevision()), null);
        }
        var definition = ownDefinition(scope, draft.definitionId(), true);
        if (definition.revision() != definitionRevision) throw stale();
        var result = validation(scope, draft);
        if (!result.valid()) {
            if (detailedFailure) throw new ValidationFailure(result);
            throw invalid(result.issues().getFirst().message());
        }
        var skillPackage = packages.fromDirectory(decodeFiles(draft.files()));
        var descriptor = descriptor(draft.content(), skillPackage);
        var metadata = skillPackage.metadata();
        CapabilityVersionView version;
        var base = draft.baseVersionId() == null ? null : catalog.getVersion(scope, draft.baseVersionId());
        if (base != null && "DRAFT".equals(base.status())) {
            if (!base.versionKey().equals(draft.versionKey())) throw conflict("现有版本草稿的版本号不可更改");
            version = catalog.updateDraftVersion(scope, definition.id(), base.id(), definitionRevision,
                    metadata.name(), metadata.description(), packages.packageSha256(skillPackage), descriptor);
            definitionRevision++;
        } else {
            version = catalog.createDraftVersion(scope, definition.id(), draft.versionKey(), metadata.name(),
                    metadata.description(), packages.packageSha256(skillPackage), descriptor);
        }
        var published = catalog.publishVersion(scope, definition.id(), version.id(), definitionRevision);
        var receipt = new SkillDraftPublication(published.id(), definitionRevision + 1);
        if (!drafts.published(scope, draftId, revision, receipt)) throw stale();
        return new PublicationOutcome(receipt, result);
    }

    private SkillDraftValidation validation(CapabilityCatalogScope scope, SkillDraftView draft) {
        try {
            if (draft.versionKey().isBlank() || draft.versionKey().length() > 128) {
                return new SkillDraftValidation(false, List.of(new SkillDraftValidation.Issue(
                        null, null, SkillDraftValidation.Severity.ERROR, "SKILL_VERSION_KEY_REQUIRED", "请填写不超过 128 字符的版本号")));
            }
            // 编辑保存不隐式移动文件；只有 ZIP/目录导入处理外层包装目录。
            if (!draft.files().containsKey("SKILL.md")) {
                return new SkillDraftValidation(false, List.of(new SkillDraftValidation.Issue(
                        "SKILL.md", null, SkillDraftValidation.Severity.ERROR, "SKILL_ENTRYPOINT_REQUIRED", "技能包根目录缺少 SKILL.md")));
            }
            var skillPackage = packages.fromDirectory(decodeFiles(draft.files()));
            var definition = catalog.getDefinition(scope, draft.definitionId());
            codec.decode(new CapabilityRef(CapabilityType.SKILL, definition.id().toString(),
                            Objects.toString(draft.baseVersionId(), draft.id().toString())), scope.tenantId(),
                    skillPackage.metadata().name(), skillPackage.metadata().description(), definition.trustLevel(),
                    "DRAFT", packages.packageSha256(skillPackage), descriptor(draft.content(), skillPackage));
            return new SkillDraftValidation(true, packages.notices(skillPackage));
        } catch (IllegalArgumentException | NullPointerException exception) {
            String message = Objects.toString(exception.getMessage(), "技能配置不完整");
            String path = message.contains("SKILL.md") ? "SKILL.md" : null;
            Integer line = null;
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof MarkedYAMLException yaml && yaml.getProblemMark() != null) {
                    path = "SKILL.md";
                    line = yaml.getProblemMark().getLine() + 2;
                    break;
                }
            }
            return new SkillDraftValidation(false, List.of(new SkillDraftValidation.Issue(
                    path, line, SkillDraftValidation.Severity.ERROR, "SKILL_PACKAGE_INVALID", message)));
        }
    }

    /** 同次解码的不可变包同时提供文件与计划声明，不重复解析元数据。 */
    private SkillDescriptor decodedVersion(CapabilityVersionView version) {
        if (version.capabilityType() != CapabilityType.SKILL) throw missing();
        return (SkillDescriptor) codec.decode(new CapabilityRef(CapabilityType.SKILL,
                        version.definitionId().toString(), version.id().toString()), version.tenantId(), version.name(),
                version.description(), version.trustLevel(), version.status(), version.contentHash(), version.descriptor());
    }

    private static SkillDraftContent versionContent(SkillDescriptor skill, String versionKey) {
        Map<String, String> files = new LinkedHashMap<>();
        for (var file : skill.skillPackage().files()) {
            files.put(file.path(), file.encoding() == SkillPackage.Encoding.UTF8
                    ? Base64.getEncoder().encodeToString(file.content().getBytes(StandardCharsets.UTF_8)) : file.content());
        }
        return new SkillDraftContent(versionKey, files,
                skill.metadata().invocationPolicy().modelInvocable(), skill.metadata().invocationPolicy().userInvocable(), skill.activationMode(), skill.conflictGroup(), skill.requiredCapabilities());
    }

    private static Map<String, Object> descriptor(SkillDraftContent content, SkillPackage skillPackage) {
        return SkillPackageDescriptorMapper.descriptor(skillPackage, content.modelInvocable(), content.userInvocable(),
                content.activationMode(), content.conflictGroup(), content.requiredCapabilities());
    }

    private static Map<String, byte[]> decodeFiles(Map<String, String> files) {
        if (files.size() > SkillPackageValidator.MAX_FILES) throw new IllegalArgumentException("skill package file count is invalid");
        Map<String, byte[]> decoded = new LinkedHashMap<>();
        long total = 0;
        for (var entry : files.entrySet()) {
            String value = entry.getValue();
            if (value.length() > ((SkillPackageValidator.MAX_FILE_BYTES + 2L) / 3L) * 4L) {
                throw new IllegalArgumentException("skill package file exceeds size limit: " + entry.getKey());
            }
            byte[] bytes = Base64.getDecoder().decode(value);
            total += bytes.length;
            if (total > SkillPackageValidator.MAX_PACKAGE_BYTES) throw new IllegalArgumentException("skill package exceeds budget");
            decoded.put(entry.getKey(), bytes);
        }
        return decoded;
    }

    private static SkillDraftContent safeContent(SkillDraftContent source, Map<String, byte[]> files) {
        if (source.versionKey().length() > 128
                || source.requiredCapabilities().size() > 256
                || (source.conflictGroup() != null && source.conflictGroup().length() > 128)) {
            throw new IllegalArgumentException("skill draft metadata exceeds limit");
        }
        Map<String, String> encoded = new LinkedHashMap<>();
        files.forEach((path, bytes) -> encoded.put(path, Base64.getEncoder().encodeToString(bytes)));
        return new SkillDraftContent(source.versionKey(), encoded, source.modelInvocable(), source.userInvocable(),
                source.activationMode(), source.conflictGroup(), source.requiredCapabilities());
    }

    private CapabilityDefinitionView ownDefinition(CapabilityCatalogScope scope, Long id, boolean writable) {
        var definition = catalog.getDefinition(scope, id);
        if (definition.capabilityType() != CapabilityType.SKILL || !Objects.equals(scope.tenantId(), definition.tenantId())) throw missing();
        if (writable && !Set.of("DRAFT", "PUBLISHED").contains(definition.status())) throw conflict("技能已停用，不能继续编辑");
        return definition;
    }

    private SkillDraftRepository.Stored require(CapabilityCatalogScope scope, Long id, boolean lock) {
        if (id == null || id <= 0) throw missing();
        return drafts.find(scope, id, lock).orElseThrow(SkillDraftService::missing);
    }

    private void editable(CapabilityCatalogScope scope, SkillDraftView draft, long revision) {
        requireRevision(draft, revision);
        ownDefinition(scope, draft.definitionId(), true);
    }

    private static void requireRevision(SkillDraftView draft, long revision) {
        if (revision <= 0 || draft.revision() != revision) throw stale();
    }

    private void bind(CapabilityCatalogScope scope) {
        if (scope.isPlatform()) context.bindPlatform(); else context.bindTenant(scope.tenantId());
    }
    private static RuntimeException stale() {
        return CapabilityCatalogErrors.exception(HttpStatus.CONFLICT, CapabilityCatalogErrors.REVISION_CONFLICT, "内容已被其他操作更新，请保留本地内容后重新加载");
    }
    private static RuntimeException conflict(String message) {
        return CapabilityCatalogErrors.exception(HttpStatus.CONFLICT, CapabilityCatalogErrors.CONFLICT, message);
    }
    private static RuntimeException missing() {
        return CapabilityCatalogErrors.exception(HttpStatus.NOT_FOUND, CapabilityCatalogErrors.NOT_FOUND, "技能草稿或版本不存在");
    }
    private static RuntimeException invalid(String message) {
        return CapabilityCatalogErrors.exception(HttpStatus.UNPROCESSABLE_ENTITY, CapabilityCatalogErrors.DESCRIPTOR_INVALID, message);
    }
}
