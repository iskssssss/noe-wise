package com.skillforge.institution.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.security.FoundationSecurityInterceptor;
import com.skillforge.institution.controller.ConnectorController;
import com.skillforge.institution.controller.ConnectorDeleteReceiptController;
import jakarta.servlet.DispatcherType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

class ConnectorPreDispatchRejectionTest {
    @Test void writesRejectedBeforeDispatchReturnTheirOperationCode() throws Exception {
        var controller = new ConnectorController(null, null, null, null, null, null);
        var handlers = Map.of(
                new HandlerMethod(controller, "createWithReceipt", ConnectorController.ConnectorCreateCommand.class), "CONNECTOR_CREATE_REJECTED",
                new HandlerMethod(controller, "updateWithReceipt", ConnectorController.ConnectorUpdateCommand.class), "CONNECTOR_UPDATE_REJECTED",
                new HandlerMethod(new ConnectorDeleteReceiptController(null), "delete", ConnectorDeleteReceiptController.Command.class), "CONNECTOR_DELETE_REJECTED");
        for (var entry : handlers.entrySet()) {
            for (var status : new HttpStatus[] {HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.TOO_MANY_REQUESTS}) {
                var failure = new ApiException(status, "ORIGINAL", "原拒绝原因", false);
                var request = new MockHttpServletRequest();
                var mapped = assertInstanceOf(ApiException.class, status == HttpStatus.TOO_MANY_REQUESTS
                        ? PublishPreDispatchRejection.rateLimit(request, entry.getKey(), failure)
                        : PublishPreDispatchRejection.map(request, entry.getKey(), failure));
                assertEquals(entry.getValue(), mapped.getCode());
                assertEquals(status, mapped.getStatus());
                assertEquals("原拒绝原因", mapped.getDetail());
                for (var dispatch : new DispatcherType[] {DispatcherType.ASYNC, DispatcherType.ERROR, DispatcherType.FORWARD}) {
                    request.setDispatcherType(dispatch);
                    assertSame(failure, PublishPreDispatchRejection.map(request, entry.getKey(), failure));
                    assertSame(failure, PublishPreDispatchRejection.rateLimit(request, entry.getKey(), failure));
                }
            }
            for (var status : new HttpStatus[] {HttpStatus.CONFLICT, HttpStatus.REQUEST_TIMEOUT, HttpStatus.INTERNAL_SERVER_ERROR}) {
                var failure = new ApiException(status, "UNKNOWN", "未取得原操作结果", false);
                assertSame(failure, PublishPreDispatchRejection.map(new MockHttpServletRequest(), entry.getKey(), failure));
                assertSame(failure, PublishPreDispatchRejection.rateLimit(new MockHttpServletRequest(), entry.getKey(), failure));
            }
        }
    }

    @Test void receiptAndDetailReadFailuresCannotResolveOriginalWrites() throws Exception {
        var controller = new ConnectorController(null, null, null, null, null, null);
        var failure = new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "登录过期", false);
        for (var handler : new HandlerMethod[] {
                new HandlerMethod(controller, "creationResult", UUID.class),
                new HandlerMethod(controller, "updateResult", UUID.class),
                new HandlerMethod(controller, "getInstance", String.class),
                new HandlerMethod(controller, "listInstances"),
                new HandlerMethod(new ConnectorDeleteReceiptController(null), "result", UUID.class) }) {
            assertSame(failure, PublishPreDispatchRejection.map(new MockHttpServletRequest(), handler, failure));
        }
        assertSame(failure, PublishPreDispatchRejection.map(new MockHttpServletRequest(), new Object(), failure));
    }

    @Test void registeredPermissionBoundaryClassifiesWriteButNotReceiptReadRejection() throws Exception {
        var access = mock(FoundationAccess.class);
        var denied = new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "当前成员无权执行", false);
        doThrow(denied).when(access).require(anyString());
        var security = mock(FoundationSecurityInterceptor.class);
        when(security.preHandle(any(), any(), any())).thenReturn(true);
        var registry = new InterceptorRegistry() {
            public java.util.List<Object> entries() { return getInterceptors(); }
        };
        new WebConfig(new PermissionCheckInterceptor(access), security, mock(RateLimitInterceptor.class),
                mock(IdempotencyInterceptor.class), "http://localhost:3000", "http://preview.local")
                .addInterceptors(registry);
        var controller = new ConnectorController(null, null, null, null, null, null);
        var handlers = Map.of(
                new HandlerMethod(controller, "createWithReceipt", ConnectorController.ConnectorCreateCommand.class), "CONNECTOR_CREATE_REJECTED",
                new HandlerMethod(controller, "updateWithReceipt", ConnectorController.ConnectorUpdateCommand.class), "CONNECTOR_UPDATE_REJECTED",
                new HandlerMethod(new ConnectorDeleteReceiptController(null), "delete", ConnectorDeleteReceiptController.Command.class), "CONNECTOR_DELETE_REJECTED",
                new HandlerMethod(controller, "updateResult", UUID.class), "PERMISSION_DENIED");
        for (var entry : handlers.entrySet()) {
            var failure = assertThrows(ApiException.class, () -> {
                for (var item : registry.entries()) {
                    var interceptor = item instanceof MappedInterceptor mapped ? mapped.getInterceptor() : (HandlerInterceptor) item;
                    assertTrue(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), entry.getKey()));
                }
            });
            assertEquals(entry.getValue(), failure.getCode());
            assertEquals(HttpStatus.FORBIDDEN, failure.getStatus());
            assertEquals("当前成员无权执行", failure.getDetail());
        }
    }
}
