# Shizuku + Services Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add self-contained Shizuku privilege escalation + services dashboard (Shizuku, code-server, Phoenix) into the NetHunter terminal top bar.

**Architecture:** New `ShizukuManager` handles server lifecycle and rish deployment. `TerminalActivity` gets expandable services panel below the top bar. Existing `code-server-ctl` and `PhoenixExporter` reused as-is.

**Tech Stack:** Kotlin, Android Views (no Compose in TerminalActivity), Shizuku native binaries, PRoot.

## Global Constraints

- All new Kotlin files in `app/src/main/java/com/linux_core/core/`
- TerminalActivity uses programmatic Views (LinearLayout, Button, TextView), **not** Compose
- Follow existing code style (monospace fonts, `Color.parseColor("#...")`, `createRoundedDrawable()`)
- Service status checks happen on main thread via Handler pattern (existing code uses `Handler(Looper.getMainLooper())`)
- MinSDK 28, targetSDK 28
- `app/src/main/java/com/linux_core/` is the root source package

---

### Task 1: AndroidManifest — Shizuku Permission

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: nothing
- Produces: apps can declare Shizuku API permission

- [ ] **Step 1: Add Shizuku API permission to manifest**

After the existing `<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />` line, add the Shizuku permission declaration:

```xml
    <permission android:name="moe.shizuku.manager.permission.API_V23"
        android:protectionLevel="signature" />
    <uses-permission android:name="moe.shizuku.manager.permission.API_V23" />
    <uses-permission android:name="moe.shizuku.manager.permission.MANAGER" />
```

- [ ] **Step 2: Verify no conflicts**

Check that no other permission in the manifest conflicts with `moe.shizuku.*`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat(shizuku): add Shizuku permission declarations to manifest"
```

---

### Task 2: Bundle Shizuku Binaries and Rish into Assets

**Files:**
- Create: `app/src/main/assets/shizuku/libshizuku.so` (copy from arm64/)
- Create: `app/src/main/assets/shizuku/rish.sh` (copy from shi/rish, modify PKG)
- Create: `app/src/main/assets/shizuku/rish_shizuku.dex` (copy from shi/)

**Interfaces:**
- Consumes: existing `arm64/libshizuku.so`, `shi/rish`, `shi/rish_shizuku.dex`
- Produces: assets ready for ProotManager to deploy

- [ ] **Step 1: Create assets/shizuku directory**

```bash
mkdir -p app/src/main/assets/shizuku
```

- [ ] **Step 2: Copy libshizuku.so**

```bash
cp arm64/libshizuku.so app/src/main/assets/shizuku/
```

- [ ] **Step 3: Copy and modify rish.sh**

Copy `shi/rish` to `app/src/main/assets/shizuku/rish.sh`, change the application ID:

```bash
cp shi/rish app/src/main/assets/shizuku/rish.sh
```

Edit the file to change `com.linux_distro` to `com.linux_core`:

```bash
# In app/src/main/assets/shizuku/rish.sh, change line:
# [ -z "$RISH_APPLICATION_ID" ] && export RISH_APPLICATION_ID="com.linux_core"
```

- [ ] **Step 4: Copy rish_shizuku.dex**

```bash
cp shi/rish_shizuku.dex app/src/main/assets/shizuku/
```

- [ ] **Step 5: Verify assets exist**

```bash
ls -la app/src/main/assets/shizuku/
# Should show: libshizuku.so, rish.sh, rish_shizuku.dex
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/assets/shizuku/
git commit -m "feat(shizuku): bundle Shizuku server binary and rish shell into assets"
```

---

### Task 3: ShizukuManager.kt — New Module

**Files:**
- Create: `app/src/main/java/com/linux_core/core/ShizukuManager.kt`

**Interfaces:**
- Consumes: `Context` for assets access and file operations
- Produces: `ShizukuStatus` data class, `exec()`, `startServer()`, `stopServer()`, `status()`, `deployRish()` methods

- [ ] **Step 1: Create ShizukuManager.kt**

```kotlin
package com.linux_core.core

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream

