package com.skillforge.institution.controller;

import com.skillforge.core.chat.observation.SessionObservationAuthorizer;
import com.skillforge.core.chat.trajectory.TrajectoryListService;
import com.skillforge.core.chat.trajectory.TrajectoryProjectionService;
import com.skillforge.core.chat.trajectory.TrajectoryQuery;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.config.ExternalAuthPaths;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrajectoryControllerTest {
    @Test void trajectoryResourcesRequireMemberJwtAndAreOutsideEveryExternalOpenPath() {
        for (String path : java.util.List.of("/api/v1/chat/trajectories", "/api/v1/chat/trajectories/detail",
                "/api/v1/chat/trajectories/nodes", "/api/v1/chat/trajectories/content",
                "/api/v1/chat/sessions/trace", "/api/v1/chat/sessions/trace/content")) {
            assertFalse(ExternalAuthPaths.OPEN_PATHS.stream().anyMatch(pattern ->
                    org.springframework.web.util.pattern.PathPatternParser.defaultInstance.parse(pattern)
                            .matches(org.springframework.http.server.PathContainer.parsePath(path))));
        }
    }

    @Test void resourceTenantComesFromCurrentSubjectAndForeignParameterIsRejected() {
        var access = mock(FoundationAccess.class);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "7", "99"));
        var projection = mock(TrajectoryProjectionService.class);
        var controller = new TrajectoryController(new SessionObservationAuthorizer(access),
                mock(TrajectoryListService.class), projection);
        controller.detail(null, "session", "root");
        verify(projection).detail(any(), eq(new TrajectoryQuery.Resource(7L, "session", "root")));
        assertThrows(ApiException.class, () -> controller.content(8L, "session", "root", "ref"));
        verify(projection, never()).content(any(), any());
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true, null, "99"));
        assertThrows(ApiException.class, () -> controller.detail(null, "session", "root"));
    }
}
