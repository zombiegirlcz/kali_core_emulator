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
            val baseFlags = "-v 0 --kill-on-exit --link2symlink -0 -r ${rootfsDir.absolutePath} -b /dev -b /proc -b /sys -b /system -b ${tmpDir.absolutePath}:/tmp -w /root"
            val sdcardMount = if (mountStorage) " -b /sdcard" else ""
            append("log -t ProotLauncher \"[PROOT] Executing proot now...\"").append(NL)
            append("exec ${'$'}USE_PROOT ${baseFlags}${sdcardMount} /bin/bash /root/entrypoint.sh \"\$@\"").append(NL)
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

        val formatHelper = """
#!/usr/bin/env python3
import sys, os

# ANSI Colors
RST = "\033[0m"
BOLD = "\033[1m"
DIM = "\033[2m"
ITALIC = "\033[3m"
UNDERLINE = "\033[4m"

RED = "\033[38;5;196m"
GREEN = "\033[38;5;46m"
YELLOW = "\033[38;5;226m"
BLUE = "\033[38;5;39m"
MAGENTA = "\033[38;5;201m"
CYAN = "\033[38;5;51m"
ORANGE = "\033[38;5;208m"
WHITE = "\033[38;5;255m"
GRAY = "\033[38;5;244m"

def box(title, content, color=CYAN):
    lines = content.splitlines()
    width = max(len(title) + 4, max((len(line) for line in lines), default=0) + 4)

    out = []
    out.append(f"{color}{BOLD}╔═ {WHITE}{title} {color}{'═' * (width - len(title) - 3)}╗{RST}")
    for line in lines:
        out.append(f"{color}║ {WHITE}{line}{' ' * (width - len(line) - 2)} {color}║{RST}")
    out.append(f"{color}╚{'═' * width}╝{RST}")
    return "\n".join(out)

def table(headers, rows, color=BLUE):
    if not rows:
        return f"{GRAY}No data available.{RST}"

    widths = [len(h) for h in headers]
    for row in rows:
        for i, val in enumerate(row):
            widths[i] = max(widths[i], len(str(val)))

    header_line = "  ".join(f"{WHITE}{BOLD}{h.upper():<{widths[i]}}{RST}" for i, h in enumerate(headers))
    separator = f"{color}{'═' * (sum(widths) + len(headers)*2 - 2)}{RST}"

    out = [header_line, separator]
    for row in rows:
        out.append("  ".join(f"{WHITE}{str(val):<{widths[i]}}{RST}" for i, val in enumerate(row)))

    return "\n".join(out)

def status(label, value, is_good=True):
    color = GREEN if is_good else RED
    if is_good is None: color = YELLOW
    icon = "✔" if is_good else "✘"
    if is_good is None: icon = "●"
    return f"{WHITE}{BOLD}{label:<18} {CYAN}│ {color}{icon} {WHITE}{value}{RST}"

if __name__ == "__main__":
    if len(sys.argv) > 1:
        cmd = sys.argv[1]
        if cmd == "box":
            print(box(sys.argv[2], sys.argv[3]))
        elif cmd == "status":
            print(status(sys.argv[2], sys.argv[3], sys.argv[4].lower() == "true"))
""".trimIndent()

        val scripts = mapOf(
            "nethunter_format.py" to formatHelper,
            "nethunter_format" to formatHelper,
            "nethunter-list" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("python3 -c \"").append(NL)
                append("import sys, os").append(NL)
                append("sys.path.append('/usr/local/bin')").append(NL)
                append("from nethunter_format import table, box").append(NL)
                append("tools = [").append(NL)
                append("    ('nethunter-battery-status', 'Zobrazí stav baterie'),").append(NL)
                append("    ('nethunter-wifi-connectioninfo', 'Informace o Wi-Fi síti'),").append(NL)
                append("    ('nethunter-location', 'GPS souřadnice + Mapy'),").append(NL)
                append("    ('nethunter-map', 'Interaktivní OSM mapa'),").append(NL)
                append("    ('nethunter-cellinfo', 'Informace o mobilní síti'),").append(NL)
                append("    ('nethunter-volume', 'Změna hlasitosti médií'),").append(NL)
                append("    ('nethunter-torch', 'Ovládání svítilny'),").append(NL)
                append("    ('nethunter-toast', 'Zobrazení Android Toastu'),").append(NL)
                append("    ('nethunter-vibrate', 'Vibrace zařízení'),").append(NL)
                append("    ('nethunter-tts-speak', 'Text-to-Speech (syntéza)'),").append(NL)
                append("    ('nethunter-clipboard-get', 'Přečíst host schránku'),").append(NL)
                append("    ('nethunter-clipboard-set', 'Zapsat do host schránky'),").append(NL)
                append("    ('nethunter-apps-usage', 'Statistiky využití aplikací'),").append(NL)
                append("    ('nethunter-notifications-active', 'Aktivní notifikace'),").append(NL)
                append("    ('nethunter-log', 'Barevný Logcat prohlížeč'),").append(NL)
                append("    ('vpn-on / vpn-off', 'Globální VPN vypínač'),").append(NL)
                append("    ('vpn-cli', 'Pokročilé VPN & MITM CLI'),").append(NL)
                append("    ('vpn-bypass', 'Bypass VPN pro příkaz'),").append(NL)
                append("    ('ignore-vpn', 'Bypass VPN pro session'),").append(NL)
                append("]").append(NL)
                append("headers = ['PŘÍKAZ', 'POPIS FUNKCE']").append(NL)
                append("print(box('Dostupné NetHunter Nástroje 🐉', table(headers, tools)))").append(NL)
                append("\"").append(NL)
            }.toString(),

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
                append("  curl -s -X POST http://127.0.0.1:1337/vpn/stop >/dev/null").append(NL)
                append("  python3 -c \"import sys, os; sys.path.append('/usr/local/bin'); from nethunter_format import status; print(status('NetHunter VPN', 'DISABLED', False))\"").append(NL)
                append("  exit 0").append(NL)
                append("fi").append(NL)
                append("was_running=false").append(NL)
                append("if curl -s http://127.0.0.1:1337/vpn | grep -q '\"running\":true'; then").append(NL)
                append("  was_running=true").append(NL)
                append("  curl -s -X POST http://127.0.0.1:1337/vpn/stop >/dev/null").append(NL)
                append("  sleep 1").append(NL)
                append("fi").append(NL)
                append("echo \"[*] Executing (VPN off): \$@\"").append(NL)
                append("\"\$@\"").append(NL)
                append("exit_code=\$?").append(NL)
                append("if [ \"\$was_running\" = \"true\" ]; then").append(NL)
                append("  curl -s -X POST http://127.0.0.1:1337/vpn/start >/dev/null").append(NL)
                append("fi").append(NL)
                append("exit \$exit_code").append(NL)
            }.toString(),

            "vpn-on" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("if [ \$# -eq 0 ]; then").append(NL)
                append("  curl -s -X POST http://127.0.0.1:1337/vpn/start >/dev/null").append(NL)
                append("  python3 -c \"import sys, os; sys.path.append('/usr/local/bin'); from nethunter_format import status; print(status('NetHunter VPN', 'ENABLED', True))\"").append(NL)
                append("  exit 0").append(NL)
                append("fi").append(NL)
                append("if ! curl -s http://127.0.0.1:1337/vpn | grep -q '\"running\":true'; then").append(NL)
                append("  curl -s -X POST http://127.0.0.1:1337/vpn/start >/dev/null").append(NL)
                append("  sleep 1").append(NL)
                append("fi").append(NL)
                append("echo \"[*] Executing (VPN on): \$@\"").append(NL)
                append("\"\$@\"").append(NL)
                append("exit \$?").append(NL)
            }.toString(),

            "vpn-cli" to context.assets.open("vpn-cli").bufferedReader().readText(),

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
                append("def analyze_network(filter_ip=None, minutes=60):").append(NL)
                append("    logs = get_logs()").append(NL)
                append("    now_ms = int(time.time() * 1000)").append(NL)
                append("    cutoff = now_ms - (minutes * 60 * 1000)").append(NL)
                append("    recent = [l for l in logs if l.get('timestamp',0) >= cutoff]").append(NL)
                append("    if filter_ip: recent = [l for l in recent if l.get('srcIp')==filter_ip or l.get('dstIp')==filter_ip]").append(NL)
                append("    if not recent: return 'Žádný provoz za posledních %d minut.' % minutes").append(NL)
                append("    total = len(recent)").append(NL)
                append("    anomalies = [l for l in recent if l.get('category') in ('CRITICAL','SUSPICIOUS')]").append(NL)
                append("    dst_c = {}").append(NL)
                append("    for l in recent: dst_c[l.get('dstIp','?')] = dst_c.get(l.get('dstIp','?'),0)+1").append(NL)
                append("    top5 = sorted(dst_c.items(), key=lambda x:x[1], reverse=True)[:5]").append(NL)
                append("    ent = [l.get('entropy',0) for l in recent if l.get('entropy',0)>0]").append(NL)
                append("    avg_e = sum(ent)/len(ent) if ent else 0").append(NL)
                append("    report = f'📊 Analýza posledních {minutes} minut:\\n'").append(NL)
                append("    report += f'  Celkem spojení: {total}\\n'").append(NL)
                append("    report += f'  Anomálie (CRITICAL/SUSPICIOUS): {len(anomalies)}\\n'").append(NL)
                append("    report += f'  Průměrná entropie: {avg_e:.2f}\\n'").append(NL)
                append("    report += f'  Top destinace: {top5}\\n'").append(NL)
                append("    if anomalies:").append(NL)
                append("        report += '  🔴 Podezřelé:\\n'").append(NL)
                append("        for a in anomalies[:5]: report += f\"    {a.get('srcIp')} -> {a.get('dstIp')}:{a.get('dstPort')} [{a.get('category')}]\\n\"").append(NL)
                append("    return report").append(NL)
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
                append("    print('🤖 NetHunter Local AI Expert (v2.0)')").append(NL)
                append("    print('Příkazy:')").append(NL)
                append("    print('  stav       - Poslední spojení')").append(NL)
                append("    print('  analýza    - Statistiky za poslední hodinu')").append(NL)
                append("    print('  ip X.X.X.X - Provoz k/z konkrétní IP')").append(NL)
                append("    print('  !příkaz    - Spustit shell příkaz')").append(NL)
                append("    print('  exit       - Ukončit')").append(NL)
                append("    while True:").append(NL)
                append("        user_input = input('👤 Ty: ').strip()").append(NL)
                append("        lower = user_input.lower()").append(NL)
                append("        if lower in ['exit', 'quit']: break").append(NL)
                append("        if 'stav' in lower:").append(NL)
                append("            logs = get_logs()").append(NL)
                append("            print(f\"🤖 Agent: {analyze_log(logs[0]) if logs else 'Žádné logy.'}\")").append(NL)
                append("        elif lower.startswith('ip '):").append(NL)
                append("            target_ip = user_input[3:].strip()").append(NL)
                append("            print(f'🤖 Agent: {analyze_network(filter_ip=target_ip)}')").append(NL)
                append("        elif 'analýza' in lower or 'analyza' in lower or 'analyz' in lower:").append(NL)
                append("            print(f'🤖 Agent: {analyze_network()}')").append(NL)
                append("        elif user_input.startswith('!'):").append(NL)
                append("            res = run_shell(user_input[1:])").append(NL)
                append("            print(f\"🤖 Shell: {res.get('stdout','')}{res.get('stderr','')}\")").append(NL)
                append("        else: print(\"🤖 Agent: Napiš 'stav', 'analýza', 'ip X.X.X.X' nebo '!příkaz'.\")").append(NL)
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
                append("  python3 -c \"import sys, os; sys.path.append('/usr/local/bin'); from nethunter_format import status; print(status('Session VPN Bypass', 'ACTIVE', True))\"").append(NL)
                append("elif [ \"\$mode\" = \"off\" ]; then").append(NL)
                append("  curl -s -X POST \"http://127.0.0.1:1337/vpn/ignore?session_id=\$NETHUNTER_SESSION_ID&ignored=false\" >/dev/null").append(NL)
                append("  python3 -c \"import sys, os; sys.path.append('/usr/local/bin'); from nethunter_format import status; print(status('Session VPN Bypass', 'DISABLED', False))\"").append(NL)
                append("elif [ \"\$mode\" = \"status\" ]; then").append(NL)
                append("  res=\$(curl -s \"http://127.0.0.1:1337/vpn/ignore?session_id=\$NETHUNTER_SESSION_ID\")").append(NL)
                append("  python3 -c \"import sys, json, os; sys.path.append('/usr/local/bin'); from nethunter_format import status; d = json.loads(sys.argv[1]); print(status('Session Bypass', 'ON' if d.get('ignored') else 'OFF', d.get('ignored')))\" \"\$res\"").append(NL)
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
                append("curl -s -X POST --data-binary \"\$text\" http://127.0.0.1:1337/toast > /dev/null").append(NL)
                append("python3 -c \"import sys, os; sys.path.append('/usr/local/bin'); from nethunter_format import status; print(status('Android Toast', 'Sent to host'))\"").append(NL)
            }.toString(),

            "nethunter-battery-status" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("DATA=\$(curl -s http://127.0.0.1:1337/battery)").append(NL)
                append("echo \"\$DATA\" | python3 -c \"").append(NL)
                append("import sys, json, os").append(NL)
                append("sys.path.append('/usr/local/bin')").append(NL)
                append("from nethunter_format import box, status").append(NL)
                append("try:").append(NL)
                append("    d = json.load(sys.stdin)").append(NL)
                append("    if 'error' in d: print(d['error'])").append(NL)
                append("    else:").append(NL)
                append("        res = []").append(NL)
                append("        res.append(status('Percentage', f\\\"{d.get('percentage')}%\\\", d.get('percentage', 0) > 20))").append(NL)
                append("        res.append(status('Status', d.get('status', 'unknown'), d.get('status') == 'charging'))").append(NL)
                append("        res.append(status('Health', d.get('health', 'unknown'), d.get('health') == 'good'))").append(NL)
                append("        res.append(status('Temperature', f\\\"{d.get('temperature')}°C\\\", d.get('temperature', 0) < 45))").append(NL)
                append("        res.append(status('Voltage', f\\\"{d.get('voltage')} mV\\\"))").append(NL)
                append("        print(box('Battery Status 🔋', '\\n'.join(res)))").append(NL)
                append("except Exception as e: print(f'Error: {e}')").append(NL)
                append("\"").append(NL)
            }.toString(),

            "nethunter-speech-input" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("DATA=\$(curl -s http://127.0.0.1:1337/voice_input)").append(NL)
                append("python3 -c \"import sys, json, os; sys.path.append('/usr/local/bin'); from nethunter_format import box; d = json.loads(sys.argv[1]); print(box('Voice Input 🎙️', d.get('text', ''))) if 'text' in d else print(d.get('error', 'Unknown error'))\" \"\$DATA\"").append(NL)
            }.toString(),

            "nethunter-vibrate" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("duration=\${1:-500}").append(NL)
                append("DATA=\$(curl -s -X POST -d \"\$duration\" http://127.0.0.1:1337/vibrate)").append(NL)
                append("python3 -c \"import sys, json, os; sys.path.append('/usr/local/bin'); from nethunter_format import status; d = json.loads(sys.argv[1]); print(status('Vibration', f\\\"{d.get('duration')} ms\\\", True))\" \"\$DATA\"").append(NL)
            }.toString(),

            "nethunter-tts-speak" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("if [ -p /dev/stdin ]; then").append(NL)
                append("  text=\$(cat)").append(NL)
                append("else").append(NL)
                append("  text=\"\$*\"").append(NL)
                append("fi").append(NL)
                append("curl -s -X POST --data-binary \"\$text\" http://127.0.0.1:1337/tts > /dev/null").append(NL)
                append("python3 -c \"import sys, os; sys.path.append('/usr/local/bin'); from nethunter_format import status; print(status('TTS Speak', 'Sent to host engine'))\"").append(NL)
            }.toString(),

            "nethunter-clipboard-get" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("DATA=\$(curl -s http://127.0.0.1:1337/clipboard)").append(NL)
                append("if [ \"\$1\" = \"--raw\" ]; then").append(NL)
                append("  echo \"\$DATA\" | python3 -c \"import sys, json; print(json.load(sys.stdin).get('text', ''))\"").append(NL)
                append("else").append(NL)
                append("  echo \"\$DATA\" | python3 -c \"import sys, json, os; sys.path.append('/usr/local/bin'); from nethunter_format import box; d = json.load(sys.stdin); print(box('Android Clipboard 📋', d.get('text', '')))\"").append(NL)
                append("fi").append(NL)
            }.toString(),

            "nethunter-clipboard-set" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("if [ -p /dev/stdin ]; then").append(NL)
                append("  text=\$(cat)").append(NL)
                append("else").append(NL)
                append("  text=\"\$*\"").append(NL)
                append("fi").append(NL)
                append("curl -s -X POST --data-binary \"\$text\" http://127.0.0.1:1337/clipboard > /dev/null").append(NL)
                append("python3 -c \"import sys, os; sys.path.append('/usr/local/bin'); from nethunter_format import status; print(status('Android Clipboard', 'Text copied to host'))\"").append(NL)
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
                append("curl -s -X POST -H \"Content-Type: application/json\" -d \"{\\\"title\\\":\\\"\$title\\\",\\\"content\\\":\\\"\$content\\\"}\" http://127.0.0.1:1337/notification > /dev/null").append(NL)
                append("python3 -c \"import sys, os; sys.path.append('/usr/local/bin'); from nethunter_format import status; print(status('Notification', 'Posted to host'))\"").append(NL)
            }.toString(),

            "nethunter-wifi-connectioninfo" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("DATA=\$(curl -s http://127.0.0.1:1337/wifi)").append(NL)
                append("echo \"\$DATA\" | python3 -c \"").append(NL)
                append("import sys, json, os").append(NL)
                append("sys.path.append('/usr/local/bin')").append(NL)
                append("from nethunter_format import box, status").append(NL)
                append("try:").append(NL)
                append("    d = json.load(sys.stdin)").append(NL)
                append("    if 'error' in d: print(d['error'])").append(NL)
                append("    else:").append(NL)
                append("        res = []").append(NL)
                append("        res.append(status('SSID', d.get('ssid', 'N/A')))\n")
                append("        res.append(status('BSSID', d.get('bssid', 'N/A')))\n")
                append("        res.append(status('Signal', f\\\"{d.get('rssi')} dBm\\\", d.get('rssi', -100) > -70))\n")
                append("        res.append(status('Speed', f\\\"{d.get('link_speed_mbps', '?')} Mbps\\\"))\n")
                append("        print(box('Wi-Fi Connection 📡', '\\n'.join(res)))").append(NL)
                append("except Exception as e: print(f'Error: {e}')").append(NL)
                append("\"").append(NL)
            }.toString(),

            "nethunter-cellinfo" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("DATA=\$(curl -s http://127.0.0.1:1337/cellinfo)").append(NL)
                append("echo \"\$DATA\" | python3 -c \"").append(NL)
                append("import sys, json, os").append(NL)
                append("sys.path.append('/usr/local/bin')").append(NL)
                append("from nethunter_format import box, status, table").append(NL)
                append("try:").append(NL)
                append("    d = json.load(sys.stdin)").append(NL)
                append("    res = []").append(NL)
                append("    res.append(status('Carrier', d.get('carrier', 'Unknown')))\n")
                append("    res.append(status('Network', d.get('network_type', 'Unknown')))\n")
                append("    res.append(status('Data State', d.get('data_state', 'Unknown'), d.get('data_state') == 'CONNECTED'))\n")
                append("    res.append(status('Roaming', str(d.get('is_roaming', False)), not d.get('is_roaming')))\n")
                append("    print(box('Cellular Info 📶', '\\n'.join(res)))").append(NL)
                append("    cells = d.get('cells', [])").append(NL)
                append("    if cells:").append(NL)
                append("        headers = ['#', 'TYPE', 'ID', 'SIGNAL', 'STATUS']").append(NL)
                append("        rows = []").append(NL)
                append("        for i, c in enumerate(cells):").append(NL)
                append("            cid = c.get('cid', 'N/A')").append(NL)
                append("            rows.append([i+1, c.get('type'), cid, f\\\"{c.get('signal_dbm', '?')} dBm\\\", 'REG' if c.get('registered') else 'NB'])\n")
                append("        print('\\n' + box('Nearby Cells', table(headers, rows)))").append(NL)
                append("except Exception as e: print(f'Error: {e}')").append(NL)
                append("\"").append(NL)
            }.toString(),

            "nethunter-location" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("DATA=\$(curl -s http://127.0.0.1:1337/location)").append(NL)
                append("echo \"\$DATA\" | python3 -c \"").append(NL)
                append("import sys, json, os").append(NL)
                append("sys.path.append('/usr/local/bin')").append(NL)
                append("from nethunter_format import box, status").append(NL)
                append("try:").append(NL)
                append("    d = json.load(sys.stdin)").append(NL)
                append("    if 'error' in d: print(d['error'])").append(NL)
                append("    else:").append(NL)
                append("        res = []").append(NL)
                append("        res.append(status('Latitude', d.get('latitude')))\n")
                append("        res.append(status('Longitude', d.get('longitude')))\n")
                append("        res.append(status('Accuracy', f\\\"±{d.get('accuracy', 0):.1f}m\\\"))\n")
                append("        res.append(status('Provider', d.get('provider')))\n")
                append("        res.append(f\\\"\\\\n🗺️  {d.get('maps_url')}\\\")\n")
                append("        print(box('GPS Location 📍', '\\n'.join(res)))").append(NL)
                append("except Exception as e: print(f'Error: {e}')").append(NL)
                append("\"").append(NL)
            }.toString(),

            "nethunter-map" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("python3 -c \"import sys, os; sys.path.append('/usr/local/bin'); from nethunter_format import status; print(status('TerminalMap', 'Fetching GPS location...'))\"").append(NL)
                append("MAP_DATA=\$(curl -s http://127.0.0.1:1337/map)").append(NL)
                append("echo \"\$MAP_DATA\" | python3 -c \"").append(NL)
                append("import sys, json, os").append(NL)
                append("sys.path.append('/usr/local/bin')").append(NL)
                append("from nethunter_format import box, status").append(NL)
                append("try:").append(NL)
                append("    d = json.load(sys.stdin)").append(NL)
                append("    if d.get('success'):").append(NL)
                append("        res = []").append(NL)
                append("        res.append(status('Latitude', d.get('latitude')))").append(NL)
                append("        res.append(status('Longitude', d.get('longitude')))").append(NL)
                append("        print(box('Location Found 📍', '\\n'.join(res)))").append(NL)
                append("        print(box('TerminalMap Controls', 'Arrows/HJKL: Pan  |  A/+/-: Zoom  |  Q: Quit', '\\033[38;5;226m'))").append(NL)
                append("    else: print(d.get('error', 'GPS error'))").append(NL)
                append("except: print('Error parsing location')").append(NL)
                append("\"").append(NL)
                append("terminalmap").append(NL)
            }.toString(),

            "nethunter-terminalmap" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("# Alias for nethunter-map (TerminalMap map viewer with current GPS location)").append(NL)
                append("nethunter-map").append(NL)
            }.toString(),

            "nethunter-apps-usage" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("DATA=\$(curl -s http://127.0.0.1:1337/apps/usage)").append(NL)
                append("python3 -c \"").append(NL)
                append("import sys, json, os").append(NL)
                append("sys.path.append('/usr/local/bin')").append(NL)
                append("from nethunter_format import box, table").append(NL)
                append("d = json.loads(sys.argv[1])").append(NL)
                append("if 'apps' in d:").append(NL)
                append("    headers = ['PACKAGE', 'TIME (MIN)', 'LAST USED']").append(NL)
                append("    rows = []").append(NL)
                append("    for a in d['apps']:").append(NL)
                append("        rows.append([a['packageName'], f\\\"{a['totalTimeInForeground']//60000}\\\", 'Recent'])").append(NL)
                append("    print(box('App Usage Statistics 📊', table(headers, rows)))").append(NL)
                append("else: print(d.get('error', 'Unknown error'))").append(NL)
                append("\" \"\$DATA\"").append(NL)
            }.toString(),

            "nethunter-notifications-active" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("DATA=\$(curl -s http://127.0.0.1:1337/notifications/active)").append(NL)
                append("python3 -c \"").append(NL)
                append("import sys, json, os").append(NL)
                append("sys.path.append('/usr/local/bin')").append(NL)
                append("from nethunter_format import box, table").append(NL)
                append("d = json.loads(sys.argv[1])").append(NL)
                append("if 'notifications' in d:").append(NL)
                append("    headers = ['PACKAGE', 'TITLE', 'TEXT']").append(NL)
                append("    rows = []").append(NL)
                append("    for n in d['notifications']:").append(NL)
                append("        rows.append([n['package'], n['title'], n['text'][:30]])").append(NL)
                append("    print(box('Active Notifications 🔔', table(headers, rows)))").append(NL)
                append("else: print(d.get('error', 'Unknown error'))").append(NL)
                append("\" \"\$DATA\"").append(NL)
            }.toString(),

            "nethunter-accessibility-hierarchy" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("curl -s http://127.0.0.1:1337/accessibility/hierarchy").append(NL)
            }.toString(),

            "nethunter-battery-optimize" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("action=\"\${1:-status}\"").append(NL)
                append("case \"\$action\" in").append(NL)
                append("  status)").append(NL)
                append("    DATA=\$(curl -s http://127.0.0.1:1337/battery/optimize)").append(NL)
                append("    python3 -c \"import sys, json, os; sys.path.append('/usr/local/bin'); from nethunter_format import status; d = json.loads(sys.argv[1]); print(status('Battery Optimize', 'IGNORED (OK)' if d.get('ignored') else 'RESTRICTED', d.get('ignored')))\" \"\$DATA\"").append(NL)
                append("    ;;").append(NL)
                append("  request) curl -s -X POST http://127.0.0.1:1337/battery/optimize ;;").append(NL)
                append("  *) echo \"Usage: nethunter-battery-optimize [status|request]\"; exit 1 ;;").append(NL)
                append("esac").append(NL)
            }.toString(),

            "nethunter-wifi-control" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("state=\"\${1:-status}\"").append(NL)
                append("DATA=\$(curl -s -X POST -d \"\$state\" http://127.0.0.1:1337/wifi)").append(NL)
                append("python3 -c \"import sys, json, os; sys.path.append('/usr/local/bin'); from nethunter_format import status; d = json.loads(sys.argv[1]); print(status('Wi-Fi Control', 'ENABLED' if d.get('enabled') else 'DISABLED', d.get('success')))\" \"\$DATA\"").append(NL)
            }.toString(),

            "nethunter-device-admin" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("action=\"\${1:-status}\"").append(NL)
                append("case \"\$action\" in").append(NL)
                append("  status)").append(NL)
                append("    DATA=\$(curl -s http://127.0.0.1:1337/device/admin)").append(NL)
                append("    python3 -c \"import sys, json, os; sys.path.append('/usr/local/bin'); from nethunter_format import status; d = json.loads(sys.argv[1]); print(status('Device Admin', 'ACTIVE' if d.get('active') else 'INACTIVE', d.get('active')))\" \"\$DATA\"").append(NL)
                append("    ;;").append(NL)
                append("  request) curl -s -X POST http://127.0.0.1:1337/device/admin ;;").append(NL)
                append("  lock) curl -s -X POST http://127.0.0.1:1337/device/lock ;;").append(NL)
                append("  *) echo \"Usage: nethunter-device-admin [status|request|lock]\"; exit 1 ;;").append(NL)
                append("esac").append(NL)
            }.toString(),

            "nethunter-volume" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("if [ -z \"\$1\" ]; then").append(NL)
                append("  DATA=\$(curl -s http://127.0.0.1:1337/volume)").append(NL)
                append("  echo \"\$DATA\" | python3 -c \"").append(NL)
                append("import sys, json, os").append(NL)
                append("sys.path.append('/usr/local/bin')").append(NL)
                append("from nethunter_format import box, status").append(NL)
                append("try:").append(NL)
                append("    d = json.load(sys.stdin)").append(NL)
                append("    if 'error' in d: print(d['error'])").append(NL)
                append("    else:").append(NL)
                append("        cur = d.get('volume', 0)").append(NL)
                append("        mx = d.get('max_volume', 15)").append(NL)
                append("        pct = int(cur/mx*100) if mx > 0 else 0").append(NL)
                append("        res = [status('Level', f\\\"{cur}/{mx} ({pct}%)\\\", pct > 0)]").append(NL)
                append("        print(box('Media Volume 🔊', '\\n'.join(res)))").append(NL)
                append("except Exception as e: print(f'Error: {e}')").append(NL)
                append("\"").append(NL)
                append("else").append(NL)
                append("  curl -s -X POST -d \"\$1\" http://127.0.0.1:1337/volume >/dev/null").append(NL)
                append("  echo \"✅ Hlasitost nastavena na \$1\"").append(NL)
                append("fi").append(NL)
            }.toString(),

            "nethunter-torch" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("state=\${1:-on}").append(NL)
                append("DATA=\$(curl -s -X POST -d \"\$state\" http://127.0.0.1:1337/torch)").append(NL)
                append("python3 -c \"").append(NL)
                append("import sys, json, os").append(NL)
                append("sys.path.append('/usr/local/bin')").append(NL)
                append("from nethunter_format import status").append(NL)
                append("d = json.loads(sys.argv[1])").append(NL)
                append("print(status('Flashlight', d.get('state', 'unknown'), d.get('state') == 'on'))").append(NL)
                append("\" \"\$DATA\"").append(NL)
            }.toString(),

            "nethunter-log" to StringBuilder().apply {
                append("#!/usr/bin/env python3").append(NL)
                append("import sys, urllib.request, re").append(NL)
                append("RST, DIM, BOLD = '\\033[0m', '\\033[2m', '\\033[1m'").append(NL)
                append("RED, GREEN, YELLOW = '\\033[1;31m', '\\033[1;32m', '\\033[1;33m'").append(NL)
                append("BLUE, MAGENTA, CYAN = '\\033[1;34m', '\\033[1;35m', '\\033[1;36m'").append(NL)
                append("LEVEL_COLORS = {'V': DIM, 'D': BLUE, 'I': GREEN, 'W': YELLOW, 'E': RED, 'F': BOLD + RED}").append(NL)
                append("LOG_RE = re.compile(r'^(\\d{2}-\\d{2}\\s\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+([VDIWEF])/([^(:\\s]+)\\s*(?:\\(\\s*(\\d+)\\))?\\s*:\\s*(.*)$')").append(NL)
                append("def usage():").append(NL)
                append("    print(f\"{MAGENTA}{BOLD}╔══════════════════════════════════════════╗{RST}\")").append(NL)
                append("    print(f\"{MAGENTA}{BOLD}║         📋 NetHunter Log Viewer          ║{RST}\")").append(NL)
                append("    print(f\"{MAGENTA}{BOLD}╚══════════════════════════════════════════╝{RST}\")").append(NL)
                append("    print(\"Usage: nethunter-log [options] [lines]\")").append(NL)
                append("    print(\"\\nOptions:\")").append(NL)
                append("    print(f\"  {CYAN}-n, --lines <number>{RST}   Show last N log lines (default: 100)\")").append(NL)
                append("    print(f\"  {CYAN}-g, --grep <pattern>{RST}   Filter logs matching pattern\")").append(NL)
                append("    print(f\"  {CYAN}-h, --help{RST}           Show help menu\")").append(NL)
                append("    sys.exit(0)").append(NL)
                append("def main():").append(NL)
                append("    limit, grep_pattern = 100, None").append(NL)
                append("    args = sys.argv[1:]").append(NL)
                append("    i = 0").append(NL)
                append("    while i < len(args):").append(NL)
                append("        arg = args[i]").append(NL)
                append("        if arg in ('-h', '--help'): usage()").append(NL)
                append("        elif arg in ('-n', '--lines'):").append(NL)
                append("            if i+1 < len(args):").append(NL)
                append("                try: limit = int(args[i+1])").append(NL)
                append("                except: pass").append(NL)
                append("                i += 2").append(NL)
                append("            else: usage()").append(NL)
                append("        elif arg in ('-g', '--grep'):").append(NL)
                append("            if i+1 < len(args):").append(NL)
                append("                grep_pattern = args[i+1].lower()").append(NL)
                append("                i += 2").append(NL)
                append("            else: usage()").append(NL)
                append("        else:").append(NL)
                append("            try: limit = int(arg)").append(NL)
                append("            except: pass").append(NL)
                append("            i += 1").append(NL)
                append("    url = f\"http://127.0.0.1:1337/app/logs?limit={limit}\"").append(NL)
                append("    try:").append(NL)
                append("        with urllib.request.urlopen(url) as r:").append(NL)
                append("            logs = r.read().decode('utf-8')").append(NL)
                append("    except Exception as e:").append(NL)
                append("        print(f\"{RED}{BOLD}Error:{RST} Cannot connect to LocalApiServer ({e})\")").append(NL)
                append("        sys.exit(1)").append(NL)
                append("    for line in logs.splitlines():").append(NL)
                append("        if grep_pattern and grep_pattern not in line.lower(): continue").append(NL)
                append("        m = LOG_RE.match(line)").append(NL)
                append("        if m:").append(NL)
                append("            ts, lvl, tag, pid, msg = m.groups()").append(NL)
                append("            lvl_color = LEVEL_COLORS.get(lvl, RST)").append(NL)
                append("            pid_str = f\"({pid.strip()})\" if pid else \"\"").append(NL)
                append("            m_lower = msg.lower()").append(NL)
                append("            if lvl in ('E', 'F') or 'denied' in m_lower or 'fail' in m_lower or 'error' in m_lower: msg = RED + msg + RST").append(NL)
                append("            elif 'warn' in m_lower or 'contention' in m_lower: msg = YELLOW + msg + RST").append(NL)
                append("            elif 'success' in m_lower or 'established' in m_lower: msg = GREEN + msg + RST").append(NL)
                append("            print(f\"{DIM}{ts}{RST} {lvl_color}{lvl}{RST} {MAGENTA}{tag}{CYAN}{pid_str}{RST}: {msg}\")").append(NL)
                append("        else: print(line)").append(NL)
                append("if __name__ == '__main__': main()").append(NL)
            }.toString(),

            "nethunter-fix-postinst" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("if [ -z \"\$1\" ]; then").append(NL)
                append("  echo \"Usage: nethunter-fix-postinst <package-name>\"").append(NL)
                append("  exit 1").append(NL)
                append("fi").append(NL)
                append("pkg=\"\$1\"").append(NL)
                append("ln -sf /bin/true /var/lib/dpkg/info/\$pkg.postinst 2>/dev/null || true").append(NL)
                append("dpkg --configure -a > /dev/null 2>&1").append(NL)
                append("python3 -c \"import sys, os; sys.path.append('/usr/local/bin'); from nethunter_format import status; print(status('Post-Inst Fix', f'Fixed {sys.argv[1]}', True))\" \"\$pkg\"").append(NL)
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

            "nethunter-api" to StringBuilder().apply {
                append("#!/bin/sh").append(NL)
                append("# NetHunter Local API Control CLI").append(NL)
                append("API_URL=\"http://127.0.0.1:1337\"").append(NL)
                append("usage() {").append(NL)
                append("  echo \"Usage: nethunter-api share [on|off|status]\"").append(NL)
                append("  exit 1").append(NL)
                append("}").append(NL)
                append("if [ \"\$1\" = \"share\" ]; then").append(NL)
                append("  mode=\"\${2:-status}\"").append(NL)
                append("  if [ \"\$mode\" = \"on\" ]; then").append(NL)
                append("    res=\$(curl -s -X POST --data-binary \"on\" \"\$API_URL/api/share\")").append(NL)
                append("    python3 -c \"import sys, os; sys.path.append('/usr/local/bin'); from nethunter_format import status; print(status('API Sharing', 'ENABLED (0.0.0.0)', True))\"").append(NL)
                append("  elif [ \"\$mode\" = \"off\" ]; then").append(NL)
                append("    res=\$(curl -s -X POST --data-binary \"off\" \"\$API_URL/api/share\")").append(NL)
                append("    python3 -c \"import sys, os; sys.path.append('/usr/local/bin'); from nethunter_format import status; print(status('API Sharing', 'DISABLED (127.0.0.1)', False))\"").append(NL)
                append("  elif [ \"\$mode\" = \"status\" ]; then").append(NL)
                append("    res=\$(curl -s \"\$API_URL/api/share\")").append(NL)
                append("    python3 -c \"import sys, json, os; sys.path.append('/usr/local/bin'); from nethunter_format import status; d = json.loads(sys.argv[1]); print(status('API Sharing', 'ENABLED (0.0.0.0)' if d.get('shared') else 'DISABLED (127.0.0.1)', d.get('shared')))\" \"\$res\"").append(NL)
                append("  else").append(NL)
                append("    usage").append(NL)
                append("  fi").append(NL)
                append("else").append(NL)
                append("  usage").append(NL)
                append("fi").append(NL)
            }.toString(),

            "vpn-log-viewer.py" to StringBuilder().apply {
                append("#!/usr/bin/python3").append(NL)
                append("import sys, json").append(NL)
                append("logs = json.load(sys.stdin)").append(NL)
                append("if not logs:").append(NL)
                append("    print('No traffic logs yet.')").append(NL)
                append("    sys.exit(0)").append(NL)
                append("for log in logs[:30]:").append(NL)
                append("    p = log.get('protocol', '?')").append(NL)
                append("    src = log.get('srcIp', '?')").append(NL)
                append("    sp = str(log.get('srcPort', '?'))").append(NL)
                append("    dst = log.get('dstIp', '?')").append(NL)
                append("    dp = str(log.get('dstPort', '?'))").append(NL)
                append("    sz = log.get('size', 0)").append(NL)
                append("    cat = log.get('category', '?')").append(NL)
                append("    det = log.get('detail', '')").append(NL)
                append("    app = log.get('appName', '') or ''").append(NL)
                append("    sess = log.get('sessionName', '') or ''").append(NL)
                append("    ent = log.get('entropy', 0)").append(NL)
                append("    elapsed = log.get('elapsedTimeMs', 0)").append(NL)
                append("    sent = log.get('bytesSent', 0)").append(NL)
                append("    recv = log.get('bytesReceived', 0)").append(NL)
                append("    ctx = ''").append(NL)
                append("    if sess and app:").append(NL)
                append("        ctx = f'[{sess} \u00bb {app}]'").append(NL)
                append("    elif sess:").append(NL)
                append("        ctx = f'[{sess}]'").append(NL)
                append("    elif app:").append(NL)
                append("        ctx = f'[{app}]'").append(NL)
                append("    emoji_map = {'ALLOWED': '\uD83D\uDFE2', 'BLOCKED': '\uD83D\uDEAB', 'SUSPICIOUS': '\u26A0\uFE0F', 'CRITICAL': '\uD83D\uDD34', 'VERBOSE': '\uD83D\uDCAC'}").append(NL)
                append("    emoji = emoji_map.get(cat, '\u2753')").append(NL)
                append("    print(f'{emoji} [{p:5s}] {src}:{sp} \u2192 {dst}:{dp} ({sz}B, ent={ent:.1f}) - {cat}')").append(NL)
                append("    if det:").append(NL)
                append("        print(f'   \u2514\u2500 {det}')").append(NL)
                append("    if ctx:").append(NL)
                append("        print(f'   \u2514\u2500 App: {ctx}  |  \u2191{sent}B \u2193{recv}B  |  {elapsed}ms')").append(NL)
            }.toString(),

            // nethunter-notebook — temporarily disabled for later
        )

        for ((name, content) in scripts) {
            val scriptFile = File(binDir, name)
            try {
                // Optimization: Only write if content changed or missing
                if (scriptFile.exists() && scriptFile.length() == content.length.toLong()) {
                    continue
                }
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
            "nethunter-agent-cli" to "nethunter-agent-cli",
            "bin/terminalmap" to "terminalmap"
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
- **Předpřipravené API Wrappery:** V `/usr/local/bin` jsou nasazeny vlastní verze `apt`/`apt-get` ošetřující pády `debconf`, dále `vpn-bypass`/`dcheck` pro obcházení filtru přes proxy (port 13339) a nástroje jako `nethunter-fix-postinst`.

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
| `nethunter-cellinfo` | Zobrazí informace o mobilní síti — operátor, signál (dBm), typ sítě (5G/4G/3G), věže. | `nethunter-cellinfo` |
| `nethunter-location` | Vrátí aktuální GPS souřadnice + odkaz na Google Maps pro otevření v mapách. | `nethunter-location` |
| `nethunter-map` | Spustí TerminalMap interaktivní mapovač OpenStreetMap s aktuální lokací. | `nethunter-map` |
| `nethunter-terminalmap` | Alias pro `nethunter-map` — synonym pro spuštění TerminalMap. | `nethunter-terminalmap` |
| `nethunter-volume [level]` | Získá nebo nastaví hlasitost médií (0-15/100). | `nethunter-volume 10` |
| `nethunter-torch [on|off]` | Zapne nebo vypne svítilnu zařízení. | `nethunter-torch on` |
| `nethunter-list` | Zobrazí seznam všech dostupných NetHunter nástrojů. | `nethunter-list` |
| `nethunter-log [options] [lines]`| Barevné zobrazení logcat záznamů aplikace (V=šedá, D=modrá, I=zelená, W=žlutá, E/F=červená). | `nethunter-log -n 50 -g "LocalApiServer"` |
| `nethunter-api share [on|off|status]`| Ovládá sdílení API serveru do sítě (0.0.0.0 vs 127.0.0.1). | `nethunter-api share on` |

## 🔧 Diagnostické nástroje

### nethunter-log

Python skript pro barevné formátované zobrazení logcat záznamů aplikace bez nutnosti ADB.

```bash
# Výchozí: posledních 100 řádků
nethunter-log

# Posledních 50 řádků
nethunter-log 50
nethunter-log -n 50

# Filtrování podle vzoru (case-insensitive)
nethunter-log -g "TlsMitm"
nethunter-log -n 200 -g "LocalApiServer"
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

Tyto skripty umožňují plnou kontrolu nad zabudovaným prémiovým filtrovacím strojem.

| Příkaz | Popis | Příklad použití |
| :--- | :--- | :--- |
| `vpn-on` | Zapne globální VPN / NAT. | `vpn-on` |
| `vpn-off` | Vypne globální VPN / NAT. | `vpn-off` |
| `vpn-cli <action>` | Pokročilé VPN CLI rozhraní: `status`, `start`, `stop`, `logs` (formátovaný výpis MITM provozu), `mitm on|off|status|ca`. | `vpn-cli logs` |
| `vpn-bypass <cmd>` | Spustí konkrétní příkaz tak, že úplně obejde VPN zachytávání. | `vpn-bypass curl ipinfo.io` |
| `ignore-vpn [on|off|status]` | Přepne ignorování VPN pro aktuální terminálovou relaci. | `ignore-vpn on` |

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
vpn-cli mitm ca

# Uložit do souboru
vpn-cli mitm ca > /tmp/nethunter-ca.crt

# Nebo přímo přes HTTP API
curl -s http://127.0.0.1:1337/vpn/mitm/ca > /tmp/nethunter-ca.crt
```

#### Instalace do Kali/PRoot trust store

```bash
vpn-cli mitm ca > /usr/local/share/ca-certificates/nethunter-mitm.crt
update-ca-certificates
```

#### Instalace do Androidu

1. Uložit certifikát: `vpn-cli mitm ca > /sdcard/nethunter-ca.crt`
2. Na telefonu: **Nastavení → Zabezpečení → Šifrování a přihlašovací údaje → Instalovat certifikát → Certifikát CA**
3. Vybrat soubor `nethunter-ca.crt` ze storage
4. Potvrdit instalaci (systém vyžádá PIN/otisk prstu)

> **Omezení:** Od Androidu 7.0+ aplikace standardně nedůvěřují uživatelským certifikátům. Pro dešifrování provozu ostatních appek je nutný root a přesun CA do systémového trust store (`/system/etc/security/cacerts/`). Aplikace s certificate pinning (banky, Google, Signal, WhatsApp) odmítnou MITM spojení i s nainstalovaným CA.

### CLI příkazy

```bash
# Zapnutí MITM rozhraní
vpn-cli mitm on

# Vypnutí MITM rozhraní
vpn-cli mitm off

# Stav MITM rozhraní + aktivní session
vpn-cli mitm status

# Stáhnout/zobrazit Root CA certifikát
vpn-cli mitm ca

# Uložit Root CA certifikát do souboru pro instalaci
vpn-cli mitm ca > /tmp/nethunter-ca.crt

# Formátovaný dešifrovaný provoz
vpn-cli logs

# JSON výstup dešifrovaného provozu
vpn-cli logs json
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

Po zapnutí MITM se průběžně ukládá dešifrovaný provoz do snippet bufferu. Využijte `vpn-cli logs` pro čitelné zobrazení.

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
* **Stažení Root CA:** `curl -s http://127.0.0.1:1337/vpn/mitm/ca > ca.crt`
* **Logcat záznamy:** `curl -s http://127.0.0.1:1337/app/logs?limit=100`

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

        val profileScript = """
#!/bin/sh
# NetHunter AI Operator — Welcome (shown once per session)
SENTINEL="${'$'}HOME/.nethunter_welcome_shown"
if [ -f "${'$'}SENTINEL" ]; then
    return 0 2>/dev/null || exit 0
fi
touch "${'$'}SENTINEL" 2>/dev/null

echo ""
echo "  \033[1;36m╔══════════════════════════════════════════════════════╗\033[0m"
echo "  \033[1;36m║\033[0m  \033[1;35m🐉 NetHunter AI Operator v4.1\033[0m                          \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;33m    Kali Linux • AdGuard VPN • AI Brain • P2P Mesh\033[0m  \033[1;36m║\033[0m"
echo "  \033[1;36m╠══════════════════════════════════════════════════════╣\033[0m"
echo "  \033[1;36m║\033[0m  \033[1;32m📡 RYCHLÉ PŘÍKAZY:\033[0m                                    \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  nethunter-location\033[0m         GPS + Google Maps           \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  nethunter-cellinfo\033[0m          mobilní síť (5G/4G/3G)      \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  nethunter-map\033[0m              OSM mapa (OpenStreetMap)    \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  nethunter-wifi-connectioninfo\033[0m WiFi info                  \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  nethunter-battery-status\033[0m     stav baterie                \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  nethunter-clipboard-get/set\033[0m  schránka (čtení/zápis)      \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  nethunter-toast \"text\"\033[0m       Android toast               \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  nethunter-vibrate [ms]\033[0m      vibrace                      \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  nethunter-tts-speak \"text\"\033[0m   přečíst text nahlas          \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  nethunter-notification\033[0m      systémová notifikace         \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  nethunter-volume 0-15\033[0m        hlasitost                    \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  nethunter-torch on/off\033[0m      svítilna                     \033[1;36m║\033[0m"
echo "  \033[1;36m╠══════════════════════════════════════════════════════╣\033[0m"
echo "  \033[1;36m║\033[0m  \033[1;33m🛡️  VPN:\033[0m                                           \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  vpn-on / vpn-off\033[0m            VPN zapnout/vypnout          \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  vpn-cli mitm on|off\033[0m        TLS MITM zapnout/vypnout       \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  vpn-cli mitm status\033[0m        MITM stav + session            \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  vpn-cli logs\033[0m                MITM formátované logy          \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  vpn-cli status\033[0m              stav VPN                      \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  vpn-cli chat\033[0m                AI Expert konzole             \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  vpn-bypass <cmd>\033[0m            obejít VPN pro příkaz          \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  ignore-vpn on/off\033[0m          VPN bypass pro session         \033[1;36m║\033[0m"
echo "  \033[1;36m╠══════════════════════════════════════════════════════╣\033[0m"
echo "  \033[1;36m║\033[0m  \033[1;33m🖥️  DESKTOP:\033[0m                                        \033[1;36m║\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;32m  nethunter-desktop start\033[0m     XFCE4 GUI (noVNC :6080)      \033[1;36m║\033[0m"
echo "  \033[1;36m╠══════════════════════════════════════════════════════╣\033[0m"
echo "  \033[1;36m║\033[0m  \033[0;33m📖 cat nethunter_docs.md\033[0m  → plná dokumentace           \033[1;36m║\033[0m"
echo "  \033[1;36m╚══════════════════════════════════════════════════════╝\033[0m"
echo ""
echo "  \033[0;90mgithub.com/zombiegirlcz/kali_core_emulator\033[0m"
echo ""
""".trimIndent()

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
            motd.append("  \u001b[1;33m╭━━━╮\u001b[0m  \u001b[1;32m╔╗ ╔╗\u001b[0m").append(NL)
            motd.append("  \u001b[1;33m┃╭━╮┃\u001b[0m  \u001b[1;32m║╚╦╝║\u001b[0m  \u001b[1;36mNetHunter AI Operator v4.1\u001b[0m").append(NL)
            motd.append("  \u001b[1;33m┃╰━╯┃\u001b[0m  \u001b[1;32m╚╗╚╗║\u001b[0m  \u001b[1;35mParrot OS Security\u001b[0m").append(NL)
            motd.append("  \u001b[1;33m┃╭━━┫\u001b[0m  \u001b[1;32m╔╝╔╝║\u001b[0m").append(NL)
            motd.append("  \u001b[1;33m┃┃\u001b[0m    \u001b[1;32m╔╝╔╝╔╝\u001b[0m").append(NL)
            motd.append("  \u001b[1;33m╰╯\u001b[0m    \u001b[1;32m╚═╝ ╚═╝\u001b[0m").append(NL)
        } else {
            motd.append("  \u001b[1;34m╦╔═╔═╗╦  ╦\u001b[0m").append(NL)
            motd.append("  \u001b[1;34m╠╩╗╠═╣║  ║\u001b[0m  \u001b[1;36mNetHunter AI Operator v4.1\u001b[0m").append(NL)
            motd.append("  \u001b[1;34m╩ ╩╩ ╩╩═╝╩═╝\u001b[0m  \u001b[1;35mKali NetHunter\u001b[0m").append(NL)
        }

        motd.append(NL)
        motd.append("  \u001b[1;32mRYCHLÉ PŘÍKAZY / QUICK HELP\u001b[0m").append(NL)
        motd.append("  \u001b[0;36mnethunter-location\u001b[0m         GPS + Google Maps").append(NL)
        motd.append("  \u001b[0;36mnethunter-cellinfo\u001b[0m          mobilní síť (5G/4G/3G)").append(NL)
        motd.append("  \u001b[0;36mnethunter-map\u001b[0m              OSM terminálová mapa").append(NL)
        motd.append("  \u001b[0;36mnethunter-battery-status\u001b[0m     stav baterie").append(NL)
        motd.append("  \u001b[0;36mnethunter-wifi-connectioninfo\u001b[0m WiFi info").append(NL)
        motd.append("  \u001b[0;36mnethunter-clipboard-get/set\u001b[0m  schránka").append(NL)
        motd.append("  \u001b[0;36mvpn-on / vpn-off\u001b[0m            VPN přepínač").append(NL)
        motd.append("  \u001b[0;36mvpn-cli mitm on|off\u001b[0m        TLS MITM dešifrování").append(NL)
        motd.append("  \u001b[0;36mvpn-cli logs\u001b[0m                MITM dešifrovaný provoz").append(NL)
        motd.append("  \u001b[0;36mvpn-cli chat\u001b[0m                AI Expert konzole").append(NL)
        motd.append("  \u001b[0;36mnethunter-desktop start\u001b[0m     GUI (noVNC :6080)").append(NL)
        motd.append(NL)
        motd.append("  \u001b[0;90mcat nethunter_docs.md  → plná dokumentace\u001b[0m").append(NL)
        motd.append(NL)

        try {
            File(motdDir, "motd").writeText(motd.toString())
            Log.i(TAG, "Deployed /etc/motd for $distroId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write /etc/motd: ${e.message}")
        }
    }
}
