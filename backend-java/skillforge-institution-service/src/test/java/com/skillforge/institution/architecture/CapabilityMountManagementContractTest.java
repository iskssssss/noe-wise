package com.skillforge.institution.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillforge.core.agent.dto.request.CapabilityMountRequest;
import com.skillforge.institution.controller.AgentCapabilityMountController;
import com.skillforge.institution.controller.AgentController;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

/** 统一 CapabilityMount 管理面与旧 Agent bindings 管理面的架构契约。 */
class CapabilityMountManagementContractTest {

    @Test
    void onlyCapabilityMountControllerOwnsTheNewAgentManagementPath() {
        RequestMapping mapping = AgentCapabilityMountController.class
                .getAnnotation(RequestMapping.class);
        assertThat(Arrays.asList(mapping.value()))
                .containsExactly("/api/v1/agents/capability-mounts");

        assertThat(Arrays.stream(AgentCapabilityMountController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class)
                        || method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(DeleteMapping.class))
                .map(this::path)
                .toList())
                .containsExactlyInAnyOrder("GET ", "POST ", "PUT /update",
                        "DELETE /delete");
    }

    @Test
    void oldAgentBindingsManagementMappingsAreGone() {
        assertThat(Arrays.stream(AgentController.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getAnnotationsByType(RequestMapping.class)))
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .filter(path -> path.contains("bindings")))
                .isEmpty();
        assertThat(Arrays.stream(AgentController.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getAnnotationsByType(PostMapping.class)))
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .filter(path -> path.contains("bindings")))
                .isEmpty();
        assertThat(Arrays.stream(AgentController.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getAnnotationsByType(DeleteMapping.class)))
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .filter(path -> path.contains("bindings")))
                .isEmpty();
    }

    @Test
    void updateAndDeleteCarryIfMatchAndNoMapConfig() {
        for (Method method : AgentCapabilityMountController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(PutMapping.class)
                    || method.isAnnotationPresent(DeleteMapping.class)) {
                assertThat(Arrays.stream(method.getParameterAnnotations())
                        .flatMap(Arrays::stream)
                        .filter(RequestHeader.class::isInstance)
                        .map(RequestHeader.class::cast)
                        .map(RequestHeader::name))
                        .contains("If-Match");
            }
        }
        assertThat(Arrays.stream(CapabilityMountRequest.class.getRecordComponents())
                .map(component -> component.getType())
                .filter(Map.class::isAssignableFrom))
                .isEmpty();
    }

    private String path(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) {
            return "GET " + first(method.getAnnotation(GetMapping.class).value());
        }
        if (method.isAnnotationPresent(PostMapping.class)) {
            return "POST " + first(method.getAnnotation(PostMapping.class).value());
        }
        if (method.isAnnotationPresent(PutMapping.class)) {
            return "PUT " + first(method.getAnnotation(PutMapping.class).value());
        }
        return "DELETE " + first(method.getAnnotation(DeleteMapping.class).value());
    }

    private String first(String[] paths) {
        return paths.length == 0 ? "" : paths[0];
    }
}
