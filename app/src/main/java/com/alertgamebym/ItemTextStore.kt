package com.alertgamebym

import android.content.Context

object ItemTextStore {
    private const val PREF = "item_text_store"
    private const val KEY = "item_text"

    fun save(context: Context, text: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, text)
            .apply()
    }

    fun get(context: Context): String {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
    }
}
