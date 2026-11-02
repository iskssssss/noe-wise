plugins {
    `java-library`
    alias(libs.plugins.dependency.management)
}

dependencyManagement {
    imports { mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}") }
}

dependencies {
    implementation("com.knuddels:jtokkit:${libs.versions.jtokkit.get()}")
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
    implementation(project(":agent-runtime-adapter-model"))
    implementation(project(":agent-runtime-api"))
    implementation(project(":skillforge-common"))
    implementation(project(":skillforge-core-billing"))
    implementation(project(":skillforge-core-foundation"))
    implementation(project(":skillforge-core-shared"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.mybatis.plus)
    implementation("com.baomidou:mybatis-plus-extension:${libs.versions.mybatisPlus.get()}")
    compileOnly(libs.swagger.annotations)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
}

tasks.processTestResources {
    from(rootProject.layout.projectDirectory.dir("../deploy/pg-init/roles")) {
        include("runtime-grants.sql")
        into("test-deployment")
    }
}
