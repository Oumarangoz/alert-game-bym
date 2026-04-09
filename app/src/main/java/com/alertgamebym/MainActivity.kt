package com.alertgamebym

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.alertgamebym.accessibility.TapAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.bind(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    // Loglari 500ms aralikla guncelle - her satirda recompose yaparsa donma olur
    val logs by produceState(initialValue = AppLog.lines.value) {
        AppLog.lines.collect { newLogs ->
            kotlinx.coroutines.delay(300)
            value = newLogs
        }
    }
    val bubbleOn by ControlCenter.bubbleRunning.collectAsState()
    val targetX by ControlCenter.targetX.collectAsState()
    val targetY by ControlCenter.targetY.collectAsState()
    val roiX1 by ControlCenter.itemRoiX1.collectAsState()
    val roiY1 by ControlCenter.itemRoiY1.collectAsState()
    val roiX2 by ControlCenter.itemRoiX2.collectAsState()
    val roiY2 by ControlCenter.itemRoiY2.collectAsState()
    val scope = rememberCoroutineScope()

    var hitCount by remember { mutableIntStateOf(0) }

    var itemInput by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(ItemRulesStore.getItems(context)) }
    var minConfText by remember { mutableStateOf((SettingsStore.getMinConf(context) * 100).toInt().toString()) }
    var tapOffsetXText by remember { mutableStateOf(SettingsStore.getTapOffsetX(context).toString()) }
    var tapOffsetYText by remember { mutableStateOf(SettingsStore.getTapOffsetY(context).toString()) }
    var tapAllMode by remember { mutableStateOf(SettingsStore.getTapAll(context)) }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ProjectionStore.set(result.resultCode, result.data!!)
            AppLog.add("PROJECTION: ekran izni alındı")
        } else {
            AppLog.add("PROJECTION: ekran izni reddedildi")
        }
    }

    val state1Launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            ReferenceStore.save(context, ReferenceStore.KEY_STATE1, uri)
            AppLog.add("REF1: kaydedildi")
        }
    }

    val state2Launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            ReferenceStore.save(context, ReferenceStore.KEY_STATE2, uri)
            AppLog.add("REF2: kaydedildi")
        }
    }

    val saveLogLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri == null) {
            AppLog.add("LOG: iptal")
        } else {
            scope.launch(Dispatchers.IO) {
                runCatching {
                                        val text = AppLog.snapshotText(context.applicationContext, flushTimeoutMs = 2500L)
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                        out.flush()
                    } ?: error("stream null")
                }.onSuccess { AppLog.add("LOG: kaydedildi") }
                 .onFailure { AppLog.add("LOG: hata: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Alert Game ByM", style = MaterialTheme.typography.headlineSmall)
        Text("Accessibility aktif: ${TapAccessibilityService.isAvailable()}")
        Text("Bubble durumu: ${if (bubbleOn) "ON" else "OFF"}")
        Text("Hedef işaretçisi: x=${targetX.toInt()} y=${targetY.toInt()}")
        Text("Item ROI: (${roiX1.toInt()},${roiY1.toInt()}) - (${roiX2.toInt()},${roiY2.toInt()})")
        Text("Ekran izni: ${if (ProjectionStore.isReady()) "hazır" else "yok"}")
        Text("Ref1: ${if (ReferenceStore.has(context, ReferenceStore.KEY_STATE1)) "hazır" else "yok"}")
        Text("Ref2: ${if (ReferenceStore.has(context, ReferenceStore.KEY_STATE2)) "hazır" else "yok"}")
        Text("İtem sayısı: ${items.size}")

        Button(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Erişilebilirlik Ayarlarını Aç")
        }

        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Overlay İznini Aç")
        }

        Button(
            onClick = {
                val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mgr.createScreenCaptureIntent())
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Ekran İzni Al")
        }

        Button(
            onClick = { state1Launcher.launch(arrayOf("image/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Durum 1 Görsel Seç (Kırmızı)")
        }

        OutlinedButton(
            onClick = { state2Launcher.launch(arrayOf("image/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Durum 2 Görsel Seç (Kahverengi)")
        }

        OutlinedTextField(
            value = itemInput,
            onValueChange = { itemInput = it },
            label = { Text("Yeni item ismi") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val clean = itemInput.trim()
                    if (clean.isNotBlank()) {
                        ItemRulesStore.addItem(context, clean)
                        items = ItemRulesStore.getItems(context)
                        AppLog.add("ITEM: eklendi -> '$clean'")
                        itemInput = ""
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("İtem Ekle")
            }

            OutlinedButton(
                onClick = {
                    ItemRulesStore.clearItems(context)
                    items = ItemRulesStore.getItems(context)
                    AppLog.add("ITEM: tüm liste temizlendi")
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Tümünü Sil")
            }
        }

        if (items.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(item, modifier = Modifier.weight(1f))
                            OutlinedButton(
                                onClick = {
                                    ItemRulesStore.removeItem(context, item)
                                    items = ItemRulesStore.getItems(context)
                                    AppLog.add("ITEM: silindi -> '$item'")
                                }
                            ) {
                                Text("Sil")
                            }
                        }
                    }
                }
            }
        }


        HorizontalDivider()
        Text("Gelişmiş Ayarlar", style = MaterialTheme.typography.titleMedium)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text("İkon Güven Skoru: %${minConfText}", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { val v=(minConfText.toIntOrNull()?:80)-5; minConfText=v.coerceIn(60,95).toString(); SettingsStore.saveMinConf(context,minConfText.toInt()/100f) }) { Text("-") }
            OutlinedButton(onClick = { val v=(minConfText.toIntOrNull()?:80)+5; minConfText=v.coerceIn(60,95).toString(); SettingsStore.saveMinConf(context,minConfText.toInt()/100f) }) { Text("+") }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Tap X Offset: ${tapOffsetXText}px", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { val v=(tapOffsetXText.toIntOrNull()?:0)-1; tapOffsetXText=v.coerceIn(-200,200).toString(); SettingsStore.saveTapOffsetX(context,tapOffsetXText.toInt()) }) { Text("-") }
            OutlinedButton(onClick = { val v=(tapOffsetXText.toIntOrNull()?:0)+1; tapOffsetXText=v.coerceIn(-200,200).toString(); SettingsStore.saveTapOffsetX(context,tapOffsetXText.toInt()) }) { Text("+") }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Tap Y Offset: ${tapOffsetYText}px", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { val v=(tapOffsetYText.toIntOrNull()?:0)-1; tapOffsetYText=v.coerceIn(-200,200).toString(); SettingsStore.saveTapOffsetY(context,tapOffsetYText.toInt()) }) { Text("-") }
            OutlinedButton(onClick = { val v=(tapOffsetYText.toIntOrNull()?:0)+1; tapOffsetYText=v.coerceIn(-200,200).toString(); SettingsStore.saveTapOffsetY(context,tapOffsetYText.toInt()) }) { Text("+") }
        }


        // Tikla modu: Tek item (eslesen) veya Tum ROI
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text(if (tapAllMode) "Mod: Tüm ROI yazıları" else "Mod: Eşleşen item", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                tapAllMode = !tapAllMode
                SettingsStore.saveTapAll(context, tapAllMode)
            }) { Text(if (tapAllMode) "Tek Item" else "Tüm ROI") }
        }

        // Tikla modu: Tek item (eslesen) veya Tum ROI
        

        OutlinedButton(
            onClick = {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BubbleOverlayService::class.java).apply {
                        action = BubbleOverlayService.ACTION_RESET_AUTO
                    }
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("AUTO State Sıfırla")
        }

        Button(
            onClick = {
                val i = Intent(context, BubbleOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, i)
                } else {
                    context.startService(i)
                }
                AppLog.add("UI: Bubble servisi başlatıldı")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Bubble Servisini Başlat")
        }

        OutlinedButton(
            onClick = {
                context.stopService(Intent(context, BubbleOverlayService::class.java))
                ControlCenter.setBubbleRunning(false)
                AppLog.add("UI: Bubble servisi durduruldu")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Bubble Servisini Durdur")
        }

        Button(
            onClick = {
                scope.launch {
                    AppLog.add("TEST: 3 sn sonra merkez tap denenecek")
                    delay(3000)
                    val dm = context.resources.displayMetrics
                    val x = dm.widthPixels / 2f
                    val y = dm.heightPixels / 2f

                    AppLog.add("TEST: hedef x=${x.toInt()} y=${y.toInt()}")

                    val result = withContext(Dispatchers.Main) {
                        TapAccessibilityService.performTap(x, y, 60L)
                    }

                    when {
                        result.error != null -> AppLog.add("TEST: Tap hata: ${result.error}")
                        result.completed -> AppLog.add("TEST: Tap tamamlandı")
                        result.cancelled -> AppLog.add("TEST: Tap iptal edildi")
                        else -> AppLog.add("TEST: Tap belirsiz")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("3 sn sonra merkez test tap")
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = {
                        hitCount += 1
                        AppLog.add("HEDEF: Merkez butonu tıklandı. sayaç=$hitCount")
                    },
                    modifier = Modifier.size(width = 220.dp, height = 90.dp)
                ) {
                    Text("MERKEZ TEST HEDEFİ\nSayaç: $hitCount")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Kullanım:")
                Text("- Kırmızı ve kahverengi ikon görsellerini seç")
                Text("- Bir veya daha fazla item ekle")
                Text("- Süre sınırını ayarla")
                Text("- Ekran izni al")
                Text("- Bubble servisini başlat")
                Text("- Oyuna geç")
                Text("- Mavi bubble kısa bas = AUTO başlat")
                Text("- AUTO açıkken mavi bubble kısa bas = AUTO durdur")
                Text("- Mor bubble kısa bas = OCR debug")
                Text("- Mor bubble uzun bas = tek sefer görsel tarama + tap")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                                                        AppLog.flushBlocking(2500L)
                        }.onFailure { AppLog.add("LOG: flush hata: ${it.message}") }
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            saveLogLauncher.launch("log_${System.currentTimeMillis()}.txt")
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Log TXT Kaydet")
            }

            OutlinedButton(
                onClick = { AppLog.clear(context) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Logları Temizle")
            }
        }

        HorizontalDivider()

        Text("Loglar", style = MaterialTheme.typography.titleMedium)

        logs.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
        }
    }
}
