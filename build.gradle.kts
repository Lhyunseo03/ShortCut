// Top-level build file where you can add configuration options common to all sub-projects/modules.
// 프로젝트 전체에 적용되는 플러그인 설정
// 각 모듈에서 필요할 때 활성화할 수 있도록 apply false로 선언
plugins {
    // Android 앱 모듈 플러그인 (app/build.gradle.kts에서 활성화)
    alias(libs.plugins.android.application) apply false

    // Jetpack Compose 컴파일러 플러그인 (app/build.gradle.kts에서 활성화)
    alias(libs.plugins.kotlin.compose) apply false

    // KSP (Kotlin Symbol Processing) — Room DB 어노테이션 처리에 사용
    // kapt 대신 KSP를 사용하는 이유: 최신 Kotlin과 호환되고 빌드 속도가 더 빠름
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}