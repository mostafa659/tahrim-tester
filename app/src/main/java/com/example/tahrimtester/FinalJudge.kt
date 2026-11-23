package com.example.tahrimtester

import ai.quranlab.tajweedformer.TajweedCalibration
import ai.quranlab.tajweedformer.TajweedHead
import ai.quranlab.tajweedformer.TajweedHeads
import ai.quranlab.tajweedformer.TajweedTokens
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min

/*
 * الحكم المعاير: لكل موضع في الآية المتوقعة ولكل بديل بوحدة واحدة (استبدال/حذف/إضافة)
 * نحسب log P(البديل) - log P(المتوقع) بخوارزمية CTC forward الكاملة على log_probs
 * وعلى inter1_log_probs (مع دمج فاصل الكلمات في الـ blank)، ثم ندمج الرأسين بوزن الصنف،
 * ونقصّ القيمة، ونقارنها بعتبة model.calibration. (docs/migrating-to-3.md)
 * الحساب بتقاطع forward/backward: O(T) لكل بديل بدل إعادة الشبكة كلها.
 * نتائجه فروق محتملة وليست حكماً شرعياً.
 */

private const val NEG = Double.NEGATIVE_INFINITY

private fun lae(a: Double, b: Double): Double {
    if (a == NEG) return b
    if (b == NEG) return a
    return if (a > b) a + ln1p(exp(b - a)) else b + ln1p(exp(a - b))
}

/** مصفوفة مخرج من مخرجات الشبكة [frames × classes] بترتيب الصفوف (log-probs). */
class HeadMat(val frameMs: Int, val frames: Int, val classes: Int, val values: FloatArray)

fun TajweedHead.toMat(): HeadMat = HeadMat(frameMs, frames, classes, values)

private class Emit(val t: Int, val c: Int, val v: FloatArray)

private fun slice(h: HeadMat, f0: Int, f1: Int, mergeBar: Boolean, blank: Int, bar: Int): Emit {
    val t = f1 - f0
    val c = h.classes
    val out = FloatArray(t * c)
    for (i in 0 until t) {
        System.arraycopy(h.values, (f0 + i) * c, out, i * c, c)
        if (mergeBar) {
            out[i * c + blank] = lae(out[i * c + blank].toDouble(), out[i * c + bar].toDouble()).toFloat()
            out[i * c + bar] = Float.NEGATIVE_INFINITY
        }
    }
    return Emit(t, c, out)
}

private class Lattice(val e: Emit, val y: IntArray, val blank: Int) {
    val T = e.t
    val L = y.size
    val S = 2 * L + 1
    val alpha = DoubleArray((T + 1) * S) { NEG }   // alpha[(t+1)*S+s] = alpha_t(s)  (t=-1 is the virtual start)
    val beta = DoubleArray((T + 1) * S) { NEG }    // beta[t*S+s], t in 0..T-1
    var base = NEG
    var ok = false

    fun em(t: Int, s: Int): Double = e.v[t * e.c + (if (s % 2 == 0) blank else y[s / 2])].toDouble()

    init {
        if (T >= 1 && L >= 1) {
            alpha[0] = 0.0
            for (t in 0 until T) {
                val p = t * S
                val n = (t + 1) * S
                for (s in 0 until S) {
                    var v = alpha[p + s]
                    if (s >= 1) v = lae(v, alpha[p + s - 1])
                    if (s >= 3 && s % 2 == 1 && y[s / 2] != y[s / 2 - 1]) v = lae(v, alpha[p + s - 2])
                    alpha[n + s] = if (v == NEG) NEG else v + em(t, s)
                }
            }
            val last = (T - 1) * S
            for (s in 0 until S) beta[last + s] = if (s >= S - 2) em(T - 1, s) else NEG
            for (t in T - 2 downTo 0) {
                val n = (t + 1) * S
                val cur = t * S
                for (s in 0 until S) {
                    var v = beta[n + s]
                    if (s + 1 < S) v = lae(v, beta[n + s + 1])
                    if (s % 2 == 1 && s + 2 < S && y[(s + 2) / 2] != y[s / 2]) v = lae(v, beta[n + s + 2])
                    beta[cur + s] = if (v == NEG) NEG else v + em(t, s)
                }
            }
            base = lae(alpha[T * S + S - 1], alpha[T * S + S - 2])
            ok = base != NEG
        }
    }