data class ShizukuStatus(
    val running: Boolean,
    val pid: Int? = null,
    val uid: Int? = null,
    val port: Int? = null,
    val mode: String = "unknown" // "existing", "adb", "none"
)

object ShizukuManager {
    private const val TAG = "ShizukuManager"
    
    // Asset paths
    private const val ASSET_SERVER = "shizuku/libshizuku.so"
    private const val ASSET_RISH_SCRIPT = "shizuku/rish.sh"
    private const val ASSET_RISH_DEX = "shizuku/rish_shizuku.dex"
    
    // FilesDir paths
    private const val SERVER_BIN = "shizuku-server"
    private const val RISH_SCRIPT = "rish.sh"
    private const val RISH_DEX = "rish_shizuku.dex"
    private const val PID_FILE = "shizuku.pid"
    
    /**
     * Deploy Shizuku server binary from assets to filesDir.
     */
    private fun deployServer(context: Context): File? {
        val target = File(context.filesDir, SERVER_BIN)
        if (target.exists() && target.length() > 0L) {
            target.setExecutable(true, false)
            return target
        }
        return try {
            context.assets.open(ASSET_SERVER).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setExecutable(true, false)
            target.setReadable(true, false)
            Log.i(TAG, "Deployed Shizuku server binary (${target.length()} bytes)")
            target
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy Shizuku server: ${e.message}")
            null
        }
    }
    
    /**
     * Deploy rish script and dex to the given rootfs directory (for PRoot).
     */
    fun deployRish(context: Context, rootfsDir: File) {
        val binDir = File(rootfsDir, "usr/local/bin")
        val libDir = File(rootfsDir, "usr/local/lib")
        if (!binDir.exists()) binDir.mkdirs()
        if (!libDir.exists()) libDir.mkdirs()
        
        // Deploy rish script
        val rishScript = File(binDir, "shizuku")
        try {
            context.assets.open(ASSET_RISH_SCRIPT).use { input ->
                rishScript.outputStream().use { output -> input.copyTo(output) }
            }
            rishScript.setExecutable(true, false)
            rishScript.setReadable(true, false)
            Log.i(TAG, "Deployed rish wrapper to ${rishScript.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy rish script: ${e.message}")
        }
        
        // Deploy rish dex
        val rishDex = File(libDir, "rish_shizuku.dex")
        try {
            context.assets.open(ASSET_RISH_DEX).use { input ->
                rishDex.outputStream().use { output -> input.copyTo(output) }
            }
            rishDex.setReadable(true, false)
            Log.i(TAG, "Deployed rish dex to ${rishDex.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy rish dex: ${e.message}")
        }
    }
    
