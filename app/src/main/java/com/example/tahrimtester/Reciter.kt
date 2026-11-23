package com.example.tahrimtester

import ai.quranlab.tajweedformer.TajweedCalibration
import ai.quranlab.tajweedformer.TajweedError
import ai.quranlab.tajweedformer.TajweedHead
import ai.quranlab.tajweedformer.TajweedHeads
import ai.quranlab.tajweedformer.TajweedModel
import ai.quranlab.tajweedformer.TajweedSessionOptions
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SURAH_NUMBER = 66

private class LiveInput(val names: List<String>, val settled: Int, val first: LongArray, val last: LongArray)

/** يجمع مخرجات الشبكة من الدفعات متتابعةً (للحكم اللحظي). الإطارات القديمة لا تتغير أبداً. */
private class HeadAcc {
    private var buf = FloatArray(0)
    private var n = 0
    private var cls = 0
    private var fms = 40
    private var bad = false

    @Synchronized
    fun append(h: TajweedHead) {
        if (h.classes <= 0) return
        if (cls == 0) { cls = h.classes; fms = h.frameMs }
        if (h.classes != cls || h.values.size < h.frames * h.classes) { bad = true; return }
        val need = (n + h.frames) * cls
        if (need > buf.size) buf = buf.copyOf(max(need, buf.size * 2 + cls * 64))
        System.arraycopy(h.values, 0, buf, n * cls, h.frames * cls)
        n += h.frames
    }

    @Synchronized fun mat(): HeadMat? = if (bad || n == 0) null else HeadMat(fms, n, cls, buf)
    @Synchronized fun totalMs(): Long = n.toLong() * fms
    @Synchronized fun isBad(): Boolean = bad
}

enum class Phase { LOADING, READY, LISTENING, ANALYZING, DONE }

/**
 * محرّك الحالة: تحميل النموذج، التلاوة، الحكم اللحظي على كل كلمة فور اكتمالها، والحكم النهائي.
 * يعيش مع التطبيق (لا مع الشاشة) فلا يضيع شيء بدوران الشاشة.
 */
