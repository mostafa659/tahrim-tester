package com.example.tahrimtester

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ───────── أيقونات مرسومة (بلا اعتماد على مكتبة أيقونات) ─────────

@Composable
fun MicIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color, topLeft = Offset(w * 0.36f, h * 0.10f), size = Size(w * 0.28f, h * 0.46f),
            cornerRadius = CornerRadius(w * 0.14f, w * 0.14f)
        )
        drawArc(
            color, startAngle = 0f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(w * 0.24f, h * 0.30f), size = Size(w * 0.52f, h * 0.42f),
            style = Stroke(width = w * 0.07f, cap = StrokeCap.Round)
        )
        drawLine(color, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.5f, h * 0.88f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.38f, h * 0.88f), Offset(w * 0.62f, h * 0.88f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
    }
}

@Composable
fun StopIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.width * 0.42f
        drawRoundRect(
            color, topLeft = Offset((size.width - s) / 2f, (size.height - s) / 2f), size = Size(s, s),
            cornerRadius = CornerRadius(s * 0.22f, s * 0.22f)
        )
    }
}

@Composable
fun TuneIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.07f
        val ys = listOf(0.28f, 0.50f, 0.72f)
        val ks = listOf(0.66f, 0.34f, 0.60f)
        for (i in 0..2) {
            val y = h * ys[i]
            drawLine(color, Offset(w * 0.18f, y), Offset(w * 0.82f, y), strokeWidth = sw, cap = StrokeCap.Round)
            drawCircle(color, radius = w * 0.085f, center = Offset(w * ks[i], y))
        }
    }
}

@Composable
fun ReportIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color, topLeft = Offset(w * 0.2f, h * 0.14f), size = Size(w * 0.6f, h * 0.78f),
            cornerRadius = CornerRadius(w * 0.08f, w * 0.08f), style = Stroke(width = w * 0.07f)
        )
        drawRoundRect(
            color, topLeft = Offset(w * 0.36f, h * 0.05f), size = Size(w * 0.28f, h * 0.15f),
            cornerRadius = CornerRadius(w * 0.05f, w * 0.05f)
        )
        for (y in listOf(0.42f, 0.58f, 0.74f)) {
            drawLine(color, Offset(w * 0.32f, h * y), Offset(w * 0.68f, h * y), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
        }
    }
}

/** نجمة «ربع الحزب»: مربعان متداخلان وفي وسطهما دائرة. */
@Composable
fun StarOrnament(color: Color, hole: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.width * 0.52f
        val tl = Offset((size.width - s) / 2f, (size.height - s) / 2f)
        drawRect(color, topLeft = tl, size = Size(s, s))
        rotate(45f) { drawRect(color, topLeft = tl, size = Size(s, s)) }
        drawCircle(hole, radius = size.width * 0.12f, center = center)
    }
}

/** حلقة تقدّم: نسبة الكلمات السليمة. */
@Composable
fun Ring(fraction: Float, color: Color, track: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val sw = size.width * 0.12f
        val inset = sw / 2f
        val sz = Size(size.width - sw, size.height - sw)
        drawArc(track, 0f, 360f, false, topLeft = Offset(inset, inset), size = sz, style = Stroke(sw))
        if (fraction > 0f) {
            drawArc(
                color, -90f, 360f * fraction.coerceIn(0f, 1f), false,
                topLeft = Offset(inset, inset), size = sz, style = Stroke(sw, cap = StrokeCap.Round)
            )
        }
    }
}

// ───────── علامة نهاية الآية ─────────

