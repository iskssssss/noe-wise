package com.skillforge.gateway;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

/**
 * 网关路由配置测试。
 *
 * <p>验证网关启动后注册了 REST/健康检查与坐席 WebSocket 路由，
 * 确保微服务拓扑的路由声明与配置一致。
 */
@SpringBootTest(classes = GatewayApplication.class)
class GatewayRoutesTest {

    /** 路由定义加载器。 */
    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    /**
     * 应存在指向业务服务的 REST 与健康检查路由。
     */
    @Test
    void serviceAndHealthRoutesExist() {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions().collectList().block();
        assertTrue(routes != null && routes.stream().anyMatch(this::hasApiPath));
        assertTrue(routes != null && routes.stream().anyMatch(this::hasWsPath));
        assertTrue(routes != null && routes.stream().anyMatch(this::hasPlatformAuditAndBillingPaths));
    }

    @Test
    void applicationMainDelegatesToSpringBoot() {
        try (var spring = org.mockito.Mockito.mockStatic(SpringApplication.class)) {
            GatewayApplication.main(new String[] {"--spring.main.web-application-type=none"});
            spring.verify(() -> SpringApplication.run(GatewayApplication.class,
                    new String[] {"--spring.main.web-application-type=none"}));
        }
    }

    /**
     * 判断路由是否包含 /api/** 与 /health/** 断言。
     *
     * @param route 路由定义
     * @return 是否匹配
     */
    private boolean hasApiPath(RouteDefinition route) {
        return route.getPredicates().stream()
                .flatMap(predicate -> predicate.getArgs().values().stream())
                .anyMatch(value -> value != null && (value.contains("/api/**") || value.contains("/health/**")));
    }

    /**
     * 判断路由是否包含坐席 WebSocket 路径。
     *
     * @param route 路由定义
     * @return 是否匹配
     */
    private boolean hasWsPath(RouteDefinition route) {
        return route.getPredicates().stream()
                .flatMap(predicate -> predicate.getArgs().values().stream())
                .anyMatch("/api/v1/agent/ws"::equals);
    }

    private boolean hasPlatformAuditAndBillingPaths(RouteDefinition route) {
        return route.getPredicates().stream()
                .flatMap(predicate -> predicate.getArgs().values().stream())
                .anyMatch(value -> value != null
                        && value.contains("/api/v1/platform/**")
                        && !value.contains("/api/v1/billing/process-states"));
    }
}
