package com.skillforge.sandbox;

import com.skillforge.sandbox.config.SandboxProperties;
import com.skillforge.sandbox.workspace.WorkspaceCloudProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({SandboxProperties.class, WorkspaceCloudProperties.class})
public class SandboxServiceApplication {

    /** 工作区与内部控制共用 UTC 时钟，测试从正式注入入口替换。 */
    @org.springframework.context.annotation.Bean
    java.time.Clock workspaceClock() {
        return java.time.Clock.systemUTC();
    }

    public static void main(String[] args) {
        SpringApplication.run(SandboxServiceApplication.class, args);
    }
}