    /** نافذة الإطارات التي يمرّ فيها المسار المتوقع قرب هذه الحالات (لتسريع الحساب). */
    fun window(states: IntArray): IntArray {
        var lo = T
        var hi = -1
        for (t in 0 until T) for (s in states) {
            if (s < 0 || s >= S) continue
            val post = alpha[(t + 1) * S + s] + beta[t * S + s] - em(t, s) - base
            if (post > -12.0) { if (t < lo) lo = t; if (t > hi) hi = t }
        }
        if (hi < 0) return intArrayOf(0, T - 1)
        return intArrayOf(max(0, lo - 4), min(T - 1, hi + 4))
    }

    /** log P للتسلسل بعد استبدال الوحدة p بالوحدة c. */
    fun sub(p: Int, c: Int, lo: Int, hi: Int): Double {
        val allowPrev = p >= 1 && y[p - 1] != c
        val allowNext = p + 1 < L && y[p + 1] != c
        var r = NEG
        var tot = NEG
        for (t in lo..hi) {
            val ea = lae(alpha[t * S + 2 * p], if (allowPrev) alpha[t * S + 2 * p - 1] else NEG)
            r = lae(r, ea)
            r = if (r == NEG) NEG else r + e.v[t * e.c + c]
            val x = if (t == T - 1) {
                if (p == L - 1) 0.0 else NEG
            } else {
                lae(beta[(t + 1) * S + 2 * p + 2], if (allowNext) beta[(t + 1) * S + 2 * p + 3] else NEG)
            }
            if (r != NEG && x != NEG) tot = lae(tot, r + x)
        }
        return tot
    }

    /** log P للتسلسل بعد حذف الوحدة p. */
    fun del(p: Int, lo: Int, hi: Int): Double {
        if (L == 1) {
            var s = 0.0
            for (t in 0 until T) s += e.v[t * e.c + blank].toDouble()
            return s
        }
        if (p == L - 1) return lae(alpha[T * S + 2 * p], alpha[T * S + 2 * p - 1])
        val allowSkip = p >= 1 && y[p - 1] != y[p + 1]
        var tot = NEG
        for (t in max(-1, lo - 1)..min(T - 2, hi)) {
            val pre = lae(alpha[(t + 1) * S + 2 * p], if (allowSkip) alpha[(t + 1) * S + 2 * p - 1] else NEG)
            val x = beta[(t + 1) * S + 2 * p + 3]
            if (pre != NEG && x != NEG) tot = lae(tot, pre + x)
        }
        return tot
    }

    /** log P للتسلسل بعد إدخال الوحدة c في الفجوة g (قبل الوحدة g). */
    fun ins(g: Int, c: Int, lo: Int, hi: Int): Double {
        val allowPrev = g >= 1 && y[g - 1] != c
        val allowNext = g < L && y[g] != c
        var r = NEG
        var tot = NEG
        for (t in lo..hi) {
            val ea = lae(alpha[t * S + 2 * g], if (allowPrev) alpha[t * S + 2 * g - 1] else NEG)
            r = lae(r, ea)
            r = if (r == NEG) NEG else r + e.v[t * e.c + c]
            val x = if (t == T - 1) {
                if (g == L) 0.0 else NEG
            } else {
                lae(beta[(t + 1) * S + 2 * g], if (allowNext) beta[(t + 1) * S + 2 * g + 1] else NEG)
            }
            if (r != NEG && x != NEG) tot = lae(tot, r + x)
        }
        return tot
    }
}

private const val K_SUB = 0
private const val K_DEL = 1
private const val K_INS = 2

private class Best(val llr: Double, val alt: Int)

