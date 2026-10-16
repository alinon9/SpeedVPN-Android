plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


private const val HEV_NATIVE_PIN = "d9dca26c7ad0e494492244f0309e80ee583e739e"

// Prepare the native checkout as part of Gradle's task graph, not as an undocumented
// one-time manual step. This always re-checks the pinned source and reviewed patches.
val prepareNativeSources = tasks.register("prepareNativeSources") {
    group = "build setup"
    description = "Fetches the pinned HEV source and applies the reviewed TCP fixes."
    outputs.upToDateWhen { false }
    doLast {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val bootstrap = rootProject.file(if (isWindows) "setup.bat" else "setup.sh")
        if (!bootstrap.isFile) {
            throw GradleException("Native bootstrap script is missing: ${bootstrap.absolutePath}")
        }
        exec {
            workingDir(rootProject.projectDir)
            commandLine(
                if (isWindows) listOf("cmd", "/c", bootstrap.absolutePath)
                else listOf("bash", bootstrap.absolutePath),
            )
        }
    }
}

val verifyNativeSources = tasks.register("verifyNativeSources") {
    group = "verification"
    description = "Fails closed if the pinned HEV sources or TCP patch are missing, stale, or contaminated by UID POC code."
    dependsOn(prepareNativeSources)
    doLast {
        val nativeDir = file("src/main/jni/hev-socks5-tunnel")
        val tcpSource = nativeDir.resolve("src/hev-socks5-session-tcp.c")
        val lwipOptions = nativeDir.resolve("third-part/lwip/src/ports/include/lwipopts.h")
        val requiredFiles = listOf(
            file("src/main/jni/Android.mk"),
            file("src/main/jni/Application.mk"),
            nativeDir.resolve("Android.mk"),
            nativeDir.resolve("src/hev-jni.c"),
            nativeDir.resolve("src/hev-socks5-tunnel.c"),
            tcpSource,
            lwipOptions,
        )
        val missing = requiredFiles.filterNot { it.isFile || it.isDirectory }
        if (missing.isNotEmpty()) {
            throw GradleException("Native source preparation incomplete; missing: " + missing.joinToString { it.relativeTo(projectDir).path })
        }

        val headOutput = java.io.ByteArrayOutputStream()
        exec {
            workingDir(rootProject.projectDir)
            commandLine("git", "-C", nativeDir.absolutePath, "rev-parse", "HEAD")
            standardOutput = headOutput
        }
        val actualPin = headOutput.toString(Charsets.UTF_8.name()).trim()
        if (actualPin != HEV_NATIVE_PIN) {
            throw GradleException("Unexpected hev-socks5-tunnel revision: $actualPin (expected $HEV_NATIVE_PIN)")
        }

        val forbiddenMarkers = listOf(
            "SPEEDVPN_UID_POC_BEGIN",
            "native_set_uid_poc_callback",
            "TProxySetUidPocCallback",
            "uid_poc_",
        )
        val contaminated = nativeDir.resolve("src").walkTopDown()
            .filter { it.isFile && (it.extension == "c" || it.extension == "h") }
            .flatMap { source -> forbiddenMarkers.filter { marker -> source.readText().contains(marker) }.map { marker -> "${source.relativeTo(projectDir).path}: $marker" } }
            .toList()
        if (contaminated.isNotEmpty()) {
            throw GradleException("Unsafe UID POC code detected in production native sources: " + contaminated.joinToString())
        }

        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val python = if (isWindows) "python" else "python3"
        exec {
            workingDir(rootProject.projectDir)
            commandLine(
                python,
                rootProject.file("scripts/e2e/patch_hev_tcp_buffers.py").absolutePath,
                lwipOptions.absolutePath,
                "--tcp-source", tcpSource.absolutePath,
                "--check",
            )
        }
    }
}

// Every real native-build task must pass through the source and patch gate. Clean-only
// tasks are excluded because they do not compile or package native code.
tasks.configureEach {
    if (name.startsWith("externalNativeBuild") && !name.startsWith("externalNativeBuildClean")) {
        dependsOn(verifyNativeSources)
    }
}

val configuredSpeedTestBaseUrl = providers.gradleProperty("speedTestBaseUrl")
    .orNull
    ?.trim()
    ?.trimEnd('/')
    .orEmpty()

if (configuredSpeedTestBaseUrl.isNotEmpty() &&
    configuredSpeedTestBaseUrl != "http://10.0.2.2:18765") {
    throw GradleException("speedTestBaseUrl is restricted to the CI local fixture endpoint")
}

android {
    namespace = "com.speedvpn.app"
    compileSdk = 36
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.speedvpn.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 30
        versionName = "1.1.1"

        // Public values only (same ones the website uses). No secrets here.
        buildConfigField("String", "API_BASE", "\"${System.getenv("SPEEDVPN_API_BASE") ?: "https://read-fix-build-magic.lovable.app/api/public/vpn"}\"")
        buildConfigField("String", "AUTH_URL", "\"${System.getenv("SPEEDVPN_AUTH_URL") ?: "https://abzykxcudjnceqdqurrc.supabase.co/auth/v1"}\"")
        buildConfigField("String", "AUTH_KEY", "\"${System.getenv("SPEEDVPN_AUTH_KEY") ?: "sb_publishable_SoW7idGma-3aqlx-pIMrTw_sK3nex2h"}\"")

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
        debug {
            // Only the CI Debug APK can target the runner-local deterministic fixture.
            buildConfigField("String", "SPEED_TEST_BASE_URL", "\"$configuredSpeedTestBaseUrl\"")
        }
        release {
            // Release builds always keep the public production speed-test service.
            buildConfigField("String", "SPEED_TEST_BASE_URL", "\"\"")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // JNI and reflection entry points are explicitly preserved by app/proguard-rules.pro.
            // Release signing is injected only by CI env vars.
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
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.work:work-testing:2.9.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
