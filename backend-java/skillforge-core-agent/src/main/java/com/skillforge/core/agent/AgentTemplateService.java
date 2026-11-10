package com.skillforge.core.agent;

import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.CapabilityVersionView;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.dto.response.AgentTemplateValidationResponse;
import com.skillforge.core.agent.dto.response.AgentTemplateValidationResponse.Issue;
import com.skillforge.core.agent.mapper.AgentTemplateMapper;
import com.skillforge.core.agent.model.AgentDefinitionOperationRow;
import com.skillforge.core.agent.model.AgentTemplateDO;
import com.skillforge.core.agent.model.AgentTemplateDisplay;
import com.skillforge.core.agent.model.AgentTemplateDefinition;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.foundation.runtimeconfig.AgentBudgetValidation;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 模板定义、发布前检查及公共依赖边界；平台和机构安装复用同一校验。 */
@Service
public class AgentTemplateService extends ServiceImpl<AgentTemplateMapper, AgentTemplateDO> {
    private final PlatformModelCatalogService models;
    private final CapabilityCatalogService catalog;
    private final PlatformToolCatalog platformTools;
    private final ObjectMapper json;
    private final OperationalSettingsService settings;

    public AgentTemplateService(PlatformModelCatalogService models, CapabilityCatalogService catalog,
            PlatformToolCatalog platformTools, ObjectMapper json, OperationalSettingsService settings) {
        this.models = models; this.catalog = catalog; this.platformTools = platformTools; this.json = json;
        this.settings = settings;
    }

    /** 仅补全调用方已经授权的目录身份；不将展示读取作为执行或安装授权。 */
    public Map<Long, AgentTemplateDisplay> displays(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, AgentTemplateDisplay> result = new HashMap<>();
        for (var row : baseMapper.selectDisplayByIds(ids.stream().distinct().toList())) result.put(row.id(), row);
        return Map.copyOf(result);
    }

    @Override public boolean save(AgentTemplateDO entity) {
        validateDraft(entity);
        return super.save(entity);
    }
    @Override public boolean updateById(AgentTemplateDO entity) {
        validateDraft(entity);
        return super.updateById(entity);
    }
    /**
     * 在调用方已授权并持有定义锁的事务中，整体恢复历史可编辑内容。
     * 可空展示字段必须显式写NULL，不能沿普通部分更新的NOT_NULL策略遗漏旧值清空。
     * 发布指针、归属及安装计数不在此写入范围，任何写入失败均交由外层事务回滚。
     */
    public void restoreDraftContent(AgentTemplateDO entity) {
        validateDraft(entity);
        if(baseMapper.restoreDraftContent(entity)!=1)throw new IllegalStateException("模板草稿恢复写入失败");
    }
    private void validateDraft(AgentTemplateDO entity) {
        requireDistributionPolicy(entity);
        if (entity.getDefinition() == null) entity.setDefinition(AgentTemplateDefinition.empty());
        if (!entity.getDefinition().executionConfig().overrides().isEmpty())
            com.skillforge.core.foundation.runtimeconfig.AgentBudgetValidation.validate(entity.getDefinition().executionConfig());
    }

    /** 必须在调用方写事务内按 family→row 顺序加锁，发布和安装使用同一顺序。 */
    public void lockFamily(String key) { baseMapper.lockFamily(key); }
    /** 当前授权和定义锁由应用服务持有；只读原回执，不从当前定义重建原结果。 */
    public AgentDefinitionOperationRow definitionOperation(String actor,String operation,String scope,String key) {
        return baseMapper.definitionOperation(actor,operation,scope,key);
    }
    /** 所属事务原子追加完整定义操作回执；失败必须传播并回滚生命周期写入。 */
    public void appendDefinitionOperation(AgentDefinitionOperationRow row) {
        if(baseMapper.appendDefinitionOperation(row)!=1)throw new IllegalStateException("定义操作回执写入失败");
    }
    public boolean definitionSaveFenced(long tenant,String actor,String operation,String scope,String key) {
        return baseMapper.definitionSaveFenced(tenant,actor,operation,scope,key);
    }
    public void fenceDefinitionSave(long tenant,String actor,String operation,String scope,String key,Long id) {
        if(baseMapper.fenceDefinitionSave(tenant,actor,operation,scope,key,id)!=1)throw new IllegalStateException("定义保存封存写入失败");
    }
    public AgentTemplateDO lockTemplate(Long id) {
        AgentTemplateDO value = getOne(new LambdaQueryWrapper<AgentTemplateDO>()
                .eq(AgentTemplateDO::getId, id).last("FOR UPDATE"));
        if (value == null) throw new ApiException(HttpStatus.NOT_FOUND, "Agent 模板不存在");
        return value;
    }
    public String nextVersion(String key) {
        AgentTemplateDO value = getOne(new LambdaQueryWrapper<AgentTemplateDO>()
                .eq(AgentTemplateDO::getTemplateKey, key));
        if (value == null || value.getPublishedAt() == null) return "1.0.0";
        String[] parts = value.getVersion().split("\\.");
        return parts[0] + "." + Math.addExact(Integer.parseInt(parts[1]), 1) + ".0";
    }