class Reciter(private val app: TahrimApp) {
    private val prefs = Prefs(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val mushaf: List<List<String>> = loadMushaf(app)
    val ayahCount: Int get() = mushaf.size

    var phase by mutableStateOf(Phase.LOADING); private set
    var loadError by mutableStateOf(""); private set
    var loadDetail by mutableStateOf(""); private set
    var dlDone by mutableLongStateOf(0L); private set
    var dlTotal by mutableLongStateOf(0L); private set
    var loadInfo by mutableStateOf(""); private set
    var status by mutableStateOf(""); private set

    var startAyah by mutableIntStateOf(prefs.startAyah.coerceAtLeast(1)); private set
    var preset by mutableStateOf(prefs.preset); private set
    var textSp by mutableStateOf(prefs.textSp); private set

    var cursor by mutableIntStateOf(-1); private set
    var speaking by mutableStateOf(false); private set
    var liveRes by mutableStateOf<FinalResult?>(null); private set
    var greedyRev by mutableStateOf<Review?>(null); private set
    var finalRes by mutableStateOf<FinalResult?>(null); private set
    var liveJudgeActive by mutableStateOf(false); private set
    var calibration by mutableStateOf<TajweedCalibration?>(null); private set
    var ctxName by mutableStateOf(""); private set
    var mism by mutableStateOf<Set<Int>>(emptySet()); private set
    var presetList by mutableStateOf<List<String>>(emptyList()); private set

    var contexts by mutableStateOf<List<Pair<String, Int>>>(emptyList()); private set
    var ctxChoice by mutableStateOf(""); private set
    var lagNow by mutableLongStateOf(0L); private set
    var lagMax by mutableLongStateOf(0L); private set
    var bench by mutableStateOf(""); private set
    var benchRunning by mutableStateOf(false); private set
    var reportText by mutableStateOf(""); private set

    // معلومات تشخيصية للتقرير
    private var calDump = ""
    private var probe: List<String> = emptyList()
    private var syncInfo = ""
    private var liveStats = ""

    val seenIntro: Boolean get() = prefs.seenIntro

    /** النتيجة المعروضة على المصحف الآن: حكم معاير (لحظي ثم نهائي) أو حكم احتياطي من الأصوات المسموعة. */
    val review: Review? by derivedStateOf {
        val c = calibration
        val res = finalRes ?: liveRes
        if (c != null && res != null && preset.isNotEmpty() && res.judged.isNotEmpty())
            buildFinal(res, c, ctxName, preset, mushaf, mism)
        else greedyRev
    }

    private var modelRef: TajweedModel? = null
    private var loadJob: Job? = null
    private var job: Job? = null
    private var finishFn: (suspend () -> TajweedHeads?)? = null

    fun markIntroSeen() { prefs.seenIntro = true }
    fun chooseAyah(n: Int) { startAyah = n; prefs.startAyah = n }
    fun choosePreset(p: String) { preset = p; prefs.preset = p }
    fun chooseTextSp(v: Float) { textSp = v; prefs.textSp = v }
    fun chooseContext(c: String) { ctxChoice = c }
    fun notify(msg: String) { status = msg }
    fun onBackground() { if (phase == Phase.LISTENING) job?.cancel() }

    private fun friendly(e: Throwable): String = when ((e as? TajweedError)?.code) {
        "needs_connection" -> "تعذّر الاتصال بالإنترنت. تأكد من اتصالك ثم أعد المحاولة."
        "download_failed" -> "تعذّر تنزيل النموذج الآن. جرّب شبكة أخرى أو أعد المحاولة بعد قليل."
        "microphone_denied" -> "لم يُسمح بالميكروفون. فعّله من إعدادات التطبيق."
        "invalid_key", "server_rejected" -> "تعذّر التحقق من التطبيق لدى الخادم."
        else -> "حدث خطأ غير متوقع. أعد المحاولة."
    }

    private fun detail(e: Throwable): String =
        (if (e is TajweedError) "${e.code}: " else "") + (e.message ?: e.toString())

    /** تحميل النموذج وتهيئته مرة واحدة، مع تقدّم ظاهر. */
    fun load() {
        if (phase != Phase.LOADING || loadJob?.isActive == true) return
        loadError = ""; loadDetail = ""; dlDone = 0L; dlTotal = 0L
        if (BuildConfig.QURANLAB_KEY.isBlank()) {
            loadError = "مفتاح التطبيق غير موجود في هذا البناء."
            return
        }
        loadJob = scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            try {
                app.client.prefetch(onProgress = { d, t -> dlDone = d; dlTotal = t })
                val t1 = SystemClock.elapsedRealtime()
                val m = app.client.model()
                val t2 = SystemClock.elapsedRealtime()
                modelRef = m
                contexts = m.contexts.map { it to m.chunkMs(it) }
                loadInfo = "التنزيل ${(t1 - t0) / 1000} ث · التهيئة ${(t2 - t1) / 1000} ث"
                phase = Phase.READY
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                loadError = friendly(e)
                loadDetail = detail(e)
            }
        }
    }

    fun retryLoad() {
        loadJob?.cancel()
        loadJob = null
        phase = Phase.LOADING
        load()
    }

    private fun resetTake() {
        liveRes = null; greedyRev = null; finalRes = null; liveJudgeActive = false
        cursor = -1; mism = emptySet(); speaking = false; status = ""
        lagNow = 0L; lagMax = 0L; probe = emptyList(); syncInfo = ""; liveStats = ""
    }

    fun start() {
        if (phase != Phase.READY && phase != Phase.DONE) return
        val model = modelRef ?: return
        resetTake()
        phase = Phase.LISTENING
        val ayah = startAyah
        job = scope.launch {
            try {
                runTake(model, ayah)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                status = friendly(e)
                loadDetail = detail(e)
            } finally {
                speaking = false
                finishFn = null
                if (phase != Phase.DONE) phase = Phase.READY
            }
        }
    }

