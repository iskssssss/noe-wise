package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.dto.identity.CreateServicePrincipalRequest;
import com.skillforge.core.foundation.persistence.ServicePrincipalMapper;
import com.skillforge.core.foundation.persistence.ServicePrincipalDO;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import com.skillforge.institution.exception.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 管理 API 只在签发/轮换响应中返回明文凭证，列表不泄露密文。 */
class ServicePrincipalControllerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void issuesListsRotatesAndChangesStatusInCurrentTenant() {
        TenantContext.setTenantId("7");
        ServicePrincipalService service = mock(ServicePrincipalService.class);
        ServicePrincipalDO principal = principal();
        when(service.issue("7", "crm", Set.of("sessions.events.read")))
                .thenReturn(new ServicePrincipalService.IssuedPrincipal(principal, "sfsp_key", "secret"));
        when(service.rotate("7", 12L))
                .thenReturn(new ServicePrincipalService.IssuedPrincipal(principal, "sfsp_rotated", "new-secret"));
        when(service.list("7")).thenReturn(List.of(principal));
        when(service.suspend("7", 12L)).thenReturn(true);
        when(service.revoke("7", 12L)).thenReturn(true);
        ServicePrincipalController controller = new ServicePrincipalController(service);

        assertEquals("sfsp_key", controller.issue(new CreateServicePrincipalRequest(
                "crm", List.of("sessions.events.read"))).data().apiKey());
        assertEquals("sp-1", controller.list().data().getFirst().principalId());
        assertEquals("sfsp_rotated", controller.rotate(12L).apiKey());
        assertEquals(true, controller.suspend(12L).get("suspended"));
        assertEquals(true, controller.revoke(12L).get("revoked"));
        verify(service).revoke("7", 12L);
    }

    /** 实际请求绑定、服务输入拒绝和公共异常映射组合验证；非法 scope 没有凭据写入。 */
    @Test void invalidScopeReturns400AndPreservesDirectoryAndCredentials() throws Exception {
        TenantContext.setTenantId("7");
        var mapper = mock(ServicePrincipalMapper.class);
        var crypto = mock(CryptoService.class);
        var row = storedPrincipal(ServicePrincipalService.ACTIVE);
        when(mapper.selectList(any())).thenReturn(List.of(row));
        var json = new ObjectMapper().findAndRegisterModules();
        var beforeRecord = json.valueToTree(row);
        var mvc = http(new ServicePrincipalService(mapper, crypto));
        var beforeDirectory = directory(mvc, json);

        mvc.perform(post("/api/v1/service-principals").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"integration_id\":\"crm\",\"scopes\":[\"UPPER BAD\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.retryable").value(false));

        assertEquals(beforeDirectory, directory(mvc, json));
        assertEquals(beforeRecord, json.valueToTree(row));
        verify(mapper, never()).insert(any(ServicePrincipalDO.class));
        verify(mapper, never()).update(any(), any());
        verifyNoInteractions(crypto);
    }

    /** 已停用的实际对象返回状态冲突；安全目录和整行凭据均保持原值。 */
    @Test void suspendedRotateReturns409AndPreservesDirectoryAndCredentials() throws Exception {
        TenantContext.setTenantId("7");
        var mapper = mock(ServicePrincipalMapper.class);
        var crypto = mock(CryptoService.class);
        var row = storedPrincipal(ServicePrincipalService.SUSPENDED);
        when(mapper.selectList(any())).thenReturn(List.of(row));
        when(mapper.selectOne(any())).thenReturn(row);
        var json = new ObjectMapper().findAndRegisterModules();
        var beforeRecord = json.valueToTree(row);
        var mvc = http(new ServicePrincipalService(mapper, crypto));
        var beforeDirectory = directory(mvc, json);

        mvc.perform(post("/api/v1/service-principals/rotate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":12}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.retryable").value(false));

        assertEquals(beforeDirectory, directory(mvc, json));
        assertEquals(beforeRecord, json.valueToTree(row));
        verify(mapper, never()).insert(any(ServicePrincipalDO.class));
        verify(mapper, never()).update(any(), any());
        verifyNoInteractions(crypto);
    }

    /** 组合真实服务与正式异常处理器，不装配数据库、认证过滤器或事务代理。 */
    private static MockMvc http(ServicePrincipalService service) {
        var json = new ObjectMapper().findAndRegisterModules()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return MockMvcBuilders.standaloneSetup(new ServicePrincipalController(service))
                .setControllerAdvice(new GlobalExceptionHandler(json))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }

    /** 比较全部公开目录字段，排除每请求独立的 trace 信封。 */
    private static JsonNode directory(MockMvc mvc, ObjectMapper json) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/service-principals")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }

    /** 在原安全视图夹具上补齐全部持久凭据字段，以逐字段检查拒绝后的原值。 */
    private ServicePrincipalDO storedPrincipal(String status) {
        var row = principal();
        row.setTenantId(7L);
        row.setApiKeyHash("original-api-key-hash");
        row.setStatus(status);
        row.setUpdatedAt(row.getCreatedAt().plusSeconds(1));
        return row;
    }

    private ServicePrincipalDO principal() {
        ServicePrincipalDO principal = new ServicePrincipalDO();
        principal.setId(12L);
        principal.setPrincipalId("sp-1");
        principal.setIntegrationId("crm");
        principal.setApiKeyPrefix("sfsp_1234567");
        principal.setSigningSecretEncrypted("never-returned");
        principal.setScopes(List.of("sessions.events.read"));
        principal.setStatus("active");
        principal.setCredentialVersion(1L);
        principal.setCreatedAt(Instant.parse("2026-08-24T12:00:00Z"));
        return principal;
    }
}
