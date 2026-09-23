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

val syncWebAssets by tasks.registering(Copy::class) {
    from(rootProject.projectDir.parentFile) {
        include("index.html", "styles.css", "app.js")
    }
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.named("preBuild") {
    dependsOn(syncWebAssets)
}

dependencies {
    implementation(files("libs/onnxruntime-android-1.30.0.aar"))
}
