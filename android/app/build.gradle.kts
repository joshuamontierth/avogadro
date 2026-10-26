import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing: keystore/keystore.properties, created once by build.sh and never committed.
val signing = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// The instance's address comes from avogadro.properties (written by build.sh from ../.env),
// so it never lands in the repository.
val instance = Properties().apply {
    val f = rootProject.file("avogadro.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val baseUrl = (instance.getProperty("baseUrl") ?: "https://search.example.com/").trimEnd('/')

android {
    namespace = "net.montierth.avogadro"
    // Current androidx releases (core 1.19, Compose UI 1.12) require compileSdk 37.
    compileSdk = 37

    defaultConfig {
        applicationId = "net.montierth.avogadro"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
    }

    signingConfigs {
        create("release") {
            if (signing.isNotEmpty()) {
                storeFile = rootProject.file("keystore/" + signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
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
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.browser)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
