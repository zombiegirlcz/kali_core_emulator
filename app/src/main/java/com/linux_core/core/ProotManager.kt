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
    private const val NL = "\n" // Force Unix line endings

    fun setupProotEnvironment(
        context: Context,
        rootfsDirName: String = "kali-arm64",
        mountStorage: Boolean = false,
        customCommand: String? = null
    ): ProotConfig {
        val rootDir = context.filesDir
        val rootfsDir = File(rootDir, rootfsDirName)
        val homeDir = File(rootfsDir, "root")
        val tmpDir = File(rootDir, "tmp")

        val criticalDirs = listOf(
            "system", "dev", "proc", "sys", "tmp", "root", "sdcard",
            "bin", "usr/bin", "usr/sbin", "sbin", "lib", "lib64", "usr/lib", "etc"
        )
        for (dirName in criticalDirs) {
            val dir = File(rootfsDir, dirName)
            if (!dir.exists()) dir.mkdirs()
            dir.setReadable(true, false)
            dir.setWritable(true, false)
            dir.setExecutable(true, false)
        }

        if (!homeDir.exists()) homeDir.mkdirs()
        if (!tmpDir.exists()) tmpDir.mkdirs()

        File(homeDir, ".hushlogin").apply { if (!exists()) createNewFile() }

        val setupDoneFile = File(homeDir, ".setup_done")
        val bootstrapRequired = File(homeDir, ".bootstrap_required")
        
        if (!setupDoneFile.exists() && !bootstrapRequired.exists()) {
            try {
                bootstrapRequired.createNewFile()
                Log.i(TAG, "Fresh install detected, created .bootstrap_required")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create bootstrap sentinel: ${e.message}")
            }
        }

        val distroId = if (rootfsDirName.contains("parrot")) "parrot" else "kali"
        createMasterScript(homeDir, distroId)
        createEntrypointScript(homeDir)
        createNetHunterZshrc(rootfsDir)
        fixLdLinuxSymlinks(rootfsDir)
        deployApiScripts(rootfsDir)

        val suffix = if (rootfsDir.name.contains("arm64")) "aarch64" else "arm"
        deployBinaries(context, suffix)

        val launcherFile = File(rootDir, "launcher.sh")
        val scriptContent = StringBuilder().apply {
            append("#!/system/bin/sh").append(NL)
            append("export PROOT_TMP_DIR=\"${tmpDir.absolutePath}\"").append(NL)
            append("export HOME=/root").append(NL)
            append("export USER=root").append(NL)
            append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin").append(NL)
            append("export TERM=xterm-256color").append(NL)
            append("export LANG=C.UTF-8").append(NL)
            append("export PROOT_LOADER=\"${File(context.filesDir, "loader").absolutePath}\"").append(NL)
            append("export LD_LIBRARY_PATH=\"${context.filesDir.absolutePath}\"").append(NL)
            append("unset LD_PRELOAD").append(NL)
            append("cd \"${context.filesDir.absolutePath}\"").append(NL)
            val baseFlags = "-v 0 --kill-on-exit --link2symlink -0 -r ${rootfsDir.absolutePath} -b /dev -b /proc -b /sys -b /system -w /root"
            val sdcardMount = if (mountStorage) " -b /sdcard" else ""
            append("exec ${File(context.filesDir, "proot").absolutePath} ${baseFlags}${sdcardMount} /bin/bash /root/entrypoint.sh \"\$@\"").append(NL)
        }.toString()
        
        launcherFile.writeText(scriptContent)
        launcherFile.setExecutable(true, false)

        val fullCommand = mutableListOf("/system/bin/sh", launcherFile.absolutePath)
        if (!customCommand.isNullOrEmpty()) {
            fullCommand.add(customCommand)
        }

        return ProotConfig(
            command = fullCommand.toTypedArray(),
            cwd = rootDir.absolutePath,
            env = emptyArray(),
            prootPath = File(context.filesDir, "proot").absolutePath,
            rootfsDir = rootfsDir.absolutePath
        )
    }

    private fun deployBinaries(context: Context, suffix: String) {
        val binaries = listOf("proot" to "proot-$suffix", "loader" to "loader-$suffix", "libtalloc.so.2" to "libtalloc-$suffix.so")
        for ((name, asset) in binaries) {
            val file = File(context.filesDir, name)
            if (!file.exists() || file.length() == 0L) {
                try {
                    context.assets.open(asset).use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    file.setExecutable(true, false)
                    file.setReadable(true, false)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to deploy $name: ${e.message}")
                }
            }
        }
    }

    private fun createNetHunterZshrc(rootfsDir: File) {
        val etcDir = File(rootfsDir, "etc")
        if (!etcDir.exists()) etcDir.mkdirs()
        val zshrcFile = File(etcDir, "nethunter.zshrc")
        val content = StringBuilder().apply {
            append("# NetHunter AI Operator Managed Config").append(NL)
            append("alias ll='ls -la --color=auto'").append(NL)
            append("alias l='ls -CF'").append(NL)
            append("alias la='ls -A'").append(NL)
            append("if [ \"\$(id -u)\" = \"0\" ]; then").append(NL)
            append("    alias parrot='sudo -u parrot -i'").append(NL)
            append("    alias kali='sudo -u kali -i'").append(NL)
            append("fi").append(NL)
            append("export PS1='%F{cyan}%n@parrot%f:%F{blue}%~%f# '").append(NL)
            append("# Load plugins if they exist").append(NL)
            append("[ -f /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh ] && source /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh").append(NL)
            append("[ -f /usr/share/zsh-autosuggestions/zsh-autosuggestions.zsh ] && source /usr/share/zsh-autosuggestions/zsh-autosuggestions.zsh").append(NL)
        }.toString()
        zshrcFile.writeText(content)
    }

    private fun createMasterScript(homeDir: File, distroId: String) {
        val masterFile = File(homeDir, "bootstrap.sh")
        val script = StringBuilder().apply {
            append("#!/bin/bash").append(NL)
            append("export DEBIAN_FRONTEND=noninteractive").append(NL)
            append("echo '[*] BOOTSTRAP STARTING...'").append(NL)
            append("rm -f /var/lib/dpkg/lock* /var/cache/apt/archives/lock /var/lib/apt/lists/lock 2>/dev/null || true").append(NL)
            append("echo 'nameserver 8.8.8.8' > /etc/resolv.conf").append(NL)

            if (distroId == "kali") {
                append("echo 'deb [trusted=yes] https://kali.download/kali kali-rolling main contrib non-free non-free-firmware' > /etc/apt/sources.list").append(NL)
            } else {
                append("echo 'deb [trusted=yes] https://deb.parrot.sh/parrot parrot main contrib non-free' > /etc/apt/sources.list").append(NL)
                append("mkdir -p /etc/apt/trusted.gpg.d").append(NL)
                append("wget -qO /etc/apt/trusted.gpg.d/parrot-archive-key.asc http://archive.parrotsec.org/parrot/misc/archive.gpg || true").append(NL)
            }

            append("for cmd in systemctl service update-rc.d invoke-rc.d dpkg-preconfigure setcap sysctl udevadm modprobe dmidecode systemd-detect-virt resolvconf passwd; do").append(NL)
            append("  for prefix in /usr/sbin /sbin /usr/bin /bin; do").append(NL)
            append("    path=\"\$prefix/\$cmd\"").append(NL)
            append("    dpkg-divert --add --local --rename --divert \"\$path.distrib\" \"\$path\" 2>/dev/null || true").append(NL)
            append("    ln -sf /bin/true \"\$path\" 2>/dev/null || true").append(NL)
            append("  done").append(NL)
            append("done").append(NL)

            append("apt update 2>&1 || true").append(NL)
            append("apt install -y --allow-unauthenticated zsh zsh-syntax-highlighting zsh-autosuggestions curl git sudo 2>&1 || true").append(NL)

            val defaultUser = distroId
            append("id -u ${defaultUser} &>/dev/null || useradd -m -s /usr/bin/zsh ${defaultUser} 2>/dev/null || true").append(NL)
            append("passwd -d ${defaultUser} 2>/dev/null && usermod -p \"\" ${defaultUser} 2>/dev/null || true").append(NL)
            append("usermod -aG sudo ${defaultUser} 2>/dev/null || true").append(NL)
            append("echo '${defaultUser} ALL=(ALL:ALL) NOPASSWD: ALL' > /etc/sudoers.d/${defaultUser}").append(NL)
            append("chmod 0440 /etc/sudoers.d/${defaultUser}").append(NL)

            append("chsh -s /usr/bin/zsh root 2>/dev/null || true").append(NL)
            append("touch /root/.setup_done").append(NL)
            append("echo '[+] BOOTSTRAP COMPLETE'").append(NL)
        }.toString()
        masterFile.writeText(script)
        masterFile.setExecutable(true, false)
    }

    private fun createEntrypointScript(homeDir: File) {
        val entryFile = File(homeDir, "entrypoint.sh")
        val script = StringBuilder().apply {
            append("#!/bin/bash").append(NL)
            append("unset LD_PRELOAD").append(NL)
            append("rm -f /var/lib/dpkg/lock* 2>/dev/null || true").append(NL)
            
            append("if [ -f /root/.bootstrap_required ]; then").append(NL)
            append("    /bin/bash /root/bootstrap.sh").append(NL)
            append("    rm -f /root/.bootstrap_required").append(NL)
            append("fi").append(NL)

            append("setup_user_zsh() {").append(NL)
            append("    local target_home=\"\$1\"").append(NL)
            append("    local user_name=\"\$2\"").append(NL)
            append("    local zrc=\"\$target_home/.zshrc\"").append(NL)
            append("    [ ! -d \"\$target_home\" ] && return").append(NL)
            append("    [ ! -f \"\$zrc\" ] && [ -f /etc/skel/.zshrc ] && cp /etc/skel/.zshrc \"\$zrc\"").append(NL)
            append("    [ ! -f \"\$zrc\" ] && touch \"\$zrc\"").append(NL)
            append("    # Clean old fragments").append(NL)
            append("    sed -i '/NetHunter AI Operator/d' \"\$zrc\" 2>/dev/null || true").append(NL)
            append("    sed -i '/FORCE_ZSH_/d' \"\$zrc\" 2>/dev/null || true").append(NL)
            append("    sed -i '/source \\/etc\\/nethunter.zshrc/d' \"\$zrc\" 2>/dev/null || true").append(NL)
            append("    # Add source line").append(NL)
            append("    echo 'source /etc/nethunter.zshrc # NetHunter AI Operator' >> \"\$zrc\"").append(NL)
            append("    [ -n \"\$user_name\" ] && chown \"\$user_name:\$user_name\" \"\$zrc\" 2>/dev/null || true").append(NL)
            append("}").append(NL)

            append("setup_user_zsh /root root").append(NL)
            append("[ -d /home/parrot ] && setup_user_zsh /home/parrot parrot").append(NL)
            append("[ -d /home/kali ] && setup_user_zsh /home/kali kali").append(NL)

            append("chmod 4755 /usr/bin/sudo /usr/bin/su /bin/su /bin/sudo 2>/dev/null || true").append(NL)
            append("echo '[*] Starting session...'").append(NL)
            append("if [ \$# -gt 0 ]; then").append(NL)
            append("    echo '[*] Running custom launcher command...'").append(NL)
            append("    exec zsh -c \"\$*\"").append(NL)
            append("else").append(NL)
            append("    exec zsh --login").append(NL)
            append("fi").append(NL)
        }.toString()
        entryFile.writeText(script)
        entryFile.setExecutable(true, false)
    }

    private fun fixLdLinuxSymlinks(rootfsDir: File) {
        val paths = listOf("lib/ld-linux-aarch64.so.1", "lib64/ld-linux-aarch64.so.1", "usr/lib/ld-linux-aarch64.so.1", "bin/sh", "bin/bash")
        for (relPath in paths) {
            val linkFile = File(rootfsDir, relPath)
            if (linkFile.exists() && Files.isSymbolicLink(linkFile.toPath())) {
                try {
                    val target = android.system.Os.readlink(linkFile.absolutePath)
                    val resolvedFile = if (target.startsWith("/")) File(rootfsDir, target.substring(1)).canonicalFile else File(linkFile.parentFile, target).canonicalFile
                    if (resolvedFile.exists() && resolvedFile.isFile) {
                        linkFile.delete()
                        resolvedFile.copyTo(linkFile)
                        linkFile.setExecutable(true, false)
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private fun deployApiScripts(rootfsDir: File) {
        val binDir = File(rootfsDir, "usr/bin")
        if (!binDir.exists()) binDir.mkdirs()

        val NL = "\n"

        val scripts = mapOf(
            "nethunter-toast" to StringBuilder().apply {
                append("#!/bin/bash").append(NL)
                append("if [ -p /dev/stdin ]; then").append(NL)
                append("  text=\$(cat)").append(NL)
                append("else").append(NL)
                append("  text=\"\$*\"").append(NL)
                append("fi").append(NL)
                append("curl -s -X POST --data-binary \"\$text\" http://127.0.0.1:1337/toast").append(NL)
            }.toString(),

            "nethunter-battery-status" to StringBuilder().apply {
                append("#!/bin/bash").append(NL)
                append("curl -s http://127.0.0.1:1337/battery").append(NL)
            }.toString(),

            "nethunter-vibrate" to StringBuilder().apply {
                append("#!/bin/bash").append(NL)
                append("duration=\${1:-500}").append(NL)
                append("curl -s -X POST -d \"\$duration\" http://127.0.0.1:1337/vibrate").append(NL)
            }.toString(),

            "nethunter-tts-speak" to StringBuilder().apply {
                append("#!/bin/bash").append(NL)
                append("if [ -p /dev/stdin ]; then").append(NL)
                append("  text=\$(cat)").append(NL)
                append("else").append(NL)
                append("  text=\"\$*\"").append(NL)
                append("fi").append(NL)
                append("curl -s -X POST --data-binary \"\$text\" http://127.0.0.1:1337/tts").append(NL)
            }.toString(),

            "nethunter-clipboard-get" to StringBuilder().apply {
                append("#!/bin/bash").append(NL)
                append("curl -s http://127.0.0.1:1337/clipboard").append(NL)
            }.toString(),

            "nethunter-clipboard-set" to StringBuilder().apply {
                append("#!/bin/bash").append(NL)
                append("if [ -p /dev/stdin ]; then").append(NL)
                append("  text=\$(cat)").append(NL)
                append("else").append(NL)
                append("  text=\"\$*\"").append(NL)
                append("fi").append(NL)
                append("curl -s -X POST --data-binary \"\$text\" http://127.0.0.1:1337/clipboard").append(NL)
            }.toString(),

            "nethunter-notification" to StringBuilder().apply {
                append("#!/bin/bash").append(NL)
                append("title=\"NetHunter\"").append(NL)
                append("content=\"\"").append(NL)
                append("while getopts \"t:c:\" opt; do").append(NL)
                append("  case \$opt in").append(NL)
                append("    t) title=\"\$OPTARG\" ;;").append(NL)
                append("    c) content=\"\$OPTARG\" ;;").append(NL)
                append("  esac").append(NL)
                append("done").append(NL)
                append("if [ -z \"\$content\" ]; then").append(NL)
                append("  shift \$((\$OPTIND-1))").append(NL)
                append("  content=\"\$*\"").append(NL)
                append("fi").append(NL)
                append("curl -s -X POST -H \"Content-Type: application/json\" -d \"{\\\"title\\\":\\\"\$title\\\",\\\"content\\\":\\\"\$content\\\"}\" http://127.0.0.1:1337/notification").append(NL)
            }.toString(),

            "nethunter-wifi-connectioninfo" to StringBuilder().apply {
                append("#!/bin/bash").append(NL)
                append("curl -s http://127.0.0.1:1337/wifi").append(NL)
            }.toString(),

            "nethunter-location" to StringBuilder().apply {
                append("#!/bin/bash").append(NL)
                append("curl -s http://127.0.0.1:1337/location").append(NL)
            }.toString(),

            "nethunter-volume" to StringBuilder().apply {
                append("#!/bin/bash").append(NL)
                append("if [ -z \"\$1\" ]; then").append(NL)
                append("  curl -s http://127.0.0.1:1337/volume").append(NL)
                append("else").append(NL)
                append("  curl -s -X POST -d \"\$1\" http://127.0.0.1:1337/volume").append(NL)
                append("fi").append(NL)
            }.toString(),

            "nethunter-torch" to StringBuilder().apply {
                append("#!/bin/bash").append(NL)
                append("state=\${1:-on}").append(NL)
                append("curl -s -X POST -d \"\$state\" http://127.0.0.1:1337/torch").append(NL)
            }.toString()
        )

        for ((name, content) in scripts) {
            val scriptFile = File(binDir, name)
            try {
                scriptFile.writeText(content)
                scriptFile.setExecutable(true, false)
                scriptFile.setReadable(true, false)
                scriptFile.setWritable(true, false)
            } catch (e: Exception) {
                Log.e("ProotManager", "Failed to deploy API script $name: ${e.message}")
            }
        }
    }
}
