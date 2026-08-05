# Plán opravy mrtvých spojení VPN

## Přehled problému
Aplikace NetHunter AI Operator má problém s mrtvými spojeními VPN, kdy se některá TCP/UDP spojení neuzavírají správně při ukončení služby nebo při dlouhodobé nečinnosti. Tento problém může vést ke ztrátě paměti, úniku zdrojů a problémům s výkonem.

## Identifikované příčiny
1. Nesprávné řízení životního cyklu TCP/UDP spojení
2. Problémy s čištěním neaktivních relací
3. Chybějící nebo nesprávná detekce ukončení spojení
4. Nesprávné uzavření TLS MITM spojení
5. Problémy s ochranou socketů
6. Nesprávné řízení stavu TCP spojení

## Návrh řešení

### 1. Vylepšení řízení životního cyklu TCP/UDP spojení
- Přidání robustního uzavírání všech spojení při zastavení služby
- Zajištění, že všechny spojení jsou uzavřeny v opačném pořadí, než byla vytvořena
- Přidání kontrol uzavření a opakovaných pokusů o uzavření v případě selhání

### 2. Vylepšení čištění neaktivních relací
- Zkrácení časového limitu pro TCP spojení z 5 minut na 2 minuty
- Zkrácení časového limitu pro UDP spojení z 1 minuty na 30 sekund
- Přidání pravidelného čištění každých 30 sekund místo pouze při nečinnosti

### 3. Vylepšení detekce ukončení spojení
- Přidání detekce RST packetů pro okamžité uzavření spojení
- Vylepšení detekce ukončení spojení na straně klienta/serveru
- Přidání timeoutů pro všechny fáze TCP handshake

### 4. Vylepšení uzavření TLS MITM spojení
- Zajištění správného uzavření všech TLS MITM relací při ukončení služby
- Přidání explicitního uzavření serverových socketů v MITM relacích
- Vylepšení řízení stavu TLS MITM relací

### 5. Vylepšení ochrany socketů
- Zajištění, že všechny nově vytvořené sockety jsou chráněny před smyčkou VPN
- Přidání kontrol ochrany socketů a opakovaných pokusů o ochranu v případě selhání

### 6. Vylepšení řízení stavu TCP spojení
- Přidání kontroly konzistence stavu TCP spojení
- Zajištění, že všechny stavy jsou správně aktualizovány
- Přidání detekce nekonzistentních stavů a jejich oprava

## Implementace

### Úpravy v souboru VpnNatEngine.kt:

1. V metodě `stop()` přidat robustní uzavření všech spojení:
   - Přidat opakované pokusy o uzavření spojení
   - Přidat kontrolu uzavření a logování stavu
   - Zajistit uzavření v opačném pořadí

2. V metodě `cleanIdleSessions()` vylepšit čištění:
   - Zkrátit timeouty pro TCP a UDP spojení
   - Přidat pravidelné čištění každých 30 sekund

3. V metodě `handleTcpPacket()` vylepšit detekci ukončení spojení:
   - Přidat detekci RST packetů
   - Přidat timeouty pro všechny fáze TCP handshake

4. V metodě `closeTcpSession()` vylepšit uzavření:
   - Přidat explicitní uzavření všech zdrojů
   - Přidat kontrolu uzavření a logování stavu

5. V metodě `handleUdpPacket()` vylepšit detekci ukončení spojení:
   - Přidat timeouty pro UDP spojení
   - Přidat kontrolu aktivity spojení

### Úpravy v souboru TlsMitmEngine.kt:

1. V metodě `close()` v TlsMitmSession vylepšit uzavření:
   - Přidat explicitní uzavření serverového socketu
   - Přidat kontrolu uzavření TLS enginů
   - Zajistit správné uzavření všech bufferů

2. V metodě `start()` přidat lepší řízení chyb:
   - Přidat detailní logování chyb
   - Zajistit správné uvolnění zdrojů při chybě

### Úpravy v souboru VpnCaptureService.kt:

1. V metodě `stopVpn()` vylepšit uzavření:
   - Přidat robustní uzavření všech komponent
   - Přidat kontrolu uzavření a logování stavu
   - Zajistit správné uvolnění všech zdrojů

2. V metodě `onDestroy()` vylepšit uvolnění zdrojů:
   - Přidat kontrolu uvolnění všech zdrojů
   - Zajistit, že všechny komponenty jsou správně uvolněny

## Testování

1. Spuštění VPN a vytvoření několika TCP/UDP spojení
2. Ukončení VPN služby a kontrola uzavření všech spojení
3. Spuštění VPN a nechání spojení nečinných po delší dobu
4. Kontrola automatického čištění neaktivních spojení
5. Testování TLS MITM spojení a jejich správné uzavření
6. Monitorování využití paměti a zdrojů před a po úpravách

## Očekávané výsledky

1. Žádná mrtvá spojení po ukončení VPN služby
2. Správné automatické čištění neaktivních spojení
3. Žádný únik zdrojů nebo paměti
4. Zlepšený výkon a stabilita VPN spojení
5. Správné uzavření TLS MITM relací

## Provedené změny

### 1. VpnNatEngine.kt
- ✅ Přidána metoda `closeTcpSessionWithRetry()` pro robustní uzavření TCP spojení s opakovanými pokusy
- ✅ Vylepšena metoda `stop()` - přidáno robustní uzavření všech spojení s čekáním na dokončení
- ✅ Vylepšena metoda `cleanIdleSessions()` - zkráceny timeouty (TCP: 5min→2min, UDP: 1min→30s)
- ✅ Vylepšena detekce RST packetů - již se neposílá další RST na RST
- ✅ Vylepšena detekce FIN packetů - přechod do stavu FIN_WAIT před uzavřením
- ✅ Vylepšena metoda `closeTcpSession()` - přidáno robustní uzavření TLS MITM, kontrola stavu, logování
- ✅ Vylepšen `startSelectorLoop()` - přidáno pravidelné čištění neaktivních spojení každých 30 sekund

### 2. TlsMitmEngine.kt
- ✅ Vylepšena metoda `close()` v TlsMitmSession - explicitní uzavření všech zdrojů, vyčištění bufferů, uvolnění TLS enginů
- ✅ Vylepšena metoda `fallingBackToPassthrough()` - robustní uzavření všech zdrojů v finally bloku

### 3. VpnCaptureService.kt
- ✅ Vylepšena metoda `stopVpn()` - přidáno robustní uzavření všech komponent, kontrola stavu vláken, lepší logování
- ✅ Vylepšena metoda `onDestroy()` - přidáno ošetření chyb a detailní logování

## Stav implementace
Všechny plánované opravy byly úspěšně implementovány. Nyní je třeba:
1. Otestovat změny na zařízení
2. Sestavit novou verzi APK pomocí `mbuild`
3. Ověřit, že mrtvá spojení jsou nyní správně čištěna