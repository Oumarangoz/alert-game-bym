package com.alertgamebym

import android.content.Context
import android.net.Uri

object ReferenceStore {
    const val KEY_STATE1 = "state1_uri"
    const val KEY_STATE2 = "state2_uri"

    private const val PREF = "reference_store"

    fun save(context: Context, key: String, uri: Uri) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(key, uri.toString())
            .apply()
    }

    fun get(context: Context, key: String): Uri? {
        val s = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(key, null)
        return if (s.isNullOrBlank()) null else Uri.parse(s)
    }

    fun has(context: Context, key: String): Boolean = get(context, key) != null
}
