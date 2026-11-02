package com.skillforge.institution.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

/** 在实际 Controller 元数据上阻止路径参数、写入 query 与固定路由碰撞回归。 */
class FixedHttpRoutesTest {
    @Test
    void resourceIdentifiersUseQueryOnlyForGetsAndMappingsAreUnique() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<RequestMappingInfo> routes = new HashSet<>();
        for (var bean : scanner.findCandidateComponents("com.skillforge.institution")) {
            var controller = Class.forName(bean.getBeanClassName(), false, getClass().getClassLoader());
            var base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            var controllerMapping = mappingInfo(base);
            for (var method : controller.getDeclaredMethods()) {
                var mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                // Spring 的映射身份包含媒体、参数和请求头条件；同一路径可以明确区分 SSE 与 JSON。
                var combined = controllerMapping.combine(mappingInfo(mapping));
                boolean getOnly = combined.getMethodsCondition().getMethods().equals(Set.of(RequestMethod.GET));
                // 工程规范允许文件上传使用 multipart 表单字段；只识别明确含文件 part 的 POST 上传。
                boolean multipartUpload = combined.getMethodsCondition().getMethods().equals(Set.of(RequestMethod.POST))
                        && combined.getConsumesCondition().getConsumableMediaTypes().contains(MediaType.MULTIPART_FORM_DATA)
                        && Arrays.stream(method.getParameters()).anyMatch(parameter ->
                                MultipartFile.class.isAssignableFrom(parameter.getType())
                                        && parameter.isAnnotationPresent(RequestPart.class));
                for (var parameter : method.getParameters()) {
                    assertFalse(parameter.isAnnotationPresent(PathVariable.class), method.toString());
                    assertTrue(getOnly || multipartUpload || !parameter.isAnnotationPresent(RequestParam.class),
                            "普通写接口不能使用 RequestParam: " + method);
                }
                for (var path : combined.getPatternValues()) {
                    assertFalse(path.contains("{"), method.toString());
                }
                assertTrue(routes.add(combined), "固定映射冲突: " + combined);
            }
        }
        assertTrue(routes.size() > 100, "必须扫描真实机构 Controller，而非空集合");
    }

    private static RequestMappingInfo mappingInfo(RequestMapping mapping) {
        if (mapping == null) return RequestMappingInfo.paths("").build();
        var paths = mapping.path().length == 0 ? new String[]{""} : mapping.path();
        return RequestMappingInfo.paths(paths).methods(mapping.method())
                .params(mapping.params()).headers(mapping.headers())
                .consumes(mapping.consumes()).produces(mapping.produces()).build();
    }
}
