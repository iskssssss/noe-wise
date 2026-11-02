package com.skillforge.institution.exception;

import com.fasterxml.jackson.databind.ObjectMapper;

import cn.dev33.satoken.exception.NotLoginException;
import com.skillforge.core.shared.web.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 全局异常处理器单元测试。
 *
 * <p>SSE 握手未提交时使用 JSON 错误信封；提交后的流不混写普通 JSON。
 */
class GlobalExceptionHandlerTest {

    /** 被测异常处理器。 */
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new ObjectMapper());

    /**
     * SSE 握手收到未登录异常时返回 401 JSON。
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
    void uncommittedFileFailureDropsAttachmentHeaders() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("Content-Disposition", "attachment; filename=report.pdf");
        response.setHeader("Content-Length", "100");
        var result = handler.handle(new IllegalStateException("storage failure"), response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatusCode());
        assertEquals(null, response.getHeader("Content-Disposition"));
        assertEquals(null, response.getHeader("Content-Length"));
    }
}
