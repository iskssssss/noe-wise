plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.dependency.management)
}
dependencyManagement { imports { mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}") } }
dependencies {
    implementation(project(":skillforge-execution-contract"))
    implementation(libs.spring.boot.starter.web)
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation(libs.spring.boot.starter.actuator)
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.bootJar { archiveFileName.set("skillforge-browser-service.jar") }
