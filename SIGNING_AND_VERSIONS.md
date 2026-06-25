# Signing & Version Management

## ✓ Current Configuration

The app is configured for **seamless updates** with the same signing key across debug and release builds.

### Keystore Details

- **File:** `app/release.jks` (2.7KB)
- **Key Alias:** `releaseKey`
- **Password:** `password123`
- **Both debug & release** use the same keystore

### Application Identity

- **Package:** `com.linux_core`
- **Current versionCode:** `5`
- **Current versionName:** `4.1-AI-FIX`

## Why Same Key Matters

**With same key:** Users can update app (install with `-r` flag)
```bash
adb install -r app-v6.apk    # ✓ Upgrade, no uninstall needed
```

**With different keys:** Users must uninstall first
```bash
adb uninstall com.linux_core  # ✗ App deleted
adb install app-v6.apk        # ✗ Fresh install as new app
```

## Version Management

### When to Increment versionCode

Every new build that will be distributed must have a **higher** versionCode:

```gradle
// app/build.gradle.kts

// OLD (versionCode = 5)
versionCode = 6      // ← INCREMENT THIS

// Human-readable for users
versionName = "4.2"  // ← Optional, for changelog
```

### Increment Strategy

| Release | versionCode | versionName | Type |
|---------|-------------|------------|------|
| Initial | 5 | 4.1-AI-FIX | Baseline |
| Next | 6 | 4.2 | Bug fix |
| Next | 7 | 5.0 | Feature |
| Next | 100 | 5.1-beta | Pre-release |

**Rule:** Must always increase, no gaps needed.

## Quick Update Workflow

1. **Update version number:**
   ```bash
   # Edit app/build.gradle.kts, change versionCode = N to N+1
   nano app/build.gradle.kts
   ```

2. **Build:**
   ```bash
   ./gradlew clean assembleDebug
   ```

3. **Deploy to device:**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

4. **Verify:**
   ```bash
   adb shell pm dump com.linux_core | grep versionCode
   # Should show incremented version
   ```

## Signing Configuration

Both debug and release builds use the same keystore:

```kotlin
// app/build.gradle.kts
signingConfigs {
    create("release") {
        storeFile = file("release.jks")
        storePassword = "password123"
        keyAlias = "releaseKey"
        keyPassword = "password123"
    }
    getByName("debug") {
        storeFile = file("release.jks")      // SAME FILE
        storePassword = "password123"
        keyAlias = "releaseKey"
        keyPassword = "password123"
    }
}
```

## Build Commands

```bash
# Debug APK (for testing)
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Release APK (optimized, for distribution)
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

## Troubleshooting

**"Can't install as update"** → versionCode didn't increase
```bash
# Check what's installed
adb shell pm dump com.linux_core | grep versionCode

# Verify your build incremented it
grep versionCode app/build.gradle.kts
```

**"Signature mismatch"** → Different keystore used
```bash
# Verify keystore path in build.gradle.kts:
grep "storeFile = file" app/build.gradle.kts
# Should show: storeFile = file("release.jks")
```

**Lost keystore** → App can't be updated with same signature
```bash
# Prevention: Always backup app/release.jks
cp app/release.jks app/release.jks.backup
```

## Security Notes

Passwords are currently hardcoded. For production use environment variables:

```kotlin
val keystorePassword = System.getenv("KEYSTORE_PASSWORD") ?: "password123"
val keyPassword = System.getenv("KEY_PASSWORD") ?: "password123"
```

Then:
```bash
export KEYSTORE_PASSWORD=your_password
export KEY_PASSWORD=your_password
./gradlew assembleRelease
```

## Summary

✓ Same signing key for debug and release
✓ App can be updated without reinstalling
✓ Increment versionCode before each release
✓ Use `adb install -r` to deploy updates

**Next:** Increment versionCode and build!
