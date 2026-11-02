plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.dependency.management)
}

val springBootVersion = libs.versions.springBoot.get()

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
    }
}

dependencies {
    implementation(project(":skillforge-execution-contract"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation("org.junit.platform:junit-platform-launcher")
}

tasks.bootJar {
    archiveFileName.set("skillforge-sandbox-service.jar")
}
