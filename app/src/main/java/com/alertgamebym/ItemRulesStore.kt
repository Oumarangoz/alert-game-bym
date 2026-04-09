package com.alertgamebym

import android.content.Context

object ItemRulesStore {
    private const val PREF = "item_rules_store"
    private const val KEY_ITEMS = "items"
    private const val KEY_TIMEOUT = "timeout_seconds"

    fun getItems(context: Context): List<String> {
        val set = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY_ITEMS, emptySet())
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: emptyList()
        return set.sorted()
    }

    fun addItem(context: Context, item: String) {
        val clean = item.trim()
        if (clean.isBlank()) return
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_ITEMS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(clean)
        prefs.edit().putStringSet(KEY_ITEMS, current).apply()
    }

    fun removeItem(context: Context, item: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_ITEMS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(item)
        prefs.edit().putStringSet(KEY_ITEMS, current).apply()
    }

    fun clearItems(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_ITEMS, emptySet())
            .apply()
    }

    fun getTimeoutSeconds(context: Context): Int {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt(KEY_TIMEOUT, 10)
            .coerceIn(1, 300)
    }

    fun saveTimeoutSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_TIMEOUT, seconds.coerceIn(1, 300))
            .apply()
    }
}
