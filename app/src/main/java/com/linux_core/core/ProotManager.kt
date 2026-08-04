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
        val distroId = if (rootfsDirName.contains("parrot")) "parrot" else "kali"
        
        deployZshrc(context, rootfsDir, distroId)
        
        // VZDY vytvorime .bootstrap_required pokud .setup_done neexistuje
        // (stary .setup_done z nekompletniho bootstrapu nesmi blokovat dalsi pokus)
        if (!setupDoneFile.exists()) {
            val bootstrapRequired = File(homeDir, ".bootstrap_required")
            try {
                if (!bootstrapRequired.exists()) {
                    bootstrapRequired.createNewFile()
                    Log.i(TAG, "Fresh install detected, created .bootstrap_required")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create bootstrap sentinel: ${e.message}")
            }
            setupDoneFile.delete()
        }

        createMasterScript(homeDir, distroId, hasRoot)
        createEntrypointScript(homeDir)
        deployVpnHelpDocument(context, homeDir)
        deployWelcomeProfile(rootfsDir, distroId)
        val userHomeDir = File(rootfsDir, "home/$distroId")
        if (userHomeDir.exists()) {
            deployVpnHelpDocument(context, userHomeDir)
        }
        deployApiScripts(context, rootfsDir)

        val suffix = if (rootfsDir.name.contains("arm64")) "aarch64" else "arm"
        deployBinaries(context, suffix)
        fixLdLinuxSymlinks(context, rootfsDir)

        val prootBin = File(context.filesDir, "proot")
        val loaderBin = File(context.filesDir, "loader")
        val tallocLib = File(context.filesDir, "libtalloc.so.2")

        // Generujeme distro-specifický launcher (launcher-kali.sh, launcher-parrot.sh,
        // launcher-docker.sh) — každý s vlastním log prefixem. Pro Docker image
        // se použije "docker" místo názvu distribuce, aby se správně logovalo.
        val launcherDistroId = if (isDockerImage) "docker" else distroId
        val distroLauncherName = "launcher-${launcherDistroId}.sh"
        val launcherFile = File(rootDir, distroLauncherName)
        deployLauncherScript(
            context = context,
            launcherFile = launcherFile,
            distroId = launcherDistroId,
            rootfsDir = rootfsDir,
            prootBin = prootBin,
            loaderBin = loaderBin,
            tallocLib = tallocLib,
            tmpDir = tmpDir,
            mountStorage = mountStorage,
            isDockerImage = isDockerImage
        )

        val fullCommand = mutableListOf("/system/bin/sh", launcherFile.absolutePath)
        if (!customCommand.isNullOrEmpty()) {
            fullCommand.add(customCommand)
        }

        val defaultProot = prootBin
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

    /**
     * Shell snippet: UTF-8 locale self-heal (generate if missing + set for all
     * login shells). Idempotent — locale GEN bezi jen jednou (sentinel), zatímco
     * /etc/default/locale + /etc/profile.d/nethunter-locale.sh se dotáhnou vždy,
     * aby bash/zsh login shell dostaly LANG/LC_CTYPE=C.UTF-8 (POSIX fallback pryč).
     */
    private fun buildLocaleFix(): String = buildString {
        appendLine("# === [NetHunter] UTF-8 locale fix ===")
        appendLine("mkdir -p /etc /etc/profile.d")
        appendLine("if [ ! -f /etc/.nethunter_locale_done ]; then")
        appendLine("    if [ -f /etc/locale.gen ]; then")
        appendLine("        grep -q 'C.UTF-8' /etc/locale.gen 2>/dev/null || printf 'C.UTF-8 UTF-8\\n' >> /etc/locale.gen")
        appendLine("        grep -q 'en_US.UTF-8' /etc/locale.gen 2>/dev/null || printf 'en_US.UTF-8 UTF-8\\n' >> /etc/locale.gen")
        appendLine("    else")
        appendLine("        printf 'C.UTF-8 UTF-8\\nen_US.UTF-8 UTF-8\\n' > /etc/locale.gen 2>/dev/null || true")
        appendLine("    fi")
        appendLine("    if command -v locale-gen >/dev/null 2>&1; then")
        appendLine("        locale-gen C.UTF-8 en_US.UTF-8 >/dev/null 2>&1 || true")
        appendLine("    elif command -v localedef >/dev/null 2>&1; then")
        appendLine("        localedef -i C -f UTF-8 C.UTF-8 >/dev/null 2>&1 || true")
        appendLine("        localedef -i en_US -f UTF-8 en_US.UTF-8 >/dev/null 2>&1 || true")
        appendLine("    fi")
        appendLine("    touch /etc/.nethunter_locale_done 2>/dev/null || true")
        appendLine("fi")
        appendLine("# /etc/default/locale doplnit jen pokud chybi/je prazdne (neresetujem uzivatelovo nastaveni)")
        appendLine("if [ ! -s /etc/default/locale ] || ! grep -q '^LANG=' /etc/default/locale 2>/dev/null; then")
        appendLine("    if command -v update-locale >/dev/null 2>&1; then")
        appendLine("        update-locale LANG=C.UTF-8 LC_CTYPE=C.UTF-8 >/dev/null 2>&1 || true")
        appendLine("    else")
        appendLine("        printf 'LANG=C.UTF-8\\nLC_CTYPE=C.UTF-8\\n' > /etc/default/locale 2>/dev/null || true")
        appendLine("    fi")
        appendLine("fi")
        appendLine("cat > /etc/profile.d/nethunter-locale.sh << 'NLOC_EOF'")
        appendLine("# NetHunter: UTF-8 locale pro vsechny login shelly (bash/zsh/sh)")
        appendLine("export LANG=C.UTF-8")
        appendLine("export LC_CTYPE=C.UTF-8")
        appendLine("NLOC_EOF")
        appendLine("chmod 644 /etc/profile.d/nethunter-locale.sh 2>/dev/null || true")
        appendLine("export LANG=C.UTF-8")
        appendLine("export LC_CTYPE=C.UTF-8")
    }

    /**
     * Shell snippet: smaze automaticky nastavene (uzivateli nezname) hesla
     * (root + distro user) a zajisti zapisovatelny /etc/shadow, aby `passwd`
     * slo nastavit nove heslo. Deletion bezi JEN jednou (sentinel), aby se
     * uzivatelovo pozdeji zvolene heslo pri kazde session nesmazalo.
     * Misto setuid `passwd` (PRoot/fake-root ho rozbiji -> "System error")
     * se password hash vymaze primo pres sed do /etc/shadow.
     * @param users prostorkem oddeleny seznam uzivatelu (null = root kali parrot)
     */
    private fun buildPasswordFix(users: String?): String = buildString {
        appendLine("# === [NetHunter] Password fix: smazat auto-hesla + zapisovatelny /etc/shadow ===")
        appendLine("chmod 600 /etc/shadow /etc/gshadow 2>/dev/null || true")
        appendLine("chmod 644 /etc/passwd /etc/group 2>/dev/null || true")
        appendLine("if [ ! -f /etc/.nethunter_password_reset_done ]; then")
        appendLine("    for u in ${users ?: "root kali parrot"}; do")
        appendLine("        if id \"\$u\" >/dev/null 2>&1; then")
        appendLine("            awk -F: -v u=\"\$u\" 'BEGIN{OFS=\":\"} $1==u{$2=\"\"} {print}' /etc/shadow > /tmp/.nh_shadow 2>/dev/null && cat /tmp/.nh_shadow > /etc/shadow 2>/dev/null; rm -f /tmp/.nh_shadow")
        appendLine("        fi")
        appendLine("    done")
        appendLine("    touch /etc/.nethunter_password_reset_done 2>/dev/null || true")
        appendLine("fi")
    }

    private fun createMasterScript(homeDir: File, distroId: String, hasRoot: Boolean) {
        val masterFile = File(homeDir, "bootstrap.sh")
        val script = StringBuilder().apply {
            append("#!/bin/bash").append(NL)
            append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin").append(NL)
            append("export TMPDIR=/tmp").append(NL)
            append("export DEBIAN_FRONTEND=noninteractive").append(NL)
            append("export DEBCONF_NOWARNINGS=yes").append(NL)
            append(buildLocaleFix()).append(NL)
            append("echo '[*] BOOTSTRAP STARTING...'").append(NL)
            append("rm -f /var/lib/dpkg/lock* /var/cache/apt/archives/lock /var/lib/apt/lists/lock 2>/dev/null || true").append(NL)
            append("chmod 777 /var/cache/apt/archives/partial 2>/dev/null || true").append(NL)
            append("# Self-heal dpkg stavu: po reinstalaci/chybe muze byt /var/lib/dpkg").append(NL)
            append("# nepripsatelny (status-old: Permission denied). PRoot fake-root mapuje root->appUID,").append(NL)
            append("# takze staci zajistit vlastnika + owner-write.").append(NL)
            append("chown root:root /var/lib/dpkg /var/lib/dpkg/status /var/lib/dpkg/status-old /var/lib/dpkg/available /var/lib/dpkg/diversions /var/lib/dpkg/statoverride 2>/dev/null || true").append(NL)
            append("chmod u+rw /var/lib/dpkg/status* /var/lib/dpkg/available /var/lib/dpkg/diversions /var/lib/dpkg/statoverride 2>/dev/null || true").append(NL)
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
            append("BOOTSTRAP_CORE_OK=0").append(NL)
            append("if apt install -y --allow-unauthenticated usrmerge perl zsh zsh-syntax-highlighting zsh-autosuggestions curl git sudo python3 python3-pip dropbear dropbear-bin 2>&1; then").append(NL)
            append("  BOOTSTRAP_CORE_OK=1").append(NL)
            append("  echo '[*] Core packages installed OK'").append(NL)
            append("else").append(NL)
            append("  echo '[!] WARNING: apt install failed — bootstrap bude zopakovan pri pristim startu'").append(NL)
            append("fi").append(NL)
            // Restore debconf confmodule (real Perl now installed, debconf should work)

            append("if [ -f /usr/share/debconf/confmodule.bak ] && [ ! -f /usr/share/debconf/confmodule ]; then").append(NL)
            append("  mv /usr/share/debconf/confmodule.bak /usr/share/debconf/confmodule").append(NL)
            append("  echo '[*] Restored debconf confmodule'").append(NL)
            append("fi").append(NL)
            append("echo '[*] Fixing any half-configured packages...'").append(NL)
            append("if dpkg --configure -a 2>&1; then").append(NL)
            append("  BOOTSTRAP_CORE_OK=1").append(NL)
            append("else").append(NL)
            append("  BOOTSTRAP_CORE_OK=0").append(NL)
            append("  echo '[!] WARNING: dpkg --configure -a failed — bootstrap bude zopakovan pri pristim startu'").append(NL)
            append("fi").append(NL)
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
            append(buildPasswordFix("root $defaultUser")).append(NL)
            append("usermod -aG sudo ${defaultUser} 2>/dev/null || true").append(NL)
            append("mkdir -p /etc/sudoers.d").append(NL)
            append("echo '${defaultUser} ALL=(ALL:ALL) NOPASSWD: ALL' > /etc/sudoers.d/${defaultUser}").append(NL)
            append("chmod 0440 /etc/sudoers.d/${defaultUser}").append(NL)

            append("echo '[*] Restoring clean NetHunter Zshrc configurations...'").append(NL)
            append("[ -f /etc/skel/.zshrc.nethunter ] && cp /etc/skel/.zshrc.nethunter /etc/skel/.zshrc").append(NL)
            append("[ -f /root/.zshrc.nethunter ] && cp /root/.zshrc.nethunter /root/.zshrc").append(NL)
            append("[ -f /etc/skel/.zshrc.nethunter ] && [ -d /home/${defaultUser} ] && cp /etc/skel/.zshrc.nethunter /home/${defaultUser}/.zshrc").append(NL)
            append("chown -R ${defaultUser}:${defaultUser} /home/${defaultUser}/.zshrc 2>/dev/null || true").append(NL)

            append("chsh -s \"\$SHELL_BIN\" root 2>/dev/null || true").append(NL)
            append("if [ \"\$BOOTSTRAP_CORE_OK\" = 1 ]; then").append(NL)
            append("  touch /root/.setup_done").append(NL)
            append("  echo '[+] BOOTSTRAP COMPLETE'").append(NL)
            append("else").append(NL)
            append("  echo '[!] BOOTSTRAP FAILED — .setup_done NEVYTVOREN, bootstrap se zopakuje pri pristim startu'").append(NL)
            append("  exit 1").append(NL)
            append("fi").append(NL)
        }.toString()
        masterFile.writeText(script)
        masterFile.setExecutable(true, false)
    }

    private fun createEntrypointScript(homeDir: File) {
        val entryFile = File(homeDir, "entrypoint.sh")
        val script = StringBuilder().apply {
            append("#!/bin/bash").append(NL)
            append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin").append(NL)
            append("export TMPDIR=/tmp").append(NL)
            append("unset LD_PRELOAD").append(NL)
            append(buildLocaleFix()).append(NL)
            append(buildPasswordFix(null)).append(NL)
            append("echo -e \"nameserver 8.8.8.8\\nnameserver 8.8.4.4\" > /etc/resolv.conf 2>/dev/null || true").append(NL)
            append("rm -f /var/lib/dpkg/lock* 2>/dev/null || true").append(NL)
            
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
            append("    grep -v -e 'NetHunter AI Operator' -e 'FORCE_ZSH_' -e 'source /etc/nethunter.zshrc' \"\$zrc\" > /tmp/.nh_zrc 2>/dev/null || true").append(NL)
            append("    cat /tmp/.nh_zrc > \"\$zrc\" 2>/dev/null || true").append(NL)
            append("    rm -f /tmp/.nh_zrc 2>/dev/null || true").append(NL)
            append("    [ -n \"\$user_name\" ] && chown \"\$user_name:\$user_name\" \"\$zrc\" 2>/dev/null || true").append(NL)
            append("}").append(NL)

            append("setup_user_zsh /root root").append(NL)
            append("[ -d /home/parrot ] && setup_user_zsh /home/parrot parrot").append(NL)
            append("[ -d /home/kali ] && setup_user_zsh /home/kali kali").append(NL)

            append("chmod 4755 /usr/bin/sudo /usr/bin/su /bin/su /bin/sudo 2>/dev/null || true").append(NL)
            append("# Dropbear SSH server (fix for OpenSSH seccomp crash on Android kernel)").append(NL)
            append("if command -v dropbear >/dev/null 2>&1; then").append(NL)
            append("  if ! pidof dropbear >/dev/null 2>&1; then").append(NL)
            append("    mkdir -p /etc/dropbear").append(NL)
            append("    for keytype in rsa ecdsa ed25519; do").append(NL)
            append("      KEYFILE=\"/etc/dropbear/dropbear_\${keytype}_host_key\"").append(NL)
            append("      [ -f \"\$KEYFILE\" ] || dropbearkey -t \"\$keytype\" -f \"\$KEYFILE\" 2>&1 | tail -1").append(NL)
            append("    done").append(NL)
            append("    # Use port 2222 (non-privileged — PRoot can't bind to port 22)").append(NL)
            append("    dropbear -p 2222 2>/dev/null && echo '[*] dropbear SSH server started on port 2222'").append(NL)
            append("  fi").append(NL)
            append("fi").append(NL)
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

        // Deploy su/sudo UNIX socket IPC bridge
        deploySuBridge(context, rootfsDir)

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
            "scripts/vpn-log-viewer.py" to "vpn-log-viewer.py",
            "usb_bridge" to "usb_bridge"
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

        // Initialize USB bridge: create socket path INSIDE rootfs tmp
        // so it's visible from PRoot as /tmp/usb_bridge.sock
        val usbBridgeSocket = File(rootfsDir, "tmp/usb_bridge.sock")
        try {
            val parent = usbBridgeSocket.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            // Ensure the socket doesn't exist (bind will create it)
            usbBridgeSocket.delete()
            // Start the UDS server on this path (runs in app process)
            UsbFdExporter.start(usbBridgeSocket.absolutePath)
            Log.i(TAG, "USB bridge UDS configured at ${usbBridgeSocket.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "USB bridge UDS setup skipped: ${e.message}")
        }
    }

    /**
     * Deploy Shizuku rish shell (shizuku command) into the guest filesystem.
     * Shizuku native binaries in assets are arm64 — other archs are not supported.
     */
    private fun deployShizukuRish(context: Context, rootfsDir: File) {
        val binDir = File(rootfsDir, "usr/local/bin")
        if (!binDir.exists()) binDir.mkdirs()

        // Deploy rish script as 'shizuku' command
        val rishScript = File(binDir, "shizuku")
        val rishDex = File(binDir, "rish_shizuku.dex")

        var needsDeploy = false
        if (!rishScript.exists() || rishScript.length() == 0L) {
            needsDeploy = true
        } else {
            // Force redeploy if asset size differs (updated script)
            try {
                val assetSize = context.assets.open("shizuku/rish.sh").use { it.available().toLong() }
                if (rishScript.length() != assetSize) needsDeploy = true
            } catch (e: Exception) {
                needsDeploy = true
            }
        }
        if (!rishDex.exists() || rishDex.length() == 0L) needsDeploy = true

        if (!needsDeploy) {
            rishScript.setExecutable(true, false)
            rishScript.setReadable(true, false)
            return
        }

        try {
            context.assets.open("shizuku/rish.sh").use { input ->
                rishScript.outputStream().use { output -> input.copyTo(output) }
            }
            rishScript.setExecutable(true, false)
            rishScript.setReadable(true, false)
            rishScript.setWritable(true, false)
            Log.i(TAG, "Deployed shizuku command to guest (${rishScript.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deploy shizuku command: ${e.message}")
        }

        try {
            context.assets.open("shizuku/rish_shizuku.dex").use { input ->
                rishDex.outputStream().use { output -> input.copyTo(output) }
            }
            rishDex.setReadable(true, false)
            rishDex.setWritable(false, false)
            Log.i(TAG, "Deployed rish dex to guest (${rishDex.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deploy rish dex: ${e.message}")
        }
    }

    /**
     * Deploy su_daemon host binary and su_wrapper guest binary for UNIX-socket privilege escalation.
     */
    private fun deploySuBridge(context: Context, rootfsDir: File) {
        // 0. Host ipc dir MUST exist BEFORE the PRoot container starts —
        //    launcher.sh binds $FILES_DIR/ipc to /run/host_ipc. If the dir is
        //    created only later (in startDaemon), the bind is a dead mountpoint
        //    and the guest never sees the daemon socket.
        try {
            val hostIpcDir = File(context.filesDir, "ipc")
            if (!hostIpcDir.exists()) hostIpcDir.mkdirs()
            Log.i(TAG, "Ensured host ipc dir: ${hostIpcDir.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create host ipc dir: ${e.message}")
        }

        val binDir = File(rootfsDir, "usr/local/bin")
        if (!binDir.exists()) binDir.mkdirs()

        // 1. Deploy su_wrapper to guest /usr/local/bin/su_wrapper
        var wrapperTarget: File? = null
        try {
            val wrapperTargetLocal = File(binDir, "su_wrapper")
            var shouldDeploy = !wrapperTargetLocal.exists() || wrapperTargetLocal.length() == 0L
            if (!shouldDeploy) {
                try {
                    val assetSize = context.assets.open("su_wrapper").use { it.available().toLong() }
                    if (wrapperTargetLocal.length() != assetSize) shouldDeploy = true
                } catch (e: Exception) {
                    shouldDeploy = true
                }
            }
            if (shouldDeploy) {
                context.assets.open("su_wrapper").use { input ->
                    wrapperTargetLocal.outputStream().use { output -> input.copyTo(output) }
                }
                wrapperTargetLocal.setExecutable(true, false)
                wrapperTargetLocal.setReadable(true, false)
                Log.i(TAG, "Deployed su_wrapper binary (${wrapperTargetLocal.length()} bytes)")
            }
            wrapperTarget = wrapperTargetLocal
        } catch (e: Exception) {
            Log.w(TAG, "su_wrapper asset not available: ${e.message}")
        }

        // 2. Install wrapper as /usr/local/bin/su and /usr/local/bin/sudo
        //    (symlinks to su_wrapper) so that 'sudo' / 'su' in the guest go
        //    through the daemon. Rename the original binaries to .orig so the
        //    wrapper can fall back when the daemon socket is unreachable.
        if (wrapperTarget != null && wrapperTarget!!.exists()) {
            val usrBin = File(rootfsDir, "usr/bin")
            for (name in listOf("su", "sudo")) {
                // 2a. Rename original if present (and not already renamed)
                val origBin = File(usrBin, name)
                val origBackup = File(usrBin, "$name.orig")
                if (origBin.exists() && !origBackup.exists()) {
                    try {
                        val ok = origBin.renameTo(origBackup)
                        Log.i(TAG, "Renamed /usr/bin/$name -> /usr/bin/$name.orig (ok=$ok)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to rename /usr/bin/$name: ${e.message}")
                    }
                }
                // Also handle /bin/su (Kali keeps su in /bin too)
                val binSu = File(rootfsDir, "bin/$name")
                val binSuBackup = File(rootfsDir, "bin/$name.orig")
                if (binSu.exists() && !binSuBackup.exists()) {
                    try {
                        val ok = binSu.renameTo(binSuBackup)
                        Log.i(TAG, "Renamed /bin/$name -> /bin/$name.orig (ok=$ok)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to rename /bin/$name: ${e.message}")
                    }
                }

                // 2b. Symlink wrapper into /usr/local/bin/$name
                val wrapperLink = File(binDir, name)
                try {
                    if (wrapperLink.exists()) wrapperLink.delete()
                    // Create symlink relative: su_wrapper
                    val created = Runtime.getRuntime().exec(
                        arrayOf(
                            "/system/bin/ln", "-s",
                            File(binDir, "su_wrapper").absolutePath,
                            wrapperLink.absolutePath
                        )
                    ).waitFor() == 0
                    if (created) {
                        Log.i(TAG, "Symlinked /usr/local/bin/$name -> su_wrapper")
                    } else {
                        // Fallback: copy the binary
                        wrapperTarget!!.copyTo(wrapperLink, overwrite = true)
                        wrapperLink.setExecutable(true, false)
                        Log.i(TAG, "Copied wrapper as /usr/local/bin/$name")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to install /usr/local/bin/$name: ${e.message}")
                }
            }
        }

        // 3. Deploy su_daemon to host filesDir
        try {
            val daemonTarget = File(context.filesDir, "su_daemon")
            var shouldDeploy = !daemonTarget.exists() || daemonTarget.length() == 0L
            if (!shouldDeploy) {
                try {
                    val assetSize = context.assets.open("su_daemon").use { it.available().toLong() }
                    if (daemonTarget.length() != assetSize) shouldDeploy = true
                } catch (e: Exception) {
                    shouldDeploy = true
                }
            }
            if (shouldDeploy) {
                context.assets.open("su_daemon").use { input ->
                    daemonTarget.outputStream().use { output -> input.copyTo(output) }
                }
                daemonTarget.setExecutable(true, false)
                daemonTarget.setReadable(true, false)
                Log.i(TAG, "Deployed su_daemon binary to host (${daemonTarget.length()} bytes)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "su_daemon asset not available: ${e.message}")
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

    private fun deployVpnHelpDocument(context: Context, targetDir: File) {
        val helpFile = File(targetDir, "nethunter_docs.md")
        // Dokumentace se čte z assets/nethunter_docs.md (editace bez rekompilace
        // Kotlin zdrojáku; stačí obnovit asset a restartovat kontejner).
        val content: String? = try {
            context.assets.open("nethunter_docs.md").use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read nethunter_docs.md from assets: ${e.message}")
            null
        }
        if (content == null) return
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
            appendLine("echo \"  \\033[1;33m   🔑  ROOT BRIDGE (host Magisk su)\\033[0m\"")
            appendLine("echo \"  \\033[1;36m─────────────────────────────────────────────────────────\\033[0m\"")
            appendLine("echo \"  \\033[0;33m     sudo id\\033[0m                       root na hostiteli (uid=0)\"")
            appendLine("echo \"  \\033[0;33m     su -c 'prikaz'\\033[0m               spustit příkaz jako root\"")
            appendLine("echo \"  \\033[0;33m     su\\033[0m                          hostitelský root shell\"")
            appendLine("echo \"  \\033[0;33m     ifconfig\\033[0m                     wlan0 + tun0 (i bez su)\"")
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
        motd.append("  \u001b[1;33m   🔑  ROOT BRIDGE (host Magisk su)\u001b[0m").append(NL)
        motd.append("  \u001b[1;36m─────────────────────────────────────────────────────────\u001b[0m").append(NL)
        motd.append("  \u001b[0;33m     sudo id\u001b[0m                       root na hostiteli (uid=0)").append(NL)
        motd.append("  \u001b[0;33m     su -c 'prikaz'\u001b[0m               spustit příkaz jako root").append(NL)
        motd.append("  \u001b[0;33m     su\u001b[0m                          hostitelský root shell").append(NL)
        motd.append("  \u001b[0;33m     ifconfig\u001b[0m                     wlan0 + tun0 (i bez su)").append(NL)
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
     * Nasadí launcher.sh z assets/ do filesDir a vyplní
     * placeholdery skutečnými cestami. Generuje distro-specifický launcher
     * (launcher-kali.sh, launcher-parrot.sh, launcher-docker.sh) s vlastním
     * log prefixem a záložní launcher.sh pro zpětnou kompatibilitu.
     *
     * @param launcherFile   cílový soubor (např. rootDir/launcher-kali.sh)
     * @param distroId       identifikátor distra ("kali", "parrot", "docker")
     * @param isDockerImage  true = Docker image, false = běžná distribuce
     */
    private fun deployLauncherScript(
        context: Context,
        launcherFile: File,
        distroId: String,
        rootfsDir: File,
        prootBin: File,
        loaderBin: File,
        tallocLib: File,
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

        // Každá distribuce má vlastní log prefix pro snadnou identifikaci
        val logPrefix = when (distroId) {
            "kali" -> "KaliLauncher"
            "parrot" -> "ParrotLauncher"
            "docker" -> "DockerLauncher"
            else -> if (isDockerImage) "DockerLauncher" else "${distroId.replaceFirstChar { it.uppercase() }}Launcher"
        }
        val sdcardMount = if (mountStorage) " -b /sdcard" else ""

        val rootPrefs = context.getSharedPreferences("root_settings", Context.MODE_PRIVATE)
        val extraMounts = buildString {
            if (rootPrefs.getBoolean("bind_system", true)) append(" -b /system:/mnt/system")
            if (rootPrefs.getBoolean("bind_vendor", false)) append(" -b /vendor:/mnt/vendor")
            if (rootPrefs.getBoolean("bind_tmp", false)) append(" -b /data/local/tmp:/mnt/tmp")
            if (rootPrefs.getBoolean("bind_usb", true)) append(" -b /dev/bus/usb:/mnt/usb")
        }

        val rendered = template
            .replace("__PROOT_BIN__", prootBin.absolutePath)
            .replace("__LOADER_BIN__", loaderBin.absolutePath)
            .replace("__TALLOC_LIB__", tallocLib.absolutePath)
            .replace("__ROOTFS_DIR__", rootfsDir.absolutePath)
            .replace("__ROOTFS_NAME__", rootfsDir.name)
            .replace("__TMP_DIR__", tmpDir.absolutePath)
            .replace("__FILES_DIR__", context.filesDir.absolutePath)
            .replace("__SDCARD_MOUNT__", sdcardMount)
            .replace("__EXTRA_ROOT_MOUNTS__", extraMounts)
            .replace("__DOCKER_MODE__", if (isDockerImage) "1" else "0")
            .replace("__LOG_PREFIX__", logPrefix)
            .replace("__DISTRO_ID__", distroId)

        // Ověř, že všechny placeholdery byly nahrazeny (jinak by se spustil
        // skript s nevyplněnou proměnnou – tichá chyba).
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
            Log.i(TAG, "Deployed ${launcherFile.name} (${launcherFile.length()} bytes, distro=$distroId, docker=$isDockerImage, log=$logPrefix)")

            // Pro zpětnou kompatibilitu zapíšeme i hlavní launcher.sh,
            // který bude dispatchem na distro-specifický skript.
            if (launcherFile.name != "launcher.sh") {
                val compatLauncher = File(launcherFile.parentFile, "launcher.sh")
                compatLauncher.writeText(renderCompatLauncher(context.filesDir, distroId))
                compatLauncher.setExecutable(true, false)
                compatLauncher.setReadable(true, false)
                Log.i(TAG, "Updated compat launcher.sh -> ${launcherFile.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write ${launcherFile.name}: ${e.message}")
        }
    }

    /**
     * Vytvori wrapper launcher.sh, ktery deleguje na spravny distro-specificky launcher.
     */
    private fun renderCompatLauncher(filesDir: File, currentDistro: String): String {
        return buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# Backward-compat launcher.sh wrapper")
            appendLine("# Delegates to the correct distro-specific launcher.")
            appendLine("# Generated by ProotManager.deployLauncherScript().")
            appendLine("FILES_DIR=\"${filesDir.absolutePath}\"")
            appendLine("")
            appendLine("# Try to detect distro from rootfs directory")
            appendLine("for d in kali parrot; do")
            appendLine("  if [ -d \"\$FILES_DIR/\${d}-arm64\" ] || [ -d \"\$FILES_DIR/\${d}\" ]; then")
            appendLine("    DISTRO=\"\$d\"")
            appendLine("    break")
            appendLine("  fi")
            appendLine("done")
            appendLine("# Docker images: match any docker-* directory")
            appendLine("if [ -z \"\$DISTRO\" ]; then")
            appendLine("  for d in \"\$FILES_DIR\"/docker-*; do")
            appendLine("    if [ -d \"\$d\" ]; then")
            appendLine("      DISTRO=\"docker\"")
            appendLine("      break")
            appendLine("    fi")
            appendLine("  done")
            appendLine("fi")
            appendLine("")
            appendLine("# Fallback: use the distro that generated this wrapper")
            appendLine("if [ -z \"\$DISTRO\" ]; then")
            appendLine("  DISTRO=\"$currentDistro\"")
            appendLine("fi")
            appendLine("")
            appendLine("LAUNCHER=\"\$FILES_DIR/launcher-\${DISTRO}.sh\"")
            appendLine("if [ -x \"\$LAUNCHER\" ]; then")
            appendLine("  exec \"\$LAUNCHER\" \"\$@\" || exit 1")
            appendLine("else")
            appendLine("  echo \"[CompatLauncher] ERROR: \$LAUNCHER not found\" >&2")
            appendLine("  exit 1")
            appendLine("fi")
        }.toString()
    }
}
