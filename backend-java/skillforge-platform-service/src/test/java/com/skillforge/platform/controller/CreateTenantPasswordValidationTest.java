package com.skillforge.platform.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.platform.dto.tenant.CreateTenantRequest;
import com.skillforge.platform.dto.tenant.TenantDetailResponse;
import com.skillforge.platform.exception.GlobalExceptionHandler;
import com.skillforge.platform.service.PlatformTenantAccountService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** 初始管理员密码的真实 DTO、Controller 绑定和错误投影；权限与数据库由宿主集成验证。 */
class CreateTenantPasswordValidationTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private PlatformTenantAccountService service;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        service = mock(PlatformTenantAccountService.class);
        when(service.createTenant(any())).thenReturn(new TenantDetailResponse(11L, "机构", "password-test", 71L,
                "free", "active", "cloud", 1L, null, null, null, null, null, null, null, null));
        validator = new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new PlatformTenantController(service))
                .setControllerAdvice(new GlobalExceptionHandler(json))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .setValidator(validator).build();
    }

    @AfterEach void closeValidator() { validator.close(); }

    /** 8–11 个 ASCII 字符均在 DTO 阶段按批准的 12 码点约束拒绝。 */
    @ParameterizedTest @ValueSource(ints = {8, 9, 10, 11})
    void eightToElevenCharactersHaveTwelveCodePointFieldError(int length) throws Exception {
        var error = rejected("Aa1" + "x".repeat(length - 3));
        assertEquals("密码至少 12 个字符（按 Unicode 码点计数）", error);
        assertFalse(error.contains("UTF-16")); assertFalse(error.contains("至少 8"));
        verifyNoInteractions(service);
    }

    /** 满足长度也必须含 ASCII 字母和数字；其它 Unicode 字母或数字不能替代。 */
    @Test void twelveCodePointsWithoutAsciiLetterOrDigitAreFieldErrors() throws Exception {
        for (String password : List.of("ABCDEFGHIJKL", "123456789012", "é".repeat(11) + "1", "A".repeat(11) + "١"))
            assertEquals("密码必须包含英文字母和数字", rejected(password));
        verifyNoInteractions(service);
    }

    /** emoji 代理对只计一个 Unicode 码点，UTF-16 长度不能使 11 码点通过。 */
    @Test void surrogatePairsUseCodePointsAtTheElevenAndTwelveBoundary() throws Exception {
        String shorter = "Aa1" + "🙂".repeat(8), valid = "Aa1" + "🙂".repeat(9);
        assertTrue(shorter.length() >= 12); assertEquals(11, shorter.codePointCount(0, shorter.length()));
        assertEquals("密码至少 12 个字符（按 Unicode 码点计数）", rejected(shorter));
        assertEquals(12, valid.codePointCount(0, valid.length())); accepted(valid);
    }

    /** 73 个实际 UTF-8 字节拒绝，中文与 emoji 的精确 72 字节均保留原文受理。 */
    @Test void seventyTwoAndSeventyThreeUtf8BytesHaveAnExactBoundary() throws Exception {
        String chinese = "Aa1" + "界".repeat(23), emoji = "Aa1" + "🙂".repeat(17) + "z";
        assertEquals(73, (chinese + "x").getBytes(StandardCharsets.UTF_8).length);
        assertEquals("密码的 UTF-8 编码最多 72 字节", rejected(chinese + "x"));
        for (String password : List.of(chinese, emoji)) {
            assertEquals(72, password.getBytes(StandardCharsets.UTF_8).length); accepted(password);
        }
    }

    /** 密码不 trim、不规范化组合字符，服务允许的换行字符仍按原文绑定。 */
    @Test void whitespaceCombiningCharactersAndNewlinesReachTheServiceIntact() throws Exception {
        for (String password : List.of(" Aa1" + "x".repeat(7) + " ", "Aa1" + "e\u0301".repeat(4) + "x", "Aa1\n" + "x".repeat(8))) {
            assertEquals(12, password.codePointCount(0, password.length())); accepted(password);
        }
    }

    /** 空值仍由既有必填约束拒绝，敏感 rejectedValue 不进入公共响应。 */
    @Test void nullPasswordKeepsItsRequiredFieldError() throws Exception {
        assertEquals("请输入管理员密码", rejected(null)); verifyNoInteractions(service);
    }

    /** 拒绝响应只包含 snake_case 字段路径与固定约束，不调用创建服务。 */
    private String rejected(String password) throws Exception {
        int calls = mockingDetails(service).getInvocations().size();
        var body = mvc.perform(post("/api/v1/admin/tenants").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request(password)))).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var response = json.readTree(body);
        assertEquals("参数校验失败", response.path("message").asText());
        assertTrue(response.path("field_errors").has("admin_password"));
        assertFalse(response.path("field_errors").has("adminPassword"));
        assertFalse(body.contains("rejectedValue")); assertFalse(body.contains("rejected_value"));
        if (password != null) assertFalse(body.contains(password));
        assertEquals(calls, mockingDetails(service).getInvocations().size());
        return response.path("field_errors").path("admin_password").asText();
    }

    /** 正常响应使用安全详情投影，验证原始密码没有在 DTO 绑定时被改写。 */
    private void accepted(String password) throws Exception {
        var body = mvc.perform(post("/api/v1/admin/tenants").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request(password)))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        verify(service).createTenant(argThat(request -> password.equals(request.adminPassword())));
        assertFalse(body.contains(password)); assertFalse(body.contains("admin_password"));
    }

    private static CreateTenantRequest request(String password) {
        return new CreateTenantRequest("机构", "password-test", null, "administrator", password, null);
    }
}
