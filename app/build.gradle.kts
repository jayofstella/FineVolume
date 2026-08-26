plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.finevolume"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.finevolume"
        minSdk = 30
        targetSdk = 28
        versionCode = 5
        versionName = "0.2.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
