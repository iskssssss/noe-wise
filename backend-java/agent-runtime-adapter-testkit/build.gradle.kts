plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    testFixturesApi(project(":agent-runtime-api"))
    testFixturesApi(project(":agent-runtime-session-spi"))
    testFixturesApi("org.junit.jupiter:junit-jupiter-api:${libs.versions.runtimeJUnit.get()}")
    testImplementation(project(":agent-runtime-codec-spi"))
    testImplementation(project(":agent-runtime-session-spi"))
    testImplementation(project(":agent-runtime-core"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
