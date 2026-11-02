plugins {
    `java-library`
    alias(libs.plugins.dependency.management)
}

// 官方技能市场包只用于独立发布校验，不进入 main resources 或生产 JAR。
tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processTestResources") {
    from(rootProject.layout.projectDirectory.dir("../official-skills")) {
        into("official-skills")
        exclude("**/__pycache__/**", "**/*.pyc")
    }
}

dependencyManagement {
    imports { mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}") }
}

dependencies {
    implementation(project(":agent-runtime-adapter-jdbc"))
    testImplementation("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.postgresql)
    testRuntimeOnly(project(":skillforge-migration"))
    testImplementation(testFixtures(project(":agent-runtime-adapter-testkit")))
    testImplementation(testFixtures(project(":agent-runtime-adapter-jdbc")))
    testImplementation(testFixtures(project(":skillforge-core-shared")))
    testImplementation(testFixtures(project(":skillforge-core-foundation")))
    implementation(project(":agent-runtime-adapter-mcp"))
    implementation(project(":agent-runtime-api"))
    implementation(project(":agent-runtime-core"))
    implementation(project(":agent-runtime-session"))
    implementation(project(":agent-runtime-spring-boot-starter"))
    implementation(project(":skillforge-common"))
    implementation(project(":skillforge-core-billing"))
    implementation(project(":skillforge-core-foundation"))
    implementation(project(":skillforge-core-shared"))
    implementation(project(":skillforge-execution-contract"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.mybatis.plus)
    implementation("com.baomidou:mybatis-plus-extension:${libs.versions.mybatisPlus.get()}")
    compileOnly(libs.swagger.annotations)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
}
