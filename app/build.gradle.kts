plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.glory.ev6lock"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.glory.ev6lock"
        minSdk = 29
        targetSdk = 35
        // 릴리스 태그(v0.1.1)와 versionName을 맞춰야 앱의 업데이트 확인이 정확합니다. versionCode = major*10000 + minor*100 + patch
        versionCode = (project.findProperty("verCode") as String?)?.toInt() ?: 101
        versionName = (project.findProperty("verName") as String?) ?: "0.1.1"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
