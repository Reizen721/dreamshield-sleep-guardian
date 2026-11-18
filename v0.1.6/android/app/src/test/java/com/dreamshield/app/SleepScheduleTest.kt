package com.dreamshield.app

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SleepScheduleTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val plan = SleepPlan(reminderEnabled = true)
    private fun at(value: String) = ZonedDateTime.parse(value).toInstant().toEpochMilli()

    @Test fun bedtimeLeadAndRestDayStayLocal() {
        assertEquals(at("2026-11-14T23:00:00+08:00[Asia/Shanghai]"),
            SleepScheduleMath.next(plan, at("2026-11-14T19:00:00+08:00[Asia/Shanghai]"), zone))
    }

    @Test fun completedNightsDoNotSendAnotherReminder() {
        assertEquals(at("2026-11-11T22:30:00+08:00[Asia/Shanghai]"),
            SleepScheduleMath.next(plan, at("2026-11-10T22:15:00+08:00[Asia/Shanghai]"), zone,
                completedNightDates = setOf("2026-11-10")))
    }

    @Test fun invalidBedtimesDoNotCrashReceiverScheduling() {
        assertNull(SleepScheduleMath.next(plan.copy(weekdayBed = "bad", weekendBed = "bad"),
            at("2026-11-11T06:00:00+08:00[Asia/Shanghai]"), zone))
    }

    @Test fun afterMidnightBedtimeStillBelongsToPreviousNight() {
        val late = plan.copy(weekdayBed = "01:00", skippedNightDate = "2026-11-10")
        assertEquals(at("2026-11-12T00:30:00+08:00[Asia/Shanghai]"),
            SleepScheduleMath.next(late, at("2026-11-11T00:15:00+08:00[Asia/Shanghai]"), zone))
    }

    @Test fun reminderDoesNotDependOnWakeTimes() {
        val now = at("2026-11-14T19:00:00+08:00[Asia/Shanghai]")
        assertEquals(SleepScheduleMath.next(plan, now, zone),
            SleepScheduleMath.next(plan.copy(weekdayWake = "06:15", weekendWake = "09:45"), now, zone))
    }
}
