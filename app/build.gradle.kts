plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.doard.screentranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.doard.screentranslator"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"

        // Android 14 以降の実機は arm64 のみ。ML Kit のネイティブライブラリが大きいので他 ABI は含めない
        // エミュレータ確認用: gradlew assembleDebug -Pabis=x86_64
        val abis = (project.findProperty("abis") as String?)?.split(",") ?: listOf("arm64-v8a")
        ndk { abiFilters += abis }
    }

    packaging {
        // ネイティブライブラリを圧縮して格納し、APK のダウンロードサイズを抑える
        jniLibs.useLegacyPackaging = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // ML Kit: OCR (モデル同梱), 言語判定, オンデバイス翻訳
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")

    testImplementation("junit:junit:4.13.2")
}
