package com.skillforge.institution.controller;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.knowledge.KnowledgeStorageService;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.exception.GlobalExceptionHandler;
import com.skillforge.institution.service.InstitutionResourceAccess;
import com.skillforge.institution.service.KnowledgeManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 知识治理控制器身份/资源检查；实际认证、Permission切面与RLS仍需HTTP真实依赖验收。 */
class KnowledgeStorageControllerTest {
    private KnowledgeStorageService storage;
    private KnowledgeManagementService management;
    private FoundationAccess access;
    private InstitutionResourceAccess resources;
    private KnowledgeStorageController controller;

    /** 绑定明确可信测试租户，业务请求体无法提供或覆盖此身份。 */
    @BeforeEach void setup() {
        TenantContext.setTenantId("7");
        storage=mock(KnowledgeStorageService.class);management=mock(KnowledgeManagementService.class);
        access=mock(FoundationAccess.class);resources=mock(InstitutionResourceAccess.class);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","71"));
        when(access.currentDataScope()).thenReturn(new FoundationAccess.DataScope(true,"71",Set.of()));
        controller=new KnowledgeStorageController(storage,management,access,resources);
    }
    /** 恢复线程上下文，避免下个用例继承租户授权。 */
    @AfterEach void cleanup() { TenantContext.clear(); }

    /** 平台、外部凭据及不同机构主体不得操作机构知识总预算。 */
    @Test void rejectsNonInstitutionAndForeignSubjectsBeforeStorage() {
        var command=new KnowledgeStorageController.BudgetCommand(false,1000L,100L);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true,null,"71"));
        assertThrows(RuntimeException.class,()->controller.budget(command));
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"8","71"));
        assertThrows(RuntimeException.class,()->controller.budget(command));
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","71"));
        when(resources.external()).thenReturn(true);
        assertThrows(RuntimeException.class,()->controller.budget(command));
        verifyNoInteractions(storage);
    }
    /** 本人/部门范围不会被容量查询或预算配置扩大为全机构范围。 */
    @Test void rejectsLimitedDataScopeForTenantWideCapacityAndBudget() {
        when(access.currentDataScope()).thenReturn(new FoundationAccess.DataScope(false,"71",Set.of("1")));
        assertThrows(RuntimeException.class,()->controller.capacity());
        assertThrows(RuntimeException.class,()->controller.budget(new KnowledgeStorageController.BudgetCommand(false,1000L,100L)));
        verifyNoInteractions(storage);
    }
    /** 预览只读取同对象候选；执行仍先通过对象manage授权。 */
    @Test void dryRunUsesPreviewAndNeverDeletes() {
        Instant cutoff=Instant.parse("2026-01-01T00:00:00Z");
        when(storage.previewRecycling(7,10,cutoff,100)).thenReturn(List.of("91"));
        controller.recycle(new KnowledgeStorageController.RecycleCommand(10L,cutoff,100,true));
        verify(management).requireBase("7",10L,"manage");
        verify(storage).previewRecycling(7,10,cutoff,100);
        verify(storage,never()).recycle(anyLong(),anyLong(),any(),anyInt());
    }
    /** 对象授权失败发生在任何候选读取或删除之前。 */
    @Test void unauthorizedObjectDoesNotReadOrDeleteStorage() {
        doThrow(new SecurityException("denied")).when(management).requireBase("7",10L,"manage");
        assertThrows(SecurityException.class,()->controller.recycle(new KnowledgeStorageController.RecycleCommand(
                10L,Instant.parse("2026-01-01T00:00:00Z"),100,false)));
        verifyNoInteractions(storage);
    }

    /** 模式和额度必须表达同一个受理意图，拒绝请求不能提前改动预算。 */
    @Test void invalidBudgetModesHaveNoStorageSideEffects() {
        for (var command:List.of(new KnowledgeStorageController.BudgetCommand(true,1000L,null),
                new KnowledgeStorageController.BudgetCommand(false,null,null))) {
            var failure=assertThrows(ApiException.class,()->controller.budget(command));
            assertEquals("knowledge_storage_budget_mode_invalid",failure.getCode());
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,failure.getStatus());
            assertFalse(failure.isRetryable());
        }
        verifyNoInteractions(storage);
    }

    /** 字段合法但模式矛盾由正式异常映射返回领域422，拒绝前不调用存储。 */
    @Test void httpBudgetModeConflictsExposeDomainCodeWithoutStorageSideEffects() throws Exception {
        var http=http();
        for (String body:List.of("{\"observation_only\":true,\"max_content_bytes\":1000}",
                "{\"observation_only\":false}")) {
            http.perform(post("/api/v1/knowledge/storage/budget").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("knowledge_storage_budget_mode_invalid"))
                    .andExpect(jsonPath("$.retryable").value(false));
        }
        verifyNoInteractions(storage);
    }

    /** 观测模式明确清空额度，只调用正常预算入口，不触发历史回收。 */
    @Test void observationModeClearsBothLimitsWithoutRecycling() {
        controller.budget(new KnowledgeStorageController.BudgetCommand(true,null,null));
        verify(storage).configureBudget(7,null,null);
        verify(storage).capacity(7);
        verifyNoMoreInteractions(storage);
    }

    /** 使用正式异常映射和请求字段绑定，必填或非法额度在领域写入前拒绝。 */
    @Test void httpBudgetBindingRejectsMissingModeAndNonPositiveLimit() throws Exception {
        var http=http();
        http.perform(post("/api/v1/knowledge/storage/budget").contentType(MediaType.APPLICATION_JSON)
                .content("{\"max_content_bytes\":1000}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.field_errors.observation_only").exists());
        http.perform(post("/api/v1/knowledge/storage/budget").contentType(MediaType.APPLICATION_JSON)
                .content("{\"observation_only\":false,\"max_content_bytes\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field_errors.max_content_bytes").exists());
        verifyNoInteractions(storage);
    }

    /** 请求必须提供past截止、合法批次和预览意图，校验拒绝不能读取候选。 */
    @Test void httpRecycleBindingRequiresExplicitSafeBoundary() throws Exception {
        var http=http();
        http.perform(post("/api/v1/knowledge/storage/recycle").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kb_id\":10,\"before\":\"2026-01-01T00:00:00Z\",\"limit\":201,\"dry_run\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field_errors.limit").exists());
        http.perform(post("/api/v1/knowledge/storage/recycle").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kb_id\":10,\"limit\":100,\"dry_run\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field_errors.before").exists());
        verifyNoInteractions(storage,management);
    }

    /** 即使宿主Jackson默认忽略未知字段，固定治理DTO也不能吞掉租户身份或配置拼写错误。 */
    @Test void httpRejectsUndeclaredIdentityAndBudgetFieldsBeforeSideEffects() throws Exception {
        var http=http();
        http.perform(post("/api/v1/knowledge/storage/budget").contentType(MediaType.APPLICATION_JSON)
                .content("{\"observation_only\":true,\"max_content_byte\":1000}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        http.perform(post("/api/v1/knowledge/storage/recycle").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kb_id\":10,\"before\":\"2026-01-01T00:00:00Z\",\"limit\":100,\"dry_run\":true,\"tenant_id\":8}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(storage,management);
    }

    /** 该HTTP夹具只证明MVC绑定/校验，正式认证、权限切面和数据库RLS仍由集成窗口验收。 */
    private MockMvc http() {
        var json=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler(json))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }
}
