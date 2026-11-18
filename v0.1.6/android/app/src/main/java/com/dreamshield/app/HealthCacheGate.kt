package com.dreamshield.app

/** Serializes only local cache publication, never platform I/O.
 * Shared by bridge instances so Activity recreation cannot revive a revoked cache. */
internal class HealthCacheGate {
    private var generation = 0L
    @Synchronized fun snapshot(): Long = generation
    @Synchronized fun invalidate(clear: () -> Unit) {
        generation++
        clear()
    }
    @Synchronized fun <T : Any> ifCurrent(expected: Long, action: () -> T): T? =
        if (generation == expected) action() else null
    @Synchronized fun <T> read(action: () -> T): T = action()
}
