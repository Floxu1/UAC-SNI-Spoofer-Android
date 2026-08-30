import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingPropertiesFile = providers.gradleProperty("uacSigningProperties").orNull
    ?.let(::file)
    ?: providers.environmentVariable("UAC_SIGNING_PROPERTIES").orNull?.let(::file)
    ?: rootProject.file("../signing.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) {
        signingPropertiesFile.inputStream().use { load(it) }
    }
}
val releaseSigningAvailable = signingPropertiesFile.isFile &&
    signingProperties.getProperty("storeFile").orEmpty().isNotBlank()

android {
    namespace = "com.uacspoofer.mobile"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.uacspoofer.mobile"
        minSdk = 24
        targetSdk = 35
        versionCode = 288
        versionName = "2.0.4"

        buildConfigField("boolean", "TV_MODE", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = signingPropertiesFile.parentFile.resolve(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            }
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("tv") {
            initWith(getByName("debug"))
            buildConfigField("boolean", "TV_MODE", "true")
            ndk {
                abiFilters.clear()
                abiFilters += "armeabi-v7a"
            }
            matchingFallbacks += listOf("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libxray.so", "**/libtor.so", "**/libwebtunnel.so", "**/libhev-socks5-tunnel.so")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.register("copyTvApkNextToDebug") {
    dependsOn("assembleTv")
    doLast {
        copy {
            from(layout.buildDirectory.dir("outputs/apk/tv")) {
                include("*.apk")
                rename { "app-tv-armeabi-v7a.apk" }
            }
            into(layout.buildDirectory.dir("outputs/apk/debug"))
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation(files("libs/libv2ray-native-tun.aar"))
    implementation("com.facebook.fresco:fresco:3.6.0")
    implementation("com.facebook.fresco:animated-webp:3.6.0")
    implementation("com.facebook.fresco:webpsupport:3.6.0")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
