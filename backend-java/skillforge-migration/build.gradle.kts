plugins {
    `java-library`
}

// Flyway SQL/Java resources are packaged here and consumed by the services.
dependencies {
    implementation("org.flywaydb:flyway-core:${libs.versions.runtimeFlyway.get()}")

    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly("org.postgresql:postgresql:${libs.versions.runtimePostgresql.get()}")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql:${libs.versions.runtimeFlyway.get()}")
    // 完整迁移链使用 JUnit Platform，显式提供启动器避免 Gradle worker 缺类。
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
