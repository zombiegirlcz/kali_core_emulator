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
        hasRoot: Boolean = false,
        isDockerImage: Boolean = false
    ): ProotConfig {
        val rootDir = context.filesDir
        val rootfsDir = File(rootDir, rootfsDirName)
        val homeDir = File(rootfsDir, "root")
        val tmpDir = File(rootDir, "tmp")

        val criticalDirs = listOf(
            "system", "dev", "proc", "sys", "tmp", "root", "sdcard",
            "bin", "usr/bin", "usr/sbin", "sbin", "lib", "lib64", "usr/lib", "etc",
            "dev/bus/usb"
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

        updateResolvConf(context, rootfsDir)

        File(homeDir, ".hushlogin").apply { if (!exists()) createNewFile() }

        val setupDoneFile = File(homeDir, ".setup_done")
        val bootstrapRequired = File(homeDir, ".bootstrap_required")
        val distroId = if (rootfsDirName.contains("parrot")) "parrot" else "kali"
        
        deployZshrc(context, rootfsDir, distroId)
        
        if (!setupDoneFile.exists() && !bootstrapRequired.exists()) {
            try {
                bootstrapRequired.createNewFile()
                Log.i(TAG, "Fresh install detected, created .bootstrap_required")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create bootstrap sentinel: ${e.message}")
            }
        }

        createMasterScript(homeDir, distroId, hasRoot)
        createEntrypointScript(homeDir)
        deployVpnHelpDocument(homeDir)
        deployWelcomeProfile(rootfsDir, distroId)
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
        // Šablona launcher.sh je v assets/launcher.sh – nasadí ji deployLauncherScript()
        // a vyplní placeholdery (__PROOT_BIN__, __ROOTFS_DIR__ atd.). Žádné ruční
        // StringBuilder.append peklo, žádná duplikace kódu mezi Docker a non-Docker.
        deployLauncherScript(
            context = context,
            launcherFile = launcherFile,
            rootfsDir = rootfsDir,
            prootBin = prootBin,
            loaderBin = loaderBin,
            tallocLib = tallocLib,
            standaloneProot = standaloneProot,
            standaloneLoader = standaloneLoader,
            tmpDir = tmpDir,
            mountStorage = mountStorage,
            isDockerImage = isDockerImage
        )

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
            // Optimization: Only deploy if missing to speed up startup
            if (file.exists() && file.length() > 0L) {
                file.setExecutable(true, false)
                continue
            }
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
            }
        }

        // Deploy terminalmap binary from assets/bin/
        try {
            val terminalMapFile = File(context.filesDir, "terminalmap")
            if (!terminalMapFile.exists() || terminalMapFile.length() == 0L) {
                context.assets.open("bin/terminalmap").use { input ->
                    terminalMapFile.outputStream().use { output -> input.copyTo(output) }
                }
                terminalMapFile.setExecutable(true, false)
                terminalMapFile.setReadable(true, false)
                Log.i(TAG, "Deployed terminalmap binary (${terminalMapFile.length()} bytes)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "TerminalMap binary not available: ${e.message}")
        }

        // Deploy static fallback binaries
        val staticSuffix = if (suffix == "aarch64") "static-aarch64" else "static-arm32"
        val staticBinaries = listOf(
            "proot.standalone" to "proot-$staticSuffix",
            "loader.standalone" to "loader-$staticSuffix"
        )
        for ((name, asset) in staticBinaries) {
            val file = File(context.filesDir, name)
            if (file.exists() && file.length() > 0L) continue
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

    private fun updateResolvConf(context: Context, rootfsDir: File) {
        try {
            val etcDir = File(rootfsDir, "etc")
            if (!etcDir.exists()) etcDir.mkdirs()
            val resolvConf = File(etcDir, "resolv.conf")
            
            val dnsList = mutableListOf<String>()
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            if (connectivityManager != null) {
                val activeNetwork = connectivityManager.activeNetwork
                if (activeNetwork != null) {
                    val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
                    if (linkProperties != null) {
                        for (dns in linkProperties.dnsServers) {
                            val ip = dns.hostAddress
                            if (!ip.isNullOrEmpty() && !ip.contains(":")) {
                                dnsList.add(ip)
                            }
                        }
                    }
                }
            }
            
            if (dnsList.isEmpty()) {
                val sharedPrefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
                val customDns = sharedPrefs.getString("vpn_dns", "8.8.8.8") ?: "8.8.8.8"
                dnsList.add(customDns)
                if (customDns != "8.8.8.8") {
                    dnsList.add("8.8.8.8")
                }
                dnsList.add("1.1.1.1")
            }
            
            val content = dnsList.joinToString("\n") { "nameserver $it" } + "\n"
            resolvConf.writeText(content)
            resolvConf.setReadable(true, false)
            resolvConf.setWritable(true, false)
            Log.i(TAG, "Dynamically updated resolv.conf with DNS servers: $dnsList")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update resolv.conf: ${e.message}")
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
            append("# DNS resolv.conf is dynamically managed by host app").append(NL)
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
                append("  curl -sSL -o /etc/apt/trusted.gpg.d/parrot-archive-key.asc https://archive.parrotsec.org/parrot/misc/archive.gpg 2>/dev/null || true").append(NL)
                append("elif command -v wget >/dev/null 2>&1; then").append(NL)
                append("  wget -qO /etc/apt/trusted.gpg.d/parrot-archive-key.asc https://archive.parrotsec.org/parrot/misc/archive.gpg 2>/dev/null || true").append(NL)
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
            append("# Fix /lib64 -> usr/lib64 before usrmerge, otherwise base-files preinst fails").append(NL)
            append("if [ -d /lib64 ] && [ -d /usr/lib64 ]; then").append(NL)
            append("  if cmp -s /lib64/ld-linux-aarch64.so.1 /usr/lib64/ld-linux-aarch64.so.1 2>/dev/null; then").append(NL)
            append("    rm /lib64/ld-linux-aarch64.so.1 2>/dev/null").append(NL)
            append("    rmdir /lib64 2>/dev/null").append(NL)
            append("    ln -sf usr/lib64 /lib64").append(NL)
            append("    echo '[*] Fixed /lib64 -> usr/lib64 symlink'").append(NL)
            append("  fi").append(NL)
            append("fi").append(NL)
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

            append("echo '[*] Restoring clean NetHunter Zshrc configurations...'").append(NL)
            append("[ -f /etc/skel/.zshrc.nethunter ] && cp /etc/skel/.zshrc.nethunter /etc/skel/.zshrc").append(NL)
            append("[ -f /root/.zshrc.nethunter ] && cp /root/.zshrc.nethunter /root/.zshrc").append(NL)
            append("[ -f /etc/skel/.zshrc.nethunter ] && [ -d /home/${defaultUser} ] && cp /etc/skel/.zshrc.nethunter /home/${defaultUser}/.zshrc").append(NL)
            append("chown -R ${defaultUser}:${defaultUser} /home/${defaultUser}/.zshrc 2>/dev/null || true").append(NL)

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
            append("echo -e \"nameserver 8.8.8.8\\nnameserver 8.8.4.4\" > /etc/resolv.conf 2>/dev/null || true").append(NL)
            append("rm -f /var/lib/dpkg/lock* 2>/dev/null || true").append(NL)
            
            append("if [ -f /root/.bootstrap_required ]; then").append(NL)
            append("    if /bin/bash /root/bootstrap.sh; then").append(NL)
            append("        rm -f /root/.bootstrap_required").append(NL)
            append("    fi").append(NL)
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
            append("    # Zkopiruje se optimalizovany zshrc pouze pokud neexistuje").append(NL)
            append("    if [ ! -f \"\$zrc\" ]; then").append(NL)
            append("        if [ -f /etc/skel/.zshrc.nethunter ]; then").append(NL)
            append("            cp /etc/skel/.zshrc.nethunter \"\$zrc\"").append(NL)
            append("        elif [ -f /etc/skel/.zshrc ]; then").append(NL)
            append("            cp /etc/skel/.zshrc \"\$zrc\"").append(NL)
            append("        fi").append(NL)
            append("    fi").append(NL)
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
            append("[ -f /etc/motd ] && cat /etc/motd").append(NL)
            append("echo '[*] Starting session...'").append(NL)
            append("ENTRY_SHELL=\$(command -v zsh || echo /bin/bash)").append(NL)
            append("if [ \$# -gt 0 ]; then").append(NL)
            append("    exec \"\$ENTRY_SHELL\" -c \"\$*\"").append(NL)
            append("else").append(NL)
            append("    exec \"\$ENTRY_SHELL\" --login").append(NL)
            append("fi").append(NL)
        }.toString()
        entryFile.writeText(script)
        entryFile.setExecutable(true, false)
    }

    private fun fixLdLinuxSymlinks(context: Context, rootfsDir: File) {
        val tallocFile = File(context.filesDir, "libtalloc.so.2")
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
                } catch (e: Exception) {
                    Log.e(TAG, "fixLdLinuxSymlinks failed for $relPath: ${e.message}")
                }
            }
        }
    }

    private fun deployApiScripts(context: Context, rootfsDir: File) {
        val binDir = File(rootfsDir, "usr/local/bin")
        if (!binDir.exists()) binDir.mkdirs()

        val NL = "\n"



                val scripts = mapOf(
            "apt" to buildString {
                appendLine("#!/bin/sh")
                appendLine("# NetHunter AI Operator APT Wrapper")
                appendLine("export DEBIAN_FRONTEND=noninteractive")
                appendLine("# Fix debconf syntax error if present")
                appendLine("if [ -f /usr/share/debconf/confmodule ] && [ ! -f /usr/share/debconf/confmodule.bak ]; then")
                appendLine("  cp /usr/share/debconf/confmodule /usr/share/debconf/confmodule.bak")
                appendLine("  cat > /usr/share/debconf/confmodule << 'ENDCONF'")
                appendLine("#!/bin/sh")
                appendLine("RET=''")
                appendLine("eval \"\$RET\"=\"")
                appendLine("ENDCONF")
                appendLine("fi")
                appendLine("# Reinforce diversions for systemd tools if missing")
                appendLine("for cmd in systemd-sysusers systemd-tmpfiles journalctl systemctl; do")
                appendLine("  if [ -f /usr/bin/\$cmd ] && [ ! -L /usr/bin/\$cmd ]; then")
                appendLine("    dpkg-divert --add --local --rename --divert /usr/bin/\$cmd.distrib /usr/bin/\$cmd 2>/dev/null")
                appendLine("    ln -sf /bin/true /usr/bin/\$cmd 2>/dev/null")
                appendLine("  fi")
                appendLine("done")
                appendLine("# Restore debconf confmodule if backup exists")
                appendLine("if [ -f /usr/share/debconf/confmodule.bak ] && [ ! -f /usr/share/debconf/confmodule ]; then")
                appendLine("  mv /usr/share/debconf/confmodule.bak /usr/share/debconf/confmodule")
                appendLine("fi")
                appendLine("# Fix half-configured packages")
                appendLine("dpkg --configure -a 2>&1 || true")
                appendLine("# Run real apt")
                appendLine("exec /usr/bin/apt \"\$@\"")
            },
            "apt-get" to buildString {
                appendLine("#!/bin/sh")
                appendLine("# NetHunter AI Operator APT-GET Wrapper")
                appendLine("export DEBIAN_FRONTEND=noninteractive")
                appendLine("# Fix debconf syntax error if present")
                appendLine("if [ -f /usr/share/debconf/confmodule ] && [ ! -f /usr/share/debconf/confmodule.bak ]; then")
                appendLine("  cp /usr/share/debconf/confmodule /usr/share/debconf/confmodule.bak")
                appendLine("  cat > /usr/share/debconf/confmodule << 'ENDCONF'")
                appendLine("#!/bin/sh")
                appendLine("RET=''")
                appendLine("eval \"\$RET\"=\"")
                appendLine("ENDCONF")
                appendLine("fi")
                appendLine("# Reinforce diversions for systemd tools if missing")
                appendLine("for cmd in systemd-sysusers systemd-tmpfiles journalctl systemctl; do")
                appendLine("  if [ -f /usr/bin/\$cmd ] && [ ! -L /usr/bin/\$cmd ]; then")
                appendLine("    dpkg-divert --add --local --rename --divert /usr/bin/\$cmd.distrib /usr/bin/\$cmd 2>/dev/null")
                appendLine("    ln -sf /bin/true /usr/bin/\$cmd 2>/dev/null")
                appendLine("  fi")
                appendLine("done")
                appendLine("# Restore debconf confmodule if backup exists")
                appendLine("if [ -f /usr/share/debconf/confmodule.bak ] && [ ! -f /usr/share/debconf/confmodule ]; then")
                appendLine("  mv /usr/share/debconf/confmodule.bak /usr/share/debconf/confmodule")
                appendLine("fi")
                appendLine("# Fix half-configured packages")
                appendLine("dpkg --configure -a 2>&1 || true")
                appendLine("# Run real apt-get")
                appendLine("exec /usr/bin/apt-get \"\$@\"")
            },
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
            "terminalmap" to buildString {
                appendLine("#!/system/bin/sh")
                appendLine("# terminalmap wrapper — explicitní ld-linux + LD_LIBRARY_PATH pro glibc")
                appendLine("# Proot nasazuje loader jako ld-linux fallback, ale pro glibc binary")
                appendLine("# potřebujeme skutečný dynamic linker z rootfs.")
                appendLine("")
                appendLine("# Hledat ld-linux v rootfs (Kali/Parrot ho má v /lib/aarch64-linux-gnu/)")
            appendLine("for ld in \"/lib/ld-linux-aarch64.so.1\" \"/lib64/ld-linux-aarch64.so.1\" \"/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1\"; do")
                appendLine("    if [ -x \"\$ld\" ]; then")
                appendLine("        LDR=\"\$ld\"")
                appendLine("        break")
                appendLine("    fi")
                appendLine("done")
                appendLine("")
                appendLine("if [ -z \"\$LDR\" ] || [ ! -f \"/data/data/com.linux_core/files/terminalmap\" ]; then")
                appendLine("    echo \"[-] terminalmap: binary or dynamic linker not found\" >&2")
                appendLine("    exit 1")
                appendLine("fi")
                appendLine("")
                appendLine("# LD_LIBRARY_PATH: host filesDir (pro talloc/proot libs) + rootfs lib")
                appendLine("export LD_LIBRARY_PATH=\"/data/data/com.linux_core/files:/lib:/lib/aarch64-linux-gnu:/usr/lib:/usr/lib/aarch64-linux-gnu\"")
                appendLine("exec \"\$LDR\" \"/data/data/com.linux_core/files/terminalmap\" \"\$@\"")
            },
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
        )

        for ((name, content) in scripts) {
            val scriptFile = File(binDir, name)
            try {
                if (scriptFile.exists() && scriptFile.length() == content.length.toLong()) {
                    continue
                }
                scriptFile.writeText(content)
                scriptFile.setExecutable(true, false)
                scriptFile.setReadable(true, false)
                scriptFile.setWritable(true, false)
            } catch (e: Exception) {
                Log.e("ProotManager", "Failed to deploy API script $name: \${e.message}")
            }
        }

        // Deploy unified nh CLI from asset
        val nhFile = File(binDir, "nh")
        try {
            context.assets.open("nh").use { input ->
                nhFile.outputStream().use { output -> input.copyTo(output) }
            }
            nhFile.setExecutable(true, false)
            nhFile.setReadable(true, false)
            nhFile.setWritable(true, false)
            Log.i(TAG, "Deployed unified nh CLI (${nhFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy nh CLI: ${e.message}")
        }

        // Deploy ashell escape script from asset
        val ashellFile = File(binDir, "ashell")
        try {
            context.assets.open("ashell").use { input ->
                ashellFile.outputStream().use { output -> input.copyTo(output) }
            }
            ashellFile.setExecutable(true, false)
            ashellFile.setReadable(true, false)
            ashellFile.setWritable(true, false)
            Log.i(TAG, "Deployed ashell escape script (${ashellFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy ashell: ${e.message}")
        }

        // Deploy Shizuku rish shell into guest
        deployShizukuRish(context, rootfsDir)

        // Create backward-compat symlinks pointing to nh
        val compatNames = listOf(
            // nethunter-* compatibility
            "nethunter",  // full name alias
            "nethunter-battery-status", "nethunter-toast", "nethunter-vibrate",
            "nethunter-tts-speak", "nethunter-clipboard-get", "nethunter-clipboard-set",
            "nethunter-notification", "nethunter-wifi-connectioninfo",
            "nethunter-wifi-control", "nethunter-cellinfo", "nethunter-location",
            "nethunter-map", "nethunter-terminalmap", "nethunter-battery-optimize",
            "nethunter-device-admin", "nethunter-volume", "nethunter-torch",
            "nethunter-log", "nethunter-speech-input", "nethunter-notifications-active",
            "nethunter-apps-usage", "nethunter-accessibility-hierarchy",
            "nethunter-fix-postinst", "nethunter-desktop", "nethunter-api",
            // VPN compatibility
            "vpn-cli", "vpn-on", "vpn-off", "vpn-bypass", "ignore-vpn",
            // old standalone
            "nethunter-agent-cli"
        )
        for (name in compatNames) {
            try {
                val link = File(binDir, name)
                if (!link.exists()) {
                    java.nio.file.Files.createSymbolicLink(link.toPath(), nhFile.toPath())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create symlink $name: ${e.message}")
            }
        }

        val assetsToDeploy = listOf(
            "nethunter_agent.py" to "nethunter_agent.py",
            "bin/terminalmap" to "terminalmap",
            "bin/ifconfig" to "ifconfig",
            "code-server-ctl" to "code-server-ctl",
            "scripts/ai-agent.py" to "ai-agent.py",
            "scripts/vpn-log-viewer.py" to "vpn-log-viewer.py"
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

    /**
     * Deploy Shizuku rish shell (shizuku command) into the guest filesystem.
     * Only deployed for arm64 (aarch64) — other archs are not supported.
     */
    private fun deployShizukuRish(context: Context, rootfsDir: File) {
        val binDir = File(rootfsDir, "usr/local/bin")
        if (!binDir.exists()) binDir.mkdirs()

        // Deploy rish script as 'shizuku' command
        val rishScript = File(binDir, "shizuku")
        val rishDex = File(binDir, "rish_shizuku.dex")

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

    private fun deployZshrc(context: Context, rootfsDir: File, distroId: String) {
        val assetName = "zshrc.$distroId"
        val zshrcContent = try {
            context.assets.open(assetName).use { input ->
                input.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read zshrc asset $assetName: ${e.message}")
            return
        }.replace("\r\n", "\n").replace("\r", "\n")

        // 1. Write to /etc/skel
        val skelDir = File(rootfsDir, "etc/skel")
        if (!skelDir.exists()) skelDir.mkdirs()
        File(skelDir, ".zshrc.nethunter").writeText(zshrcContent)
        val skelZshrc = File(skelDir, ".zshrc")
        if (!skelZshrc.exists()) {
            skelZshrc.writeText(zshrcContent)
        }

        // 2. Write to /root
        val rootHome = File(rootfsDir, "root")
        if (!rootHome.exists()) rootHome.mkdirs()
        File(rootHome, ".zshrc.nethunter").writeText(zshrcContent)
        val rootZshrc = File(rootHome, ".zshrc")
        if (!rootZshrc.exists()) {
            rootZshrc.writeText(zshrcContent)
        }

        // 3. Write to /home/$distroId (skip gracefully if home dir doesn't exist yet — bootstrap.sh handles first-time)
        try {
            val userHome = File(rootfsDir, "home/$distroId")
            val homeParent = userHome.parentFile
            if (homeParent != null && !homeParent.exists()) homeParent.mkdirs()
            if (!userHome.exists()) userHome.mkdirs()
            if (userHome.exists()) {
                File(userHome, ".zshrc.nethunter").writeText(zshrcContent)
                val userZshrc = File(userHome, ".zshrc")
                if (!userZshrc.exists()) {
                    userZshrc.writeText(zshrcContent)
                }
            } else {
                Log.w(TAG, "deployZshrc: /home/$distroId doesn't exist, will be handled by bootstrap.sh")
            }
        } catch (e: Exception) {
            Log.w(TAG, "deployZshrc: /home/$distroId write skipped (${e.message}), will be handled by bootstrap.sh")
        }
    }

    private fun deployVpnHelpDocument(targetDir: File) {
        val helpFile = File(targetDir, "nethunter_docs.md")
        val content = """
# 🐉 NetHunter AI Operator - Kompletní Dokumentace Funkcí

Tento dokument obsahuje přehled všech dostupných příkazů a API funkcí, které můžete používat z terminálu NetHunter AI Operator. Tyto příkazy zajišťují integraci s Android systémem a správu VPN.

## 🚀 Životní cyklus spouštění & struktura PRootu

Při každém startu terminálu zajišťuje `ProotManager` inicializaci a úpravu virtuálního prostředí:

### 📁 Vytvářené adresáře
V rootfs se automaticky ověřuje a vytváří tato adresářová struktura:
`system`, `dev`, `proc`, `sys`, `tmp`, `root`, `sdcard` (pokud je sdcard povolena), `bin`, `usr/bin`, `usr/sbin`, `sbin`, `lib`, `lib64`, `usr/lib`, `etc`.

### 🏳️ Stavové soubory (Sentinely)
- `/root/.hushlogin` - Vypíná výchozí uvítací zprávy shellu.
- `/root/.bootstrap_required` - Vytvoří se při první instalaci a dává pokyn ke spuštění `bootstrap.sh`. Po dokončení se smaže.
- `/root/.setup_done` - Vytvoří se po úspěšném dokončení bootstrap skriptu.

### ⚙️ Automatické úpravy a opravy prostředí
- **Přesměrování systemd příkazů:** Nástroje jako `systemctl`, `service`, `update-rc.d`, `resolvconf`, `journalctl` atd. jsou v unrooted prostředí přesměrovány na `/bin/true`, čímž se předchází selhání instalací balíčků.
- **Oprava zavaděče (linker):** Dynamický zavaděč se kopíruje do `lib/ld-linux-aarch64.so.1` a `lib64/ld-linux-aarch64.so.1`. Knihovna `libtalloc.so.2` se umísťuje do `lib/libtalloc.so.2`.
- **Oprava nefunkčních shell odkazů:** Pokud jsou `bin/sh` nebo `bin/bash` rozbité symlinky, nahradí se skutečnými kopiemi shellů.
- **Předpřipravené API Wrappery:** V `/usr/local/bin` jsou nasazeny vlastní verze `apt`/`apt-get` ošetřující pády `debconf`, `dcheck` pro diagnostiku, `vpn-bypass` pro obcházení VPN filtru (port 13339) a sjednocený CLI nástroj `nh` aliasy starších příkazů (zpětná kompatibilita).

## 🆕 Sjednocený CLI příkaz `nh`

Od verze 4.2 jsou všechny dřívější `nethunter-*`, `vpn-*` a `vpn-cli` příkazy sjednoceny do jednoho CLI:

```bash
nh <kategorie> <akce> [argumenty]
```

**Hlavní kategorie:** `system`, `network`, `vpn`, `agent`, `log`, `device`, `api`, `desktop`, `fix`, `apps`, `usb`, `help`, `list`

**Příklady:**
```bash
nh list                          # seznam všech příkazů
nh help <kategorie>              # nápověda pro kategorii
nh network location              # GPS + Google Maps
nh system battery                # stav baterie
nh vpn on                        # zapnout VPN
nh vpn mitm on                   # zapnout TLS MITM
nh log -n 50 -g TlsMitm          # logcat viewer
```

Staré názvy (`nethunter-toast`, `vpn-cli`, `vpn-on`, `vpn-bypass`, `ignore-vpn`, ...) zůstávají funkční jako symlinky na `nh`.
Klasický příkaz `ifconfig` je k dispozici jako wrapper v `/usr/local/bin/ifconfig` (deleguje na `nh network ifconfig`).

## 📱 Hardwarové a Systémové Funkce (Android API Bridge)

Tyto příkazy volají lokální API server (`127.0.0.1:1337`) a umožňují ovládat a číst senzory hostitelského zařízení. Všechny jsou dostupné jak přes sjednocený CLI `nh <kategorie> <akce>`, tak přes staré aliasy (symlinky).

| Příkaz | Popis | Příklad použití |
| :--- | :--- | :--- |
| `nh system battery` | Vypíše aktuální stav baterie ve formátu JSON. | `nh system battery` |
| `nh system toast <msg>` | Zobrazí na obrazovce vyskakovací Toast upozornění. | `nh system toast "Úkol úspěšně dokončen!"` |
| `nh system vibrate [ms]` | Rozvibruje zařízení (výchozí doba je 500ms). | `nh system vibrate 1000` |
| `nh system tts-speak <text>` | Přečte zadaný text pomocí Text-to-Speech (syntéza řeči). | `echo "Firewall breach detected" | nh system tts-speak` |
| `nh system clipboard get` | Přečte obsah hostitelské schránky. | `nh system clipboard get` |
| `nh system clipboard set <text>`| Zapíše text do hostitelské schránky. | `nh system clipboard set "MojeTajneHeslo123"` |
| `nh system notification -t <t> -c <c>`| Pošle standardní systémovou notifikaci. | `nh system notification -t "Upozornění" -c "Skenování hotovo"` |
| `nh network wifi`| Vrátí informace o Wi-Fi síti ve formátu JSON. | `nh network wifi` |
| `nh network cell` | Zobrazí informace o mobilní síti — operátor, signál (dBm), typ sítě (5G/4G/3G), věže. | `nh network cell` |
| `nh network location` | Vrátí aktuální GPS souřadnice + odkaz na Google Maps pro otevření v mapách. | `nh network location` |
| `nh network map` | Spustí TerminalMap interaktivní mapovač OpenStreetMap s aktuální lokací. | `nh network map` |
| `nh network ifconfig [rozhraní]` | Zobrazí síťová rozhraní hostitelského Androidu (IP, MAC, MTU, statistiky). | `nh network ifconfig wlan0` |
| `ifconfig [rozhraní]` | Stejné jako `nh network ifconfig`, dostupné jako samostatný wrapper v `/usr/local/bin/ifconfig`. | `ifconfig wlan0` |
| `nh system volume [level]` | Získá nebo nastaví hlasitost médií (0-15/100). | `nh system volume 10` |
| `nh system torch on|off` | Zapne nebo vypne svítilnu zařízení. | `nh system torch on` |
| `nh log [-n N] [-g P]`| Barevné zobrazení logcat záznamů aplikace (V=šedá, D=modrá, I=zelená, W=žlutá, E/F=červená). | `nh log -n 50 -g "LocalApiServer"` |
| `nh api share on|off|status`| Ovládá sdílení API serveru do sítě (0.0.0.0 vs 127.0.0.1). | `nh api share on` |
| `nh log set lvl 1-5` | Nastaví úroveň logování v settings UI (1=Error, 2=Warn, 3=Info, 4=Debug, 5=Verbose). | `nh log set lvl 4` |

## 🔧 Diagnostické nástroje

### nethunter-log

Python skript pro barevné formátované zobrazení logcat záznamů aplikace bez nutnosti ADB. Od verze 4.2 je dostupný přes sjednocený CLI `nh log` (případně starý alias `nethunter-log`).

```bash
# Výchozí: posledních 100 řádků
nh log
nethunter-log

# Posledních 50 řádků
nh log 50
nh log -n 50

# Filtrování podle vzoru (case-insensitive)
nh log -g "TlsMitm"
nh log -n 200 -g "LocalApiServer"

# Nastavení úrovně logování (sync s UI)
nh log set lvl 1   # Error
nh log set lvl 2   # Warn
nh log set lvl 3   # Info (výchozí)
nh log set lvl 4   # Debug
nh log set lvl 5   # Verbose
```

Barevné schéma podle log úrovně:
- **V** (Verbose): šedá / tlumená
- **D** (Debug): modrá
- **I** (Info): zelená
- **W** (Warn): žlutá
- **E/F** (Error/Fatal): červená tučná

Automatické zvýraznění klíčových slov: `error`/`denied`/`fail` = červeně, `success`/`established` = zeleně.

HTTP API endpoint: `GET /app/logs?limit=N`

## 🛡️ Správa AdGuard VPN Firewallu

Tyto skripty umožňují plnou kontrolu nad zabudovaným prémiovým filtrovacím strojem. Všechny jsou dostupné jak přes sjednocený CLI `nh vpn`, tak přes staré aliasy (symlinky).

| Příkaz | Popis | Příklad použití |
| :--- | :--- | :--- |
| `nh vpn on` | Zapne globální VPN / NAT. | `nh vpn on` |
| `nh vpn off` | Vypne globální VPN / NAT. | `nh vpn off` |
| `nh vpn status` | Vrátí stav VPN (running/stopped) + uptime + bytes. | `nh vpn status` |
| `nh vpn mitm on|off` | Zapne/vypne TLS MITM rozhraní. | `nh vpn mitm on` |
| `nh vpn mitm status` | Stav MITM rozhraní + aktivní session. | `nh vpn mitm status` |
| `nh vpn mitm ca` | Zobrazí/exportuje Root CA certifikát. | `nh vpn mitm ca > /tmp/ca.crt` |
| `nh vpn logs [json]` | Formátovaný výpis dešifrovaného MITM provozu. | `nh vpn logs` |
| `nh vpn bypass <cmd>` | Spustí konkrétní příkaz tak, že úplně obejde VPN zachytávání. | `nh vpn bypass curl ipinfo.io` |
| `nh vpn ignore on|off` | Přepne ignorování VPN pro aktuální terminálovou relaci. | `nh vpn ignore on` |
| `nh firewall block|unblock <ip>` | Přidá/odebere IP z blocklistu firewallu. | `nh firewall block 1.2.3.4` |

## 🔍 TLS MITM Inspection

TLS MITM (Man-in-the-Middle) umožňuje plně dešifrovat HTTPS a další TLS provoz přímo ve VPN tunelu. Proxy provádí TLS handshake jak s klientem, tak se vzdáleným serverem a transparentně přeposílá plaintext.

### Konfigurace MITM v runtime

- MITM CA cert: `assets/certs/mitm-ca.crt`
- MITM CA privátní klíč: `assets/certs/mitm-ca.p12`
- Alias v PKCS12: `nethunter_mitm_ca`

### 🔐 Instalace Root CA certifikátu

Pro správné fungování MITM dešifrování musí být Root CA certifikát nainstalován.

#### Získání certifikátu

```bash
# Zobrazit certifikát v terminálu
nh vpn mitm ca

# Uložit do souboru
nh vpn mitm ca > /tmp/nethunter-ca.crt

# Nebo přímo přes HTTP API
curl -s http://127.0.0.1:1337/vpn/mitm/ca > /tmp/nethunter-ca.crt
```

#### Instalace do Kali/PRoot trust store

```bash
nh vpn mitm ca > /usr/local/share/ca-certificates/nethunter-mitm.crt
update-ca-certificates
```

#### Instalace do Androidu

1. Uložit certifikát: `nh vpn mitm ca > /sdcard/nethunter-ca.crt`
2. Na telefonu: **Nastavení → Zabezpečení → Šifrování a přihlašovací údaje → Instalovat certifikát → Certifikát CA**
3. Vybrat soubor `nethunter-ca.crt` ze storage
4. Potvrdit instalaci (systém vyžádá PIN/otisk prstu)

> **Omezení:** Od Androidu 7.0+ aplikace standardně nedůvěřují uživatelským certifikátům. Pro dešifrování provozu ostatních appek je nutný root a přesun CA do systémového trust store (`/system/etc/security/cacerts/`). Aplikace s certificate pinning (banky, Google, Signal, WhatsApp) odmítnou MITM spojení i s nainstalovaným CA.

### CLI příkazy (`nh vpn mitm`)

```bash
# Zapnutí MITM rozhraní
nh vpn mitm on

# Vypnutí MITM rozhraní
nh vpn mitm off

# Stav MITM rozhraní + aktivní session
nh vpn mitm status

# Stáhnout/zobrazit Root CA certifikát
nh vpn mitm ca

# Uložit Root CA certifikát do souboru pro instalaci
nh vpn mitm ca > /tmp/nethunter-ca.crt

# Formátovaný dešifrovaný provoz
nh vpn logs

# JSON výstup dešifrovaného provozu
nh vpn logs json
```

### HTTP API (port 1337)

```http
POST /vpn/mitm
Body: on|off

GET /vpn/mitm
{"mitm":"on","active_sessions":2,"sessions":[{"port":54321,"snippet":"[CLIENT->SERVER] GET / ..."}]}

GET /vpn/mitm/ca
(vrátí PEM certifikát s Content-Type: application/x-x509-ca-cert)

GET /vpn/mitm/logs
GET /vpn/mitm/logs?format=json
```

### Zobrazení v terminálové relaci

Po zapnutí MITM se průběžně ukládá dešifrovaný provoz do snippet bufferu. Využijte `nh vpn logs` pro čitelné zobrazení.

### Klíčové třídy

| Třída | Role |
| :--- | :--- |
| `TlsClientHelloParser` | Parser TLS Client Hello zprávy, detekce TLS a extrakce SNI |
| `TlsMitmEngine` | Singleton, spravuje aktivní MITM session (`TlsMitmSession`) |
| `TlsMitmSession` | Jedna MITM relace — naváže spojení ke vzdálenému serveru, provede TLS handshake, podepíše certifikát a proxy plaintext |
| `RootCaInstaller` | Načte MITM CA, podepíše server certifikát, vytvoří `SSLContext` s forged certem |
| `MitmCertSigner` | BouncyCastle podepisování listových certifikátů |
| `VpnNatEngine.kt` | Hlavní NAT engine, detekuje TLS Client Hello v `handleTcpPacket` a předává payload do `TlsMitmEngine` |
| `VpnSecurityTab.kt` | UI záložka pro zobrazení MITM provozu (žlutý indikátor `TLS MITM INTERCEPT` + karta `LIVE DECRYPTED TLS TRAFFIC`) |
| `VpnSettingsTab.kt` | Přepínač `TLS MITM Inspection` v Nastavení |
| `LocalApiServer.kt` | Endpointy `/vpn/mitm`, `/vpn/mitm/ca` a `/vpn/mitm/logs` pro vzdálené ovládání |

## 🧠 AI Mozek VPN (Inference Engine)

NetHunter AI Operator obsahuje lokální AI model pro analýzu síťového provozu, který klasifikuje pakety a detekuje anomálie.

| Příkaz | Popis |
| :--- | :--- |
| `nh agent start` | Spustí na pozadí démona, který monitoruje spojení a upozorňuje na rizika (vyskakovací Toasty při detekci anomálie). |
| `nh agent chat` | Otevře konzoli lokálního AI experta pro analýzu síťových dat. |
| `nh agent status` | Zobrazí stav agenta (port 13338). |
| `nh agent analyze` | Spustí jednorázovou analýzu aktuálního provozu. |

## 🎙️ Hlasový Asistent

Aplikace funguje také jako plnohodnotný hlasový asistent integrovaný do systému Android.
Pro jeho správnou funkci je nutné provést následující kroky:

1. **Nastavení API klíče:** V nastavení aplikace vložte platný API klíč vámi vybraného poskytovatele (OpenAI, Anthropic atd.).
2. **Výchozí asistent:** V nastavení samotného Androidu (Aplikace -> Výchozí aplikace -> Digitální asistent) nastavte NetHunter AI Operator jako výchozího asistenta.
3. **Oprávnění mikrofonu:** Ujistěte se, že má aplikace povoleno oprávnění přistupovat k mikrofonu.

## 🔌 USB Host Mode — Ovládání připojených zařízení

Od verze 4.3 je k dispozici plná podpora **USB Host (OTG)** přes Android `UsbManager` API.
Připojená zařízení (např. druhá deska, USB flashdisk, sériový adaptér) jsou dostupná přes API bridge.

### CLI příkazy (`nh usb`)

| Příkaz | Popis | Příklad použití |
| :--- | :--- | :--- |
| `nh usb list` | Zobrazí všechna připojená USB zařízení (VID:PID, rozhraní, endpointy) | `nh usb list` |
| `nh usb permission <device>` | Vyžádá oprávnění pro přístup k zařízení (Android dialog) | `nh usb permission /dev/bus/usb/001/002` |
| `nh usb claim <device> [iface]` | Claimuje rozhraní (výchozí 0) a otevře spojení | `nh usb claim /dev/bus/usb/001/002 0` |
| `nh usb release <device>` | Uvolní rozhraní a zavře spojení | `nh usb release /dev/bus/usb/001/002` |
| `nh usb send <device> <file>` | Pošle binární soubor přes první OUT bulk endpoint | `nh usb send /dev/bus/usb/001/002 exploit.bin` |
| `nh usb bulk <device> <ep> [file]` | Bulk transfer na konkrétní endpoint (IN čte, OUT zapisuje) | `nh usb bulk /dev/bus/usb/001/002 2 data.bin` |
| `nh usb control <device> [req] [val] [idx] [file]` | Control transfer na endpoint 0 | `nh usb control /dev/bus/usb/001/002 64 0 0 config.bin` |

### HTTP API (port 1337)

```http
GET /usb/devices                     → seznam zařízení (JSON array)
POST /usb/permission                 → vyžádat oprávnění (body: device_name)
POST /usb/claim                      → claim rozhraní (JSON: device_name, interface_id)
POST /usb/release                    → uvolnit rozhraní (JSON: device_name)
POST /usb/bulk_transfer              → bulk transfer (JSON: device_name, endpoint, data_base64, timeout, direction)
POST /usb/control_transfer           → control transfer (JSON: device_name, request_type, request, value, index, data_base64)
POST /usb/send                       → raw send (JSON: device_name, data_base64)
```

### Příklad poslání exploitu

```bash
# 1. Zjisti zařízení
nh usb list

# 2. Vyžádej oprávnění
nh usb permission "/dev/bus/usb/001/002"

# 3. Claimni rozhraní
nh usb claim "/dev/bus/usb/001/002" 0

# 4. Pošli binární data
nh usb send "/dev/bus/usb/001/002" exploit.bin
```

> **Poznámka:** USB Host vyžaduje, aby první telefon podporoval OTG. Druhé zařízení se musí tvářit jako USB device (gadget režim) - jinak ho `UsbManager` neuvidí.

## 🌐 Přímé HTTP API Volání

Všechny nástroje výše používají pod kapotou HTTP volání na localhost. Můžete je používat i přímo pomocí `curl`:

* **Kontrola VPN stavu:** `curl -s http://127.0.0.1:1337/vpn`
* **Zapnutí VPN:** `curl -s -X POST http://127.0.0.1:1337/vpn/start`
* **Vypnutí VPN:** `curl -s -X POST http://127.0.0.1:1337/vpn/stop`
* **Stažení Root CA:** `curl -s http://127.0.0.1:1337/vpn/mitm/ca > ca.crt`
* **Logcat záznamy:** `curl -s http://127.0.0.1:1337/app/logs?limit=100`
* **USB zařízení:** `curl -s http://127.0.0.1:1337/usb/devices`
* **USB poslat data:** `curl -s -X POST -H "Content-Type: application/json" -d '{"device_name":"/dev/bus/usb/001/002","data_base64":"$(base64 -w0 exploit.bin)"}' http://127.0.0.1:1337/usb/send`

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

    private fun deployWelcomeProfile(rootfsDir: File, distroId: String) {
        val profileDir = File(rootfsDir, "etc/profile.d")
        if (!profileDir.exists()) profileDir.mkdirs()

        val isParrot = distroId == "parrot"

        val profileScript = buildString {
            appendLine("#!/bin/sh")
            appendLine("# NetHunter AI Operator - Welcome (shown once per session)")
            appendLine("SENTINEL=\$HOME/.nethunter_welcome_shown")
            appendLine("if [ -f \"\$SENTINEL\" ]; then return 0 2>/dev/null || exit 0; fi")
            appendLine("touch \"\$SENTINEL\" 2>/dev/null")
            appendLine()

            if (isParrot) {
                appendLine("echo \"\"")
                appendLine("echo \"  \\033[1;33m╭━━━╮╱╱╱╱╱╱╱╱╱╭╮╱╭━━━┳━━━╮\\033[0m\"")
                appendLine("echo \"  \\033[1;33m┃╭━╮┃╱╱╱╱╱╱╱╱╭╯╰╮┃╭━╮┃╭━╮┃\\033[0m\"")
                appendLine("echo \"  \\033[1;33m┃╰━╯┣━━┳━┳━┳━┻╮╭╯┃┃╱┃┃╰━━╮\\033[0m\"")
                appendLine("echo \"  \\033[1;33m┃╭━━┫╭╮┃╭┫╭┫╭╮┃┃╱┃┃╱┃┣━━╮┃\\033[0m\"")
                appendLine("echo \"  \\033[1;33m┃┃╱╱┃╭╮┃┃┃┃┃╰╯┃╰╮┃╰━╯┃╰━╯┃\\033[0m\"")
                appendLine("echo \"  \\033[1;33m╰╯╱╱╰╯╰┻╯╰╯╰━━┻━╯╰━━━┻━━━╯\\033[0m\"")
                appendLine("echo \"  \\033[1;32m   NetHunter AI Operator v4.1\\033[0m\"")
                appendLine("echo \"  \\033[1;32m      Parrot OS Security\\033[0m\"")
            } else {
                appendLine("echo \"\"")
                appendLine("echo \"  \\033[1;34m##################################################\\033[0m\"")
                appendLine("echo \"  \\033[1;34m##                                              ##\\033[0m\"")
                appendLine("echo \"  \\033[1;34m##  88      a8P         db        88        88  ##\\033[0m\"")
                appendLine("echo \"  \\033[1;34m##  88    .88'         d88b       88        88  ##\\033[0m\"")
                appendLine("echo \"  \\033[1;34m##  88   88'          d8''8b      88        88  ##\\033[0m\"")
                appendLine("echo \"  \\033[1;34m##  88 d88           d8'  '8b     88        88  ##\\033[0m\"")
                appendLine("echo \"  \\033[1;34m##  8888'88.        d8YaaaaY8b    88        88  ##\\033[0m\"")
                appendLine("echo \"  \\033[1;34m##  88P   Y8b      d8''''''''8b   88        88  ##\\033[0m\"")
                appendLine("echo \"  \\033[1;34m##  88     '88.   d8'        '8b  88        88  ##\\033[0m\"")
                appendLine("echo \"  \\033[1;34m##  88       Y8b d8'          '8b 888888888 88  ##\\033[0m\"")
                appendLine("echo \"  \\033[1;34m##                                              ##\\033[0m\"")
                appendLine("echo \"  \\033[1;34m####  ############# NetHunter ####################\\033[0m\"")
            }

            appendLine()
            appendLine("echo \"  \\033[1;36m─────────────────────────────────────────────────────────\\033[0m\"")
            appendLine("echo \"  \\033[1;32m   📡  RYCHLÉ PŘÍKAZY / QUICK HELP\\033[0m\"")
            appendLine("echo \"  \\033[1;36m─────────────────────────────────────────────────────────\\033[0m\"")
            appendLine("echo \"  \\033[0;32m     nh network location\\033[0m              GPS + Google Maps\"")
            appendLine("echo \"  \\033[0;32m     nh network cell\\033[0m                 mobilní síť (5G/4G/3G)\"")
            appendLine("echo \"  \\033[0;32m     nh network map\\033[0m                  OSM terminálová mapa\"")
            appendLine("echo \"  \\033[0;32m     nh system battery\\033[0m               stav baterie\"")
            appendLine("echo \"  \\033[0;32m     nh network wifi\\033[0m                 WiFi info\"")
            appendLine("echo \"  \\033[0;32m     nh system volume\\033[0m               hlasitost\"")
            appendLine("echo \"  \\033[0;32m     nh system torch\\033[0m               svítilna\"")
            appendLine("echo \"  \\033[0;32m     nh system toast\\033[0m                Android toast\"")
            appendLine("echo \"  \\033[0;32m     nh system vibrate\\033[0m             vibrace\"")
            appendLine("echo \"  \\033[0;32m     nh system tts-speak\\033[0m           přečíst text nahlas\"")
            appendLine("echo \"  \\033[0;32m     nh system notification\\033[0m         systémová notifikace\"")
            appendLine("echo \"  \\033[0;32m     nh system clipboard\\033[0m           schránka (čtení/zápis)\"")
            appendLine("echo \"  \\033[0;32m     nh log [-n N] [-g P]\\033[0m          logcat viewer\"")
            appendLine("echo \"  \\033[0;32m     nh usb list\\033[0m                    USB zařízení (OTG)\"")
            appendLine()
            appendLine("echo \"  \\033[1;36m─────────────────────────────────────────────────────────\\033[0m\"")
            appendLine("echo \"  \\033[1;33m   🛡️  VPN\\033[0m\"")
            appendLine("echo \"  \\033[1;36m─────────────────────────────────────────────────────────\\033[0m\"")
            appendLine("echo \"  \\033[0;33m     nh vpn on|off\\033[0m                  VPN zapnout/vypnout\"")
            appendLine("echo \"  \\033[0;33m     nh vpn mitm on|off\\033[0m            TLS MITM zapnout/vypnout\"")
            appendLine("echo \"  \\033[0;33m     nh vpn mitm status\\033[0m            MITM stav + session\"")
            appendLine("echo \"  \\033[0;33m     nh vpn logs\\033[0m                    MITM formátované logy\"")
            appendLine("echo \"  \\033[0;33m     nh vpn status\\033[0m                  stav VPN\"")
            appendLine("echo \"  \\033[0;33m     nh vpn bypass\\033[0m                 obejít VPN pro příkaz\"")
            appendLine("echo \"  \\033[0;33m     nh vpn ignore\\033[0m                VPN bypass pro session\"")
            appendLine()
            appendLine("echo \"  \\033[1;36m─────────────────────────────────────────────────────────\\033[0m\"")
            appendLine("echo \"  \\033[1;33m   🖥️  DESKTOP\\033[0m\"")
            appendLine("echo \"  \\033[1;36m─────────────────────────────────────────────────────────\\033[0m\"")
            appendLine("echo \"  \\033[0;33m     nh desktop start|stop|status\\033[0m  XFCE4 GUI (noVNC :6080)\"")
            appendLine()
            appendLine("echo \"  \\033[1;36m─────────────────────────────────────────────────────────\\033[0m\"")
            appendLine("echo \"  \\033[1;33m   </>  EDITOR (VS Code)\\033[0m\"")
            appendLine("echo \"  \\033[1;36m─────────────────────────────────────────────────────────\\033[0m\"")
            appendLine("echo \"  \\033[0;33m     code-server-ctl start\\033[0m           VS Code v prohlížeči (:8443)\"")
            appendLine("echo \"  \\033[0;33m     code-server-ctl status\\033[0m          stav editoru\"")
            appendLine("echo \"  \\033[0;33m     code-server-ctl password\\033[0m         zobrazit heslo\"")
            appendLine("echo \"  \\033[0;33m     code-server-ctl install\\033[0m         nainstalovat code-server\"")
            appendLine()
            appendLine("echo \"  \\033[1;36m─────────────────────────────────────────────────────────\\033[0m\"")
            appendLine("echo \"  \\033[0;90m     📖 nh list  → seznam všech příkazů\\033[0m\"")
            appendLine("echo \"  \\033[0;90m     📖 nh help  → nápověda\\033[0m\"")
            appendLine("echo \"  \\033[0;90m     📖 cat nethunter_docs.md  → plná dokumentace\\033[0m\"")
            appendLine("echo \"  \\033[1;36m─────────────────────────────────────────────────────────\\033[0m\"")
            appendLine("echo \"\"")
            appendLine("echo \"  \\033[0;90m     github.com/zombiegirlcz/kali_core_emulator\\033[0m\"")
            appendLine("echo \"\"")
        }

        val welcomeFile = File(profileDir, "nethunter-welcome.sh")
        try {
            welcomeFile.writeText(profileScript)
            welcomeFile.setExecutable(true, false)
            welcomeFile.setReadable(true, false)
            Log.i(TAG, "Deployed welcome profile: ${welcomeFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write nethunter-welcome.sh: ${e.message}")
        }

        val motdDir = File(rootfsDir, "etc")
        if (!motdDir.exists()) motdDir.mkdirs()

        val motd = StringBuilder()
        motd.append(NL)

        if (isParrot) {
            motd.append("  \u001b[1;33m╭━━━╮╱╱╱╱╱╱╱╱╱╭╮╱╭━━━┳━━━╮\u001b[0m").append(NL)
            motd.append("  \u001b[1;33m┃╭━╮┃╱╱╱╱╱╱╱╱╭╯╰╮┃╭━╮┃╭━╮┃\u001b[0m").append(NL)
            motd.append("  \u001b[1;33m┃╰━╯┣━━┳━┳━┳━┻╮╭╯┃┃╱┃┃╰━━╮\u001b[0m").append(NL)
            motd.append("  \u001b[1;33m┃╭━━┫╭╮┃╭┫╭┫╭╮┃┃╱┃┃╱┃┣━━╮┃\u001b[0m").append(NL)
            motd.append("  \u001b[1;33m┃┃╱╱┃╭╮┃┃┃┃┃╰╯┃╰╮┃╰━╯┃╰━╯┃\u001b[0m").append(NL)
            motd.append("  \u001b[1;33m╰╯╱╱╰╯╰┻╯╰╯╰━━┻━╯╰━━━┻━━━╯\u001b[0m").append(NL)
            motd.append("  \u001b[1;32m   NetHunter AI Operator v4.1\u001b[0m").append(NL)
            motd.append("  \u001b[1;32m      Parrot OS Security\u001b[0m").append(NL)
        } else {
            motd.append("  \u001b[1;34m##################################################\u001b[0m").append(NL)
            motd.append("  \u001b[1;34m##                                              ##\u001b[0m").append(NL)
            motd.append("  \u001b[1;34m##  88      a8P         db        88        88  ##\u001b[0m").append(NL)
            motd.append("  \u001b[1;34m##  88    .88'         d88b       88        88  ##\u001b[0m").append(NL)
            motd.append("  \u001b[1;34m##  88   88'          d8''8b      88        88  ##\u001b[0m").append(NL)
            motd.append("  \u001b[1;34m##  88 d88           d8'  '8b     88        88  ##\u001b[0m").append(NL)
            motd.append("  \u001b[1;34m##  8888'88.        d8YaaaaY8b    88        88  ##\u001b[0m").append(NL)
            motd.append("  \u001b[1;34m##  88P   Y8b      d8''''''''8b   88        88  ##\u001b[0m").append(NL)
            motd.append("  \u001b[1;34m##  88     '88.   d8'        '8b  88        88  ##\u001b[0m").append(NL)
            motd.append("  \u001b[1;34m##  88       Y8b d8'          '8b 888888888 88  ##\u001b[0m").append(NL)
            motd.append("  \u001b[1;34m##                                              ##\u001b[0m").append(NL)
            motd.append("  \u001b[1;34m####  ############# NetHunter ####################\u001b[0m").append(NL)
        }

        motd.append(NL)
        motd.append("  \u001b[1;36m─────────────────────────────────────────────────────────\u001b[0m").append(NL)
        motd.append("  \u001b[1;32m   📡  RYCHLÉ PŘÍKAZY / QUICK HELP\u001b[0m").append(NL)
        motd.append("  \u001b[1;36m─────────────────────────────────────────────────────────\u001b[0m").append(NL)
        motd.append("  \u001b[0;32m     nh network location\u001b[0m              GPS + Google Maps").append(NL)
        motd.append("  \u001b[0;32m     nh network cell\u001b[0m                 mobilní síť (5G/4G/3G)").append(NL)
        motd.append("  \u001b[0;32m     nh network map\u001b[0m                  OSM terminálová mapa").append(NL)
        motd.append("  \u001b[0;32m     nh system battery\u001b[0m               stav baterie").append(NL)
        motd.append("  \u001b[0;32m     nh network wifi\u001b[0m                 WiFi info").append(NL)
        motd.append("  \u001b[0;32m     nh system volume\u001b[0m               hlasitost").append(NL)
        motd.append("  \u001b[0;32m     nh system torch\u001b[0m               svítilna").append(NL)
        motd.append("  \u001b[0;32m     nh system toast\u001b[0m                Android toast").append(NL)
        motd.append("  \u001b[0;32m     nh system vibrate\u001b[0m             vibrace").append(NL)
        motd.append("  \u001b[0;32m     nh system tts-speak\u001b[0m           přečíst text nahlas").append(NL)
        motd.append("  \u001b[0;32m     nh system notification\u001b[0m         systémová notifikace").append(NL)
        motd.append("  \u001b[0;32m     nh system clipboard\u001b[0m           schránka (čtení/zápis)").append(NL)
        motd.append("  \u001b[0;32m     nh log [-n N] [-g P]\u001b[0m          logcat viewer").append(NL)
        motd.append("  \u001b[0;32m     nh usb list\u001b[0m                    USB zařízení (OTG)").append(NL)
        motd.append("  \u001b[0;32m     ifconfig [rozhraní]\u001b[0m                 síťová rozhraní (přes Android API)").append(NL)
        motd.append(NL)
        motd.append("  \u001b[1;36m─────────────────────────────────────────────────────────\u001b[0m").append(NL)
        motd.append("  \u001b[1;33m   🛡️  VPN\u001b[0m").append(NL)
        motd.append("  \u001b[1;36m─────────────────────────────────────────────────────────\u001b[0m").append(NL)
        motd.append("  \u001b[0;33m     nh vpn on|off\u001b[0m                  VPN zapnout/vypnout").append(NL)
        motd.append("  \u001b[0;33m     nh vpn mitm on|off\u001b[0m            TLS MITM zapnout/vypnout").append(NL)
        motd.append("  \u001b[0;33m     nh vpn mitm status\u001b[0m            MITM stav + session").append(NL)
        motd.append("  \u001b[0;33m     nh vpn logs\u001b[0m                    MITM formátované logy").append(NL)
        motd.append("  \u001b[0;33m     nh vpn status\u001b[0m                  stav VPN").append(NL)
        motd.append("  \u001b[0;33m     nh vpn bypass\u001b[0m                 obejít VPN pro příkaz").append(NL)
        motd.append("  \u001b[0;33m     nh vpn ignore\u001b[0m                VPN bypass pro session").append(NL)
        motd.append(NL)
        motd.append("  \u001b[1;36m─────────────────────────────────────────────────────────\u001b[0m").append(NL)
        motd.append("  \u001b[1;33m   🖥️  DESKTOP\u001b[0m").append(NL)
        motd.append("  \u001b[1;36m─────────────────────────────────────────────────────────\u001b[0m").append(NL)
        motd.append("  \u001b[0;33m     nh desktop start|stop|status\u001b[0m  XFCE4 GUI (noVNC :6080)").append(NL)
        motd.append(NL)
        motd.append("  \u001b[1;36m─────────────────────────────────────────────────────────\u001b[0m").append(NL)
        motd.append("  \u001b[1;33m   </>  EDITOR (VS Code)\u001b[0m").append(NL)
        motd.append("  \u001b[1;36m─────────────────────────────────────────────────────────\u001b[0m").append(NL)
        motd.append("  \u001b[0;33m     code-server-ctl start\u001b[0m           VS Code v prohlížeči (:8443)").append(NL)
        motd.append("  \u001b[0;33m     code-server-ctl status\u001b[0m          stav editoru").append(NL)
        motd.append("  \u001b[0;33m     code-server-ctl password\u001b[0m         zobrazit heslo").append(NL)
        motd.append("  \u001b[0;33m     code-server-ctl install\u001b[0m         nainstalovat code-server").append(NL)
        motd.append(NL)
        motd.append("  \u001b[1;36m─────────────────────────────────────────────────────────\u001b[0m").append(NL)
        motd.append("  \u001b[0;90m     📖 nh list  → seznam všech příkazů\u001b[0m").append(NL)
        motd.append("  \u001b[0;90m     📖 nh help  → nápověda\u001b[0m").append(NL)
        motd.append("  \u001b[0;90m     📖 cat nethunter_docs.md  → plná dokumentace\u001b[0m").append(NL)
        motd.append("  \u001b[1;36m─────────────────────────────────────────────────────────\u001b[0m").append(NL)
        motd.append(NL)

        try {
            File(motdDir, "motd").writeText(motd.toString())
            Log.i(TAG, "Deployed /etc/motd for $distroId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write /etc/motd: ${e.message}")
        }
    }

    /**
     * Nasadí launcher.sh z assets/ do filesDir (nebo rootDir) a vyplní
     * placeholdery skutečnými cestami. Společné pro Docker i non-Docker –
     * šablona assets/launcher.sh se větví přes __DOCKER_MODE__.
     *
     * @param launcherFile   cílový soubor (typicky rootDir/launcher.sh)
     * @param isDockerImage  true = Docker image, false = běžná distribuce
     */
    private fun deployLauncherScript(
        context: Context,
        launcherFile: File,
        rootfsDir: File,
        prootBin: File,
        loaderBin: File,
        tallocLib: File,
        standaloneProot: File,
        standaloneLoader: File,
        tmpDir: File,
        mountStorage: Boolean,
        isDockerImage: Boolean
    ) {
        val template = try {
            context.assets.open("launcher.sh").use { input ->
                input.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read launcher.sh from assets: ${e.message}")
            return
        }.replace("\r\n", "\n").replace("\r", "\n")

        val logPrefix = if (isDockerImage) "ProotDocker" else "ProotLauncher"
        val sdcardMount = if (mountStorage) " -b /sdcard" else ""

        val rendered = template
            .replace("__PROOT_BIN__", prootBin.absolutePath)
            .replace("__LOADER_BIN__", loaderBin.absolutePath)
            .replace("__TALLOC_LIB__", tallocLib.absolutePath)
            .replace("__STANDALONE_PROOT__", standaloneProot.absolutePath)
            .replace("__STANDALONE_LOADER__", standaloneLoader.absolutePath)
            .replace("__ROOTFS_DIR__", rootfsDir.absolutePath)
            .replace("__ROOTFS_NAME__", rootfsDir.name)
            .replace("__TMP_DIR__", tmpDir.absolutePath)
            .replace("__FILES_DIR__", context.filesDir.absolutePath)
            .replace("__SDCARD_MOUNT__", sdcardMount)
            .replace("__DOCKER_MODE__", if (isDockerImage) "1" else "0")
            .replace("__LOG_PREFIX__", logPrefix)

        // Ověř, že všechny placeholdery byly nahrazeny (jinak by se spustil
        // skript s __PROOT_BIN__ v textu – tichá chyba).
        val unfilled = Regex("__[A-Z_]+__").findAll(rendered).map { it.value }.toList()
        if (unfilled.isNotEmpty()) {
            Log.w(TAG, "launcher.sh has unfilled placeholders: $unfilled")
        }

        try {
            launcherFile.parentFile?.mkdirs()
            synchronized(this) {
                launcherFile.writeText(rendered)
                launcherFile.setExecutable(true, false)
                launcherFile.setReadable(true, false)
            }
            Log.i(TAG, "Deployed launcher.sh (${launcherFile.length()} bytes, docker=$isDockerImage, log=$logPrefix)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write launcher.sh: ${e.message}")
        }
    }
}
