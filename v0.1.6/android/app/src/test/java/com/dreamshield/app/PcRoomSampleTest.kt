package com.dreamshield.app

import org.junit.Assert.*
import org.junit.Test

class PcRoomSampleTest {
    private val snapshot = mapOf<String, Any?>(
        "state" to "fresh", "temperature_c" to 24.45, "humidity_percent" to 55.25,
        "measured_at" to "2026-11-06T10:11:12.000Z", "fresh_for_ms" to 45000,
        "display_for_ms" to 300000
    )
    private fun read(at: Long, vararg fields: Pair<String, Any?>) =
        PcRoomSample.parse(at, (snapshot + fields)::get)

    @Test fun repeatedReadOfSameSampleDoesNotExtendItsLifetime() {
        val first = read(1000)!!
        val same = read(4000, "fresh_for_ms" to 42000, "display_for_ms" to 297000)!!
        assertEquals(first, same)
        assertFalse(first.cached(45999))
        assertTrue(same.cached(46000))
        assertTrue(same.visible(300999))
        assertFalse(same.visible(301000))
        assertEquals("24.4", first.temperature)
        assertEquals("55.3", first.humidity)
    }

    @Test fun cacheAndSampleAgeAreControlledByPcNotPhoneWallClock() {
        val cached = read(50, "state" to "cached", "measured_at" to "2020-01-01T00:00:00Z",
            "fresh_for_ms" to 0, "display_for_ms" to 105)!!
        assertTrue(cached.cached(50))
        assertFalse(cached.visible(155))
        val replacement = read(200, "temperature_c" to 19.9, "humidity_percent" to 100)!!
        assertFalse(replacement.cached(201))
        assertEquals("19.9", replacement.temperature)
        assertEquals("100.0", replacement.humidity)
    }

    @Test fun absentInvalidAndExpiredSnapshotsAreNotDisplayedAsCurrent() {
        assertNull(read(0, "state" to "unavailable"))
        assertNull(read(0, "temperature_c" to "22"))
        assertNull(read(0, "temperature_c" to Double.NaN))
        assertNull(read(0, "humidity_percent" to 101))
        assertNull(read(0, "measured_at" to null))
        assertNull(read(0, "measured_at" to "invalid"))
        assertNull(read(0, "display_for_ms" to -1))
        assertNull(read(0, "fresh_for_ms" to 1.5))
        assertNull(read(0, "display_for_ms" to 10))
        assertNull(read(0, "state" to "cached", "fresh_for_ms" to 1))
        assertFalse(read(200, "state" to "cached", "fresh_for_ms" to 0, "display_for_ms" to 0)!!.visible(200))
    }

    @Test fun snapshotRouteIsReadOnlyAndTakesNoParameters() {
        NetworkPolicy.validatePath("GET", "/api/environment/current")
        for ((method, path) in listOf("POST" to "/api/environment/current",
                    "GET" to "/api/environment/current?device=1")) {
            try { NetworkPolicy.validatePath(method, path); fail("unexpectedly accepted $method $path") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
