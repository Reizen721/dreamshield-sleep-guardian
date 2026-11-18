package com.dreamshield.app

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** A read-only projection of the PC's selected room sample. Deadlines use the
 * request start on the phone's monotonic clock, so transit cannot prolong a sample. */
internal data class PcRoomSample(
    val temperature: String,
    val humidity: String,
    val measuredAt: String,
    val freshUntil: Long,
    val displayUntil: Long,
) {
    fun visible(now: Long) = now < displayUntil
    fun cached(now: Long) = visible(now) && now >= freshUntil

    companion object {
        fun parse(start: Long, field: (String) -> Any?): PcRoomSample? {
            val state = field("state")
            if (state != "fresh" && state != "cached") return null
            fun number(key: String) = (field(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
            fun ttl(key: String, max: Long) = number(key)?.takeIf {
                it >= 0 && it <= max.toDouble() && it == it.toLong().toDouble()
            }?.toLong()
            val temperature = number("temperature_c")?.takeIf { it in -40.0..125.0 } ?: return null
            val humidity = number("humidity_percent")?.takeIf { it in 0.0..100.0 } ?: return null
            val measured = field("measured_at") as? String ?: return null
            try { Instant.parse(measured) } catch (_: java.time.format.DateTimeParseException) { return null }
            val display = ttl("display_for_ms", 300_001L) ?: return null
            val fresh = ttl("fresh_for_ms", 45_001L) ?: return null
            if (fresh > display || (state == "cached" && fresh > 0)) return null
            // Round the same binary sensor value as the desktop's Number.toFixed(1).
            fun oneDecimal(value: Double) = BigDecimal(value).setScale(1, RoundingMode.HALF_UP).toPlainString()
            return PcRoomSample(oneDecimal(temperature), oneDecimal(humidity), measured, start + fresh, start + display)
        }
    }
}
