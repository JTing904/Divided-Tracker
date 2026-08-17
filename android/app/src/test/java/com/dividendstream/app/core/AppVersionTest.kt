package com.dividendstream.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test
    fun `a higher release is detected`() {
        assertTrue(AppVersion.isOutdated(current = "1.0.0", latest = "1.0.1"))
        assertTrue(AppVersion.isOutdated(current = "1.0.9", latest = "1.1.0"))
        assertTrue(AppVersion.isOutdated(current = "1.9.9", latest = "2.0.0"))
    }

    @Test
    fun `the same release is not outdated`() {
        assertFalse(AppVersion.isOutdated(current = "1.0.1", latest = "1.0.1"))
        // Missing parts count as zero, so these are the same release written two ways.
        assertFalse(AppVersion.isOutdated(current = "1.0", latest = "1.0.0"))
        assertFalse(AppVersion.isOutdated(current = "1.0.0", latest = "1.0"))
    }

    @Test
    fun `a lower release never prompts`() {
        // A debug build ahead of what is published must not be told to downgrade.
        assertFalse(AppVersion.isOutdated(current = "1.1.0", latest = "1.0.1"))
    }

    @Test
    fun `numeric comparison, not text`() {
        // "10" sorts before "9" as text. This is the bug the whole file exists to prevent.
        assertTrue(AppVersion.isOutdated(current = "1.0.9", latest = "1.0.10"))
        assertFalse(AppVersion.isOutdated(current = "1.0.10", latest = "1.0.9"))
    }

    @Test
    fun `anything unparseable says nothing rather than nagging`() {
        assertFalse(AppVersion.isOutdated(current = "1.0.0", latest = null))
        assertFalse(AppVersion.isOutdated(current = "1.0.0", latest = ""))
        assertFalse(AppVersion.isOutdated(current = "1.0.0", latest = "   "))
        assertFalse(AppVersion.isOutdated(current = "1.0.0", latest = "1.0.1-beta"))
        assertFalse(AppVersion.isOutdated(current = "1.0.0", latest = "v1.0.1"))
        assertFalse(AppVersion.isOutdated(current = "", latest = "1.0.1"))
        assertFalse(AppVersion.isOutdated(current = "not-a-version", latest = "1.0.1"))
    }

    @Test
    fun `surrounding whitespace is forgiven`() {
        assertTrue(AppVersion.isOutdated(current = " 1.0.0 ", latest = " 1.0.1 "))
    }
}
