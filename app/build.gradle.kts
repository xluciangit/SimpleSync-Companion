import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace  = "com.simplesync.companion"
    compileSdk = 35

    // Read signing credentials from local.properties (never commit these)
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) load(f.inputStream())
    }

    signingConfigs {
        create("release") {
            storeFile = localProps.getProperty("KEYSTORE_PATH")?.let { file(it) }
            storePassword = localProps.getProperty("KEYSTORE_PASSWORD")
            keyAlias = localProps.getProperty("KEY_ALIAS")
            keyPassword = localProps.getProperty("KEY_PASSWORD")
        }
    }

    defaultConfig {
        applicationId = "com.simplesync.companion"
        minSdk        = 26
        targetSdk     = 35
        versionCode   = 4
        versionName   = "1.2.2"
        signingConfig = signingConfigs.getByName("release")
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig    = signingConfigs.getByName("release")
            isMinifyEnabled  = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.workmanager.ktx)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(libs.documentfile)
    testImplementation("junit:junit:4.13.2")
}