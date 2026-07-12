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
        versionCode = 8
        versionName = "4.2-MITM-LOG-FIX"

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
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: propertyOrNull("keystore.password") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: propertyOrNull("key.alias") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: propertyOrNull("key.password") ?: ""
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
    tasks.register("validateCertAssets") {
        doLast {
            val assetsDir = file("src/main/assets/certs")
            
            // In development, we might not have all certs yet.
            // Only fail build if it's a release build or if files are critical.
            val isRelease = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

            if (!assetsDir.exists()) {
                if (isRelease) throw GradleException("Certificate assets directory missing: $assetsDir")
                else {
                    assetsDir.mkdirs()
                    println("Created missing certs directory")
                }
            }
            
            val requiredFiles = mutableListOf<String>()
            if (isRelease) {
                requiredFiles.addAll(listOf("mitm-ca.crt", "mitm-ca.p12", "google_attestation_root.der", "internal.p12"))
            }

            requiredFiles.forEach { fileName ->
                if (!file("${assetsDir}/$fileName").exists()) {
                    throw GradleException("Critical certificate asset missing for release: certs/$fileName")
                }
            }

            // For debug builds, just warn if they are missing
            if (!isRelease) {
                listOf("mitm-ca.crt", "mitm-ca.p12", "google_attestation_root.der", "internal.p12").forEach { fileName ->
                    if (!file("${assetsDir}/$fileName").exists()) {
                        println("WARNING: Optional development cert missing: certs/$fileName")
                    }
                }
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
