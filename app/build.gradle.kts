plugins {
    // Android 앱 모듈 플러그인
    alias(libs.plugins.android.application)

    // Jetpack Compose 컴파일러 플러그인
    alias(libs.plugins.kotlin.compose)

    // KSP — Room DB 어노테이션 처리기
    // kapt 대신 KSP 사용: 최신 Kotlin과 호환되고 빌드 속도가 더 빠름
    id("com.google.devtools.ksp")

    // Google Services 플러그인 — Firebase 연동에 필요
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.short_cut"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.short_cut"
        minSdk = 30
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    // Socket.IO — 서버와 Android 간 실시간 통신 라이브러리
    // Android에 기본 내장이 아니라서 별도로 추가 필요
    // Sync Now 누르면 Gradle이 인터넷에서 자동으로 받아옴
    implementation("io.socket:socket.io-client:2.1.0")

    // Room DB — 로컬 데이터베이스 (스크롤 기록, limit 설정 저장용)
    implementation("androidx.room:room-runtime:2.7.1")  // Room 핵심 라이브러리
    implementation("androidx.room:room-ktx:2.7.1")      // Kotlin 코루틴 지원 (suspend 함수 사용 가능)
    ksp("androidx.room:room-compiler:2.7.1")            // DAO 코드 자동 생성 (kapt → ksp로 변경)

    // OkHttp — REST API HTTP 요청용 라이브러리 (POST /violations 전송에 사용)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Firebase BoM — Firebase 라이브러리 버전 통합 관리
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))

    // Firebase Auth — 구글 로그인 인증
    implementation("com.google.firebase:firebase-auth-ktx")

    // Google Sign-In — 구글 계정 선택 UI 제공
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Firebase Auth tasks — getIdToken await() 사용을 위해 필요
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Material Icons Extended — NavigationBar 아이콘(Home, BarChart, Groups 등)에 사용
    implementation("androidx.compose.material:material-icons-extended")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}