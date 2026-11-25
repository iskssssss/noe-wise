package com.skillforge.institution.config;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.team.TeamDraftSaveController;
import com.skillforge.institution.team.TeamDraftSaveService;
import com.skillforge.institution.team.TeamPublishController;
import com.skillforge.institution.team.TeamPublishService;
import com.skillforge.institution.team.TeamSavedPublishController;
import jakarta.servlet.DispatcherType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;

class TeamPreDispatchRejectionTest {
    @Test void teamWritesIdentifyAuthenticationAndRateLimitRejection() throws Exception {
        var handlers = new HandlerMethod[] {
            new HandlerMethod(new TeamDraftSaveController(null), "save", TeamDraftSaveService.Command.class),
            new HandlerMethod(new TeamSavedPublishController(null), "publish", TeamPublishService.SavedCommand.class),
            new HandlerMethod(new TeamPublishController(null), "publish", TeamPublishService.Command.class)
        };
        for (var handler : handlers) {
            String code = handler.getBeanType() == TeamDraftSaveController.class
                ? TeamDraftSaveController.REJECTED : TeamPublishController.PRE_DISPATCH_REJECTED;
            for (var status : new HttpStatus[] {HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.TOO_MANY_REQUESTS}) {
                var failure = new ApiException(status, "ORIGINAL", "原拒绝原因", false);
                var request = new MockHttpServletRequest();
                var mapped = assertInstanceOf(ApiException.class, status == HttpStatus.TOO_MANY_REQUESTS
                    ? PublishPreDispatchRejection.rateLimit(request, handler, failure)
                    : PublishPreDispatchRejection.map(request, handler, failure));
                assertEquals(code, mapped.getCode());
                assertEquals(status, mapped.getStatus());
                assertEquals("原拒绝原因", mapped.getDetail());
                request.setDispatcherType(DispatcherType.ASYNC);
                assertSame(failure, PublishPreDispatchRejection.map(request, handler, failure));
                assertSame(failure, PublishPreDispatchRejection.rateLimit(request, handler, failure));
            }
            var failure = new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "UNKNOWN", "未知结果", false);
            assertSame(failure, PublishPreDispatchRejection.map(new MockHttpServletRequest(), handler, failure));
        }
    }

    @Test void receiptReadFailureDoesNotDetermineOriginalWriteOutcome() throws Exception {
        var request = new MockHttpServletRequest();
        var handler = new HandlerMethod(new TeamDraftSaveController(null), "result", UUID.class);
        var failure = new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "登录过期", false);
        assertSame(failure, PublishPreDispatchRejection.map(request, handler, failure));
        assertSame(failure, PublishPreDispatchRejection.map(request, new Object(), failure));
    }
}
