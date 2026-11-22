package com.example.tahrimtester

enum class Kind { MATCH, SUB, DEL, INS }

/**
 * خلية محاذاة. expIdx: موضع الوحدة في المتوقع (null للزائد)، heardIdx: موضعها في المسموع (null للناقص)،
 * after: آخر وحدة متوقعة استُهلكت حتى هذه الخلية (تُستعمل لربط الزائد بكلمته).
 */
data class Cell(
    val exp: String?, val heard: String?, val kind: Kind,
    val expIdx: Int?, val heardIdx: Int?, val after: Int
)

/**
 * محاذاة بمسافة التحرير بين المسموع والمتوقع. نهاية المتوقع حرّة (ما لم يُقرأ بعد لا يُحسب نقصاً).
 * فروق محتملة بين نصّين، وليست حكماً على التلاوة.
 */
fun align(expected: List<String>, heard: List<String>): List<Cell> {
    val n = heard.size
    val m = expected.size
    if (n == 0 || m == 0) return emptyList()
    val w = m + 1
    val d = IntArray((n + 1) * w)
    for (j in 0..m) d[j] = j
    for (i in 1..n) {
        d[i * w] = i
        for (j in 1..m) {
            val sub = d[(i - 1) * w + (j - 1)] + if (heard[i - 1] == expected[j - 1]) 0 else 1
            val ins = d[(i - 1) * w + j] + 1
            val del = d[i * w + (j - 1)] + 1
            d[i * w + j] = minOf(sub, ins, del)
        }
    }
    var bestJ = 0
    for (j in 1..m) if (d[n * w + j] < d[n * w + bestJ]) bestJ = j

    val out = ArrayList<Cell>()
    var i = n
    var j = bestJ
    while (i > 0 || j > 0) {
        if (i > 0 && j > 0) {
            val same = heard[i - 1] == expected[j - 1]
            if (d[i * w + j] == d[(i - 1) * w + (j - 1)] + (if (same) 0 else 1)) {
                out.add(Cell(expected[j - 1], heard[i - 1], if (same) Kind.MATCH else Kind.SUB, j - 1, i - 1, j - 1))
                i--; j--; continue
            }
        }
        if (i > 0 && d[i * w + j] == d[(i - 1) * w + j] + 1) {
            out.add(Cell(null, heard[i - 1], Kind.INS, null, i - 1, j - 1)); i--
        } else {
            out.add(Cell(expected[j - 1], null, Kind.DEL, j - 1, null, j - 1)); j--
        }
    }
    out.reverse()
    return out
}
