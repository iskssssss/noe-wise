package com.skillforge.institution.config;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.controller.ConversationTaskController;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;

class ConversationTaskPreDispatchRejectionTest {
    @Test void onlyTaskWritesIdentifyPreDispatchRejection() throws Exception {
        var controller = new ConversationTaskController(null);
        var handlers = new HandlerMethod[] {
            new HandlerMethod(controller, "select", ConversationTaskController.Selection.class),
            new HandlerMethod(controller, "update", ConversationTaskController.Update.class)
        };
        for (var handler : handlers) {
            for (var status : new HttpStatus[] {HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.TOO_MANY_REQUESTS}) {
                var failure = new ApiException(status, "ORIGINAL", "原拒绝原因", false);
                var request = new MockHttpServletRequest();
                var mapped = assertInstanceOf(ApiException.class, status == HttpStatus.TOO_MANY_REQUESTS
                        ? PublishPreDispatchRejection.rateLimit(request, handler, failure)
                        : PublishPreDispatchRejection.map(request, handler, failure));
                assertEquals("TASK_OPERATION_REJECTED_BEFORE_MUTATION", mapped.getCode());
                assertEquals(status, mapped.getStatus());
                assertEquals("原拒绝原因", mapped.getDetail());
                request.setDispatcherType(DispatcherType.ASYNC);
                assertSame(failure, PublishPreDispatchRejection.map(request, handler, failure));
                assertSame(failure, PublishPreDispatchRejection.rateLimit(request, handler, failure));
            }
            var unknown = new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "UNKNOWN", "未知结果", false);
            assertSame(unknown, PublishPreDispatchRejection.map(new MockHttpServletRequest(), handler, unknown));
        }
    }

    @Test void receiptAndDetailReadFailuresDoNotResolveOriginalWrites() throws Exception {
        var controller = new ConversationTaskController(null);
        var failure = new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "登录过期", false);
        for (var handler : new HandlerMethod[] {
                new HandlerMethod(controller, "receipt", String.class, String.class, String.class),
                new HandlerMethod(controller, "detail", String.class, String.class) })
            assertSame(failure, PublishPreDispatchRejection.map(new MockHttpServletRequest(), handler, failure));
    }
}
