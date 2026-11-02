pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "skillforge-backend-java"

include("agent-runtime-api")
include("agent-runtime-codec-spi")
include("agent-runtime-session-spi")
include("agent-runtime-json")
include("agent-runtime-core")
include("agent-runtime-session")
include("agent-runtime-memory")
include("agent-runtime-adapter-testkit")
include("agent-runtime-adapter-jdbc")
include("agent-runtime-adapter-sqlite")
include("agent-runtime-desktop-host")
include("agent-runtime-adapter-model")
include("agent-runtime-adapter-mcp")
include("agent-runtime-spring-boot-starter")
include("agent-runtime-bom")
include("skillforge-common")
include("skillforge-prompts")
include("skillforge-core-shared")
include("skillforge-core-agent")
include("skillforge-core-chat")
include("skillforge-core-workflow")
include("skillforge-core-knowledge")
include("skillforge-core-channel")
include("skillforge-migration")
include("skillforge-institution-service")
include("skillforge-platform-service")
include("skillforge-gateway")

include("skillforge-sandbox-service")

include("skillforge-system-agent-runtime")

include("skillforge-execution-contract")
include("skillforge-browser-service")

include("skillforge-core-foundation")

include("skillforge-core-model")

include("skillforge-core-billing")

include("skillforge-core-capability")
