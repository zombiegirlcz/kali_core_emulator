import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Helper: returns null if property is not set (avoids MissingPropertyException)
fun propertyOrNull(name: String): String? {
    return if (hasProperty(name)) property(name) as? String else null
}

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
        versionCode = 7
        versionName = "4.1-AI-FIX"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("boolean", "ENABLE_MITM", "false")
        buildConfigField("boolean", "ENABLE_ATTESTATION", "true")
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            // WARNING: Passwords should come from environment variables or a secure CI pipeline
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: propertyOrNull("keystore.password") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: propertyOrNull("key.alias") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: propertyOrNull("key.password") ?: ""
        }
        getByName("debug") {
            storeFile = file("release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: propertyOrNull("keystore.password") ?: "password123"
            keyAlias = System.getenv("KEY_ALIAS") ?: propertyOrNull("key.alias") ?: "releaseKey"
            keyPassword = System.getenv("KEY_PASSWORD") ?: propertyOrNull("key.password") ?: "password123"
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
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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

    // Validate certificate assets exist when MITM or Attestation is enabled
    // Simple approach: always check if files exist (fails fast)
    tasks.register("validateCertAssets") {
        doLast {
            val assetsDir = file("src/main/assets/certs")
            // Always require certs when ENABLE_MITM/ENABLE_ATTESTATION are true in defaultConfig
            val mitmEnabled = true // Set via buildConfigField in defaultConfig
            val attestEnabled = true // Set via buildConfigField in defaultConfig
            
            // Check if certs directory exists at all
            if (!assetsDir.exists()) {
                throw GradleException("Certificate assets directory missing: $assetsDir. Run generate-dev-certs.sh")
            }
            
            // Check required certificate files
            if (!file("${assetsDir}/mitm-ca.crt").exists()) {
                throw GradleException("MITM CA certificate missing: certs/mitm-ca.crt. Run generate-dev-certs.sh or provide production certs.")
            }
            if (!file("${assetsDir}/mitm-ca.p12").exists()) {
                throw GradleException("MITM CA key missing: certs/mitm-ca.p12. Run generate-dev-certs.sh or provide production certs.")
            }
            if (!file("${assetsDir}/google_attestation_root.der").exists()) {
                throw GradleException("Google attestation root missing: certs/google_attestation_root.der. Download from https://pki.goog/hardware-attestation-root.pem")
            }
            if (!file("${assetsDir}/internal.p12").exists()) {
                throw GradleException("Internal keystore missing: certs/internal.p12. Run generate-dev-certs.sh or provide production certs.")
            }
        }
    }
    
    tasks.named("preBuild") {
        dependsOn("validateCertAssets")
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

    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
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

    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.androidx.biometric)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
