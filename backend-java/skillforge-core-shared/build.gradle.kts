plugins {
    `java-library`
    `java-test-fixtures`
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
    testImplementation(libs.postgresql)
    implementation(project(":skillforge-common"))

    testFixturesImplementation(libs.spring.boot.starter.test)
    implementation(libs.spring.boot.starter.web)
    implementation("io.micrometer:micrometer-core")
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.data.redis)

    implementation(libs.yitter.idgenerator)
    implementation(libs.mybatis.plus)
    // 版本目录访问器类已接近方法数上限，扩展包直接引用目录版本号声明
    implementation("com.baomidou:mybatis-plus-extension:${libs.versions.mybatisPlus.get()}")
    // 分页插件自 3.5.9 起拆分到 jsqlparser 模块，需显式引入
    implementation("com.baomidou:mybatis-plus-jsqlparser:${libs.versions.mybatisPlus.get()}")
    implementation(libs.sa.token)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.postgresql)
    compileOnly(libs.swagger.annotations)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    // JUnit Platform 启动器：缺省会报 TestExecutor 无法启动
    testImplementation("org.junit.platform:junit-platform-launcher")
    // Mockito（经 Spring Boot BOM 版本管理）：服务层单测 mock Mapper / LLM
    testImplementation(libs.spring.boot.starter.test)
}
