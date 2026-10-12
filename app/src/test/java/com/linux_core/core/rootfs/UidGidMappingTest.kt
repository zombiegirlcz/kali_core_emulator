package com.linux_core.core.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy pro remapping /etc/passwd|group|shadow v [ProotManager].
 *
 * Regrese: stará verze slila VŠECHNY ne-root uid na uid aplikace (10323), takže
 * `getpwuid(10323)` vracel první shodu = `daemon` a `su <kdokoli>` končil jako
 * "daemon". Nová verze musí dát každému účtu rozlišitelné uid a jen JEDEN účet
 * ([ProotManager.APP_USER_NAME]) smí nést uid aplikace.
 */
class UidGidMappingTest {

    private val appUid = 10323

    // name -> uid z passwd řádku
    private fun passwdUids(content: String): Map<String, Int> =
        content.split("\n")
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split(":") }
            .filter { it.size >= 3 }
            .associate { it[0] to it[2].toInt() }

    private fun groupGids(content: String): Map<String, Int> =
        content.split("\n")
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split(":") }
            .filter { it.size >= 3 }
            .associate { it[0] to it[2].toInt() }

    private val freshPasswd =
        """
        root:x:0:0:root:/root:/bin/bash
        daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin
        bin:x:2:2:bin:/bin:/usr/sbin/nologin
        sys:x:3:3:sys:/dev:/usr/sbin/nologin
        kali:x:1000:1000:Kali,,,:/home/kali:/bin/bash
        nobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin
        """.trimIndent()

    // Rootfs rozbitý starou verzí: vše ne-root slito na uid appky.
    private val collapsedPasswd =
        """
        root:x:0:0:root:/root:/bin/bash
        daemon:x:10323:10323:daemon:/usr/sbin:/usr/sbin/nologin
        bin:x:10323:10323:bin:/bin:/usr/sbin/nologin
        sys:x:10323:10323:sys:/dev:/usr/sbin/nologin
        kali:x:10323:10323:Kali,,,:/home/kali:/bin/bash
        nobody:x:10323:10323:nobody:/nonexistent:/usr/sbin/nologin
        """.trimIndent()

    @Test
    fun `root zustava 0-0`() {
        val uids = passwdUids(ProotManager.remapPasswdContent(freshPasswd, appUid, appUid, "kali"))
        assertEquals(0, uids["root"])
    }

    @Test
    fun `cerstvy passwd zachova puvodni uid`() {
        val uids = passwdUids(ProotManager.remapPasswdContent(freshPasswd, appUid, appUid, "kali"))
        assertEquals(1, uids["daemon"])
        assertEquals(2, uids["bin"])
        assertEquals(1000, uids["kali"])
        assertEquals(65534, uids["nobody"])
    }

    @Test
    fun `prida se prave jeden ucet s uid aplikace a jmenuje se nh`() {
        val out = ProotManager.remapPasswdContent(freshPasswd, appUid, appUid, "kali")
        val uids = passwdUids(out)
        val withAppUid = uids.filterValues { it == appUid }.keys
        assertEquals(setOf(ProotManager.APP_USER_NAME), withAppUid)
    }

    @Test
    fun `rozbity passwd se opravi na rozlisitelna uid`() {
        val out = ProotManager.remapPasswdContent(collapsedPasswd, appUid, appUid, "kali")
        val uids = passwdUids(out)
        // Každý účet má unikátní uid (bez kolize = lze přepínat uživatele).
        assertEquals(uids.values.size, uids.values.toSet().size)
        // Jediný držitel uid appky je nh.
        assertEquals(setOf(ProotManager.APP_USER_NAME), uids.filterValues { it == appUid }.keys)
        // Žádný jiný účet už není "daemon duplikát".
        assertNotNull(uids["daemon"])
        assertFalse(uids["daemon"] == uids["bin"])
        // Interaktivní uživatel přednostně 1000.
        assertEquals(1000, uids["kali"])
    }

    @Test
    fun `getpwuid uid aplikace resolvuje na nh ne daemon`() {
        // Simulace getpwuid: první řádek se shodným uid.
        val out = ProotManager.remapPasswdContent(collapsedPasswd, appUid, appUid, "kali")
        val firstForAppUid =
            out.split("\n").map { it.split(":") }.filter { it.size >= 3 }
                .firstOrNull { it[2].toIntOrNull() == appUid }?.get(0)
        assertEquals(ProotManager.APP_USER_NAME, firstForAppUid)
    }

    @Test
    fun `remap passwd je idempotentni`() {
        val once = ProotManager.remapPasswdContent(collapsedPasswd, appUid, appUid, "kali")
        val twice = ProotManager.remapPasswdContent(once, appUid, appUid, "kali")
        assertEquals(once, twice)
        // a nh se nezduplikuje
        assertEquals(1, twice.split("\n").count { it.startsWith("${ProotManager.APP_USER_NAME}:") })
    }

    @Test
    fun `group zachova cleny a prida nh skupinu`() {
        val group =
            """
            root:x:0:
            daemon:x:1:
            sudo:x:27:kali
            kali:x:1000:
            """.trimIndent()
        val out = ProotManager.remapGroupContent(group, appUid, "kali")
        val gids = groupGids(out)
        assertEquals(0, gids["root"])
        assertEquals(1, gids["daemon"])
        assertEquals(27, gids["sudo"])
        assertEquals(1000, gids["kali"])
        assertEquals(appUid, gids[ProotManager.APP_USER_NAME])
        // členství "kali" ve skupině sudo zůstává
        assertTrue(out.split("\n").any { it.startsWith("sudo:") && it.endsWith(":kali") })
    }

    @Test
    fun `shadow nemeni ciselna pole a jen prida nh`() {
        val shadow =
            """
            root:*:19000:0:99999:7:::
            kali:${'$'}6${'$'}abc:19500:0:99999:7:::
            """.trimIndent()
        val out = ProotManager.ensureShadowUser(shadow)
        // Původní řádky beze změny (parts[2] = lastchange, NE uid).
        assertTrue(out.contains("kali:${'$'}6${'$'}abc:19500:0:99999:7:::"))
        assertTrue(out.contains("root:*:19000:"))
        // nh přidán
        assertTrue(out.split("\n").any { it.startsWith("${ProotManager.APP_USER_NAME}:") })
        // idempotentní — nepřidá se podruhé
        val twice = ProotManager.ensureShadowUser(out)
        assertEquals(1, twice.split("\n").count { it.startsWith("${ProotManager.APP_USER_NAME}:") })
    }
}
