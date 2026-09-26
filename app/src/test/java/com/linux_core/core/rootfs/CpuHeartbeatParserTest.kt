package com.linux_core.core.rootfs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuHeartbeatParserTest {

    @Test
    fun `valid heartbeat within 15s window is alive`() {
        val now = System.currentTimeMillis() / 1000
        assertTrue(parseHeartbeat("12345 $now", now))
    }

    @Test
    fun `heartbeat at exactly 14s is alive`() {
        val now = System.currentTimeMillis() / 1000
        assertTrue(parseHeartbeat("12345 ${now - 14}", now))
    }

    @Test
    fun `heartbeat at exactly 15s is not alive`() {
        val now = System.currentTimeMillis() / 1000
        assertFalse(parseHeartbeat("12345 ${now - 15}", now))
    }

    @Test
    fun `stale heartbeat is not alive`() {
        val now = System.currentTimeMillis() / 1000
        assertFalse(parseHeartbeat("12345 ${now - 60}", now))
    }

    @Test
    fun `empty content is not alive`() {
        val now = System.currentTimeMillis() / 1000
        assertFalse(parseHeartbeat("", now))
    }

    @Test
    fun `single field is not alive`() {
        val now = System.currentTimeMillis() / 1000
        assertFalse(parseHeartbeat("12345", now))
    }

    @Test
    fun `non-numeric timestamp is not alive`() {
        val now = System.currentTimeMillis() / 1000
        assertFalse(parseHeartbeat("12345 abc", now))
    }

    @Test
    fun `whitespace-only content is not alive`() {
        val now = System.currentTimeMillis() / 1000
        assertFalse(parseHeartbeat("   ", now))
    }

    @Test
    fun `extra fields are tolerated`() {
        val now = System.currentTimeMillis() / 1000
        assertTrue(parseHeartbeat("12345 $now extra", now))
    }

    companion object {
        fun parseHeartbeat(content: String, nowEpochSec: Long): Boolean {
            val parts = content.trim().split(" ")
            if (parts.size < 2) return false
            val ts = parts[1].toLongOrNull() ?: return false
            return (nowEpochSec - ts) < 15
        }
    }
}
