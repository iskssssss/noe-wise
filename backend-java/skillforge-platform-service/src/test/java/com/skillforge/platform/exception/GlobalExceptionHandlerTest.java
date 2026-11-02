package com.skillforge.platform.exception;

import com.fasterxml.jackson.databind.ObjectMapper;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.SaTokenException;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全局异常处理器单元测试。
 *
 * <p>重点覆盖 SSE 请求的错误响应：错误事件必须直接写回
 * {@code text/event-stream}，否则内容协商失败会级联成 500。
 */
class GlobalExceptionHandlerTest {

    /** 被测异常处理器。 */
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new ObjectMapper());

    /**
     * SSE 请求收到未登录异常时应返回 401 与 event:error，而不是 500。
     *
     * @throws Exception 响应写入失败时抛出
     */
    @Test
    void uncommittedSseHandshakeUsesJsonFailureContract() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<ApiResponse<Void>> result =
                handler.handle(new NotLoginException("", "", ""), response);

        assertEquals(HttpStatus.UNAUTHORIZED, result.getStatusCode());
        assertEquals("UNAUTHORIZED", result.getBody().code());
        assertEquals("未登录或登录已过期", result.getBody().message());
    }

    /**
     * 普通 JSON 请求的错误响应格式应保持不变。
     *
     * @throws Exception 响应写入失败时抛出
     */
    @Test
    void jsonErrorResponseStaysJson() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<ApiResponse<Void>> result =
                handler.handle(new NotLoginException("", "", ""), response);

        assertEquals(HttpStatus.UNAUTHORIZED, result.getStatusCode());
        assertEquals("未登录或登录已过期", result.getBody().message());
        assertEquals("", response.getContentAsString());
    }

    @Test
    void businessErrorsMapAllContractStatusCodes() throws Exception {
        for (HttpStatus status : List.of(
                HttpStatus.BAD_REQUEST,
                HttpStatus.UNAUTHORIZED,
                HttpStatus.FORBIDDEN,
                HttpStatus.NOT_FOUND,
                HttpStatus.CONFLICT,
                HttpStatus.UNPROCESSABLE_ENTITY,
                HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.INTERNAL_SERVER_ERROR)) {
            ResponseEntity<ApiResponse<Void>> result = handler.handle(
                    new ApiException(status, "detail"), new MockHttpServletResponse());
            assertEquals(status, result.getStatusCode());
            assertEquals("detail", result.getBody().message());
        }
    }

    @Test
    void capabilityErrorKeepsDomainCodeMessageAndCause() {
        var cause = new IllegalArgumentException("source");
        var exception = com.skillforge.core.capability.CapabilityCatalogErrors.exception(
                HttpStatus.CONFLICT, com.skillforge.core.capability.CapabilityCatalogErrors.DUPLICATE,
                "capability already exists", cause);
        var result = handler.handle(exception, new MockHttpServletResponse());
        assertEquals(HttpStatus.CONFLICT, result.getStatusCode());
        assertEquals("CAPABILITY_DUPLICATE", result.getBody().code());
        assertEquals("capability already exists", result.getBody().message());
        assertEquals(false, result.getBody().retryable());
        assertEquals(cause, exception.getCause());
    }

    @Test
    void validationAndSecurityExceptionsUseStableJsonContract() throws Exception {
        BindingResult binding = mock(BindingResult.class);
        MethodArgumentNotValidException validation = mock(MethodArgumentNotValidException.class);
        when(validation.getBindingResult()).thenReturn(binding);
        when(binding.getFieldErrors()).thenReturn(List.of(new FieldError("request", "name", "name required")));
        ResponseEntity<ApiResponse<Void>> result = handler.handle(
                validation, new MockHttpServletResponse());
        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        assertEquals("参数校验失败", result.getBody().message());
        assertEquals("name required", result.getBody().fieldErrors().get("name"));

        when(binding.getFieldErrors()).thenReturn(List.of(new FieldError("request", "name", null)));
        result = handler.handle(validation, new MockHttpServletResponse());
        assertEquals("输入无效", result.getBody().fieldErrors().get("name"));

        when(binding.getFieldErrors()).thenReturn(List.of());
        result = handler.handle(validation, new MockHttpServletResponse());
        assertEquals("参数校验失败", result.getBody().message());

        result = handler.handle(new SaTokenException("missing context"), new MockHttpServletResponse());
        assertEquals(HttpStatus.UNAUTHORIZED, result.getStatusCode());
    }

    @Test
    void notFoundAndUnknownExceptionsAreSanitized() throws Exception {
        ResponseEntity<ApiResponse<Void>> notFound = handler.handle(
                new NoResourceFoundException(HttpMethod.GET, "/missing"), new MockHttpServletResponse());
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
        assertEquals("Not Found", notFound.getBody().message());

        ResponseEntity<ApiResponse<Void>> unknown = handler.handle(
                new IllegalStateException("secret internal detail"), new MockHttpServletResponse());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, unknown.getStatusCode());
        assertEquals("内部错误", unknown.getBody().message());
    }
}
