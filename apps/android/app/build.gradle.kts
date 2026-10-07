plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.motif"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.motif"
        minSdk = 26
        targetSdk = 35
        // CI run number, so each downloaded APK installs over the last one.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.$versionCode"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        // Free key from developer.jamendo.com, from the environment (a CI secret) or
        // `jamendo.clientId` in ~/.gradle/gradle.properties. Empty means Discover asks for one.
        val jamendo = System.getenv("JAMENDO_CLIENT_ID") ?: providers.gradleProperty("jamendo.clientId").orNull ?: ""
        buildConfigField("String", "JAMENDO_CLIENT_ID", "\"${jamendo.trim()}\"")
    }

    signingConfigs {
        // Development key, committed on purpose so every CI build carries the same
        // signature and installs as an update. Replace before any store release.
        create("dev") {
            storeFile = file("motif-dev.keystore")
            storePassword = "motif-dev"
            keyAlias = "motif-dev"
            keyPassword = "motif-dev"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dev")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("dev")
        }
    }

    sourceSets {
        // schemas/library.sql is the shared source of truth for the library database.
        getByName("main").assets.srcDir("../../../schemas")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    // The real org.json, since android.jar's copy only has stubs in unit tests.
    testImplementation(libs.json)
}
