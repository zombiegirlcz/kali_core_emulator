package com.linux_core.bridge;

interface ICoreBridge {
    // Vrací JSON string se stejným tvarem jako odpovídající HTTP endpoint.
    String prootExec(String distro, String command, int timeoutMs);
    String hostShell(String command);

    /**
     * EXPERIMENTÁLNÍ: přímé spuštění příkazu na hostu přes elf wrapper
     * (elf_loader --ownall, bypass PRoot). Vyžaduje nainstalovaný
     * parrot_elf_loader Magisk modul nebo wrapper v files/usr/bin/elf.
     * Guardy: SHELL_ALLOWLIST + DESTRUCTIVE_PATTERNS (reálný FS!).
     */
    String elfExec(String command, int timeoutMs);

    String getBattery();
    String getWifi();
    String getLocation();
    String getStatus(); // {"bridge_version":1,"core_version":"..."}

    /** Vrací JSON pole nainstalovaných distrí (podadresáře nh/distro/, docker jako "docker/<image>"). */
    String listDistros();
}
