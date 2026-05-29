import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tid.nowplaying"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tid.nowplaying"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        create("release") {
            storeFile = file("keystore.jks")
            storePassword = "tidnowplaying"
            keyAlias = "tid"
            keyPassword = "tidnowplaying"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        aidl = true
    }
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
// Default to the submodule at firmware/; override with firmware.dir in local.properties.
val firmwareProjectDir = localProps.getProperty("firmware.dir") ?: "firmware"
val firmwareProject = rootProject.file(firmwareProjectDir).takeIf { it.exists() && it.isDirectory }
// Override with pio.path in local.properties; fallback to pio on PATH (used in CI).
val pio = localProps.getProperty("pio.path") ?: "pio"
val firmwareBoards = listOf("nano", "uno", "micro", "nano_diag")
val firmwareAssetsDir = file("src/main/assets/firmware")

tasks.register("compileFirmware") {
    onlyIf { firmwareProject != null }
    if (firmwareProject != null) {
        inputs.dir(file("$firmwareProject/src"))
        inputs.file(file("$firmwareProject/platformio.ini"))
        outputs.files(firmwareBoards.map { file("$firmwareAssetsDir/$it.hex") })
    }
    doLast {
        firmwareAssetsDir.mkdirs()
        val failed = mutableListOf<String>()
        firmwareBoards.forEach { env ->
            try {
                exec {
                    workingDir = firmwareProject!!
                    commandLine(pio, "run", "-e", env)
                    isIgnoreExitValue = true
                }.also { result ->
                    if (result.exitValue != 0) {
                        failed.add(env)
                    } else {
                        copy {
                            from(file("$firmwareProject/.pio/build/$env/firmware.hex"))
                            into(firmwareAssetsDir)
                            rename { "$env.hex" }
                        }
                    }
                }
            } catch (e: Exception) {
                failed.add(env)
            }
        }
        if (failed.isNotEmpty()) {
            throw GradleException("Firmware build failed for: ${failed.joinToString(", ")}")
        }
    }
}

tasks.named("preBuild") { dependsOn("compileFirmware") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.github.mik3y:usb-serial-for-android:3.10.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    debugImplementation(libs.androidx.ui.tooling)
}
