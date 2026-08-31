package com.linux_core.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DockerImageRefTest {

    @Test
    fun `parses simple image with default tag`() {
        val ref = DockerImageRef.parse("kali/security")
        assertEquals("kali", ref.namespace)
        assertEquals("security", ref.repository)
        assertEquals("latest", ref.tag)
        assertFalse(ref.hasDigest)
    }

    @Test
    fun `parses image with explicit tag`() {
        val ref = DockerImageRef.parse("myuser/custom-app:v1.2.3")
        assertEquals("myuser", ref.namespace)
        assertEquals("custom-app", ref.repository)
        assertEquals("v1.2.3", ref.tag)
        assertFalse(ref.hasDigest)
    }

    @Test
    fun `parses official library image without namespace`() {
        val ref = DockerImageRef.parse("alpine")
        assertEquals("library", ref.namespace)
        assertEquals("alpine", ref.repository)
        assertEquals("latest", ref.tag)
    }

    @Test
    fun `parses image with digest`() {
        val ref = DockerImageRef.parse("alpine@sha256:abcdef123456")
        assertEquals("library", ref.namespace)
        assertEquals("alpine", ref.repository)
        assertEquals("", ref.tag)
        assertEquals("sha256:abcdef123456", ref.digest)
        assertTrue(ref.hasDigest)
    }

    @Test
    fun `rejects invalid empty string`() {
        try {
            DockerImageRef.parse("")
            assertTrue("Should throw for empty string", false)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `rejects image with no repository part`() {
        try {
            DockerImageRef.parse("user/")
            assertTrue("Should throw for trailing slash", false)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `rejects image starting with slash`() {
        try {
            DockerImageRef.parse("/alpine")
            assertTrue("Should throw for leading slash", false)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `rejects web URL in docker reference`() {
        // Web URLs jsou detekovány v UI (MainActivity) před DockerImageRef.parse;
        // parser sám musí URL odmítnout (nelze ji chápat jako image ref).
        try {
            DockerImageRef.parse("https://images.kali.org/rootfs.tar.xz")
            assertTrue("Should throw for web URL", false)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `rejects http URL in docker reference`() {
        try {
            DockerImageRef.parse("http://example.com/rootfs.tar.gz")
            assertTrue("Should throw for http URL", false)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `generates correct registry host`() {
        val ref = DockerImageRef.parse("kali/security")
        assertEquals("registry-1.docker.io", ref.registryHost)
    }

    @Test
    fun `generates correct auth scope`() {
        val ref = DockerImageRef.parse("myuser/myapp:latest")
        assertEquals("repository:myuser/myapp:pull", ref.authScope)
    }

    @Test
    fun `builds manifest URL`() {
        val ref = DockerImageRef.parse("kali/security:v1")
        val url = ref.manifestUrl("v1")
        assertEquals(
            "https://registry-1.docker.io/v2/kali/security/manifests/v1",
            url
        )
    }

    @Test
    fun `builds blob URL`() {
        val ref = DockerImageRef.parse("kali/security")
        val url = ref.blobUrl("sha256:abc123")
        assertEquals(
            "https://registry-1.docker.io/v2/kali/security/blobs/sha256:abc123",
            url
        )
    }

    @Test
    fun `normalizes tag with multiple colons`() {
        // "ubuntu:latest" is fine, but "ubuntu:latest:extra" is invalid
        try {
            DockerImageRef.parse("ubuntu:latest:extra")
            assertTrue("Should throw for multiple colons", false)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `parses http custom registry reference`() {
        val ref = DockerImageRef.parse("http://myregistry.local:5000/myuser/myapp:v1")
        assertEquals("myuser", ref.namespace)
        assertEquals("myapp", ref.repository)
        assertEquals("v1", ref.tag)
        assertEquals("myregistry.local:5000", ref.registryHost)
        assertTrue(ref.isHttp)
        assertEquals("http", ref.scheme)
        assertEquals("http://myregistry.local:5000/v2/myuser/myapp/manifests/v1", ref.manifestUrl())
    }
}