private fun bestLlr(lat: Lattice?, pos: Int, kind: Int, alts: List<Int>): Best? {
    if (lat == null || !lat.ok) return null
    val states = if (kind == K_INS) intArrayOf(2 * pos - 1, 2 * pos, 2 * pos + 1)
    else intArrayOf(2 * pos - 1, 2 * pos, 2 * pos + 1, 2 * pos + 2, 2 * pos + 3)
    val w = lat.window(states)
    val lo = w[0]
    val hi = w[1]
    var best = NEG
    var bestAlt = -1
    if (kind == K_DEL) {
        best = lat.del(pos, lo, hi)
    } else {
        for (c in alts) {
            val v = if (kind == K_SUB) lat.sub(pos, c, lo, hi) else lat.ins(pos, c, lo, hi)
            if (v > best) { best = v; bestAlt = c }
        }
    }
    val llr = if (best == NEG) -30.0 else best - lat.base
    return Best(llr, bestAlt)
}

internal class RawSite(val unit: Int, val gap: Boolean, val cls: String, val llr: Double, val alt: String)

private fun isLetter(n: String) = n.length == 1 && n[0] in '\u0621'..'\u064A'

internal fun judgeUnits(
    lpH: HeadMat, i1H: HeadMat?, t0Ms: Long, t1Ms: Long, units: IntArray, tok: TajweedTokens,
    active: (String) -> Boolean, weight: (String) -> Double
): List<RawSite> {
    val blank = tok.blank
    val bar = tok.wordBar
    val yNb = units.filter { it != bar }.toIntArray()
    val idxLp = IntArray(yNb.size)
    run {
        var k = 0
        units.forEachIndexed { i, u -> if (u != bar) { idxLp[k] = i; k++ } }
    }
    fun frames(h: HeadMat): IntArray {
        val f0 = max(0, (t0Ms / h.frameMs).toInt())
        val f1 = min(h.frames, (t1Ms / h.frameMs).toInt() + 1)
        return intArrayOf(f0, f1)
    }
    val fl = frames(lpH)
    if (fl[1] - fl[0] < 2 || fl[1] - fl[0] > 3000) return emptyList()
    val latLp = Lattice(slice(lpH, fl[0], fl[1], false, blank, bar), units, blank)
    if (!latLp.ok) return emptyList()
    val latI1 = i1H?.let { h ->
        val f = frames(h)
        if (f[1] - f[0] in 2..3000 && yNb.isNotEmpty()) Lattice(slice(h, f[0], f[1], true, blank, bar), yNb, blank) else null
    }

    val names = yNb.map { tok.name(it) }
    val shortIds = listOf("a", "i", "u").mapNotNull { tok.id(it) }
    val longOf = mapOf("a" to "aa", "i" to "ii", "u" to "uu")
    val shortOf = mapOf("aa" to "a", "ii" to "i", "uu" to "u")
    val letterIds = (0 until tok.count).filter { it != blank && it != bar && isLetter(tok.name(it)) }

    val out = ArrayList<RawSite>()

    fun eval(cls: String, kind: Int, posLp: Int, posNb: Int, alts: List<Int>, unit: Int, gap: Boolean) {
        if (!active(cls)) return
        if (kind != K_DEL && alts.isEmpty()) return
        val r1 = bestLlr(latLp, posLp, kind, alts) ?: return
        val r2 = bestLlr(latI1, posNb, kind, alts)
        val a = weight(cls)
        val fused = if (r2 == null) r1.llr else a * r1.llr + (1.0 - a) * r2.llr
        val clipped = max(-30.0, min(30.0, fused))
        val alt = if (r1.alt >= 0) tok.name(r1.alt) else "∅"
        out.add(RawSite(unit, gap, cls, clipped, alt))
    }

    for (k in names.indices) {
        val n = names[k]
        val posLp = idxLp[k]
        if (n in shortOf.values) {
            val others = shortOf.values.filter { it != n }.mapNotNull { tok.id(it) }
            eval("vowel_sub", K_SUB, posLp, k, others, k, false)
            longOf[n]?.let { l -> tok.id(l)?.let { eval("short_to_long", K_SUB, posLp, k, listOf(it), k, false) } }
            eval("vowel_del", K_DEL, posLp, k, emptyList(), k, false)
        } else if (n in shortOf.keys) {
            val others = shortOf.keys.filter { it != n }.mapNotNull { tok.id(it) }
            eval("vowel_sub", K_SUB, posLp, k, others, k, false)
            shortOf[n]?.let { s -> tok.id(s)?.let { eval("long_to_short", K_SUB, posLp, k, listOf(it), k, false) } }
            eval("vowel_del", K_DEL, posLp, k, emptyList(), k, false)
        } else if (isLetter(n)) {
            val self = yNb[k]
            eval("cons_sub", K_SUB, posLp, k, letterIds.filter { it != self }, k, false)
            eval("cons_del", K_DEL, posLp, k, emptyList(), k, false)
        }
    }
    for (g in 0..yNb.size) {
        val posLp = if (g == 0) 0 else idxLp[g - 1] + 1
        if (g >= 1 && isLetter(names[g - 1])) eval("vowel_ins", K_INS, posLp, g, shortIds, g, true)
        eval("insertion", K_INS, posLp, g, letterIds, g, true)
    }
    return out
}

