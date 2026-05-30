lateinit var jvmTarget: String

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.linux_core"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.linux_core"
        minSdk = 33
        targetSdk = 28
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    compileOptions {
        jvmTarget = "17"
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
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    // Coroutines & OkHttp for downloading rootfs
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    // Apache Commons Compress for pure-Java tar.xz extraction (Android has no tar binary)
    //noinspection UseTomlInstead
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation(libs.xz)

    // Termux for terminal emulation
    implementation("com.github.termux.termux-app:termux-shared:v0.118.0") {
        exclude(group = "com.google.guava", module = "listenablefuture")
    }
    implementation("com.github.termux.termux-app:terminal-view:v0.118.0") {
        exclude(group = "com.google.guava", module = "listenablefuture")
    }
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.0") {
        exclude(group = "com.google.guava", module = "listenablefuture")
    }
    //noinspection UseTomlInstead
    implementation("com.google.guava:guava:33.6.0-android")

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}