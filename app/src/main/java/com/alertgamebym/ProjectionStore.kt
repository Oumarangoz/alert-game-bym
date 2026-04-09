package com.alertgamebym

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager

object ProjectionStore {
    var resultCode: Int? = null
    var dataIntent: Intent? = null
    private var cachedProjection: MediaProjection? = null

    fun isReady(): Boolean = resultCode != null && dataIntent != null

    fun set(code: Int, data: Intent) {
        cachedProjection?.stop()
        cachedProjection = null
        resultCode = code
        dataIntent = data
    }

    fun getProjection(context: Context): MediaProjection {
        cachedProjection?.let { return it }
        val code = resultCode ?: throw IllegalStateException("Ekran izni yok")
        val data = dataIntent ?: throw IllegalStateException("Ekran izni yok")
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mgr.getMediaProjection(code, data)
            ?: throw IllegalStateException("MediaProjection baslatılamadı")
        cachedProjection = proj
        return proj
    }

    fun clear() {
        cachedProjection?.stop()
        cachedProjection = null
        resultCode = null
        dataIntent = null
    }
}
