plugins { application }

dependencies {
    implementation(project(":agent-runtime-api"))
    implementation(project(":agent-runtime-core"))
    implementation(project(":agent-runtime-codec-spi"))
    implementation(project(":agent-runtime-session"))
    implementation(project(":agent-runtime-session-spi"))
    implementation(project(":agent-runtime-adapter-model"))
    implementation(project(":agent-runtime-adapter-sqlite"))
    implementation("com.fasterxml.jackson.core:jackson-databind:${libs.versions.runtimeJackson.get()}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application { mainClass.set("com.skillforge.runtime.desktop.DesktopHostMain") }
