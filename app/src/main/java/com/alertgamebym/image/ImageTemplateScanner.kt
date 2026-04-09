package com.alertgamebym.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import com.alertgamebym.ProjectionStore
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class TemplateMatch(
    val label: String,
    val confidence: Float,
    val centerX: Int,
    val centerY: Int,
    val width: Int,
    val height: Int
)

object ImageTemplateScanner {

    // VirtualDisplay cache - her cagirda yeniden olusturma
    @Volatile private var cachedReader: ImageReader? = null
    @Volatile private var cachedVd: VirtualDisplay? = null
    @Volatile private var cachedW = 0
    @Volatile private var cachedH = 0

    fun invalidateCache() {
        runCatching { cachedVd?.release() }
        runCatching { cachedReader?.close() }
        cachedVd = null
        cachedReader = null
        cachedW = 0
        cachedH = 0
    }

    suspend fun findBestMatch(
        context: Context,
        templateUri: Uri,
        label: String,
        centerX: Int? = null,
        centerY: Int? = null,
        radiusX: Int = 220,
        radiusY: Int = 220,
        fullScreen: Boolean = false
    ): TemplateMatch? {
        val screen = captureScreenBitmap(context) ?: return null
        val templateOriginal = loadTemplateBitmap(context, templateUri) ?: run {
            screen.recycle()
            return null
        }
        var screenScaledRef: Bitmap? = null
        var templateBaseRef: Bitmap? = null

        // Ekrani 720px genislige scale et (540 yerine - daha fazla detay)
        val factor = min(1f, 720f / screen.width.toFloat())

        val screenScaled = if (factor < 1f) {
            Bitmap.createScaledBitmap(
                screen,
                max((screen.width * factor).toInt(), 1),
                max((screen.height * factor).toInt(), 1),
                true
            ).also { screenScaledRef = it }
        } else screen

        val templateBase = if (factor < 1f) {
            Bitmap.createScaledBitmap(
                templateOriginal,
                max((templateOriginal.width * factor).toInt(), 12),
                max((templateOriginal.height * factor).toInt(), 12),
                true
            ).also { templateBaseRef = it }
        } else templateOriginal

        val screenGray = toGray(screenScaled)
        val screenPixels = toPixels(screenScaled)

        val scaledCx = ((centerX ?: (screen.width / 2)) * factor).toInt()
        val scaledCy = ((centerY ?: (screen.height / 2)) * factor).toInt()
        val scaledRx = max((radiusX * factor).toInt(), 24)
        val scaledRy = max((radiusY * factor).toInt(), 24)

        var best: TemplateMatch? = null

        // Daha genis scale araligi - oyun UI degisikliklerine karsi
        val scales = listOf(0.80f, 0.90f, 1.00f, 1.10f, 1.20f)

        for (scale in scales) {
            val tw = max((templateBase.width * scale).toInt(), 12)
            val th = max((templateBase.height * scale).toInt(), 12)

            if (tw >= screenScaled.width || th >= screenScaled.height) continue

            val tpl = if (scale == 1.0f) templateBase
            else Bitmap.createScaledBitmap(templateBase, tw, th, true)

            val tplGray = toGray(tpl)
            val tplPixels = toPixels(tpl)

            val xStart: Int; val yStart: Int
            val xEnd: Int;   val yEnd: Int

            if (fullScreen) {
                xStart = 0; yStart = 0
                xEnd = screenScaled.width - tw
                yEnd = screenScaled.height - th
            } else {
                xStart = max(0, scaledCx - scaledRx - tw / 2)
                yStart = max(0, scaledCy - scaledRy - th / 2)
                xEnd = min(screenScaled.width - tw, scaledCx + scaledRx - tw / 2)
                yEnd = min(screenScaled.height - th, scaledCy + scaledRy - th / 2)
            }

            if (xEnd >= xStart && yEnd >= yStart) {
                // step=2: daha hassas tarama (3 yerine)
                val step = 2
                var y = yStart
                while (y <= yEnd) {
                    var x = xStart
                    while (x <= xEnd) {
                        val similarity = scoreAt(
                            screenGray, screenPixels, screenScaled.width,
                            x, y, tplGray, tplPixels, tw, th
                        )

                        val finalScore = if (fullScreen) {
                            similarity
                        } else {
                            val cx = x + tw / 2
                            val cy = y + th / 2
                            val dist = hypot((cx - scaledCx).toFloat(), (cy - scaledCy).toFloat())
                            val maxDist = hypot(scaledRx.toFloat(), scaledRy.toFloat()).coerceAtLeast(1f)
                            // Penalty 0.16 -> 0.08 (daha az ceza, uzaktaki gercek eslesme kaybolmasin)
                            similarity - ((dist / maxDist) * 0.08f)
                        }

                        if (best == null || finalScore > best!!.confidence) {
                            best = TemplateMatch(
                                label = label,
                                confidence = finalScore,
                                centerX = ((x + tw / 2) / factor).toInt(),
                                centerY = ((y + th / 2) / factor).toInt(),
                                width = (tw / factor).toInt(),
                                height = (th / factor).toInt()
                            )
                        }

                        x += step
                    }
                    y += step
                }
            }

            if (tpl !== templateBase) tpl.recycle()
        }

        // Normal recycle
        if (screenScaled !== screen) screenScaledRef?.recycle()
        runCatching { screen.recycle() }
        if (templateBase !== templateOriginal) templateBaseRef?.recycle()
        runCatching { templateOriginal.recycle() }

        return best
    }

