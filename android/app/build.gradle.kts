plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.hgdaylight.app"
    compileSdk = 36
    defaultConfig {
        // Its own id: never dev.dwremake, which would install over DQ Daylight.
        applicationId = "dev.hgdaylight.ss"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        // Day to day: R8-optimized, signed with the debug key under the same id, so `adb install -r`
        // replaces a debug install and keeps the imported ROM (./gradlew :app:installFast).
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
}

/** The release build over the existing install (data kept), then an ahead-of-time compile. */
tasks.register("installFast") {
    group = "install"
    description = "Installs the release build with adb install -r (data kept) and AOT-compiles it."
    dependsOn("assembleRelease")
    val adb = android.sdkDirectory.resolve("platform-tools/adb").absolutePath
    val serial = (project.findProperty("serial") as String?)?.let { listOf("-s", it) } ?: emptyList()
    val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    doLast {
        fun adb(vararg args: String) {
            val p = ProcessBuilder(listOf(adb) + serial + args.toList()).inheritIO().start()
            check(p.waitFor() == 0) { "adb ${args.joinToString(" ")} failed" }
        }
        adb("install", "-r", apk.get().asFile.absolutePath)
        adb("shell", "cmd", "package", "compile", "-m", "speed", "-f", "dev.hgdaylight.ss")
    }
}
