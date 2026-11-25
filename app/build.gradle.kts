plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// 정식 서명 키: ~/.android/dagakey-release.properties 가 있을 때만 사용 (키·비밀번호는 저장소에 올리지 않음)
val relFile: File = File(System.getProperty("user.home"), ".android/dagakey-release.properties")
val rel: Map<String, String> = if (relFile.exists())
    relFile.readLines().filter { line: String -> line.contains("=") }
        .associate { line: String -> Pair(line.substringBefore("=").trim(), line.substringAfter("=").trim()) }
else emptyMap()

android {
    namespace = "com.glory.ev6lock"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.glory.ev6lock"
        minSdk = 29
        targetSdk = 35
        // 릴리스 태그(v0.1.1)와 versionName을 맞춰야 앱의 업데이트 확인이 정확합니다. versionCode = major*10000 + minor*100 + patch
        versionCode = (project.findProperty("verCode") as String?)?.toInt() ?: 116
        versionName = (project.findProperty("verName") as String?) ?: "0.1.16"
    }
    signingConfigs {
        if (rel.isNotEmpty()) create("release") {
            storeFile = file(rel.getValue("storeFile"))
            storePassword = rel.getValue("storePassword")
            keyAlias = rel.getValue("keyAlias")
            keyPassword = rel.getValue("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (rel.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
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