    /** 发布在同一模板锁事务中追加快照；保存工作草稿不会改变该记录。 */
    public void appendPublication(AgentTemplateDO template) {
        requireDistributionPolicy(template);
        try {
            if (baseMapper.appendPublication(template.getId(), template.getVersion(),
                    json.writeValueAsString(template)) != 1) throw new IllegalStateException("模板发布快照写入失败");
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("模板发布快照无法序列化", failure);
        }
    }

    /** 根据稳定模板 ID 读取当前不可变发布配置；下架判断由调用方基于模板状态执行。 */
    public AgentTemplateDO currentPublished(Long id) {
        String snapshot = baseMapper.currentPublication(id);
        if (snapshot == null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Agent 模板尚未发布");
        return decodePublication(snapshot);
    }

    /** 恢复已授权模板的精确发布快照，缺失与损坏不能改读当前版本。 */
    public AgentTemplateDO requirePublication(Long id, String version) {
        String snapshot = baseMapper.publication(id, version);
        if (snapshot == null) throw new ApiException(HttpStatus.NOT_FOUND, "发布历史不存在");
        return decodePublication(snapshot);
    }

    public List<AgentTemplateDO> publications(Long id) {
        return baseMapper.publications(id).stream().map(this::decodePublication).toList();
    }

    public List<AgentTemplateDO> currentPublishedAll() {
        return baseMapper.currentPublishedAll().stream().map(this::decodePublication).toList();
    }

    /** 普通独立安装只读取明确允许 OPEN_INSTALL 的当前发布。 */
    public List<AgentTemplateDO> currentPublishedLibrary() {
        var log=org.slf4j.LoggerFactory.getLogger(AgentTemplateService.class);
        log.debug("开放Agent模板目录查询进入");
        var result=baseMapper.currentPublishedLibrary().stream().map(this::decodePublication).toList();
        log.debug("开放Agent模板目录查询退出 count={}",result.size());
        return result;
    }

    /** 消费稳定身份的当前策略，不能用旧发布引用或调用方 mode 取得复制权。 */
    public void requireLibraryTemplate(AgentTemplateDO identity) {
        var log=org.slf4j.LoggerFactory.getLogger(AgentTemplateService.class);
        log.debug("开放模板访问校验进入 templateId={}",identity.getId());
        try {
            requireDistributionPolicy(identity);
            if(identity.getDistributionPolicy()!=com.skillforge.core.agent.model.AgentDistributionPolicy.OPEN_INSTALL)
                throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN",
                        "此公共Agent定义仅能通过当前有效授权部署，不能独立安装或复制",false);
        }finally{log.debug("开放模板访问校验退出 templateId={}",identity.getId());}
    }

