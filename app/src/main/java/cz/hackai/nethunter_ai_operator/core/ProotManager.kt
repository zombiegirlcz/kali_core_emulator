package cz.hackai.nethunter_ai_operator.core

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

    fun setupProotEnvironment(context: Context, rootfsDirName: String = "kali-arm64", mountStorage: Boolean = false): ProotConfig {
        val rootDir = context.filesDir
        val rootfsDir = File(rootDir, rootfsDirName)
        val homeDir = File(rootfsDir, "root")
        val tmpDir = File(rootDir, "tmp")

        // Pre-emptively fix permissions of critical bind mount target directories
        val criticalDirs = listOf("system", "dev", "proc", "sys", "tmp", "root", "sdcard")
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
        
        // Do not force cleanup of the sentinel so that bootstrap runs only once!
        val sentinel = File(homeDir, ".setup_done")

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
            
            val baseFlags = "-v 0 --kill-on-exit --link2symlink -0 -r ${rootfsDir.absolutePath} -b /dev -b /proc -b /sys -b /system -w /root"
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
            appendLine("set -e")
            appendLine("export DEBIAN_FRONTEND=noninteractive")
            appendLine("echo '========================================'")
            appendLine("echo '[*] ${distroId.uppercase()} BOOTSTRAP STARTING... '")
            appendLine("echo '========================================'")
            
            appendLine("# 1. Fix Networking")
            appendLine("echo 'nameserver 8.8.8.8' > /etc/resolv.conf")
            
            if (distroId == "kali") {
                appendLine("echo 'deb https://kali.download/kali kali-rolling main contrib non-free non-free-firmware' > /etc/apt/sources.list")
            } else {
                appendLine("echo 'deb [trusted=yes] https://deb.parrot.sh/parrot parrot main contrib non-free' > /etc/apt/sources.list")
            }

            // Wrappers are pre-installed by Kotlin code at /usr/sbin, /sbin, /usr/bin, /bin
            // We use dpkg-divert to ensure that apt packages do not overwrite our mock wrappers during installation!
            appendLine("# 2. Set up system call wrappers via dpkg-divert")
            appendLine("echo '[*] Setting up system call wrappers...'")
            appendLine("MOCK_SCRIPT='/usr/sbin/systemctl'")
            appendLine("mkdir -p /usr/sbin")
            appendLine("cat > \"\$MOCK_SCRIPT\" << 'MOCKEOF'")
            appendLine("#!/usr/bin/env bash")
            appendLine("echo \"Mocked call: \$0 \$*\"")
            appendLine("exit 0")
            appendLine("MOCKEOF")
            appendLine("chmod +x \"\$MOCK_SCRIPT\"")
            appendLine("for cmd in systemctl service update-rc.d invoke-rc.d dpkg-preconfigure setcap getcap capsh sysctl udevadm modprobe rmmod insmod dmidecode systemd-detect-virt timedatectl hostnamectl localectl loginctl journalctl ldconfig ldconfig.real adduser addgroup; do")
            appendLine("  for prefix in /usr/sbin /sbin /usr/bin /bin; do")
            appendLine("    path=\"\$prefix/\$cmd\"")
            appendLine("    dpkg-divert --add --local --rename --divert \"\$path.distrib\" \"\$path\" 2>/dev/null || true")
            appendLine("    cp \"\$MOCK_SCRIPT\" \"\$path\" 2>/dev/null || true")
            appendLine("    chmod +x \"\$path\" 2>/dev/null || true")
            appendLine("  done")
            appendLine("done")

            appendLine("# 3. Patch known failing postinst scripts")
            val pkgsToPatch = if (distroId == "kali") {
                "sudo x11-common initramfs-tools kali-menu passwd login"
            } else {
                "sudo x11-common initramfs-tools passwd login"
            }
            appendLine("for pkg in \$pkgsToPatch; do")
            appendLine("  postinst=\"/var/lib/dpkg/info/\${pkg}.postinst\"")
            appendLine("  echo '#!/usr/bin/env bash' > \"\$postinst\"")
            appendLine("  echo 'exit 0' >> \"\$postinst\"")
            appendLine("  chmod +x \"\$postinst\" 2>/dev/null || true")
            appendLine("done")

            appendLine("# 4. Update and Install")
            appendLine("echo '[*] Updating system and installing packages...'")
            appendLine("apt update")
            
            appendLine("# 4.1 Fix broken dpkg state before installing new packages")
            appendLine("echo '[*] Repairing broken packages...'")
            appendLine("dpkg --configure -a 2>/dev/null || true")
            appendLine("apt --fix-broken install -y 2>/dev/null || true")
            
            if (distroId == "kali") {
                appendLine("apt install -y kali-defaults zsh zsh-syntax-highlighting curl git")
            } else {
                appendLine("apt install -y zsh zsh-syntax-highlighting curl git")
            }

            appendLine("# 5. Finalize dpkg state")
            appendLine("dpkg --configure -a 2>/dev/null || true")

            appendLine("# 6. Ensure wrappers are still applied")
            appendLine("for cmd in systemctl service update-rc.d invoke-rc.d dpkg-preconfigure setcap getcap capsh sysctl udevadm modprobe rmmod insmod dmidecode systemd-detect-virt timedatectl hostnamectl localectl loginctl journalctl ldconfig ldconfig.real adduser addgroup; do")
            appendLine("  for prefix in /usr/sbin /sbin /usr/bin /bin; do")
            appendLine("    path=\"\$prefix/\$cmd\"")
            appendLine("    cp \"\$MOCK_SCRIPT\" \"\$path\" 2>/dev/null || true")
            appendLine("  done")
            appendLine("done")

            if (distroId == "kali") {
                appendLine("# 7. Configure 'kali' user access")
                appendLine("echo '[*] Configuring kali user...'")
                appendLine("passwd -d kali 2>/dev/null || true")
                appendLine("usermod -aG sudo kali 2>/dev/null || (groupadd sudo 2>/dev/null && usermod -aG sudo kali 2>/dev/null) || true")
                appendLine("echo 'kali ALL=(ALL:ALL) NOPASSWD: ALL' > /etc/sudoers.d/kali 2>/dev/null || true")
                appendLine("chmod 0440 /etc/sudoers.d/kali 2>/dev/null || true")
            }

            appendLine("# 8. Set ZSH as default shell and enable syntax highlighting")
            appendLine("chsh -s /usr/bin/zsh root 2>/dev/null || true")
            appendLine("[ -f /etc/skel/.zshrc ] && cp -n /etc/skel/.zshrc /root/.zshrc")
            appendLine("grep -q 'FORCE_ZSH_HIGHLIGHT' /root/.zshrc 2>/dev/null || echo -e '\\n# FORCE_ZSH_HIGHLIGHT\\nsource /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh' >> /root/.zshrc")

            if (distroId == "kali") {
                appendLine("# 9. Copy ZSH config to kali user")
                appendLine("cp /root/.zshrc /home/kali/.zshrc 2>/dev/null || true")
                appendLine("chown -R kali:kali /home/kali/.zshrc 2>/dev/null || true")
            }

            appendLine("# 10. Restore setuid bits for sudo and su (intercepted by proot .l2s)")
            appendLine("chmod 4755 /usr/bin/sudo /usr/bin/su /bin/su /bin/sudo 2>/dev/null || true")

            appendLine("touch /root/.setup_done")
            appendLine("echo '[+] ALL DONE! ${distroId.uppercase()} is ready.'")
            appendLine("rm -- \"\$0\"")
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
            appendLine("if [ ! -f /root/.setup_done ]; then")
            appendLine("    /bin/bash /root/bootstrap.sh")
            appendLine("fi")
            appendLine("if [ ! -f /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh ]; then")
            appendLine("    echo '[*] Installing missing zsh-syntax-highlighting...'")
            appendLine("    apt update >/dev/null 2>&1 && apt install -y zsh-syntax-highlighting >/dev/null 2>&1")
            appendLine("fi")
            appendLine("if [ -f /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh ]; then")
            appendLine("    [ -f /etc/skel/.zshrc ] && cp -n /etc/skel/.zshrc /root/.zshrc")
            appendLine("    grep -q 'FORCE_ZSH_HIGHLIGHT' /root/.zshrc 2>/dev/null || echo -e '\\n# FORCE_ZSH_HIGHLIGHT\\nsource /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh' >> /root/.zshrc")
            appendLine("    if [ -d /home/kali ]; then")
            appendLine("        [ -f /etc/skel/.zshrc ] && cp -n /etc/skel/.zshrc /home/kali/.zshrc")
            appendLine("        grep -q 'FORCE_ZSH_HIGHLIGHT' /home/kali/.zshrc 2>/dev/null || echo -e '\\n# FORCE_ZSH_HIGHLIGHT\\nsource /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh' >> /home/kali/.zshrc")
            appendLine("    fi")
            appendLine("fi")
            
            appendLine("# Restore setuid for existing installs")
            appendLine("chmod 4755 /usr/bin/sudo /usr/bin/su /bin/su /bin/sudo 2>/dev/null || true")
            
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
        val paths = listOf("lib/ld-linux-aarch64.so.1", "lib64/ld-linux-aarch64.so.1", "usr/lib/ld-linux-aarch64.so.1")
        for (relPath in paths) {
            val ldLink = File(rootfsDir, relPath)
            if (ldLink.exists() && Files.isSymbolicLink(ldLink.toPath())) {
                try {
                    val target = android.system.Os.readlink(ldLink.absolutePath)
                    val resolvedFile = if (target.startsWith("/")) {
                        File(rootfsDir, target.substring(1)).canonicalFile
                    } else {
                        File(ldLink.parentFile, target).canonicalFile
                    }
                    if (resolvedFile.exists() && resolvedFile.isFile) {
                        ldLink.delete()
                        resolvedFile.copyTo(ldLink)
                        ldLink.setExecutable(true, false)
                        ldLink.setReadable(true, false)
                        Log.i(TAG, "Successfully resolved and replaced symlink $relPath with real binary")
                    } else {
                        Log.w(TAG, "Resolved file for symlink $relPath does not exist: ${resolvedFile.absolutePath}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fix symlink $relPath: ${e.message}")
                }
            }
        }
    }
}
