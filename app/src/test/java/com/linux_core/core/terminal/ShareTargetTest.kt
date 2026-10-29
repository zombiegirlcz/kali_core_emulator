package com.linux_core.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Sdílení odkazů z Androidu → program z guest proměnné `SHARE`.
 *
 * Testuje obě cesty hledání cíle (prompt snapshot i `/proc/<pid>/environ`
 * formát) a složení guest příkazu včetně quotingu — URL přichází z cizí
 * aplikace, takže se do shellu nesmí dostat nekvotovaná.
 */
class ShareTargetTest {

    // ── parsery ──────────────────────────────────────────────────────────

    @Test
    fun `snapshot se rozparsuje na klice`() {
        val kv =
            ShareTargetResolver.parseSnapshot(
                "pid=1234\ncwd=/root/dl\ncmd=yt-dlp -P ~/dl\ndistro=kali\nsession=session_1_2_3\n",
            )
        assertEquals("1234", kv["pid"])
        assertEquals("/root/dl", kv["cwd"])
        assertEquals("yt-dlp -P ~/dl", kv["cmd"])
        assertEquals("kali", kv["distro"])
        assertEquals("session_1_2_3", kv["session"])
    }

    @Test
    fun `snapshot snese hodnotu s rovnitkem`() {
        val kv = ShareTargetResolver.parseSnapshot("cmd=sh -c 'X=1 prog --opt=a'\n")
        assertEquals("sh -c 'X=1 prog --opt=a'", kv["cmd"])
    }

    @Test
    fun `environ je NUL separovany`() {
        val env = ShareTargetResolver.parseEnviron("HOME=/root\u0000SHARE=aria2c\u0000NH_DISTRO=parrot\u0000")
        assertEquals("aria2c", env["SHARE"])
        assertEquals("parrot", env["NH_DISTRO"])
        assertEquals("/root", env["HOME"])
    }

    @Test
    fun `guestCwd odrizne prefix rootfs`() {
        assertEquals(
            "/root/dl",
            ShareTargetResolver.guestCwd("/data/user/0/com.linux_core/files/nh/distro/kali/root/dl", "/data/user/0/com.linux_core/files/nh/distro/kali"),
        )
        // cwd == rootfs → guest "/"
        assertEquals(
            "/",
            ShareTargetResolver.guestCwd("/data/x/kali", "/data/x/kali"),
        )
        // mimo rootfs (bind mount) → bezpečný default
        assertEquals("/root", ShareTargetResolver.guestCwd("/storage/emulated/0", "/data/x/kali"))
        assertEquals("/root", ShareTargetResolver.guestCwd(null, null))
    }

    // ── resolver nad snapshoty ───────────────────────────────────────────

    @Test
    fun `resolve vezme snapshot zijiciho procesu`() {
        val files = Files.createTempDirectory("nhshare").toFile()
        val dir = File(files, "nh/share").apply { mkdirs() }
        val myPid = ProcessHandle.current().pid().toInt()
        File(dir, "$myPid").writeText("pid=$myPid\ncwd=/root/dl\ncmd=yt-dlp\ndistro=kali\nsession=sess1\n")

        val target = ShareTargetResolver(files).resolve()
        requireNotNull(target)
        assertEquals("yt-dlp", target.command)
        assertEquals("/root/dl", target.cwd)
        assertEquals("kali", target.distro)
        assertEquals("sess1", target.sessionId)
        assertEquals("hook", target.source)
    }

    @Test
    fun `snapshot mrtveho procesu se zahodi`() {
        val files = Files.createTempDirectory("nhshare").toFile()
        val dir = File(files, "nh/share").apply { mkdirs() }
        // PID, který na žádném Linuxu neexistuje (nad /proc/sys/kernel/pid_max)
        val stale = File(dir, "9999999")
        stale.writeText("pid=9999999\ncmd=yt-dlp\n")

        ShareTargetResolver(files).resolve()
        assertTrue("mrtvý snapshot se musí smazat", !stale.exists())
    }

    @Test
    fun `snapshot bez prikazu se preskoci`() {
        val files = Files.createTempDirectory("nhshare").toFile()
        val dir = File(files, "nh/share").apply { mkdirs() }
        val myPid = ProcessHandle.current().pid().toInt()
        File(dir, "$myPid").writeText("pid=$myPid\ncwd=/root\ncmd=\n")
        // Fallback sken /proc na buildovacím stroji SHARE nemá → null
        assertNull(ShareTargetResolver(files).resolve()?.takeIf { it.source == "hook" })
    }

    // ── skládání příkazu ─────────────────────────────────────────────────

    @Test
    fun `prikaz ma cd do cwd a kvotovany payload`() {
        val t = ShareTarget(1, "yt-dlp -P ~/dl", "/root/dl", "kali", null, "hook")
        val line = ShareDelivery.buildCommandLine(t, "https://a.example/x?q=1")
        assertEquals("cd '/root/dl' 2>/dev/null; yt-dlp -P ~/dl 'https://a.example/x?q=1'", line)
    }

    @Test
    fun `payload s apostrofem nerozbije quoting`() {
        val t = ShareTarget(1, "echo", "/root", null, null, "hook")
        val line = ShareDelivery.buildCommandLine(t, "a'b; rm -rf /")
        assertEquals("cd '/root' 2>/dev/null; echo 'a'\\''b; rm -rf /'", line)
    }

    @Test
    fun `activeDistro cte marker i docker spec`() {
        val files = Files.createTempDirectory("nhshare2").toFile()
        val nh = File(files, "nh").apply { mkdirs() }
        File(nh, ".active_distro").writeText("parrot\n")
        assertEquals("parrot", ShareDelivery.activeDistro(files))
        File(nh, ".active_distro").writeText("docker:alpine")
        assertEquals("docker/alpine", ShareDelivery.activeDistro(files))
        File(nh, ".active_distro").delete()
        // bez markeru a bez rootfs → kali
        assertEquals("kali", ShareDelivery.activeDistro(files))
    }
}
