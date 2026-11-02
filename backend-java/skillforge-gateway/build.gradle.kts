plugins {
    java
    alias(libs.plugins.spring.boot)
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
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation(libs.spring.cloud.starter.gateway)
    implementation(libs.nacos.discovery)
    implementation(libs.sentinel)

    testImplementation(libs.spring.boot.starter.test)
}

tasks.bootJar {
    archiveFileName.set("skillforge-gateway.jar")
}
