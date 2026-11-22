package com.example.tahrimtester

import ai.quranlab.tajweedformer.TajweedSession
import ai.quranlab.tajweedformer.TajweedSessionOptions
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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

/** كل رمز صوتي منفصل وبترتيب الزمن من اليسار لليمين. */
private fun soundsLtr(s: String): String =
    s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("\u200E \u200E")

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
    var heardNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var expectedCount by remember { mutableIntStateOf(0) }
    var cells by remember { mutableStateOf<List<Cell>>(emptyList()) }
    var settledN by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("اختر الآية ثم اضغط زر التسجيل.") }
    var diag by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var current by remember { mutableStateOf<TajweedSession?>(null) }
    val history = remember { mutableStateListOf<String>() }

    fun stop() {
        scope.launch {
            try { current?.finish() } catch (_: Throwable) { job?.cancel() }
        }
    }

    fun start() {
        if (BuildConfig.QURANLAB_KEY.isBlank()) {
            status = "مفتاح QURANLAB_KEY غير موجود في هذا البناء."
            return
        }
        heardNames = emptyList(); cells = emptyList(); settledN = 0; finished = false
        status = "جارٍ تحميل النموذج…"
        val ayah = startAyah
        job = scope.launch {
            listening = true
            try {
                val model = app.client.model()
                val tok = model.tokens
                val barName = "${tok.name(tok.wordBar)}"

                // المتوقع: وحدات الآيات من آية البداية حتى نهاية السورة (من مرجع Quran Lab).
                val ref = model.reference
                val expected = ArrayList<String>()
                if (ref != null) {
                    try {
                        for (a in ayah..AYAH_COUNT) {
                            ref.units(SURAH_NUMBER, a).toList().forEach { id ->
                                val nm = "${tok.name(id)}"
                                if (nm != barName) expected.add(nm)
                            }
                        }
                    } catch (e: Throwable) {
                        status = "تعذّر قراءة المرجع: ${e.message}"
                    }
                }
                expectedCount = expected.size

                status = "تحدّث الآن — البداية من الآية $ayah"
                val session = model.listen(TajweedSessionOptions(endOnSilenceMs = 3000))
                current = session
                session.chunks.collect { chunk ->
                    val toks = chunk.decoded.tokens
                    val st = chunk.decoded.settled.toInt()
                    val settledCount = toks.take(st).count { "${it.name}" != barName }
                    val names = toks.map { "${it.name}" }.filter { it != barName }
                    heardNames = names
                    settledN = settledCount
                    cells = if (expected.isEmpty()) emptyList() else align(expected, names)
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
                current = null
                if (heardNames.isNotEmpty()) {
                    history.add(0, "من الآية $ayah|${soundsLtr(heardNames.joinToString(" "))}")
                }
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else status = "لم يُسمح بالميكروفون. فعّله من إعدادات التطبيق."
    }

    // جلسة الاستماع تنتهي عند مغادرة التطبيق للواجهة؛ تبدأ جلسة جديدة بالزر.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { job?.cancel() }

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
            "اختبار نموذج TajweedFormer V4. النموذج يسمع وينقل ما سمعه فقط. " +
                "المقارنة أدناه فروق محتملة بين المسموع والمتوقع، وقد تخطئ، وليست حكماً شرعياً ولا تغني عن معلم مؤهل.",
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
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("المقارنة (أعلى: المتوقع · أسفل: المسموع)", style = MaterialTheme.typography.titleSmall)
                if (cells.isEmpty()) {
                    Text(
                        if (expectedCount == 0 && heardNames.isNotEmpty()) "المرجع غير متوفر في هذا النموذج."
                        else "—"
                    )
                } else {
                    val withExp = cells.count { it.exp != null }
                    val ok = cells.count { it.kind == Kind.MATCH }
                    Text(
                        "تطابق $ok من $withExp وحدة صوتية متوقعة حتى الآن · " +
                            "مختلف: ${cells.count { it.kind == Kind.SUB }} · " +
                            "ناقص: ${cells.count { it.kind == Kind.DEL }} · " +
                            "زائد: ${cells.count { it.kind == Kind.INS }}",
                        style = MaterialTheme.typography.labelMedium
                    )
                    CompareView(cells, settledN)
                    Text(
                        "الخلايا الباهتة لم تستقر بعد وقد تتغير. الأحمر: اختلاف بين المسموع والمتوقع، وقد يكون من النموذج لا من القارئ.",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        if (history.isNotEmpty()) {
            Text("آخر التجارب (المسموع)", style = MaterialTheme.typography.titleSmall)
            history.take(10).forEach { line ->
                val ayahLabel = line.substringBefore("|")
                val sounds = line.substringAfter("|")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(ayahLabel, style = MaterialTheme.typography.labelMedium)
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(sounds, fontSize = 16.sp, lineHeight = 26.sp)
                        }
                    }
                }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompareView(cells: List<Cell>, settledN: Int) {
    val okBg = MaterialTheme.colorScheme.surfaceVariant
    val badBg = MaterialTheme.colorScheme.errorContainer
    val badFg = MaterialTheme.colorScheme.onErrorContainer
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            var heardPos = 0
            cells.forEach { c ->
                val bad = c.kind != Kind.MATCH
                val tentative = heardPos >= settledN
                if (c.heard != null) heardPos++
                Column(
                    Modifier
                        .alpha(if (tentative) 0.5f else 1f)
                        .background(if (bad) badBg else okBg, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(c.exp ?: "∅", fontSize = 20.sp)
                    Text(c.heard ?: "∅", fontSize = 20.sp, color = if (bad) badFg else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
