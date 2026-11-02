plugins {
    `java-library`
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-core:${libs.versions.runtimeJackson.get()}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 基础 JSON 只能依赖选定的语法库，不反向引入 Runtime 或宿主构件。
val verifyJsonDependencies by tasks.registering {
    group = "verification"
    doLast {
        val expected = "com.fasterxml.jackson.core:jackson-core:${libs.versions.runtimeJackson.get()}"
        listOf(configurations.compileClasspath.get(), configurations.runtimeClasspath.get()).forEach { classpath ->
            val artifacts = classpath.resolvedConfiguration.resolvedArtifacts
            check(artifacts.size == 1 && artifacts.single().moduleVersion.id.toString() == expected &&
                classpath.files.map { it.canonicalFile }.toSet() == setOf(artifacts.single().file.canonicalFile)) {
                "agent-runtime-json must resolve only $expected"
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyJsonDependencies)
}
