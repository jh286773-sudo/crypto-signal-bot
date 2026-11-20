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
        versionCode = 10
        versionName = "0.3.0"
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies { testImplementation("junit:junit:4.13.2") }
kotlin {
    jvmToolchain(17)
}
