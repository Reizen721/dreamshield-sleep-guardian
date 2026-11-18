package com.dreamshield.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HealthCacheGateTest {
    @Test fun currentReadMayPublish() {
        val gate = HealthCacheGate()
        var cache = ""
        assertEquals("allowed", gate.ifCurrent(gate.snapshot()) { cache = "record"; "allowed" })
        assertEquals("record", cache)
    }
    @Test fun revokeBeforeDelayedReadFinishesPreventsRepopulation() {
        val gate = HealthCacheGate()
        var cache = "previous"
        val token = gate.snapshot()
        val continueRead = CountDownLatch(1)
        val result = AtomicReference<String?>("not-finished")
        val reader = Thread {
            check(continueRead.await(2, TimeUnit.SECONDS))
            result.set(gate.ifCurrent(token) { cache = "late-record"; cache })
        }
        reader.start()
        gate.invalidate { cache = "" }
        continueRead.countDown()
        reader.join(2000)
        assertFalse(reader.isAlive)
        assertNull(result.get())
        assertEquals("", cache)
    }
    @Test fun revokeClearsEvenWhenReadPublishedFirst() {
        val gate = HealthCacheGate()
        var cache = ""
        val token = gate.snapshot()
        gate.ifCurrent(token) { cache = "finished-record"; cache }
        gate.invalidate { cache = "" }
        assertEquals("", cache)
        assertNull(gate.ifCurrent(token) { "obsolete" })
        assertEquals("new-read", gate.ifCurrent(gate.snapshot()) { "new-read" })
    }
}
