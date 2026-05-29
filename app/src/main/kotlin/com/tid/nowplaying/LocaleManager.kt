package com.tid.nowplaying

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

private const val PREFS_NAME = "locale_prefs"
private const val KEY_LANGUAGE = "language"

object LocaleManager {

    fun getLanguage(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "") ?: ""

    fun setLanguage(context: Context, languageCode: String) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, languageCode).apply()
    }

    fun applyLocale(base: Context): Context {
        val code = getLanguage(base)
        if (code.isEmpty()) return base
        val locale = Locale(code)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
