import java.util.Properties

val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasSigning = keystoreProps.getProperty("storePassword").isNullOrEmpty().not() &&
    rootProject.file(keystoreProps.getProperty("storeFile", "kryptos.keystore")).exists()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.kryptos.android"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.kryptos.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "2.4"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    androidResources {
        localeFilters += listOf(
            "de", "en", "en-rAU", "en-rCA", "en-rGB", "en-rIN", "fa",
            "pt", "pt-rBR", "pt-rPT", "ru", "zh", "zh-rCN", "zh-rHK", "zh-rTW",
        )
    }

    signingConfigs {
        if (hasSigning) {
            create("selfsigned") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile", "kryptos.keystore"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("selfsigned")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf(
            "META-INF/{AL2.0,LGPL2.1}",
            "META-INF/*.version",
            "META-INF/versions/*/OSGI-INF/MANIFEST.MF",
            "libsignal_jni*.dylib", "signal_jni*.dll", "libsignal_jni*.so",
            "org/bouncycastle/x509/CertPathReviewerMessages*.properties",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json",
            "META-INF/**/verification.properties",
            "kotlin/**.kotlin_builtins",
        )
        resources.pickFirsts += "META-INF/LICENSE.md"
        jniLibs.excludes += "**/libsignal_jni_testing.so"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation(project(":libsignal"))

    implementation("org.pgpainless:pgpainless-core:2.0.4")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.bouncycastle:bcpg-jdk18on:1.86")
    implementation("org.bouncycastle:bcutil-jdk18on:1.86")

    implementation("com.google.zxing:core:3.5.4")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