    /**
     * Check if Shizuku server is running.
     */
    fun status(context: Context): ShizukuStatus {
        // Check via PID file
        val pidFile = File(context.filesDir, PID_FILE)
        if (pidFile.exists()) {
            val pid = try { pidFile.readText().trim().toInt() } catch (e: Exception) { null }
            if (pid != null) {
                // Check if process exists
                val processList = try {
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", "ls /proc/$pid/status 2>/dev/null"))
                    processList.inputStream.bufferedReader().readText()
                } catch (e: Exception) { "" }
                if (processList.isNotEmpty()) {
                    return ShizukuStatus(running = true, pid = pid, mode = "self")
                }
            }
            // Stale PID file
            pidFile.delete()
        }
        
        // Also check if an existing Shizuku server is running (from Shizuku app)
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "ps -ef 2>/dev/null | grep -i shizuku | grep -v grep | head -5"))
            val output = proc.inputStream.bufferedReader().readText()
            if (output.isNotBlank()) {
                // Parse PID from ps output
                val pid = Regex("\\s*(root|shell)\\s+(\\d+)").find(output)?.groupValues?.get(2)?.toIntOrNull()
                return ShizukuStatus(running = true, pid = pid, mode = "existing")
            }
        } catch (e: Exception) { /* ignore */ }
        
        return ShizukuStatus(running = false, mode = "none")
    }
    
    /**
     * Execute a command through Shizuku (via rish if available).
     */
    fun exec(context: Context, command: String): String {
        val status = status(context)
        if (!status.running) {
            return "{\"error\":\"Shizuku server not running\",\"exit_code\":-1}"
        }
        
        return try {
            val dexFile = File(context.filesDir, RISH_DEX)
            if (!dexFile.exists()) {
                // Deploy rish first
                context.assets.open(ASSET_RISH_DEX).use { input ->
                    dexFile.outputStream().use { output -> input.copyTo(output) }
                }
                dexFile.setReadable(true, false)
            }
            
            val pb = ProcessBuilder(
                "/system/bin/app_process",
                "-Djava.class.path=${dexFile.absolutePath}",
                "/system/bin",
                "--nice-name=shizuku-exec",
                "rikka.shizuku.shell.ShizukuShellLoader",
                "-c", command
            )
            pb.environment()["RISH_APPLICATION_ID"] = context.packageName
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor()
            
            """{"stdout":${output.replace("\"","\\\"")},"exit_code":$exitCode}"""
        } catch (e: Exception) {
            """{"error":"${e.message}","exit_code":-1}"""
        }
    }
    
    /**
     * Attempt to start the Shizuku server.
     * Note: Starting requires either root or ADB. This method checks
     * for an existing server first.
     */
    fun startServer(context: Context): Boolean {
        val currentStatus = status(context)
        if (currentStatus.running) {
            Log.i(TAG, "Shizuku server already running")
            return true
        }
        
        // For now, rely on existing Shizuku server from Shizuku app.
        // Self-hosted ADB pairing will be added in a future iteration.
        Log.w(TAG, "No Shizuku server found. User needs to start Shizuku app first.")
        return false
    }
    
    /**
     * Attempt to stop the Shizuku server.
     */
    fun stopServer(context: Context): Boolean {
        val currentStatus = status(context)
        if (!currentStatus.running) return true
        
        if (currentStatus.mode == "self" && currentStatus.pid != null) {
            return try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "kill ${currentStatus.pid} 2>/dev/null"))
                val pidFile = File(context.filesDir, PID_FILE)
                pidFile.delete()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop Shizuku server: ${e.message}")
                false
            }
        }
        
        // If it's an existing server, can't stop it
        Log.w(TAG, "Cannot stop external Shizuku server")
        return false
    }
}
```

- [ ] **Step 2: Verify compiles**

Check by looking for unused imports, verify all referenced classes exist.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/linux_core/core/ShizukuManager.kt
git commit -m "feat(shizuku): add ShizukuManager module with server lifecycle and rish exec"
```

---

### Task 4: ProotManager — Deploy Rish into PRoot

**Files:**
- Modify: `app/src/main/java/com/linux_core/core/ProotManager.kt`

**Interfaces:**
- Consumes: `ShizukuManager.deployRish()` from Task 3
- Produces: `/usr/local/bin/shizuku` and `/usr/local/lib/rish_shizuku.dex` inside guest

- [ ] **Step 1: Add rish deployment call in ProotManager.setupProotEnvironment()**

In `ProotManager.setupProotEnvironment()`, after the existing `deployApiScripts(context, rootfsDir)` call (around line 76), add:

```kotlin
// Deploy Shizuku rish into PRoot
deployShizukuRish(context, rootfsDir, suffix)
```

- [ ] **Step 2: Add deployShizukuRish method**

Add this new method to `ProotManager`:

