package com.linux_core.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootModeTest {

    // ─── Letter -> launcher flags ───────────────────────────────

    @Test
    fun `default boot mode is full`() {
        assertEquals("D", DEFAULT_BOOT_MODE)
        assertEquals("0" to "0", bootModeFlags(DEFAULT_BOOT_MODE))
    }

    @Test
    fun `D is full - no isolation and no minimal stripping`() {
        assertEquals("0" to "0", bootModeFlags("D"))
    }

    @Test
    fun `I is isolated - fake sysdata but no host paths`() {
        assertEquals("1" to "0", bootModeFlags("I"))
    }

    @Test
    fun `M is minimal - isolation plus minimal config`() {
        assertEquals("1" to "1", bootModeFlags("M"))
    }

    @Test
    fun `unknown mode falls back to full`() {
        assertEquals("0" to "0", bootModeFlags(""))
        assertEquals("0" to "0", bootModeFlags("X"))
        assertEquals("0" to "0", bootModeFlags("m"))
        assertEquals("0" to "0", bootModeFlags("d"))
    }

    // ─── Legacy scheme migration ────────────────────────────────

    @Test
    fun `legacy M meaning full becomes D`() {
        val migrated = migrateBootModeEntries(mapOf("mode_kali" to "M"), 1)
        assertEquals("D", migrated["mode_kali"])
    }

    @Test
    fun `legacy D meaning minimal becomes M`() {
        val migrated = migrateBootModeEntries(mapOf("mode_parrot" to "D"), 1)
        assertEquals("M", migrated["mode_parrot"])
    }

    @Test
    fun `isolated and unknown values are left untouched`() {
        val migrated =
            migrateBootModeEntries(
                mapOf(
                    "mode_kali" to "I",
                    "mode_docker" to "Z",
                ),
                1,
            )
        assertEquals("I", migrated["mode_kali"])
        assertEquals("Z", migrated["mode_docker"])
    }

    @Test
    fun `non-mode keys and non-string values are skipped`() {
        val migrated =
            migrateBootModeEntries(
                mapOf(
                    "scheme_version" to 1,
                    "other_key" to "M",
                    "mode_kali" to 7,
                ),
                1,
            )
        assertTrue(migrated.isEmpty())
    }

    @Test
    fun `already current scheme needs no migration`() {
        assertTrue(migrateBootModeEntries(mapOf("mode_kali" to "M"), BOOT_MODE_SCHEME_VERSION).isEmpty())
        assertTrue(migrateBootModeEntries(mapOf("mode_kali" to "M"), 99).isEmpty())
    }

    @Test
    fun `migration preserves all distro entries`() {
        val migrated =
            migrateBootModeEntries(
                mapOf(
                    "mode_kali" to "M",
                    "mode_parrot" to "D",
                    "mode_docker" to "I",
                ),
                1,
            )
        assertEquals(3, migrated.size)
        assertEquals("D", migrated["mode_kali"])
        assertEquals("M", migrated["mode_parrot"])
        assertEquals("I", migrated["mode_docker"])
    }
}
