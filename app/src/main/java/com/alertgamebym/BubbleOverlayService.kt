// V12_AUTO_PATCH
package com.alertgamebym

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.alertgamebym.accessibility.TapAccessibilityService
import com.alertgamebym.image.ImageTemplateScanner
import com.alertgamebym.ocr.OcrDebugScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot

class BubbleOverlayService : Service() {

    private lateinit var windowManager: WindowManager

    private var bubbleView: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var targetView: TextView? = null
    private var targetParams: WindowManager.LayoutParams? = null

    // ROI dikdortgen overlay
    private var roiDrawView: android.view.View? = null
    private var roiDrawParams: WindowManager.LayoutParams? = null
    private var roiHandle1View: TextView? = null
    private var roiHandle1Params: WindowManager.LayoutParams? = null
    private var roiHandle2View: TextView? = null
    private var roiHandle2Params: WindowManager.LayoutParams? = null

    // Main: UI guncellemeleri, Default: agir isler (bitmap, OCR, template)
    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        AppLog.add("AUTO: BEKLENMEDIK HATA: ${t.message ?: t.javaClass.simpleName}")
    }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + exceptionHandler)
    private var autoJob: Job? = null


    private enum class AutoPhase {
        WAIT_FOR_ITEM,
        TAP_RED,
        TAP_ITEM,
        FIND_STATE2
    }

    @Volatile private var autoPhase = AutoPhase.WAIT_FOR_ITEM
    @Volatile private var itemMissCount = 0
    @Volatile private var state2MissCount = 0

 // Phase bazli antispam - STATE1/STATE2 birbirini engellemez
 private val phaseTapX = java.util.concurrent.ConcurrentHashMap<String, Float>()
 private val phaseTapY = java.util.concurrent.ConcurrentHashMap<String, Float>()
 private val phaseTapAt = java.util.concurrent.ConcurrentHashMap<String, Long>()


    companion object {
        const val CHANNEL_ID = "bubble_overlay_channel"
        const val NOTIF_ID = 3001

        const val ACTION_RESET_AUTO = "ACTION_RESET_AUTO"
        const val ICON_MIN_CONF = 0.80f
        const val ICON_RADIUS_X = 85
        const val ICON_RADIUS_Y = 85
    }

    override fun onCreate() {
        super.onCreate()

  AppLog.bind(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification("Bubble hazırlanıyor"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, buildNotification("Bubble hazırlanıyor"))
        }

        createOverlays()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESET_AUTO -> {
                resetAutoState(log = true)
                return START_STICKY
            }

        }

        createOverlays()
        refreshBubble()
        return START_STICKY
    }

    private fun resetAutoState(log: Boolean) {
        autoPhase = AutoPhase.WAIT_FOR_ITEM
        itemMissCount = 0
        state2MissCount = 0
  clearTapLocks()
        if (log) AppLog.add("AUTO: state sıfırlandı")
    }


    private fun baseOverlayFlags(): Int {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }

    private fun applyOverlayState(
        view: View?,
        params: WindowManager.LayoutParams?,
        touchable: Boolean,
        visible: Boolean
    ) {
        if (view == null || params == null) return

        params.flags = baseOverlayFlags() or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

        view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        view.alpha = if (visible) 1f else 0f

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            runCatching { windowManager.updateViewLayout(view, params) }
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
    }

    private fun hideTransientOverlaysKeepBubble() { // Main thread safe
        applyOverlayState(targetView, targetParams, false, false)
        applyOverlayState(roiHandle1View, roiHandle1Params, false, false)
        applyOverlayState(roiHandle2View, roiHandle2Params, false, false)
        roiDrawView?.visibility = android.view.View.INVISIBLE
        applyOverlayState(bubbleView, bubbleParams, true, true)
    }

    private fun restoreTransientOverlaysKeepBubble() {
        applyOverlayState(targetView, targetParams, true, true)
        applyOverlayState(roiHandle1View, roiHandle1Params, true, true)
        applyOverlayState(roiHandle2View, roiHandle2Params, true, true)
        // roiDrawView: flaglara dokunma, sadece visibility + updateViewLayout
        roiDrawView?.let { v ->
            v.visibility = android.view.View.VISIBLE
            v.alpha = 1f
            roiDrawParams?.let { lp -> runCatching { windowManager.updateViewLayout(v, lp) } }
        }
        applyOverlayState(bubbleView, bubbleParams, true, true)
    }

    private fun hasAnyReference(): Boolean {
        return ReferenceStore.get(this, ReferenceStore.KEY_STATE1) != null ||
            ReferenceStore.get(this, ReferenceStore.KEY_STATE2) != null
    }

    private fun startAutoLoop() {
        stopAutoLoop()

        if (!ProjectionStore.isReady()) {
            AppLog.add("AUTO: ekran izni yok")
            ControlCenter.setBubbleRunning(false)
            refreshBubble()
            return
        }

        if (!hasAnyReference()) {
            AppLog.add("AUTO: referans görsel yok")
            ControlCenter.setBubbleRunning(false)
            refreshBubble()
            return
        }

        val items = ItemRulesStore.getItems(this)
        if (items.isEmpty()) {
            AppLog.add("AUTO: item listesi boş")
            ControlCenter.setBubbleRunning(false)
            refreshBubble()
            return
        }

        val minConf = SettingsStore.getMinConf(this)
        val tapOffsetX = SettingsStore.getTapOffsetX(this)
        val tapOffsetY = SettingsStore.getTapOffsetY(this)
        val tapAll = SettingsStore.getTapAll(this)

        resetAutoState(log = false)

        autoJob = scope.launch {
            // Referanslari loop basinda bir kez yukle
            val cachedRef1 = ReferenceStore.get(this@BubbleOverlayService, ReferenceStore.KEY_STATE1)
            val cachedRef2 = ReferenceStore.get(this@BubbleOverlayService, ReferenceStore.KEY_STATE2)
            AppLog.add("AUTO: loop başladı")
            AppLog.add("AUTO: ITEM-LIST -> ${items.size} kayıt")
            AppLog.add("AUTO: IKON ROI x=${ControlCenter.targetX.value.toInt()} y=${ControlCenter.targetY.value.toInt()} r=${ICON_RADIUS_X}x${ICON_RADIUS_Y} | ITEM ROI x1=${ControlCenter.itemRoiX1.value.toInt()} y1=${ControlCenter.itemRoiY1.value.toInt()} x2=${ControlCenter.itemRoiX2.value.toInt()} y2=${ControlCenter.itemRoiY2.value.toInt()}")
            AppLog.add("AUTO: conf=${minConf} offsetX=${tapOffsetX} offsetY=${tapOffsetY}")

            while (isActive && ControlCenter.bubbleRunning.value) {
                when (autoPhase) {

                    AutoPhase.WAIT_FOR_ITEM -> {
                        // Kirmizi ikonu kontrol et (tikla degil)
                        val ref1 = cachedRef1
                        val roiX = ControlCenter.targetX.value.toInt()
                        val roiY = ControlCenter.targetY.value.toInt()

                        val kirmizi = if (ref1 != null) {
                            withContext(Dispatchers.Main) { hideTransientOverlaysKeepBubble() }
                            delay(120)
                            val m = withContext(Dispatchers.Default) {
                                ImageTemplateScanner.findBestMatch(
                                    context = this@BubbleOverlayService,
                                    templateUri = ref1,
                                    label = "STATE1",
                                    centerX = roiX, centerY = roiY,
                                    radiusX = ICON_RADIUS_X, radiusY = ICON_RADIUS_Y,
                                    fullScreen = false
                                )
                            }
                            val result = m != null && m.confidence >= minConf
                            withContext(Dispatchers.Main) { restoreTransientOverlaysKeepBubble(); refreshBubble() }
                            result
                        } else false

                        if (!kirmizi) {
                            AppLog.add("DEBUG: kirmizi yok, ref1=${ref1 != null} roiX=$roiX roiY=$roiY")
                            delay(400) // CPU yormasin
                        } else {
                            // Kirmizi var - item dusmus mu bak
                            val x1 = ControlCenter.itemRoiX1.value.toInt()
                            val y1 = ControlCenter.itemRoiY1.value.toInt()
                            val x2 = ControlCenter.itemRoiX2.value.toInt()
                            val y2 = ControlCenter.itemRoiY2.value.toInt()

                            withContext(Dispatchers.Main) { hideTransientOverlaysKeepBubble() }
                            delay(200)
                            val lines = withContext(Dispatchers.IO) {
                                OcrDebugScanner.scanRegion(this@BubbleOverlayService, x1, y1, x2, y2)
                            }
                            withContext(Dispatchers.Main) { restoreTransientOverlaysKeepBubble(); refreshBubble() }
                            val itemFound = lines.any { line ->
                                items.any { q -> line.text.lowercase().contains(q.lowercase()) }
                            }

                            if (itemFound) {
                                AppLog.add("AUTO: KIRMIZI var + ITEM dusmus -> kirmiziya tikliyorum")
                                autoPhase = AutoPhase.TAP_RED
                            } else {
                                delay(300)
                            }
                        }
                    }

                    AutoPhase.TAP_RED -> {
                        // Kirmiziya SADECE BIR KEZ tikla - basarisiz olsa da devam et
                        performSingleRefTap(
                            key = ReferenceStore.KEY_STATE1,
                            label = "STATE1",
                            logPrefix = "AUTO-R",
                            silentNoMatch = false,
                            minConf = minConf,
                            cachedUri = cachedRef1
                        )
                        AppLog.add("AUTO: KIRMIZI tek tikla -> item aranıyor")
                        autoPhase = AutoPhase.TAP_ITEM
                    }

                    AutoPhase.TAP_ITEM -> {
                        // Item yazisina tikla
                        val foundQuery = performItemOcrTap(
                            itemQueries = items,
                            logPrefix = "AUTO-I",
                            silentNoMatch = false,
                            tapOffsetX = tapOffsetX,
                            tapOffsetY = tapOffsetY,
                            tapAll = tapAll
                        )
                        if (foundQuery != null) {
                            AppLog.add("AUTO: ITEM alindi -> '$foundQuery' - 1sn sonra STATE2")
                            delay(1000)
                            itemMissCount = 0
                            autoPhase = AutoPhase.FIND_STATE2
                        } else {
                            itemMissCount += 1
                            if (itemMissCount % 10 == 1) {
                                AppLog.add("AUTO-I: item bekleniyor (${itemMissCount}. deneme)")
                            }
                            delay(300)
                        }
                    }

                    AutoPhase.FIND_STATE2 -> {
                        val tapped = performSingleRefTap(
                            key = ReferenceStore.KEY_STATE2,
                            label = "STATE2",
                            logPrefix = "AUTO-B",
                            silentNoMatch = true,
                            minConf = minConf,
                            cachedUri = cachedRef2
                        )
                        if (tapped) {
                            AppLog.add("AUTO: KAHVERENGI tiklandi -> ava donuluyor")
                            resetAutoState(log = false)
                            delay(300)
                        } else {
                            state2MissCount += 1
                            if (state2MissCount % 5 == 1) {
                                AppLog.add("AUTO-B: kahverengi bulunamadi")
                            }
                            delay(400)
                        }
                    }
                }
            }

            AppLog.add("AUTO: loop durdu")
        }
    }

    private fun stopAutoLoop() {
        autoJob?.cancel()
        autoJob = null
    }

    private fun createOverlays() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AppLog.add("BUBBLE: Overlay izni yok")
            stopSelf()
            return
        }

        createBubbleIfNeeded()
        createTargetIfNeeded()
        createRoiOverlayIfNeeded()
        refreshBubble()
    }

    private fun createBubbleIfNeeded() {
        if (bubbleView != null) return

        val tv = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(20, 20, 20, 20)
            elevation = 14f
        }

        val lp = WindowManager.LayoutParams(
            170,
            170,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseOverlayFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 300
        }

        var startX = 0
        var startY = 0
        var startTouchX = 0f
        var startTouchY = 0f
        var downAt = 0L
        var dragging = false

        tv.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    downAt = SystemClock.uptimeMillis()
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startTouchX
                    val dy = event.rawY - startTouchY
                    if (abs(dx) > 10 || abs(dy) > 10) dragging = true
                    if (dragging) {
                        lp.x = (startX + dx).toInt()
                        lp.y = (startY + dy).toInt()
                        runCatching { windowManager.updateViewLayout(v, lp) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        if (ControlCenter.bubbleRunning.value) {
                            ControlCenter.setBubbleRunning(false)
                            stopAutoLoop()
                            AppLog.add("AUTO: OFF")
                            refreshBubble()
                            return@setOnTouchListener true
                        }

                        val pressDuration = SystemClock.uptimeMillis() - downAt
                        if (pressDuration >= 650L) {
                            AppLog.add("AUTO: OFF")
                            refreshBubble()
                        } else {
                            ControlCenter.setBubbleRunning(true)
                            AppLog.add("AUTO: ON")
                            refreshBubble()
                            startAutoLoop()
                        }
                    }
                    true
                }

                else -> false
            }
        }

        runCatching {
            windowManager.addView(tv, lp)
            bubbleView = tv
            bubbleParams = lp
            AppLog.add("BUBBLE: eklendi")
        }.onFailure {
            AppLog.add("BUBBLE: addView hatası: ${it.message}")
        }
    }

    private fun createTargetIfNeeded() {
        if (targetView != null) return

        val dm = resources.displayMetrics
        val centerX = dm.widthPixels / 2
        val centerY = dm.heightPixels / 2

        val tv = TextView(this).apply {
            text = "◎"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#55E91E63"))
                setStroke(3, Color.WHITE)
            }
            elevation = 12f
        }

        val lp = WindowManager.LayoutParams(
            120,
            120,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseOverlayFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = centerX - 60
            y = centerY - 60
        }

        var startX = 0
        var startY = 0
        var startTouchX = 0f
        var startTouchY = 0f

        tv.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startTouchX
                    val dy = event.rawY - startTouchY
                    lp.x = (startX + dx).toInt()
                    lp.y = (startY + dy).toInt()
                    runCatching { windowManager.updateViewLayout(v, lp) }

                    val tx = lp.x + 60f
                    val ty = lp.y + 60f
                    ControlCenter.setTarget(tx, ty)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val tx = lp.x + 60f
                    val ty = lp.y + 60f
                    ControlCenter.setTarget(tx, ty)
                    getSharedPreferences("overlay_pos", MODE_PRIVATE).edit()
                        .putFloat("target_x", tx).putFloat("target_y", ty).apply()
                    AppLog.add("TARGET: x=${tx.toInt()} y=${ty.toInt()}")
                    true
                }

                else -> false
            }
        }

        // Kayitli pozisyon yukle
        val savedTx = getSharedPreferences("overlay_pos", MODE_PRIVATE).getFloat("target_x", -1f)
        val savedTy = getSharedPreferences("overlay_pos", MODE_PRIVATE).getFloat("target_y", -1f)
        if (savedTx > 0 && savedTy > 0) {
            lp.x = (savedTx - 60f).toInt()
            lp.y = (savedTy - 60f).toInt()
        }
        runCatching {
            windowManager.addView(tv, lp)
            targetView = tv
            targetParams = lp
            ControlCenter.setTarget(lp.x + 60f, lp.y + 60f)
            AppLog.add("TARGET: işaretçi eklendi")
        }.onFailure {
            AppLog.add("TARGET: addView hatası: ${it.message}")
        }
    }


    // ─── ROI Dikdortgen Overlay ───────────────────────────────────────────

    private fun createRoiOverlayIfNeeded() {
        if (roiHandle1View != null) return

        val dm = resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        val handleSize = 90
        val half = handleSize / 2

        // Baslangic konumu: ekranin orta-alt bolgesinde
        val initX1 = (sw * 0.05f).toInt()
        val initY1 = (sh * 0.45f).toInt()
        val initX2 = (sw * 0.95f).toInt()
        val initY2 = (sh * 0.65f).toInt()

        // Dikdortgen cizim view (tam ekran seffaf, dokunulamaz)
        val drawView = object : android.view.View(this) {
            private val borderPaint = Paint().apply {
                color = android.graphics.Color.GREEN
                style = Paint.Style.STROKE
                strokeWidth = 4f
                isAntiAlias = true
            }
            private val dimPaint = Paint().apply {
                color = android.graphics.Color.argb(40, 0, 200, 0)
                style = Paint.Style.FILL
            }
            init { setWillNotDraw(false) }
            override fun onDraw(canvas: Canvas) {
                val x1 = ControlCenter.itemRoiX1.value
                val y1 = ControlCenter.itemRoiY1.value
                val x2 = ControlCenter.itemRoiX2.value
                val y2 = ControlCenter.itemRoiY2.value
                if (x1 == x2 && y1 == y2) return // ROI boyutu 0, cizme
                val l = minOf(x1, x2); val t = minOf(y1, y2)
                val r = maxOf(x1, x2); val b = maxOf(y1, y2)
                canvas.drawRect(l, t, r, b, dimPaint)
                canvas.drawRect(l, t, r, b, borderPaint)
            }
        }

        val drawLp = WindowManager.LayoutParams(
            sw, sh,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSPARENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }

        // Kayitli ROI pozisyon yukle - handle pozisyonlari da kayitli degerle baslasin
        val roiPrefs = getSharedPreferences("overlay_pos", MODE_PRIVATE)
        val rx1 = roiPrefs.getFloat("roi_x1", initX1.toFloat())
        val ry1 = roiPrefs.getFloat("roi_y1", initY1.toFloat())
        val rx2 = roiPrefs.getFloat("roi_x2", initX2.toFloat())
        val ry2 = roiPrefs.getFloat("roi_y2", initY2.toFloat())
        ControlCenter.setItemRoi(rx1, ry1, rx2, ry2)

        // Sol-ust kose (◤) - kayitli pozisyondan baslat
        val (h1, lp1) = createRoiHandle(
            symbol = "◤", initX = (rx1 - half).toInt(), initY = (ry1 - half).toInt(), size = handleSize
        ) { cx, cy ->
            ControlCenter.setItemRoi(cx, cy, ControlCenter.itemRoiX2.value, ControlCenter.itemRoiY2.value)
            drawView.postInvalidate()
        }

        // Sag-alt kose (◢) - kayitli pozisyondan baslat
        val (h2, lp2) = createRoiHandle(
            symbol = "◢", initX = (rx2 - half).toInt(), initY = (ry2 - half).toInt(), size = handleSize
        ) { cx, cy ->
            ControlCenter.setItemRoi(ControlCenter.itemRoiX1.value, ControlCenter.itemRoiY1.value, cx, cy)
            drawView.postInvalidate()
        }

        runCatching {
            windowManager.addView(drawView, drawLp)
            roiDrawView = drawView; roiDrawParams = drawLp

            windowManager.addView(h1, lp1)
            roiHandle1View = h1; roiHandle1Params = lp1

            windowManager.addView(h2, lp2)
            roiHandle2View = h2; roiHandle2Params = lp2

            AppLog.add("ROI: dikdortgen eklendi " +
                "x1=${rx1.toInt()} y1=${ry1.toInt()} x2=${rx2.toInt()} y2=${ry2.toInt()}")
        }.onFailure {
            AppLog.add("ROI: hata: ${it.message}")
        }
    }

    private fun createRoiHandle(
        symbol: String,
        initX: Int,
        initY: Int,
        size: Int,
        onMoved: (cx: Float, cy: Float) -> Unit
    ): Pair<TextView, WindowManager.LayoutParams> {
        val half = size / 2
        val tv = TextView(this).apply {
            text = symbol
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f
                setColor(Color.parseColor("#CC004400"))
                setStroke(4, Color.GREEN)
            }
            elevation = 15f
        }

        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseOverlayFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initX; y = initY
        }

        var sx = 0; var sy = 0; var stx = 0f; var sty = 0f

        tv.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.x; sy = lp.y
                    stx = event.rawX; sty = event.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (sx + event.rawX - stx).toInt()
                    lp.y = (sy + event.rawY - sty).toInt()
                    runCatching { windowManager.updateViewLayout(v, lp) }
                    onMoved(lp.x + half.toFloat(), lp.y + half.toFloat())
                    true
                }
                MotionEvent.ACTION_UP -> {
                    onMoved(lp.x + half.toFloat(), lp.y + half.toFloat())
                    getSharedPreferences("overlay_pos", MODE_PRIVATE).edit()
                        .putFloat("roi_x1", ControlCenter.itemRoiX1.value)
                        .putFloat("roi_y1", ControlCenter.itemRoiY1.value)
                        .putFloat("roi_x2", ControlCenter.itemRoiX2.value)
                        .putFloat("roi_y2", ControlCenter.itemRoiY2.value).apply()
                    AppLog.add("ROI: x1=${ControlCenter.itemRoiX1.value.toInt()}" +
                        " y1=${ControlCenter.itemRoiY1.value.toInt()}" +
                        " x2=${ControlCenter.itemRoiX2.value.toInt()}" +
                        " y2=${ControlCenter.itemRoiY2.value.toInt()}")
                    true
                }
                else -> false
            }
        }
        return Pair(tv, lp)
    }




    private suspend fun performTapAt(xRaw: Float, yRaw: Float, prefix: String, skipHide: Boolean = false): Boolean {
        if (!TapAccessibilityService.isAvailable()) {
            withContext(Dispatchers.Main) {
                restoreTransientOverlaysKeepBubble()
                refreshBubble()
            }
            AppLog.add("$prefix: Tap BASARISIZ - accessibility servisi yok")
            return false
        }

        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels.toFloat() - 2f).coerceAtLeast(2f)
        val maxY = (dm.heightPixels.toFloat() - 2f).coerceAtLeast(2f)
        val x = xRaw.coerceIn(2f, maxX)
        val y = yRaw.coerceIn(2f, maxY)

        if (x != xRaw || y != yRaw) {
            AppLog.add(
                "$prefix: clamp tap ham=(${xRaw.toInt()},${yRaw.toInt()}) -> giden=(${x.toInt()},${y.toInt()})"
            )
        } else {
            AppLog.add("$prefix: tap x=${x.toInt()} y=${y.toInt()}")
        }

        if (!skipHide) {
            withContext(Dispatchers.Main) { hideTransientOverlaysKeepBubble() }
            delay(150)
        }

        val result = withContext(Dispatchers.Main) {
            TapAccessibilityService.performTap(x, y, 60L)
        }

        delay(150)
        withContext(Dispatchers.Main) {
            restoreTransientOverlaysKeepBubble()
            refreshBubble()
        }

        return when {
            result.completed -> {
                AppLog.add("$prefix: TAP OK x=${x.toInt()} y=${y.toInt()}")
                true
            }
            result.cancelled -> {
                AppLog.add("$prefix: TAP IPTAL x=${x.toInt()} y=${y.toInt()} reason=${result.error ?: "cancelled"}")
                false
            }
            result.error != null -> {
                AppLog.add("$prefix: TAP HATA: ${result.error}")
                false
            }
            else -> {
                AppLog.add("$prefix: TAP BELIRSIZ")
                false
            }
        }
    }

    private suspend fun performSingleRefTap(
        key: String,
        label: String,
        logPrefix: String,
        silentNoMatch: Boolean,
        minConf: Float = ICON_MIN_CONF,
        cachedUri: android.net.Uri? = null
    ): Boolean {
        val ref = cachedUri ?: ReferenceStore.get(this, key) ?: return false

        val roiX = ControlCenter.targetX.value.toInt()
        val roiY = ControlCenter.targetY.value.toInt()

        withContext(Dispatchers.Main) { hideTransientOverlaysKeepBubble() }
        if (!silentNoMatch) AppLog.add("$logPrefix: overlays hidden")
        delay(120)

        return try {
            val match = withContext(Dispatchers.Default) {
                ImageTemplateScanner.findBestMatch(
                    context = this@BubbleOverlayService,
                    templateUri = ref,
                    label = label,
                    centerX = roiX,
                    centerY = roiY,
                    radiusX = ICON_RADIUS_X,
                    radiusY = ICON_RADIUS_Y,
                    fullScreen = false
                )
            }

            if (match == null) {
                withContext(Dispatchers.Main) {
                    restoreTransientOverlaysKeepBubble()
                    refreshBubble()
                }
                if (!silentNoMatch) AppLog.add("$logPrefix: bulunamadı roi x=$roiX y=$roiY")
                false
            } else if (match.confidence < minConf) {
                withContext(Dispatchers.Main) {
                    restoreTransientOverlaysKeepBubble()
                    refreshBubble()
                }
                if (!silentNoMatch) {
                    AppLog.add(
                        "$logPrefix: zayif conf=${"%.2f".format(match.confidence)} min=${"%.2f".format(minConf)}"
                    )
                }
                false
            } else {
                if (!silentNoMatch) {
                    AppLog.add(
                        "$logPrefix: ESLEME conf=${"%.2f".format(match.confidence)} @(${match.centerX},${match.centerY})"
                    )
                }

                val cx = match.centerX.toFloat()
                val cy = match.centerY.toFloat()
                if (!shouldClickPhase(logPrefix, cx, cy)) {
                    withContext(Dispatchers.Main) {
                        restoreTransientOverlaysKeepBubble()
                        refreshBubble()
                    }
                    if (!silentNoMatch) {
                        AppLog.add("$logPrefix: ANTISPAM nedeniyle tap atlandi, state ilerlemeyecek")
                    }
                    return false
                }

                val tapOk = performTapAt(cx, cy, logPrefix, skipHide = true)
                if (tapOk) {
                    markPhaseClicked(logPrefix, cx, cy)
                } else if (!silentNoMatch) {
                    AppLog.add("$logPrefix: UYARI tap basarisiz, state ilerlemeyecek")
                }
                tapOk
            }
        } catch (t: Throwable) {
            withContext(Dispatchers.Main) {
                restoreTransientOverlaysKeepBubble()
                refreshBubble()
            }
            if (!silentNoMatch) AppLog.add("$logPrefix: hata: ${t.message ?: "bilinmeyen hata"}")
            false
        }
    }


    private suspend fun performItemOcrTap(
        itemQueries: List<String>,
        logPrefix: String,
        silentNoMatch: Boolean,
        tapOffsetX: Int = 0,
        tapOffsetY: Int = 0,
        tapAll: Boolean = false
    ): String? {
        if (itemQueries.isEmpty()) return null

        withContext(Dispatchers.Main) { hideTransientOverlaysKeepBubble() }
        if (!silentNoMatch) AppLog.add("$logPrefix: overlays hidden")
        delay(150)

        return try {
            val x1 = ControlCenter.itemRoiX1.value.toInt()
            val y1 = ControlCenter.itemRoiY1.value.toInt()
            val x2 = ControlCenter.itemRoiX2.value.toInt()
            val y2 = ControlCenter.itemRoiY2.value.toInt()
            val lines = withContext(Dispatchers.IO) {
                OcrDebugScanner.scanRegion(this@BubbleOverlayService, x1, y1, x2, y2)
            }

            val normalizedQueries = itemQueries
                .map { it.trim() }
                .filter { it.isNotBlank() }

            val roiCx = (ControlCenter.itemRoiX1.value + ControlCenter.itemRoiX2.value) / 2.0
            val roiCy = (ControlCenter.itemRoiY1.value + ControlCenter.itemRoiY2.value) / 2.0

            data class Candidate(
                val query: String,
                val lineText: String,
                val x: Int,
                val y: Int
            )

            val candidates = mutableListOf<Candidate>()
            if (tapAll) {
                // Tum ROI yazilarina tikla - item listesine bakma
                for (line in lines) {
                    if (line.text.isNotBlank()) {
                        candidates += Candidate("*", line.text, line.x, line.y)
                    }
                }
            } else {
                // Sadece item listesindeki yazilari ara
                for (line in lines) {
                    val lower = line.text.lowercase()
                    for (query in normalizedQueries) {
                        if (lower.contains(query.lowercase())) {
                            candidates += Candidate(query, line.text, line.x, line.y)
                        }
                    }
                }
            }

            if (candidates.isEmpty()) {
                withContext(Dispatchers.Main) {
                    restoreTransientOverlaysKeepBubble()
                    refreshBubble()
                }
                if (!silentNoMatch) AppLog.add("$logPrefix: item yok")
                return null
            }

            // Hepsini ROI merkezine yakinliga gore sirala
            val sorted = candidates.sortedBy {
                kotlin.math.hypot(it.x - roiCx, it.y - roiCy)
            }

            val targets = if (tapAll) sorted else listOf(sorted.first())
            var lastQuery: String? = null

            for (target in targets) {
                val tapX = target.x.toFloat() + tapOffsetX
                val tapY = target.y.toFloat() + tapOffsetY
                if (!silentNoMatch) {
                    AppLog.add("$logPrefix: TAP query='${target.query}' text='${target.lineText}' @(${target.x},${target.y}) -> (${tapX.toInt()},${tapY.toInt()})")
                }
                val tapOk = performTapAt(tapX, tapY, logPrefix, skipHide = true)
                if (tapOk) {
                    lastQuery = target.query
                    if (tapAll && targets.size > 1) delay(150)
                }
            }
            // Son olarak overlay restore et
            withContext(Dispatchers.Main) {
                restoreTransientOverlaysKeepBubble()
                refreshBubble()
            }
            lastQuery
        } catch (t: Throwable) {
            withContext(Dispatchers.Main) {
                restoreTransientOverlaysKeepBubble()
                refreshBubble()
            }
            if (!silentNoMatch) {
                AppLog.add("$logPrefix: overlays restored")
                AppLog.add("$logPrefix: hata: ${t.message ?: "bilinmeyen hata"}")
            }
            null
        }
    }



    





 private fun clearTapLocks() {
  phaseTapX.clear()
  phaseTapY.clear()
  phaseTapAt.clear()
 }







 private fun shouldClickPhase(phaseKey: String, x: Float, y: Float): Boolean {
  val lx = phaseTapX[phaseKey] ?: -999f
  val ly = phaseTapY[phaseKey] ?: -999f
  val lat = phaseTapAt[phaseKey] ?: 0L
  val nearSamePoint = abs(lx - x) <= 25f && abs(ly - y) <= 25f
  val now = SystemClock.uptimeMillis()
  if (nearSamePoint && now - lat < 3000L) {
   AppLog.add("ANTISPAM: $phaseKey ayni nokta engellendi x=${x.toInt()} y=${y.toInt()} (${now-lat}ms)")
   return false
  }
  return true
 }

 private fun markPhaseClicked(phaseKey: String, x: Float, y: Float) {
  phaseTapX[phaseKey] = x
  phaseTapY[phaseKey] = y
  phaseTapAt[phaseKey] = SystemClock.uptimeMillis()
 }

private fun refreshBubble() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { refreshBubble() }
            return
        }
        val tv = bubbleView ?: return
        val isOn = ControlCenter.bubbleRunning.value

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(4, Color.WHITE)
            setColor(Color.parseColor(if (isOn) "#1E88E5" else "#607D8B"))
        }

        tv.background = bg
        tv.text = if (isOn) "ON\nAUTO" else "OFF\n⏸"
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bubble Overlay",
            NotificationManager.IMPORTANCE_MIN
        )
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Alert Game ByM")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAutoLoop()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        targetView?.let { runCatching { windowManager.removeView(it) } }
        roiHandle1View?.let { runCatching { windowManager.removeView(it) } }
        roiHandle2View?.let { runCatching { windowManager.removeView(it) } }
        roiDrawView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        bubbleParams = null
        targetView = null
        targetParams = null
        roiHandle1View = null; roiHandle1Params = null
        roiHandle2View = null; roiHandle2Params = null
        roiDrawView = null; roiDrawParams = null
        scope.cancel()
        com.alertgamebym.ocr.OcrDebugScanner.invalidateCache()
        com.alertgamebym.image.ImageTemplateScanner.invalidateCache()
        AppLog.add("BUBBLE: servis kapandı")
        super.onDestroy()
    }
}
