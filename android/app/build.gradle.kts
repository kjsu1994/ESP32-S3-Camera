plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.kjsu1994.workbenchcam"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.kjsu1994.workbenchcam"
        minSdk = 29
        targetSdk = 36
        versionCode = 25
        versionName = "0.3.15"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
