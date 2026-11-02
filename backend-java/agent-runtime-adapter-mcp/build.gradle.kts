plugins { `java-library` }

// 仅使用冻结绑定与独立 HTTP/JSON 协议；具体存储只由装配层接入。
dependencies {
    api(project(":agent-runtime-api"))
    implementation("com.fasterxml.jackson.core:jackson-databind:${libs.versions.runtimeJackson.get()}")
    testImplementation(project(":agent-runtime-adapter-jdbc"))
    testImplementation(testFixtures(project(":agent-runtime-adapter-testkit")))
    testImplementation("org.postgresql:postgresql:${libs.versions.runtimePostgresql.get()}")
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
