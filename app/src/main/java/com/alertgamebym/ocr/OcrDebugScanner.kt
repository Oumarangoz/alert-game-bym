package com.alertgamebym.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import com.alertgamebym.ProjectionStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

data class OcrLine(
    val text: String,
    val x: Int,
    val y: Int
)

object OcrDebugScanner {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // VirtualDisplay cache - her taramada yeniden olusturma
    @Volatile private var cachedReader: ImageReader? = null
    @Volatile private var cachedVd: VirtualDisplay? = null
    @Volatile private var cachedW = 0
    @Volatile private var cachedH = 0

    // Servis kapaninca cagir
    fun invalidateCache() {
        runCatching { cachedVd?.release() }
        runCatching { cachedReader?.close() }
        cachedVd = null
        cachedReader = null
        cachedW = 0
        cachedH = 0
    }

    suspend fun scanRegion(
        context: Context,
        roiX1: Int, roiY1: Int,
        roiX2: Int, roiY2: Int
    ): List<OcrLine> {
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val density = dm.densityDpi

        val left   = max(0, min(roiX1, roiX2))
        val top    = max(0, min(roiY1, roiY2))
        val right  = min(screenW, max(roiX1, roiX2))
        val bottom = min(screenH, max(roiY1, roiY2))
        if (right <= left || bottom <= top) return emptyList()

        val projection = ProjectionStore.getProjection(context)

        // Cache kontrol - ayni boyutta ise yeniden kullan
        var reader = cachedReader
        var vd = cachedVd
        if (reader == null || vd == null || cachedW != screenW || cachedH != screenH) {
            runCatching { vd?.release() }
            runCatching { reader?.close() }
            reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
            vd = projection.createVirtualDisplay(
                "ocr-scan", screenW, screenH, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
            cachedReader = reader
            cachedVd = vd
            cachedW = screenW
            cachedH = screenH
        }

        var fullBitmap: Bitmap? = null
        var cropped: Bitmap? = null
        var enhanced: Bitmap? = null
        return try {
            fullBitmap = captureBitmap(reader!!, screenW, screenH)
            cropped = Bitmap.createBitmap(fullBitmap, left, top, right - left, bottom - top)
            fullBitmap.recycle(); fullBitmap = null

            enhanced = toGrayscaleEnhanced(cropped)
            cropped.recycle(); cropped = null

            val result = runRecognition(enhanced)
            enhanced.recycle(); enhanced = null

            result.textBlocks
                .flatMap { it.lines }
                .mapNotNull { line ->
                    val bb = line.boundingBox ?: return@mapNotNull null
                    OcrLine(
                        text = line.text,
                        x = ((bb.left + bb.right) / 2) + left,
                        y = ((bb.top + bb.bottom) / 2) + top
                    )
                }
                .sortedWith(compareBy<OcrLine> { it.y }.thenBy { it.x })
        } catch (e: Exception) {
            // Hata olursa cache temizle
            invalidateCache()
            emptyList()
        } finally {
            // Exception olsa bile bitmap recycle - memory leak onle
            runCatching { fullBitmap?.recycle() }
            runCatching { cropped?.recycle() }
            runCatching { enhanced?.recycle() }
        }
    }

    // Gri ton + kontrast artisi - oyun icindeki renk kaosunu azaltir
    private fun toGrayscaleEnhanced(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint()
        val cm = ColorMatrix()
        // Once gri ton
        cm.setSaturation(0f)
        // Sonra kontrast artir (1.5x kontrast)
        val contrast = 1.5f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val contrastMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
        cm.postConcat(contrastMatrix)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private suspend fun captureBitmap(reader: ImageReader, width: Int, height: Int): Bitmap {
        repeat(15) {
            val image = reader.acquireLatestImage()
            if (image != null) return imageToBitmap(image, width, height)
            delay(60)
        }
        throw IllegalStateException("Ekran goruntüsü alinamadi")
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

    private suspend fun runRecognition(bitmap: Bitmap): Text =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
}
