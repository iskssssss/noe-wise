package com.skillforge.core.shared.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

/** 错误路径使用实际请求 JSON 属性，别名与下标不会被整串命名策略破坏。 */
class HostExceptionFieldPathTest {
    private final ObjectMapper json = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final HostExceptionHandler handler = new HostExceptionHandler(json) { };

    @Test
    void mapsAliasesNestedCollectionsArraysAndMapValues() {
        var request = new Request(List.of(new Binding("")), new Binding[] { new Binding("") },
                Map.of("primary", new Binding("")), new Binding(""));
        var binding = new BeanPropertyBindingResult(request, "request");
        for (String path : List.of("resourceBindings[0].resourceId", "arrayBindings[0].resourceId",
                "keyedBindings[primary].resourceId", "directBinding.resourceId")) {
            binding.addError(new FieldError("request", path, "请输入资源"));
        }
        var failure = mock(MethodArgumentNotValidException.class);
        when(failure.getBindingResult()).thenReturn(binding);

        var response = handler.handle(failure, new MockHttpServletResponse());

        assertEquals(Map.of("mounts[0].resource_key", "请输入资源", "array_bindings[0].resource_key", "请输入资源",
                "keyed_bindings[primary].resource_key", "请输入资源", "direct_binding.resource_key", "请输入资源"),
                response.getBody().fieldErrors());
    }

    @Test
    void honorsTheConfiguredNamingStrategyInsteadOfAssumingSnakeCase() {
        var kebab = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);
        var paths = new JsonFieldPaths(kebab);
        assertEquals("direct-binding.resource_key", paths.externalPath(
                new Request(List.of(), new Binding[0], Map.of(), new Binding("")), "directBinding.resourceId"));
        assertEquals("unknownField", paths.externalPath(null, "unknownField"));
        assertEquals("keyed-bindings[primary.region].resource_key", paths.externalPath(
                new Request(List.of(), new Binding[0], Map.of(), new Binding("")), "keyedBindings[primary.region].resourceId"));
        assertEquals("keyed-bindings[primary[region]].resource_key", paths.externalPath(
                new Request(List.of(), new Binding[0], Map.of(), new Binding("")), "keyedBindings[primary[region]].resourceId"));
    }

    private record Request(@JsonProperty("mounts") List<Binding> resourceBindings,
                           Binding[] arrayBindings, Map<String, Binding> keyedBindings, Binding directBinding) { }
    private record Binding(@JsonProperty("resource_key") String resourceId) { }
}
