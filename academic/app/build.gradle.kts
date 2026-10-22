plugins {
    id("com.android.application")
}
android {
    namespace = "com.auditlab.academic"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.auditlab.academic"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-i01"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":domain"))
}
