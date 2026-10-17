// Pure Kotlin: ROM decoding and rendering, shared by the Android app and the desktop tools.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

// ./gradlew :core:renderMap -Pargs="--map 60"
tasks.register<JavaExec>("renderMap") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.hgdaylight.tools.RenderMapKt")
    workingDir = rootProject.projectDir.parentFile
    args = (project.findProperty("args") as String? ?: "").split(" ").filter { it.isNotBlank() }
    jvmArgs("-Xmx2g")
}
