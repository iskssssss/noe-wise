plugins {
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.dependency.management)
}

dependencyManagement {
    imports { mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}") }
}

dependencies {
    implementation("io.micrometer:micrometer-core")
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.security.crypto)
    implementation(libs.sa.token)
    implementation(libs.sa.token.jwt)
    implementation(libs.yitter.idgenerator)
    implementation(libs.postgresql)
    implementation("org.springframework.boot:spring-boot-starter-mail")
    testFixturesImplementation(libs.flyway.core)
    testFixturesRuntimeOnly(libs.flyway.postgresql)
    testFixturesImplementation(libs.spring.boot.starter.test)
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
    implementation(project(":agent-runtime-adapter-jdbc"))
    implementation(project(":agent-runtime-api"))
    implementation(project(":agent-runtime-spring-boot-starter"))
    implementation(project(":skillforge-common"))
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