    /** 所有发布和消费边界失败关闭，缺失策略不能解释为普通开放模板。 */
    public void requireDistributionPolicy(AgentTemplateDO template) {
        if (template.getDistributionPolicy() == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请明确选择定义分发策略", false);
        if("tenant".equals(template.getOwnerKind()) && template.getDistributionPolicy()!=com.skillforge.core.agent.model.AgentDistributionPolicy.AUTHORIZED_DEPLOY)
            throw new ApiException(HttpStatus.BAD_REQUEST,"机构共享源只能授权部署");
    }

    /** 发布消费和受限派发均复验源当前可用性；不以另一个授权链替代实例已选链。 */
    public void requireSourceAvailable(AgentTemplateDO identity) {
        if(identity==null)throw new ApiException(HttpStatus.NOT_FOUND,"共享定义不存在");
        requireAuthorizedDeployment(identity);
        if(!"published".equals(identity.getStatus()))throw new ApiException(HttpStatus.CONFLICT,"共享定义当前未发布");
        if("tenant".equals(identity.getOwnerKind()) && (identity.getOwnerTenantId()==null || !baseMapper.sourceTenantAvailable(identity.getOwnerTenantId())))
            throw new ApiException(HttpStatus.FORBIDDEN,"共享源机构已停用");
        if(!"platform".equals(identity.getOwnerKind()) && !"tenant".equals(identity.getOwnerKind()))
            throw new IllegalStateException("共享源所有者类型无效");
    }
    /** grant/部署消费者校验当前源身份后读取当前不可变发布；不会返回工作草稿。 */
    public AgentTemplateDO requireCurrentDefinitionAvailable(long id) {
        var identity=getById(id);requireSourceAvailable(identity);return currentPublished(id);
    }
    /** 调用方已校验消费grant后映射不可变发布；系统提示词、模型配置、预算和私有绑定不进入受让展示。 */
    public com.skillforge.core.agent.dto.response.PublishedAgentDefinitionView publicView(AgentTemplateDO publication) {
        if(publication==null||publication.getPublishedAt()==null)throw new IllegalArgumentException("需要不可变发布快照");
        var definition=publication.getDefinition();
        return new com.skillforge.core.agent.dto.response.PublishedAgentDefinitionView(publication.getId(),publication.getOwnerKind(),publication.getOwnerTenantId(),
                publication.getVersion(),publication.getName(),definition.roleDescription(),definition.resourceSlots(),definition.delegationSlots());
    }

    /** 受控部署必须使用服务端 AUTHORIZED_DEPLOY 策略，部署权不等于独立复制权。 */
    public void requireAuthorizedDeployment(AgentTemplateDO template) {
        requireDistributionPolicy(template);
        if (template.getDistributionPolicy() != com.skillforge.core.agent.model.AgentDistributionPolicy.AUTHORIZED_DEPLOY)
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "此定义未允许受控授权部署", false);
    }

