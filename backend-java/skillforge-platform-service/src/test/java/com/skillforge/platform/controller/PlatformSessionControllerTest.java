package com.skillforge.platform.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.foundation.constant.RoleConstants;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.observation.SessionObservationAuthorizer;
import com.skillforge.core.chat.observation.SessionObservationService;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

class PlatformSessionControllerTest {

    @Test
    void exposesSevenFixedGetPathsForPlatformAdmins() {
        assertTrue(Arrays.stream(PlatformSessionController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .allMatch(method -> method.getAnnotation(RequirePermission.class) != null
                        && method.getAnnotation(RequirePermission.class).value().equals("foundation.sessions.read")));
        assertArrayEquals(new String[] {"/api/v1/platform/sessions"},
                PlatformSessionController.class.getAnnotation(RequestMapping.class).value());
        Set<String> paths = Arrays.stream(PlatformSessionController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .map(method -> {
                    String[] value = method.getAnnotation(GetMapping.class).value();
                    return value.length == 0 ? "" : value[0];
                }).collect(Collectors.toSet());
        assertTrue(paths.containsAll(Set.of("", "/detail", "/rounds", "/messages",
                "/trace", "/trace/content", "/usage")));
        assertFalse(Arrays.stream(PlatformSessionController.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameterAnnotations()))
                .flatMap(Arrays::stream).anyMatch(PathVariable.class::isInstance));
    }

    @Test
    void rejectsInvalidTenantBeforeCallingObservationService() {
        PlatformSessionController controller = new PlatformSessionController(
                org.mockito.Mockito.mock(SessionObservationAuthorizer.class),
                org.mockito.Mockito.mock(SessionObservationService.class));

        assertThrows(ApiException.class, () -> controller.detail("other-tenant", "session-1"));
    }
}
