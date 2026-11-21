package com.example.tahrimtester

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val SURAH_NUMBER = 66
private const val AYAH_COUNT = 12

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TahrimApp
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize()) { TahrimScreen(app) }
                }
            }
        }
    }
}

@Composable
fun TahrimScreen(app: TahrimApp) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var startAyah by remember { mutableIntStateOf(1) }
    var listening by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf("") }
    var settled by remember { mutableStateOf("") }
    var finished by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("اختر الآية ثم اضغط زر التسجيل.") }
    var diag by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val history = remember { mutableStateListOf<String>() }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun start() {
        if (BuildConfig.QURANLAB_KEY.isBlank()) {
            status = "مفتاح QURANLAB_KEY غير موجود في هذا البناء."
            return
        }
        heard = ""; settled = ""; finished = false
        status = "جارٍ تحميل النموذج…"
        val ayah = startAyah
        job = scope.launch {
            listening = true
            try {
                val model = app.client.model()
                status = "تحدّث الآن — البداية من الآية $ayah"
                val session = model.listen()
                session.chunks.collect { chunk ->
                    heard = "${chunk.decoded.text}"
                    settled = "${chunk.decoded.settled}"
                    finished = chunk.decoded.isFinal
                }
                status = "انتهى الاستماع."
            } catch (e: CancellationException) {
                status = "تم الإيقاف."
                throw e
            } catch (e: Throwable) {
                status = "خطأ: ${e.message}"
            } finally {
                listening = false
                if (heard.isNotBlank()) history.add(0, "من الآية $ayah: $heard")
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else status = "لم يُسمح بالميكروفون. فعّله من إعدادات التطبيق."
    }

    // انتهاء الجلسة عند مغادرة التطبيق للواجهة؛ تبدأ جلسة جديدة بالضغط على الزر.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { stop() }

    // تحميل مسبق للنموذج عند أول تشغيل.
    LaunchedEffect(Unit) {
        try { app.client.prefetch() } catch (_: Throwable) { }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("سورة التحريم ($SURAH_NUMBER)", fontSize = 24.sp, style = MaterialTheme.typography.headlineSmall)
        Text(
            "اختبار نموذج TajweedFormer V4. النموذج يسمع وينقل ما سمعه فقط، وليس حكماً شرعياً على التلاوة.",
            style = MaterialTheme.typography.bodySmall
        )

        Text("ابدأ من الآية:", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items((1..AYAH_COUNT).toList()) { n ->
                FilterChip(
                    selected = n == startAyah,
                    onClick = { if (!listening) startAyah = n },
                    label = { Text("$n") }
                )
            }
        }

        Button(
            onClick = {
                if (listening) stop()
                else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) start()
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            Text(if (listening) "⏹ إيقاف" else "🎙 ابدأ التسجيل من الآية $startAyah", fontSize = 18.sp)
        }

        Text(status, style = MaterialTheme.typography.bodyMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ما سمعه النموذج", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (heard.isBlank()) "—" else heard,
                    fontSize = 22.sp,
                    lineHeight = 34.sp
                )
                if (settled.isNotBlank() || finished) {
                    Text(
                        "رموز مستقرّة: $settled" + if (finished) " · نهاية الجلسة" else "",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        if (history.isNotEmpty()) {
            Text("آخر التجارب", style = MaterialTheme.typography.titleSmall)
            history.take(10).forEach { line ->
                Card(Modifier.fillMaxWidth()) { Text(line, Modifier.padding(12.dp)) }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    diag = try { "${app.client.diagnose()}" } catch (e: Throwable) { "خطأ: ${e.message}" }
                }
            }) { Text("تشخيص") }
        }
        if (diag.isNotBlank()) Text(diag, style = MaterialTheme.typography.bodySmall)
    }
}
