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

    fun setupProotEnvironment(context: Context): ProotConfig {
        val rootDir = context.filesDir

        val rootfsDir = when {
            File(rootDir, "kali-arm64").isDirectory -> File(rootDir, "kali-arm64")
            File(rootDir, "kali-armhf").isDirectory -> File(rootDir, "kali-armhf")
            else -> File(rootDir, "kali-arm64")
        }
        val homeDir = File(rootfsDir, "root")
        val tmpDir = File(rootDir, "tmp")

        if (!homeDir.exists()) homeDir.mkdirs()
        if (!tmpDir.exists()) tmpDir.mkdirs()
        
        File(homeDir, ".hushlogin").apply { if (!exists()) createNewFile() }
        
        // FORCE CLEANUP: delete sentinel to ensure bootstrap runs
        val sentinel = File(homeDir, ".setup_done")
        if (sentinel.exists()) sentinel.delete()

        createMasterScript(homeDir)
        createEntrypointScript(homeDir)
        fixLdLinuxSymlinks(rootfsDir)

        val suffix = if (rootfsDir.name.contains("arm64")) "aarch64" else "arm"
        
        // Deploy dynamic binaries
        val prootFile = File(context.filesDir, "proot")
        val loaderFile = File(context.filesDir, "loader")
        val libtallocFile = File(context.filesDir, "libtalloc.so.2")

        synchronized(this) {
            context.assets.open("proot-$suffix").use { input ->
                prootFile.outputStream().use { output -> input.copyTo(output) }
            }
            context.assets.open("loader-$suffix").use { input ->
                loaderFile.outputStream().use { output -> input.copyTo(output) }
            }
            context.assets.open("libtalloc-$suffix.so").use { input ->
                libtallocFile.outputStream().use { output -> input.copyTo(output) }
            }
            prootFile.setExecutable(true, false)
            loaderFile.setExecutable(true, false)
            libtallocFile.setReadable(true, false)
        }

        val launcherFile = File(rootDir, "launcher.sh")
        val scriptContent = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("export PROOT_TMP_DIR=\"${tmpDir.absolutePath}\"")
            appendLine("export HOME=/root")
            appendLine("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            appendLine("export TERM=xterm-256color")
            appendLine("export LANG=C.UTF-8")
            
            // USE LD_LIBRARY_PATH ONLY, NO LD_PRELOAD (fixes the noise)
            appendLine("export PROOT_LOADER=\"${loaderFile.absolutePath}\"")
            appendLine("export LD_LIBRARY_PATH=\"${rootDir.absolutePath}\"")
            appendLine("unset LD_PRELOAD")
            
            appendLine("cd \"${rootDir.absolutePath}\"")
            
            appendLine("PROOT_FLAGS=\"-v 0 --kill-on-exit --link2symlink -0 -r ${rootfsDir.absolutePath} -b /dev -b /proc -b /sys -b /system -w /root\"")
            
            // Clean start inside the guest
            appendLine("exec ${prootFile.absolutePath} \$PROOT_FLAGS /bin/bash /root/entrypoint.sh")
        }
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

    private fun createMasterScript(homeDir: File) {
        val masterFile = File(homeDir, "bootstrap.sh")
        val script = buildString {
            appendLine("#!/bin/bash")
            appendLine("export DEBIAN_FRONTEND=noninteractive")
            appendLine("echo '========================================'")
            appendLine("echo '[*] KALI LINUX BOOTSTRAP STARTING... '")
            appendLine("echo '========================================'")
            
            appendLine("# 1. Fix Networking")
            appendLine("echo 'nameserver 8.8.8.8' > /etc/resolv.conf")
            appendLine("echo 'deb https://kali.download/kali kali-rolling main contrib non-free non-free-firmware' > /etc/apt/sources.list")
            
            appendLine("# 2. Neutralize problematic scripts (PRoot workarounds)")
            appendLine("echo '[*] Neutralizing system scripts...'")
            appendLine("for cmd in update-rc.d systemctl invoke-rc.d dpkg-preconfigure; do")
            appendLine("  path=\"/usr/sbin/\$cmd\"")
            appendLine("  if [ ! -f \"\$path.orig\" ]; then")
            appendLine("    [ -f \"\$path\" ] && mv \"\$path\" \"\$path.orig\"")
            appendLine("    echo '#!/bin/sh' > \"\$path\"")
            appendLine("    echo 'exit 0' >> \"\$path\"")
            appendLine("    chmod +x \"\$path\"")
            appendLine("  fi")
            appendLine("done")

            appendLine("# 3. Specifically patch sudo and other failing postinst scripts")
            appendLine("for pkg in sudo x11-common initramfs-tools kali-menu; do")
            appendLine("  postinst=\"/var/lib/dpkg/info/\${pkg}.postinst\"")
            appendLine("  if [ -f \"\$postinst\" ]; then")
            appendLine("    echo '#!/bin/sh' > \"\$postinst\"")
            appendLine("    echo 'exit 0' >> \"\$postinst\"")
            appendLine("    chmod +x \"\$postinst\"")
            appendLine("  fi")
            appendLine("done")

            appendLine("# 4. Update and Install")
            appendLine("echo '[*] Updating system and installing ZSH...'")
            appendLine("apt update")
            appendLine("apt install -y kali-defaults zsh zsh-syntax-highlighting curl git")
            
            appendLine("# 5. Finalize dpkg state")
            appendLine("dpkg --configure -a")
            
            appendLine("# 6. Configure 'kali' user access")
            appendLine("echo '[*] Configuring kali user...'")
            appendLine("passwd -d kali")
            appendLine("usermod -aG sudo kali 2>/dev/null || groupadd sudo && usermod -aG sudo kali")
            appendLine("echo 'kali ALL=(ALL:ALL) NOPASSWD: ALL' > /etc/sudoers.d/kali")
            appendLine("chmod 0440 /etc/sudoers.d/kali")

            appendLine("# 7. Configure environment")
            appendLine("cat <<EOF > ~/.zshrc")
            appendLine("source /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh")
            appendLine("export PS1='%n@kali:%~# '")
            appendLine("alias ls='ls --color=auto'")
            appendLine("alias ll='ls -la'")
            appendLine("EOF")
            
            appendLine("touch /root/.setup_done")
            appendLine("echo '[+] ALL DONE! Kali is ready.'")
            appendLine("rm -- \"\$0\"")
        }
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
            appendLine("echo '[*] Starting session...'")
            appendLine("exec /usr/bin/zsh --login 2>/dev/null || exec /bin/bash --login")
        }
        entryFile.writeText(script)
        entryFile.setExecutable(true, false)
    }

    private fun fixLdLinuxSymlinks(rootfsDir: File) {
        val ldLink = File(rootfsDir, "lib/ld-linux-aarch64.so.1")
        if (ldLink.exists() && Files.isSymbolicLink(ldLink.toPath())) {
            try {
                val target = android.system.Os.readlink(ldLink.absolutePath)
                val resolvedFile = File(ldLink.parentFile, target).canonicalFile
                if (resolvedFile.exists() && resolvedFile.isFile) {
                    ldLink.delete()
                    resolvedFile.copyTo(ldLink)
                    ldLink.setExecutable(true, false)
                    ldLink.setReadable(true, false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fix ld-linux: ${e.message}")
            }
        }
    }
}