    fun stop() {
        val f = finishFn
        if (f == null) { job?.cancel(); return }
        scope.launch {
            try { f() } catch (_: Throwable) { job?.cancel() }
        }
    }

    private suspend fun runTake(model: TajweedModel, ayah: Int) {
        val tok = model.tokens
        val barName = "${tok.name(tok.wordBar)}"
        val ref = model.reference
        if (ref == null) {
            status = "المرجع غير متوفر في هذا النموذج."
            return
        }

        // المعايرة والسياق: نستعمل السياق الذي تنتمي إليه عتبات المعايرة.
        val calFull = model.calibration
        val ctxN = if (ctxChoice.isNotEmpty() && ctxChoice in model.contexts) ctxChoice
        else if (calFull != null && model.defaultContext !in calFull.contexts) (calFull.defaultContext ?: model.defaultContext)
        else model.defaultContext
        val cal = calFull?.takeIf { ctxN in it.contexts }
        calibration = cal
        ctxName = ctxN
        presetList = cal?.presets ?: emptyList()
        if (cal != null && (preset.isEmpty() || preset !in cal.presets)) {
            val d = cal.defaultPreset
            choosePreset(if (d != null && d in cal.presets) d else (cal.presets.firstOrNull() ?: "balanced"))
        }
        calDump = if (cal != null) dumpCalibration(cal, ctxN)
        else if (calFull != null) "لا معايرة للسياق $ctxN: الحكم من الأصوات المسموعة فقط."
        else "لا توجد معايرة في هذا النموذج."

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
        val wordRanges = buildWordRanges(expUnits)
        val prevFirstOf = IntArray(wordRanges.size) { i ->
            if (i > 0 && wordRanges[i - 1].ayah == wordRanges[i].ayah) wordRanges[i - 1].first else wordRanges[i].first
        }
        val active = { c: String -> cal != null && cal.status(ctxN, c).let { it != null && it != "off" } }
        val weight = { c: String -> cal?.fusionWeight(ctxN, c) ?: 0.5 }
        val chunkMsN = model.chunkMs(ctxN)

        val session = model.listen(
            TajweedSessionOptions(context = ctxN, endOnSilenceMs = 3000, heads = listOf("log_probs", "inter1_log_probs"))
        )
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
        finishFn = finishOnce

        val latest = AtomicReference<LiveInput?>(null)
        val signal = Channel<Unit>(Channel.CONFLATED)
        val lpAcc = HeadAcc()
        val i1Acc = HeadAcc()
        val syncOk = AtomicBoolean(true)
        val probeList = ArrayList<String>()
        var lastDev = 0L
        val t0 = SystemClock.elapsedRealtime()
        liveJudgeActive = cal != null

        coroutineScope {
            // المعالجة الثقيلة خارج خيط الواجهة: محاذاة + حكم محلي لكل كلمة فور اكتمالها.
            val worker = launch(Dispatchers.Default) {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                var reached = 0
                var nextWord = 0
                val liveSites = ArrayList<Site>()
                val liveJudged = HashSet<Int>()
                var judgeMs = 0L
                var judgeCount = 0
                for (ignored in signal) {
                    val inp = latest.getAndSet(null) ?: continue
                    val limit = min(expNames.size, reached + 200)
                    val cells = align(expNames.subList(0, limit), inp.names)
                    val lv = liveAnalyze(expUnits, cells, inp.settled, mismatch)
                    val li = cells.lastOrNull { it.expIdx != null }?.expIdx
                    if (li != null) reached = max(reached, li)
                    val useLive = cal != null && syncOk.get()
                    var newRes: FinalResult? = null
                    var newGreedy: Review? = null
                    if (useLive) {
                        val cellByExp = arrayOfNulls<Cell>(limit)
                        for (c in cells) { val ei = c.expIdx; if (ei != null && ei < limit) cellByExp[ei] = c }
                        val lpMat = lpAcc.mat()
                        val i1Mat = i1Acc.mat()
                        var changed = false
                        val liHeard = if (li != null) cellByExp[li]?.heardIdx else null
                        val liSettled = liHeard != null && liHeard < inp.settled
                        while (lpMat != null && li != null && liSettled && nextWord < wordRanges.size) {
                            val wr = wordRanges[nextWord]
                            val need = min(wr.last + 2, wr.ayahLast)
                            if (li < need) break
                            val tj = SystemClock.elapsedRealtime()
                            val ss = judgeWordLocal(
                                lpMat, i1Mat, tok, expUnits, cellByExp, inp.first, inp.last,
                                wr, prevFirstOf[nextWord], active, weight
                            )
                            judgeMs += SystemClock.elapsedRealtime() - tj
                            judgeCount++
                            if (ss != null) { liveSites.addAll(ss); liveJudged.add(wr.key); changed = true }
                            nextWord++
                        }
                        if (changed) newRes = FinalResult(ArrayList(liveSites), HashSet(liveJudged), "live")
                    } else {
                        newGreedy = greedyReview(expUnits, cells, inp.settled, mismatch)
                    }
                    val stats = "live: words judged=${liveJudged.size} sites=${liveSites.size} " +
                        "avgJudgeMs=${if (judgeCount > 0) judgeMs / judgeCount else 0} mode=${if (useLive) "calibrated" else "greedy-fallback"}"
                    withContext(Dispatchers.Main) {
                        cursor = if (lv.cursor >= 0) lv.cursor else cursor
                        liveJudgeActive = useLive
                        if (newRes != null) liveRes = newRes
                        if (newGreedy != null) greedyRev = newGreedy
                        liveStats = stats
                    }
                }
            }
            session.chunks.collect { chunk ->
                val toks = chunk.decoded.tokens
                val st = chunk.decoded.settled.toInt()
                val settledCount = toks.take(st).count { "${it.name}" != barName }
                val kept = toks.filter { "${it.name}" != barName }
                val heard = kept.map { "${it.name}" }
                val fm = LongArray(kept.size) { kept[it].firstMs }
                val lm = LongArray(kept.size) { kept[it].lastMs }
                latest.set(LiveInput(heard, settledCount, fm, lm))

                val hl = chunk.heads["log_probs"]
                val hi = chunk.heads["inter1_log_probs"]
                if (hl != null) lpAcc.append(hl)
                if (hi != null) i1Acc.append(hi)
                if (chunk.index < 4) {
                    probeList.add(
                        "chunk ${chunk.index}: firstFrame=${chunk.firstFrame} ${chunk.startMs}-${chunk.endMs}ms " +
                            "lp=${hl?.frames}x${hl?.classes}@${hl?.frameMs}ms i1=${hi?.frames}"
                    )
                }
                lastDev = lpAcc.totalMs() - chunk.endMs
                if (lpAcc.isBad() || (chunk.index >= 2 && abs(lastDev) > 3L * chunkMsN)) syncOk.set(false)

                signal.trySend(Unit)
                speaking = chunk.speech
                val lag = SystemClock.elapsedRealtime() - t0 - chunk.endMs
                lagNow = lag
                if (lag > lagMax) lagMax = lag
            }
            signal.close()
            worker.join()
        }
        probe = probeList
        syncInfo = "heads sync: ok=${syncOk.get()} lastDeviationMs=$lastDev chunkMs=$chunkMsN"

        speaking = false
        phase = Phase.ANALYZING
        val heads = finishOnce()
        if (heads != null && cal != null) {
            finalRes = withContext(Dispatchers.Default) {
                judgeTake(heads, tok, ayahUnits, expUnits, cal, ctxN)
            }
        }
        phase = Phase.DONE
    }

