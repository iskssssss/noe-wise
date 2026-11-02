plugins {
    `java-library`
    alias(libs.plugins.dependency.management)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
    }
}

dependencies {
    api(project(":agent-runtime-api"))
    api(libs.spring.boot.starter.jdbc)
    implementation(project(":agent-runtime-core"))
    // 公开 Host.drive / Worker.driveProgramChild 返回 ExecutionCoordinator.Outcome，须传递给消费方编译。
    api(project(":agent-runtime-session"))
    implementation(project(":agent-runtime-memory"))
    implementation(project(":agent-runtime-adapter-jdbc"))
    implementation(project(":agent-runtime-adapter-model"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.flyway.core)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val verifyStarterRuntimeBoundary by tasks.registering {
    group = "verification"
    doLast {
        val allowedRuntimeProjects = setOf(
            "agent-runtime-api",
            "agent-runtime-json",
            "agent-runtime-codec-spi",
            "agent-runtime-session-spi",
            "agent-runtime-core",
            "agent-runtime-session",
            "agent-runtime-memory",
            "agent-runtime-adapter-jdbc",
            "agent-runtime-adapter-model",
        )
        val actualRuntimeProjects = configurations.runtimeClasspath.get()
            .resolvedConfiguration.resolvedArtifacts
            .filter { it.moduleVersion.id.group == project.group.toString() }
            .map { it.name }
            .toSet()
        check(actualRuntimeProjects == allowedRuntimeProjects) {
            "Starter production runtime contains unapproved SkillForge modules: " +
                (actualRuntimeProjects - allowedRuntimeProjects)
        }
    }
}

tasks.named("check") {
    dependsOn(verifyStarterRuntimeBoundary)
}
