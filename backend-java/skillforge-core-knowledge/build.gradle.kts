plugins {
    `java-library`
    alias(libs.plugins.dependency.management)
}

tasks.named<org.gradle.api.tasks.testing.Test>("test") {
    inputs.file(rootProject.file("../tools/knowledge_evaluation/suite.json"))
        .withPropertyName("knowledgeEvaluationCorpus").withPathSensitivity(PathSensitivity.RELATIVE)
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
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.postgresql)
    testRuntimeOnly(project(":skillforge-migration"))
    testImplementation(testFixtures(project(":agent-runtime-adapter-testkit")))
    testImplementation(testFixtures(project(":agent-runtime-adapter-jdbc")))
    testImplementation(testFixtures(project(":skillforge-core-foundation")))
    implementation(project(":agent-runtime-adapter-jdbc"))
    implementation(project(":agent-runtime-adapter-model"))
    implementation(project(":agent-runtime-api"))
    implementation(project(":agent-runtime-codec-spi"))
    testImplementation(project(":agent-runtime-core"))
    implementation(project(":agent-runtime-spring-boot-starter"))
    implementation(project(":skillforge-common"))
    implementation(project(":skillforge-core-billing"))
    implementation(project(":skillforge-core-capability"))
    implementation(project(":skillforge-core-foundation"))
    implementation(project(":skillforge-core-model"))
    implementation(project(":skillforge-core-shared"))
    testImplementation(testFixtures(project(":skillforge-core-shared")))


    implementation(libs.spring.boot.starter.web)
    implementation(libs.mybatis.plus)
    // 版本目录访问器类已接近方法数上限，扩展包直接引用目录版本号声明
    implementation("com.baomidou:mybatis-plus-extension:${libs.versions.mybatisPlus.get()}")
    implementation(libs.postgresql)
    implementation(libs.tika.core)
    implementation(libs.tika.parsers.standard)
    implementation(libs.spring.boot.starter.validation)
    compileOnly(libs.swagger.annotations)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    // JUnit Platform 启动器：缺省会报 TestExecutor 无法启动
    testImplementation("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.spring.boot.starter.test)
}
