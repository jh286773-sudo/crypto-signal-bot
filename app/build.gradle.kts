plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.cryptoscalp.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.cryptoscalp.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.5"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin {
    jvmToolchain(17)
}
