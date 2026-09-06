# NetHunter Keepalive - co modul řeší a co ne

## Co tenhle modul dělá (AOSP vrstva, spolehlivě skriptovatelné)
- `dumpsys deviceidle whitelist +com.linux_core` - vyjme appku z Doze/App Standby
- `am set-standby-bucket com.linux_core active` - drží standby bucket na ACTIVE
- `cmd appops set ... RUN_ANY_IN_BACKGROUND/RUN_IN_BACKGROUND/START_FOREGROUND/WAKE_LOCK allow`
- Znovu aplikováno při každém bootu (late_start service)

## Co NEŘEŠÍ - MIUI PowerKeeper vrstva
MIUI má nad AOSP ještě svého vlastního "hlídače" (com.miui.powerkeeper +
Security Center autostart list), který appky zabíjí nezávisle na tom, co
říká standardní Android. Schéma jeho DB/content provideru se liší build od
buildu MIUI, takže slepé skriptování zápisu do něj je nespolehlivé a
riskuje rozbití Security Center appky.

### Zjištění na tvém zařízení (spusť a pošli výstup)
```
dumpsys package com.miui.powerkeeper | grep -A3 "Receiver\|Service"
```
Podle výstupu ti pak dokážu napsat přesný `pm disable-user` příkaz na
konkrétní komponentu, která appky zabíjí - místo hádání.

### Spolehlivá manuální cesta (jednorázově, přežije reboot)
1. Nastavení -> Aplikace -> Spravovat aplikace -> NetHunter AI Operator
   -> Úsporný režim baterie -> **Bez omezení**
2. Tamtéž -> Autostart -> **zapnout**
3. Nedávné aplikace -> podržet kartu appky -> **zamknout (ikona zámku)**

## Doporučení - největší páka je na straně appky, ne Magisku
Modul appku chrání před *předčasným* zabitím systémem. Skutečný OOM (ten
z předchozího ticketu) je pádem JVM heapu uvnitř procesu a Magisk ho
neumí zastavit. Nejúčinnější doplněk: spustit `pullDockerImage`/rootfs
operace jako **foreground Service** s notifikací (`startForeground()`) -
to appce zvedne prioritu (oom_adj) do pásma, které lmkd prakticky nikdy
nezabíjí, a zároveň to řeší i standardní Android background limity (ne
jen MIUI). Řekni, jestli mám připravit brief i na tohle.