class Site(
    val ayah: Int, val word: Int, val unitName: String, val prevLetter: String?,
    val cls: String, val llr: Double, val alt: String
)

class FinalResult(val sites: List<Site>, val judged: Set<Int>, val info: String = "")

fun judgeTake(
    heads: TajweedHeads, tok: TajweedTokens, ayahUnits: Map<Int, IntArray>,
    expUnits: List<ExpUnit>, cal: TajweedCalibration, ctx: String
): FinalResult? {
    val info = StringBuilder()
    for (n in heads.names) {
        val h = heads[n] ?: continue
        info.appendLine("head $n: kind=${h.kind} frameMs=${h.frameMs} frames=${h.frames} classes=${h.classes} ${h.classNames.take(12)}")
    }
    fun fail(why: String) = FinalResult(emptyList(), emptySet(), info.toString() + "FAIL: $why\n")
    val lpH = heads["log_probs"]?.toMat() ?: return fail("no log_probs head")
    val i1H = heads["inter1_log_probs"]?.toMat()
    if (i1H == null) info.appendLine("inter1_log_probs missing: judging on log_probs only")
    val bar = tok.wordBar
    val toks = heads.decoded().tokens.filter { it.id != bar }
    if (toks.isEmpty()) return fail("no tokens heard")
    if (expUnits.isEmpty()) return fail("no expected units")
    val cells = align(expUnits.map { it.name }, toks.map { it.name })
    if (cells.isEmpty()) return fail("empty alignment")
    info.appendLine("heard=${toks.size} expected=${expUnits.size} cells=${cells.size}")

    val first = HashMap<Int, Int>()
    val total = HashMap<Int, Int>()
    expUnits.forEachIndexed { i, u ->
        if (u.ayah !in first) first[u.ayah] = i
        total[u.ayah] = (total[u.ayah] ?: 0) + 1
    }
    val lastCell = cells.lastOrNull { it.expIdx != null }
    val lastIdx = lastCell?.expIdx ?: return fail("no matched unit")
    val lastAyah = expUnits[lastIdx].ayah

    val active = { cls: String -> cal.status(ctx, cls).let { it != null && it != "off" } }
    val weight = { cls: String -> cal.fusionWeight(ctx, cls) ?: 0.5 }

    val sites = ArrayList<Site>()
    val judged = HashSet<Int>()

    for (a in first.keys.sorted()) {
        if (a > lastAyah) break
        val f0 = first[a]!!
        val mine = cells.filter { c ->
            val idx = (c.expIdx ?: c.after).coerceIn(0, expUnits.size - 1)
            expUnits[idx].ayah == a
        }
        val hIdx = mine.mapNotNull { it.heardIdx }
        if (hIdx.isEmpty()) continue
        val maxExp = mine.mapNotNull { it.expIdx }.maxOrNull() ?: continue
        val nTotal = total[a] ?: continue
        val reachedN = if (a < lastAyah) nTotal else (maxExp - f0 + 1).coerceIn(1, nTotal)

        val full = ayahUnits[a] ?: continue
        val trunc = ArrayList<Int>()
        var cnt = 0
        for (u in full) {
            if (cnt >= reachedN) break
            trunc.add(u)
            if (u != bar) cnt++
        }
        while (trunc.isNotEmpty() && trunc.last() == bar) trunc.removeAt(trunc.size - 1)

        val t0 = toks[hIdx.min()].firstMs - 500
        val t1 = toks[hIdx.max()].lastMs + 500
        val raw = judgeUnits(lpH, i1H, t0, t1, trunc.toIntArray(), tok, active, weight)

        sites.addAll(toSites(raw, expUnits, IntArray(trunc.count { it != bar }) { f0 + it }, null))

        // الكلمات التي اكتملت وحداتها ضمن الجزء المقروء تُعدّ محكومة.
        val lastOfWord = HashMap<Int, Int>()
        for (k in 0 until nTotal) lastOfWord[expUnits[f0 + k].word] = k
        for ((w, lastK) in lastOfWord) if (lastK < reachedN) judged.add(a * 100 + w)
    }
    info.appendLine("ayat judged up to $lastAyah; raw sites=${sites.size}")
    return FinalResult(sites, judged, info.toString())
}

