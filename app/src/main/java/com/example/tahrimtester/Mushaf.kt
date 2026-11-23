package com.example.tahrimtester

import android.content.Context

/** نص السورة من assets/tahrim.txt: سطر لكل آية، والكلمات مفصولة بمسافة. */
fun loadMushaf(ctx: Context): List<List<String>> = try {
    ctx.assets.open("tahrim.txt").bufferedReader().use { it.readLines() }
        .map { it.trim() }.filter { it.isNotEmpty() }
        .map { line -> line.split(Regex("\\s+")).filter { it.isNotEmpty() } }
} catch (e: Throwable) {
    emptyList()
}

fun arabicDigits(n: Int): String = n.toString().map { '\u0660' + (it - '0') }.joinToString("")

data class ExpUnit(val name: String, val ayah: Int, val word: Int)

enum class WState { GOOD, BAD }

/** ملاحظة على كلمة: نوع الخطأ، لماذا، التصحيح، ومستوى الثقة (1 احتمال، 2 مرجّح، 3 قوي). */
data class Note(val type: String, val explain: String, val fix: String, val level: Int, val experimental: Boolean)

data class WordStatus(val state: WState, val notes: List<Note>)

data class Issue(val ayah: Int, val word: Int, val text: String, val notes: List<Note>)

data class Review(val states: Map<Int, WordStatus>, val issues: List<Issue>, val judged: Int, val good: Int)

val VOWELS = setOf("a", "i", "u", "aa", "ii", "uu")

fun vowelAr(n: String): String? = when (n) {
    "a" -> "فتحة"
    "i" -> "كسرة"
    "u" -> "ضمة"
    "aa" -> "مدّ بالألف"
    "ii" -> "مدّ بالياء"
    "uu" -> "مدّ بالواو"
    else -> null
}

fun unitAr(n: String): String = vowelAr(n) ?: "حرف «$n»"

/** نتيجة التحليل اللحظي: الكلمات المطابقة بثقة، ومكان القارئ الآن (مفتاح الكلمة = آية*100+رقمها). */
class LiveView(val good: Set<Int>, val cursor: Int)

/**
 * تحليل لحظي خفيف: لا يُظهر إلا ما تطابق مع المتوقع (أخضر)، ولا يحكم بالخطأ.
 * الأحكام بالخطأ تأتي فقط من الحكم المعاير بعد انتهاء التلاوة.
 */
fun liveAnalyze(exp: List<ExpUnit>, cells: List<Cell>, settledN: Int, mism: Set<Int>): LiveView {
    if (exp.isEmpty() || cells.isEmpty()) return LiveView(emptySet(), -1)
    val unitsPer = HashMap<Int, Int>()
    exp.forEach { u -> val k = u.ayah * 100 + u.word; unitsPer[k] = (unitsPer[k] ?: 0) + 1 }
    val good = HashMap<Int, Int>()
    val bad = HashSet<Int>()
    var lastExp = -1
    for (c in cells) {
        val ei = c.expIdx
        if (ei != null && ei > lastExp) lastExp = ei
        val idx = (ei ?: c.after).coerceIn(0, exp.size - 1)
        val u = exp[idx]
        val k = u.ayah * 100 + u.word
        val h = c.heardIdx
        if (h == null || h >= settledN) continue
        if (c.kind == Kind.MATCH) good[k] = (good[k] ?: 0) + 1 else bad.add(k)
    }
    val goodSet = HashSet<Int>()
    for ((k, n) in good) if (n == unitsPer[k] && k !in bad && k / 100 !in mism) goodSet.add(k)
    var cursor = -1
    if (lastExp >= 0) {
        val kc = exp[lastExp].ayah * 100 + exp[lastExp].word
        cursor = kc
        if (kc in goodSet) {
            var j = lastExp + 1
            while (j < exp.size && exp[j].ayah * 100 + exp[j].word == kc) j++
            if (j < exp.size) cursor = exp[j].ayah * 100 + exp[j].word
        }
    }
    return LiveView(goodSet, cursor)
}
