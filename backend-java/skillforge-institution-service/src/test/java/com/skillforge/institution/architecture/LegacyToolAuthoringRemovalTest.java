package com.skillforge.institution.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 持久化防回流 Gate：旧租户 Tool 编写入口及其类型不得重新出现。 */
class LegacyToolAuthoringRemovalTest {

    private static final String CONTROLLER_PACKAGE = "com.skillforge.institution.controller";
    private static final String LEGACY_TOOLS_PATH = "/api/v1/tools";

    private static final List<String> REMOVED_TYPES = List.of(
            "com.skillforge.institution.controller.ToolController",
            "com.skillforge.institution.service.WorkflowToolSyncService",
            "com.skillforge.core.shared.dto.dto.request.CreateToolRequest",
            "com.skillforge.core.shared.dto.dto.request.UpdateToolRequest",
            "com.skillforge.core.shared.dto.dto.response.CreateToolResponse",
            "com.skillforge.core.shared.dto.dto.response.ToolItemResponse",
            "com.skillforge.core.shared.dto.dto.response.ToolListResponse");

    @Test
    void restControllersDoNotExposeLegacyToolMappings() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<String> controllerNames = scanner.findCandidateComponents(CONTROLLER_PACKAGE).stream()
                .map(component -> component.getBeanClassName())
                .collect(Collectors.toSet());
        assertThat(controllerNames).as("scanned institution REST controllers").isNotEmpty();

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        for (String controllerName : controllerNames) {
            Class<?> controller = Class.forName(controllerName, false, classLoader);
            assertNoLegacyPath(controller.getName(),
                    AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class));
            for (Method method : controller.getMethods()) {
                assertNoLegacyPath(controller.getName() + "#" + method.getName(),
                        AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class));
            }
        }
    }

    @Test
    void removedLegacyTypesAreNotLoadable() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        for (String removedType : REMOVED_TYPES) {
            assertThatThrownBy(() -> Class.forName(removedType, false, classLoader))
                    .as("removed type %s", removedType)
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    private static void assertNoLegacyPath(String owner, RequestMapping mapping) {
        if (mapping == null) {
            return;
        }
        List<String> paths = Stream.concat(Arrays.stream(mapping.path()), Arrays.stream(mapping.value()))
                .toList();
        assertThat(paths)
                .as("request mappings on %s", owner)
                .noneMatch(LegacyToolAuthoringRemovalTest::isLegacyToolsPath);
    }

    private static boolean isLegacyToolsPath(String path) {
        String normalized = path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1) : path;
        return normalized.equals(LEGACY_TOOLS_PATH)
                || normalized.startsWith(LEGACY_TOOLS_PATH + "/");
    }
}
