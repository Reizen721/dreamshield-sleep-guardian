package com.dreamshield.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SleepModelsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun t(text: String) = Instant.parse(text).toEpochMilli()
    private val start = t("2026-11-09T15:00:00Z")
    private val end = t("2026-11-09T23:00:00Z")
    private fun night(vararg ignored: String) = SleepNight(id="night", startMillis=start, endMillis=end, zoneId=zone.id, sourceId="pc|room")
    private fun sample(time: Long, temperature: Double = 22.0, source: String = "pc|room") = RoomSample(source,time,temperature,55.0)
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Invalid input was accepted") } catch (_: IllegalArgumentException) {}
    }

    @Test fun midnightBelongsToTheEveningWhereTheNightStarted() {
        assertEquals("2026-11-09", SleepMath.nightDate(start,zone))
        assertEquals("2026-11-09", SleepMath.nightDate(end,zone))
        assertEquals("2026-11-10", SleepMath.nightDate(t("2026-11-10T04:00:00Z"),zone))
        assertEquals("2026-11-09", night().date)
        assertEquals(480L,night().durationMinutes)
    }

    @Test fun napsUseCalendarDateAndSessionsKeepOriginalTimeZone() {
        assertEquals("2026-11-10", night().copy(startMillis=end,kind="nap").date)
        val travelling = night().copy(zoneId="America/Los_Angeles")
        assertEquals("2026-11-08", travelling.date)
        assertEquals("2026-11-09",night().date)
    }

    @Test fun plansAcceptOnlyRealClockTimesAndFiniteReminderRange() {
        listOf("00:00","23:59","07:05").forEach { assertTrue(SleepMath.validTime(it)) }
        listOf("7:05","24:00","12:60"," 07:00","ab:cd","").forEach { assertFalse(SleepMath.validTime(it)) }
        invalid { SleepMath.validatePlan(SleepPlan(weekdayWake="24:00")) }
        invalid { SleepMath.validatePlan(SleepPlan(reminderLeadMinutes=-1)) }
        invalid { SleepMath.validatePlan(SleepPlan(reminderLeadMinutes=181)) }
        invalid { SleepMath.validatePlan(SleepPlan(skippedNightDate="2026-02-30")) }
        assertEquals(180,SleepMath.validatePlan(SleepPlan(reminderLeadMinutes=180)).reminderLeadMinutes)
    }

    @Test fun nextWakeUsesWakeDaysWeekendAndNeverReturnsThePast() {
        val plan=SleepPlan()
        val fridayNight=t("2026-11-13T15:00:00Z")
        assertEquals(t("2026-11-14T00:00:00Z"), SleepMath.nextWake(plan,fridayNight,zone))
        val sunday=t("2026-11-15T00:00:00Z")
        assertEquals(t("2026-11-15T23:00:00Z"),SleepMath.nextWake(plan,sunday,zone))
        assertTrue(SleepMath.nextWake(plan,end,zone)>end)
    }

    @Test fun nextBedtimeSkipsTheSelectedNightButPreservesPlans() {
        val now=t("2026-11-09T10:00:00Z")
        assertEquals(start, SleepMath.nextBedtime(SleepPlan(),now,zone))
        assertEquals(start+24*60*60_000L,SleepMath.nextBedtime(SleepPlan(skippedNightDate="2026-11-09"),now,zone))
    }

    @Test fun timeZoneDaylightSavingTransitionsProduceFutureRealInstants() {
        val berlin=ZoneId.of("Europe/Berlin")
        val plan=SleepPlan(weekdayWake="02:30",weekendWake="02:30")
        for (at in listOf("2026-03-28T20:00:00Z","2026-10-24T20:00:00Z")) {
            val now=t(at)
            val next=SleepMath.nextWake(plan,now,berlin)
            assertTrue(next>now)
            assertTrue(next-now<26*60*60_000L)
        }
    }

    @Test fun recordsRejectReversedFutureAndUnreasonablyLongIntervals() {
        assertEquals(night(),SleepMath.validateNight(night(),end))
        invalid { SleepMath.validateNight(night().copy(endMillis=start-1),end) }
        invalid { SleepMath.validateNight(night().copy(endMillis=start),end) }
        invalid { SleepMath.validateNight(night().copy(endMillis=end+1),end) }
        invalid { SleepMath.validateNight(night().copy(startMillis=end+600_000,endMillis=null),end) }
        invalid { SleepMath.validateNight(night().copy(endMillis=start+SleepMath.MAX_NIGHT_MILLIS+1),end+SleepMath.MAX_NIGHT_MILLIS) }
        invalid { SleepMath.validateNight(night().copy(plannedWakeMillis=start-1),end) }
        invalid { SleepMath.validateNight(night().copy(wakings=listOf(end+1)),end) }
        invalid { SleepMath.validateNight(night().copy(scene=3),end) }
        invalid { SleepMath.validateNight(night().copy(zoneId="made-up-timezone"),end) }
    }

    @Test fun subjectiveFeedbackNeverPretendsToBeSensorMeasuredSleep() {
        val valid=MorningFeedback(5,"comfortable",0,"醒来很轻松")
        assertEquals(valid,SleepMath.validateFeedback(valid))
        assertEquals(MorningFeedback(),SleepMath.validateFeedback(MorningFeedback()))
        invalid { SleepMath.validateFeedback(valid.copy(rating=0)) }
        invalid { SleepMath.validateFeedback(valid.copy(rating=6)) }
        invalid { SleepMath.validateFeedback(valid.copy(comfort="sleeping")) }
        invalid { SleepMath.validateFeedback(valid.copy(rememberedWakings=-1)) }
        invalid { SleepMath.validateFeedback(valid.copy(note="x".repeat(1001))) }
    }

    @Test fun historicalAdditionsCannotOverlapOtherCompletedOrActiveRecords() {
        val previous=night().copy(id="previous",startMillis=start-24*60*60_000L,endMillis=end-24*60*60_000L)
        assertEquals(previous,SleepMath.validateNoOverlap(listOf(night()),SleepMath.validateNight(previous,end)))
        invalid { SleepMath.validateNoOverlap(listOf(night()),previous.copy(startMillis=start-1000,endMillis=start+1)) }
        val active=night().copy(endMillis=null)
        invalid { SleepMath.validateNoOverlap(listOf(active),previous.copy(startMillis=end,endMillis=end+60_000)) }
        val adjacent=previous.copy(startMillis=end,endMillis=end+60_000)
        assertEquals(adjacent,SleepMath.validateNoOverlap(listOf(night()),adjacent))
        assertEquals(night(),SleepMath.validateNoOverlap(listOf(night()),night()))
    }

    @Test fun anUnfinishedOldNightSurvivesRecoveryAndRequestsAnExplicitCorrection() {
        val now=end+48*60*60_000L
        val unfinished=night().copy(endMillis=null,plannedWakeMillis=end)
        assertEquals(unfinished,SleepMath.validateNight(unfinished,now))
        try { SleepMath.finishNight(unfinished,now);fail("Long unfinished record was silently stretched") }
        catch(error:IllegalArgumentException) { assertTrue(error.message!!.contains("修改时间")) }
        // Supplying the remembered, real morning restores the original eight-hour record.
        val corrected=SleepMath.validateNight(unfinished.copy(endMillis=end),now)
        assertEquals(480L,corrected.durationMinutes)
    }

    @Test fun samplesRejectNonFiniteOutOfBoundsAndFutureNumbers() {
        assertTrue(SleepMath.validSample(sample(start,0.0),end))
        assertTrue(SleepMath.validSample(sample(start).copy(humidityPercent=0.0),end))
        for (bad in listOf(Double.NaN,Double.POSITIVE_INFINITY,-41.0,126.0)) assertFalse(SleepMath.validSample(sample(start,bad),end))
        assertFalse(SleepMath.validSample(sample(start).copy(humidityPercent=101.0),end))
        assertFalse(SleepMath.validSample(sample(end+61_000),end))
        assertFalse(SleepMath.validSample(sample(start,source=""),end))
        assertFalse(SleepMath.validSample(sample(start,source="pc\ncredential"),end))
    }

    @Test fun emptyAndSingleReadReportsDeclareTheUnobservedPeriod() {
        val n=night()
        val empty=SleepMath.report(SleepSnapshot(nights=listOf(n)),n,end)
        assertNull(empty.tempMeanC);assertEquals(0L,empty.coveredMillis)
        assertEquals(listOf(TimeSpan(start,end)),empty.gaps)
        val single=SleepMath.report(SleepSnapshot(samples=listOf(sample(start+1000))),n,end)
        assertEquals(22.0,single.tempMeanC!!,0.001)
        assertEquals(0L,single.coveredMillis)
        assertEquals(0,single.coveragePercent)
    }

    @Test fun coverageConnectsOnlyNearbyMeasurementsAndRetainsOutageGaps() {
        val n=night().copy(endMillis=start+300_000)
        val points=listOf(sample(start+10_000),sample(start+25_000),sample(start+40_000),sample(start+240_000),sample(start+255_000))
        val report=SleepMath.report(SleepSnapshot(samples=points),n,start+300_000)
        assertEquals(45_000L,report.coveredMillis)
        assertEquals(listOf(TimeSpan(start,start+10_000),TimeSpan(start+40_000,start+240_000),TimeSpan(start+255_000,start+300_000)),report.gaps)
        assertEquals(15,report.coveragePercent)
    }

    @Test fun dataFromOtherRoomsAndOutsideTheNightNeverEnterTheReport() {
        val n=night().copy(endMillis=start+60_000)
        val points=listOf(sample(start,20.0),sample(start+15_000,22.0),sample(start+30_000,24.0),
            sample(start+10_000,100.0,"other-room"),sample(start-1,100.0),sample(start+60_001,100.0))
        val events=listOf(NightEvent("one",start+5000,"被子变化","pc|room"),
            NightEvent("two",start+5000,"其他房间","other-room"))
        val report=SleepMath.report(SleepSnapshot(samples=points,events=events),n,start+60_000)
        assertEquals(3,report.points.size)
        assertEquals(22.0,report.tempMeanC!!,0.001)
        assertEquals(listOf("one"),report.events.map { it.id })
    }

    @Test fun repeatedSampleTimestampsDoNotIncreaseCoverageOrCount() {
        val n=night().copy(endMillis=start+60_000)
        val p=sample(start)
        val report=SleepMath.report(SleepSnapshot(samples=listOf(p,p,sample(start+15_000))),n,start+60_000)
        assertEquals(2,report.points.size)
        assertEquals(15_000L,report.coveredMillis)
    }

    @Test fun validObservationEventsRemainVisibleWhenTheTemperatureSensorHasNoData() {
        val n=night().copy(sourceId=null)
        val event=NightEvent("one",start+1000,"观察到被子变化","pc|room")
        val report=SleepMath.report(SleepSnapshot(events=listOf(event)),n,end)
        assertEquals(listOf(event),report.events)
        assertNull(report.tempMeanC)
        assertEquals(0L,report.coveredMillis)
        assertEquals("pc|room",report.sourceId)
    }

    @Test fun malformedDirectSnapshotCannotPolluteAReport() {
        val n=night()
        val report=SleepMath.report(SleepSnapshot(samples=listOf(sample(start,Double.NaN)),
            events=listOf(NightEvent("bad",start+1000,"","pc|room"))),n,end)
        assertTrue(report.points.isEmpty())
        assertTrue(report.events.isEmpty())
        assertNull(report.tempMeanC)
    }

    @Test fun unboundNightsChooseOneRoomAndDoNotMixThem() {
        val n=night().copy(sourceId=null,endMillis=start+60_000)
        val report=SleepMath.report(SleepSnapshot(samples=listOf(sample(start),sample(start+15_000),
            sample(start+15_000,100.0,"other-room"))),n,start+60_000)
        assertEquals("pc|room",report.sourceId)
        assertEquals(22.0,report.tempMeanC!!,0.001)
    }

    @Test fun weeklyStatisticsUseEndedUserNightsAndDoNotCreateFakeScores() {
        val n=night()
        val snapshot=SleepSnapshot(nights=listOf(n,n.copy(id="active",endMillis=null),
            n.copy(id="nap",kind="nap"),n.copy(id="old",startMillis=start-40*24*60*60_000L,endMillis=end-40*24*60*60_000L)))
        val stats=SleepMath.insights(snapshot,7,end,zone)
        assertEquals(1,stats.nightsCount)
        assertEquals(480.0,stats.meanInBedMinutes!!,0.001)
        assertNull(stats.bedtimeDeviationMinutes)
        assertEquals(0,stats.feedbackCount)
        assertTrue(stats.comfortAssociations.isEmpty())
    }

    @Test fun bedtimeRegularityHandlesMidnightWithoutTwentyThreeHourSpread() {
        val day=24*60*60_000L
        val first=night().copy(startMillis=t("2026-11-09T15:55:00Z"),endMillis=t("2026-11-09T23:55:00Z"))
        val second=first.copy(id="second",startMillis=t("2026-11-10T16:05:00Z"),endMillis=t("2026-11-11T00:05:00Z"))
        val stats=SleepMath.insights(SleepSnapshot(nights=listOf(first,second)),7,second.endMillis!!,zone)
        assertEquals(5.0,stats.bedtimeDeviationMinutes!!,0.001)
        assertEquals(5.0,stats.wakeDeviationMinutes!!,0.001)
    }

    @Test fun comfortAssociationNeedsEnoughNightsAndThirtyMinutesOfDataPerNight() {
        val days=24*60*60_000L
        val rows=(0 until 7).map { i -> night().copy(id="n$i",startMillis=start-i*days,endMillis=end-i*days,
            feedback=MorningFeedback(4,if(i<3)"hot" else "comfortable")) }
        val points=rows.flatMap { n -> (0..120).map { sample(n.startMillis+it*15_000,if(n.feedback!!.comfort=="hot")26.0 else 22.0) } }
        val stats=SleepMath.insights(SleepSnapshot(nights=rows,samples=points),7,end,zone)
        assertEquals(7,stats.feedbackCount)
        assertEquals(2,stats.comfortAssociations.size)
        assertEquals(26.0,stats.comfortAssociations.find { it.comfort=="hot" }!!.temperatureMeanC,0.001)
        val sparse=SleepMath.insights(SleepSnapshot(nights=rows,samples=points.take(2)),7,end,zone)
        assertTrue(sparse.comfortAssociations.isEmpty())
        val few=SleepMath.insights(SleepSnapshot(nights=rows.take(6),samples=points),7,end,zone)
        assertTrue(few.comfortAssociations.isEmpty())
    }

    @Test fun archivalCompactionPreservesRealSamplesAndRoomIdentity() {
        val now=end
        val old=start-2*24*60*60_000L
        val points=(0..7).map { sample(old+it*15_000) }+listOf(sample(start),sample(start+15_000),
            sample(old,source="other-room"),sample(now-36*24*60*60_000L))
        val archived=SleepRepository.compactSamples(points+points,now)
        assertEquals(5,archived.size)
        assertEquals(2,archived.count { it.measuredAtMillis>=start })
        assertEquals(1,archived.count { it.sourceId=="other-room" })
        assertTrue(archived.all { it in points })
    }
}
