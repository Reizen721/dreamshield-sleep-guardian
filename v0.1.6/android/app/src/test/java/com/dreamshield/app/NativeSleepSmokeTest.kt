package com.dreamshield.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

/**
 * Runs the production composables and local repository with Android's native graphics
 * inside a disposable Robolectric sandbox. This is not a physical-device screenshot.
 * It never invokes MainActivity.onCreate(), NativeState.start(), BLE, network or sound.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DreamShieldApplication::class, qualifiers = "zh-rCN-w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeSleepSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var state: NativeState

    @Before fun isolatedPhoneState() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.getSharedPreferences("dreamshield_sleep_v1", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("dreamshield_native_ui_v1", Context.MODE_PRIVATE).edit().clear().commit()
        // buildActivity attaches a context, but no create/start/resume is called.
        // Production Activity startup, permissions, pairing and timers stay dormant.
        state = NativeState(Robolectric.buildActivity(MainActivity::class.java).get())
        state.setupVisible = false
        state.launchVisible = false
        state.welcomeInline = false
    }

    @After fun closePresentationOnly() { compose.runOnIdle { state.close() } }

    @Composable private fun PaperScreen(dark: Boolean = false, body: @Composable ColumnScope.() -> Unit) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), typography = Typography()) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState()).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp), content = body)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // PixelCopy's forced hardware redraw does not deliver its callback on
        // this Windows JVM. Draw the real, already-laid-out production view to
        // an Android native Skia-backed Canvas instead; no mirrored UI is used.
        lateinit var image: Bitmap
        compose.runOnIdle {
            val view = ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }?.window?.decorView
                ?: compose.activity.window.decorView
            check(view.width > 0 && view.height > 0) { "Production view has not been laid out" }
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
        }
        assertTrue("Native bitmap must have content", image.width > 300 && image.height > 300)
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        assertTrue("A screenshot must contain more than a flat background",
            pixels.asSequence().filterIndexed { index, _ -> index % 97 == 0 }.distinct().take(20).count() > 5)
        val directory = File(System.getProperty("dreamshield.screenshotDir") ?: "build/outputs/native-smoke").apply { mkdirs() }
        File(directory, name + ".png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun phoneStartsFinishesAndPersistsMorningFeedback() {
        compose.setContent { PaperScreen { NativeTonightCard(state); NativeSleepDialogs(state) } }
        compose.onNodeWithText("开始今晚").assertIsDisplayed()
        capture("phone-tonight")
        compose.onNodeWithText("开始今晚").performClick()
        compose.onNodeWithText("今夜进行中").assertExists()
        compose.runOnIdle {
            val night = state.sleep.repo.snapshot.active!!
            // Keep this fixture's start safely earlier than the end without sleeping in a test.
            state.sleep.repo.editNight(night.id, System.currentTimeMillis() - 120_000L, null, night.plannedWakeMillis)
        }
        capture("phone-active")
        compose.onNodeWithText("我起床了").performScrollTo().performClick()
        compose.onNodeWithText("昨晚感觉如何？").assertExists()
        compose.onNodeWithText("还不错").performClick()
        compose.onNodeWithText("舒适").performClick()
        capture("phone-morning-feedback")
        compose.onNodeWithText("保存感受").performScrollTo().performClick()
        compose.runOnIdle {
            val stored = SleepRepository(ApplicationProvider.getApplicationContext()).snapshot
            assertNull(stored.active)
            assertEquals(1, stored.nights.size)
            assertEquals(4, stored.latest!!.feedback!!.rating)
            assertEquals("comfortable", stored.latest!!.feedback!!.comfort)
        }
    }

    @Test fun phoneSoundControlsRenderWithoutPlaying() {
        compose.setContent { PaperScreen { NativeSoundCard(0) } }
        compose.onNodeWithText("声景").assertExists()
        compose.onNodeWithText("雨落夜窗").assertExists()
        compose.onNodeWithText("林间微风").performClick()
        compose.onNodeWithText("60 分钟").performClick()
        compose.onNodeWithText("播放声景").assertExists()
        capture("phone-offline-sound")
        assertFalse(SoundscapeController.state.playing)
        assertFalse(SoundscapeController.attached)
    }

    @Test fun phoneNightModeRendersAndRecordsAwakeningOffline() {
        compose.runOnIdle {
            state.sleep.repo.startNight(state.sleep.plannedWake(), 0, now = System.currentTimeMillis() - 120_000L)
            state.sleep.nightMode = true
        }
        compose.setContent { MaterialTheme { NativeNightMode(state) } }
        compose.onNodeWithText("暂时醒了").assertIsDisplayed().performClick()
        compose.onNodeWithText("已记下夜醒").assertExists()
        capture("phone-night-mode")
        compose.runOnIdle { assertEquals(1, state.sleep.repo.snapshot.active!!.wakings.size) }
    }

    @Test @Config(qualifiers = "zh-rCN-w840dp-h1180dp-port-xhdpi")
    fun tabletReportsAndSettingsRenderFromLocalFixture() {
        compose.runOnIdle {
            val now = System.currentTimeMillis()
            val night = state.sleep.repo.addNight(now - 12 * 3_600_000L, now - 4 * 3_600_000L, 2)
            state.sleep.repo.saveFeedback(night.id, 4, "comfortable", 1, "这是隔离测试日记")
            state.sleep.repo.ingestSamples((0..24).map { i ->
                RoomSample("isolated-render-fixture", night.startMillis + i*30_000L,24.0+i*.025,52.0+i*.05)
            } + (0..24).map { i ->
                RoomSample("isolated-render-fixture", night.endMillis!!-12*60_000L+i*30_000L,24.9-i*.02,53.2-i*.02)
            })
            state.sleep.repo.ingestEvents(listOf(NightEvent("isolated-observation",night.startMillis+60_000L,"隔离测试的盖被观察","isolated-render-fixture")))
            state.sleep.selectedNightId = night.id
        }
        compose.setContent { PaperScreen { NativeSleepRecords(state); NativeSleepSettings(state) } }
        compose.onNodeWithText("睡眠日记").assertExists()
        capture("tablet-local-records")
        compose.onNodeWithText("保存作息").performScrollTo().assertIsDisplayed()
        capture("tablet-sleep-settings")
        compose.runOnIdle {
            assertFalse(state.sleep.repo.snapshot.plan.reminderEnabled)
        }
    }

    @Test @Config(qualifiers = "en-rUS-w390dp-h844dp-port-xhdpi")
    fun englishSettingsShowLanguageAndBedtimeReminder() {
        compose.setContent { PaperScreen { NativeLanguageCard(); NativeSleepSettings(state) } }
        compose.onNodeWithText("Language").assertIsDisplayed()
        compose.onNodeWithText("English").assertIsDisplayed().performClick().assertIsSelected()
        assertEquals(AppLanguage.ENGLISH, AppLanguage.choice(compose.activity))
        compose.onNodeWithText("Sleep schedule").assertExists()
        compose.onNodeWithText("Bedtime reminder").assertExists()
        compose.onNodeWithText("Wake alarm", substring = true).assertDoesNotExist()
        compose.onNodeWithText("起床闹钟", substring = true).assertDoesNotExist()
        capture("phone-settings-english")
    }

    @Test @Config(qualifiers = "en-rUS-w390dp-h844dp-port-xhdpi")
    fun englishTonightUsesLocalizedCopyAndTimeFormat() {
        compose.setContent { PaperScreen { NativeTonightCard(state) } }
        compose.onNodeWithText("Start tonight").assertIsDisplayed().performClick()
        compose.onNodeWithText("Tonight is underway").assertExists()
        assertTrue(SleepCoordinator.dateTime(0).contains(','))
        capture("phone-tonight-english")
    }

    @Test fun upgradeCancelsOldWakeAndSnoozeAndKeepsJournal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val journal = SleepRepository(context)
        val now = System.currentTimeMillis()
        val night = journal.addNight(now - 120_000L, now - 60_000L, 0)
        val snapshot = JSONObject(journal.exportJson())
        snapshot.getJSONObject("plan").put("alarm_enabled", true).put("weekday_wake", "06:35")
            .put("reminder_enabled", true)
        context.getSharedPreferences("dreamshield_sleep_v1", Context.MODE_PRIVATE).edit()
            .putString("snapshot", snapshot.toString()).commit()
        val clock = context.getSharedPreferences("dreamshield_sleep_clock_v1", Context.MODE_PRIVATE)
        clock.edit().remove("wake_alarm_removed").putLong("wake_at", now + 300_000L)
            .putLong("snooze_at", now + 600_000L).commit()

        val alarm = context.getSystemService(AlarmManager::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val wake = PendingIntent.getBroadcast(context, 6102,
            Intent(context, SleepAlarmReceiver::class.java).setAction("com.dreamshield.app.WAKE"), flags)
        val snooze = PendingIntent.getBroadcast(context, 6103,
            Intent(context, SleepAlarmReceiver::class.java).setAction("com.dreamshield.app.WAKE.SNOOZED"), flags)
        alarm.set(AlarmManager.RTC_WAKEUP, now + 300_000L, wake)
        alarm.set(AlarmManager.RTC_WAKEUP, now + 600_000L, snooze)
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("sleep_wake_v1", "Wake", NotificationManager.IMPORTANCE_HIGH))

        SleepAlarmReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertTrue(shadowOf(wake).isCanceled)
        assertTrue(shadowOf(snooze).isCanceled)
        assertNull(notifications.getNotificationChannel("sleep_wake_v1"))
        assertNotNull(notifications.getNotificationChannel(SleepSystem.REMINDER_CHANNEL))
        assertFalse(clock.contains("wake_at"))
        assertFalse(clock.contains("snooze_at"))
        assertTrue(clock.getLong("bedtime_at", 0L) > now)
        val restored = SleepRepository(context).snapshot
        assertEquals("06:35", restored.plan.weekdayWake)
        assertTrue(restored.plan.reminderEnabled)
        assertEquals(night.id, restored.latest?.id)
    }
}
