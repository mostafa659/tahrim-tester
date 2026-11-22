package com.example.tahrimtester

import ai.quranlab.tajweedformer.TajweedCalibration
import ai.quranlab.tajweedformer.TajweedHeads
import ai.quranlab.tajweedformer.TajweedModel
import ai.quranlab.tajweedformer.TajweedSession
import ai.quranlab.tajweedformer.TajweedSessionOptions
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SURAH_NUMBER = 66
private val GREEN = Color(0xFF2E7D32)
private val RED = Color(0xFFC62828)

private class LiveInput(val names: List<String>, val settled: Int)

private class TakeHolder { var finish: (suspend () -> TajweedHeads?)? = null }

private fun presetAr(p: String) = when (p) {
    "strict" -> "صارم"
    "balanced" -> "متوازن"
    "sensitive" -> "حسّاس"
    "very_sensitive" -> "حسّاس جداً"
    else -> p
}

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
    var finalRes by remember { mutableStateOf<FinalResult?>(null) }
    var calState by remember { mutableStateOf<TajweedCalibration?>(null) }
    var ctxState by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf("") }
    var calDump by remember { mutableStateOf("") }
    var modelState by remember { mutableStateOf<TajweedModel?>(null) }
    var dl by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var loadErr by remember { mutableStateOf("") }
    var loadTick by remember { mutableIntStateOf(0) }
    var loadInfo by remember { mutableStateOf("") }
    var ctxChoice by remember { mutableStateOf("") }
    var lagNow by remember { mutableLongStateOf(0L) }
    var lagMax by remember { mutableLongStateOf(0L) }
    var bench by remember { mutableStateOf("") }
    var benchRunning by remember { mutableStateOf(false) }
    val holder = remember { TakeHolder() }
    val listState = rememberLazyListState()

    fun stop() {
        scope.launch {
            try { holder.finish?.invoke() } catch (_: Throwable) { job?.cancel() }
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
        wordStates = emptyMap(); issues = emptyList(); mism = emptySet(); finalRes = null
        status = "جارٍ تحميل النموذج…"
        val ayah = startAyah
        job = scope.launch {
            listening = true
            try {
                val model = modelState ?: app.client.model()
                val tok = model.tokens
                val barName = "${tok.name(tok.wordBar)}"
                val ref = model.reference
                if (ref == null) {
                    status = "المرجع (model.reference) غير متوفر في هذا النموذج."
                    return@launch
                }

                // المعايرة والسياق: نستعمل السياق الذي تنتمي إليه عتبات المعايرة.
                val calFull = model.calibration
                val ctxName = if (ctxChoice.isNotEmpty() && ctxChoice in model.contexts) ctxChoice
                else if (calFull != null && model.defaultContext !in calFull.contexts) (calFull.defaultContext ?: model.defaultContext)
                else model.defaultContext
                val cal = calFull?.takeIf { ctxName in it.contexts }
                calState = cal
                ctxState = ctxName
                if (cal != null && preset.isEmpty()) preset = cal.defaultPreset ?: "balanced"
                calDump = if (cal != null) dumpCalibration(cal, ctxName) else if (calFull != null) "لا معايرة للسياق $ctxName: التحليل المبدئي فقط." else "لا توجد معايرة في هذا النموذج."

                // المتوقع: وحدات كل كلمة من مرجع Quran Lab، من آية البداية إلى نهاية السورة.
                val expUnits = ArrayList<ExpUnit>()
                val mismatch = HashSet<Int>()
                val ayahUnits = HashMap<Int, IntArray>()
                for (a in ayah..ayahCount) {
                    val raw = ref.units(SURAH_NUMBER, a)
                    ayahUnits[a] = raw
                    val names = raw.toList().map { "${tok.name(it)}" }
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
                val session = model.listen(TajweedSessionOptions(context = ctxName, endOnSilenceMs = 3000, heads = listOf("log_probs", "inter1_log_probs")))
                current = session
                val finishedFlag = AtomicBoolean(false)
                val headsDef = CompletableDeferred<TajweedHeads?>()
                val finishOnce: suspend () -> TajweedHeads? = {
                    if (!finishedFlag.compareAndSet(false, true)) headsDef.await()
                    else try {
                        session.finish().also { headsDef.complete(it) }
                    } catch (e: CancellationException) {
                        headsDef.complete(null); throw e
                    } catch (e: Throwable) {
                        headsDef.complete(null); null
                    }
                }
                holder.finish = finishOnce
                // المعالجة الثقيلة خارج خيط الواجهة وبلا تعطيل لقراءة مخرجات النموذج.
                val latest = AtomicReference<LiveInput?>(null)
                val signal = Channel<Unit>(Channel.CONFLATED)
                val t0 = SystemClock.elapsedRealtime()
                lagNow = 0L; lagMax = 0L
                val worker = launch(Dispatchers.Default) {
                    // أقل أولوية من محرك النموذج حتى لا ننافسه على أنوية المعالج الضعيفة.
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    var reached = 0
                    for (ignored in signal) {
                        val inp = latest.getAndSet(null) ?: continue
                        val limit = min(expNames.size, reached + 200)
                        val cells = align(expNames.subList(0, limit), inp.names)
                        val (ws, iss) = analyze(expUnits, cells, inp.settled, mushaf, mismatch)
                        val li = cells.lastOrNull { it.expIdx != null }?.expIdx
                        if (li != null) reached = max(reached, li)
                        withContext(Dispatchers.Main) {
                            wordStates = ws
                            issues = iss
                            if (li != null) currentAyah = expUnits[li].ayah
                        }
                    }
                }
                session.chunks.collect { chunk ->
                    val toks = chunk.decoded.tokens
                    val st = chunk.decoded.settled.toInt()
                    val settledCount = toks.take(st).count { "${it.name}" != barName }
                    val heard = toks.map { "${it.name}" }.filter { it != barName }
                    latest.set(LiveInput(heard, settledCount))
                    signal.trySend(Unit)
                    val lag = SystemClock.elapsedRealtime() - t0 - chunk.endMs
                    lagNow = lag
                    if (lag > lagMax) lagMax = lag
                }
                signal.close()
                worker.join()
                status = "جارٍ التحليل المعاير…"
                val heads = finishOnce()
                if (heads != null && cal != null) {
                    val res = withContext(Dispatchers.Default) {
                        judgeTake(heads, tok, ayahUnits, expUnits, cal, ctxName)
                    }
                    finalRes = res
                    status = if (res != null && res.judged.isNotEmpty()) "انتهى. التحليل المعاير جاهز (يمكنك تغيير الحساسية)."
                    else "تعذّر التحليل المعاير؛ يُعرض التحليل المبدئي."
                } else {
                    status = "انتهى الاستماع (التحليل المبدئي فقط)."
                }
            } catch (e: CancellationException) {
                status = "تم الإيقاف."
                throw e
            } catch (e: Throwable) {
                status = "خطأ: ${e.message}"
            } finally {
                listening = false
                current = null
                holder.finish = null
            }
        }
    }

    // قياس معامل الزمن الحقيقي (RTF) لكل سياق على هاتفك بمقطع تجريبي من النموذج، دون ميكروفون.
    fun runBench() {
        val m = modelState ?: return
        if (listening || benchRunning) return
        benchRunning = true
        bench = "جارٍ قياس السرعة على هاتفك…"
        scope.launch {
            try {
                val clip = m.sampleClips.firstOrNull()
                if (clip == null) {
                    bench = "لا توجد مقاطع تجريبية في هذا النموذج."
                    return@launch
                }
                val rows = ArrayList<Triple<String, Int, Double>>()
                for (c in m.contexts) {
                    var rtf = 0.0
                    for (pass in 0..1) { // الأولى للإحماء، والثانية للقياس
                        val t0 = SystemClock.elapsedRealtime()
                        val sess = m.listen(
                            clip,
                            TajweedSessionOptions(
                                context = c,
                                pace = TajweedSessionOptions.Pace.FAST,
                                heads = listOf("log_probs", "inter1_log_probs")
                            )
                        )
                        sess.chunks.collect { }
                        try { sess.finish() } catch (_: Throwable) { }
                        rtf = (SystemClock.elapsedRealtime() - t0).toDouble() / clip.durationMs.toDouble()
                    }
                    rows.add(Triple(c, m.chunkMs(c), rtf))
                    bench = "جارٍ القياس… " + rows.joinToString(" | ") { "${it.first}: " + String.format(java.util.Locale.US, "%.2f", it.third) }
                }
                // الأفضل: أقصر دفعة بين السياقات التي تواكب الزمن الحقيقي بهامش أمان (RTF ≤ 0.7)، وإلا الأسرع معالجة.
                val ok = rows.filter { it.third <= 0.7 }
                val best = if (ok.isNotEmpty()) ok.minByOrNull { it.second }!! else rows.minByOrNull { it.third }!!
                ctxChoice = best.first
                bench = "معامل الزمن الحقيقي (أقل من 1 يعني يواكب صوتك):\n" +
                    rows.joinToString("\n") { r ->
                        "${r.first} (دفعة ${r.second} م.ث): " + String.format(java.util.Locale.US, "%.2f", r.third)
                    } + "\nتم اختيار: ${best.first}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                bench = "تعذّر القياس: ${e.message}"
            } finally {
                benchRunning = false
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else status = "لم يُسمح بالميكروفون. فعّله من إعدادات التطبيق."
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { job?.cancel() }

    // تحميل النموذج وتهيئته مرة واحدة عند فتح التطبيق، مع تقدّم ظاهر وزمن كل مرحلة.
    LaunchedEffect(loadTick) {
        if (BuildConfig.QURANLAB_KEY.isBlank()) return@LaunchedEffect
        loadErr = ""; modelState = null; dl = null; loadInfo = ""
        val t0 = SystemClock.elapsedRealtime()
        try {
            app.client.prefetch(onProgress = { d, t -> dl = d to t })
            val t1 = SystemClock.elapsedRealtime()
            val m = app.client.model()
            val t2 = SystemClock.elapsedRealtime()
            loadInfo = "التنزيل ${(t1 - t0) / 1000} ث · التهيئة ${(t2 - t1) / 1000} ث"
            modelState = m
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            loadErr = "${e.message}"
        }
    }

    // يتبع التطبيق الآية الجارية أثناء التلاوة.
    LaunchedEffect(currentAyah, listening) {
        if (listening && currentAyah in 1..ayahCount) listState.animateScrollToItem(currentAyah - 1)
    }

    val finalPair = remember(finalRes, preset, calState, ctxState, mism) {
        val r = finalRes
        val c = calState
        if (r != null && c != null && preset.isNotEmpty()) buildFinal(r, c, ctxState, preset, mushaf, mism) else null
    }
    val shownStates = finalPair?.first ?: wordStates
    val shownIssues = finalPair?.second ?: issues

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
                enabled = listening || modelState != null,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                Text(
                    if (listening) "⏹ إيقاف"
                    else if (modelState == null) "جارٍ تحميل النموذج…"
                    else "🎙 ابدأ من الآية $startAyah",
                    fontSize = 17.sp
                )
            }
            TextButton(onClick = { runBench() }, enabled = modelState != null && !listening && !benchRunning) { Text("قياس السرعة") }
            TextButton(onClick = {
                scope.launch {
                    val d = try { "${app.client.diagnose()}" } catch (e: Throwable) { "خطأ: ${e.message}" }
                    val rep = run {
                        val r = finalRes
                        val c = calState
                        if (r != null && c != null) "\n\n" + judgeReport(r, c, ctxState) else ""
                    }
                    diag = d + (if (calDump.isNotBlank()) "\n\n" + calDump else "") + rep
                }
            }) { Text("تشخيص") }
        }

        Text(status, style = MaterialTheme.typography.bodySmall)
        val mdl = modelState
        if (mdl == null) {
            if (loadErr.isNotEmpty()) {
                Text("تعذّر تحميل النموذج: $loadErr", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { loadTick++ }) { Text("إعادة المحاولة") }
            } else {
                val d = dl
                Text(
                    "تحميل النموذج" + (if (d != null && d.second > 0) " ${d.first / 1_000_000} / ${d.second / 1_000_000} م.ب" else "…"),
                    style = MaterialTheme.typography.labelMedium
                )
                if (d != null && d.second > 0) {
                    LinearProgressIndicator(
                        progress = { (d.first.toFloat() / d.second.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        } else {
            Text("النموذج جاهز · $loadInfo", style = MaterialTheme.typography.labelSmall)
            if (mdl.contexts.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("سرعة الاستجابة:", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(mdl.contexts) { c ->
                            FilterChip(
                                selected = (ctxChoice.ifEmpty { mdl.defaultContext }) == c,
                                onClick = { if (!listening) ctxChoice = c },
                                label = { Text("${mdl.chunkMs(c)} م.ث") }
                            )
                        }
                    }
                }
            }
        }
        if (listening || lagMax > 0L) {
            Text(
                "تأخر المعالجة عن صوتك: الآن " + String.format(java.util.Locale.US, "%.1f", lagNow / 1000.0) +
                    " ث · الأقصى " + String.format(java.util.Locale.US, "%.1f", lagMax / 1000.0) + " ث",
                style = MaterialTheme.typography.labelSmall
            )
        }
        if (bench.isNotBlank()) Text(bench, style = MaterialTheme.typography.labelSmall)
        val presetList = calState?.presets ?: emptyList()
        if (presetList.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("الحساسية:", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(presetList) { p ->
                        FilterChip(selected = p == preset, onClick = { preset = p }, label = { Text(presetAr(p)) })
                    }
                }
            }
        }
        Text(
            if (finalPair != null) "التحليل: معاير (عتبات Quran Lab) — الحساسية ${presetAr(preset)}"
            else "التحليل: مبدئي (مقارنة الأصوات المسموعة فقط)",
            style = MaterialTheme.typography.labelMedium
        )
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
                    states = shownStates, mismatch = a in mism
                ) { startAyah = a }
            }
        }

        if (shownIssues.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .heightIn(max = 170.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("لماذا؟ (فروق بين المسموع والمتوقع)", style = MaterialTheme.typography.titleSmall)
                    shownIssues.forEach { it2 ->
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
