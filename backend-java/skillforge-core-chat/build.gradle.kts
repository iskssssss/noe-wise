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

// 工具契约测试只消费所需官方包样例，不把市场包加入生产资源。
tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processTestResources") {
    from(rootProject.layout.projectDirectory.dir("../official-skills")) {
        into("official-skills")
        include("presentation/examples/presentation-tool-call.json", "presentation/manifest.json")
    }
}

dependencies {
    testImplementation(project(":agent-runtime-core"))
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
    implementation(project(":agent-runtime-api"))
    implementation(project(":agent-runtime-session"))
    implementation(project(":agent-runtime-memory"))
    implementation(project(":agent-runtime-session-spi"))
    implementation(project(":skillforge-common"))
    implementation(project(":skillforge-core-agent"))
    implementation(project(":skillforge-core-billing"))
    implementation(project(":skillforge-core-capability"))
    implementation(project(":skillforge-core-foundation"))
    implementation(project(":skillforge-core-model"))
    implementation(project(":skillforge-core-knowledge"))
    implementation(project(":skillforge-core-shared"))
    implementation(project(":skillforge-execution-contract"))
    implementation(project(":skillforge-prompts"))


    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.postgresql)
    implementation(libs.mybatis.plus)
    // 版本目录访问器类已接近方法数上限，扩展包直接引用目录版本号声明
    implementation("com.baomidou:mybatis-plus-extension:${libs.versions.mybatisPlus.get()}")
    implementation(libs.spring.boot.starter.validation)
    compileOnly(libs.swagger.annotations)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation("org.junit.platform:junit-platform-launcher")
}
