plugins {
    `java-library`
}

// 完整系统正文由标准资源处理直接打包；Runtime 正文归 Session 模块维护。
tasks.processResources {
    includeEmptyDirs = false
    // Copy 不清除已删除资源；同步清理本任务输出，防止旧正文残留在最终 JAR。
    doFirst {
        val destination = destinationDir.canonicalFile
        check(destination.toPath().startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath()) &&
                destination != layout.buildDirectory.get().asFile.canonicalFile) { "Resource output must stay inside build/" }
        delete(destination)
    }
}

dependencies {
    api(project(":agent-runtime-session-spi"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
