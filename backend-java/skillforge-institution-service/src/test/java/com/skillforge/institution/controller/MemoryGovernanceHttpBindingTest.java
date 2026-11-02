package com.skillforge.institution.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.common.context.TenantContext;
import com.skillforge.institution.exception.GlobalExceptionHandler;
import com.skillforge.institution.memory.MemoryGovernanceService;
import com.skillforge.runtime.api.MemoryScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 仅检验 MVC JSON 绑定与治理服务委托；预置 tenant 不代表 JWT/Foundation 授权已验证。 */
class MemoryGovernanceHttpBindingTest {
    private final MemoryGovernanceService service = mock(MemoryGovernanceService.class);
    private MockMvc mvc;

    /** 复用宿主 snake_case 配置和异常映射；服务语义由真实服务定向测试验证。 */
    @BeforeEach
    void setUp() {
        TenantContext.setTenantId("101");
        ObjectMapper json = new ObjectMapper().findAndRegisterModules()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mvc = MockMvcBuilders.standaloneSetup(new MemoryGovernanceController(service))
                .setControllerAdvice(new GlobalExceptionHandler(json))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }

    /** 释放线程上下文，避免后续测试继承当前预置租户。 */
    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** confirmed_scope 必须作为 payload 的枚举值和原 Idempotency-Key 一起传给专用审核服务。 */
    @Test
    void explicitUserScopeBindsFromNestedPayloadAndPreservesIdempotencyKey() throws Exception {
        mvc.perform(post("/api/v1/memories/review").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "user-review-key")
                .content("""
                        {"fact_id":"104","payload":{"expected_revision":4,"approved":true,"confirmed_scope":"USER"}}
                        """))
                .andExpect(status().isOk());

        verify(service).review("101", "104", 4, true, MemoryScope.USER, "user-review-key");
    }

    /** 拒绝不要求新增字段；缺失与显式 null 均原样传为 null，不补 USER 默认值。 */
    @Test
    void rejectionAllowsMissingOrNullConfirmedScope() throws Exception {
        mvc.perform(post("/api/v1/memories/review").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "reject-missing")
                .content("""
                        {"fact_id":"104","payload":{"expected_revision":4,"approved":false}}
                        """))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/memories/review").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "reject-null")
                .content("""
                        {"fact_id":"104","payload":{"expected_revision":4,"approved":false,"confirmed_scope":null}}
                        """))
                .andExpect(status().isOk());

        verify(service).review("101", "104", 4, false, null, "reject-missing");
        verify(service).review("101", "104", 4, false, null, "reject-null");
    }

    /** 未登记枚举不能被静默归为 null，也不能触及治理服务。 */
    @Test
    void unknownConfirmedScopeFailsBindingBeforeServiceInvocation() throws Exception {
        mvc.perform(post("/api/v1/memories/review").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fact_id":"104","payload":{"expected_revision":4,"approved":true,"confirmed_scope":"GLOBAL"}}
                        """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
