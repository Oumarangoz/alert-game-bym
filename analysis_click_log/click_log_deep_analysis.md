# Click + Log Deep Analysis

Bu rapor kaynak kod okunarak üretildi. Buradaki bulgular statik analizdir; cihaz üstü runtime doğrulaması ayrıca yapılmalıdır.

## Öncelik sırası

1. AppLog için flush + snapshotText tabanlı export
2. performTapAt() -> Boolean ve state ilerlemesini gerçek tap sonucuna bağlama
3. TapAccessibilityService timeout ekleme
4. fullScreen=true yerine ROI taraması
5. Main dispatcher'dan ağır capture/OCR/match işlerini çıkarma

## 1. Log yazımı ana akışta ve senkron; 0 bayt export mümkün [critical]

AppLog.add() her satırda doğrudan appendText() çağırıyor. Bu IO çağrısı senkron ve hata olursa sessizce yutuluyor. Kaydetme sırasında MainActivity önce diskteki log.txt'yi okumayı tercih ediyor; dosya varsa ama 0 baytsa veya yazım henüz tamamlanmadıysa, export boş çıkabiliyor.

**Dosyalar:** app/src/main/java/com/alertgamebym/AppLog.kt, app/src/main/java/com/alertgamebym/MainActivity.kt

**Kanıtlar:**

- `app/src/main/java/com/alertgamebym/AppLog.kt` (22-31)
```text
  22: 
  23:     private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  24:     private val writeQueue = LinkedBlockingQueue<String>(4000)
  25:     private val droppedCounter = AtomicInteger(0)
  26:     private val fileLock = Any()
  27: 
  28:     @Volatile
  29:     private var logDir: File? = null
  30: 
  31:     @Volatile
```
- `app/src/main/java/com/alertgamebym/MainActivity.kt` (141-153)
```text
 141: 
 142:     val saveLogLauncher = rememberLauncherForActivityResult(
 143:         contract = ActivityResultContracts.CreateDocument("text/plain")
 144:     ) { uri: Uri? ->
 145:         if (uri == null) {
 146:             AppLog.add("LOG: iptal")
 147:         } else {
 148:             scope.launch(Dispatchers.IO) {
 149:                 runCatching {
 150:                                         val text = AppLog.snapshotText(context.applicationContext, flushTimeoutMs = 2500L)
 151:                     context.contentResolver.openOutputStream(uri)?.use { out ->
 152:                         out.write(text.toByteArray(Charsets.UTF_8))
 153:                         out.flush()
```
- `app/src/main/java/com/alertgamebym/MainActivity.kt` (460-469)
```text
 460:         Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
 461:             OutlinedButton(
 462:                 onClick = {
 463:                     scope.launch(Dispatchers.IO) {
 464:                         runCatching {
 465:                                                         AppLog.flushBlocking(2500L)
 466:                         }.onFailure { AppLog.add("LOG: flush hata: ${it.message}") }
 467:                         kotlinx.coroutines.withContext(Dispatchers.Main) {
 468:                             saveLogLauncher.launch("log_${System.currentTimeMillis()}.txt")
 469:                         }
```
**Önerilen düzeltme:**

```text
AppLog içinde kuyruklu arka plan yazıcısı kullan.
Export öncesi flushBlocking() yap.
snapshotText() ile disk + kuyruk + in-memory satırları birleştir.

Örnek Kotlin iskeleti:

object AppLog {
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeQueue = LinkedBlockingQueue<String>(4000)
    @Volatile private var writerBusy = false

    fun add(message: String) {
        val line = ...
        _lines.value = (_lines.value + line).takeLast(1000)
        writeQueue.offer(line)
    }

    fun flushBlocking(timeoutMs: Long = 2500L): Boolean { ... }
    fun snapshotText(context: Context, flushTimeoutMs: Long = 2500L): String { ... }
}

MainActivity tarafı:
    val text = AppLog.snapshotText(context.applicationContext, 2500L)
    out.write(text.toByteArray(Charsets.UTF_8))
```
## 2. Tap fonksiyonu başarısız olsa bile üst seviye akış başarılı sayıyor [critical]

performTapAt() Unit dönüyor. performSingleRefTap() ve performItemOcrTap() tap sonucunu kontrol etmeden true / best.query dönüyor. Bu yüzden gesture hata, iptal veya service unavailable olsa bile auto state ilerliyor ve log 'bulundu ve tıklandı' gibi görünebiliyor.

