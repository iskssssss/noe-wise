package com.skillforge.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 灵知 网关服务启动入口。
 *
 * <p>基于 Spring Cloud Gateway 提供统一入口：将 {@code /api/**} 与健康检查路由到
 * 业务服务，并透传 SSE 与 WebSocket；可选接入 Nacos 服务发现与 Sentinel 限流。
 */
@SpringBootApplication
public class GatewayApplication {

    /**
     * 启动网关服务。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