    private AgentTemplateDO decodePublication(String snapshot) {
        try { return json.readValue(snapshot, AgentTemplateDO.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("模板发布快照无法读取", failure);
        }
    }
    public void recordInstallation(Long templateId) {
        if (baseMapper.incrementInstallCount(templateId) != 1) throw new IllegalStateException("模板安装计数更新失败");
    }

    public void requireInstallable(AgentTemplateDO template) {
        requireDistributionPolicy(template);
        if (!"published".equals(template.getStatus())) throw invalid("Agent 模板未发布");
        var result = validateDefinition(template.getDefinition());
        if (!result.valid()) throw invalid(result.issues().stream().map(Issue::message).reduce((a,b) -> a + "；" + b).orElse("模板不可安装"));
    }

    /** 只读目录中的当前发布依赖；机构校验使用机构作用域，不能提升为平台上下文。 */
    @Transactional
    public AgentTemplateValidationResponse validateDefinition(AgentTemplateDefinition definition) {
        List<Issue> issues = new ArrayList<>();
        if (definition == null) return new AgentTemplateValidationResponse(false, List.of(new Issue("definition", "required", "请配置 Agent 定义")));
        if (blank(definition.systemPrompt())) issue(issues, "system_prompt", "required", "请填写完整系统提示词");
        if (blank(definition.roleDescription())) issue(issues, "role_description", "required", "请填写公开角色简介");
        Optional<PlatformModelConfig> model = Optional.empty();
        if (blank(definition.modelConfigId())) issue(issues, "model_config_id", "required", "请选择默认模型");
        else try { model = Optional.of(models.requireEnabled(definition.modelConfigId(), "LLM")); }
        catch (ApiException | IllegalArgumentException e) { issue(issues, "model_config_id", "model_unavailable", "默认模型不存在、已停用或不是 LLM"); }
        try {
            if (model.isPresent()) {
                var selected = model.orElseThrow();
                long timeout = Math.min(selected.timeoutSeconds(), settings.read("calls").integer("chat_timeout_seconds"));
                AgentBudgetValidation.validateModelMinimum(definition.executionConfig(), selected.maxOutputTokens(), timeout);
            } else AgentBudgetValidation.validate(definition.executionConfig());
        }
        catch (ApiException | IllegalArgumentException e) { issue(issues, "execution_config", "budget_invalid", e.getMessage()); }

        String tenant = TenantContext.getTenantId();
        CapabilityCatalogScope scope = blank(tenant) ? CapabilityCatalogScope.platform() : CapabilityCatalogScope.tenant(Long.valueOf(tenant));
        List<Long> ids = definition.capabilities().stream().map(AgentTemplateDefinition.BaseCapability::capabilityDefinitionId)
                .filter(Objects::nonNull).filter(id -> id > 0).distinct().toList();
        Map<Long, CapabilityVersionView> versions = new HashMap<>();
        if (!ids.isEmpty()) catalog.currentPublishedVersions(scope, ids).forEach(v -> versions.put(v.definitionId(), v));
        Set<String> refs = new HashSet<>(), definitions = new HashSet<>();
        for (int i = 0; i < definition.capabilities().size(); i++) {
            var c = definition.capabilities().get(i); String path = "capabilities[" + i + "]";
            var version = versions.get(c.capabilityDefinitionId());
            if (version == null || version.tenantId() != null || !Objects.equals(c.capabilityDefinitionId(), version.definitionId())
                    || version.capabilityType() != c.capabilityType()) {
                issue(issues, path, "capability_unavailable", "基础能力必须选择平台公共目录中拥有当前发布版本的稳定能力"); continue;
            }
            if (!definitions.add(c.capabilityType() + ":" + c.capabilityDefinitionId()))
                issue(issues, path, "duplicate_capability", "同一能力只能配置一个版本");
            refs.add(c.capabilityType() + ":" + c.capabilityDefinitionId());
            if (c.capabilityType() == CapabilityType.SKILL) {
                var config = c.skillConfig();
                if (c.platformToolName() != null || config == null || config.activationMode() == null || config.tokenBudget() == null
                        || config.tokenBudget() <= 0 || !config.activationMode().name().equals(version.descriptor().get("activation_mode")))
                    issue(issues, path + ".skill_config", "skill_config_invalid", "技能激活方式须与发布版本一致，Token 预算须为正数");
            } else if (c.capabilityType() == CapabilityType.TOOL) {
                var entry = platformTools.entries().stream().filter(e -> e.name().equals(c.platformToolName())).findFirst();
                if (c.skillConfig() != null || entry.isEmpty() || !Objects.equals(version.descriptor().get("tool_name"), c.platformToolName())
                        || !Objects.equals(entry.orElseThrow().contentHash(), version.contentHash()))
                    issue(issues, path + ".platform_tool_name", "tool_provider_invalid", "请选择与当前内置执行器契约一致的平台工具版本");
            } else issue(issues, path, "base_type_invalid", "基础能力仅支持公共技能和内置工具；其他资源请声明可选增强项");
        }
        for (int i = 0; i < definition.capabilities().size(); i++) {
            var c = definition.capabilities().get(i); var version = versions.get(c.capabilityDefinitionId());
            if (version == null || c.capabilityType() != CapabilityType.SKILL) continue;
            Object required = version.descriptor().get("required_capabilities");
            if (required instanceof List<?> dependencies) for (Object item : dependencies) {
                if (!(item instanceof Map<?, ?> ref) || !refs.contains(ref.get("type") + ":" + ref.get("capability_id")))
                    issue(issues, "capabilities[" + i + "]", "skill_dependency_missing", "技能依赖必须全部包含在基础能力中，不能依赖未绑定的可选资源");
            }
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < definition.resourceSlots().size(); i++) {
            var slot = definition.resourceSlots().get(i); String path = "resource_slots[" + i + "]";
            checkSlot(issues, keys, slot.key(), slot.label(), path);
            if (slot.capabilityType() != CapabilityType.TOOL && slot.capabilityType() != CapabilityType.WORKFLOW && slot.capabilityType() != CapabilityType.KNOWLEDGE_SOURCE)
                issue(issues, path, "slot_type_invalid", "增强项类型必须为工具、工作流或知识库");
        }
        keys.clear();
        for (int i = 0; i < definition.delegationSlots().size(); i++) {
            var slot = definition.delegationSlots().get(i);
            checkSlot(issues, keys, slot.key(), slot.label(), "delegation_slots[" + i + "]");
        }
        return new AgentTemplateValidationResponse(issues.isEmpty(), issues);
    }

    /** 草稿按能力身份解析当前发布配置；未完成的选择保留，留给发布校验提示。 */
    public AgentTemplateDefinition resolveDraftDefinition(AgentTemplateDefinition definition) {
        String tenant = TenantContext.getTenantId();
        CapabilityCatalogScope scope = blank(tenant) ? CapabilityCatalogScope.platform() : CapabilityCatalogScope.tenant(Long.valueOf(tenant));
        List<Long> ids = definition.capabilities().stream().map(AgentTemplateDefinition.BaseCapability::capabilityDefinitionId)
                .filter(Objects::nonNull).filter(id -> id > 0).distinct().toList();
        Map<Long, CapabilityVersionView> current = new HashMap<>();
        if (!ids.isEmpty()) catalog.currentPublishedVersions(scope, ids).forEach(v -> current.put(v.definitionId(), v));
        var capabilities = definition.capabilities().stream().map(c -> {
            var version = current.get(c.capabilityDefinitionId());
            if (version == null || version.tenantId() != null || version.capabilityType() != c.capabilityType()) return c;
            var skill = c.skillConfig();
            var toolName = c.platformToolName();
            if (c.capabilityType() == CapabilityType.SKILL) {
                toolName = null;
                Object mode = version.descriptor().get("activation_mode");
                if (skill != null && ("ALWAYS".equals(mode) || "ON_DEMAND".equals(mode)))
                    skill = new com.skillforge.core.agent.dto.request.SkillMountConfigRequest(
                            com.skillforge.core.capability.SkillActivationMode.valueOf((String) mode), skill.tokenBudget());
            } else if (c.capabilityType() == CapabilityType.TOOL && version.descriptor().get("tool_name") instanceof String name) {
                toolName = name;
            }
            return new AgentTemplateDefinition.BaseCapability(c.capabilityType(), c.capabilityDefinitionId(), version.id(),
                    skill, toolName, c.priority());
        }).toList();
        return new AgentTemplateDefinition(definition.systemPrompt(), definition.roleDescription(), definition.modelConfigId(),
                definition.executionConfig(), capabilities, definition.resourceSlots(), definition.delegationSlots());
    }

    /** 新安装按稳定能力身份批量解析当前发布版本，然后由新 Agent 自身管理发布周期。 */
    public AgentTemplateDefinition resolveCurrentDefinition(AgentTemplateDefinition definition) {
        String tenant = TenantContext.getTenantId();
        CapabilityCatalogScope scope = blank(tenant) ? CapabilityCatalogScope.platform() : CapabilityCatalogScope.tenant(Long.valueOf(tenant));
        List<Long> ids = definition.capabilities().stream().map(AgentTemplateDefinition.BaseCapability::capabilityDefinitionId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, CapabilityVersionView> current = new HashMap<>();
        if (!ids.isEmpty()) catalog.currentPublishedVersions(scope, ids).forEach(v -> current.put(v.definitionId(), v));
        var capabilities = definition.capabilities().stream().map(c -> {
            var version = current.get(c.capabilityDefinitionId());
            if (version == null || version.tenantId() != null || version.capabilityType() != c.capabilityType())
                throw invalid("模板基础能力当前发布版本不可用");
            return new AgentTemplateDefinition.BaseCapability(c.capabilityType(), c.capabilityDefinitionId(), version.id(),
                    c.skillConfig(), c.platformToolName(), c.priority());
        }).toList();
        return new AgentTemplateDefinition(definition.systemPrompt(), definition.roleDescription(), definition.modelConfigId(),
                definition.executionConfig(), capabilities, definition.resourceSlots(), definition.delegationSlots());
    }
    private static void checkSlot(List<Issue> issues, Set<String> keys, String key, String label, String path) {
        if (key == null || !key.matches("[a-z][a-z0-9_]{0,63}") || !keys.add(key))
            issue(issues, path + ".key", "slot_key_invalid", "配置项标识须为唯一的小写字母、数字或下划线");
        if (blank(label)) issue(issues, path + ".label", "required", "请填写配置项名称");
    }
    private static void issue(List<Issue> issues, String path, String code, String message) { issues.add(new Issue("definition." + path, code, message)); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message); }
}
