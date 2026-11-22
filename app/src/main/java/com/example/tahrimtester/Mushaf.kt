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

/** بعض خطوط الأندرويد القديمة لا تحوي رموز التنوين المفتوح؛ نستبدلها للعرض فقط. */
fun disp(s: String): String =
    s.replace('\u08F0', '\u064B').replace('\u08F1', '\u064C').replace('\u08F2', '\u064D')

fun arabicDigits(n: Int): String = n.toString().map { '\u0660' + (it - '0') }.joinToString("")

data class ExpUnit(val name: String, val ayah: Int, val word: Int)
enum class WState { GOOD, BAD }
data class WordStatus(val state: WState, val reasons: List<String>)
data class Issue(val ayah: Int, val word: String, val reasons: List<String>)

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

private fun letterBefore(exp: List<ExpUnit>, from: Int): String? {
    var k = from
    while (k >= 0) {
        if (k < exp.size && exp[k].name !in VOWELS) return exp[k].name
        k--
    }
    return null
}

/** سبب الاختلاف بجملة عربية قصيرة، بوصفه قراءة التطبيق لما سمعه النموذج. */
private fun describe(c: Cell, exp: List<ExpUnit>): String {
    return when (c.kind) {
        Kind.SUB -> {
            val e = c.exp ?: ""
            val h = c.heard ?: ""
            val letter = letterBefore(exp, (c.expIdx ?: 0) - 1)
            val at = if (letter != null) " بعد «$letter»" else ""
            if (e in VOWELS && h in VOWELS) {
                if (e[0] == h[0]) "المدّ$at: المتوقع ${unitAr(e)} والمسموع ${unitAr(h)}"
                else "الحركة$at: المتوقع ${unitAr(e)} والمسموع ${unitAr(h)}"
            } else "المتوقع ${unitAr(e)} والمسموع ${unitAr(h)}"
        }
        Kind.DEL -> {
            val e = c.exp ?: ""
            val letter = letterBefore(exp, (c.expIdx ?: 0) - 1)
            if (e in VOWELS) "لم تُسمع ${unitAr(e)}" + (if (letter != null) " بعد «$letter»" else "")
            else "لم يُسمع ${unitAr(e)}"
        }
        Kind.INS -> {
            val h = c.heard ?: ""
            val letter = letterBefore(exp, c.after)
            if (h in VOWELS) "سُمعت ${unitAr(h)} زائدة" + (if (letter != null) " بعد «$letter»" else "")
            else "سُمع ${unitAr(h)} زائد"
        }
        Kind.MATCH -> ""
    }
}

/**
 * يحوّل المحاذاة إلى حالة لكل كلمة: أخضر عند تطابق كل وحدات الكلمة المستقرة،
 * أحمر عند أي اختلاف مستقر، وبلا لون ما لم يُقرأ بعد. المفتاح: ayah*100+wordIndex.
 */
fun analyze(
    exp: List<ExpUnit>, cells: List<Cell>, settledN: Int,
    words: List<List<String>>, mism: Set<Int>
): Pair<Map<Int, WordStatus>, List<Issue>> {
    if (exp.isEmpty() || cells.isEmpty()) return emptyMap<Int, WordStatus>() to emptyList()

    val unitsPer = HashMap<Int, Int>()
    exp.forEach { u -> val k = u.ayah * 100 + u.word; unitsPer[k] = (unitsPer[k] ?: 0) + 1 }

    var lastSettledPos = -1
    cells.forEachIndexed { p, c ->
        val h = c.heardIdx
        if (h != null && h < settledN) lastSettledPos = p
    }

    val bad = LinkedHashMap<Int, MutableList<String>>()
    val good = HashMap<Int, Int>()
    cells.forEachIndexed { p, c ->
        val idx = (c.expIdx ?: c.after).coerceIn(0, exp.size - 1)
        val u = exp[idx]
        val k = u.ayah * 100 + u.word
        val h = c.heardIdx
        val settled = h != null && h < settledN
        if (c.kind == Kind.MATCH) {
            if (settled) good[k] = (good[k] ?: 0) + 1
        } else if (c.kind == Kind.DEL) {
            if (p < lastSettledPos) bad.getOrPut(k) { ArrayList() }.add(describe(c, exp))
        } else {
            if (settled) bad.getOrPut(k) { ArrayList() }.add(describe(c, exp))
        }
    }

    val states = HashMap<Int, WordStatus>()
    val issues = ArrayList<Issue>()
    for (k in unitsPer.keys.sorted()) {
        val a = k / 100
        val w = k % 100
        val r = bad[k]
        if (r != null && r.isNotEmpty()) {
            val reasons = r.distinct()
            if (a !in mism) {
                states[k] = WordStatus(WState.BAD, reasons)
                issues.add(Issue(a, words.getOrNull(a - 1)?.getOrNull(w) ?: "", reasons))
            } else {
                issues.add(Issue(a, "", reasons))
            }
        } else if (a !in mism && good[k] == unitsPer[k]) {
            states[k] = WordStatus(WState.GOOD, emptyList())
        }
    }
    return states to issues
}
