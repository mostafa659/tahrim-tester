import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// المفتاح: من متغير بيئة (GitHub Secrets) أو من local.properties (محلياً). لا يُكتب في الكود أبداً.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val quranLabKey: String = System.getenv("QURANLAB_KEY")?.takeIf { it.isNotBlank() }
    ?: local.getProperty("QURANLAB_KEY", "")

android {
    namespace = "com.example.tahrimtester"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.tahrimtester"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "QURANLAB_KEY", "\"$quranLabKey\"")
    }

    // مفتاح توقيع ثابت: كل بناء يُثبَّت فوق السابق ويحتفظ بالنموذج المنزَّل بدل إعادة التنزيل.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // نسخة غير قابلة للتنقيح: أسرع على الهاتف (تُوقَّع بنفس المفتاح فتُثبَّت فوق النسخة التجريبية).
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation("ai.quranlab:tajweedformer:4.0.1")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
