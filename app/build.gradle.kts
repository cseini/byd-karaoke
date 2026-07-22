plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cseini.byd.karaoke"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cseini.byd.karaoke"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "1.1"
    }

    // keystore/ota.keystore 가 있으면 그 키로 서명해 어느 PC/CI 에서 빌드해도
    // 같은 서명이 나오게 한다(서명이 다르면 OTA 덮어쓰기 설치가 거부됨).
    // 없으면 기본 디버그 키 — 이 경우 빌드 머신이 바뀌면 재설치가 필요하다.
    val otaKeystore = rootProject.file("keystore/ota.keystore")
    if (otaKeystore.exists()) {
        signingConfigs {
            create("ota") {
                // 비밀번호는 환경변수 또는 ~/.gradle/gradle.properties 의
                // otaKeystorePassword 로 주입 (기본값: android 디버그 관례값)
                val pw = System.getenv("OTA_KEYSTORE_PASSWORD")
                    ?: (findProperty("otaKeystorePassword") as String?)
                    ?: "android"
                storeFile = otaKeystore
                storePassword = pw
                keyAlias = "ota"
                keyPassword = pw
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (otaKeystore.exists()) signingConfig = signingConfigs.getByName("ota")
        }
        debug {
            isMinifyEnabled = false
            if (otaKeystore.exists()) signingConfig = signingConfigs.getByName("ota")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE.md"
            )
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("com.google.android.material:material:1.11.0")

    // 코루틴 (검색 네트워크)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 유튜브 IFrame 재생
    implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:13.0.0")

    // YouTube Data API v3 검색
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // 히스토리 타일 썸네일 로딩
    implementation("io.coil-kt:coil:2.6.0")

    // 단위 테스트 (순수 Kotlin DSP·채점 검증 — 차 없이 실행)
    testImplementation("junit:junit:4.13.2")
}