/** يطبّق عتبات مستوى التنبيه المختار على نتائج الحكم المعاير، دون إعادة الحساب. */
fun buildFinal(
    res: FinalResult, cal: TajweedCalibration, ctx: String, preset: String,
    words: List<List<String>>, mism: Set<Int>
): Review {
    val bad = LinkedHashMap<Int, MutableList<Note>>()
    for (s in res.sites.sortedWith(compareBy({ it.ayah }, { it.word }))) {
        val thr = cal.threshold(ctx, s.cls, preset) ?: continue
        val st = cal.status(ctx, s.cls) ?: continue
        if (st == "off") continue
        if (s.llr > thr) {
            val margin = s.llr - thr
            val level = if (margin < 1.5) 1 else if (margin < 5.0) 2 else 3
            bad.getOrPut(s.ayah * 100 + s.word) { ArrayList() }.add(noteFor(s, level, st != "validated"))
        }
    }
    val states = HashMap<Int, WordStatus>()
    for (k in res.judged.sorted()) {
        if (k / 100 in mism) continue
        val r = bad[k]
        states[k] = if (r != null && r.isNotEmpty()) WordStatus(WState.BAD, r) else WordStatus(WState.GOOD, emptyList())
    }
    val issues = ArrayList<Issue>()
    for ((k, r) in bad.entries.sortedBy { it.key }) {
        val a = k / 100
        val w = k % 100
        val text = if (a in mism) "" else (words.getOrNull(a - 1)?.getOrNull(w) ?: "")
        issues.add(Issue(a, w, text, r.distinctBy { it.explain }.sortedByDescending { it.level }))
    }
    val good = states.values.count { it.state == WState.GOOD }
    return Review(states, issues, states.size, good)
}

/** لقطة من المعايرة للتشخيص (لأجل ضبط الأحكام الأخرى: المدّ والقلقلة). */
fun dumpCalibration(cal: TajweedCalibration, ctx: String): String {
    val sb = StringBuilder()
    sb.appendLine("format=${cal.format} model=${cal.modelId}")
    sb.appendLine("ctx=$ctx default=${cal.defaultContext}/${cal.defaultPreset}")
    sb.appendLine("contexts=${cal.contexts} presets=${cal.presets}")
    for (c in cal.classes) {
        sb.appendLine(
            "$c: status=${cal.status(ctx, c)} a=${cal.fusionWeight(ctx, c)} " +
                cal.presets.joinToString(" ") { "$it=${cal.threshold(ctx, c, it)}" }
        )
    }
    for (k in listOf("madd", "qalqalah", "gemination", "ghunna", "tafkheem", "word")) {
        sb.appendLine("$k: ${cal.value("contexts", ctx, k)}".take(700))
    }
    return sb.toString()
}


private fun f1(x: Double) = String.format(java.util.Locale.US, "%.1f", x)

