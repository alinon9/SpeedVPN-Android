plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.speedvpn.app"
    compileSdk = 35
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.speedvpn.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 29
        versionName = "1.1.0"

        // Public values only (same ones the website uses). No secrets here.
        buildConfigField("String", "API_BASE", "\"https://read-fix-build-magic.lovable.app/api/public/vpn\"")
        buildConfigField("String", "AUTH_URL", "\"https://abzykxcudjnceqdqurrc.supabase.co/auth/v1\"")
        buildConfigField("String", "AUTH_KEY", "\"sb_publishable_SoW7idGma-3aqlx-pIMrTw_sK3nex2h\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Native tun -> SOCKS5 engine (hev-socks5-tunnel). Run setup.sh / setup.bat once first.
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("RELEASE_KEYSTORE_PATH")
            val storePwd = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            val keyAliasEnv = System.getenv("RELEASE_KEY_ALIAS")
            val keyPwd = System.getenv("RELEASE_KEY_PASSWORD")
            if (!storeFilePath.isNullOrBlank() && !storePwd.isNullOrBlank() &&
                !keyAliasEnv.isNullOrBlank() && !keyPwd.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                storePassword = storePwd
                keyAlias = keyAliasEnv
                keyPassword = keyPwd
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // R8 stays disabled until a real release build verifies Compose + JNI
            // reflection/entry points. Release signing is injected only by CI env vars.
            val hasReleaseSigning = listOf(
                "RELEASE_KEYSTORE_PATH",
                "RELEASE_KEYSTORE_PASSWORD",
                "RELEASE_KEY_ALIAS",
                "RELEASE_KEY_PASSWORD",
            ).all { !System.getenv(it).isNullOrBlank() }
            signingConfig = signingConfigs.getByName("release").takeIf { hasReleaseSigning }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
