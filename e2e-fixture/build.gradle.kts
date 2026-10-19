plugins {
    id("com.android.application")
}

android {
    namespace = "com.speedvpn.e2e"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"
    }

    flavorDimensions += "role"
    productFlavors {
        create("blocked") {
            dimension = "role"
            applicationId = "com.speedvpn.fixture.blocked"
        }
        create("allowed") {
            dimension = "role"
            applicationId = "com.speedvpn.fixture.allowed"
        }
    }
}
