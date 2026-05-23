import sys

path = "app/src/main/java/cz/hackai/nethunter_ai_operator/ui/terminal/TerminalActivity.kt"
c = open(path, "r", encoding="utf-8").read()
print(f"Original length: {len(c)}")

# Update the doc comment and add check 4 for launcher script
old_checks_doc = """    /**
     * Run pre-flight checks before creating a [TerminalSession].
     *
     * Checks:
     *  1. rootfs directory exists
     *  2. rootfs/bin/bash exists (validates rootfs was extracted)
     *  3. proot binary exists and is executable
     *
     * @return An error message string if any check fails, or `null` if all pass.
     */"""

new_checks_doc = """    /**
     * Run pre-flight checks before creating a [TerminalSession].
     *
     * Checks:
     *  1. rootfs directory exists
     *  2. rootfs/bin/bash exists (validates rootfs was extracted)
     *  3. proot binary exists and is executable
     *  4. launcher script exists and is readable
     *
     * @return An error message string if any check fails, or `null` if all pass.
     */"""

c = c.replace(old_checks_doc, new_checks_doc)

# After check 3, add check 4 for launcher script
old_check3_end = """        Log.d(TAG, "Pre-flight check 3 PASSED: proot binary exists and is executable")

        return null // All checks passed"""

new_with_check4 = """        Log.d(TAG, "Pre-flight check 3 PASSED: proot binary exists and is executable")

        // ---- Check 4: launcher script exists and is readable ----
        val launcherScriptPath = if (config.command.size >= 2) config.command[1] else null
        if (launcherScriptPath != null) {
            val launcherFile = File(launcherScriptPath)
            if (!launcherFile.exists()) {
                Log.w(TAG, "Pre-flight FAILED: launcher script missing at $launcherScriptPath")
                return "Launcher script is missing.\\n\\n" +
                        "Expected at: $launcherScriptPath\\n\\n" +
                        "The launcher script may have failed to generate.\\n" +
                        "Try clearing app data and restarting."
            }
            if (!launcherFile.canRead()) {
                Log.w(TAG, "Pre-flight FAILED: launcher script exists but is not readable at $launcherScriptPath")
                return "Launcher script is not readable.\\n\\n" +
                        "Path: $launcherScriptPath\\n\\n" +
                        "Permission denied -- the script may have wrong permissions."
            }
            Log.d(TAG, "Pre-flight check 4 PASSED: launcher script exists and is readable at $launcherScriptPath")
        } else {
            Log.w(TAG, "Pre-flight check 4 SKIPPED: no launcher script path in command array")
        }

        return null // All checks passed"""

c = c.replace(old_check3_end, new_with_check4)

# Also update the startTerminalSession logging to clarify the new approach
old_start_log = """        Log.i(TAG, "Starting PRoot session with ${config.command.size} command args")
        Log.i(TAG, "PRoot command: ${config.command.joinToString(\" \")}")
        Log.i(TAG, "PRoot env vars: ${config.env.joinToString(\" \")}")
        Log.d(TAG, "Working directory (cwd): ${config.cwd}")"""

new_start_log = """        Log.i(TAG, "Starting PRoot session with ${config.command.size} command args")
        Log.i(TAG, "Shell: ${config.command[0]}, Launcher: ${if (config.command.size > 1) config.command[1] else "(none)"}")
        Log.i(TAG, "PRoot command: ${config.command.joinToString(\" \")}")
        Log.i(TAG, "PRoot env vars (set inside launcher script): ${config.env.size} entries (script-managed)")
        Log.d(TAG, "Working directory (cwd): ${config.cwd}")"""

c = c.replace(old_start_log, new_start_log)

# Also update the documentation in the launcher script section about env being empty
# (no additional changes needed since the code already works generically)

with open(path, "w", encoding="utf-8") as f:
    f.write(c)

print(f"New length: {len(c)}")
print("TerminalActivity.kt patched successfully")
