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
        versionCode = 1
        versionName = "1.0"
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
    }
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val firmwareProjectDir = localProps.getProperty("firmware.dir")
val pio = "/home/vodkannelle/.platformio/penv/bin/pio"
val firmwareBoards = listOf("nano", "uno")
val firmwareAssetsDir = file("src/main/assets/firmware")

tasks.register("compileFirmware") {
    onlyIf { firmwareProjectDir != null }
    inputs.dir("$firmwareProjectDir/src")
    inputs.file("$firmwareProjectDir/platformio.ini")
    outputs.files(firmwareBoards.map { file("$firmwareAssetsDir/$it.hex") })
    doLast {
        firmwareAssetsDir.mkdirs()
        exec {
            workingDir = file(firmwareProjectDir!!)
            commandLine(pio, "run", *firmwareBoards.flatMap { listOf("-e", it) }.toTypedArray())
        }
        firmwareBoards.forEach { env ->
            copy {
                from(file("$firmwareProjectDir/.pio/build/$env/firmware.hex"))
                into(firmwareAssetsDir)
                rename { "$env.hex" }
            }
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
    implementation("com.github.mik3y:usb-serial-for-android:3.10.0")
    implementation("androidx.car.app:app:1.4.0")
    debugImplementation(libs.androidx.ui.tooling)
}
