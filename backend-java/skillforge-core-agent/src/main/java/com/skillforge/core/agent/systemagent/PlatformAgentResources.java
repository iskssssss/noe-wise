package com.skillforge.core.agent.systemagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台系统 Agent 的公共 SKILL/TOOL 资源与工具部署服务。
 *
 * <p>这里只读取 platform scope 的已发布 Capability。工具部署采用追加版本与当前指针，
 * 已受理运行保存的部署 revision 始终可以从版本表重新读取，后续更新不会改写其执行事实。
 */
@Service
public class PlatformAgentResources {
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final PlatformAgentResourceMapper mapper;
    private final CapabilityCatalogDatabaseContext database;
    private final ObjectMapper json;
    private final PlatformToolCatalog platformTools;

    public PlatformAgentResources(
            PlatformAgentResourceMapper mapper,
            CapabilityCatalogDatabaseContext database,
            ObjectMapper objectMapper, PlatformToolCatalog platformTools) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.database = Objects.requireNonNull(database, "database");
        this.json = Objects.requireNonNull(objectMapper, "objectMapper");
        this.platformTools = Objects.requireNonNull(platformTools, "platformTools");
    }

    /** 返回系统 Agent 编辑器可选择的已发布公共资源和当前部署。 */
    @Transactional(readOnly = true)
    public MountOptions options() {
        platform();
        return new MountOptions(
                mapper.optionSkills().stream().map(this::option).toList(),
                mapper.optionTools().stream().map(this::option).toList(),
                mapper.optionDeployments().stream().map(this::deployment)
                        .map(DeploymentOption::new).toList());
    }

    /** 校验挂载引用及其当前工具部署，拒绝任何非公共或未发布 Capability。 */
    @Transactional(readOnly = true)
    public void validate(List<SystemAgentTypes.Mount> mounts, boolean debug) {
        if (mounts == null || mounts.isEmpty()) {
            return;
        }
        platform();
        resolve(mounts, debug, true);
    }

    /**
     * 校验平台管理草稿的挂载引用。
     *
     * <p>草稿可预先选择 TEST 或 PRODUCTION 的活动部署；环境匹配只在发布和受理执行时判断，
     * 因而不会阻断为 debug 准备的测试工具配置。
     */
    @Transactional(readOnly = true)
    public void validateDraft(List<SystemAgentTypes.Mount> mounts) {
        if (mounts == null || mounts.isEmpty()) {
            return;
        }
        platform();
        resolve(mounts, false, false);
    }

    /** 将挂载 Skill 的冻结 SKILL.md 正文按优先级拼接为模型指令。 */
    @Transactional(readOnly = true)
    public String skillInstructions(List<SystemAgentTypes.Mount> mounts) {
        if (mounts == null || mounts.isEmpty()) {
            return "";
        }
        platform();
        return resolve(mounts, false, false).stream()
                .sorted(java.util.Comparator.comparingInt(
                        (PublishedMount value) -> value.mount().priority()).reversed())
                .map(PublishedMount::skillInstructions)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    /**
     * 解析一次运行要冻结的完整挂载，工具包含不可变 deployment revision。
     *
     * <p>运行器必须把本方法返回的 deployment revision 随运行事实持久化，并在执行时使用
     * {@link #requireDeployment(String, long)}，不得再次按当前部署指针解析。
     */
    @Transactional(readOnly = true)
    public List<PublishedMount> resolvePublishedMounts(
            List<SystemAgentTypes.Mount> mounts, boolean debug) {
        if (mounts == null || mounts.isEmpty()) {
            return List.of();
        }
        platform();
        return resolve(mounts, debug, true);
    }

    /**
     * 为已受理的系统 Agent 运行解析公共资源快照。
     *
     * <p>该读取保留调用方的 tenant 上下文，仅打开部署版本的专用 RLS 只读视图；它不设置
     * {@code app.platform_admin}，也不授予任何工具执行权限。实际执行仍由 Runtime 的
     * Provider 绑定和动作授权控制。
     */
    @Transactional(readOnly = true)
    public List<PublishedMount> resolveForExecution(
            List<SystemAgentTypes.Mount> mounts, boolean debug) {
        if (mounts == null || mounts.isEmpty()) {
            return List.of();
        }
        mapper.bindRuntimeRead();
        return resolve(mounts, debug, true);
    }

    /**
     * 按受理时冻结的 revision 读取部署版本，供运行恢复使用。
     *
     * <p>仅打开系统 Agent Runtime 的只读 RLS 视图，不改变当前 tenant，也不赋予执行许可。
     */
    @Transactional(readOnly = true)
    public DeploymentView requireDeploymentForExecution(String id, long revision) {
        mapper.bindRuntimeRead();
        return requireDeploymentInternal(id, revision);
    }

    private List<PublishedMount> resolve(
            List<SystemAgentTypes.Mount> mounts, boolean debug, boolean enforceEnvironment) {
        List<SystemAgentTypes.Mount> values = mounts == null ? List.of() : List.copyOf(mounts);
        Set<String> unique = new HashSet<>();
        List<PublishedMount> resolved = new ArrayList<>(values.size());
        for (SystemAgentTypes.Mount mount : values) {
            if (mount == null) {
                throw invalid("挂载项不能为空");
            }
            String type = normalizedType(mount.capabilityType());
            String definitionId = positiveId(mount.capabilityDefinitionId(), "capability_definition_id");
            if (!unique.add(type + ":" + definitionId)) {
                throw invalid("同一能力不得重复挂载");
            }
            if ("SKILL".equals(type)) {
                Map<String, Object> capability = currentCapability(type, definitionId);
                String versionId = string(capability.get("id"), "capability_version_id");
                validateSkillMount(mount);
                resolved.add(new PublishedMount(mount, skillBody(capability), null, definitionId, versionId, object(capability.get("descriptor"), "SKILL descriptor")));
                continue;
            }
            validateToolMount(mount);
            DeploymentView deployment = requireDeploymentInternal(mount.toolDeploymentId(), null);
            if (!definitionId.equals(deployment.toolDefinitionId())) {
                throw invalid("工具部署与挂载的 TOOL 身份不一致");
            }
            if (enforceEnvironment) {
                validateRuntimeEnvironment(deployment, debug);
            }
            Map<String, Object> capability = capability(type, deployment.toolVersionId());
            validatePlatformProvider(deployment, capability);
            resolved.add(new PublishedMount(mount, null, deployment, definitionId, deployment.toolVersionId(), object(capability.get("descriptor"), "TOOL descriptor")));
        }
        return List.copyOf(resolved);
    }

    /** 创建稳定工具部署的工作草稿，发布后才允许新运行使用。 */
    @Transactional(rollbackFor = Exception.class)
    public DeploymentView create(DeploymentWrite request, String actor, String requestId) {
        platform();
        return execute(actor, requestId, "create", request, () -> {
            DeploymentWrite value = normalizedWrite(request);
            validateDeployment(value);
            String id = Long.toString(SnowflakeIds.nextId());
            if (mapper.createDraft(id, encode(value)) != 1) throw new IllegalStateException("平台工具部署草稿写入失败");
            return draftRequired(id);
        });
    }

    @Transactional(readOnly = true)
    public List<DeploymentView> deployments() {
        platform();
        return mapper.deployments().stream().map(this::deployment).toList();
    }

    /** 保存原身份的工作草稿，不增加发布历史。 */
    @Transactional(rollbackFor = Exception.class)
    public DeploymentView update(String id, long expectedRevision, DeploymentWrite request, String actor, String requestId) {
        platform();
        return execute(actor, requestId, "update", java.util.List.of(id, expectedRevision, request), () -> {
            String normalizedId = positiveId(id, "id");
            DeploymentWrite value = normalizedWrite(request);
            validateDeployment(value);
            if (mapper.saveDraft(normalizedId, expectedRevision, encode(value)) != 1) throw conflict("工具部署草稿已变更");
            return draftRequired(normalizedId);
        });
    }

    /** 发布草稿追加不可变部署快照；运行恢复始终读取原精确修订。 */
    @Transactional(rollbackFor = Exception.class)
    public DeploymentView publish(String id, long expectedRevision, String actor, String requestId) {
        platform();
        return execute(actor, requestId, "publish", java.util.List.of(id, expectedRevision), () -> {
            String normalizedId = positiveId(id, "id");
            String actorValue = requiredText(actor, "actor");
            DeploymentView draft = draftRequired(normalizedId);
            if (draft.revision() != expectedRevision) throw conflict("工具部署草稿已变更");
            if (!draft.hasUnpublishedChanges()) return draft;
            DeploymentWrite value = new DeploymentWrite(draft.name(), draft.toolDefinitionId(), draft.providerType(),
                    draft.providerRef(), draft.environment(), draft.active());
            validateDeployment(value);
            String versionId = string(currentCapability("TOOL", value.toolDefinitionId()).get("id"), "tool_version_id");
            long revision = draft.publishedRevision() == null ? 1 : Math.addExact(draft.publishedRevision(), 1);
            if (mapper.publishDraft(normalizedId, expectedRevision, revision) != 1) throw conflict("工具部署发布已变更");
            if (mapper.appendRevision(normalizedId, revision, value.name(), versionId, value.toolDefinitionId(),
                    value.providerType(), value.providerRef(), value.environment(), value.active(), actorValue) != 1)
                throw new IllegalStateException("平台工具部署版本未完整写入");
            audit(normalizedId, revision, revision == 1 ? "CREATE" : "UPDATE", actorValue, value);
            return draftRequired(normalizedId);
        });
    }

    /** 回执只属于原操作者；未找到不能推断原请求已结束。 */
    @Transactional(readOnly = true)
    public ReceiptView receipt(String actor, String requestId) {
        platform();
        var receipt = mapper.receipt(requiredText(actor, "actor"), requestId(requestId));
        return receipt == null || receipt.response() == null
                ? new ReceiptView("UNKNOWN", null) : new ReceiptView("SUCCEEDED", deployment(receipt.response()));
    }

    private DeploymentView execute(String actor, String requestId, String operation, Object input,
            java.util.function.Supplier<DeploymentView> action) {
        String owner = requiredText(actor, "actor"), key = requestId(requestId);
        try {
            String canonical = json.writeValueAsString(java.util.List.of(operation, input));
            String fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (mapper.reserveReceipt(owner, key, fingerprint) == 0) {
                var existing = mapper.receipt(owner, key);
                if (existing == null || !fingerprint.equals(existing.fingerprint())) throw conflict("原请求标识与内容不一致");
                if (existing.response() == null) throw conflict("原请求尚未完成");
                return deployment(existing.response());
            }
            DeploymentView result = action.get();
            if (mapper.completeReceipt(owner, key, json.writeValueAsString(result)) != 1)
                throw new IllegalStateException("工具部署回执未完整写入");
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("工具部署回执序列化失败", failure);
        }
    }

    private static String requestId(String value) {
        try { return java.util.UUID.fromString(value).toString(); }
        catch (RuntimeException failure) { throw invalid("原请求标识无效"); }
    }

    @Transactional(readOnly = true)
    public List<DeploymentView> history(String id) {
        platform();
        draftRequired(positiveId(id, "id"));
        return mapper.history(id).stream().map(this::deployment).toList();
    }

    private DeploymentView draftRequired(String id) {
        String stored = mapper.draft(id);
        if (stored == null) throw notFound("工具部署不存在");
        return deployment(stored);
    }

    private String encode(DeploymentWrite value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("工具部署草稿无法序列化", failure);
        }
    }

    /** 获取当前可执行部署，并核验其工具版本仍属于平台已发布公共 Catalog。 */
    @Transactional(readOnly = true)
    public DeploymentView requireDeployment(String id) {
        platform();
        return requireDeploymentInternal(id, null);
    }

    /** 获取已冻结 revision 的部署版本，供受理后的运行恢复执行。 */
    @Transactional(readOnly = true)
    public DeploymentView requireDeployment(String id, long revision) {
        platform();
        return requireDeploymentInternal(id, revision);
    }

    private DeploymentView requireDeploymentInternal(String id, Long revision) {
        if (revision != null && revision <= 0) {
            throw invalid("deployment_revision 必须是正整数");
        }
        DeploymentView value = deploymentRequired(positiveId(id, "tool_deployment_id"), revision);
        validateDeploymentVersionOwnership(value);
        return value;
    }

    private void validateDeployment(DeploymentWrite value) {
        Map<String, Object> capability = currentCapability("TOOL", value.toolDefinitionId());
        String providerType = value.providerType();
        if (!"PLATFORM".equals(providerType)) {
            // connector_instances 是带租户凭证的 RLS 资源，当前没有平台级执行 Provider；
            // MCP 同样没有平台部署的发现、凭证和调用通道，不能把 provider_ref 当成 URL 执行。
            throw invalid("当前平台系统 Agent 仅支持已注册的 PLATFORM Provider");
        }
        if (!"TEST".equals(value.environment()) && !"PRODUCTION".equals(value.environment())) {
            throw invalid("environment 仅支持 TEST 或 PRODUCTION");
        }
        validatePlatformProvider(new DeploymentView("", value.name(), string(capability.get("id"), "tool_version_id"),
                providerType, value.providerRef(), value.environment(), value.active(), 1, value.toolDefinitionId(), null, true), capability);
    }

    private void validateDeploymentVersionOwnership(DeploymentView value) {
        if (!value.active()) {
            throw invalid("工具部署未启用");
        }
        validateDeploymentReference(value);
    }

    private void validateDeploymentReference(DeploymentView value) {
        enumValue(value.environment(), Set.of("TEST", "PRODUCTION"), "environment");
        Map<String, Object> capability = capability("TOOL", value.toolVersionId());
        if (!value.toolDefinitionId().equals(string(capability.get("definition_id"), "definition_id")))
            throw invalid("工具部署的发布身份不一致");
        validatePlatformProvider(value, capability);
    }

    private void validatePlatformProvider(DeploymentView deployment, Map<String, Object> capability) {
        if (!"PLATFORM".equals(deployment.providerType())) {
            throw invalid("当前平台系统 Agent 仅支持已注册的 PLATFORM Provider");
        }
        String toolName = text(capability.get("descriptor"), "tool_name");
        if (!deployment.providerRef().equals(toolName)
                || !platformTools.names().contains(deployment.providerRef())) {
            throw invalid("provider_ref 必须绑定同名的已注册平台工具");
        }
    }

    private void validateRuntimeEnvironment(DeploymentView deployment, boolean debug) {
        if (debug && !"TEST".equals(deployment.environment())) {
            throw invalid("debug 执行只允许挂载 TEST 工具部署");
        }
        if (!debug && !"PRODUCTION".equals(deployment.environment())) {
            throw invalid("非 debug 执行只允许挂载 PRODUCTION 工具部署");
        }
    }

    private void validateSkillMount(SystemAgentTypes.Mount mount) {
        if (mount.tokenBudget() == null || mount.tokenBudget() <= 0) {
            throw invalid("SKILL 挂载的 token_budget 必须是正整数");
        }
        enumValue(mount.activationMode(), Set.of("ALWAYS", "ON_DEMAND"), "activation_mode");
        if (mount.toolDeploymentId() != null && !mount.toolDeploymentId().isBlank()) {
            throw invalid("SKILL 挂载不得提供 tool_deployment_id");
        }
    }

    private void validateToolMount(SystemAgentTypes.Mount mount) {
        positiveId(mount.toolDeploymentId(), "tool_deployment_id");
        if (mount.tokenBudget() != null || (mount.activationMode() != null
                && !mount.activationMode().isBlank())) {
            throw invalid("TOOL 挂载不得提供 token_budget 或 activation_mode");
        }
    }

    private Map<String, Object> capability(String type, String versionId) {
        String value = mapper.capability(type, versionId);
        if (value == null) {
            throw notFound("未找到平台已发布 " + type + " CapabilityVersion");
        }
        return object(value);
    }

    private Map<String, Object> currentCapability(String type, String definitionId) {
        String value = mapper.currentCapability(type, definitionId);
        if (value == null) throw notFound("未找到平台能力的当前发布版本");
        return object(value);
    }

    private String skillBody(Map<String, Object> capability) {
        Map<String, Object> descriptor = object(capability.get("descriptor"), "SKILL descriptor");
        Map<String, Object> packageValue = object(descriptor.get("package"), "SKILL package");
        Object rawFiles = packageValue.get("files");
        if (!(rawFiles instanceof List<?> files)) {
            throw invalid("SKILL package 缺少文件列表");
        }
        for (Object raw : files) {
            Map<String, Object> file = object(raw, "SKILL file");
            if (!"SKILL.md".equals(file.get("path"))) {
                continue;
            }
            if (!"UTF8".equals(file.get("encoding")) || !(file.get("content") instanceof String content)) {
                throw invalid("SKILL.md 必须为 UTF8 正文");
            }
            return content;
        }
        throw invalid("SKILL package 缺少 SKILL.md");
    }

    private DeploymentWrite normalizedWrite(DeploymentWrite request) {
        if (request == null) {
            throw invalid("payload 不能为空");
        }
        return new DeploymentWrite(requiredText(request.name(), "name"),
                positiveId(request.toolDefinitionId(), "tool_definition_id"),
                enumValue(request.providerType(), Set.of("PLATFORM"), "provider_type"),
                requiredText(request.providerRef(), "provider_ref"),
                enumValue(request.environment(), Set.of("TEST", "PRODUCTION"), "environment"),
                request.active());
    }

    private DeploymentView deploymentRequired(String id, Long revision) {
        String value = revision == null ? mapper.deployment(id) : mapper.deploymentRevision(id, revision);
        if (value == null) {
            throw notFound("工具部署不存在");
        }
        return deployment(value);
    }

    private Option option(String value) {
        Map<String, Object> item = object(value);
        return new Option(string(item.get("id"), "id"), string(item.get("name"), "name"));
    }

    private DeploymentView deployment(String value) {
        Map<String, Object> item = object(value);
        Object revision = item.get("revision");
        if (!(revision instanceof Number number) || number.longValue() <= 0
                || !(item.get("active") instanceof Boolean active)) {
            throw new IllegalStateException("工具部署持久化记录无效");
        }
        return new DeploymentView(string(item.get("id"), "id"), string(item.get("name"), "name"),
                item.get("tool_version_id") == null ? null : string(item.get("tool_version_id"), "tool_version_id"),
                string(item.get("provider_type"), "provider_type"),
                string(item.get("provider_ref"), "provider_ref"), string(item.get("environment"), "environment"),
                active, number.longValue(), string(item.get("tool_definition_id"), "tool_definition_id"),
                item.get("published_revision") instanceof Number published ? published.longValue() : null,
                Boolean.TRUE.equals(item.get("has_unpublished_changes")));
    }

    private void audit(String id, long revision, String operation, String actor, DeploymentWrite value) {
        try {
            String payload = json.writeValueAsString(Map.of("name", value.name(),
                    "tool_definition_id", value.toolDefinitionId(), "provider_type", value.providerType(),
                    "provider_ref", value.providerRef(), "environment", value.environment(),
                    "active", value.active()));
            if (mapper.audit(id, revision, operation, actor, payload) != 1) {
                throw new IllegalStateException("平台工具部署审计未写入");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("平台工具部署审计序列化失败", exception);
        }
    }

    private Map<String, Object> object(String value) {
        try {
            return json.readValue(value, OBJECT);
        } catch (Exception exception) {
            throw new IllegalStateException("平台资源持久化 JSON 无法解析", exception);
        }
    }

    private static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw invalid(field + " 格式无效");
        }
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String text(Object value, String field) {
        return string(object(value, field).get(field), field);
    }

    private static String string(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid(field + " 必须是非空文本");
        }
        return text;
    }

    private static String normalizedType(String value) {
        String type = enumValue(value, Set.of("SKILL", "TOOL"), "capability_type");
        if ("MODEL_TASK".equalsIgnoreCase(value == null ? "" : value.trim())) {
            throw invalid("MODEL_TASK 不允许作为系统 Agent 挂载");
        }
        return type;
    }

    private static String enumValue(String value, Set<String> allowed, String field) {
        String normalized = requiredText(value, field).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw invalid(field + " 不支持该取值");
        }
        return normalized;
    }

    private static String positiveId(String value, String field) {
        String normalized = requiredText(value, field);
        try {
            if (Long.parseLong(normalized) <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return normalized;
        } catch (NumberFormatException exception) {
            throw invalid(field + " 必须是正整数");
        }
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " 不能为空");
        }
        return value.trim();
    }

    private void platform() {
        database.bindPlatform();
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    private static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Option(String id, String name) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MountOptions(List<Option> skills, List<Option> tools,
                               List<DeploymentOption> deployments) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DeploymentOption(String id, String name, String toolVersionId, String toolDefinitionId, String environment) {
        private DeploymentOption(DeploymentView value) {
            this(value.id(), value.name(), value.toolVersionId(), value.toolDefinitionId(), value.environment());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DeploymentView(String id, String name, String toolVersionId, String providerType,
                                 String providerRef, String environment, boolean active, long revision, String toolDefinitionId,
                                 Long publishedRevision, boolean hasUnpublishedChanges) {}
    public record ReceiptView(String status, DeploymentView result) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DeploymentWrite(String name, String toolDefinitionId, String providerType,
                                  String providerRef, String environment, boolean active) {}

    /** 受理执行用的不可变挂载快照；Tool deployment 含当前或指定的 revision。 */
    public record PublishedMount(SystemAgentTypes.Mount mount, String skillInstructions,
                                 DeploymentView deployment, String capabilityId, String capabilityVersionId,
                                 Map<String,Object> descriptor) {}
}