    private suspend fun captureScreenBitmap(context: Context): Bitmap? {
        return try {
            val projection = ProjectionStore.getProjection(context)
            val dm = context.resources.displayMetrics
            val width = dm.widthPixels
            val height = dm.heightPixels
            val density = dm.densityDpi

            // Cache kontrol
            var reader = cachedReader
            var vd = cachedVd
            if (reader == null || vd == null || cachedW != width || cachedH != height) {
                runCatching { vd?.release() }
                runCatching { reader?.close() }
                reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                vd = projection.createVirtualDisplay(
                    "img-template-scan", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, null
                )
                cachedReader = reader
                cachedVd = vd
                cachedW = width
                cachedH = height
            }

            // Eski frameleri temizle
            repeat(3) { reader!!.acquireLatestImage()?.close() }
            delay(50)
            // Yeni taze frame al
            repeat(15) {
                val image = reader!!.acquireLatestImage()
                if (image != null) return imageToBitmap(image, width, height)
                delay(80)
            }
            null
        } catch (e: Exception) {
            invalidateCache()
            null
        }
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        image.use { img ->
            val plane = img.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val raw = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            raw.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(raw, 0, 0, width, height)
            raw.recycle() // ara bitmap leak onle
            return cropped
        }
    }

    private fun loadTemplateBitmap(context: Context, uri: Uri): Bitmap? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        }.getOrNull()
    }

    private fun toGray(bitmap: Bitmap): IntArray {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return IntArray(w * h) { i ->
            val p = pixels[i]
            ((p shr 16 and 0xFF) * 30 + (p shr 8 and 0xFF) * 59 + (p and 0xFF) * 11) / 100
        }
    }

    private fun toPixels(bitmap: Bitmap): IntArray {
        val w = bitmap.width; val h = bitmap.height
        val out = IntArray(w * h)
        bitmap.getPixels(out, 0, w, 0, 0, w, h)
        return out
    }

    private fun scoreAt(
        screenGray: IntArray, screenPixels: IntArray, screenW: Int,
        sx: Int, sy: Int,
        tplGray: IntArray, tplPixels: IntArray,
        tplW: Int, tplH: Int
    ): Float {
        val sampleCols = 16
        val sampleRows = 16
        var grayDiffSum = 0f
        var rDiffSum = 0f
        var gDiffSum = 0f
        var bDiffSum = 0f
        var weightSum = 0f

        // Merkez: ikon ic kismi (glow ring disarida kalir)
        // Glow ring genelde dis %30-35'lik halka - merkeze yakin pikseller daha onemli
        val cx = sampleCols / 2f
        val cy = sampleRows / 2f
        val maxR = cx * 0.72f // ic %72'lik daireyi hedefle, dis %28 dusuk agirlik

        for (ry in 0 until sampleRows) {
            val ty = if (sampleRows == 1) 0 else (ry * (tplH - 1)) / (sampleRows - 1)
            for (rx in 0 until sampleCols) {
                val tx = if (sampleCols == 1) 0 else (rx * (tplW - 1)) / (sampleCols - 1)

                // Merkeze uzaklik (0.0 = merkez, 1.0 = kose)
                val dx = (rx - cx) / cx
                val dy = (ry - cy) / cy
                val dist = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtMost(1f)

                // Merkez agirlik: ic piksel 1.0, dis piksel 0.15
                // Glow ring dis halkada, dusuk agirlikla skoru bozmaz
                val weight = if (dist <= 0.65f) 1.0f
                             else if (dist <= 0.85f) 0.4f
                             else 0.15f

                val sIdx = (sy + ty) * screenW + (sx + tx)
                val tIdx = ty * tplW + tx

                grayDiffSum += abs(screenGray[sIdx] - tplGray[tIdx]).toFloat() * weight

                val sp = screenPixels[sIdx]; val tp = tplPixels[tIdx]
                rDiffSum += abs((sp shr 16 and 0xFF) - (tp shr 16 and 0xFF)).toFloat() * weight
                gDiffSum += abs((sp shr 8  and 0xFF) - (tp shr 8  and 0xFF)).toFloat() * weight
                bDiffSum += abs((sp        and 0xFF) - (tp        and 0xFF)).toFloat() * weight
                weightSum += weight
            }
        }

        val w = weightSum.coerceAtLeast(1f)
        val grayScore = 1f - (grayDiffSum / w / 255f)
        val rScore    = 1f - (rDiffSum    / w / 255f)
        val gScore    = 1f - (gDiffSum    / w / 255f)
        val bScore    = 1f - (bDiffSum    / w / 255f)
        // R kanalina 2x agirlik - kirmizi/kahverengi ayrimi
        val colorScore = (rScore * 2f + gScore + bScore) / 4f

        return grayScore * 0.35f + colorScore * 0.65f
    }
}