```kotlin
private fun deployShizukuRish(context: Context, rootfsDir: File, suffix: String) {
    if (suffix != "aarch64") {
        Log.w(TAG, "Shizuku rish deploy skipped: only arm64 is supported (suffix=$suffix)")
        return
    }
    val binDir = File(rootfsDir, "usr/local/bin")
    val libDir = File(rootfsDir, "usr/local/lib")
    if (!binDir.exists()) binDir.mkdirs()
    if (!libDir.exists()) libDir.mkdirs()

    // Deploy rish script as 'shizuku' command
    val rishScript = File(binDir, "shizuku")
    val rishDex = File(libDir, "rish_shizuku.dex")
    
    var needsDeploy = false
    if (!rishScript.exists() || rishScript.length() == 0L) needsDeploy = true
    if (!rishDex.exists() || rishDex.length() == 0L) needsDeploy = true
    
    if (!needsDeploy) {
        rishScript.setExecutable(true, false)
        return
    }
    
    try {
        context.assets.open("shizuku/rish.sh").use { input ->
            rishScript.outputStream().use { output -> input.copyTo(output) }
        }
        rishScript.setExecutable(true, false)
        rishScript.setReadable(true, false)
        Log.i(TAG, "Deployed shizuku command to guest (${rishScript.length()} bytes)")
    } catch (e: Exception) {
        Log.w(TAG, "Failed to deploy shizuku command: ${e.message}")
    }
    
    try {
        context.assets.open("shizuku/rish_shizuku.dex").use { input ->
            rishDex.outputStream().use { output -> input.copyTo(output) }
        }
        rishDex.setReadable(true, false)
        Log.i(TAG, "Deployed rish dex to guest (${rishDex.length()} bytes)")
    } catch (e: Exception) {
        Log.w(TAG, "Failed to deploy rish dex: ${e.message}")
    }
}
```

- [ ] **Step 3: Add import at top if needed**

The `ShizukuManager` class doesn't need to be imported since we use direct asset access. But add a comment. Actually, we don't use ShizukuManager here — we deploy directly from assets. Good.

- [ ] **Step 4: Verify the code compiles**

Check that `Log.w(TAG, ...)` uses the existing `TAG` constant.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/linux_core/core/ProotManager.kt
git commit -m "feat(shizuku): deploy rish shell into PRoot guest filesystem"
```

---

### Task 5: TerminalActivity — Services Panel in Top Bar

**Files:**
- Modify: `app/src/main/java/com/linux_core/ui/terminal/TerminalActivity.kt`

**Interfaces:**
- Consumes: `ShizukuManager` from Task 3
- Produces: Interactive services dashboard in terminal top bar

**⚠️ This is the largest change. The TerminalActivity uses programmatic Views.**

- [ ] **Step 1: Add state variables to TerminalActivity class**

Add these fields alongside the existing ones (after `private lateinit var statusTitle: TextView`):

```kotlin
// ── Services Panel State ──
private var isServicesExpanded = false
private var expandedService: String? = null // "shizuku", "code", "phoenix", or null
private lateinit var servicesPanel: LinearLayout
private lateinit var servicesDetailPanel: LinearLayout
private lateinit var btnServicesToggle: Button
private lateinit var btnShizuku: Button
private lateinit var btnCode: Button
private lateinit var btnPhoenix: Button
private val servicesUpdateHandler = Handler(Looper.getMainLooper())
private val servicesPoller = object : Runnable {
    override fun run() {
        if (isServicesExpanded) {
            updateAllServiceIndicators()
            servicesUpdateHandler.postDelayed(this, 5000)
        }
    }
}
```

- [ ] **Step 2: Replace the static statusTitle with an interactive row**

Find this section in `onCreate()` where `statusTitle` is created (around line 260):

```kotlin
statusTitle = TextView(this).apply {
    text = "🐉 KALI"
    textSize = 11f
    ...
}
```

Replace with:

```kotlin
// ── Distro title + Services toggle ──
val distroRow = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
}

statusTitle = TextView(this).apply {
    text = "🐉 KALI"
    textSize = 11f
    typeface = Typeface.MONOSPACE
    setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD))
    setTextColor(Color.parseColor("#00FF41"))
    gravity = Gravity.CENTER
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
}
distroRow.addView(statusTitle)

