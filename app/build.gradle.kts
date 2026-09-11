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
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.uacspoofer.mobile"
        minSdk = 24
        targetSdk = 35
        versionCode = 324
        versionName = "2.0.5"

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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf(
                "**/libxray.so",
                "**/libtor.so",
                "**/libwebtunnel.so",
                "**/libhev-socks5-tunnel.so",
                "**/libaether.so",
                "**/libaether_jni.so",
                "**/libgojni.so",
                "**/libgopsi.so",
            )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

android.applicationVariants.configureEach {
    val version = versionName
    outputs.configureEach {
        val apkOutput = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
        val abi = apkOutput.getFilter("ABI") ?: "universal"
        if (buildType.name == "release") {
            apkOutput.outputFileName = "UAC-${version}-${abi}-Android7plus.apk"
        }
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    doLast {
        val dir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val version = android.defaultConfig.versionName ?: "2.0.5"
        val universal = dir.listFiles()
            ?.filter { it.extension.equals("apk", ignoreCase = true) }
            ?.firstOrNull { it.name.contains("universal", ignoreCase = true) }
        if (universal != null) {
            universal.copyTo(dir.resolve("app-release.apk"), overwrite = true)
        }
        dir.resolve("WHICH-APK.txt").writeText(
            """
            UAC SNI Spoofer $version
            همه این فایل‌ها حداقل اندروید ۷ (Android 7.0) می‌خواهند.
            All of these APKs require Android 7.0 or newer.

            UAC-$version-arm64-v8a-Android7plus.apk
              گوشی‌های ۶۴ بیتی — تقریباً همه گوشی‌های ۲۰۱۷ به بعد. سبک‌تر است. پیشنهاد اصلی.
              64-bit phones (most devices from 2017 on). Smaller. Recommended.

            UAC-$version-armeabi-v7a-Android7plus.apk
              گوشی‌های ۳۲ بیتی قدیمی.
              32-bit phones only.

            UAC-$version-universal-Android7plus.apk
              روی همه معماری‌ها نصب می‌شود. حجم بیشتر.
              Works on every CPU. Largest file.
              app-release.apk همین فایل است.

            UAC-$version-x86_64-Android7plus.apk
            UAC-$version-x86-Android7plus.apk
              امولاتور / شبیه‌ساز. برای گوشی واقعی نیست.
              Emulators only, not real phones.

            نسخه universal را می‌توان با arm64 روی همان گوشی ۶۴ بیتی آپدیت کرد
            (همان امضا و versionCode بالاتر یا مساوی).
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
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

val generatedPowAars = layout.buildDirectory.dir("generated/pow-aars")

val isolatePsiphonAar = tasks.register<Exec>("isolatePsiphonAar") {
    val out = generatedPowAars.map { it.file("psiphontunnel-isolated.aar") }
    inputs.file(file("libs/psiphontunnel-2.0.39.aar"))
    inputs.file(rootProject.file("scripts/isolate_psiphon_aar.py"))
    outputs.file(out)
    commandLine("python", rootProject.file("scripts/isolate_psiphon_aar.py").absolutePath)
    environment("POW_PSIPHON_SRC", file("libs/psiphontunnel-2.0.39.aar").absolutePath)
    environment("POW_PSIPHON_DST", out.get().asFile.absolutePath)
}

val patchV2raySeqAar = tasks.register<Exec>("patchV2raySeqAar") {
    val out = generatedPowAars.map { it.file("libv2ray-seqpatched.aar") }
    inputs.file(file("libs/libv2ray-native-tun.aar"))
    inputs.file(rootProject.file("scripts/patch_v2ray_seq.py"))
    outputs.file(out)
    commandLine("python", rootProject.file("scripts/patch_v2ray_seq.py").absolutePath)
    environment("POW_V2RAY_SRC", file("libs/libv2ray-native-tun.aar").absolutePath)
    environment("POW_V2RAY_DST", out.get().asFile.absolutePath)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation(isolatePsiphonAar.map { it.outputs.files })
    implementation(patchV2raySeqAar.map { it.outputs.files })
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

listOf("arm64-v8a", "armeabi-v7a", "x86_64").forEach { abi ->
    val output = file("src/main/jniLibs/$abi/libaether.so")
    if (output.isFile) return@forEach
    val taskName = "buildUacPowCore${abi.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) }}"
    tasks.register<Exec>(taskName) {
        group = "build"
        description = "Build UAC PoW WARP core for $abi"
        val buildScript = rootProject.file("core/build-android.ps1")
        commandLine(
            "powershell.exe",
            "-ExecutionPolicy", "Bypass",
            "-File", buildScript.absolutePath,
            "-Abi", abi,
        )
        environment("ANDROID_HOME", android.sdkDirectory.absolutePath)
        inputs.dir(rootProject.file("core/aether/src"))
        inputs.file(rootProject.file("core/aether/Cargo.toml"))
        inputs.file(buildScript)
        outputs.file(output)
    }
    tasks.named("preBuild").configure { dependsOn(taskName) }
}
