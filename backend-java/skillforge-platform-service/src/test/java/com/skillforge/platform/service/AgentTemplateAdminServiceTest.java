package com.skillforge.platform.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.common.context.ManagementContext;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.dto.query.AgentTemplateQuery;
import com.skillforge.core.agent.dto.request.CreateAgentTemplateRequest;
import com.skillforge.core.agent.dto.request.UpdateAgentTemplateRequest;
import com.skillforge.core.agent.dto.response.AgentTemplateValidationResponse;
import com.skillforge.core.agent.model.*;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.application.AuditService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;

/** 定义生命周期的服务边界；真实锁竞争与事务回滚由同模块数据库测试承担。 */
class AgentTemplateAdminServiceTest {
    /** 列表权限筛选沿用真实实体Lambda元数据，不以Mock省略构造过程。 */
    @BeforeAll static void metadata(){TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(),""),AgentTemplateDO.class);}
    private AgentTemplateService templates;
    private PlatformManagementService management;
    private FoundationAccess access;
    private AuditService audit;
    private AgentTemplateAdminService service;
    private com.skillforge.core.agent.AgentDefinitionLifecycleService lifecycle;
    private com.skillforge.core.foundation.integration.InstitutionIntegrationService integrations;
    private final Map<List<String>,AgentDefinitionOperationRow> operations=new HashMap<>();
    private final Map<String,AgentTemplateDO> histories=new LinkedHashMap<>();
    private final AtomicReference<AgentTemplateDO> stored=new AtomicReference<>();
    private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /** 构造器使用正式依赖，持久化模拟只供验证授权/回执分支，不能代替数据库原子性证据。 */
    @BeforeEach void setup() {
        templates=mock(AgentTemplateService.class);management=mock(PlatformManagementService.class);access=mock(FoundationAccess.class);audit=mock(AuditService.class);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true,null,"7"));
        integrations=mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class);
        lifecycle=new com.skillforge.core.agent.AgentDefinitionLifecycleService(templates,management,access,json,audit,integrations);
        service=new AgentTemplateAdminService(lifecycle);
        when(templates.resolveDraftDefinition(any())).thenAnswer(call -> call.getArgument(0));
        when(templates.getById(any())).thenAnswer(c->copy(stored.get()));
        when(templates.lockTemplate(any())).thenAnswer(c->copy(stored.get()));
        when(templates.save(any())).thenAnswer(c->{var row=copy(c.getArgument(0));row.setCreatedAt(row.getCreatedAt().truncatedTo(ChronoUnit.MICROS));row.setUpdatedAt(row.getCreatedAt());stored.set(row);return true;});
        when(templates.updateById(any())).thenAnswer(c->{var row=copy(c.getArgument(0));row.setUpdatedAt(row.getUpdatedAt().truncatedTo(ChronoUnit.MICROS));if(row.getPublishedAt()!=null)row.setPublishedAt(row.getPublishedAt().truncatedTo(ChronoUnit.MICROS));stored.set(row);return true;});
        doAnswer(c->{var row=copy(c.getArgument(0));row.setUpdatedAt(row.getUpdatedAt().truncatedTo(ChronoUnit.MICROS));stored.set(row);return null;}).when(templates).restoreDraftContent(any());
        when(templates.validateDefinition(any())).thenReturn(new AgentTemplateValidationResponse(true,List.of()));
        when(templates.nextVersion(any())).thenAnswer(c->stored.get().getPublishedAt()==null?"1.0.0":"1."+(Integer.parseInt(stored.get().getVersion().split("\\.")[1])+1)+".0");
        doAnswer(c->{var row=copy(c.getArgument(0));histories.put(row.getVersion(),row);return null;}).when(templates).appendPublication(any());
        when(templates.requirePublication(any(),anyString())).thenAnswer(c->{var row=histories.get(c.<String>getArgument(1));
            if(row==null)throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"发布历史不存在");return copy(row);});
        when(templates.publications(any())).thenAnswer(c->{var newest=new ArrayList<>(histories.values().stream().map(this::copy).toList());Collections.reverse(newest);return newest;});
        when(templates.definitionOperation(anyString(),anyString(),anyString(),anyString())).thenAnswer(c->operations.get(List.of(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3))));
        doAnswer(c->{AgentDefinitionOperationRow row=c.getArgument(0);operations.put(List.of(row.actorRef(),row.operation(),row.objectScope(),row.requestKey()),row);return null;}).when(templates).appendDefinitionOperation(any());
    }
    /** 线程主体不泄漏至其他测试。 */ @AfterEach void cleanup(){ManagementContext.clear();com.skillforge.common.context.ServicePrincipalContext.clear();com.skillforge.common.context.TenantContext.clear();}

    /** 列表继续消费当前对象范围并映射已持久化行。 */
    @Test void listRetainsCurrentTargetFilterAndPersistedProjection() {
        var c=service.create(create(null,null),"create");when(management.allowedDefinitionIds("platform.agent_definitions.read")).thenReturn(Set.of(c.id()));
        var page=new Page<AgentTemplateDO>(1,20);page.setTotal(1);page.setRecords(List.of(copy(stored.get())));
        when(templates.page(any(Page.class),any())).thenReturn(page);
        var result=service.list(new AgentTemplateQuery(1,20,"draft","ops"," helper ",null));
        assertThat(result.total()).isEqualTo(1);assertThat(result.items()).containsExactly(c);
        verify(management).allowedDefinitionIds("platform.agent_definitions.read");
    }
    /** 两个分发策略均在同一个分页Wrapper中与名称/状态共同生效。 */
    @ParameterizedTest @EnumSource(AgentDistributionPolicy.class)
    void listAppliesDistributionPolicyTogetherWithNameAndStatusBeforePaging(AgentDistributionPolicy policy) {
        when(management.allowedDefinitionIds("platform.agent_definitions.read")).thenReturn(null);
        when(templates.page(any(Page.class),any())).thenAnswer(call->{
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentTemplateDO> wrapper=call.getArgument(1);
            assertThat(wrapper.getSqlSegment()).containsIgnoringCase("distribution").containsIgnoringCase("status");
            assertThat(wrapper.getParamNameValuePairs().values()).contains(policy,"published","%helper%");
            Page<AgentTemplateDO> page=call.getArgument(0);
            assertThat(page.getCurrent()).isEqualTo(2);assertThat(page.getSize()).isEqualTo(20);
            page.setTotal(21);page.setRecords(List.of());return page;
        });
        var result=service.list(new AgentTemplateQuery(2,20,"published",null," helper ",policy));
        assertThat(result.total()).isEqualTo(21);assertThat(result.items()).isEmpty();
    }
    /** 明细和校验保留类型化定义，不能改从未经校验的通用Map解释。 */
    @Test void detailAndValidationRetainTypedDraft() {
        var c=service.create(create(null,null),"create");var validation=new AgentTemplateValidationResponse(false,
                List.of(new AgentTemplateValidationResponse.Issue("definition.system_prompt","required","请填写系统提示词")));
        when(templates.validateDefinition(any())).thenReturn(validation);
        assertThat(service.detail(c.id()).definition()).isEqualTo(stored.get().getDefinition());
        assertThat(service.validate(c.id(),1)).isEqualTo(validation);
    }
    /** 缺省定义允许保存未完成草稿，并沿唯一创建入口规范化名称与价格。 */
    @Test void creationAllowsEmptyTypedDraftWithoutPublishing() {
        var c=service.create(new CreateAgentTemplateRequest(" helper ",null,null,null,null,null,null,null,AgentDistributionPolicy.OPEN_INSTALL),"create");
        assertThat(c.name()).isEqualTo("helper");assertThat(c.priceType()).isEqualTo("free");assertThat(c.priceCents()).isZero();
        assertThat(stored.get().getDefinition()).isEqualTo(AgentTemplateDefinition.empty());verify(templates,never()).appendPublication(any());
    }
    /** 创建原回执不能随之后的草稿或发布状态变化，默认值规范化不制造第二个对象。 */
    @Test void creationReturnsOriginalAfterLaterChangesAndCanonicalizesDefaults() {
        var first=service.create(create(null,null),"create");
        service.update(first.id(),1,update("changed"),"edit");service.publish(first.id(),2,"publish");
        var replay=service.create(create("free",0),"create");
        assertThat(replay).isEqualTo(first);assertThat(replay.status()).isEqualTo("draft");assertThat(replay.draftRevision()).isEqualTo(1);
        verify(templates,times(1)).save(any());
        assertThat(operations).hasSize(3);
    }
    /** 同键异参在任何生命周期写入前409，不能靠当前行回读混淆原参数。 */
    @Test void differentCreateContentConflictsWithoutSecondObject() {
        service.create(create(null,null),"create");
        var changed=new CreateAgentTemplateRequest("different",null,null,null,null,null,null,definition("first"),AgentDistributionPolicy.OPEN_INSTALL);
        assertThatThrownBy(()->service.create(changed,"create")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(409));
        verify(templates,times(1)).save(any());
    }
    /** update重复请求先匹配原回执，后续修改不使旧expectedRevision重试变成CAS失败。 */
    @Test void updateReplaysOriginalBeforeCheckingCurrentRevision() {
        var created=service.create(create(null,null),"create");var first=service.update(created.id(),1,update("one"),"one");
        service.update(created.id(),2,update("two"),"two");clearInvocations(templates,audit);
        assertThat(service.update(created.id(),1,update("one"),"one")).isEqualTo(first);
        verify(templates,never()).updateById(any());verifyNoInteractions(audit);
        assertThatThrownBy(()->service.update(created.id(),2,update("one"),"one")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(409));
    }
    /** 不同请求的旧CAS不能覆盖当前草稿，也不能留下成功回执。 */
    @ParameterizedTest @ValueSource(strings={"edit","publish","deprecate","restore","validate"})
    void staleNewOperationFailsBeforeWrites(String action) {
        var c=service.create(create(null,null),"create");service.update(c.id(),1,update("one"),"one");clearInvocations(templates);
        assertThatThrownBy(()->action(action,c.id(),1,"new")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(409));
        verify(templates,never()).appendDefinitionOperation(any());verify(templates,never()).updateById(any());verify(templates,never()).restoreDraftContent(any());verify(templates,never()).validateDefinition(any());
    }
    /** 当前精确动作先于任何定义或原回执读取，旧成功不保留已撤销目标权。 */
    @ParameterizedTest @ValueSource(strings={"edit","publish","deprecate","restore","validate"})
    void currentTargetPermissionPrecedesAllOperationReads(String action) {
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"denied")).when(management).requireDefinition("platform.agent_definitions."+action,7L);
        assertThatThrownBy(()->action(action,7L,1,"key")).isInstanceOf(ApiException.class);verifyNoInteractions(templates);
    }
    /** 发布保存持久原安全响应，下架与新草稿不会改变旧发布请求的结果。 */
    @Test void publishOriginalSurvivesDeprecationAndLaterDrafts() {
        var c=service.create(create(null,null),"create");var published=service.publish(c.id(),1,"publish");
        service.deprecate(c.id(),1,"deprecate");service.update(c.id(),2,update("new"),"edit");
        clearInvocations(templates,audit);assertThat(service.publish(c.id(),1,"publish")).isEqualTo(published);
        verify(templates,never()).appendPublication(any());verify(templates,never()).validateDefinition(any());verifyNoInteractions(audit);
        assertThat(histories).hasSize(1);
    }
    /** 下架原请求重试不影响之后重新发布的当前状态。 */
    @Test void deprecateReplaysOriginalAfterRepublish() {
        var c=service.create(create(null,null),"create");service.publish(c.id(),1,"publish");var down=service.deprecate(c.id(),1,"down");
        service.publish(c.id(),2,"republish");assertThat(stored.get().getStatus()).isEqualTo("published");
        assertThat(service.deprecate(c.id(),1,"down")).isEqualTo(down);assertThat(stored.get().getStatus()).isEqualTo("published");
    }
    /** 历史恢复只改变草稿；原恢复回执和不可变旧快照保持不变。 */
    @Test void restoreIsIdempotentAndDoesNotMoveCurrentPublication() {
        var c=service.create(create(null,null),"create");service.publish(c.id(),1,"v1");
        service.update(c.id(),1,update("new"),"edit");service.publish(c.id(),2,"v2");
        var restored=service.restore(c.id(),2,"1.0.0","restore");
        verify(templates).requirePublication(c.id(),"1.0.0");verify(templates,never()).publications(any());
        assertThat(restored.version()).isEqualTo("1.1.0");assertThat(stored.get().getDefinition().systemPrompt()).isEqualTo("first");
        service.publish(c.id(),3,"v3");assertThat(service.restore(c.id(),2,"1.0.0","restore")).isEqualTo(restored);
        assertThat(histories.get("1.0.0").getDefinition().systemPrompt()).isEqualTo("first");
        assertThat(histories).hasSize(3);
    }
    @Test void restoreMissingExactVersionDoesNotWriteDraftOrReceipt() {
        var c=service.create(create(null,null),"create");service.publish(c.id(),1,"publish");
        clearInvocations(templates,audit);
        assertThatThrownBy(()->service.restore(c.id(),1,"missing","restore-missing"))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(404));
        verify(templates).requirePublication(c.id(),"missing");verify(templates,never()).publications(any());
        verify(templates,never()).restoreDraftContent(any());verify(templates,never()).appendDefinitionOperation(any());
        verifyNoInteractions(audit);
    }
    /** 没有草稿变化的另一次发布仍保持同一版本，仅记录自身安全原回执。 */
    @Test void unchangedPublishDoesNotCreateExtraPublication() {
        var c=service.create(create(null,null),"create");var first=service.publish(c.id(),1,"first");
        assertThat(service.publish(c.id(),1,"other")).isEqualTo(first);verify(templates,times(1)).appendPublication(any());
    }
    /** 校验失败不更新发布指针、不追加快照/回执/成功审计。 */
    @Test void failedValidationDoesNotPublishOrCompleteReceipt() {
        var c=service.create(create(null,null),"create");clearInvocations(templates,audit);
        when(templates.validateDefinition(any())).thenReturn(new AgentTemplateValidationResponse(false,List.of(new AgentTemplateValidationResponse.Issue("model_config_id","disabled","模型未启用"))));
        assertThatThrownBy(()->service.publish(c.id(),1,"bad")).isInstanceOf(ApiException.class);
        verify(templates,never()).updateById(any());verify(templates,never()).appendPublication(any());verify(templates,never()).appendDefinitionOperation(any());verifyNoInteractions(audit);
    }
    /** 发布锁顺序不变；操作回执检查在定义行锁内，避免两个并发执行者都写入。 */
    @ParameterizedTest @ValueSource(strings={"edit","publish","deprecate","restore","validate"})
    void familyThenRowPrecedesReceiptAndMutation(String action) {
        var c=service.create(create(null,null),"create");if(Set.of("deprecate","restore").contains(action))service.publish(c.id(),1,"seed");clearInvocations(templates,management);
        action(action,c.id(),1,"request");var order=inOrder(templates);
        order.verify(templates).getById(c.id());order.verify(templates).lockFamily(c.templateKey());order.verify(templates).lockTemplate(c.id());
        if(!"validate".equals(action))order.verify(templates).definitionOperation("platform_member:7","edit".equals(action)?"update":action,c.id().toString(),"request");
        verify(management).requireDefinition("platform.agent_definitions."+action,c.id());verifyNoMoreInteractions(management);
    }
    /** 管理机器轮换凭据版本不能制造新的业务幂等主体。 */
    @Test void machineCredentialRotationKeepsStableOperationIdentity() {
        var c=service.create(create(null,null),"create");ManagementContext.set(new ManagementContext.Identity("9",1,1));
        var updated=service.update(c.id(),1,update("machine"),"machine");
        ManagementContext.set(new ManagementContext.Identity("9",2,2));assertThat(service.update(c.id(),1,update("machine"),"machine")).isEqualTo(updated);
        assertThat(operations.keySet()).anyMatch(k->k.getFirst().equals("management_principal:9"));
    }
    /** 创建仍只接受平台人员，不能借新回执扩大机器创建目标。 */
    @Test void machineCannotCreateDefinition() {
        ManagementContext.set(new ManagementContext.Identity("9",1,1));assertThatThrownBy(()->service.create(create(null,null),"key")).isInstanceOf(ApiException.class);verifyNoInteractions(templates);
    }
    /** 首次发布后不能切换分发策略，即便新请求拥有编辑权限。 */
    @Test void publishedPolicyCannotChangeViaIdempotentEditing() {
        var c=service.create(create(null,null),"create");service.publish(c.id(),1,"publish");
        var changed=new UpdateAgentTemplateRequest(null,null,null,null,null,null,null,null,AgentDistributionPolicy.AUTHORIZED_DEPLOY);
        assertThatThrownBy(()->service.update(c.id(),1,changed,"policy")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(409));
    }
    /** 更新只改草稿，发布使用持久时间冻结同一身份与计数，旧快照不随之后计数变化。 */
    @Test void publicationPreservesIdentityCountersAndFrozenPersistedMetadata() {
        var c=service.create(create(null,null),"create");stored.get().setInstallCount(3);
        var first=service.publish(c.id(),1,"publish");var frozen=service.history(c.id()).getFirst();
        assertThat(frozen.id()).isEqualTo(c.id());assertThat(frozen.version()).isEqualTo("1.0.0");
        assertThat(frozen.createdAt()).isEqualTo(first.createdAt());assertThat(frozen.updatedAt()).isEqualTo(first.updatedAt());
        assertThat(frozen.publishedAt()).isEqualTo(first.publishedAt()).isEqualTo(first.updatedAt());
        var updated=service.update(c.id(),1,update("new"),"edit");
        assertThat(updated.id()).isEqualTo(c.id());assertThat(updated.version()).isEqualTo(first.version());
        assertThat(updated.installCount()).isEqualTo(3);assertThat(updated.createdAt()).isEqualTo(c.createdAt());
        assertThat(service.history(c.id())).containsExactly(frozen);
        stored.get().setInstallCount(7);var second=service.publish(c.id(),2,"next");
        assertThat(second.version()).isEqualTo("1.1.0");assertThat(second.installCount()).isEqualTo(7);
        assertThat(service.history(c.id())).hasSize(2);assertThat(service.history(c.id()).get(1)).isEqualTo(frozen);
    }
    /** 创建存储未生效不得以候选值补成功，更不能消费幂等key。 */
    @Test void failedCreateDoesNotReadOrCompleteCandidate() {
        doReturn(false).when(templates).save(any());
        assertThatThrownBy(()->service.create(create(null,null),"failed")).isInstanceOf(IllegalStateException.class);
        verify(templates,never()).getById(any());verify(templates,never()).appendDefinitionOperation(any());verifyNoInteractions(audit);
    }
    /** 发布更新失败不能冻结未提交版本或留下成功原回执。 */
    @Test void failedPublicationWriteDoesNotFreezeOrCompleteCandidate() {
        var c=service.create(create(null,null),"create");clearInvocations(templates,audit);doReturn(false).when(templates).updateById(any());
        assertThatThrownBy(()->service.publish(c.id(),1,"failed")).isInstanceOf(IllegalStateException.class);
        verify(templates,times(1)).getById(c.id());verify(templates,never()).appendPublication(any());
        verify(templates,never()).appendDefinitionOperation(any());verifyNoInteractions(audit);
    }
    /** 持久回读缺失时不能回退内存候选或当前发布头。 */
    @Test void missingPublicationReadBackDoesNotFreezeCandidate() {
        var c=service.create(create(null,null),"create");clearInvocations(templates,audit);
        when(templates.getById(c.id())).thenReturn(copy(stored.get()),null);
        assertThatThrownBy(()->service.publish(c.id(),1,"failed")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(404));
        verify(templates,never()).appendPublication(any());verify(templates,never()).currentPublished(any());
        verify(templates,never()).appendDefinitionOperation(any());verifyNoInteractions(audit);
    }
    /** 回读技术异常必须传播给事务边界，不以旧行返回成功。 */
    @Test void failedPublicationReadBackPropagatesOriginalFailure() {
        var c=service.create(create(null,null),"create");clearInvocations(templates,audit);var failure=new IllegalStateException("controlled persisted read failure");
        when(templates.getById(c.id())).thenReturn(copy(stored.get())).thenThrow(failure);
        assertThatThrownBy(()->service.publish(c.id(),1,"failed")).isSameAs(failure);
        verify(templates,never()).appendPublication(any());verify(templates,never()).appendDefinitionOperation(any());verifyNoInteractions(audit);
    }
    /** 旧无key旁路移除后，正式入口仍在所有持久化前保护非法生命周期状态。 */
    @ParameterizedTest @ValueSource(strings={"edit","publish","restore","deprecate"})
    void invalidLifecycleStatesRejectBeforeWrites(String action) {
        var c=service.create(create(null,null),"create");if(!"deprecate".equals(action))stored.get().setStatus("archived");clearInvocations(templates,audit);
        assertThatThrownBy(()->action(action,c.id(),1,"invalid")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(409));
        verify(templates,never()).updateById(any());verify(templates,never()).appendPublication(any());
        verify(templates,never()).restoreDraftContent(any());verify(templates,never()).appendDefinitionOperation(any());verifyNoInteractions(audit);
    }
    /** 只适配正式公开动作，使参数化断言共用同一key/CAS契约。 */
    private Object action(String action,Long id,long expected,String key){return switch(action){
        case "edit"->service.update(id,expected,update("changed"),key);
        case "publish"->service.publish(id,expected,key);
        case "deprecate"->service.deprecate(id,expected,key);
        case "restore"->service.restore(id,expected,"1.0.0",key);
        case "validate"->service.validate(id,expected);
        default->throw new AssertionError(action);
    };}
    /** 请求DTO保持创建默认值由唯一规范化入口形成。 */
    static CreateAgentTemplateRequest create(String price,Integer cents){return new CreateAgentTemplateRequest(" helper ",null,null,"description",null,price,cents,definition("first"),AgentDistributionPolicy.OPEN_INSTALL);}
    /** 完整定义替换是有意义的内容变化。 */
    static UpdateAgentTemplateRequest update(String prompt){return new UpdateAgentTemplateRequest(null,null,null,null,null,null,null,definition(prompt),null);}
    /** 公共定义只引用平台模型及公开能力，测试不连接真实模型。 */
    static AgentTemplateDefinition definition(String prompt){return new AgentTemplateDefinition(prompt,"role","1",null,List.of(),List.of(),List.of());}
    /** 机构所有权和消费分发策略由当前身份及源契约确定，不能将普通源开放复制。 */
    @Test void tenantSourceIsOwnedByCurrentTenantAndCannotSelectOpenInstall() {
        tenant("7");assertThatThrownBy(()->lifecycle.create(create(null,null),"open")).isInstanceOf(ApiException.class);verify(templates,never()).save(any());
        var c=lifecycle.create(tenantCreate(),"tenant");assertThat(c.ownerKind()).isEqualTo("tenant");assertThat(c.ownerTenantId()).isEqualTo(7L);
        assertThat(c.distributionPolicy()).isEqualTo(AgentDistributionPolicy.AUTHORIZED_DEPLOY);verify(access,times(2)).require("tenant.agent_definitions.create");
        assertThatThrownBy(()->service.detail(c.id())).isInstanceOf(ApiException.class);
    }
    /** 源使用动作不授予其他机构的草稿、发布历史或修改权限。 */
    @Test void receivingTenantCannotReadOrEditOwnerDraftEvenWithItsOwnLifecycleAction() {
        tenant("7");var c=lifecycle.create(tenantCreate(),"source");tenant("8");
        assertThatThrownBy(()->lifecycle.detail(c.id())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(404));
        assertThatThrownBy(()->lifecycle.history(c.id())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->lifecycle.update(c.id(),1,update("stolen"),"edit")).isInstanceOf(ApiException.class);
        assertThat(stored.get().getDefinition().systemPrompt()).isEqualTo("first");
    }
    /** 机构机器使用精确scope且不冒充成员，凭据轮换后同key仍取原结果。 */
    @Test void tenantMachineUsesStablePrincipalAndExactDefinitionScopes() {
        com.skillforge.common.context.TenantContext.setTenantId("7");
        var first=new com.skillforge.common.context.ServicePrincipalContext.Identity("31","7","integration",1,Set.of("tenant.agent_definitions.create"));
        com.skillforge.common.context.ServicePrincipalContext.set(first);clearInvocations(access);
        var c=lifecycle.create(tenantCreate(),"source");
        var rotated=new com.skillforge.common.context.ServicePrincipalContext.Identity("31","7","integration",2,first.scopes());
        com.skillforge.common.context.ServicePrincipalContext.set(rotated);
        assertThat(lifecycle.create(tenantCreate(),"source")).isEqualTo(c);verify(templates,times(1)).save(any());
        verify(integrations).requirePrincipal(first,"tenant.agent_definitions.create");verify(integrations).requirePrincipal(rotated,"tenant.agent_definitions.create");
        verifyNoInteractions(access);assertThat(operations.keySet()).anyMatch(key->key.getFirst().equals("service_principal:31"));
    }
    /** 建立本测试已经认证的机构人员身份，不创建平台或执行权限。 */
    private void tenant(String id){when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,id,"9"));com.skillforge.common.context.TenantContext.setTenantId(id);}
    /** 机构源只能明确声明受控部署。 */
    private static CreateAgentTemplateRequest tenantCreate(){return new CreateAgentTemplateRequest("shared",null,List.of(),null,null,"free",0,definition("first"),AgentDistributionPolicy.AUTHORIZED_DEPLOY);}
    /** 模拟数据库读取不会让测试直接持有可写存储对象。 */
    private AgentTemplateDO copy(AgentTemplateDO row){if(row==null)return null;return json.convertValue(row,AgentTemplateDO.class);}
}