/** تقرير تشخيصي: هل يعمل الحكم؟ وكم موضعاً قيّم؟ وما أعلى قوة دليل لكل صنف مقابل عتباته؟ */
fun judgeReport(res: FinalResult, cal: TajweedCalibration, ctx: String): String {
    val sb = StringBuilder()
    sb.appendLine(res.info)
    sb.appendLine("sites=${res.sites.size} judgedWords=${res.judged.size}")
    for (cls in cal.classes) {
        val ss = res.sites.filter { it.cls == cls }
        if (ss.isEmpty()) {
            sb.appendLine("$cls: لا مواضع (status=${cal.status(ctx, cls)})")
            continue
        }
        val parts = cal.presets.joinToString(" ") { p ->
            val t = cal.threshold(ctx, cls, p)
            if (t == null) "$p:-" else "$p@${f1(t)}=${ss.count { it.llr > t }}"
        }
        sb.appendLine("$cls: n=${ss.size} max=${f1(ss.maxOf { it.llr })} status=${cal.status(ctx, cls)} flagged: $parts")
    }
    sb.appendLine("أعلى 8 مواضع:")
    for (s in res.sites.sortedByDescending { it.llr }.take(8)) {
        sb.appendLine("آية ${s.ayah} كلمة ${s.word} ${s.cls} ${s.unitName}->${s.alt} llr=${f1(s.llr)}")
    }
    return sb.toString()
}


// ───────── ربط المواضع بالكلمات، والحكم المحلي اللحظي ─────────

/** يحوّل مواضع الحكم الخام إلى مواضع على كلمات السورة. gIdx: الوحدة المحلية ← فهرسها العام في المتوقع. */
internal fun toSites(raw: List<RawSite>, exp: List<ExpUnit>, gIdx: IntArray, wordKey: Int?): List<Site> {
    val out = ArrayList<Site>()
    for (r in raw) {
        val k = if (r.gap) max(0, r.unit - 1) else r.unit
        if (k >= gIdx.size) continue
        val gi = gIdx[k]
        if (gi < 0 || gi >= exp.size) continue
        val u = exp[gi]
        if (wordKey != null && u.ayah * 100 + u.word != wordKey) continue
        var prev: String? = null
        if (r.gap) {
            prev = if (r.unit >= 1 && r.unit - 1 < gIdx.size) exp[gIdx[r.unit - 1]].name else null
        } else {
            var j = gi - 1
            while (j >= 0 && exp[j].ayah == u.ayah) {
                val nm = exp[j].name
                if (nm !in VOWELS) { prev = nm; break }
                j--
            }
        }
        out.add(Site(u.ayah, u.word, if (r.gap) "" else u.name, prev, r.cls, r.llr, r.alt))
    }
    return out
}

/** نطاق وحدات كلمة في المتوقع (فهارس عامة). */
class WordRange(val key: Int, val ayah: Int, val word: Int, val first: Int, val last: Int, val ayahLast: Int)

fun buildWordRanges(exp: List<ExpUnit>): List<WordRange> {
    val out = ArrayList<WordRange>()
    val ayahLast = HashMap<Int, Int>()
    exp.forEachIndexed { i, u -> ayahLast[u.ayah] = i }
    var i = 0
    while (i < exp.size) {
        val u = exp[i]
        var j = i
        while (j + 1 < exp.size && exp[j + 1].ayah == u.ayah && exp[j + 1].word == u.word) j++
        out.add(WordRange(u.ayah * 100 + u.word, u.ayah, u.word, i, j, ayahLast[u.ayah] ?: j))
        i = j + 1
    }
    return out
}

/**
 * حكم محلي لكلمة واحدة بمجرد اكتمالها: نافذة من الكلمة السابقة حتى وحدتين بعد الكلمة،
 * فتحسب فروق الاحتمال على الإطارات المحيطة بها فقط. يُعيد null إن لم يكن هناك صوت مسموع لها.
 */