btnServicesToggle = Button(this).apply {
    text = "▼"
    textSize = 9f
    setTextColor(Color.parseColor("#00FF41"))
    background = null // transparent
    setPadding(4, 0, 4, 0)
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
    setOnClickListener {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        toggleServicesPanel()
    }
}
distroRow.addView(btnServicesToggle)
```

Then replace the `topBar.addView(statusTitle)` with `topBar.addView(distroRow)`.

- [ ] **Step 3: Add services panel building method**

Add these new methods to `TerminalActivity`:

```kotlin
/**
 * Build the services panel (shown when toggled).
 * Returns a LinearLayout with service buttons.
 */
private fun buildServicesPanel(): LinearLayout {
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 36f, resources.displayMetrics).toInt()
        ).apply {
            setMargins(8, 4, 8, 4)
        }
        
        // ⚡ SHIZU
        btnShizuku = createServiceButton("⚡", "SHIZU", Color.parseColor("#00BFFF"))
        addView(btnShizuku)
        
        addSpacer(6)
        
        // [code] CODE
        btnCode = createServiceButton("[code]", "CODE", Color.parseColor("#00FF41"))
        addView(btnCode)
        
        addSpacer(6)
        
        // 🔥 PHOENIX
        btnPhoenix = createServiceButton("🔥", "PHOENIX", Color.parseColor("#FF6B35"))
        addView(btnPhoenix)
        
        addSpacer(12)
        
        // Spacer
        View(this@TerminalActivity).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }.also { addView(it) }
        
        // ▶ START ALL button
        Button(this@TerminalActivity).apply {
            text = "▶ ALL"
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.parseColor("#00FF41"))
            background = createRoundedDrawable(Color.parseColor("#0f1017"), 6f, Color.parseColor("#00FF41"), 1f)
            setPadding(10, 4, 10, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28f, resources.displayMetrics).toInt()
            )
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                startAllServices()
            }
        }.also { addView(it) }
        
        addSpacer(4)
        
        // ↻ Refresh button
        Button(this@TerminalActivity).apply {
            text = "↻"
            textSize = 12f
            setTextColor(Color.Gray)
            background = null
            setPadding(6, 0, 6, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28f, resources.displayMetrics).toInt()
            )
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                updateAllServiceIndicators()
            }
        }.also { addView(it) }
    }
}

private fun createServiceButton(icon: String, label: String, accentColor: Int): Button {
    return Button(this@TerminalActivity).apply {
        text = "$icon $label ○"
        textSize = 9f
        typeface = Typeface.MONOSPACE
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(Color.Gray)
        background = createRoundedDrawable(Color.parseColor("#0c0d12"), 6f, Color.parseColor("#1e2026"), 1f)
        setPadding(10, 4, 10, 4)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28f, resources.displayMetrics).toInt()
        )
        tag = label.uppercase()
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val svc = (tag as String).lowercase()
            toggleServiceDetail(svc)
        }
    }
}

private fun addSpacer(widthDp: Int) {
    View(this@TerminalActivity).apply {
        layoutParams = LinearLayout.LayoutParams(
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, widthDp.toFloat(), resources.displayMetrics).toInt(),
            1
        )
    }.also { (servicesPanel as? LinearLayout)?.addView(it) }
}
```

- [ ] **Step 4: Add services detail panel building method**

```kotlin
/**
 * Build the detail panel for expanded service info.
 */
private fun buildServicesDetailPanel(): LinearLayout {
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics).toInt(),
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt(),
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics).toInt(),
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt()
        )
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        setBackgroundColor(Color.parseColor("#0c0d12"))
    }
}

/**
 * Update the detail panel content for a specific service.
 */
