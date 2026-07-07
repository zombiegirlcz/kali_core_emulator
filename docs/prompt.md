nethunter-accessibility-hierarchy  nethunter-device-admin             nethunter-toast
nethunter-agent-cli                nethunter-fix-postinst             nethunter-torch
nethunter-api                      nethunter-list                     nethunter-tts-speak
nethunter-apps-usage               nethunter-location                 nethunter-vibrate
nethunter-battery-optimize         nethunter-log                      nethunter-volume
nethunter-battery-status           nethunter-map                      nethunter-wifi-connectioninfo
nethunter-cellinfo                 nethunter-notification             nethunter-wifi-control
nethunter-clipboard-get            nethunter-notifications-active     nethunter_agent.py
nethunter-clipboard-set            nethunter-speech-input            
nethunter-desktop                  nethunter-terminalmap              


kazdy prikaz musim byt otestovan jestly vraci formatovany vystup jestly prochazy pres localapiserver kazdy prikaz 
musi byt prezkouman jestly dela to co opravdu delat ma napriklad prikaz nethunter-wifi-control ma umet vypnout zapnout wifi vratit okolni wifi v dosahu umet se na vybramou wifi pripojit pokud toto nedela musime zapsat do seznamu 
fix take mame treba nethunter-wifi-connectioninfo by vubec nemusel existovat toto je funkce wifi-control status
prikaz log by mel byt nastavitelny/synchronyzovany s setting ui kde mame urovne logovani info az debug 1 az 5 takze zkontrolovat jestly log vypisuje dostatecne dost informaci podle nastaveneho lazeni nethunter-notifications-active stejny pripad jako wifi dalsi vec  nethunter je cele bych to sjednotil do nethunter bez pomlcky jalo jeden nastroj
nethunter/alias nh (command) (args) (flag) 
      vytvorime─┘

uplne to same 
       vpn-on / vpn-off                 VPN zapnout/vypnout
       vpn-cli mitm on|off            TLS MITM zapnout/vypnout
       vpn-cli mitm status             MITM stav + session 
       vpn-cli logs                     MITM formátované logy
       vpn-cli status                   stav VPN
       vpn-cli chat                     AI Expert konzole
       vpn-bypass <cmd>                 obejít VPN pro příkaz
       ignore-vpn on/off               
 
