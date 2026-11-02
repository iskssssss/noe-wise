package com.skillforge.core.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.dto.query.AgentTemplateQuery;
import com.skillforge.core.agent.dto.request.CreateAgentTemplateRequest;
import com.skillforge.core.agent.dto.request.UpdateAgentTemplateRequest;
import com.skillforge.core.agent.dto.response.AdminAgentTemplateDetailResponse;
import com.skillforge.core.agent.dto.response.AdminAgentTemplateItemResponse;
import com.skillforge.core.agent.dto.response.AdminAgentTemplateListResponse;
import com.skillforge.core.agent.dto.response.AgentDefinitionSaveResolution;
import com.skillforge.core.agent.dto.response.AgentTemplateValidationResponse;
import com.skillforge.core.agent.model.AgentTemplateDO;
import com.skillforge.core.agent.model.AgentTemplateDefinition;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.agent.constant.AgentConstants;
import com.skillforge.core.shared.constant.ApiConstants;
import com.skillforge.core.shared.exception.ApiException;
import java.time.Instant;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.skillforge.common.context.ManagementContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.agent.model.AgentDefinitionOperationRow;
import com.skillforge.core.shared.security.TokenHash;
import com.skillforge.core.foundation.application.AuditService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 平台和机构共享定义的唯一生命周期；当前主体决定源所有权，受让使用权不授予草稿维护权。 */
@Service
public class AgentDefinitionLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionLifecycleService.class);
    private static final String TEMPLATE_KEY_PREFIX = "tpl-";
    private static final String DEFAULT_VERSION = "1.0.0";

    private final AgentTemplateService templateService;
    private final PlatformManagementService management;
    private final FoundationAccess access;
    private final ObjectMapper json;
    private final AuditService audit;
    private final InstitutionIntegrationService integrations;

    /** 原回执和生命周期共用调用方事务；JSON只用于规范命令和安全原响应。 */
    public AgentDefinitionLifecycleService(AgentTemplateService templateService,PlatformManagementService management,FoundationAccess access,
            ObjectMapper json,AuditService audit,InstitutionIntegrationService integrations) {
        this.templateService=templateService;this.management=management;this.access=access;
        this.json=json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);this.audit=audit;this.integrations=integrations;
    }

    public AdminAgentTemplateListResponse list(AgentTemplateQuery query) {
        int page = query.page() == null || query.page() < 1 ? ApiConstants.DEFAULT_PAGE : query.page();
        int limit = query.limit() == null
                ? ApiConstants.DEFAULT_PAGE_SIZE
                : Math.max(1, Math.min(ApiConstants.MAX_PAGE_SIZE, query.limit()));
        var owner=authorize("read",null);
        var allowed=owner.tenantId()==null?management.allowedDefinitionIds("platform.agent_definitions.read"):null;
        LambdaQueryWrapper<AgentTemplateDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentTemplateDO::getOwnerKind,owner.kind());
        if(owner.tenantId()!=null)wrapper.eq(AgentTemplateDO::getOwnerTenantId,owner.tenantId());
        if(allowed!=null) { if(allowed.isEmpty())return new AdminAgentTemplateListResponse(0,List.of());wrapper.in(AgentTemplateDO::getId,allowed); }
        if (query.status() != null && !query.status().isBlank()) {
            wrapper.eq(AgentTemplateDO::getStatus, query.status());
        }
        if (query.category() != null && !query.category().isBlank()) {
            wrapper.eq(AgentTemplateDO::getCategory, query.category());
        }
        if (query.name() != null && !query.name().isBlank()) {
            wrapper.like(AgentTemplateDO::getName, query.name().trim());
        }
        if (query.distributionPolicy() != null) {
            wrapper.eq(AgentTemplateDO::getDistributionPolicy, query.distributionPolicy());
        }
        wrapper.orderByDesc(AgentTemplateDO::getCreatedAt);
        Page<AgentTemplateDO> result = templateService.page(new Page<>(page, limit), wrapper);
        return new AdminAgentTemplateListResponse(
                result.getTotal(), result.getRecords().stream().map(this::item).toList());
    }

    public AdminAgentTemplateDetailResponse detail(Long id) {
        authorize("read",id);
        return detail(requireTemplate(id));
    }

    /** 读取当前机构和主体首次保存的原安全快照，不推断不存在的回执。 */
    @Transactional(readOnly=true)
    public AdminAgentTemplateItemResponse tenantSaveReceipt(String operation,String requestKey,Long id) {
        requireTenantOwner();
        requireSaveIdentity(operation,id);
        authorize("read",id);
        if(id!=null)requireTemplate(id);
        String key=com.skillforge.core.agent.deployment.AgentTemplateGrantService.requireKey(requestKey);
        var row=templateService.definitionOperation(actorRef(),operation,id==null?"new":id.toString(),key);
        if(row==null)return null;
        if(row.definitionId()==null||id!=null&&!id.equals(row.definitionId()))throw new IllegalStateException("定义原操作回执目标不一致");
        authorize("read",row.definitionId());
        var current=requireTemplate(row.definitionId());
        try {
            var result=json.readValue(row.resultSnapshot(),AdminAgentTemplateItemResponse.class);
            if(result==null||!row.definitionId().equals(result.id())||!current.getOwnerKind().equals(result.ownerKind())
                    ||!java.util.Objects.equals(current.getOwnerTenantId(),result.ownerTenantId()))
                throw new IllegalStateException("定义原操作回执身份不一致");
            return result;
        } catch(JsonProcessingException failure){throw new IllegalStateException("定义原操作回执损坏",failure);}
    }

    /** 只读确认原键的成功或封存事实；查不到仍是UNKNOWN。 */
    @Transactional(readOnly=true)
    public AgentDefinitionSaveResolution tenantSaveResolution(String operation,String requestKey,Long id) {
        var receipt=tenantSaveReceipt(operation,requestKey,id);
        if(receipt!=null)return new AgentDefinitionSaveResolution("COMMITTED",receipt);
        var owner=owner();var key=com.skillforge.core.agent.deployment.AgentTemplateGrantService.requireKey(requestKey);
        return new AgentDefinitionSaveResolution(templateService.definitionSaveFenced(owner.tenantId(),actorRef(),operation,
                id==null?"new":id.toString(),key)?"FENCED":"UNKNOWN",null);
    }

    /** 原键已成功则返回原回执；否则与原写同锁封存，不推断原请求是否到达。 */
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public AgentDefinitionSaveResolution tenantFenceSave(String operation,String requestKey,Long id) {
        requireTenantOwner();requireSaveIdentity(operation,id);
        var owner=authorize("read",id);var actor=actorRef();
        var key=com.skillforge.core.agent.deployment.AgentTemplateGrantService.requireKey(requestKey);
        String scope=id==null?"new":id.toString();
        if(id==null)templateService.lockFamily("create:"+actor+":"+key);
        else lockTemplateFamilyAndRow(id);
        var receipt=tenantSaveReceipt(operation,key,id);
        if(receipt!=null)return new AgentDefinitionSaveResolution("COMMITTED",receipt);
        if(!templateService.definitionSaveFenced(owner.tenantId(),actor,operation,scope,key))
            templateService.fenceDefinitionSave(owner.tenantId(),actor,operation,scope,key,id);
        return new AgentDefinitionSaveResolution("FENCED",null);
    }
    private static void requireSaveIdentity(String operation,Long id) {
        if(!"create".equals(operation)&&!"update".equals(operation)
                ||"create".equals(operation)&&id!=null||"update".equals(operation)&&(id==null||id<=0))
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","保存回执查询参数非法",false);
    }

    /** 返回当前定义的字段级校验结果，供编辑器在发布前定位问题。 */
    public AgentTemplateValidationResponse validate(Long id) {
        authorize("validate",id);
        return validateChecked(requireTemplate(id));
    }

    private AgentTemplateValidationResponse validateChecked(AgentTemplateDO template) {
        return templateService.validateDefinition(template.getDefinition());
    }

    /** 只校验未保存内容，同时确认其所属草稿和基准修订。 */
    @Transactional(readOnly = true)
    public AgentTemplateValidationResponse validateCandidate(Long id, long expected,
            com.skillforge.core.agent.model.AgentTemplateDefinition candidate) {
        authorize("validate", id);
        var current = requireTemplate(id);
        if (expected < 1) throw new ApiException(HttpStatus.BAD_REQUEST, "expected_revision必须为正数");
        if (current.getDraftRevision() != expected) throw new ApiException(HttpStatus.CONFLICT, "草稿修订已变化，请重新读取");
        return templateService.validateDefinition(templateService.resolveDraftDefinition(candidate));
    }

    /** 发布前只读校验确切保存内容，不替换其冻结字段。 */
    @Transactional(readOnly = true)
    public AgentTemplateValidationResponse validateSaved(Long id, long expected) {
        authorize("validate", id);
        var current = requireTemplate(id);
        if (expected < 1) throw new ApiException(HttpStatus.BAD_REQUEST, "expected_revision必须为正数");
        if (current.getDraftRevision() != expected) throw new ApiException(HttpStatus.CONFLICT, "草稿修订已变化，请重新读取");
        return validateChecked(current);
    }

    /** 首次创建的内容写入；授权、创建请求锁和原回执由唯一公开create入口持有。 */
    private AdminAgentTemplateItemResponse createChecked(CreateAgentTemplateRequest request) {
        AgentTemplateDO template = new AgentTemplateDO();
        template.setId(SnowflakeIds.nextId());
        template.setTemplateKey(TEMPLATE_KEY_PREFIX + SnowflakeIds.nextId());
        template.setName(request.name().trim());
        template.setVersion(DEFAULT_VERSION);
        template.setCategory(request.category());
        template.setTags(request.tags() == null ? List.of() : request.tags());
        template.setDescription(request.description());
        template.setIconUrl(request.iconUrl());
        var owner=owner();template.setAuthor(owner.kind());template.setAuthorType(owner.kind());
        template.setOwnerKind(owner.kind());template.setOwnerTenantId(owner.tenantId());template.setDraftRevision(1L);
        template.setDefinition(request.definition() == null ? AgentTemplateDefinition.empty() : templateService.resolveDraftDefinition(request.definition()));
        template.setPriceType(request.priceType() == null ? "free" : request.priceType());
        template.setPriceCents(request.priceCents() == null ? 0 : request.priceCents());
        template.setInstallCount(0);
        template.setStatus(AgentConstants.STATUS_DRAFT);
        template.setDistributionPolicy(request.distributionPolicy());
        templateService.requireDistributionPolicy(template);
        template.setHasUnpublishedChanges(true);
        Instant now = Instant.now();
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        if (!templateService.save(template)) throw new IllegalStateException("模板创建写入失败");
        log.info("创建共享 Agent 定义 ownerKind={} ownerTenantId={} id={} key={}",template.getOwnerKind(),template.getOwnerTenantId(),template.getId(),template.getTemplateKey());
        return item(requireTemplate(template.getId()));
    }

    /** 同主体创建请求只提交一个定义，任何后续编辑都不改变原创建响应。 */
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public AdminAgentTemplateItemResponse create(CreateAgentTemplateRequest request,String requestKey) {
        var owner=authorize("create",null);
        String actor=actorRef();
        String key=com.skillforge.core.agent.deployment.AgentTemplateGrantService.requireKey(requestKey);
        var normalized=normalizeCreate(request);
        String fingerprint=fingerprint("create",null,normalized);
        templateService.lockFamily("create:"+actor+":"+key);
        var replay=replay(actor,"create","new",key,fingerprint);
        if(replay!=null)return replay;
        if(owner.tenantId()!=null&&templateService.definitionSaveFenced(owner.tenantId(),actor,"create","new",key))throw saveFenced();
        var result=createChecked(normalized);
        complete(actor,"create","new",key,fingerprint,result);
        return result;
    }

    private AdminAgentTemplateItemResponse updateChecked(AgentTemplateDO existing, UpdateAgentTemplateRequest request) {
        if(request==null)throw new ApiException(HttpStatus.BAD_REQUEST,"缺少定义编辑内容");
        if (AgentConstants.STATUS_ARCHIVED.equals(existing.getStatus()))
            throw new ApiException(HttpStatus.CONFLICT, "归档模板不可修改");
        applyContent(existing, request);
        existing.setDraftRevision(Math.addExact(existing.getDraftRevision(),1L));
        existing.setHasUnpublishedChanges(true);
        existing.setUpdatedAt(Instant.now());
        return item(updateAndRead(existing));
    }

    private AdminAgentTemplateItemResponse publishChecked(AgentTemplateDO template) {
        if (AgentConstants.STATUS_ARCHIVED.equals(template.getStatus()))
            throw new ApiException(HttpStatus.CONFLICT, "归档模板不可发布");
        if (!Boolean.TRUE.equals(template.getHasUnpublishedChanges()) && template.getPublishedAt() != null
                && AgentConstants.STATUS_PUBLISHED.equals(template.getStatus())) return item(template);
        templateService.requireDistributionPolicy(template);
        requirePublishable(template.getDefinition());
        template.setVersion(templateService.nextVersion(template.getTemplateKey()));
        Instant now = Instant.now();
        template.setStatus(AgentConstants.STATUS_PUBLISHED);
        template.setHasUnpublishedChanges(false);
        template.setPublishedAt(now);
        template.setUpdatedAt(now);
        AgentTemplateDO persisted = updateAndRead(template);
        // 本次 id/version 的冻结表示复用持久行时间；不从当前发布头替代已冻结版本。
        templateService.appendPublication(persisted);
        return item(persisted);
    }

    public List<AdminAgentTemplateDetailResponse> history(Long id) {
        authorize("read",id);
        requireTemplate(id);
        return templateService.publications(id).stream().map(this::detail).toList();
    }

    private AdminAgentTemplateItemResponse deprecateChecked(AgentTemplateDO template) {
        if (!AgentConstants.STATUS_PUBLISHED.equals(template.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "仅已发布状态可下架");
        }
        template.setStatus(AgentConstants.STATUS_DEPRECATED);
        template.setDraftRevision(Math.addExact(template.getDraftRevision(),1L));
        template.setUpdatedAt(Instant.now());
        return item(updateAndRead(template));
    }

    /** 编辑在同一family/row锁下先读取原回执，再对新命令检查精确草稿CAS。 */
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public AdminAgentTemplateItemResponse update(Long id,long expected,UpdateAgentTemplateRequest request,String key) {
        return mutate(id,expected,"update","edit",key,request,existing->updateChecked(existing,request));
    }
    /** 发布快照、当前指针、审计和原回执原子提交；重复请求不再追加版本。 */
    @Transactional(rollbackFor=Exception.class)
    public AdminAgentTemplateItemResponse publish(Long id,long expected,String key) {
        return mutate(id,expected,"publish","publish",key,null,this::publishChecked);
    }
    /** 下架原回执不会因后续重新发布而变成另一项下架操作。 */
    @Transactional(rollbackFor=Exception.class)
    public AdminAgentTemplateItemResponse deprecate(Long id,long expected,String key) {
        return mutate(id,expected,"deprecate","deprecate",key,null,this::deprecateChecked);
    }
    /** 校验只检查精确草稿，不创建生命周期操作或回执。 */
    @Transactional(rollbackFor=Exception.class)
    public AgentTemplateValidationResponse validate(Long id,long expected) {
        authorize("validate",id);
        return validateChecked(requireRevision(id,expected));
    }
    /** 历史恢复复制可编辑内容为新草稿，绝不移动当前发布指针或改写不可变历史。 */
    @Transactional(rollbackFor=Exception.class)
    public AdminAgentTemplateItemResponse restore(Long id,long expected,String version,String key) {
        return mutate(id,expected,"restore","restore",key,version,current->{
            if(AgentConstants.STATUS_ARCHIVED.equals(current.getStatus()))throw new ApiException(HttpStatus.CONFLICT,"归档定义不可恢复草稿");
            var historic=templateService.requirePublication(id,version);
            current.setDefinition(historic.getDefinition());current.setName(historic.getName());
            current.setCategory(historic.getCategory());current.setTags(historic.getTags());
            current.setDescription(historic.getDescription());current.setIconUrl(historic.getIconUrl());
            current.setPriceType(historic.getPriceType());current.setPriceCents(historic.getPriceCents());
            current.setDraftRevision(Math.addExact(current.getDraftRevision(),1L));current.setHasUnpublishedChanges(true);current.setUpdatedAt(Instant.now());
            templateService.restoreDraftContent(current);
            return item(requireTemplate(current.getId()));
        });
    }

    /** 当前动作/目标检查先于任何回执或定义读取；锁内只为新命令核验CAS，不重放业务写入。 */
    private AdminAgentTemplateItemResponse mutate(Long id,long expected,String operation,String action,String requestKey,Object payload,
            java.util.function.Function<AgentTemplateDO,AdminAgentTemplateItemResponse> work) {
        var owner=authorize(action,id);
        String actor=actorRef(),key=com.skillforge.core.agent.deployment.AgentTemplateGrantService.requireKey(requestKey);
        String fingerprint=fingerprint(operation,expected,payload),scope=id.toString();
        var current=lockTemplateFamilyAndRow(id);
        var previous=replay(actor,operation,scope,key,fingerprint);
        if(previous!=null)return previous;
        if("update".equals(operation)&&owner.tenantId()!=null&&templateService.definitionSaveFenced(owner.tenantId(),actor,operation,scope,key))throw saveFenced();
        if(expected<1)throw new ApiException(HttpStatus.BAD_REQUEST,"expected_revision必须为正数");
        if(current.getDraftRevision()!=expected) {
            if("update".equals(operation))throw new ApiException(HttpStatus.CONFLICT,"AGENT_DEFINITION_REVISION_CONFLICT","草稿修订已变化，请重新读取",false);
            throw new ApiException(HttpStatus.CONFLICT,"草稿修订已变化，请重新读取");
        }
        var result=work.apply(current);
        complete(actor,operation,scope,key,fingerprint,result);
        return result;
    }

    /** 可信认证身份的稳定引用，不重新读取角色，也不把凭据版本写进幂等域。 */
    private String actorRef() {
        var principal=ServicePrincipalContext.get();if(principal!=null)return "service_principal:"+principal.principalId();
        var machine=ManagementContext.get();
        if(machine!=null)return "management_principal:"+machine.principalId();
        var actor=access.currentSubject();return actor.platform()?"platform_member:"+actor.memberId():"tenant:"+actor.tenantId()+":member:"+actor.memberId();
    }

    /** 原回执只返回首次提交的安全视图，当前草稿/计数变化不参与重建。 */
    private AdminAgentTemplateItemResponse replay(String actor,String operation,String scope,String key,String fingerprint) {
        var row=templateService.definitionOperation(actor,operation,scope,key);
        if(row==null)return null;
        if(!fingerprint.equals(row.fingerprint()))throw new ApiException(HttpStatus.CONFLICT,"同一幂等键对应不同定义操作参数");
        try{return json.readValue(row.resultSnapshot(),AdminAgentTemplateItemResponse.class);}
        catch(JsonProcessingException failure){throw new IllegalStateException("定义原操作回执损坏",failure);}
    }

    /** 业务行、发布快照、原回执和审计使用外层同一事务，任一步失败整体回滚。 */
    private void complete(String actor,String operation,String scope,String key,String fingerprint,AdminAgentTemplateItemResponse result) {
        try {templateService.appendDefinitionOperation(new AgentDefinitionOperationRow(actor,operation,scope,key,fingerprint,result.id(),json.writeValueAsString(result)));}
        catch(JsonProcessingException failure){throw new IllegalStateException("定义操作回执无法编码",failure);}
        var owner=owner();audit.record(owner.tenantId()==null?null:owner.tenantId().toString(),actor,owner.kind()+".agent_definitions."+("update".equals(operation)?"edit":operation),"agent_definition",result.id().toString(),
                "公共定义生命周期操作",Map.of("draft_revision",result.draftRevision(),"version",result.version(),"request_fingerprint",fingerprint));
    }

    /** 对象的规范序列化固定字段，Map排序但保留业务数组顺序，明确包含expectedRevision。 */
    private String fingerprint(String operation,Long expected,Object payload) {
        try{return TokenHash.sha256Hex(json.writeValueAsString(new OperationParameters(operation,expected,payload)));}
        catch(JsonProcessingException failure){throw new IllegalStateException("定义操作参数无法编码",failure);}
    }
    /** 首次创建使用与实际落库一致的允许默认值，空策略及非法名称先拒绝。 */
    private CreateAgentTemplateRequest normalizeCreate(CreateAgentTemplateRequest request) {
        if(request==null||request.name()==null||request.name().isBlank()||request.distributionPolicy()==null)
            throw new ApiException(HttpStatus.BAD_REQUEST,"创建定义需要明确名称与分发策略");
        if(owner().tenantId()!=null && request.distributionPolicy()!=com.skillforge.core.agent.model.AgentDistributionPolicy.AUTHORIZED_DEPLOY)
            throw new ApiException(HttpStatus.BAD_REQUEST,"机构共享定义只能选择AUTHORIZED_DEPLOY");
        return new CreateAgentTemplateRequest(request.name().trim(),request.category(),request.tags()==null?List.of():request.tags(),
                request.description(),request.iconUrl(),request.priceType()==null?"free":request.priceType(),
                request.priceCents()==null?0:request.priceCents(),request.definition()==null?AgentTemplateDefinition.empty():request.definition(),request.distributionPolicy());
    }
    /**
     * 本领域规范指纹的固定信封，不保存独立执行状态。
     * @param operation 精确生命周期动作
     * @param expectedRevision 首次创建为空，其他动作绑定请求预期草稿修订
     * @param payload 已规范化创建内容、编辑内容或恢复历史修订
     */
    private record OperationParameters(String operation,Long expectedRevision,Object payload) { }
    private static ApiException saveFenced(){return new ApiException(HttpStatus.CONFLICT,"AGENT_DEFINITION_SAVE_FENCED","原保存请求已封存，不能再次提交",false);}

    /** 机构入口拒绝平台人员或平台管理机器身份。 */
    public void requireTenantOwner(){if(owner().tenantId()==null)throw new ApiException(HttpStatus.FORBIDDEN,"需要固定机构管理身份");}
    /** 平台薄适配器不能将机构调用解释为平台源。 */
    public void requirePlatformOwner(){if(owner().tenantId()!=null)throw new ApiException(HttpStatus.FORBIDDEN,"需要平台管理身份");}
    /** 当前精确动作先于源/原回执读取，机构使用权不授予源维护权。 */
    private Owner authorize(String action,Long id) {
        var owner=owner();
        if(owner.tenantId()==null) {
            if("create".equals(action)) {
                if(ManagementContext.get()!=null)throw new ApiException(HttpStatus.FORBIDDEN,"仅平台人员可创建平台源");
                access.require("platform.agent_definitions.create");
            } else if(id!=null)management.requireDefinition("platform.agent_definitions."+action,id);
        } else {
            var principal=ServicePrincipalContext.get();
            if(principal!=null)integrations.requirePrincipal(principal,"tenant.agent_definitions."+action);
            else access.require("tenant.agent_definitions."+action);
        }
        return owner;
    }
    /** 所有者取当前已认证身份，拒绝与线程Tenant不一致的混合域。 */
    private Owner owner() {
        var principal=ServicePrincipalContext.get();
        if(principal!=null) {
            if(ManagementContext.get()!=null||!principal.tenantId().equals(TenantContext.getTenantId()))throw new ApiException(HttpStatus.FORBIDDEN,"机构源身份域不一致");
            return new Owner("tenant",Long.valueOf(principal.tenantId()));
        }
        if(ManagementContext.get()!=null)return new Owner("platform",null);
        var subject=access.currentSubject();if(subject.platform())return new Owner("platform",null);
        if(!subject.tenantId().equals(TenantContext.getTenantId()))throw new ApiException(HttpStatus.FORBIDDEN,"机构源身份域不一致");
        return new Owner("tenant",Long.valueOf(subject.tenantId()));
    }
    /** 受让方不能用源ID读取上级草稿，所有权每次从可信域核对。 */
    private boolean owned(AgentTemplateDO row){var owner=owner();return owner.kind().equals(row.getOwnerKind())&&java.util.Objects.equals(owner.tenantId(),row.getOwnerTenantId());}
    /** @param kind platform/tenant可信源域 @param tenantId 机构源当前Tenant，平台为空 */
    private record Owner(String kind,Long tenantId) { }

    private AgentTemplateDO requireRevision(Long id,long expected) {
        if(expected<1)throw new ApiException(HttpStatus.BAD_REQUEST,"expected_revision必须为正数");
        var current=lockTemplateFamilyAndRow(id);
        if(current.getDraftRevision()!=expected)throw new ApiException(HttpStatus.CONFLICT,"草稿修订已变化，请重新读取");
        return current;
    }
    private AgentTemplateDO lockTemplateFamilyAndRow(Long id) {
        AgentTemplateDO template = requireTemplate(id);
        templateService.lockFamily(template.getTemplateKey());
        return requireLockedTemplate(id);
    }

    private AgentTemplateDO requireTemplate(Long id) {
        AgentTemplateDO template = templateService.getById(id);
        if (template == null || !owned(template)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Agent 模板不存在");
        }
        return template;
    }

    private AgentTemplateDO requireLockedTemplate(Long id) {
        AgentTemplateDO template = templateService.lockTemplate(id);
        if (template == null || !owned(template)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Agent 模板不存在");
        }
        return template;
    }

    /** 原行锁事务中的管理写入回执只投影成功写入后的持久行。 */
    private AgentTemplateDO updateAndRead(AgentTemplateDO template) {
        if (!templateService.updateById(template)) throw new IllegalStateException("模板更新写入失败");
        return requireTemplate(template.getId());
    }

    private void requirePublishable(AgentTemplateDefinition definition) {
        AgentTemplateValidationResponse validation = templateService.validateDefinition(definition);
        if (!validation.valid()) {
            String messages = validation.issues().stream()
                    .map(AgentTemplateValidationResponse.Issue::message)
                    .distinct()
                    .reduce((left, right) -> left + "；" + right)
                    .orElse("模板定义不完整");
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, messages);
        }
    }

    private void applyContent(AgentTemplateDO template, UpdateAgentTemplateRequest request) {
        if (request.distributionPolicy() != null) {
            if("tenant".equals(template.getOwnerKind()) && request.distributionPolicy()!=com.skillforge.core.agent.model.AgentDistributionPolicy.AUTHORIZED_DEPLOY)
                throw new ApiException(HttpStatus.BAD_REQUEST,"机构共享定义不能独立安装或复制");
            if (template.getPublishedAt() != null && request.distributionPolicy() != template.getDistributionPolicy())
                throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "首次发布后不能切换定义分发策略", false);
            template.setDistributionPolicy(request.distributionPolicy());
        }
        if (request.name() != null && !request.name().isBlank()) {
            template.setName(request.name().trim());
        }
        if (request.category() != null) {
            template.setCategory(request.category());
        }
        if (request.tags() != null) {
            template.setTags(request.tags());
        }
        if (request.description() != null) {
            template.setDescription(request.description());
        }
        if (request.iconUrl() != null) {
            template.setIconUrl(request.iconUrl());
        }
        if (request.priceType() != null && !request.priceType().isBlank()) {
            template.setPriceType(request.priceType());
        }
        if (request.priceCents() != null) {
            template.setPriceCents(request.priceCents());
        }
        if (request.definition() != null) {
            template.setDefinition(templateService.resolveDraftDefinition(request.definition()));
        }
    }

    private AdminAgentTemplateItemResponse item(AgentTemplateDO template) {
        return new AdminAgentTemplateItemResponse(
                template.getId(), template.getTemplateKey(), template.getName(), template.getVersion(),
                template.getCategory(), template.getTags(), template.getDescription(), template.getIconUrl(),
                template.getAuthor(), template.getAuthorType(), template.getStatus(), template.getPriceType(),
                template.getPriceCents(), template.getInstallCount(), template.getPublishedAt(),
                template.getCreatedAt(), template.getUpdatedAt(), Boolean.TRUE.equals(template.getHasUnpublishedChanges()), template.getDistributionPolicy(),template.getOwnerKind(),template.getOwnerTenantId(),template.getDraftRevision());
    }

    private AdminAgentTemplateDetailResponse detail(AgentTemplateDO template) {
        return new AdminAgentTemplateDetailResponse(
                template.getId(), template.getTemplateKey(), template.getName(), template.getVersion(),
                template.getCategory(), template.getTags(), template.getDescription(), template.getIconUrl(),
                template.getAuthor(), template.getAuthorType(), template.getStatus(), template.getPriceType(),
                template.getPriceCents(), template.getInstallCount(), template.getPublishedAt(),
                template.getCreatedAt(), template.getUpdatedAt(), template.getDefinition(), Boolean.TRUE.equals(template.getHasUnpublishedChanges()), template.getDistributionPolicy(),template.getOwnerKind(),template.getOwnerTenantId(),template.getDraftRevision());
    }
}
