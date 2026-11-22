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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
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
private val GREEN = Color(0xFF2E7D32)
private val RED = Color(0xFFC62828)

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
    val mushaf = remember { loadMushaf(ctx) }
    val ayahCount = mushaf.size

    var startAyah by remember { mutableIntStateOf(1) }
    var listening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("اضغط على الآية التي تريد البدء منها، ثم زر التسجيل.") }
    var diag by remember { mutableStateOf("") }
    var wordStates by remember { mutableStateOf<Map<Int, WordStatus>>(emptyMap()) }
    var issues by remember { mutableStateOf<List<Issue>>(emptyList()) }
    var mism by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var currentAyah by remember { mutableIntStateOf(1) }
    var job by remember { mutableStateOf<Job?>(null) }
    var current by remember { mutableStateOf<TajweedSession?>(null) }
    val listState = rememberLazyListState()

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
        if (ayahCount == 0) {
            status = "تعذّر تحميل نص السورة (assets/tahrim.txt)."
            return
        }
        wordStates = emptyMap(); issues = emptyList(); mism = emptySet()
        status = "جارٍ تحميل النموذج…"
        val ayah = startAyah
        job = scope.launch {
            listening = true
            try {
                val model = app.client.model()
                val tok = model.tokens
                val barName = "${tok.name(tok.wordBar)}"
                val ref = model.reference
                if (ref == null) {
                    status = "المرجع (model.reference) غير متوفر في هذا النموذج."
                    return@launch
                }

                // المتوقع: وحدات كل كلمة من مرجع Quran Lab، من آية البداية إلى نهاية السورة.
                val expUnits = ArrayList<ExpUnit>()
                val mismatch = HashSet<Int>()
                for (a in ayah..ayahCount) {
                    val names = ref.units(SURAH_NUMBER, a).toList().map { "${tok.name(it)}" }
                    val perWord = ArrayList<List<String>>()
                    var cur = ArrayList<String>()
                    for (u in names) {
                        if (u == barName) {
                            if (cur.isNotEmpty()) { perWord.add(cur); cur = ArrayList() }
                        } else cur.add(u)
                    }
                    if (cur.isNotEmpty()) perWord.add(cur)
                    if (perWord.size != mushaf[a - 1].size) mismatch.add(a)
                    perWord.forEachIndexed { wi, ws -> ws.forEach { expUnits.add(ExpUnit(it, a, wi)) } }
                }
                mism = mismatch
                val expNames = expUnits.map { it.name }

                status = "تحدّث الآن من الآية $ayah"
                val session = model.listen(TajweedSessionOptions(endOnSilenceMs = 3000))
                current = session
                session.chunks.collect { chunk ->
                    val toks = chunk.decoded.tokens
                    val st = chunk.decoded.settled.toInt()
                    val settledCount = toks.take(st).count { "${it.name}" != barName }
                    val heard = toks.map { "${it.name}" }.filter { it != barName }
                    val cells = align(expNames, heard)
                    val (ws, iss) = analyze(expUnits, cells, settledCount, mushaf, mismatch)
                    wordStates = ws
                    issues = iss
                    val last = cells.lastOrNull { it.expIdx != null }
                    val li = last?.expIdx
                    if (li != null) currentAyah = expUnits[li].ayah
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
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else status = "لم يُسمح بالميكروفون. فعّله من إعدادات التطبيق."
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { job?.cancel() }

    LaunchedEffect(Unit) {
        try { app.client.prefetch() } catch (_: Throwable) { }
    }

    // يتبع التطبيق الآية الجارية أثناء التلاوة.
    LaunchedEffect(currentAyah, listening) {
        if (listening && currentAyah in 1..ayahCount) listState.animateScrollToItem(currentAyah - 1)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (listening) stop()
                    else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                    ) start()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                Text(if (listening) "⏹ إيقاف" else "🎙 ابدأ من الآية $startAyah", fontSize = 17.sp)
            }
            TextButton(onClick = {
                scope.launch {
                    diag = try { "${app.client.diagnose()}" } catch (e: Throwable) { "خطأ: ${e.message}" }
                }
            }) { Text("تشخيص") }
        }

        Text(status, style = MaterialTheme.typography.bodySmall)
        Text(
            "أخضر: يطابق المتوقع · أحمر: يختلف عنه · بلا لون: لم يُقرأ بعد. " +
                "هذه قراءة آلية قد تخطئ، وليست حكماً شرعياً ولا تغني عن معلم مؤهل.",
            style = MaterialTheme.typography.labelSmall
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(mushaf) { idx, words ->
                val a = idx + 1
                AyahCard(
                    ayah = a, words = words, selected = a == startAyah, enabled = !listening,
                    states = wordStates, mismatch = a in mism
                ) { startAyah = a }
            }
        }

        if (issues.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .heightIn(max = 170.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("لماذا؟ (فروق بين المسموع والمتوقع)", style = MaterialTheme.typography.titleSmall)
                    issues.forEach { it2 ->
                        val head = if (it2.word.isNotBlank()) "«${disp(it2.word)}» (آية ${it2.ayah})" else "آية ${it2.ayah}"
                        Text("$head: ${it2.reasons.joinToString("؛ ")}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (diag.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { diag = "" },
            confirmButton = { TextButton(onClick = { diag = "" }) { Text("إغلاق") } },
            title = { Text("تشخيص") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(diag, style = MaterialTheme.typography.bodySmall) } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AyahCard(
    ayah: Int, words: List<String>, selected: Boolean, enabled: Boolean,
    states: Map<Int, WordStatus>, mismatch: Boolean, onSelect: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    Card(
        colors = CardDefaults.cardColors(containerColor = bg),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onSelect() }
    ) {
        Column(Modifier.padding(12.dp)) {
            if (selected) Text("▶ البداية من هنا", style = MaterialTheme.typography.labelSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                words.forEachIndexed { w, word ->
                    val st = states[ayah * 100 + w]?.state
                    val color = when (st) {
                        WState.GOOD -> GREEN
                        WState.BAD -> RED
                        null -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        disp(word),
                        fontSize = 28.sp,
                        lineHeight = 46.sp,
                        color = color,
                        fontWeight = if (st == null) FontWeight.Normal else FontWeight.Bold
                    )
                }
                Text("﴿${arabicDigits(ayah)}﴾", fontSize = 24.sp)
            }
            if (mismatch) {
                Text(
                    "تعذّر ربط كلمات هذه الآية بالمرجع، فلن تُلوَّن كلماتها.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
