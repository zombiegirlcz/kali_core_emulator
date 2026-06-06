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
        customCommand: String? = null,
        hasRoot: Boolean = false
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
        val distroId = if (rootfsDirName.contains("parrot")) "parrot" else "kali"
        
        if (!setupDoneFile.exists() && !bootstrapRequired.exists()) {
            try {
                bootstrapRequired.createNewFile()
                Log.i(TAG, "Fresh install detected, created .bootstrap_required")
                deployZshrc(context, rootfsDir, distroId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create bootstrap sentinel: ${e.message}")
            }
        }

        createMasterScript(homeDir, distroId, hasRoot)
        createEntrypointScript(homeDir)
        deployVpnHelpDocument(homeDir)
        val userHomeDir = File(rootfsDir, "home/$distroId")
        if (userHomeDir.exists()) {
            deployVpnHelpDocument(userHomeDir)
        }
        deployApiScripts(context, rootfsDir)

        val suffix = if (rootfsDir.name.contains("arm64")) "aarch64" else "arm"
        deployBinaries(context, suffix)
        fixLdLinuxSymlinks(context, rootfsDir)

        val prootBin = File(context.filesDir, "proot")
        val loaderBin = File(context.filesDir, "loader")
        val tallocLib = File(context.filesDir, "libtalloc.so.2")
        val standaloneProot = File(context.filesDir, "proot.standalone")
        val standaloneLoader = File(context.filesDir, "loader.standalone")

        val launcherFile = File(rootDir, "launcher.sh")
        val scriptContent = StringBuilder().apply {
            append("#!/system/bin/sh").append(NL)
            // Preflight diagnostics
            append("log -t ProotLauncher \"[PROOT] Starting launcher.sh\"").append(NL)
            // Try dynamic proot first (needs loader+talloc), fall back to standalone
            append("USE_PROOT=\"${prootBin.absolutePath}\"").append(NL)
            append("USE_LOADER=\"${loaderBin.absolutePath}\"").append(NL)
            append("if [ -x \"${'$'}USE_PROOT\" ] && [ -x \"${'$'}USE_LOADER\" ] && [ -f \"${tallocLib.absolutePath}\" ]; then").append(NL)
            append("  log -t ProotLauncher \"[PROOT] Using dynamic proot with LOADER+talloc\"").append(NL)
            append("  export PROOT_LOADER=\"${'$'}USE_LOADER\"").append(NL)
            append("  export LD_LIBRARY_PATH=\"${context.filesDir.absolutePath}\"").append(NL)
            append("elif [ -x \"${standaloneProot.absolutePath}\" ] && [ -x \"${standaloneLoader.absolutePath}\" ]; then").append(NL)
            append("  USE_PROOT=\"${standaloneProot.absolutePath}\"").append(NL)
            append("  USE_LOADER=\"${standaloneLoader.absolutePath}\"").append(NL)
            append("  log -t ProotLauncher \"[PROOT] Using standalone proot (static)\"").append(NL)
            append("  export PROOT_LOADER=\"${'$'}USE_LOADER\"").append(NL)
            append("else").append(NL)
            append("  log -t ProotLauncher \"[PROOT] FATAL: no usable proot+loader combination found\"").append(NL)
            append("  exit 1").append(NL)
            append("fi").append(NL)
            append("log -t ProotLauncher \"[PROOT] proot binary: ${'$'}USE_PROOT (exists=\$(test -f \"${'$'}USE_PROOT\" && echo yes || echo no), exec=\$(test -x \"${'$'}USE_PROOT\" && echo yes || echo no))\"").append(NL)
            append("log -t ProotLauncher \"[PROOT] loader binary: ${'$'}USE_LOADER (exists=\$(test -f \"${'$'}USE_LOADER\" && echo yes || echo no), exec=\$(test -x \"${'$'}USE_LOADER\" && echo yes || echo no))\"").append(NL)
            append("log -t ProotLauncher \"[PROOT] talloc lib: ${tallocLib.absolutePath} (exists=$(test -f '${tallocLib.absolutePath}' && echo yes || echo no))\"").append(NL)
            append("log -t ProotLauncher \"[PROOT] rootfs: ${rootfsDir.absolutePath} (exists=$(test -d '${rootfsDir.absolutePath}' && echo yes || echo no))\"").append(NL)
            append("log -t ProotLauncher \"[PROOT] /bin/bash in rootfs: (exists=$(test -e '${rootfsDir.absolutePath}/bin/bash' && echo yes || echo no), islink=$(test -L '${rootfsDir.absolutePath}/bin/bash' && echo yes || echo no))\"").append(NL)
            append("export PROOT_TMP_DIR=\"${tmpDir.absolutePath}\"").append(NL)
            append("export HOME=/root").append(NL)
            append("export USER=root").append(NL)
            append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin").append(NL)
            append("export TERM=xterm-256color").append(NL)
            append("export LANG=C.UTF-8").append(NL)
            append("unset LD_PRELOAD").append(NL)
            append("cd \"${context.filesDir.absolutePath}\"").append(NL)
            val baseFlags = "-v 0 --kill-on-exit --link2symlink -0 -r ${rootfsDir.absolutePath} -b /dev -b /proc -b /sys -b /system -w /root"
            val sdcardMount = if (mountStorage) " -b /sdcard" else ""
            append("log -t ProotLauncher \"[PROOT] Executing proot now...\"").append(NL)
            append("exec ${'$'}USE_PROOT ${baseFlags}${sdcardMount} /bin/bash /root/entrypoint.sh \"${'$'}@\"").append(NL)
            append("log -t ProotLauncher \"[PROOT] exec returned \$? (should not reach here)\"").append(NL)
        }.toString()
        
        launcherFile.writeText(scriptContent)
        launcherFile.setExecutable(true, false)

        val fullCommand = mutableListOf("/system/bin/sh", launcherFile.absolutePath)
        if (!customCommand.isNullOrEmpty()) {
            fullCommand.add(customCommand)
        }

        val defaultProot = if (standaloneProot.exists() && standaloneProot.canExecute()) standaloneProot else prootBin
        return ProotConfig(
            command = fullCommand.toTypedArray(),
            cwd = rootDir.absolutePath,
            env = emptyArray(),
            prootPath = defaultProot.absolutePath,
            rootfsDir = rootfsDir.absolutePath
        )
    }

    private fun deployBinaries(context: Context, suffix: String) {
        val binaries = listOf(
            "proot" to "proot-$suffix",
            "loader" to "loader-$suffix",
            "libtalloc.so.2" to "libtalloc-$suffix.so"
        )
        for ((name, asset) in binaries) {
            val file = File(context.filesDir, name)
            // Always delete old binary to ensure fresh deployment
            if (file.exists()) file.delete()
            try {
                context.assets.open(asset).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "Deployed binary $name (${file.length()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deploy $name from asset $asset: ${e.message}")
            }
            if (file.exists() && file.length() > 0L) {
                file.setExecutable(true, false)
                file.setReadable(true, false)
                Log.d(TAG, "Permissions enforced: $name (${file.length()} bytes, canExecute=${file.canExecute()})")
            } else {
                Log.e(TAG, "Binary missing or empty after deploy: $name at ${file.absolutePath}")
            }
        }

        // Deploy static fallback binaries
        val staticSuffix = if (suffix == "aarch64") "static-aarch64" else "static-arm32"
        val staticBinaries = listOf(
            "proot.standalone" to "proot-$staticSuffix",
            "loader.standalone" to "loader-$staticSuffix"
        )
        for ((name, asset) in staticBinaries) {
            val file = File(context.filesDir, name)
            if (file.exists()) file.delete()
            try {
                context.assets.open(asset).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file.setExecutable(true, false)
                file.setReadable(true, false)
                Log.i(TAG, "Deployed static fallback binary $name (${file.length()} bytes)")
            } catch (e: Exception) {
                Log.w(TAG, "Static fallback $name not available: ${e.message}")
            }
        }
    }


    private fun createMasterScript(homeDir: File, distroId: String, hasRoot: Boolean) {
        val masterFile = File(homeDir, "bootstrap.sh")
        val script = StringBuilder().apply {
            append("#!/bin/bash").append(NL)
            append("export DEBIAN_FRONTEND=noninteractive").append(NL)
            append("export DEBCONF_NOWARNINGS=yes").append(NL)
            append("echo '[*] BOOTSTRAP STARTING...'").append(NL)
            append("rm -f /var/lib/dpkg/lock* /var/cache/apt/archives/lock /var/lib/apt/lists/lock 2>/dev/null || true").append(NL)
            append("echo 'nameserver 8.8.8.8' > /etc/resolv.conf").append(NL)
            append("# Base ParrotOS /usr/bin/perl is a directory, breaking debconf Perl shebang.").append(NL)
            append("# Replace confmodule with dummy no-op shell functions during bootstrap.").append(NL)
            append("if [ -f /usr/share/debconf/confmodule ] && [ ! -f /usr/share/debconf/confmodule.bak ]; then").append(NL)
            append("  cp /usr/share/debconf/confmodule /usr/share/debconf/confmodule.bak").append(NL)
            append("  cat > /usr/share/debconf/confmodule << 'ENDCONF'").append(NL)
            append("db_version() { return 0; }").append(NL)
            append("db_input() { return 0; }").append(NL)
            append("db_go() { return 0; }").append(NL)
            append("db_get() { RET=''; return 0; }").append(NL)
            append("db_set() { return 0; }").append(NL)
            append("db_subst() { return 0; }").append(NL)
            append("db_fset() { return 0; }").append(NL)
            append("db_reset() { return 0; }").append(NL)
            append("db_stop() { return 0; }").append(NL)
            append("db_metaget() { return 0; }").append(NL)
            append("db_register() { return 0; }").append(NL)
            append("db_purge() { return 0; }").append(NL)
            append("ENDCONF").append(NL)
            append("  echo '[*] Replaced debconf confmodule with dummy (no Perl needed)'").append(NL)
            append("fi").append(NL)

            if (distroId == "kali") {
                append("echo 'deb [trusted=yes] https://kali.download/kali kali-rolling main contrib non-free non-free-firmware' > /etc/apt/sources.list").append(NL)
            } else {
                append("echo 'deb [trusted=yes] https://deb.parrot.sh/parrot parrot main contrib non-free' > /etc/apt/sources.list").append(NL)
                append("mkdir -p /etc/apt/trusted.gpg.d").append(NL)
                append("if command -v curl >/dev/null 2>&1; then").append(NL)
                append("  curl -sSL -o /etc/apt/trusted.gpg.d/parrot-archive-key.asc http://archive.parrotsec.org/parrot/misc/archive.gpg 2>/dev/null || true").append(NL)
                append("elif command -v wget >/dev/null 2>&1; then").append(NL)
                append("  wget -qO /etc/apt/trusted.gpg.d/parrot-archive-key.asc http://archive.parrotsec.org/parrot/misc/archive.gpg 2>/dev/null || true").append(NL)
                append("fi").append(NL)
            }

            if (!hasRoot) {
                append("for cmd in systemctl service update-rc.d invoke-rc.d dpkg-preconfigure setcap sysctl udevadm modprobe dmidecode systemd-detect-virt resolvconf dpkg-realpath systemd-sysusers systemd-tmpfiles journalctl; do").append(NL)
                append("  for prefix in /usr/sbin /sbin /usr/bin /bin; do").append(NL)
                append("    path=\"\$prefix/\$cmd\"").append(NL)
                append("    dpkg-divert --add --local --rename --divert \"\$path.distrib\" \"\$path\" 2>/dev/null || true").append(NL)
                append("    ln -sf /bin/true \"\$path\" 2>/dev/null || true").append(NL)
                append("  done").append(NL)
                append("done").append(NL)
            }

            append("mkdir -p /etc/dpkg/dpkg.cfg.d").append(NL)
            append("echo 'force-unsafe-io' > /etc/dpkg/dpkg.cfg.d/force-unsafe-io").append(NL)
            append("mkdir -p /etc/apt/apt.conf.d").append(NL)
            append("echo 'DPkg::options { \"--force-unsafe-io\"; };' > /etc/apt/apt.conf.d/force-unsafe-io").append(NL)

            append("apt update 2>&1 || true").append(NL)
            append("echo '[*] Installing core packages...'").append(NL)
            append("apt install -y --allow-unauthenticated usrmerge perl zsh zsh-syntax-highlighting zsh-autosuggestions curl git sudo python3 python3-pip 2>&1 || true").append(NL)
            // Restore debconf confmodule (real Perl now installed, debconf should work)

            append("if [ -f /usr/share/debconf/confmodule.bak ] && [ ! -f /usr/share/debconf/confmodule ]; then").append(NL)
            append("  mv /usr/share/debconf/confmodule.bak /usr/share/debconf/confmodule").append(NL)
            append("  echo '[*] Restored debconf confmodule'").append(NL)
            append("fi").append(NL)
            append("echo '[*] Fixing any half-configured packages...'").append(NL)
            append("dpkg --configure -a 2>&1 || true").append(NL)
            append("SHELL_BIN=/bin/bash").append(NL)
            append("if command -v zsh >/dev/null 2>&1; then SHELL_BIN=/usr/bin/zsh; echo '[*] zsh installed OK'; else echo '[!] WARNING: zsh install failed, using bash fallback'; fi").append(NL)
            append("echo '[*] Installing Python packages (requests, scapy)...'").append(NL)
            append("# Try pip3 first, fall back to python3 -m pip, with break-system-packages detection").append(NL)
            append("PIP_CMD=\"\"").append(NL)
            append("if command -v pip3 >/dev/null 2>&1; then").append(NL)
            append("  PIP_CMD=pip3").append(NL)
            append("elif command -v python3 >/dev/null 2>&1 && python3 -m pip --version >/dev/null 2>&1; then").append(NL)
            append("  PIP_CMD=\"python3 -m pip\"").append(NL)
            append("fi").append(NL)
            append("if [ -n \"\$PIP_CMD\" ]; then").append(NL)
            append("  if \$PIP_CMD install --help 2>&1 | grep -q break-system-packages; then").append(NL)
            append("    \$PIP_CMD install --break-system-packages requests scapy 2>&1 || true").append(NL)
            append("  else").append(NL)
            append("    PIP_REQUIRE_VIRTUALENV=false \$PIP_CMD install requests scapy 2>&1 || true").append(NL)
            append("  fi").append(NL)
            append("else").append(NL)
            append("  echo '[!] WARNING: pip not found, skipping Python packages'").append(NL)
            append("fi").append(NL)

            val defaultUser = distroId
            append("id -u ${defaultUser} &>/dev/null || useradd -m -s \"\$SHELL_BIN\" ${defaultUser} 2>/dev/null || true").append(NL)
            append("passwd -d ${defaultUser} 2>/dev/null && usermod -p \"\" ${defaultUser} 2>/dev/null || true").append(NL)
            append("usermod -aG sudo ${defaultUser} 2>/dev/null || true").append(NL)
            append("echo '${defaultUser} ALL=(ALL:ALL) NOPASSWD: ALL' > /etc/sudoers.d/${defaultUser}").append(NL)
            append("chmod 0440 /etc/sudoers.d/${defaultUser}").append(NL)

            append("chsh -s \"\$SHELL_BIN\" root 2>/dev/null || true").append(NL)
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
            append("    # Restore debconf confmodule if bootstrap left it disabled").append(NL)
            append("    if [ -f /usr/share/debconf/confmodule.bak ] && [ ! -f /usr/share/debconf/confmodule ]; then").append(NL)
            append("        mv /usr/share/debconf/confmodule.bak /usr/share/debconf/confmodule").append(NL)
            append("    fi").append(NL)
            append("fi").append(NL)
            
            append("# Restore passwd if it was previously diverted by mistake").append(NL)
            append("for prefix in /usr/sbin /sbin /usr/bin /bin; do").append(NL)
            append("  path=\"\$prefix/passwd\"").append(NL)
            append("  if [ -L \"\$path\" ] && [ -f \"\$path.distrib\" ]; then").append(NL)
            append("    rm -f \"\$path\"").append(NL)
            append("    dpkg-divert --remove --local --rename \"\$path\" 2>/dev/null || true").append(NL)
            append("  fi").append(NL)
            append("done").append(NL)

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
            append("    [ -n \"\$user_name\" ] && chown \"\$user_name:\$user_name\" \"\$zrc\" 2>/dev/null || true").append(NL)
            append("}").append(NL)

            append("setup_user_zsh /root root").append(NL)
            append("[ -d /home/parrot ] && setup_user_zsh /home/parrot parrot").append(NL)
            append("[ -d /home/kali ] && setup_user_zsh /home/kali kali").append(NL)

            append("chmod 4755 /usr/bin/sudo /usr/bin/su /bin/su /bin/sudo 2>/dev/null || true").append(NL)
            append("echo '[*] Starting session...'").append(NL)
            append("ENTRY_SHELL=\$(command -v zsh || echo /bin/bash)").append(NL)
            append("if [ \$# -gt 0 ]; then").append(NL)
            append("    echo '[*] Running custom launcher command...'").append(NL)
            append("    exec \"\$ENTRY_SHELL\" -c \"\$*\"").append(NL)
            append("else").append(NL)
            append("    exec \"\$ENTRY_SHELL\" --login").append(NL)
            append("fi").append(NL)
        }.toString()
        entryFile.writeText(script)
        entryFile.setExecutable(true, false)
    }

    private fun fixLdLinuxSymlinks(context: Context, rootfsDir: File) {
        // Also copy the loader into the rootfs as ld-linux fallback
        val loaderFile = File(context.filesDir, "loader")
        val tallocFile = File(context.filesDir, "libtalloc.so.2")
        for (destRel in listOf("lib/ld-linux-aarch64.so.1", "lib64/ld-linux-aarch64.so.1")) {
            val dest = File(rootfsDir, destRel)
            if (!dest.exists() || dest.length() == 0L) {
                dest.parentFile?.mkdirs()
                try {
                    loaderFile.copyTo(dest, overwrite = true)
                    dest.setExecutable(true, false)
                    Log.i(TAG, "Installed loader into rootfs: $destRel")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to install loader into rootfs: ${e.message}")
                }
            }
        }
        // Copy talloc into rootfs lib so guest binaries can find it
        val tallocDest = File(rootfsDir, "lib/libtalloc.so.2")
        if (!tallocDest.exists() || tallocDest.length() == 0L) {
            try {
                tallocFile.copyTo(tallocDest, overwrite = true)
                tallocDest.setReadable(true, false)
                Log.i(TAG, "Installed talloc into rootfs: lib/libtalloc.so.2")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to install talloc into rootfs: ${e.message}")
            }
        }
        // Fix bash/sh symlinks
        val paths = listOf("bin/sh", "bin/bash")
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

    private fun deployApiScripts(context: Context, rootfsDir: File) {
        val binDir = File(rootfsDir, "usr/local/bin")
        if (!binDir.exists()) binDir.mkdirs()

        val NL = "\n"

        val scripts = mapOf(
            "apt" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("# NetHunter AI Operator APT Wrapper").append(NL)
                append("export DEBIAN_FRONTEND=noninteractive").append(NL)
                append("# 1. Fix debconf syntax error if present").append(NL)
                append("if [ -f /usr/share/debconf/confmodule ]; then").append(NL)
                append("  sed -i \"s/eval \\\"\\\$RET=''\\\"/RET=''/g\" /usr/share/debconf/confmodule 2>/dev/null").append(NL)
                append("fi").append(NL)
                append("# 2. Reinforce diversions for systemd tools if missing").append(NL)
                append("for cmd in systemd-sysusers systemd-tmpfiles journalctl systemctl; do").append(NL)
                append("  if [ -f \"/usr/bin/\$cmd\" ] && [ ! -L \"/usr/bin/\$cmd\" ]; then").append(NL)
                append("    dpkg-divert --add --local --rename --divert \"/usr/bin/\$cmd.distrib\" \"/usr/bin/\$cmd\" 2>/dev/null").append(NL)
                append("    ln -sf /bin/true \"/usr/bin/\$cmd\" 2>/dev/null").append(NL)
                append("  fi").append(NL)
                append("done").append(NL)
                append("# 3. Run real apt").append(NL)
                append("exec /usr/bin/apt \"$@\"").append(NL)
            }.toString(),

            "apt-get" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("# NetHunter AI Operator APT-GET Wrapper").append(NL)
                append("export DEBIAN_FRONTEND=noninteractive").append(NL)
                append("# 1. Fix debconf syntax error if present").append(NL)
                append("if [ -f /usr/share/debconf/confmodule ]; then").append(NL)
                append("  sed -i \"s/eval \\\"\\\$RET=''\\\"/RET=''/g\" /usr/share/debconf/confmodule 2>/dev/null").append(NL)
                append("fi").append(NL)
                append("# 2. Reinforce diversions for systemd tools if missing").append(NL)
                append("for cmd in systemd-sysusers systemd-tmpfiles journalctl systemctl; do").append(NL)
                append("  if [ -f \"/usr/bin/\$cmd\" ] && [ ! -L \"/usr/bin/\$cmd\" ]; then").append(NL)
                append("    dpkg-divert --add --local --rename --divert \"/usr/bin/\$cmd.distrib\" \"/usr/bin/\$cmd\" 2>/dev/null").append(NL)
                append("    ln -sf /bin/true \"/usr/bin/\$cmd\" 2>/dev/null").append(NL)
                append("  fi").append(NL)
                append("done").append(NL)
                append("# 3. Run real apt-get").append(NL)
                append("exec /usr/bin/apt-get \"$@\"").append(NL)
            }.toString(),

            "vpn-bypass" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("if [ \$# -eq 0 ]; then").append(NL)
                append("  echo \"Usage: vpn-bypass <command> [arguments...]\"").append(NL)
                append("  exit 1").append(NL)
                append("fi").append(NL)
                append("export http_proxy=http://127.0.0.1:13339").append(NL)
                append("export https_proxy=http://127.0.0.1:13339").append(NL)
                append("export all_proxy=http://127.0.0.1:13339").append(NL)
                append("export HTTP_PROXY=http://127.0.0.1:13339").append(NL)
                append("export HTTPS_PROXY=http://127.0.0.1:13339").append(NL)
                append("export ALL_PROXY=http://127.0.0.1:13339").append(NL)
                append("echo \"[*] Executing in VPN bypass mode: \$@\"").append(NL)
                append("exec \"\$@\"").append(NL)
            }.toString(),

            "dcheck" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("if [ \$# -eq 0 ]; then").append(NL)
                append("  echo \"Usage: dcheck <command> [arguments...]\"").append(NL)
                append("  exit 1").append(NL)
                append("fi").append(NL)
                append("export http_proxy=http://127.0.0.1:13339").append(NL)
                append("export https_proxy=http://127.0.0.1:13339").append(NL)
                append("export all_proxy=http://127.0.0.1:13339").append(NL)
                append("export HTTP_PROXY=http://127.0.0.1:13339").append(NL)
                append("export HTTPS_PROXY=http://127.0.0.1:13339").append(NL)
                append("export ALL_PROXY=http://127.0.0.1:13339").append(NL)
                append("echo \"[*] Executing in VPN bypass mode (dcheck): \$@\"").append(NL)
                append("exec \"\$@\"").append(NL)
            }.toString(),

            "vpn-off" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("if [ \$# -eq 0 ]; then").append(NL)
                append("  echo \"[*] Stopping global VPN service...\"").append(NL)
                append("  curl -s -X POST http://127.0.0.1:1337/vpn/stop >/dev/null").append(NL)
                append("  echo \"[-] Global VPN sniffer disabled.\"").append(NL)
                append("  exit 0").append(NL)
                append("fi").append(NL)
                append("was_running=false").append(NL)
                append("if curl -s http://127.0.0.1:1337/vpn | grep -q '\"running\":true'; then").append(NL)
                append("  was_running=true").append(NL)
                append("  echo \"[*] Temporarily disabling VPN\"").append(NL)
                append("  curl -s -X POST http://127.0.0.1:1337/vpn/stop >/dev/null").append(NL)
                append("  sleep 1").append(NL)
                append("fi").append(NL)
                append("echo \"[*] Executing (VPN off): \$@\"").append(NL)
                append("\"\$@\"").append(NL)
                append("exit_code=\$?").append(NL)
                append("if [ \"\$was_running\" = \"true\" ]; then").append(NL)
                append("  echo \"[*] Restoring VPN\"").append(NL)
                append("  curl -s -X POST http://127.0.0.1:1337/vpn/start >/dev/null").append(NL)
                append("fi").append(NL)
                append("exit \$exit_code").append(NL)
            }.toString(),

            "vpn-on" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("if [ \$# -eq 0 ]; then").append(NL)
                append("  echo \"[*] Starting global VPN service...\"").append(NL)
                append("  curl -s -X POST http://127.0.0.1:1337/vpn/start >/dev/null").append(NL)
                append("  echo \"[+] Global VPN sniffer enabled.\"").append(NL)
                append("  exit 0").append(NL)
                append("fi").append(NL)
                append("if ! curl -s http://127.0.0.1:1337/vpn | grep -q '\"running\":true'; then").append(NL)
                append("  echo \"[*] Starting VPN\"").append(NL)
                append("  curl -s -X POST http://127.0.0.1:1337/vpn/start >/dev/null").append(NL)
                append("  sleep 1").append(NL)
                append("fi").append(NL)
                append("echo \"[*] Executing (VPN on): \$@\"").append(NL)
                append("\"\$@\"").append(NL)
                append("exit \$?").append(NL)
            }.toString(),

            "vpn-cli" to StringBuilder().apply {
                append("#!/bin/bash").append(NL)
                append("API_URL=\"http://127.0.0.1:1337\"").append(NL)
                append("function usage() {").append(NL)
                append("    echo \"Usage: vpn-cli [start|stop|status|logs|ignore|ai|chat]\"").append(NL)
                append("    echo \"  chat          Open local AI Expert console\"").append(NL)
                append("    echo \"  ai start      Start background monitor\"").append(NL)
                append("    exit 1").append(NL)
                append("}").append(NL)
                append("case \"\$1\" in").append(NL)
                append("    chat) python3 /usr/local/bin/ai-agent.py chat ;;").append(NL)
                append("    start) curl -s -X POST \"\$API_URL/vpn/start\" | grep -q \"starting\" && echo \"✅ VPN starting...\" || echo \"❌ Failed to start VPN\" ;;").append(NL)
                append("    stop) curl -s -X POST \"\$API_URL/vpn/stop\" | grep -q \"stopping\" && echo \"✅ VPN stopping...\" || echo \"❌ Failed to stop VPN\" ;;").append(NL)
                append("    status)").append(NL)
                append("        STATUS=\$(curl -s \"\$API_URL/vpn\")").append(NL)
                append("        RUNNING=\$(echo \"\$STATUS\" | grep -o '\"running\":[^,]*' | cut -d: -f2)").append(NL)
                append("        if [ \"\$RUNNING\" == \"true\" ]; then echo \"🟢 VPN is RUNNING\"; else echo \"🔴 VPN is STOPPED\"; fi").append(NL)
                append("        ;;").append(NL)
                append("    ai)").append(NL)
                append("        case \"\$2\" in").append(NL)
                append("            start) nohup python3 /usr/local/bin/ai-agent.py monitor >/tmp/ai-agent.log 2>&1 & echo \"🚀 AI Monitor started.\" ;;").append(NL)
                append("            stop) pkill -f ai-agent.py && echo \"🛑 AI Monitor stopped.\" ;;").append(NL)
                append("            status) pgrep -f ai-agent.py >/dev/null && echo \"🟢 AI Monitor is RUNNING\" || echo \"🔴 AI Monitor is STOPPED\" ;;").append(NL)
                append("            *) echo \"Usage: vpn-cli ai [start|stop|status]\" ;;").append(NL)
                append("        esac ;;").append(NL)
                append("    *) usage ;;").append(NL)
                append("esac").append(NL)
            }.toString(),

            "ai-agent.py" to StringBuilder().apply {
                append("#!/usr/bin/python3").append(NL)
                append("import requests, time, json, os, sys").append(NL)
                append("API_URL = 'http://127.0.0.1:1337'").append(NL)
                append("def get_logs():").append(NL)
                append("    try: return requests.get(f'{API_URL}/vpn/logs').json()").append(NL)
                append("    except: return []").append(NL)
                append("def run_shell(cmd):").append(NL)
                append("    try: return requests.post(f'{API_URL}/shell', data=cmd).json()").append(NL)
                append("    except: return {'error': 'API unreachable'}").append(NL)
                append("def analyze_log(log):").append(NL)
                append("    src = log.get('srcIp')").append(NL)
                append("    dst = log.get('dstIp')").append(NL)
                append("    port = log.get('dstPort')").append(NL)
                append("    entropy = log.get('entropy', 0)").append(NL)
                append("    ans = f\"Provoz {src} -> {dst}:{port}. \"").append(NL)
                append("    if log.get('category') == 'CRITICAL':").append(NL)
                append("        ans += \"🔴 ANALÝZA: Kritická anomálie detekována! \"").append(NL)
                append("        if entropy > 7.5: ans += \"Vysoká entropie (šifrovaný tunel). \"").append(NL)
                append("        ans += f\"\\n👉 Tip: '!iptables -I OUTPUT -d {dst} -j DROP'\"").append(NL)
                append("    else: ans += \"🟢 Vypadá to v pohodě.\"").append(NL)
                append("    return ans").append(NL)
                append("def chat_loop():").append(NL)
                append("    print('🤖 NetHunter Local AI Expert (v1.0)')").append(NL)
                append("    print('Ptej se na \"stav\" nebo piš !příkaz pro shell.')").append(NL)
                append("    while True:").append(NL)
                append("        user_input = input('👤 Ty: ').lower()").append(NL)
                append("        if user_input in ['exit', 'quit']: break").append(NL)
                append("        if 'stav' in user_input:").append(NL)
                append("            logs = get_logs()").append(NL)
                append("            print(f\"🤖 Agent: {analyze_log(logs[0]) if logs else 'Žádné logy.'}\")").append(NL)
                append("        elif user_input.startswith('!'):").append(NL)
                append("            res = run_shell(user_input[1:])").append(NL)
                append("            print(f\"🤖 Shell: {res.get('stdout','')}{res.get('stderr','')}\")").append(NL)
                append("        else: print(\"🤖 Agent: Sleduju provoz. Napiš 'stav' pro analýzu.\")").append(NL)
                append("def monitor_loop():").append(NL)
                append("    seen = set()").append(NL)
                append("    while True:").append(NL)
                append("        for l in get_logs():").append(NL)
                append("            if f\"{l['timestamp']}:{l['srcIp']}\" not in seen:").append(NL)
                append("                seen.add(f\"{l['timestamp']}:{l['srcIp']}\")").append(NL)
                append("                if l.get('category') == 'CRITICAL':").append(NL)
                append("                    requests.post(f'{API_URL}/toast', data=f\"AI: Detekován útok z {l['srcIp']}!\")").append(NL)
                append("        time.sleep(5)").append(NL)
                append("if __name__ == '__main__':").append(NL)
                append("    if len(sys.argv) > 1 and sys.argv[1] == 'chat': chat_loop()").append(NL)
                append("    else: monitor_loop()").append(NL)
            }.toString(),

            "ignore-vpn" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("if [ -z \"\$NETHUNTER_SESSION_ID\" ]; then").append(NL)
                append("  echo \"[-] Error: NETHUNTER_SESSION_ID is not set in this terminal session.\"").append(NL)
                append("  exit 1").append(NL)
                append("fi").append(NL)
                append("mode=\${1:-on}").append(NL)
                append("if [ \"\$mode\" = \"on\" ]; then").append(NL)
                append("  curl -s -X POST \"http://127.0.0.1:1337/vpn/ignore?session_id=\$NETHUNTER_SESSION_ID&ignored=true\" >/dev/null").append(NL)
                append("  echo \"[*] VPN sniffer bypassed for this session.\"").append(NL)
                append("elif [ \"\$mode\" = \"off\" ]; then").append(NL)
                append("  curl -s -X POST \"http://127.0.0.1:1337/vpn/ignore?session_id=\$NETHUNTER_SESSION_ID&ignored=false\" >/dev/null").append(NL)
                append("  echo \"[*] VPN sniffer routing restored for this session.\"").append(NL)
                append("elif [ \"\$mode\" = \"status\" ]; then").append(NL)
                append("  res=\$(curl -s \"http://127.0.0.1:1337/vpn/ignore?session_id=\$NETHUNTER_SESSION_ID\")").append(NL)
                append("  echo \"[*] VPN ignore status: \$res\"").append(NL)
                append("else").append(NL)
                append("  echo \"Usage: ignore-vpn [on|off|status]\"").append(NL)
                append("  exit 1").append(NL)
                append("fi").append(NL)
            }.toString(),

            "nethunter-toast" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("if [ -p /dev/stdin ]; then").append(NL)
                append("  text=\$(cat)").append(NL)
                append("else").append(NL)
                append("  text=\"\$*\"").append(NL)
                append("fi").append(NL)
                append("curl -s -X POST --data-binary \"\$text\" http://127.0.0.1:1337/toast").append(NL)
            }.toString(),

            "nethunter-battery-status" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("curl -s http://127.0.0.1:1337/battery").append(NL)
            }.toString(),

            "nethunter-speech-input" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("curl -s http://127.0.0.1:1337/voice_input").append(NL)
            }.toString(),

            "nethunter-vibrate" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("duration=\${1:-500}").append(NL)
                append("curl -s -X POST -d \"\$duration\" http://127.0.0.1:1337/vibrate").append(NL)
            }.toString(),

            "nethunter-tts-speak" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("if [ -p /dev/stdin ]; then").append(NL)
                append("  text=\$(cat)").append(NL)
                append("else").append(NL)
                append("  text=\"\$*\"").append(NL)
                append("fi").append(NL)
                append("curl -s -X POST --data-binary \"\$text\" http://127.0.0.1:1337/tts").append(NL)
            }.toString(),

            "nethunter-clipboard-get" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("curl -s http://127.0.0.1:1337/clipboard").append(NL)
            }.toString(),

            "nethunter-clipboard-set" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("if [ -p /dev/stdin ]; then").append(NL)
                append("  text=\$(cat)").append(NL)
                append("else").append(NL)
                append("  text=\"\$*\"").append(NL)
                append("fi").append(NL)
                append("curl -s -X POST --data-binary \"\$text\" http://127.0.0.1:1337/clipboard").append(NL)
            }.toString(),

            "nethunter-notification" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
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
                append("#!/bin/sh").append(NL)
                append("curl -s http://127.0.0.1:1337/wifi").append(NL)
            }.toString(),

            "nethunter-location" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("curl -s http://127.0.0.1:1337/location").append(NL)
            }.toString(),

            "nethunter-volume" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("if [ -z \"\$1\" ]; then").append(NL)
                append("  curl -s http://127.0.0.1:1337/volume").append(NL)
                append("else").append(NL)
                append("  curl -s -X POST -d \"\$1\" http://127.0.0.1:1337/volume").append(NL)
                append("fi").append(NL)
            }.toString(),

            "nethunter-torch" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("state=\${1:-on}").append(NL)
                append("curl -s -X POST -d \"\$state\" http://127.0.0.1:1337/torch").append(NL)
            }.toString(),

            "nethunter-fix-postinst" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("if [ -z \"\$1\" ]; then").append(NL)
                append("  echo \"Usage: nethunter-fix-postinst <package-name>\"").append(NL)
                append("  exit 1").append(NL)
                append("fi").append(NL)
                append("pkg=\"\$1\"").append(NL)
                append("echo \"[*] Mocking postinst for \$pkg...\"").append(NL)
                append("ln -sf /bin/true /var/lib/dpkg/info/\$pkg.postinst 2>/dev/null || true").append(NL)
                append("echo \"[*] Reconfiguring dpkg...\"").append(NL)
                append("dpkg --configure -a").append(NL)
                append("echo \"[+] Successfully fixed postinst for \$pkg!\"").append(NL)
            }.toString(),

            "nethunter-desktop" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("action=\"\${1:-start}\"").append(NL)
                append("VNC_PORT=5901").append(NL)
                append("NO_VNC_PORT=6080").append(NL)
                append("case \"\$action\" in").append(NL)
                append("  start)").append(NL)
                append("    echo \"[*] Clearing potential package manager locks...\"").append(NL)
                append("    rm -f /var/lib/dpkg/lock* /var/cache/apt/archives/lock /var/lib/apt/lists/lock 2>/dev/null || true").append(NL)
                append("    echo \"[*] Automatically repairing any half-configured packages...\"").append(NL)
                append("    export DEBIAN_FRONTEND=noninteractive").append(NL)
                append("    export DEBIAN_PRIORITY=critical").append(NL)
                append("    dpkg --configure -a 2>/dev/null || true").append(NL)
                append("    echo \"[*] Checking desktop dependencies...\"").append(NL)
                append("    if ! command -v vncserver &>/dev/null || ! command -v websockify &>/dev/null || ! command -v dbus-launch &>/dev/null; then").append(NL)
                append("      echo \"[*] Graphical packages are missing. Installing XFCE4, VNC server and noVNC...\"").append(NL)
                append("      echo \"[*] This may take a few minutes. Please wait...\"").append(NL)
                append("      apt-get update").append(NL)
                append("      apt-get install -y -o Dpkg::Options::=\"--force-confdef\" -o Dpkg::Options::=\"--force-confold\" xfce4 xfce4-terminal tigervnc-standalone-server dbus-x11 novnc websockify curl procps </dev/null").append(NL)
                append("    fi").append(NL)
                append("    echo \"[*] Stopping existing VNC sessions...\"").append(NL)
                append("    vncserver -kill :1 2>/dev/null || true").append(NL)
                append("    pkill -9 -f Xvnc 2>/dev/null || true").append(NL)
                append("    pkill -9 -f Xtightvnc 2>/dev/null || true").append(NL)
                append("    pkill -9 -f Xtigervnc 2>/dev/null || true").append(NL)
                append("    pkill -f websockify 2>/dev/null || true").append(NL)
                append("    rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null || true").append(NL)
                append("    echo \"[*] Configuring VNC password...\"").append(NL)
                append("    mkdir -p ~/.vnc").append(NL)
                append("    echo \"kali_operator\" | vncpasswd -f > ~/.vnc/passwd").append(NL)
                append("    chmod 600 ~/.vnc/passwd").append(NL)
                append("    echo \"[*] Configuring XFCE4 desktop startup...\"").append(NL)
                append("    cat << 'EOF' > ~/.vnc/xstartup").append(NL)
                append("#!/bin/sh").append(NL)
                append("unset SESSION_MANAGER").append(NL)
                append("unset DBUS_SESSION_BUS_ADDRESS").append(NL)
                append("startxfce4 &").append(NL)
                append("EOF").append(NL)
                append("    chmod +x ~/.vnc/xstartup").append(NL)
                append("    echo \"[*] Starting VNC server session on display :1 (port \$VNC_PORT)...\"").append(NL)
                append("    vncserver :1 -geometry 1280x720 -depth 24 2>&1 | tee /tmp/vnc.log").append(NL)
                append("    echo \"[*] Starting noVNC proxy websockify on port \$NO_VNC_PORT...\"").append(NL)
                append("    websockify --web=/usr/share/novnc/ \$NO_VNC_PORT 127.0.0.1:\$VNC_PORT &>/dev/null &").append(NL)
                append("    echo \"[+] XFCE4 Graphical desktop successfully launched on Display :1!\"").append(NL)
                append("    echo \"[*] Open noVNC client at http://127.0.0.1:6080/vnc.html\"").append(NL)
                append("    ;;").append(NL)
                append("  stop)").append(NL)
                append("    echo \"[*] Killing VNC server session...\"").append(NL)
                append("    vncserver -kill :1 2>/dev/null || true").append(NL)
                append("    pkill -9 -f Xvnc 2>/dev/null || true").append(NL)
                append("    pkill -9 -f Xtightvnc 2>/dev/null || true").append(NL)
                append("    pkill -9 -f Xtigervnc 2>/dev/null || true").append(NL)
                append("    pkill -f websockify 2>/dev/null || true").append(NL)
                append("    rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null || true").append(NL)
                append("    echo \"[-] Graphical desktop session stopped.\"").append(NL)
                append("    ;;").append(NL)
                append("  status)").append(NL)
                append("    echo \"=== DESKTOP SESSION STATUS ===\"").append(NL)
                append("    if pgrep -f \"Xvnc|Xtightvnc|Xtigervnc\" &>/dev/null; then").append(NL)
                append("      echo \"[+] VNC Server: RUNNING\"").append(NL)
                append("    else").append(NL)
                append("      echo \"[-] VNC Server: STOPPED\"").append(NL)
                append("    fi").append(NL)
                append("    if pgrep -f \"websockify\" &>/dev/null; then").append(NL)
                append("      echo \"[+] noVNC Websockify: RUNNING\"").append(NL)
                append("    else").append(NL)
                append("      echo \"[-] noVNC Websockify: STOPPED\"").append(NL)
                append("    fi").append(NL)
                append("    echo \"=== VNC LOGS (LAST 20 LINES) ===\"").append(NL)
                append("    tail -n 20 ~/.vnc/*.log 2>/dev/null || echo \"No VNC log files found.\"").append(NL)
                append("    ;;").append(NL)
                append("  *)").append(NL)
                append("    echo \"Usage: nethunter-desktop {start|stop|status}\"").append(NL)
                append("    exit 1").append(NL)
                append("    ;;").append(NL)
                append("esac").append(NL)
            }.toString(),

            // nethunter-notebook — temporarily disabled for later
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

        // Deploy nethunter_agent.py and nethunter-agent-cli from assets
        val assetsToDeploy = listOf(
            "nethunter_agent.py" to "nethunter_agent.py",
            "nethunter-agent-cli" to "nethunter-agent-cli"
        )
        for ((assetName, targetName) in assetsToDeploy) {
            val destFile = File(binDir, targetName)
            try {
                context.assets.open(assetName).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                destFile.setExecutable(true, false)
                destFile.setReadable(true, false)
                destFile.setWritable(true, false)
                Log.i("ProotManager", "Successfully deployed agent script: $targetName")
            } catch (e: Exception) {
                Log.e("ProotManager", "Failed to deploy P2P/AI asset script $targetName: ${e.message}")
            }
        }
    }

    private fun deployZshrc(context: Context, rootfsDir: File, distroId: String) {
        val assetName = "zshrc.$distroId"
        val zshrcContent = try {
            context.assets.open(assetName).use { input ->
                input.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read zshrc asset $assetName: ${e.message}")
            return
        }

        // 1. Write to /etc/skel/.zshrc
        val skelDir = File(rootfsDir, "etc/skel")
        if (!skelDir.exists()) skelDir.mkdirs()
        File(skelDir, ".zshrc").writeText(zshrcContent)

        // 2. Write to /root/.zshrc
        val rootHome = File(rootfsDir, "root")
        if (!rootHome.exists()) rootHome.mkdirs()
        File(rootHome, ".zshrc").writeText(zshrcContent)

        // 3. Write to /home/$distroId/.zshrc
        val userHome = File(rootfsDir, "home/$distroId")
        if (userHome.exists()) {
            File(userHome, ".zshrc").writeText(zshrcContent)
        }
    }

    private fun deployVpnHelpDocument(targetDir: File) {
        val helpFile = File(targetDir, "nethunter_docs.md")
        val content = """
# 🐉 NetHunter AI Operator - Kompletní Dokumentace Funkcí

Tento dokument obsahuje přehled všech dostupných příkazů a API funkcí, které můžete používat z terminálu NetHunter AI Operator. Tyto příkazy zajišťují integraci s Android systémem a správu VPN.

## 📱 Hardwarové a Systémové Funkce (Android API Bridge)

Tyto příkazy volají lokální API server (`127.0.0.1:1337`) a umožňují ovládat a číst senzory hostitelského zařízení.

| Příkaz | Popis | Příklad použití |
| :--- | :--- | :--- |
| `nethunter-battery-status` | Vypíše aktuální stav baterie ve formátu JSON. | `nethunter-battery-status` |
| `nethunter-toast <msg>` | Zobrazí na obrazovce vyskakovací Toast upozornění. | `nethunter-toast "Úkol úspěšně dokončen!"` |
| `nethunter-vibrate [ms]` | Rozvibruje zařízení (výchozí doba je 500ms). | `nethunter-vibrate 1000` |
| `nethunter-tts-speak <text>` | Přečte zadaný text pomocí Text-to-Speech (syntéza řeči). | `echo "Firewall breach detected" | nethunter-tts-speak` |
| `nethunter-clipboard-get` | Přečte obsah hostitelské schránky. | `nethunter-clipboard-get` |
| `nethunter-clipboard-set <text>`| Zapíše text do hostitelské schránky. | `nethunter-clipboard-set "MojeTajneHeslo123"` |
| `nethunter-notification -t <t> -c <c>`| Pošle standardní systémovou notifikaci. | `nethunter-notification -t "Upozornění" -c "Skenování hotovo"` |
| `nethunter-wifi-connectioninfo`| Vrátí informace o Wi-Fi síti ve formátu JSON. | `nethunter-wifi-connectioninfo` |
| `nethunter-location` | Vrátí aktuální GPS souřadnice ve formátu JSON. | `nethunter-location` |
| `nethunter-volume [level]` | Získá nebo nastaví hlasitost médií (0-15/100). | `nethunter-volume 10` |
| `nethunter-torch [on|off]` | Zapne nebo vypne svítilnu zařízení. | `nethunter-torch on` |

## 🛡️ Správa AdGuard VPN Firewallu

Tyto skripty umožňují plnou kontrolu nad zabudovaným prémiovým filtrovacím strojem.

| Příkaz | Popis | Příklad použití |
| :--- | :--- | :--- |
| `vpn-on` | Zapne globální VPN / NAT. | `vpn-on` |
| `vpn-off` | Vypne globální VPN / NAT. | `vpn-off` |
| `vpn-cli <action>` | Pokročilé VPN CLI rozhraní pro čtení logů, stavů či AI monitor. | `vpn-cli status` |
| `vpn-bypass <cmd>` | Spustí konkrétní příkaz tak, že úplně obejde VPN zachytávání. | `vpn-bypass curl ipinfo.io` |
| `ignore-vpn [on|off|status]` | Přepne ignorování VPN pro aktuální terminálovou relaci. | `ignore-vpn on` |

## 🧠 AI Mozek VPN (Inference Engine)

NetHunter AI Operator obsahuje lokální AI model pro analýzu síťového provozu, který klasifikuje pakety a detekuje anomálie.

| Příkaz | Popis |
| :--- | :--- |
| `vpn-cli ai start` | Spustí na pozadí démona, který monitoruje spojení a upozorňuje na rizika (vyskakovací Toasty při detekci anomálie). |
| `vpn-cli chat` | Otevře konzoli lokálního AI experta pro analýzu síťových dat. |

## 🎙️ Hlasový Asistent

Aplikace funguje také jako plnohodnotný hlasový asistent integrovaný do systému Android.
Pro jeho správnou funkci je nutné provést následující kroky:

1. **Nastavení API klíče:** V nastavení aplikace vložte platný API klíč vámi vybraného poskytovatele (OpenAI, Anthropic atd.).
2. **Výchozí asistent:** V nastavení samotného Androidu (Aplikace -> Výchozí aplikace -> Digitální asistent) nastavte NetHunter AI Operator jako výchozího asistenta.
3. **Oprávnění mikrofonu:** Ujistěte se, že má aplikace povoleno oprávnění přistupovat k mikrofonu.

## 🌐 Přímé HTTP API Volání

Všechny nástroje výše používají pod kapotou HTTP volání na localhost. Můžete je používat i přímo pomocí `curl`:

* **Kontrola VPN stavu:** `curl -s http://127.0.0.1:1337/vpn`
* **Zapnutí VPN:** `curl -s -X POST http://127.0.0.1:1337/vpn/start`
* **Vypnutí VPN:** `curl -s -X POST http://127.0.0.1:1337/vpn/stop`

---
*Dokument byl automaticky vygenerován NetHunter AI Operatorem.*
""".trimIndent()
        try {
            helpFile.writeText(content)
            helpFile.setReadable(true, false)
            Log.i(TAG, "Created comprehensive manual at: ${helpFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write nethunter_docs.md: ${e.message}")
        }
    }
}
