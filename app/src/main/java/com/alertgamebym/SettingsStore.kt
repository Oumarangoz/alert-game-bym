package com.alertgamebym

import android.content.Context

object SettingsStore {
    private const val PREF = "settings_store"
    private const val KEY_MIN_CONF   = "min_conf"
    private const val KEY_TAP_OFFSET_X = "tap_offset_x"
    private const val KEY_TAP_OFFSET_Y = "tap_offset_y"

    // Güven skoru: 0.60 - 0.95, varsayilan 0.80
    fun getMinConf(context: Context): Float {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getFloat(KEY_MIN_CONF, 0.80f)
        return raw.coerceIn(0.60f, 0.95f)
    }

    fun saveMinConf(context: Context, value: Float) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_MIN_CONF, value.coerceIn(0.60f, 0.95f)).apply()
    }

    // OCR tap X offset: -200 ile +200 arasi, varsayilan 0
    fun getTapOffsetX(context: Context): Int {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt(KEY_TAP_OFFSET_X, 0).coerceIn(-200, 200)
    }

    fun saveTapOffsetX(context: Context, value: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putInt(KEY_TAP_OFFSET_X, value.coerceIn(-200, 200)).apply()
    }

    // OCR tap Y offset: -200 ile +200 arasi, varsayilan 0
    fun getTapOffsetY(context: Context): Int {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt(KEY_TAP_OFFSET_Y, 0).coerceIn(-200, 200)
    }

    fun saveTapOffsetY(context: Context, value: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putInt(KEY_TAP_OFFSET_Y, value.coerceIn(-200, 200)).apply()
    }


    // Tum ROI yazilarina tikla (true) veya sadece eslesen iteme (false)
    private const val KEY_TAP_ALL = "tap_all_items"

    fun getTapAll(context: Context): Boolean {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_TAP_ALL, false)
    }

    fun saveTapAll(context: Context, value: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TAP_ALL, value).apply()
    }
}
