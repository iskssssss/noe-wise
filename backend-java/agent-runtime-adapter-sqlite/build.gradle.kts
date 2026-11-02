plugins { `java-library` }
dependencies {
    api(project(":agent-runtime-session-spi"))
    api("org.mybatis:mybatis:${libs.versions.runtimeMybatis.get()}")
    implementation("org.xerial:sqlite-jdbc:${libs.versions.runtimeSqlite.get()}")
    testImplementation(project(":agent-runtime-adapter-model"))
    testImplementation(testFixtures(project(":agent-runtime-adapter-testkit")))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
