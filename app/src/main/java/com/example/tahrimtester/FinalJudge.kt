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

private class Emit(val t: Int, val c: Int, val v: FloatArray)

private fun slice(h: TajweedHead, f0: Int, f1: Int, mergeBar: Boolean, blank: Int, bar: Int): Emit {
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

private class RawSite(val unit: Int, val gap: Boolean, val cls: String, val llr: Double, val alt: String)

private fun isLetter(n: String) = n.length == 1 && n[0] in '\u0621'..'\u064A'

private fun judgeUnits(
    lpH: TajweedHead, i1H: TajweedHead?, t0Ms: Long, t1Ms: Long, units: IntArray, tok: TajweedTokens,
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
    fun frames(h: TajweedHead): IntArray {
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
    val lpH = heads["log_probs"] ?: return fail("no log_probs head")
    val i1H = heads["inter1_log_probs"]
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

        for (r in raw) {
            val k = if (r.gap) max(0, r.unit - 1) else r.unit
            if (f0 + k >= expUnits.size) continue
            val u = expUnits[f0 + k]
            var prev: String? = null
            if (r.gap) {
                prev = if (r.unit >= 1) expUnits[f0 + r.unit - 1].name else null
            } else {
                var j = k - 1
                while (j >= 0) {
                    val nm = expUnits[f0 + j].name
                    if (nm !in VOWELS) { prev = nm; break }
                    j--
                }
            }
            sites.add(Site(a, u.word, if (r.gap) "" else u.name, prev, r.cls, r.llr, r.alt))
        }

        // الكلمات التي اكتملت وحداتها ضمن الجزء المقروء تُعدّ محكومة.
        val lastOfWord = HashMap<Int, Int>()
        for (k in 0 until nTotal) lastOfWord[expUnits[f0 + k].word] = k
        for ((w, lastK) in lastOfWord) if (lastK < reachedN) judged.add(a * 100 + w)
    }
    info.appendLine("ayat judged up to $lastAyah; raw sites=${sites.size}")
    return FinalResult(sites, judged, info.toString())
}

fun describeSite(s: Site, status: String): String {
    val at = if (s.prevLetter != null) " بعد «${s.prevLetter}»" else ""
    val core = when (s.cls) {
        "vowel_sub" -> "الحركة$at: المتوقع ${unitAr(s.unitName)} والأقرب لما سُمع ${unitAr(s.alt)}"
        "short_to_long" -> "مدّ زائد$at: المتوقع ${unitAr(s.unitName)} والمسموع أقرب إلى ${unitAr(s.alt)}"
        "long_to_short" -> "مدّ ناقص$at: المتوقع ${unitAr(s.unitName)} والمسموع أقرب إلى ${unitAr(s.alt)}"
        "vowel_del" -> "لم تُسمع ${unitAr(s.unitName)}$at"
        "cons_del" -> "لم يُسمع ${unitAr(s.unitName)}"
        "cons_sub" -> "المتوقع ${unitAr(s.unitName)} والأقرب لما سُمع ${unitAr(s.alt)}"
        "vowel_ins" -> "سُمعت ${unitAr(s.alt)} زائدة$at"
        "insertion" -> "سُمع ${unitAr(s.alt)} زائد$at"
        else -> "${s.cls}: ${s.unitName} ← ${s.alt}"
    }
    val tag = when (status) {
        "experimental" -> " (تجريبي)"
        "unverified" -> " (غير مؤكد)"
        else -> ""
    }
    return core + tag + " · قوة الدليل " + String.format(java.util.Locale.US, "%.1f", s.llr)
}

/** يطبّق عتبات الحساسية المختارة على نتائج الحكم المعاير، دون إعادة الحساب. */
fun buildFinal(
    res: FinalResult, cal: TajweedCalibration, ctx: String, preset: String,
    words: List<List<String>>, mism: Set<Int>
): Pair<Map<Int, WordStatus>, List<Issue>> {
    val bad = LinkedHashMap<Int, MutableList<String>>()
    for (s in res.sites.sortedWith(compareBy({ it.ayah }, { it.word }))) {
        val thr = cal.threshold(ctx, s.cls, preset) ?: continue
        val st = cal.status(ctx, s.cls) ?: continue
        if (st == "off") continue
        if (s.llr > thr) bad.getOrPut(s.ayah * 100 + s.word) { ArrayList() }.add(describeSite(s, st))
    }
    val states = HashMap<Int, WordStatus>()
    val issues = ArrayList<Issue>()
    for (k in res.judged.sorted()) {
        val a = k / 100
        if (a in mism) continue
        val r = bad[k]
        states[k] = if (r != null && r.isNotEmpty()) WordStatus(WState.BAD, r) else WordStatus(WState.GOOD, emptyList())
    }
    for ((k, r) in bad.entries.sortedBy { it.key }) {
        val a = k / 100
        val w = k % 100
        issues.add(Issue(a, if (a in mism) "" else (words.getOrNull(a - 1)?.getOrNull(w) ?: ""), r.distinct()))
    }
    return states to issues
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