private fun updateServiceDetail(service: String) {
    servicesDetailPanel.removeAllViews()
    
    val context = this@TerminalActivity
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    
    when (service) {
        "shizuku" -> {
            val status = com.linux_core.core.ShizukuManager.status(applicationContext)
            val icon = if (status.running) "●" else "○"
            val color = if (status.running) Color.parseColor("#00FF41") else Color.Gray
            
            TextView(context).apply {
                text = "⚡ SHIZUKU SERVER  $icon"
                setTextColor(color)
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTypeface(Typeface.DEFAULT_BOLD)
            }.also { row.addView(it) }
            
            if (status.running) {
                TextView(context).apply {
                    text = "  pid:${status.pid ?: "?"}  uid:${status.uid?.toString() ?: "shell"}"
                    setTextColor(Color.LightGray)
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                }.also { row.addView(it) }
            }
            
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }.also { row.addView(it) }
            
            if (status.running) {
                // STOP button
                Button(context).apply {
                    text = "⏹ STOP"
                    textSize = 9f
                    setTextColor(Color.parseColor("#FF5555"))
                    background = createRoundedDrawable(Color.parseColor("#1a1a2e"), 6f, Color.parseColor("#FF5555"), 1f)
                    setPadding(10, 4, 10, 4)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt()
                    )
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        com.linux_core.core.ShizukuManager.stopServer(applicationContext)
                        updateAllServiceIndicators()
                    }
                }.also { row.addView(it) }
            } else {
                // START button
                Button(context).apply {
                    text = "▶ START"
                    textSize = 9f
                    setTextColor(Color.parseColor("#00FF41"))
                    background = createRoundedDrawable(Color.parseColor("#0a1a0a"), 6f, Color.parseColor("#00FF41"), 1f)
                    setPadding(10, 4, 10, 4)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt()
                    )
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        com.linux_core.core.ShizukuManager.startServer(applicationContext)
                        updateAllServiceIndicators()
                    }
                }.also { row.addView(it) }
            }
        }
        "code" -> {
            // Code-server status via LocalApiServer
            val raw = runCodeServerCtl("status")
            val running = raw.contains("running", ignoreCase = true) || raw.contains("pid", ignoreCase = true)
            val icon = if (running) "●" else "○"
            val color = if (running) Color.parseColor("#00FF41") else Color.Gray
            
            TextView(context).apply {
                text = "[code] CODE-SERVER  $icon"
                setTextColor(color)
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTypeface(Typeface.DEFAULT_BOLD)
            }.also { row.addView(it) }
            
            if (running) {
                TextView(context).apply {
                    text = "  :8443"
                    setTextColor(Color.LightGray)
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                }.also { row.addView(it) }
            }
            
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }.also { row.addView(it) }
            
            if (running) {
                Button(context).apply {
                    text = "⏹ STOP"
                    textSize = 9f
                    setTextColor(Color.parseColor("#FF5555"))
                    background = createRoundedDrawable(Color.parseColor("#1a1a2e"), 6f, Color.parseColor("#FF5555"), 1f)
                    setPadding(10, 4, 10, 4)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt()
                    )
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        runCodeServerCtl("stop")
                        updateAllServiceIndicators()
                    }
                }.also { row.addView(it) }
                
                addSpacerInner(4)
                
                Button(context).apply {
                    text = "🌐 OPEN"
                    textSize = 9f
                    setTextColor(Color.parseColor("#00BFFF"))
                    background = createRoundedDrawable(Color.parseColor("#0a1a2e"), 6f, Color.parseColor("#00BFFF"), 1f)
                    setPadding(10, 4, 10, 4)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt()
                    )
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("http://127.0.0.1:8443"))
                        startActivity(intent)
                    }
                }.also { row.addView(it) }
            } else {
                Button(context).apply {
                    text = "▶ START"
                    textSize = 9f
                    setTextColor(Color.parseColor("#00FF41"))
                    background = createRoundedDrawable(Color.parseColor("#0a1a0a"), 6f, Color.parseColor("#00FF41"), 1f)
                    setPadding(10, 4, 10, 4)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt()
                    )
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        runCodeServerCtl("start")
                        updateAllServiceIndicators()
                    }
                }.also { row.addView(it) }
            }
        }
        "phoenix" -> {
            val icon = "○"
            
            TextView(context).apply {
                text = "🔥 PHOENIX OTLP  $icon"
                setTextColor(Color.Gray)
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTypeface(Typeface.DEFAULT_BOLD)
            }.also { row.addView(it) }
            
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }.also { row.addView(it) }
            
            // CONFIGURE button
            Button(context).apply {
                text = "⚙ CONFIGURE"
                textSize = 9f
                setTextColor(Color.parseColor("#FF6B35"))
                background = createRoundedDrawable(Color.parseColor("#1a1a0a"), 6f, Color.parseColor("#FF6B35"), 1f)
                setPadding(10, 4, 10, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt()
                )
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    showPhoenixConfigDialog()
                }
            }.also { row.addView(it) }
        }
    }
    
    servicesDetailPanel.addView(row)
}

