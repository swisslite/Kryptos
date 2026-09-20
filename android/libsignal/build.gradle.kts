plugins {
    id("com.android.library")
}

val ls = "../../ThirdParty/libsignal/java"

android {
    namespace = "org.signal.libsignal"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("$ls/shared/resources/META-INF/proguard/libsignal.pro")
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        listOf("$ls/shared/java", "$ls/client/src/main/java", "$ls/android/src/main/java").forEach {
            variant.sources.java?.addStaticSourceDirectory(it)
            variant.sources.kotlin?.addStaticSourceDirectory(it)
        }
        variant.sources.resources?.addStaticSourceDirectory("$ls/shared/resources")
        variant.sources.jniLibs?.addStaticSourceDirectory("$ls/android/src/main/jniLibs")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
