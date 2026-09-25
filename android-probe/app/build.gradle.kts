import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.security.MessageDigest

plugins {
    id("com.android.application")
}

val bundledModelNames = listOf(
    "sam3-miniature-1008.onnx",
    "sam3-miniature-1008.onnx.data",
    "da3-large-1008x756.onnx",
    "da3-large-1008x756.onnx.data",
)
val modelSourceDirectory = rootProject.projectDir.resolve("models")
val generatedModelAssets = layout.buildDirectory.dir("generated/bundledModelAssets")

android {
    namespace = "com.digitalghost.nmmprobe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.digitalghost.nmmprobe"
        minSdk = 28
        targetSdk = 34
        versionCode = 5
        versionName = "0.3.5"
        testInstrumentationRunner = "com.digitalghost.nmmprobe.RenderInstrumentation"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // ONNX Runtime needs the external-data files byte-for-byte. Keeping
        // them uncompressed also lets the first-run installer stream them.
        noCompress += listOf("onnx", "data")
    }
}

val syncWebAssets by tasks.registering(Copy::class) {
    from(rootProject.projectDir.parentFile) {
        include("index.html", "styles.css", "app.js")
    }
    into(layout.projectDirectory.dir("src/main/assets"))
}

val prepareBundledModels by tasks.registering {
    val sourceFiles = bundledModelNames.map(modelSourceDirectory::resolve)
    inputs.files(sourceFiles)
    outputs.dir(generatedModelAssets)

    doLast {
        val outputRoot = generatedModelAssets.get().asFile
        val outputDirectory = outputRoot.resolve("models")
        outputRoot.deleteRecursively()
        check(outputDirectory.mkdirs()) { "Unable to create generated model asset directory" }

        val manifest = StringBuilder()
        sourceFiles.zip(bundledModelNames).forEach { (source, name) ->
            check(source.isFile) {
                "Missing Android inference model: ${source.absolutePath}. " +
                    "Run the model export commands documented in android-probe/README.md."
            }
            val target = outputDirectory.resolve(name)
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(source.inputStream(), 4 * 1024 * 1024).use { input ->
                BufferedOutputStream(target.outputStream(), 4 * 1024 * 1024).use { output ->
                    val buffer = ByteArray(4 * 1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
            val sha256 = digest.digest().joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
            manifest.append(name).append('\t')
                .append(target.length()).append('\t')
                .append(sha256).append('\n')
        }
        outputDirectory.resolve("manifest.tsv").writeText(manifest.toString())
    }
}

android.sourceSets.getByName("main").assets.srcDir(generatedModelAssets)

val syncRenderTest by tasks.registering(Copy::class) {
    from(rootProject.projectDir.parentFile.resolve("dist/heif-tests")) {
        include("miniature.HEIF", "*.heic", "*.heif", "magic.bin", "import-cases.json")
    }
    from(rootProject.projectDir.parentFile.resolve("tests/android-render-regression.js"))
    into(layout.buildDirectory.dir("generated/renderTestAssets"))
}

android.sourceSets.getByName("androidTest").assets.srcDir(
    layout.buildDirectory.dir("generated/renderTestAssets")
)

tasks.named("preBuild") {
    dependsOn(syncWebAssets)
    dependsOn(prepareBundledModels)
    dependsOn(syncRenderTest)
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
}