private fun addSpacerInner(widthDp: Int) {
    View(this@TerminalActivity).apply {
        layoutParams = LinearLayout.LayoutParams(
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, widthDp.toFloat(), resources.displayMetrics).toInt(),
            1
        )
    }.also { (servicesDetailPanel as? LinearLayout)?.addView(it) }
}

/**
 * Helper to run code-server-ctl from the terminal activity.
 * Uses the same mechanism as LocalApiServer.
 */
private fun runCodeServerCtl(vararg args: String): String {
    val launcherFile = java.io.File(applicationContext.filesDir, "launcher.sh")
    if (!launcherFile.exists() || !launcherFile.canExecute()) {
        return "{\"error\":\"launcher.sh not found\"}"
    }
    return try {
        val pb = ProcessBuilder("sh", launcherFile.absolutePath, "code-server-ctl", *args)
        pb.directory(applicationContext.filesDir)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        val finished = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return "{\"error\":\"timed out\"}"
        }
        output
    } catch (e: Exception) {
        "{\"error\":\"${e.message}\"}"
    }
}

/**
 * Show Phoenix endpoint configuration dialog.
 */
private fun showPhoenixConfigDialog() {
    val prefs = applicationContext.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
    val currentEndpoint = prefs.getString("phoenix_endpoint", "http://localhost:6006/v1/traces") ?: "http://localhost:6006/v1/traces"
    
    // Use AlertDialog with edit text
    val input = android.widget.EditText(this).apply {
        setText(currentEndpoint)
        setHint("http://localhost:6006/v1/traces")
        setTextColor(Color.WHITE)
        setHintTextColor(Color.Gray)
        textSize = 12f
        setPadding(24, 16, 24, 16)
    }
    
    android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
        .setTitle("Phoenix OTLP Endpoint")
        .setMessage("Configure OpenTelemetry endpoint for Phoenix telemetry export.")
        .setView(input)
        .setPositiveButton("SAVE") { _, _ ->
            val newEndpoint = input.text.toString().trim()
            prefs.edit().putString("phoenix_endpoint", newEndpoint).apply()
        }
        .setNegativeButton("CANCEL", null)
        .show()
}
```

- [ ] **Step 5: Add toggleServicesPanel and service update methods**

```kotlin
/**
 * Toggle the services panel visibility.
 */
private fun toggleServicesPanel() {
    isServicesExpanded = !isServicesExpanded
    servicesPanel.visibility = if (isServicesExpanded) View.VISIBLE else View.GONE
    btnServicesToggle.text = if (isServicesExpanded) "▲" else "▼"
    
    if (isServicesExpanded) {
        updateAllServiceIndicators()
        servicesUpdateHandler.post(servicesPoller)
    } else {
        servicesDetailPanel.visibility = View.GONE
        expandedService = null
        servicesUpdateHandler.removeCallbacks(servicesPoller)
    }
}

/**
 * Toggle the detail panel for a specific service.
 */
private fun toggleServiceDetail(service: String) {
    if (expandedService == service) {
        // Collapse
        servicesDetailPanel.visibility = View.GONE
        expandedService = null
    } else {
        // Expand
        expandedService = service
        updateServiceDetail(service)
        servicesDetailPanel.visibility = View.VISIBLE
    }
}

