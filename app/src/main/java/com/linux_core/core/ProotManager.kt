package com.linux_core.core

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files

class ProotConfig(
    val command: Array<String>,
    val cwd: String,
    val env: Array<String>,
    val prootPath: String,
    val rootfsDir: String
)

object ProotManager {
    private const val TAG = "ProotManager"

    fun setupProotEnvironment(
        context: Context,
        rootfsDirName: String = "kali-arm64",
        mountStorage: Boolean = false
    ): ProotConfig {
        val rootDir = context.filesDir
        val rootfsDir = File(rootDir, rootfsDirName)
        val homeDir = File(rootfsDir, "root")
        val tmpDir = File(rootDir, "tmp")

        // Pre-emptively fix permissions of critical bind mount target directories
        val criticalDirs = listOf(
            "system",
            "dev",
            "proc",
            "sys",
            "tmp",
            "root",
            "sdcard",
            "bin",
            "usr/bin",
            "usr/sbin",
            "sbin",
            "lib",
            "lib64",
            "usr/lib"
        )
        for (dirName in criticalDirs) {
            val dir = File(rootfsDir, dirName)
            if (dir.exists()) {
                dir.setReadable(true, false)
                dir.setWritable(true, false)
                dir.setExecutable(true, false)
            } else {
                dir.mkdirs()
            }
        }

        if (!homeDir.exists()) homeDir.mkdirs()
        if (!tmpDir.exists()) tmpDir.mkdirs()

        File(homeDir, ".hushlogin").apply { if (!exists()) createNewFile() }

        // .bootstrap_required is the trigger for entrypoint to run bootstrap.sh.
        val setupDoneFile = File(homeDir, ".setup_done")
        val bootstrapRequired = File(homeDir, ".bootstrap_required")
        
        // If it's a fresh extraction (no .setup_done), ensure bootstrap runs
        if (!setupDoneFile.exists()) {
            try {
                if (!bootstrapRequired.exists()) bootstrapRequired.createNewFile()
                Log.i(TAG, "Fresh install detected, created .bootstrap_required")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create bootstrap sentinel: ${e.message}")
            }
        }

        val distroId = if (rootfsDirName.contains("parrot")) "parrot" else "kali"
        createMasterScript(homeDir, distroId)
        createEntrypointScript(homeDir)
        fixLdLinuxSymlinks(rootfsDir)

        val suffix = if (rootfsDir.name.contains("arm64")) "aarch64" else "arm"

        // Deploy dynamic binaries
        val prootFile = File(context.filesDir, "proot")
        val loaderFile = File(context.filesDir, "loader")
        val libtallocFile = File(context.filesDir, "libtalloc.so.2")

        synchronized(this) {
            if (!prootFile.exists() || prootFile.length() == 0L) {
                try {
                    if (prootFile.exists()) prootFile.delete()
                    context.assets.open("proot-$suffix").use { input ->
                        prootFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    prootFile.setExecutable(true, false)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to deploy proot: ${e.message}")
                    if (!prootFile.exists()) throw e
                }
            }
            if (!loaderFile.exists() || loaderFile.length() == 0L) {
                try {
                    if (loaderFile.exists()) loaderFile.delete()
                    context.assets.open("loader-$suffix").use { input ->
                        loaderFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    loaderFile.setExecutable(true, false)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to deploy loader: ${e.message}")
                    if (!loaderFile.exists()) throw e
                }
            }
            if (!libtallocFile.exists() || libtallocFile.length() == 0L) {
                try {
                    if (libtallocFile.exists()) libtallocFile.delete()
                    context.assets.open("libtalloc-$suffix.so").use { input ->
                        libtallocFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    libtallocFile.setReadable(true, false)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to deploy libtalloc: ${e.message}")
                    if (!libtallocFile.exists()) throw e
                }
            }
        }

        val launcherFile = File(rootDir, "launcher.sh")
        val scriptContent = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("export PROOT_TMP_DIR=\"${tmpDir.absolutePath}\"")
            appendLine("export HOME=/root")
            appendLine("export USER=root")
            appendLine("export LOGNAME=root")
            appendLine("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            appendLine("export TERM=xterm-256color")
            appendLine("export LANG=C.UTF-8")

            // USE LD_LIBRARY_PATH ONLY, NO LD_PRELOAD (fixes the noise)
            appendLine("export PROOT_LOADER=\"${loaderFile.absolutePath}\"")
            appendLine("export LD_LIBRARY_PATH=\"${rootDir.absolutePath}\"")
            appendLine("unset LD_PRELOAD")

            appendLine("cd \"${rootDir.absolutePath}\"")

            val baseFlags =
                "-v 0 --kill-on-exit --link2symlink -0 -r ${rootfsDir.absolutePath} -b /dev -b /proc -b /sys -b /system -w /root"
            val sdcardMount = if (mountStorage) " -b /sdcard" else ""

            appendLine("PROOT_FLAGS=\"${baseFlags}${sdcardMount}\"")

            // Clean start inside the guest
            appendLine("exec ${prootFile.absolutePath} \$PROOT_FLAGS /bin/bash /root/entrypoint.sh")
        }
        if (launcherFile.exists()) launcherFile.delete()
        launcherFile.writeText(scriptContent)
        launcherFile.setExecutable(true, false)

        return ProotConfig(
            command = arrayOf("/system/bin/sh", launcherFile.absolutePath),
            cwd = rootDir.absolutePath,
            env = emptyArray(),
            prootPath = prootFile.absolutePath,
            rootfsDir = rootfsDir.absolutePath
        )
    }

    private fun createMasterScript(homeDir: File, distroId: String) {
        val masterFile = File(homeDir, "bootstrap.sh")
        val script = buildString {
            appendLine("#!/bin/bash")
            // CRITICAL: Do NOT use set -e. dpkg WILL fail on some postinst scripts
            // inside PRoot (no systemd, no /proc full access). We handle each
            // failure gracefully with || true and force-all flags.
            appendLine("export DEBIAN_FRONTEND=noninteractive")
            appendLine("echo '========================================'")
            appendLine("echo '[*] ${distroId.uppercase()} BOOTSTRAP STARTING... '")
            appendLine("echo '========================================'")

            appendLine("# 0. Clean up stale dpkg locks from any previous failed run")
            appendLine("echo '[*] Cleaning stale dpkg/apt locks...'")
            appendLine("rm -f /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock /var/cache/apt/archives/lock /var/lib/apt/lists/lock 2>/dev/null || true")
            appendLine("rm -f /var/lib/dpkg/triggers/* 2>/dev/null || true")

            appendLine("# CRITICAL: Mark setup as started IMMEDIATELY so a mid-run crash")
            appendLine("# does NOT cause infinite bootstrap re-runs on next launch.")
            appendLine("touch /root/.setup_done 2>/dev/null || true")

            appendLine("# 1. Fix Networking")
            appendLine("echo 'nameserver 8.8.8.8' > /etc/resolv.conf")

            if (distroId == "kali") {
                appendLine("echo 'deb [trusted=yes] https://kali.download/kali kali-rolling main contrib non-free non-free-firmware' > /etc/apt/sources.list")
            } else {
                appendLine("echo 'deb [trusted=yes] https://deb.parrot.sh/parrot parrot main contrib non-free' > /etc/apt/sources.list")
                appendLine("# Parrot-specific: Import GPG key")
                appendLine("mkdir -p /etc/apt/trusted.gpg.d")
                appendLine("wget -qO /etc/apt/trusted.gpg.d/parrot-archive-key.asc http://archive.parrotsec.org/parrot/misc/archive.gpg || true")
            }

            appendLine("# 2. Set up system call wrappers via dpkg-divert")
            appendLine("echo '[*] Setting up system call wrappers (unconditional)...'")

            // Ensure basic tools exist
            appendLine("mkdir -p /usr/sbin /usr/bin /sbin /bin")

            // Extended list of commands known to cause dpkg postinst failures inside PRoot
            appendLine("for cmd in systemctl service update-rc.d invoke-rc.d dpkg-preconfigure setcap getcap capsh sysctl udevadm modprobe rmmod insmod dmidecode systemd-detect-virt timedatectl hostnamectl localectl loginctl journalctl ldconfig ldconfig.real adduser addgroup mount umount systemd-tmpfiles deb-systemd-helper policy-rc.d start-stop-daemon initctl checkrestart aa-status apparmor_status dmesg; do")
            appendLine("  for prefix in /usr/sbin /sbin /usr/bin /bin; do")
            appendLine("    path=\"\$prefix/\$cmd\"")
            appendLine("    # Unconditional diversion: move away real binary (if it exists) and replace with /bin/true")
            appendLine("    dpkg-divert --add --local --rename --divert \"\$path.distrib\" \"\$path\" 2>/dev/null || true")
            appendLine("    ln -sf /bin/true \"\$path\" 2>/dev/null || true")
            appendLine("  done")
            appendLine("done")

            appendLine("# 2.5. Create PERMANENT dpkg force configuration + APT auto-patch hook")
            appendLine("#    This ensures ALL future package installs (not just bootstrap) are protected.")
            appendLine("echo '[*] Creating permanent dpkg force config and APT post-invoke hook...'")
            appendLine("mkdir -p /etc/dpkg/dpkg.cfg.d /etc/apt/apt.conf.d /usr/local/bin")
            appendLine("")
            appendLine("# /etc/dpkg/dpkg.cfg.d/01-force-all: force ALL dpkg operations to skip dangerous checks")
            appendLine("cat > /etc/dpkg/dpkg.cfg.d/01-force-all << 'DPKGEOF'")
            appendLine("# Force dpkg inside PRoot - skip all dangerous checks")
            appendLine("force-all")
            appendLine("DPKGEOF")
            appendLine("")
            appendLine("# /etc/apt/apt.conf.d/99-patch-scripts: calls /bin/true symlink loop after EVERY dpkg operation")
            appendLine("echo 'DPkg::Post-Invoke {\"for s in /var/lib/dpkg/info/*.postinst /var/lib/dpkg/info/*.preinst; do [ -f \\\"\$s\\\" ] && [ ! -L \\\"\$s\\\" ] && ln -sf /bin/true \\\"\$s\\\"; done\";};' > /etc/apt/apt.conf.d/99-patch-scripts")
            appendLine("")
            appendLine("# Also apply dpkg.cfg settings immediately for current session")
            appendLine("export DPKG_FORCE=all,depends,overwrite,bad-path")

            appendLine("# 3. Blanket-patch ALL existing maintainer scripts to /bin/true")
            appendLine("#    This prevents dpkg failures from any package's postinst/preinst/prerm/postrm")
            appendLine("#    calling ldconfig, systemctl, or other system commands that don't work inside PRoot.")
            appendLine("echo '[*] Blanket-patching all maintainer scripts to /bin/true symlinks...'")
            appendLine("mkdir -p /var/lib/dpkg/info")
            appendLine("for script in /var/lib/dpkg/info/*.postinst /var/lib/dpkg/info/*.preinst /var/lib/dpkg/info/*.prerm /var/lib/dpkg/info/*.postrm; do")
            appendLine("  if [ -f \"\$script\" ] && [ ! -L \"\$script\" ]; then")
            appendLine("    ln -sf /bin/true \"\$script\" 2>/dev/null || true")
            appendLine("  fi")
            appendLine("done")

            if (distroId == "kali") {
                appendLine("# Kali-specific: install archive keyring first, then do full apt operations")
                appendLine("echo '[*] Installing Kali archive keyring...'")
                appendLine("apt-get install -y --allow-unauthenticated kali-archive-keyring 2>&1 || true")
            }

            appendLine("# 3.5. Pre-apt dpkg repair: audit + configure + fix-broken")
            appendLine("echo '[*] Running pre-apt dpkg audit and repair...'")
            appendLine("dpkg --audit 2>&1 | head -20 || true")
            appendLine("dpkg --configure -a --force-all --force-depends --force-overwrite 2>&1 || true")
            appendLine("apt --fix-broken install -y --allow-unauthenticated -o Dpkg::Options::=\"--force-confold\" -o Dpkg::Options::=\"--force-confdef\" 2>&1 || true")

            appendLine("# 4. Update and Install")
            appendLine("echo '[*] Updating package lists...'")
            appendLine("apt update 2>&1 || true")

            appendLine("# 4.1 Fix broken dpkg state after update")
            appendLine("echo '[*] Repairing broken packages after update...'")
            appendLine("dpkg --configure -a --force-all --force-depends --force-overwrite 2>&1 || true")
            appendLine("apt --fix-broken install -y --allow-unauthenticated -o Dpkg::Options::=\"--force-confold\" -o Dpkg::Options::=\"--force-confdef\" 2>&1 || true")

            if (distroId == "kali") {
                appendLine("echo '[*] Installing Kali packages (zsh, plugins, kali-defaults)...'")
                // Install lighter packages first, then kali-defaults separately
                appendLine("apt install -y --allow-unauthenticated -o Dpkg::Options::=\"--force-confold\" -o Dpkg::Options::=\"--force-confdef\" zsh zsh-syntax-highlighting zsh-autosuggestions curl git 2>&1 || true")
                appendLine("dpkg --configure -a --force-all --force-depends --force-overwrite 2>&1 || true")
                appendLine("apt install -y --allow-unauthenticated -o Dpkg::Options::=\"--force-confold\" -o Dpkg::Options::=\"--force-confdef\" kali-defaults 2>&1 || true")
            } else {
                appendLine("echo '[*] Installing Parrot packages (zsh, plugins)...'")
                appendLine("apt install -y --allow-unauthenticated -o Dpkg::Options::=\"--force-confold\" -o Dpkg::Options::=\"--force-confdef\" zsh zsh-syntax-highlighting zsh-autosuggestions curl git 2>&1 || true")
            }

            appendLine("# 5. Re-patch maintainer scripts + Re-apply mock wrappers + Force-configure loop")
            appendLine("#    Phase A: Re-patch ALL maintainer scripts to /bin/true symlinks")
            appendLine("echo '[*] Re-patching all maintainer scripts to /bin/true...'")
            appendLine("for script in /var/lib/dpkg/info/*.postinst /var/lib/dpkg/info/*.preinst /var/lib/dpkg/info/*.prerm /var/lib/dpkg/info/*.postrm; do")
            appendLine("  if [ -f \"\$script\" ] && [ ! -L \"\$script\" ]; then")
            appendLine("    ln -sf /bin/true \"\$script\" 2>/dev/null || true")
            appendLine("  fi")
            appendLine("done")

            appendLine("#    Phase B: Re-apply mock wrappers unconditionally")
            appendLine("for cmd in systemctl service update-rc.d invoke-rc.d dpkg-preconfigure setcap getcap capsh sysctl udevadm modprobe rmmod insmod dmidecode systemd-detect-virt timedatectl hostnamectl localectl loginctl journalctl ldconfig ldconfig.real adduser addgroup mount umount systemd-tmpfiles deb-systemd-helper policy-rc.d start-stop-daemon initctl; do")
            appendLine("  for prefix in /usr/sbin /sbin /usr/bin /bin; do")
            appendLine("    path=\"\$prefix/\$cmd\"")
            appendLine("    ln -sf /bin/true \"\$path\" 2>/dev/null || true")
            appendLine("  done")
            appendLine("done")

            appendLine("#    Phase C: Force-configure dpkg in a WHILE loop until stable (max 10 iterations)")
            appendLine("echo '[*] Force-configuring all packages (up to 10 passes)...'")
            appendLine("MAX_RETRIES=10; COUNT=0")
            appendLine("while [ \$COUNT -lt \$MAX_RETRIES ]; do")
            appendLine("  RESULT=0")
            appendLine("  dpkg --configure -a --force-all --force-depends --force-overwrite 2>&1 || RESULT=\$?")
            appendLine("  apt --fix-broken install -y --allow-unauthenticated -o Dpkg::Options::=\"--force-confold\" -o Dpkg::Options::=\"--force-confdef\" 2>&1 || true")
            appendLine("  if [ \$RESULT -eq 0 ]; then")
            appendLine("    echo \"[*] dpkg state clean after \$COUNT passes.\"")
            appendLine("    break")
            appendLine("  fi")
            appendLine("  COUNT=\$((COUNT + 1))")
            appendLine("done")
            appendLine("if [ \$COUNT -eq \$MAX_RETRIES ]; then")
            appendLine("  echo '[!] dpkg state still has issues after \$MAX_RETRIES passes - continuing anyway...'")
            appendLine("fi")

            val defaultUser = distroId // Uses 'kali' for Kali, 'parrot' for Parrot
            appendLine("# 7. Configure '${defaultUser}' user access")
            appendLine("echo '[*] Configuring ${defaultUser} user...'")
            appendLine("id -u ${defaultUser} &>/dev/null || useradd -m -s /usr/bin/zsh ${defaultUser} 2>/dev/null || true")
            appendLine("passwd -d ${defaultUser} 2>/dev/null || true")
            appendLine("usermod -aG sudo ${defaultUser} 2>/dev/null || (groupadd sudo 2>/dev/null && usermod -aG sudo ${defaultUser} 2>/dev/null) || true")
            appendLine("echo '${defaultUser} ALL=(ALL:ALL) NOPASSWD: ALL' > /etc/sudoers.d/${defaultUser} 2>/dev/null || true")
            appendLine("chmod 0440 /etc/sudoers.d/${defaultUser} 2>/dev/null || true")

            appendLine("# 8. Set ZSH as default shell and enable syntax highlighting + autosuggestions")
            appendLine("chsh -s /usr/bin/zsh root 2>/dev/null || true")
            
            // Shared ZSH config logic
            appendLine("setup_zsh_for_user() {")
            appendLine("  local target_user=\"\$1\"")
            appendLine("  local target_home=\"\$2\"")
            appendLine("  echo \"[*] Configuring ZSH for \$target_user...\"")
            appendLine("  [ -f /etc/skel/.zshrc ] && cp -n /etc/skel/.zshrc \"\$target_home/.zshrc\"")
            appendLine("  [ ! -f \"\$target_home/.zshrc\" ] && touch \"\$target_home/.zshrc\"")
            
            // Prompt configuration based on distro
            if (distroId == "parrot") {
                appendLine("  grep -q 'PROMPT=' \"\$target_home/.zshrc\" || echo \"export PROMPT='%F{cyan}%n@parrot%f:%F{blue}%~%f# '\" >> \"\$target_home/.zshrc\"")
            } else {
                appendLine("  grep -q 'PROMPT=' \"\$target_home/.zshrc\" || echo \"export PROMPT='%F{red}%n@kali%f:%F{blue}%~%f# '\" >> \"\$target_home/.zshrc\"")
            }

            appendLine("  # Enable syntax highlighting if installed")
            appendLine("  if [ -f /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh ]; then")
            appendLine("    grep -q 'zsh-syntax-highlighting' \"\$target_home/.zshrc\" || echo 'source /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh' >> \"\$target_home/.zshrc\"")
            appendLine("  fi")
            appendLine("  # Enable autosuggestions if installed")
            appendLine("  if [ -f /usr/share/zsh-autosuggestions/zsh-autosuggestions.zsh ]; then")
            appendLine("    grep -q 'zsh-autosuggestions' \"\$target_home/.zshrc\" || echo 'source /usr/share/zsh-autosuggestions/zsh-autosuggestions.zsh' >> \"\$target_home/.zshrc\"")
            appendLine("  fi")
            appendLine("  chown \"\$target_user:\$target_user\" \"\$target_home/.zshrc\" 2>/dev/null || true")
            appendLine("}")

            appendLine("setup_zsh_for_user root /root")
            appendLine("setup_zsh_for_user ${defaultUser} /home/${defaultUser}")

            appendLine("# 10. Restore setuid bits for sudo and su (intercepted by proot .l2s)")
            appendLine("chmod 4755 /usr/bin/sudo /usr/bin/su /bin/su /bin/sudo 2>/dev/null || true")

            appendLine("# 11. Final cleanup and mark setup complete")
            appendLine("rm -f /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock 2>/dev/null || true")
            appendLine("touch /root/.setup_done 2>/dev/null || true")
            appendLine("echo '[+] ALL DONE! ${distroId.uppercase()} is ready.'")
            appendLine("rm -f -- \"\$0\" 2>/dev/null || true")
        }
        if (masterFile.exists()) masterFile.delete()
        masterFile.writeText(script)
        masterFile.setExecutable(true, false)
    }

    private fun createEntrypointScript(homeDir: File) {
        val entryFile = File(homeDir, "entrypoint.sh")
        val script = buildString {
            appendLine("#!/bin/bash")
            appendLine("unset LD_PRELOAD")
            appendLine("unset PROOT_LOADER")
            appendLine("# Clean stale locks in case bootstrap crashed mid-run")
            appendLine("rm -f /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock /var/cache/apt/archives/lock /var/lib/apt/lists/lock 2>/dev/null || true")
            appendLine("if [ -f /root/.bootstrap_required ]; then")
            appendLine("    echo '[*] Running first-time setup...'")
            appendLine("    /bin/bash /root/bootstrap.sh")
            appendLine("    rm -f /root/.bootstrap_required")
            appendLine("    echo '[*] First-time setup complete.'")
            appendLine("fi")
            appendLine("echo '[*] .setup_done already exists (created by Android).'")
            appendLine("echo '[*] Checking for missing zsh plugins...'")
            appendLine("if [ ! -f /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh ] || [ ! -f /usr/share/zsh-autosuggestions/zsh-autosuggestions.zsh ]; then")
            appendLine("    echo '[*] Installing missing zsh plugins...'")
            appendLine("    apt update >/dev/null 2>&1 && apt install -y zsh-syntax-highlighting zsh-autosuggestions >/dev/null 2>&1")
            appendLine("fi")
            appendLine("if [ -f /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh ]; then")
            appendLine("    [ -f /etc/skel/.zshrc ] && cp -n /etc/skel/.zshrc /root/.zshrc")
            appendLine("    grep -q 'FORCE_ZSH_HIGHLIGHT' /root/.zshrc 2>/dev/null || echo -e '\\n# FORCE_ZSH_HIGHLIGHT\\nsource /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh' >> /root/.zshrc")
            appendLine("    grep -q 'FORCE_ZSH_AUTOSUGGEST' /root/.zshrc 2>/dev/null || echo -e '\\n# FORCE_ZSH_AUTOSUGGEST\\nsource /usr/share/zsh-autosuggestions/zsh-autosuggestions.zsh' >> /root/.zshrc")
            appendLine("    for usr in kali parrot; do")
            appendLine("        if [ -d /home/\$usr ]; then")
            appendLine("            [ -f /etc/skel/.zshrc ] && cp -n /etc/skel/.zshrc /home/\$usr/.zshrc")
            appendLine("            grep -q 'FORCE_ZSH_HIGHLIGHT' /home/\$usr/.zshrc 2>/dev/null || echo -e '\\n# FORCE_ZSH_HIGHLIGHT\\nsource /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh' >> /home/\$usr/.zshrc")
            appendLine("            grep -q 'FORCE_ZSH_AUTOSUGGEST' /home/\$usr/.zshrc 2>/dev/null || echo -e '\\n# FORCE_ZSH_AUTOSUGGEST\\nsource /usr/share/zsh-autosuggestions/zsh-autosuggestions.zsh' >> /home/\$usr/.zshrc")
            appendLine("        fi")
            appendLine("    done")
            appendLine("fi")

            appendLine("# Restore setuid for existing installs")
            appendLine("chmod 4755 /usr/bin/sudo /usr/bin/su /bin/su /bin/sudo 2>/dev/null || true")

            appendLine("setup_zsh_config() {")
            appendLine("    local target_home=\"\$1\"")
            appendLine("    [ ! -f \"\$target_home/.zshrc\" ] && [ -f /etc/skel/.zshrc ] && cp /etc/skel/.zshrc \"\$target_home/.zshrc\"")
            appendLine("    [ ! -f \"\$target_home/.zshrc\" ] && touch \"\$target_home/.zshrc\"")
            appendLine("    if [ -f /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh ]; then")
            appendLine("        grep -q 'zsh-syntax-highlighting' \"\$target_home/.zshrc\" || echo 'source /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh' >> \"\$target_home/.zshrc\"")
            appendLine("    fi")
            appendLine("    if [ -f /usr/share/zsh-autosuggestions/zsh-autosuggestions.zsh ]; then")
            appendLine("        grep -q 'zsh-autosuggestions' \"\$target_home/.zshrc\" || echo 'source /usr/share/zsh-autosuggestions/zsh-autosuggestions.zsh' >> \"\$target_home/.zshrc\"")
            appendLine("    fi")
            appendLine("}")

            appendLine("setup_zsh_config /root")
            appendLine("[ -d /home/kali ] && setup_zsh_config /home/kali")
            appendLine("[ -d /home/parrot ] && setup_zsh_config /home/parrot")

            appendLine("echo '[*] Starting session...'")
            appendLine("if [ -x /usr/bin/zsh ]; then")
            appendLine("    exec /usr/bin/zsh --login")
            appendLine("elif [ -x /bin/zsh ]; then")
            appendLine("    exec /bin/zsh --login")
            appendLine("else")
            appendLine("    exec /bin/bash --login")
            appendLine("fi")
        }
        if (entryFile.exists()) entryFile.delete()
        entryFile.writeText(script)
        entryFile.setExecutable(true, false)
    }

    private fun fixLdLinuxSymlinks(rootfsDir: File) {
        val paths = listOf(
            "lib/ld-linux-aarch64.so.1",
            "lib64/ld-linux-aarch64.so.1",
            "usr/lib/ld-linux-aarch64.so.1",
            "bin/sh",
            "usr/bin/sh",
            "bin/bash",
            "usr/bin/bash"
        )
        for (relPath in paths) {
            val linkFile = File(rootfsDir, relPath)
            if (linkFile.exists() && Files.isSymbolicLink(linkFile.toPath())) {
                try {
                    val target = android.system.Os.readlink(linkFile.absolutePath)
                    val resolvedFile = if (target.startsWith("/")) {
                        File(rootfsDir, target.substring(1)).canonicalFile
                    } else {
                        File(linkFile.parentFile, target).canonicalFile
                    }

                    if (resolvedFile.exists() && resolvedFile.isFile) {
                        Log.i(TAG, "Fixing symlink $relPath -> ${resolvedFile.absolutePath}")
                        linkFile.delete()
                        resolvedFile.copyTo(linkFile)
                        linkFile.setExecutable(true, false)
                        linkFile.setReadable(true, false)
                    } else if (relPath.endsWith("sh")) {
                        // Fallback for /bin/sh if symlink is broken
                        val bash = File(rootfsDir, "bin/bash")
                        if (bash.exists()) {
                            Log.i(TAG, "Broken sh symlink, copying bash to $relPath")
                            linkFile.delete()
                            bash.copyTo(linkFile)
                            linkFile.setExecutable(true, false)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fix symlink $relPath: ${e.message}")
                }
            }
        }
    }
}
