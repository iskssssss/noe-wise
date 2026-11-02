plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies()
}

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    constraints {
        api(project(":agent-runtime-api"))
        api(project(":agent-runtime-codec-spi"))
        api(project(":agent-runtime-session-spi"))
        api(project(":agent-runtime-json"))
        api(project(":agent-runtime-core"))
        api(project(":agent-runtime-session"))
        api(project(":agent-runtime-memory"))
        api(project(":agent-runtime-adapter-testkit"))
        api(project(":agent-runtime-adapter-jdbc"))
        api(project(":agent-runtime-adapter-model"))
        api(project(":agent-runtime-adapter-mcp"))
        api(project(":agent-runtime-spring-boot-starter"))
        api(project(":agent-runtime-adapter-sqlite"))
    }
}
