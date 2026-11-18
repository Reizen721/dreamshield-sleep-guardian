@file:JvmName("AppCopy")

package com.dreamshield.app

import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

class DreamShieldApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguage.refresh(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (AppLanguage.refresh(this)) {
            SleepWidget.refresh(this)
            SleepSystem.channels(this)
            SoundscapeService.updateChannel(this)
        }
    }
}

internal object AppLanguage {
    private const val PREFS = "dreamshield_language"
    private const val KEY = "locale"
    const val SYSTEM = ""
    const val CHINESE = "zh-CN"
    const val ENGLISH = "en"

    private data class CopyResources(val locale: Locale, val resources: Resources)
    @Volatile private var copy: CopyResources? = null
    val locale: Locale get() = copy?.locale ?: Locale.SIMPLIFIED_CHINESE
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun choice(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) return SYSTEM
            return if (locales[0].language == "en") ENGLISH else CHINESE
        }
        return prefs(context).getString(KEY, SYSTEM) ?: SYSTEM
    }

    private fun systemLocale(context: Context): Locale {
        val locales = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).systemLocales
        } else {
            context.applicationContext.resources.configuration.locales
        }
        for (index in 0 until locales.size()) {
            when (locales[index].language) {
                "en" -> return Locale.ENGLISH
                "zh" -> return Locale.SIMPLIFIED_CHINESE
            }
        }
        return Locale.SIMPLIFIED_CHINESE
    }

    private fun selectedLocale(context: Context) = when (choice(context)) {
        ENGLISH -> Locale.ENGLISH
        CHINESE -> Locale.SIMPLIFIED_CHINESE
        else -> systemLocale(context)
    }

    private fun withLocale(context: Context, locale: Locale): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(locale)
        })

    fun refresh(context: Context): Boolean {
        val selected = selectedLocale(context)
        val changed = copy?.locale != selected
        copy = CopyResources(selected, withLocale(context, selected).resources)
        return changed
    }

    fun select(activity: Activity, tag: String) {
        require(tag == SYSTEM || tag == CHINESE || tag == ENGLISH)
        if (choice(activity) == tag) return
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            prefs(activity).edit().putString(KEY, tag).apply()
            refresh(activity.applicationContext)
            SleepWidget.refresh(activity)
            SleepSystem.channels(activity)
            SoundscapeService.updateChannel(activity)
            activity.recreate()
        }
    }

    @JvmStatic fun activityContext(base: Context): Context =
        if (Build.VERSION.SDK_INT < 33) withLocale(base, selectedLocale(base)) else base

    fun get(@StringRes id: Int, arguments: Array<out Any?>): String {
        val resources = checkNotNull(copy).resources
        return if (arguments.isEmpty()) resources.getString(id) else resources.getString(id, *arguments)
    }
}

internal fun text(@StringRes id: Int, vararg arguments: Any?): String = AppLanguage.get(id, arguments)

// Validation messages also run on the host JVM, where Android resources are unavailable.
internal fun message(chinese: String, english: String): String =
    if (AppLanguage.locale.language == "en") english else chinese
