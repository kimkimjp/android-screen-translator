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
        versionCode = 4
        versionName = "1.3"

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


    // 依存は 2026-09-19 時点で意図的に据え置き。新しくすると次が起きることを実測済み:
    //   - AndroidX の新しい版は AGP 8.9.1〜9.1.0 と compileSdk 36〜37 を要求する（AAR metadata で弾かれる）
    //   - coroutines 1.10 以降は Kotlin 2.2 でビルドされていて Kotlin 2.0.21 では読めない
    //   - AGP 8.7.3 で通る範囲（core-ktx 1.16.0 / activity-ktx 1.10.1 / lifecycle 2.9.4）まで上げると、
    //     APK が 20.3MB → 24.2MB に増え、さらに lifecycle 同梱の lint 検出器が AGP 8.7.3 の lint を
    //     クラッシュさせる（NonNullableMutableLiveDataDetector）。機能面の利得は無い。
    // 上げるなら AGP と compileSdk の更新（Android 16 対応）とセットで行うこと。

    // ML Kit: OCR (モデル同梱), 言語判定, オンデバイス翻訳
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")

    testImplementation("junit:junit:4.13.2")
}
