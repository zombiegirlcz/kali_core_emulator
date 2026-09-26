package com.linux_core.sdk

/**
 * Host-loopback (127.0.0.1) porty runtime služeb jádra i pluginů. Jediný zdroj pravdy —
 * viz `AGENTS.md` sekce 3 "Runtime porty" v kali_core_emulator. Přesun literálů z jednotlivých
 * souborů na tyto konstanty je postupný (viz docs/plans/2026-09-26-plugin-system-design.md, Fáze 1);
 * dokud nejsou všechna volání přepsaná, tyto hodnoty MUSÍ zůstat v sync s existujícími literály.
 */
object NethunterPorts {
    /** `LocalApiServer` — REST most (baterka, wifi, GPS, VPN, USB, `/shell`, `/distro/*`). */
    const val LOCAL_API = 1337

    /** AI agent démon (`nethunter_agent.py`, ReAct LLM). */
    const val AGENT = 13338

    /** VPN bypass proxy (`http(s)_proxy` pro guest, obchází AdGuard). */
    const val VPN_BYPASS_PROXY = 13339

    /** X11 server (Xvfb `:0` v guestu, viewer `127.0.0.1:6000`). */
    const val X11 = 6000
}
