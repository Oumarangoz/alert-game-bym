package com.alertgamebym.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.alertgamebym.AppLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class TapAccessibilityService : AccessibilityService() {

    data class TapResult(
        val accepted: Boolean,
        val completed: Boolean,
        val cancelled: Boolean,
        val error: String? = null
    )

    companion object {
        @Volatile
        private var instance: TapAccessibilityService? = null

        fun isAvailable(): Boolean = instance != null

        suspend fun performTap(x: Float, y: Float, durationMs: Long = 60L): TapResult {
            val service = instance
            if (service == null) {
                AppLog.add("ACC:SERVICE_UNAVAILABLE")
                return TapResult(false, false, false, "SERVICE_UNAVAILABLE")
            }
            return withTimeoutOrNull(1500L) {
                service.dispatchTapInternal(x, y, durationMs)
            } ?: run {
                AppLog.add("ACC:GESTURE_TIMEOUT x=${x.toInt()} y=${y.toInt()}")
                TapResult(true, false, true, "GESTURE_TIMEOUT")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLog.add("ACC:SERVICE_CONNECTED")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        AppLog.add("ACC:SERVICE_INTERRUPTED")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        AppLog.add("ACC:SERVICE_DESTROYED")
        super.onDestroy()
    }

    private suspend fun dispatchTapInternal(x: Float, y: Float, durationMs: Long): TapResult =
        suspendCancellableCoroutine { cont ->
            try {
                val path = Path().apply { moveTo(x, y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(1L, 180L)))
                    .build()

                val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        AppLog.add("ACC:GESTURE_COMPLETED x=${x.toInt()} y=${y.toInt()}")
                        if (cont.isActive) cont.resume(TapResult(true, true, false, null))
                    }
                    override fun onCancelled(g: GestureDescription?) {
                        AppLog.add("ACC:GESTURE_CANCELLED x=${x.toInt()} y=${y.toInt()}")
                        if (cont.isActive) cont.resume(TapResult(true, false, true, "GESTURE_CANCELLED"))
                    }
                }, null)

                if (!accepted) {
                    AppLog.add("ACC:DISPATCH_REJECTED x=${x.toInt()} y=${y.toInt()}")
                    if (cont.isActive) cont.resume(TapResult(false, false, false, "DISPATCH_REJECTED"))
                } else {
                    AppLog.add("ACC:DISPATCH_ACCEPTED x=${x.toInt()} y=${y.toInt()}")
                }
            } catch (t: Throwable) {
                AppLog.add("ACC:ERROR ${t.message ?: "unknown"}")
                if (cont.isActive) cont.resume(TapResult(false, false, false, t.message ?: "ERROR"))
            }
        }
}