@Composable
fun AyahMarker(n: Int, modifier: Modifier = Modifier) {
    val q = LocalQ.current
    Box(
        modifier
            .size(32.dp)
            .border(1.5.dp, q.gold, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(arabicDigits(n), color = q.gold, fontSize = 13.sp, fontFamily = QuranFont)
    }
}

// ───────── كتلة الآية ─────────

private enum class WStyle { NONE, GOOD, REVIEW }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AyahBlock(
    ayah: Int, words: List<String>, selected: Boolean, canSelect: Boolean, listening: Boolean,
    textSp: Float, liveGood: Set<Int>, cursor: Int, review: Review?, focusKey: Int, mismatch: Boolean,
    onSelect: () -> Unit, onWordTap: (Int) -> Unit
) {
    val q = LocalQ.current
    val ink = MaterialTheme.colorScheme.onBackground
    val showSel = selected && canSelect
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (showSel) q.goldBg.copy(alpha = 0.55f) else Color.Transparent)
            .drawBehind {
                if (showSel) {
                    val bw = 4.dp.toPx()
                    drawRoundRect(
                        q.gold, topLeft = Offset(size.width - bw, 10.dp.toPx()),
                        size = Size(bw, size.height - 20.dp.toPx()), cornerRadius = CornerRadius(bw, bw)
                    )
                }
            }
            .clickable(enabled = canSelect) { onSelect() }
            .padding(start = 14.dp, end = 18.dp, top = 8.dp, bottom = 8.dp)
    ) {
        if (showSel) {
            Text("نبدأ من هنا", color = q.gold, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            words.forEachIndexed { w, word ->
                val key = ayah * 100 + w
                val st = review?.states?.get(key)
                val style = when {
                    st != null && st.state == WState.BAD -> WStyle.REVIEW
                    st != null && st.state == WState.GOOD -> WStyle.GOOD
                    review == null && key in liveGood -> WStyle.GOOD
                    else -> WStyle.NONE
                }
                val isCursor = listening && key == cursor && style == WStyle.NONE
                val isFocus = key == focusKey
                val fg = when (style) {
                    WStyle.GOOD -> q.good
                    WStyle.REVIEW -> q.review
                    WStyle.NONE -> ink
                }
                val bg = when {
                    isFocus -> q.goldBg
                    style == WStyle.REVIEW -> q.reviewBg
                    style == WStyle.GOOD -> q.goodBg.copy(alpha = 0.6f)
                    isCursor -> q.goldBg
                    else -> Color.Transparent
                }
                var m: Modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                if (isFocus) m = m.border(1.5.dp, q.gold, RoundedCornerShape(8.dp))
                if (style == WStyle.REVIEW) {
                    m = m.drawBehind {
                        val y = size.height - 6.dp.toPx()
                        drawLine(q.review, Offset(4.dp.toPx(), y), Offset(size.width - 4.dp.toPx(), y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    }.clickable { onWordTap(key) }
                }
                Text(
                    text = word,
                    fontFamily = QuranFont,
                    fontSize = textSp.sp,
                    lineHeight = (textSp * 2.0f).sp,
                    color = fg,
                    modifier = m.padding(horizontal = 4.dp)
                )
            }
            AyahMarker(ayah, Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        }
        if (mismatch) {
            Text(
                "تعذّر ربط كلمات هذه الآية بالمرجع، فلن تُلوَّن كلماتها.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
            )
        }
    }
}

// ───────── شريط التسجيل السفلي ─────────

@Composable
private fun PulseRing(fast: Boolean, color: Color) {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (fast) 900 else 1700, easing = LinearEasing), RepeatMode.Restart),
        label = "p"
    )
    Box(
        Modifier
            .size((84f + 44f * p).dp)
            .background(color.copy(alpha = 0.22f * (1f - p)), CircleShape)
    )
}

@Composable
fun RecordDock(r: Reciter, onTap: () -> Unit, onSummary: () -> Unit, modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    val q = LocalQ.current
    val listening = r.phase == Phase.LISTENING
    val analyzing = r.phase == Phase.ANALYZING
    val review = r.review
    val message: String = when {
        r.status.isNotBlank() -> r.status
        listening -> if (r.speaking) "نسمعك…" else "أنصت إليك… ابدأ التلاوة من الآية ${arabicDigits(r.startAyah)}"
        analyzing -> "نراجع تلاوتك…"
        r.phase == Phase.DONE ->
            if (review != null && review.issues.isNotEmpty()) "انتهت الجلسة"
            else "انتهت الجلسة. اضغط الميكروفون لتبدأ من جديد."
        else -> "اضغط الميكروفون وابدأ التلاوة من الآية ${arabicDigits(r.startAyah)}"
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, bg.copy(alpha = 0.96f), bg)))
            .padding(top = 28.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (r.phase == Phase.DONE && review != null) {
            val n = review.issues.size
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (n == 0) q.goodBg else q.reviewBg)
                    .clickable { onSummary() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (n == 0) "لا ملاحظات · عرض الملخص" else "${arabicDigits(n)} ${if (n == 1) "كلمة" else "كلمات"} للمراجعة · عرض",
                    color = if (n == 0) q.good else q.review, fontSize = 14.sp
                )
            }
            Box(Modifier.height(8.dp))
        }
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Box(Modifier.height(10.dp))
        Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
            if (listening) PulseRing(fast = r.speaking, color = MaterialTheme.colorScheme.primary)
            val btnColor = if (listening) q.review else MaterialTheme.colorScheme.primary
            Box(
                Modifier
                    .size(84.dp)
                    .shadow(10.dp, CircleShape)
                    .clip(CircleShape)
                    .background(btnColor)
                    .semantics { contentDescription = if (listening) "إيقاف التسجيل" else "بدء التسجيل" }
                    .clickable(enabled = !analyzing) { onTap() },
                contentAlignment = Alignment.Center
            ) {
                when {
                    analyzing -> CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp, modifier = Modifier.size(34.dp)
                    )
                    listening -> StopIcon(Color.White, Modifier.size(40.dp))
                    else -> MicIcon(MaterialTheme.colorScheme.onPrimary, Modifier.size(40.dp))
                }
            }
        }
    }
}