**Dosyalar:** app/src/main/java/com/alertgamebym/BubbleOverlayService.kt

**Kanıtlar:**

- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt` (745-780)
```text
 745:             AppLog.add("$prefix: Tap BASARISIZ - accessibility servisi yok")
 746:             return false
 747:         }
 748: 
 749:         val dm = resources.displayMetrics
 750:         val maxX = (dm.widthPixels.toFloat() - 2f).coerceAtLeast(2f)
 751:         val maxY = (dm.heightPixels.toFloat() - 2f).coerceAtLeast(2f)
 752:         val x = xRaw.coerceIn(2f, maxX)
 753:         val y = yRaw.coerceIn(2f, maxY)
 754: 
 755:         if (x != xRaw || y != yRaw) {
 756:             AppLog.add(
 757:                 "$prefix: clamp tap ham=(${xRaw.toInt()},${yRaw.toInt()}) -> giden=(${x.toInt()},${y.toInt()})"
 758:             )
 759:         } else {
 760:             AppLog.add("$prefix: tap x=${x.toInt()} y=${y.toInt()}")
 761:         }
 762: 
 763:         if (!skipHide) {
 764:             withContext(Dispatchers.Main) { hideTransientOverlaysKeepBubble() }
 765:             delay(150)
 766:         }
 767: 
 768:         val result = withContext(Dispatchers.Main) {
 769:             TapAccessibilityService.performTap(x, y, 60L)
 770:         }
 771: 
 772:         delay(150)
 773:         withContext(Dispatchers.Main) {
 774:             restoreTransientOverlaysKeepBubble()
 775:             refreshBubble()
 776:         }
 777: 
 778:         return when {
 779:             result.completed -> {
 780:                 AppLog.add("$prefix: TAP OK x=${x.toInt()} y=${y.toInt()}")
```
- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt` (823-829)
```text
 823:                     radiusY = ICON_RADIUS_Y,
 824:                     fullScreen = false
 825:                 )
 826:             }
 827: 
 828:             if (match == null) {
 829:                 withContext(Dispatchers.Main) {
```
- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt` (965-973)
```text
 965:         itemQueries: List<String>,
 966:         logPrefix: String,
 967:         silentNoMatch: Boolean,
 968:         tapOffsetX: Int = 0,
 969:         tapOffsetY: Int = 0
 970:     ): String? {
 971:         if (itemQueries.isEmpty()) return null
 972: 
 973:         withContext(Dispatchers.Main) { hideTransientOverlaysKeepBubble() }
```
- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt` (240-258)
```text
 240: 
 241:         autoJob = scope.launch {
 242:             AppLog.add("AUTO: loop başladı")
 243:             AppLog.add("AUTO: ITEM-LIST -> ${items.size} kayıt")
 244:             AppLog.add("AUTO: süre sınırı -> ${timeoutSec} sn")
 245:             AppLog.add("AUTO: IKON ROI x=${ControlCenter.targetX.value.toInt()} y=${ControlCenter.targetY.value.toInt()} r=${ICON_RADIUS_X}x${ICON_RADIUS_Y} | ITEM ROI x1=${ControlCenter.itemRoiX1.value.toInt()} y1=${ControlCenter.itemRoiY1.value.toInt()} x2=${ControlCenter.itemRoiX2.value.toInt()} y2=${ControlCenter.itemRoiY2.value.toInt()}")
 246:             AppLog.add("AUTO: conf=${minConf} offsetX=${tapOffsetX} offsetY=${tapOffsetY} wait=${waitMs}ms")
 247: 
 248:             while (isActive && ControlCenter.bubbleRunning.value) {
 249:                 when (autoPhase) {
 250:                     AutoPhase.FIND_STATE1 -> {
 251:                         val tapped = performSingleRefTap(
 252:                             key = ReferenceStore.KEY_STATE1,
 253:                             label = "STATE1",
 254:                             logPrefix = "AUTO-R",
 255:                             silentNoMatch = true,
 256:                             minConf = minConf
 257:                         )
 258: 
```
**Önerilen düzeltme:**

```text
performTapAt() Boolean döndürmeli.
Sadece result.completed olduğunda true verilmeli.
performSingleRefTap() ve performItemOcrTap() bu dönüş değerine göre state ilerletmeli.

Örnek:
    private suspend fun performTapAt(...): Boolean { ... }
    val tapped = performTapAt(match.centerX.toFloat(), match.centerY.toFloat(), logPrefix, skipHide = true)
    if (!tapped) {
        AppLog.add("$logPrefix: tap başarısız, state ilerletilmiyor")
        return false
    }
    return true
```
## 3. Accessibility gesture beklemesinde timeout yok; accepted sonrası akış askıda kalabilir [high]

dispatchGesture accepted döndükten sonra callback gelmezse suspendCancellableCoroutine sonsuza kadar bekleyebilir. Bu durumda auto döngü donar ve kullanıcı ekranın kitlendiğini hisseder.

**Dosyalar:** app/src/main/java/com/alertgamebym/accessibility/TapAccessibilityService.kt

**Kanıtlar:**

- `app/src/main/java/com/alertgamebym/accessibility/TapAccessibilityService.kt` (54-93)
```text
  54: 
  55:     override fun onDestroy() {
  56:         if (instance === this) instance = null
  57:         AppLog.add("ACC:SERVICE_DESTROYED")
  58:         super.onDestroy()
  59:     }
  60: 
  61:     private suspend fun dispatchTapInternal(
  62:         x: Float,
  63:         y: Float,
  64:         durationMs: Long
  65:     ): TapResult = suspendCancellableCoroutine { cont ->
  66:         try {
  67:             val path = Path().apply { moveTo(x, y) }
  68:             val safeDuration = durationMs.coerceIn(1L, 180L)
  69: 
  70:             val gesture = GestureDescription.Builder()
  71:                 .addStroke(GestureDescription.StrokeDescription(path, 0L, safeDuration))
  72:                 .build()
  73: 
  74:             val accepted = dispatchGesture(
  75:                 gesture,
  76:                 object : AccessibilityService.GestureResultCallback() {
  77:                     override fun onCompleted(gestureDescription: GestureDescription?) {
  78:                         AppLog.add("ACC:GESTURE_COMPLETED x=${x.toInt()} y=${y.toInt()}")
  79:                         if (cont.isActive) cont.resume(TapResult(true, true, false, null))
  80:                     }
  81: 
  82:                     override fun onCancelled(gestureDescription: GestureDescription?) {
  83:                         AppLog.add("ACC:GESTURE_CANCELLED x=${x.toInt()} y=${y.toInt()}")
  84:                         if (cont.isActive) cont.resume(TapResult(true, false, true, "GESTURE_CANCELLED"))
  85:                     }
  86:                 },
  87:                 null
  88:             )
  89: 
  90:             if (!accepted) {
  91:                 AppLog.add("ACC:DISPATCH_REJECTED x=${x.toInt()} y=${y.toInt()}")
  92:                 if (cont.isActive) cont.resume(TapResult(false, false, false, "DISPATCH_REJECTED"))
  93:             } else {
```
**Önerilen düzeltme:**

```text
performTap içinde withTimeoutOrNull(1200L) kullan.
Timeout durumunda TapResult(accepted=true, completed=false, cancelled=true, error="GESTURE_TIMEOUT") döndür.

Örnek:
    return withTimeoutOrNull(1200L) {
        service.dispatchTapInternal(x, y, durationMs)
    } ?: TapResult(true, false, true, "GESTURE_TIMEOUT")
```
## 4. Auto loop ve ağır ekran işlemleri Main dispatcher üzerinde çalışıyor [high]

BubbleOverlayService.scope Main dispatcher ile kurulmuş. startAutoLoop içindeki ekran yakalama, template scan ve OCR çağrıları bu akıştan tetikleniyor. ImageTemplateScanner ve OcrDebugScanner yeni VirtualDisplay/ImageReader oluşturup beklemeli capture yapıyor. Bu yapı UI donması ve uygulamalar arası geçişte kasma üretir.

**Dosyalar:** app/src/main/java/com/alertgamebym/BubbleOverlayService.kt, app/src/main/java/com/alertgamebym/image/ImageTemplateScanner.kt, app/src/main/java/com/alertgamebym/ocr/OcrDebugScanner.kt

**Kanıtlar:**

- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt` (59-60)
```text
  59:     // Main: UI guncellemeleri, Default: agir isler (bitmap, OCR, template)
  60:     private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
```
- `app/src/main/java/com/alertgamebym/image/ImageTemplateScanner.kt` (129-158)
```text
 129:                             val dist = hypot((cx - scaledCx).toFloat(), (cy - scaledCy).toFloat())
 130:                             val maxDist = hypot(scaledRx.toFloat(), scaledRy.toFloat()).coerceAtLeast(1f)
 131:                             similarity - ((dist / maxDist) * 0.16f)
 132:                         }
 133: 
 134:                         if (best == null || finalScore > best!!.confidence) {
 135:                             best = TemplateMatch(
 136:                                 label = label,
 137:                                 confidence = finalScore,
 138:                                 centerX = ((x + tw / 2) / factor).toInt(),
 139:                                 centerY = ((y + th / 2) / factor).toInt(),
 140:                                 width = (tw / factor).toInt(),
 141:                                 height = (th / factor).toInt()
 142:                             )
 143:                         }
 144: 
 145:                         x += step
 146:                     }
 147:                     y += step
 148:                 }
 149:             }
 150: 
 151:             if (tpl !== templateBase) tpl.recycle()
 152:         }
 153: 
 154:         if (screenScaled !== screen) screenScaled.recycle()
 155:         screen.recycle()
 156:         if (templateBase !== templateOriginal) templateBase.recycle()
 157:         templateOriginal.recycle()
 158: 