fun judgeWordLocal(
    lp: HeadMat, i1: HeadMat?, tok: TajweedTokens, exp: List<ExpUnit>, cellByExp: Array<Cell?>,
    firstMs: LongArray, lastMs: LongArray, wr: WordRange, prevFirst: Int,
    active: (String) -> Boolean, weight: (String) -> Double
): List<Site>? {
    val from = prevFirst
    val to = min(wr.last + 2, wr.ayahLast)
    var hMin = Int.MAX_VALUE
    var hMax = -1
    for (k in from..to) {
        val h = cellByExp.getOrNull(k)?.heardIdx ?: continue
        if (h < hMin) hMin = h
        if (h > hMax) hMax = h
    }
    if (hMax < 0 || hMax >= firstMs.size) return null
    val ids = ArrayList<Int>()
    val gIdx = ArrayList<Int>()
    var prevWord = -1
    for (k in from..to) {
        val u = exp[k]
        val id = tok.id(u.name) ?: continue
        if (prevWord != -1 && u.word != prevWord) ids.add(tok.wordBar)
        ids.add(id)
        gIdx.add(k)
        prevWord = u.word
    }
    if (gIdx.isEmpty()) return null
    val raw = judgeUnits(lp, i1, firstMs[hMin] - 400, lastMs[hMax] + 400, ids.toIntArray(), tok, active, weight)
    return toSites(raw, exp, gIdx.toIntArray(), wr.key)
}

// ───────── الشرح البسيط للأخطاء ─────────

private fun hk(n: String): String = when (n) {
    "a" -> "فتحة"; "i" -> "كسرة"; "u" -> "ضمة"
    "aa" -> "مدّ بالألف"; "ii" -> "مدّ بالياء"; "uu" -> "مدّ بالواو"
    else -> "حرف «$n»"
}

private fun hkDef(n: String): String = when (n) {
    "a" -> "الفتحة"; "i" -> "الكسرة"; "u" -> "الضمة"
    "aa" -> "المدّ بالألف"; "ii" -> "المدّ بالياء"; "uu" -> "المدّ بالواو"
    else -> "حرف «$n»"
}

private fun vstate(n: String): String = when (n) {
    "a" -> "مفتوحة"; "i" -> "مكسورة"; "u" -> "مضمومة"
    "aa" -> "ممدودة بالألف"; "ii" -> "ممدودة بالياء"; "uu" -> "ممدودة بالواو"
    else -> ""
}

/** ملاحظة كاملة: نوع الخطأ، لماذا، والتصحيح، بلغة بسيطة. */
fun noteFor(s: Site, level: Int, experimental: Boolean): Note {
    val L = s.prevLetter
    val letter = if (L != null) "«$L»" else "الحرف"
    val at = if (L != null) " بعد «$L»" else ""
    val e = s.unitName
    val a = s.alt
    return when (s.cls) {
        "vowel_sub" -> Note(
            "حركة مختلفة",
            "على $letter سُمعت ${hk(a)} بدل ${hkDef(e)} المتوقعة.",
            "الصحيح: انطق $letter ${vstate(e)}.", level, experimental
        )
        "short_to_long" -> Note(
            "مدّ زائد",
            "مددتَ $letter أكثر من اللازم (سُمعت ${hk(a)} بدل ${hkDef(e)}).",
            "الصحيح: انطق $letter ${vstate(e)} دون مدّ.", level, experimental
        )
        "long_to_short" -> Note(
            "مدّ ناقص",
            "لم تُمدّ $letter بما يكفي (سُمعت ${hk(a)} بدل ${hkDef(e)}).",
            "الصحيح: انطق $letter ${vstate(e)} وأطل المدّ حركتين على الأقل.", level, experimental
        )
        "vowel_del" -> Note(
            "حركة ناقصة",
            "لم تُسمع ${hkDef(e)} على $letter.",
            "الصحيح: انطق $letter ${vstate(e)}.", level, experimental
        )
        "cons_del" -> Note(
            "حرف ناقص",
            "لم يُسمع حرف «$e».",
            "الصحيح: انطق حرف «$e» بوضوح.", level, experimental
        )
        "cons_sub" -> Note(
            "حرف مختلف",
            "سُمع حرف أقرب إلى «$a» بدل «$e».",
            "الصحيح: أخرج حرف «$e» من مخرجه.", level, experimental
        )
        "vowel_ins" -> Note(
            "حركة زائدة",
            "سُمعت ${hk(a)} زائدة$at.",
            "الصحيح: لا تُتبع $letter بحركة هنا.", level, experimental
        )
        "insertion" -> Note(
            "صوت زائد",
            "سُمع حرف «$a» زائد$at.",
            "الصحيح: لا تُضف صوتاً$at.", level, experimental
        )
        else -> Note(s.cls, "${s.cls}: ${s.unitName} ← ${s.alt}", "", level, experimental)
    }
}

