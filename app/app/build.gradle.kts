import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// 외부 데이터 키 (커밋 제외). 없으면 빈 문자열 → 해당 기능은 조용히 꺼진다. 예시: keys.properties.example
val keyProps = Properties().apply {
    val f = rootProject.file("keys.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "kr.lastmile.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.lastmile.app"
        minSdk = 28
        ndk { abiFilters += listOf("arm64-v8a") }   // Pleos 실차·에뮬레이터 모두 arm64 (APK 50 MB 이하 유지)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DATA_GO_KR_KEY", "\"${keyProps.getProperty("DATA_GO_KR_KEY", "")}\"")   // 공공데이터포털(한국환경공단 충전소) 일반 인증키
    }
    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true; enableV2Signing = true; enableV3Signing = true
            }
        }
    }
    buildFeatures { viewBinding = true; buildConfig = true }
    flavorDimensions += "source"
    productFlavors {
        create("mock") { dimension = "source"; applicationIdSuffix = ".mock"; buildConfigField("boolean", "USE_MOCK", "true") }
        create("sdk") { dimension = "source"; buildConfigField("boolean", "USE_MOCK", "false") }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    useLibrary("android.car")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")   // 스프링 모션 (Apple Fluid Interfaces 식 response/damping)   // 실제 지도 (벡터 타일: OpenFreeMap, 키 불필요)

    // Pleos Connect SDK (2026-10-19 release)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
}
