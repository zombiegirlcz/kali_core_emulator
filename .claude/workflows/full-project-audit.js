export const meta = {
  name: 'full-project-audit',
  description: 'Kompletní audit projektu po oblastech (haiku), každá oblast se poté ověří skeptikem',
  phases: [{ title: 'Audit' }, { title: 'Verify' }],
}
const R = '/root/kali_combined/kali_core_emulator/'
const AREAS = [
  { k: 'api', f: 'app/src/main/java/com/linux_core/core/LocalApiServer.kt', f2: 'autentizace, injekce příkazů, blocklist, path traversal, localhost gate' },
  { k: 'rootfs', f: 'app/src/main/java/com/linux_core/core/rootfs/ (RootfsManager, ProotManager, RemoteRootfsCatalog, DistroDocumentsProvider, BootModePersistence)', f2: 'extrakce archivů (zip-slip, symlinky), mazání, download/hash, deploy, race conditions' },
  { k: 'vpn', f: 'app/src/main/java/com/linux_core/core/vpn/', f2: 'NAT, leaky sockety/vlákna, buffer handling, concurrency, resource leaks' },
  { k: 'mitm', f: 'app/src/main/java/com/linux_core/core/mitm/ a security/', f2: 'TLS handshake, správa klíčů, parsing, leaky, kryptografie' },
  { k: 'terminal', f: 'app/src/main/java/com/linux_core/core/terminal/ a core/device/', f2: 'ShellDaemonClient protokol, služby lifecycle, exported komponenty, intent handling' },
  { k: 'ui_misc', f: 'app/src/main/java/com/linux_core/ui/, core/ai, core/assistant, core/widget, core/docker, core/usb, UsbFdExporter.kt, bridge/', f2: 'exported komponenty, accessibility, docker registry klient, USB, zbytečná oprávnění' },
  { k: 'manifest', f: 'app/src/main/AndroidManifest.xml, app/build.gradle.kts, app/src/main/res/xml, gradle/, .gitignore, docs/', f2: 'oprávnění, exported, network security config, secrets v repu, build konfigurace' },
  { k: 'c_daemons', f: 'app/src/main/cpp/su_daemon.c, shell_daemon.c, su_wrapper.c', f2: 'privilege escalation, buffer overflow, TOCTOU, auth tokenu, fork/fd leaky, blocklist bypass' },
  { k: 'c_clients', f: 'app/src/main/cpp/ashell.c, ashell_pty.c, cpuctl.c, usb_bridge.c, usbfd_jni.c', f2: 'memory safety, snprintf/strcpy, injekce do system(), parsing, race conditions' },
  { k: 'scripts', f: 'assets/usr/bin/boot, assets/nh, assets/*.sh, assets/zshrc*', f2: 'shell injekce, quoting, nebezpečné rm/chmod, rozbité portabilitě mksh/dash' },
  { k: 'magisk_tools', f: 'magisk-modules/ (skripty, ne zip), tools/ (modal_build.py, mbuild, nh_test.sh)', f2: 'root skripty, nebezpečné operace, hardcoded secrets, korektnost build pipeline' },
]
const FIND = { type: 'object', properties: { findings: { type: 'array', items: { type: 'object', properties: {
  file: { type: 'string' }, line: { type: 'number' }, severity: { type: 'string', enum: ['CRITICAL','HIGH','MEDIUM','LOW'] },
  title: { type: 'string' }, detail: { type: 'string' }, fix: { type: 'string' } }, required: ['file','severity','title','detail'] } } }, required: ['findings'] }
const VER = { type: 'object', properties: { verified: { type: 'array', items: { type: 'object', properties: {
  title: { type: 'string' }, file: { type: 'string' }, line: { type: 'number' }, severity: { type: 'string' }, real: { type: 'boolean' }, reason: { type: 'string' }, fix: { type: 'string' } }, required: ['title','real','reason'] } } }, required: ['verified'] }
const res = await pipeline(AREAS,
  a => agent(`Proveď bezpečnostní a korektnostní audit oblasti projektu v ${R}: ${a.f}. Zaměř se na: ${a.f2}. Přečti kód skutečně (celé soubory po částech). Respektuj AGENTS.md sekci "co nevrátit zpět" — nehlas jako chyby věci, které jsou tam označené jako záměrné. Hlas jen reálné, konkrétní chyby s file:line; žádné spekulace. Max 15 nejzávažnějších nálezů. Piš česky.`, { label: 'audit:' + a.k, phase: 'Audit', model: 'haiku', schema: FIND }),
  (r, a) => r && r.findings.length
    ? agent(`Jsi skeptik. Pro každý nález ověř přímým čtením kódu v ${R}, zda je skutečný (real=true) či ne. Default real=false, pokud to z kódu nelze potvrdit. Nálezy:\n${JSON.stringify(r.findings)}`, { label: 'verify:' + a.k, phase: 'Verify', model: 'haiku', schema: VER })
    : { verified: [] })
const all = res.map((r, i) => ({ area: AREAS[i].k, items: (r?.verified || []).filter(v => v.real) }))
log(all.map(x => x.area + ':' + x.items.length).join(' '))
return all