package com.alertgamebym.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
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
        val screen = captureScreenBitmap(context)
        val templateOriginal = loadTemplateBitmap(context, templateUri) ?: return null

        val factor = min(1f, 540f / screen.width.toFloat())

        val screenScaled = if (factor < 1f) {
            Bitmap.createScaledBitmap(
                screen,
                max((screen.width * factor).toInt(), 1),
                max((screen.height * factor).toInt(), 1),
                true
            )
        } else {
            screen
        }

        val templateBase = if (factor < 1f) {
            Bitmap.createScaledBitmap(
                templateOriginal,
                max((templateOriginal.width * factor).toInt(), 12),
                max((templateOriginal.height * factor).toInt(), 12),
                true
            )
        } else {
            templateOriginal
        }

        val screenGray = toGray(screenScaled)
        val screenPixels = toPixels(screenScaled)

        val scaledCx = ((centerX ?: (screen.width / 2)) * factor).toInt()
        val scaledCy = ((centerY ?: (screen.height / 2)) * factor).toInt()
        val scaledRx = max((radiusX * factor).toInt(), 24)
        val scaledRy = max((radiusY * factor).toInt(), 24)

        var best: TemplateMatch? = null
        val scales = listOf(0.90f, 1.00f, 1.10f)

        for (scale in scales) {
            val tw = max((templateBase.width * scale).toInt(), 12)
            val th = max((templateBase.height * scale).toInt(), 12)

            if (tw >= screenScaled.width || th >= screenScaled.height) continue

            val tpl = if (scale == 1.0f) templateBase
            else Bitmap.createScaledBitmap(templateBase, tw, th, true)

            val tplGray = toGray(tpl)
            val tplPixels = toPixels(tpl)

            val xStart: Int
            val yStart: Int
            val xEnd: Int
            val yEnd: Int

            if (fullScreen) {
                xStart = 0
                yStart = 0
                xEnd = screenScaled.width - tw
                yEnd = screenScaled.height - th
            } else {
                xStart = max(0, scaledCx - scaledRx - tw / 2)
                yStart = max(0, scaledCy - scaledRy - th / 2)
                xEnd = min(screenScaled.width - tw, scaledCx + scaledRx - tw / 2)
                yEnd = min(screenScaled.height - th, scaledCy + scaledRy - th / 2)
            }

            if (xEnd >= xStart && yEnd >= yStart) {
                val step = 3
                var y = yStart
                while (y <= yEnd) {
                    var x = xStart
                    while (x <= xEnd) {
                        val similarity = scoreAt(
                            screenGray = screenGray,
                            screenPixels = screenPixels,
                            screenW = screenScaled.width,
                            sx = x,
                            sy = y,
                            tplGray = tplGray,
                            tplPixels = tplPixels,
                            tplW = tw,
                            tplH = th
                        )

                        val finalScore = if (fullScreen) {
                            similarity
                        } else {
                            val cx = x + tw / 2
                            val cy = y + th / 2
                            val dist = hypot((cx - scaledCx).toFloat(), (cy - scaledCy).toFloat())
                            val maxDist = hypot(scaledRx.toFloat(), scaledRy.toFloat()).coerceAtLeast(1f)
                            similarity - ((dist / maxDist) * 0.16f)
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

        if (screenScaled !== screen) screenScaled.recycle()
        screen.recycle()
        if (templateBase !== templateOriginal) templateBase.recycle()
        templateOriginal.recycle()

        return best
    }

    private suspend fun captureScreenBitmap(context: Context): Bitmap {
        val projection = ProjectionStore.getProjection(context)

        val dm = context.resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val density = dm.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val vd = projection.createVirtualDisplay(
            "img-template-scan",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )

        try {
            repeat(12) {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    return imageToBitmap(image, width, height)
                }
                delay(120)
            }
            throw IllegalStateException("Ekran görüntüsü alınamadı")
        } finally {
            runCatching { reader.close() }
            runCatching { vd.release() }
        }
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        image.use { img ->
            val plane = img.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            return Bitmap.createBitmap(bitmap, 0, 0, width, height)
        }
    }

    private fun loadTemplateBitmap(context: Context, uri: Uri): Bitmap? {
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        }
    }

    private fun toGray(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = (r * 30 + g * 59 + b * 11) / 100
        }
        return out
    }

    private fun toPixels(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val out = IntArray(w * h)
        bitmap.getPixels(out, 0, w, 0, 0, w, h)
        return out
    }

    private fun scoreAt(
        screenGray: IntArray,
        screenPixels: IntArray,
        screenW: Int,
        sx: Int,
        sy: Int,
        tplGray: IntArray,
        tplPixels: IntArray,
        tplW: Int,
        tplH: Int
    ): Float {
        val sampleCols = 14
        val sampleRows = 14
        var grayDiffSum = 0f
        var colorDiffSum = 0f
        var count = 0

        for (ry in 0 until sampleRows) {
            val ty = if (sampleRows == 1) 0 else (ry * (tplH - 1)) / (sampleRows - 1)
            for (rx in 0 until sampleCols) {
                val tx = if (sampleCols == 1) 0 else (rx * (tplW - 1)) / (sampleCols - 1)

                val sIndex = (sy + ty) * screenW + (sx + tx)
                val tIndex = ty * tplW + tx

                val sg = screenGray[sIndex]
                val tg = tplGray[tIndex]
                grayDiffSum += abs(sg - tg).toFloat()

                val sp = screenPixels[sIndex]
                val tp = tplPixels[tIndex]

                val sr = (sp shr 16) and 0xFF
                val sgc = (sp shr 8) and 0xFF
                val sb = sp and 0xFF

                val tr = (tp shr 16) and 0xFF
                val tgc = (tp shr 8) and 0xFF
                val tb = tp and 0xFF

                colorDiffSum += (
                    abs(sr - tr) * 2 +
                    abs(sgc - tgc) +
                    abs(sb - tb)
                ).toFloat()

                count++
            }
        }

        val grayAvg = grayDiffSum / count
        val colorAvg = colorDiffSum / count

        val grayScore = 1f - (grayAvg / 255f)
        val colorScore = 1f - (colorAvg / (255f * 3f))

        return grayScore * 0.40f + colorScore * 0.60f
    }
}
