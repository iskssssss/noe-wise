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
    implementation(project(":agent-runtime-api"))
    implementation(project(":skillforge-core-billing"))
    implementation(project(":skillforge-core-capability"))
    implementation(project(":skillforge-core-foundation"))
    implementation(project(":skillforge-core-model"))
    implementation(project(":skillforge-prompts"))
    implementation(project(":skillforge-common"))
    implementation(project(":skillforge-core-shared"))
    implementation(project(":skillforge-core-agent"))
    implementation(project(":agent-runtime-spring-boot-starter"))
    implementation(project(":agent-runtime-session-spi"))
    implementation(project(":agent-runtime-adapter-jdbc"))
    implementation(project(":agent-runtime-adapter-model"))
    implementation(project(":agent-runtime-codec-spi"))
    implementation(project(":agent-runtime-memory"))
    implementation(project(":agent-runtime-session"))
    implementation(project(":agent-runtime-core"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation(libs.mybatis.plus)
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
