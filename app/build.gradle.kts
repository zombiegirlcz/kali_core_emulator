import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // alias(libs.plugins.kotlin.android) // Dočasně zakomentováno pro test duplicity
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.linux_core"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.linux_core"
        minSdk = 28
        targetSdk = 28
        versionCode = 4
        versionName = "4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "password123"
            keyAlias = "releaseKey"
            keyPassword = "password123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            isJniDebuggable = true
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/jniLibs")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.drawerlayout)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    implementation(libs.commons.compress)
    implementation(libs.xz)

    implementation(libs.termux.shared) {
        exclude(group = "com.google.guava", module = "listenablefuture")
    }
    implementation(libs.termux.terminal.view) {
        exclude(group = "com.google.guava", module = "listenablefuture")
    }
    implementation(libs.termux.terminal.emulator) {
        exclude(group = "com.google.guava", module = "listenablefuture")
    }
    implementation(libs.guava)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