// ───────── بديل احتياطي: حكم من الأصوات المسموعة وحدها ─────────

private fun siteFromCell(c: Cell, exp: List<ExpUnit>): Site? {
    val idx = (c.expIdx ?: c.after).coerceIn(0, exp.size - 1)
    val u = exp[idx]
    var prev: String? = null
    var j = (if (c.kind == Kind.INS) idx else idx - 1)
    while (j >= 0 && exp[j].ayah == u.ayah) {
        val nm = exp[j].name
        if (nm !in VOWELS) { prev = nm; break }
        j--
    }
    val e = c.exp ?: ""
    val h = c.heard ?: ""
    val cls = when (c.kind) {
        Kind.SUB ->
            if (e in VOWELS && h in VOWELS) {
                if (e[0] == h[0]) (if (e.length < h.length) "short_to_long" else "long_to_short") else "vowel_sub"
            } else "cons_sub"
        Kind.DEL -> if (e in VOWELS) "vowel_del" else "cons_del"
        Kind.INS -> if (h in VOWELS) "vowel_ins" else "insertion"
        Kind.MATCH -> return null
    }
    return Site(u.ayah, u.word, e, prev, cls, 0.0, if (h.isEmpty()) "∅" else h)
}

/** حكم احتياطي (عند غياب المعايرة أو تعذّر مزامنة المخرجات): يقارن الأصوات المسموعة بالمتوقع فقط. */
fun greedyReview(exp: List<ExpUnit>, cells: List<Cell>, settledN: Int, mism: Set<Int>): Review {
    if (exp.isEmpty() || cells.isEmpty()) return Review(emptyMap(), emptyList(), 0, 0)
    val unitsPer = HashMap<Int, Int>()
    exp.forEach { u -> val k = u.ayah * 100 + u.word; unitsPer[k] = (unitsPer[k] ?: 0) + 1 }
    var lastSettledPos = -1
    cells.forEachIndexed { p, c ->
        val h = c.heardIdx
        if (h != null && h < settledN) lastSettledPos = p
    }
    val good = HashMap<Int, Int>()
    val bad = LinkedHashMap<Int, MutableList<Note>>()
    cells.forEachIndexed { p, c ->
        val idx = (c.expIdx ?: c.after).coerceIn(0, exp.size - 1)
        val u = exp[idx]
        val k = u.ayah * 100 + u.word
        val h = c.heardIdx
        val settled = h != null && h < settledN
        if (c.kind == Kind.MATCH) {
            if (settled) good[k] = (good[k] ?: 0) + 1
        } else if (c.kind == Kind.DEL) {
            if (p < lastSettledPos) siteFromCell(c, exp)?.let { bad.getOrPut(k) { ArrayList() }.add(noteFor(it, 1, true)) }
        } else if (settled) {
            siteFromCell(c, exp)?.let { bad.getOrPut(k) { ArrayList() }.add(noteFor(it, 1, true)) }
        }
    }
    val states = HashMap<Int, WordStatus>()
    for (k in unitsPer.keys.sorted()) {
        if (k / 100 in mism) continue
        val r = bad[k]
        if (r != null && r.isNotEmpty()) states[k] = WordStatus(WState.BAD, r.distinctBy { it.explain })
        else if (good[k] == unitsPer[k]) states[k] = WordStatus(WState.GOOD, emptyList())
    }
    val issues = ArrayList<Issue>()
    for ((k, r) in bad.entries.sortedBy { it.key }) {
        if (k / 100 in mism) continue
        issues.add(Issue(k / 100, k % 100, "", r.distinctBy { it.explain }))
    }
    return Review(states, issues, states.size, states.values.count { it.state == WState.GOOD })
}