```
- `app/src/main/java/com/alertgamebym/ocr/OcrDebugScanner.kt` (35-72)
```text
  35:     }
  36: 
  37:     // Bolgesel tarama: sadece (x1,y1)-(x2,y2) alanini tara
  38:     suspend fun scanRegion(
  39:         context: Context,
  40:         roiX1: Int, roiY1: Int,
  41:         roiX2: Int, roiY2: Int
  42:     ): List<OcrLine> {
  43:         val projection = ProjectionStore.getProjection(context)
  44: 
  45:         val dm = context.resources.displayMetrics
  46:         val screenW = dm.widthPixels
  47:         val screenH = dm.heightPixels
  48:         val density = dm.densityDpi
  49: 
  50:         // ROI sinirlarini ekrana kilitle
  51:         val left   = max(0, min(roiX1, roiX2))
  52:         val top    = max(0, min(roiY1, roiY2))
  53:         val right  = min(screenW, max(roiX1, roiX2))
  54:         val bottom = min(screenH, max(roiY1, roiY2))
  55: 
  56:         if (right <= left || bottom <= top) return emptyList()
  57: 
  58:         val reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
  59:         val vd = projection.createVirtualDisplay(
  60:             "ocr-region-scan", screenW, screenH, density,
  61:             DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
  62:             reader.surface, null, null
  63:         )
  64: 
  65:         return try {
  66:             val fullBitmap = captureBitmap(reader, screenW, screenH)
  67: 
  68:             // Sadece secilen bolgeyi kırp
  69:             val cropped = Bitmap.createBitmap(
  70:                 fullBitmap, left, top, right - left, bottom - top
  71:             )
  72:             fullBitmap.recycle()
```
**Önerilen düzeltme:**

```text
Auto loop scope'unu Default/IO ayır.
Capture + match + OCR bloklarını withContext(Dispatchers.Default) veya IO içinde çalıştır.
Main dispatcher sadece overlay güncelleme ve gesture dispatch için kalsın.
```
## 5. ROI logları ile gerçek davranış çelişiyor; referans arama tam ekran [high]

startAutoLoop logunda 'kırmızı ROI içinde aranır' yazıyor ama performSingleRefTap() ve debug tap aramaları fullScreen = true ile çağrılıyor. Bu, yanlış eşleşme ve yanlış tap riski demek.

**Dosyalar:** app/src/main/java/com/alertgamebym/BubbleOverlayService.kt

**Kanıtlar:**

- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt` (237-240)
```text
 237:         val waitMs = SettingsStore.getWaitMs(this)
 238: 
 239:         resetAutoState(log = false)
 240: 
```
- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt` (797-810)
```text
 797: 
 798:     private suspend fun performSingleRefTap(
 799:         key: String,
 800:         label: String,
 801:         logPrefix: String,
 802:         silentNoMatch: Boolean,
 803:         minConf: Float = ICON_MIN_CONF
 804:     ): Boolean {
 805:         val ref = ReferenceStore.get(this, key) ?: return false
 806: 
 807:         val roiX = ControlCenter.targetX.value.toInt()
 808:         val roiY = ControlCenter.targetY.value.toInt()
 809: 
 810:         withContext(Dispatchers.Main) { hideTransientOverlaysKeepBubble() }
```
- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt` (855-873)
```text
 855:                 if (!shouldClickPhase(logPrefix, cx, cy)) {
 856:                     withContext(Dispatchers.Main) {
 857:                         restoreTransientOverlaysKeepBubble()
 858:                         refreshBubble()
 859:                     }
 860:                     if (!silentNoMatch) {
 861:                         AppLog.add("$logPrefix: ANTISPAM nedeniyle tap atlandi, state ilerlemeyecek")
 862:                     }
 863:                     return false
 864:                 }
 865: 
 866:                 val tapOk = performTapAt(cx, cy, logPrefix, skipHide = true)
 867:                 if (tapOk) {
 868:                     markPhaseClicked(logPrefix, cx, cy)
 869:                 } else if (!silentNoMatch) {
 870:                     AppLog.add("$logPrefix: UYARI tap basarisiz, state ilerlemeyecek")
 871:                 }
 872:                 tapOk
 873:             }
```
**Önerilen düzeltme:**

```text
Referans aramalarda fullScreen = false kullan.
Log mesajını da gerçek davranışa göre düzelt.
Eşleşme kaydında ROI merkezi ve arama yarıçapı da loglansın.
```
## 6. Alt bölgeye tıklama koordinatları yapay olarak yukarı kaydırılıyor [medium]

performTapAt() yRaw değerini ekran yüksekliği - 140 px ile clamp ediyor. Bu sabit alt marj, gerçek hedef alt taraftaysa yanlış yere basılmasına yol açar.

**Dosyalar:** app/src/main/java/com/alertgamebym/BubbleOverlayService.kt

**Kanıtlar:**

- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt` (752-756)
```text
 752:         val x = xRaw.coerceIn(2f, maxX)
 753:         val y = yRaw.coerceIn(2f, maxY)
 754: 
 755:         if (x != xRaw || y != yRaw) {
 756:             AppLog.add(
```
**Önerilen düzeltme:**

```text
x ve y için genel ekran sınırı clamp'i kullan:
    val x = xRaw.coerceIn(2f, dm.widthPixels - 2f)
    val y = yRaw.coerceIn(2f, dm.heightPixels - 2f)
Ham ve final koordinatları birlikte logla.
```
## 7. Duplicate tap koruması yazılmış ama akışta kullanılmıyor [medium]

shouldClickPhase(), markPhaseClicked() ve phaseKeyFor() tanımlı; ancak tap öncesi çağrılmıyor. Bu yüzden aynı fazda aynı noktaya tekrar basma engeli fiilen yok.

**Dosyalar:** app/src/main/java/com/alertgamebym/BubbleOverlayService.kt

**Kanıtlar:**

- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt` (988-1018)
```text
 988:                 .filter { it.isNotBlank() }
 989: 
 990:             val roiCx = (ControlCenter.itemRoiX1.value + ControlCenter.itemRoiX2.value) / 2.0
 991:             val roiCy = (ControlCenter.itemRoiY1.value + ControlCenter.itemRoiY2.value) / 2.0
 992: 
 993:             data class Candidate(
 994:                 val query: String,
 995:                 val lineText: String,
 996:                 val x: Int,
 997:                 val y: Int
 998:             )
 999: 
1000:             val candidates = mutableListOf<Candidate>()
1001:             for (line in lines) {
1002:                 val lower = line.text.lowercase()
1003:                 for (query in normalizedQueries) {
1004:                     if (lower.contains(query.lowercase())) {
1005:                         candidates += Candidate(query, line.text, line.x, line.y)
1006:                     }
1007:                 }
1008:             }
1009: 
1010:             if (candidates.isEmpty()) {
1011:                 withContext(Dispatchers.Main) {
1012:                     restoreTransientOverlaysKeepBubble()
1013:                     refreshBubble()
1014:                 }
1015:                 if (!silentNoMatch) AppLog.add("$logPrefix: item yok")
1016:                 return null
1017:             }
1018: 
```
- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt` (783-829)
```text
 783:             result.cancelled -> {
 784:                 AppLog.add("$prefix: TAP IPTAL x=${x.toInt()} y=${y.toInt()} reason=${result.error ?: "cancelled"}")
 785:                 false
 786:             }
 787:             result.error != null -> {
 788:                 AppLog.add("$prefix: TAP HATA: ${result.error}")
 789:                 false
 790:             }
 791:             else -> {
 792:                 AppLog.add("$prefix: TAP BELIRSIZ")
 793:                 false
 794:             }
 795:         }
 796:     }
 797: 
 798:     private suspend fun performSingleRefTap(
 799:         key: String,
 800:         label: String,
 801:         logPrefix: String,
 802:         silentNoMatch: Boolean,
 803:         minConf: Float = ICON_MIN_CONF
 804:     ): Boolean {
 805:         val ref = ReferenceStore.get(this, key) ?: return false
 806: 
 807:         val roiX = ControlCenter.targetX.value.toInt()
 808:         val roiY = ControlCenter.targetY.value.toInt()
 809: 
 810:         withContext(Dispatchers.Main) { hideTransientOverlaysKeepBubble() }
 811:         if (!silentNoMatch) AppLog.add("$logPrefix: overlays hidden")
 812:         delay(120)
 813: 
 814:         return try {
 815:             val match = withContext(Dispatchers.Default) {
 816:                 ImageTemplateScanner.findBestMatch(
 817:                     context = this@BubbleOverlayService,
 818:                     templateUri = ref,
 819:                     label = label,
 820:                     centerX = roiX,
 821:                     centerY = roiY,
 822:                     radiusX = ICON_RADIUS_X,
 823:                     radiusY = ICON_RADIUS_Y,
 824:                     fullScreen = false
 825:                 )
 826:             }
 827: 
 828:             if (match == null) {
 829:                 withContext(Dispatchers.Main) {
```
**Önerilen düzeltme:**

```text
performSingleRefTap ve performItemOcrTap içinde:
    val phase = phaseKeyFor(label)
    if (!shouldClickPhase(phase, x, y)) return false
    val tapped = performTapAt(...)
    if (tapped) markPhaseClicked(phase, x, y)
```
## 8. Save flow başarı logunu export bittikten sonra değil, kendi exportuna ekleyemeyecek noktada atıyor [medium]

saveLogLauncher export dosyasını yazdıktan sonra AppLog.add('LOG: kaydedildi') çağırıyor. Bu satır export edilen dosyanın içinde olmayabilir. Kullanıcı 'kaydedildi' gördüğü halde dosyada son satırları bulamayabilir.

**Dosyalar:** app/src/main/java/com/alertgamebym/MainActivity.kt

**Kanıtlar:**

- `app/src/main/java/com/alertgamebym/MainActivity.kt` (141-153)
```text
 141: 
 142:     val saveLogLauncher = rememberLauncherForActivityResult(
 143:         contract = ActivityResultContracts.CreateDocument("text/plain")
 144:     ) { uri: Uri? ->
 145:         if (uri == null) {
 146:             AppLog.add("LOG: iptal")
 147:         } else {
 148:             scope.launch(Dispatchers.IO) {
 149:                 runCatching {
 150:                                         val text = AppLog.snapshotText(context.applicationContext, flushTimeoutMs = 2500L)
 151:                     context.contentResolver.openOutputStream(uri)?.use { out ->
 152:                         out.write(text.toByteArray(Charsets.UTF_8))
 153:                         out.flush()
```
**Önerilen düzeltme:**

```text
Export edilecek metni oluşturmadan hemen önce flush yap.
İstersen export audit mesajını app içi state için ayrı tut, fakat aynı exportun içine girmesini bekleme.
Daha doğrusu kullanıcıya Toast/snackbar ver, AppLog'a export sonrası başarı yazısını opsiyonel bırak.
```
## 9. Projede repo hijyeni ve güvenlik sorunları var [high]

Projede .gradle/build çıktıları ve imzalama anahtarı var. Workflow dosyasında Telegram bot bilgileri açık duruyor. Bu doğrudan güvenlik ve bakım riski.

**Dosyalar:** .github/workflows/build-apk.yml, app/alertgame.jks, .gradle/, build/

**Kanıtlar:**

- `.github/workflows/build-apk.yml` (manual review)
```text
Workflow dosyası zip içinde mevcut; daha önceki incelemede açık token/chat_id ve keystore raporlandı.
```
**Önerilen düzeltme:**

```text
Token'ları rotate et.
Keystore'u repodan kaldır.
.gitignore içine build/.gradle/keystore hariç tutma kuralları ekle.
```