    /** قياس معامل الزمن الحقيقي (RTF) لكل سياق على الهاتف بمقطع تجريبي، دون ميكروفون. */
    fun runBench() {
        val m = modelRef ?: return
        if ((phase != Phase.READY && phase != Phase.DONE) || benchRunning) return
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
                    bench = "جارٍ القياس… " + rows.joinToString(" | ") {
                        "${it.first}: " + String.format(java.util.Locale.US, "%.2f", it.third)
                    }
                }
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

    // ───────── التقرير الموحّد ─────────

    /** يجمع كل بيانات الاختبار والتشخيص في نص واحد قابل للنسخ. */
    fun refreshReport() {
        scope.launch {
            val d = try { app.client.diagnose().toJson() } catch (e: Throwable) { "diagnose failed: ${e.message}" }
            reportText = composeReport(d)
        }
    }

    private fun composeReport(diagJson: String): String {
        val sb = StringBuilder()
        val rev = review
        val res = finalRes ?: liveRes
        val cal = calibration
        sb.appendLine("# تقرير مرتّل — سورة التحريم")
        sb.appendLine("الجهاز: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("التطبيق: ${BuildConfig.VERSION_NAME}")
        sb.appendLine("الحالة: $phase")
        sb.appendLine()
        sb.appendLine("## الإعدادات")
        sb.appendLine("بداية التلاوة: الآية $startAyah · مستوى التنبيه: $preset · السياق: $ctxName (اختيار يدوي: ${ctxChoice.ifEmpty { "—" }}) · حجم الخط: ${textSp.toInt()}")
        sb.appendLine()
        sb.appendLine("## التحميل")
        sb.appendLine(if (loadInfo.isNotBlank()) loadInfo else "—")
        if (loadError.isNotBlank()) sb.appendLine("خطأ: $loadError")
        if (loadDetail.isNotBlank()) sb.appendLine("آخر تفصيل خطأ: $loadDetail")
        sb.appendLine()
        sb.appendLine("## آخر تلاوة")
        sb.appendLine("تأخر المعالجة: الآن ${lagNow}ms · الأقصى ${lagMax}ms")
        sb.appendLine("وضع الحكم اللحظي: ${if (liveJudgeActive) "معاير (CTC محلي لكل كلمة)" else "احتياطي (من الأصوات المسموعة)"}")
        if (syncInfo.isNotBlank()) sb.appendLine(syncInfo)
        if (liveStats.isNotBlank()) sb.appendLine(liveStats)
        probe.forEach { sb.appendLine(it) }
        if (mism.isNotEmpty()) sb.appendLine("آيات لم يتطابق عدد كلمات نصها مع المرجع: ${mism.sorted()}")
        if (rev != null) sb.appendLine("النتيجة: ${rev.good} سليمة من ${rev.judged} كلمة محكومة · ${rev.issues.size} للمراجعة")
        else sb.appendLine("النتيجة: لا شيء بعد")
        sb.appendLine()
        sb.appendLine("## الكلمات المعلَّمة")
        if (rev == null || rev.issues.isEmpty()) sb.appendLine("—")
        else for (i in rev.issues) {
            sb.appendLine("آية ${i.ayah} كلمة ${i.word} «${i.text}»")
            for (n in i.notes) {
                sb.appendLine("  - [${n.level}${if (n.experimental) " تجريبي" else ""}] ${n.type}: ${n.explain} | ${n.fix}")
            }
        }
        sb.appendLine()
        sb.appendLine("## تقرير الحكم المعاير")
        if (cal != null && res != null) sb.appendLine(judgeReport(res, cal, ctxName)) else sb.appendLine("—")
        sb.appendLine()
        sb.appendLine("## المعايرة")
        sb.appendLine(if (calDump.isNotBlank()) calDump else "—")
        sb.appendLine()
        sb.appendLine("## قياس السرعة")
        sb.appendLine(if (bench.isNotBlank()) bench else "لم يُجرَ بعد")
        sb.appendLine()
        sb.appendLine("## تشخيص الـ SDK")
        sb.appendLine(diagJson)
        return sb.toString()
    }
}
