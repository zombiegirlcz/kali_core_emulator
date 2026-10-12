package com.linux_core.core.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRootfsCatalogTest {

    // Výřez ve tvaru skriptů z zombiegirlcz/ROOTFS-for-proot (např. chimera.sh).
    private val script =
        """
        #!/bin/bash
        DISTRO_NAME="Chimera Linux"
        DISTRO_COMMENT="test"

        declare -A TARBALL_URL
        declare -A TARBALL_SHA256
        TARBALL_URL['aarch64']="https://example.org/rootfs.tar.gz"
        TARBALL_SHA256['aarch64']="abc123"

        cat <<'BOOTSTRAP_EOF' > "${'$'}DISTRO_ROOTFS/bootstrap.sh"
        #!/bin/sh
        apk update && apk upgrade
        BOOTSTRAP_EOF

        chmod +x "${'$'}DISTRO_ROOTFS/bootstrap.sh"

        mkdir -p "${'$'}DISTRO_ROOTFS/root"
        cat <<'ENTRYPOINT_EOF' > "${'$'}DISTRO_ROOTFS/root/entrypoint.sh"
        #!/bin/sh
        exec /bin/sh -l
        ENTRYPOINT_EOF

        mkdir -p "${'$'}DISTRO_ROOTFS/.nh"
        cat <<'MANIFEST_EOF' > "${'$'}DISTRO_ROOTFS/.nh/manifest"
        NH_SHELL=/bin/sh
        NH_ENTRYPOINT=/root/entrypoint.sh
        NH_BOOTSTRAP=/bootstrap.sh
        MANIFEST_EOF
        """.trimIndent()

    private fun parse() = RemoteDistroScript.fromScript("chimera.sh", script, "deadbeef", "aarch64")

    @Test
    fun `bootstrap heredoc is extracted`() {
        val s = parse()
        assertNotNull(s)
        assertEquals("#!/bin/sh\napk update && apk upgrade", s!!.bootstrapScript)
    }

    @Test
    fun `entrypoint heredoc is extracted`() {
        assertEquals("#!/bin/sh\nexec /bin/sh -l", parse()!!.entrypointScript)
    }

    @Test
    fun `manifest heredoc is extracted`() {
        val manifest = parse()!!.manifest
        assertTrue(manifest.startsWith("NH_SHELL=/bin/sh"))
        assertTrue(manifest.contains("NH_BOOTSTRAP=/bootstrap.sh"))
    }

    @Test
    fun `tarball for current arch is resolved`() {
        val s = parse()!!
        assertEquals("https://example.org/rootfs.tar.gz", s.tarballUrl)
        assertEquals("abc123", s.tarballSha256)
    }
}
