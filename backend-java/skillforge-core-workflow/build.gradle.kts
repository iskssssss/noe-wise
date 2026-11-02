plugins {
    `java-library`
    alias(libs.plugins.dependency.management)
}

val springBootVersion = libs.versions.springBoot.get()
val springCloudVersion = libs.versions.springCloud.get()
val springCloudAlibabaVersion = libs.versions.springCloudAlibaba.get()

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:$springCloudVersion")
        mavenBom("com.alibaba.cloud:spring-cloud-alibaba-dependencies:$springCloudAlibabaVersion")
    }
}

dependencies {
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.postgresql)
    testImplementation(testFixtures(project(":agent-runtime-adapter-jdbc")))
    testImplementation(testFixtures(project(":skillforge-core-shared")))
    testImplementation(testFixtures(project(":skillforge-core-foundation")))
    implementation(project(":agent-runtime-adapter-jdbc"))
    implementation(project(":agent-runtime-api"))
    implementation(project(":agent-runtime-session"))
    implementation(project(":agent-runtime-session-spi"))
    implementation(project(":skillforge-common"))
    implementation(project(":skillforge-core-capability"))
    implementation(project(":skillforge-core-foundation"))
    implementation(project(":skillforge-core-shared"))
    implementation(project(":skillforge-execution-contract"))




    implementation(libs.spring.boot.starter.web)
    implementation(libs.mybatis.plus)
    // 版本目录访问器类已接近方法数上限，扩展包直接引用目录版本号声明
    implementation("com.baomidou:mybatis-plus-extension:${libs.versions.mybatisPlus.get()}")
    implementation(libs.spring.boot.starter.validation)
    compileOnly(libs.swagger.annotations)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testImplementation("org.mockito:mockito-core")
    testImplementation(project(":agent-runtime-core"))
    testImplementation(testFixtures(project(":agent-runtime-adapter-testkit")))
    testImplementation("org.postgresql:postgresql:${libs.versions.runtimePostgresql.get()}")
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation("org.flywaydb:flyway-core:${libs.versions.runtimeFlyway.get()}")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql:${libs.versions.runtimeFlyway.get()}")
    testRuntimeOnly(project(":skillforge-migration"))
    testImplementation("org.junit.platform:junit-platform-launcher")
}
