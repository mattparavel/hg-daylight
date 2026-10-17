// Desktop tools over the core (PNG renders for reviewing the look without a device).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
}

// ./gradlew :tools:renderMap -Pargs="--header 60"
tasks.register<JavaExec>("renderMap") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.hgdaylight.tools.RenderMapKt")
    workingDir = rootProject.projectDir.parentFile
    args = (project.findProperty("args") as String? ?: "").split(" ").filter { it.isNotBlank() }
    jvmArgs("-Xmx2g")
}

tasks.register<JavaExec>("dumpSprite") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.hgdaylight.tools.DumpSpriteKt")
    workingDir = rootProject.projectDir.parentFile
}
