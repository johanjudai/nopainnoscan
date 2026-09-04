package com.maitre.nopainnoscan.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun semver_comparison() {
        assertTrue(AppUpdater.isNewer("1.1.0", "1.0.1"))
        assertTrue(AppUpdater.isNewer("2.0.0", "1.9.9"))
        assertTrue(AppUpdater.isNewer("1.0.10", "1.0.9"))
        assertFalse(AppUpdater.isNewer("1.0.1", "1.0.1"))
        assertFalse(AppUpdater.isNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun dev_builds_are_older_than_the_same_release() {
        assertTrue(AppUpdater.isNewer("1.1.0", "1.1.0-dev"))
        assertFalse(AppUpdater.isNewer("1.1.0-rc1", "1.1.0"))
        assertTrue(AppUpdater.isNewer("1.1.0", "0.0.0-dev"))
    }
}
