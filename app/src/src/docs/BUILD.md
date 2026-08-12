# Building kali_core_emulator

This document explains how to build the kali_core_emulator Android application.

## Quick Start

### Option 1: GitHub Actions CI/CD (Recommended)
```bash
git push origin master
# GitHub Actions automatically builds and uploads APK as artifact
```

### Option 2: Local Build

**Prerequisites:**
- Java JDK 21
- Android SDK (API 36, Build Tools 36.0.0)

**Steps:**
```bash
# Set up environment
export JAVA_HOME=/path/to/jdk21
export ANDROID_HOME=/path/to/android-sdk

# Build APK
./gradlew assembleDebug

# Deploy to device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## System Requirements

### For Local Builds

| Component | Version | Notes |
|---|---|---|
| Java JDK | 21+ | Temurin, OpenJDK, or Oracle JDK |
| Android SDK | 36+ | Platform and Build Tools |
| Gradle | 8.x | Included via gradle wrapper |
| Git | 2.0+ | With LFS support for large files |

### For CI/CD Builds

- GitHub Actions (ubuntu-latest)
- JDK 21 setup (actions/setup-java@v4)
- Android SDK setup (android-actions/setup-android@v3)

## Build Options

### Debug APK
```bash
./gradlew assembleDebug
```
- Outputs: `app/build/outputs/apk/debug/app-debug.apk`
- Includes debugging symbols
- Not signed for Play Store
- Larger file size (~115MB)

### Release APK
```bash
./gradlew assembleRelease
```
- Requires signing configuration in `app/build.gradle.kts`
- Optimized and minified
- Ready for distribution

### Other Gradle Tasks
```bash
./gradlew clean              # Remove build artifacts
./gradlew test               # Run unit tests
./gradlew lint               # Run Android lint
./gradlew connectedAndroidTest  # Run instrumented tests
./gradlew -version           # Show gradle version
```

## Installation

### On Connected Device
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### On Emulator
```bash
emulator -avd <avd_name> &
sleep 5
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Troubleshooting

### JAVA_HOME Not Set
```bash
export JAVA_HOME=$(dirname $(dirname $(which java)))
./gradlew -version  # Verify
```

### Android SDK Not Found
```bash
export ANDROID_HOME=~/Android/Sdk  # Or your SDK path
ls $ANDROID_HOME/platforms/         # Verify
```

### Build Memory Error
Edit `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m
```

### Certificate/SSL Errors
- Use GitHub Actions CI/CD instead
- Or configure ca-certificates on your system

### APK Size Too Large
```bash
./gradlew assembleDebug --profile  # Check build profile
# Optimize build with:
org.gradle.parallel=true            # In gradle.properties
org.gradle.workers.max=4
```

## Build Artifacts

### Generated Files
```
app/build/
├── intermediates/          # Intermediate build files
├── generated/              # Generated code
├── outputs/
│   ├── apk/
│   │   ├── debug/
│   │   │   └── app-debug.apk        # Debug APK
│   │   └── release/
│   │       └── app-release.apk      # Release APK
│   └── bundle/
│       └── release/
│           └── app-release.aab      # Android App Bundle
└── reports/                # Build reports
```

### APK Contents
```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | head -20

# Verify TerminalMap binary is included:
unzip -p app/build/outputs/apk/debug/app-debug.apk assets/bin/terminalmap > /tmp/tm
file /tmp/tm  # Should show: ELF 64-bit LSB executable, ARM aarch64
```

## Gradle Wrapper

This project uses the Gradle wrapper for consistent builds.

```bash
# Wrapper handles Java/Gradle version management automatically
./gradlew --version         # Show gradle version
./gradlew wrapper --gradle-version=8.0  # Update wrapper
```

## Configuration

### build.gradle.kts
```kotlin
android {
    compileSdk = 36
    targetSdk = 36
    minSdk = 33
}

kotlin {
    jvmToolchain(21)
}
```

### gradle.properties
```properties
org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=256m
org.gradle.parallel=false
kotlin.code.style=official
```

## CI/CD Pipeline

The project uses GitHub Actions for automated builds:

```yaml
# .github/workflows/build.yml
on:
  push:
    branches: [master]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - Set up JDK 21 (Temurin)
      - Setup Android SDK
      - Build: ./gradlew assembleDebug
      - Upload APK artifact
```

**To trigger manually:**
```bash
gh workflow run build.yml --ref master
```

## Dependencies

### Critical Dependencies
- **Android Gradle Plugin**: 9.2.1
- **Kotlin**: 2.2.10
- **Compose**: 2026.05.01
- **OkHttp**: 5.3.2 (download)
- **commons-compress**: 1.28.0 (tar.xz extraction)
- **Termux**: v0.118.0 (terminal UI)
- **Guava**: 33.6.0-android

### See Also
- `app/build.gradle.kts` - Full dependency list
- `libs.versions.toml` - Version catalog

## Testing

```bash
# Unit tests (app/src/test/)
./gradlew test

# Instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest

# Run specific test
./gradlew test --tests "*ProotManager*"

# With debug output
./gradlew test --info
```

## Performance Tips

### Faster Builds
```bash
# Enable parallel builds in gradle.properties
org.gradle.parallel=true

# Use daemon
org.gradle.daemon=true

# Limit workers
org.gradle.workers.max=4

# Configure JVM
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m
```

### Incremental Builds
```bash
# Only rebuild changed parts
./gradlew assembleDebug -x lint

# Skip tests
./gradlew assembleDebug -x test
```

## Project Structure

```
kali_core_emulator/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/linux_core/           # Source code
│   │   │   ├── res/                            # Resources
│   │   │   └── assets/
│   │   │       └── bin/terminalmap            # TerminalMap binary (6.9MB)
│   │   ├── test/                              # Unit tests
│   │   └── androidTest/                       # Instrumented tests
│   ├── build.gradle.kts                       # App module build
│   └── proguard-rules.pro                     # Obfuscation rules
├── gradle/                                     # Gradle config
├── build.gradle.kts                           # Root build config
├── settings.gradle.kts                        # Gradle settings
├── gradlew                                    # Gradle wrapper (executable)
├── .github/workflows/build.yml               # CI/CD pipeline
└── BUILD.md                                   # This file
```

## References

- [Android Developer Guide](https://developer.android.com/docs)
- [Gradle Documentation](https://docs.gradle.org/)
- [Kotlin for Android](https://kotlinlang.org/docs/android-overview.html)
- [Android Gradle Plugin](https://developer.android.com/build/releases/gradle-plugin)

## Support

For issues with:
- **Build failures**: Check Gradle output for missing SDK components
- **Runtime errors**: Check logcat output
- **CI/CD failures**: Check GitHub Actions workflow logs

---

**Last Updated**: 2025-06-24
**Build Status**: ✓ Ready for CI/CD and local builds
