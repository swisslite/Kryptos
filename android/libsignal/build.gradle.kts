import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val ls = "../../ThirdParty/libsignal/java"

android {
    namespace = "org.signal.libsignal"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("$ls/shared/resources/META-INF/proguard/libsignal.pro")
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].apply {
        java.srcDirs("$ls/shared/java", "$ls/client/src/main/java", "$ls/android/src/main/java")
        kotlin.srcDirs("$ls/shared/java", "$ls/client/src/main/java", "$ls/android/src/main/java")
        resources.srcDir("$ls/shared/resources")
        jniLibs.srcDir("$ls/android/src/main/jniLibs")
    }

    packaging { jniLibs.keepDebugSymbols += "**/*.so" }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
