plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cseini.byd.karaoke"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cseini.byd.karaoke"
        minSdk = 26
        // targetSdk 28: Android 10 에서 Legacy External Storage 를 켜, 블랙박스가 독점 마운트한
        // SD카드 경로(/storage/XXXX-XXXX 등)에 직접 접근한다(일렉트로 앱과 동일한 전략).
        targetSdk = 28
        versionCode = 215
        versionName = "5.6"

        // 차량 헤드유닛은 ARM — x86 계열 네이티브 라이브러리(Vosk)는 제외해 APK 크기를 줄인다.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    // prod = 라이브 앱(노래방), test = 실험용 별도 앱(노래방테스트). applicationId 가 달라 함께 설치된다.
    flavorDimensions += "variant"
    productFlavors {
        create("prod") {
            dimension = "variant"
            manifestPlaceholders["appLabel"] = "노래방"
            // 라이브 앱 OTA 저장소
            buildConfigField("String", "OTA_REPO", "\"cseini/byd-karaoke\"")
        }
        create("lab") {
            dimension = "variant"
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
            manifestPlaceholders["appLabel"] = "노래방테스트"
            // 테스트 앱은 별도 저장소에서 OTA (본앱과 분리)
            buildConfigField("String", "OTA_REPO", "\"cseini/byd-karaoke-test\"")
        }
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
            // R8 축소는 -PenableR8=true 를 준 빌드에서만 켠다 — lab(노래방테스트) 채널로
            // 실차 검증(리플렉션 라이브러리 파손 여부)을 통과하면 기본값을 켜는 것으로 바꾼다.
            // prod 무음 업데이트에 무검증 R8 빌드가 나가는 것을 막기 위한 명시적 게이트.
            val r8 = (findProperty("enableR8") as String?) == "true"
            isMinifyEnabled = r8
            isShrinkResources = r8
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (otaKeystore.exists()) signingConfig = signingConfigs.getByName("ota")
        }
        debug {
            // 디버그는 축소하지 않는다(빌드 빠르게, 스택트레이스 그대로).
            isMinifyEnabled = false
            if (otaKeystore.exists()) signingConfig = signingConfigs.getByName("ota")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        // NewPipeExtractor 는 java.time/java.nio 등을 써서 minSdk 26 에선 디슈가링 필요.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        buildConfig = true
    }

    // 사이드로드(OTA) 전용 앱 — Play Store 의 targetSdk 최소치 검사는 해당 없음.
    lint {
        disable += "ExpiredTargetSdkVersion"
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
    implementation("dev.mobile:dadb:1.2.10")   // ★무음 업데이트(pm install)용
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("com.google.android.material:material:1.11.0")

    // 코루틴 (검색 네트워크)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 재생 방식 B — 유튜브 스트림 추출(NewPipe) → 네이티브 ExoPlayer 재생. 광고·로그인 없음.
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.media3:media3-ui:1.10.0")
    // 미디어 세션/브라우저 — 차량 런처(Kinex) 미디어 위젯에 앱을 노출.
    implementation("androidx.media3:media3-session:1.10.0")
    // 레거시 MediaBrowserServiceCompat — 구형 런처(Kinex)가 스캔하는 표준.
    implementation("androidx.media:media:1.7.0")

    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    // YouTube Data API v3 검색
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // 히스토리 타일 썸네일 로딩
    implementation("io.coil-kt:coil:2.6.0")

    // 녹음 공유 — 차량엔 공유 대상 앱이 없어, 앱이 직접 작은 HTTP 서버를 띄우고
    // QR(로컬 URL)을 보여주면 휴대폰이 같은 네트워크에서 브라우저로 내려받는다.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")

    // 단위 테스트 (순수 Kotlin DSP·채점 검증 — 차 없이 실행)
    testImplementation("junit:junit:4.13.2")
}
