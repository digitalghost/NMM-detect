plugins {
    id("com.android.application")
}

android {
    namespace = "com.digitalghost.nmmprobe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.digitalghost.nmmprobe"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
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
}

dependencies {
    implementation(files("libs/onnxruntime-android-1.30.0.aar"))
}