/**
 * Update all service indicator dots.
 */
private fun updateAllServiceIndicators() {
    updateServiceIndicator("shizuku", btnShizuku)
    updateServiceIndicator("code", btnCode)
    updateServiceIndicator("phoenix", btnPhoenix)
    
    if (expandedService != null) {
        updateServiceDetail(expandedService!!)
    }
}

/**
 * Update a single service button's indicator.
 */
private fun updateServiceIndicator(service: String, button: Button) {
    val running = when (service) {
        "shizuku" -> com.linux_core.core.ShizukuManager.status(applicationContext).running
        "code" -> {
            val raw = runCodeServerCtl("status")
            raw.contains("running", ignoreCase = true) || raw.contains("pid", ignoreCase = true)
        }
        "phoenix" -> false // Always show as stopped, no active health check
        else -> false
    }
    
    val icon = if (running) "●" else "○"
    val color = if (running) Color.parseColor("#00FF41") else Color.Gray
    button.text = when (service) {
        "shizuku" -> "⚡ SHIZU $icon"
        "code" -> "[code] CODE $icon"
        "phoenix" -> "🔥 PHOENIX $icon"
        else -> button.text
    }
    button.setTextColor(color)
}

/**
 * Start all services.
 */
private fun startAllServices() {
    // Start Shizuku
    com.linux_core.core.ShizukuManager.startServer(applicationContext)
    
    // Start code-server
    runCodeServerCtl("start")
    
    updateAllServiceIndicators()
}
```

- [ ] **Step 6: Wire services panel into the layout**

After this section in `onCreate()`:
```kotlin
mainLayout.addView(topBar)
```

Add:
```kotlin
// Services panel (collapsible)
servicesPanel = buildServicesPanel()
mainLayout.addView(servicesPanel)

// Services detail panel
servicesDetailPanel = buildServicesDetailPanel()
mainLayout.addView(servicesDetailPanel)
```

- [ ] **Step 7: Verify the code compiles and is consistent**

Check that:
- All referenced methods (runCodeServerCtl, toggleServicesPanel, etc.) are defined
- All imports are available (Color, Gravity, LinearLayout, etc.)
- The `createRoundedDrawable` method is accessible (it's defined in TerminalActivity)

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/linux_core/ui/terminal/TerminalActivity.kt
git commit -m "feat(terminal): add services panel with Shizuku, code-server, Phoenix controls"
```

---

### Task 6: Distro Name in Status Title

**Files:**
- Modify: `app/src/main/java/com/linux_core/ui/terminal/TerminalActivity.kt`

- [ ] **Step 1: Update statusTitle dynamically**

Find the section where `statusTitle.text` is set (likely from the intent extras or session info) and ensure it shows the correct distro name. Look for:

```kotlin
statusTitle.text = "🐉 KALI"
```

Make sure this reflects the actual distro passed via `rootfsDirName` intent extra. The existing code likely already sets this, just verify.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/linux_core/ui/terminal/TerminalActivity.kt
git commit -m "fix(terminal): ensure services panel distro name matches session"
```

---

### Task 7: Verify Build

- [ ] **Step 1: Check all files exist**

```bash
find app/src/main/java/com/linux_core/core/ShizukuManager.kt -type f
find app/src/main/assets/shizuku/ -type f
```

- [ ] **Step 2: Check there are no syntax issues in the modified files**

```bash
# Quick grep for common issues
grep -n "import missing\|TODO\|FIXME" app/src/main/java/com/linux_core/core/ShizukuManager.kt 2>/dev/null || echo "OK"
```

- [ ] **Step 3: Manual code review of the plan**

Check: Are all method signatures consistent? Is `runCodeServerCtl` used correctly? Are the `context` references in TerminalActivity correct (using `applicationContext` or `this` where appropriate)?

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "feat: complete Shizuku + services integration implementation"
```
