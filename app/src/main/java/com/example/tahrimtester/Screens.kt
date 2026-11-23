package com.example.tahrimtester

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private fun presetLabel(p: String) = when (p) {
    "strict" -> "قليل التنبيهات"
    "balanced" -> "متوازن"
    "sensitive" -> "كثير التنبيهات"
    "very_sensitive" -> "أقصى تنبيه"
    else -> p
}

@Composable
fun TahrimRoot(r: Reciter) {
    TahrimTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Crossfade(targetState = r.phase == Phase.LOADING, label = "root") { loading ->
                    if (loading) SplashScreen(r) else ReaderScreen(r)
                }
            }
        }
    }
}

// ───────── شاشة التحميل (أول تشغيل) ─────────

@Composable
fun SplashScreen(r: Reciter) {
    val q = LocalQ.current
    var showDetail by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        StarOrnament(q.gold, MaterialTheme.colorScheme.background, Modifier.size(96.dp))
        Spacer(Modifier.height(20.dp))
        Text("سورة التحريم", fontFamily = QuranFont, fontSize = 40.sp, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(10.dp))
        if (r.loadError.isEmpty()) {
            Text(
                "نُجهّز مُعلِّمك الصوتي على جهازك",
                fontSize = 17.sp, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(26.dp))
            if (r.dlTotal > 0L) {
                LinearProgressIndicator(
                    progress = { (r.dlDone.toFloat() / r.dlTotal.toFloat()).coerceIn(0f, 1f) },
                    color = q.gold, trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${arabicDigits((r.dlDone / 1_000_000L).toInt())} / ${arabicDigits((r.dlTotal / 1_000_000L).toInt())} م.ب",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LinearProgressIndicator(
                    color = q.gold, trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Spacer(Modifier.height(8.dp))
                Text("جارٍ الاتصال…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(22.dp))
            Text(
                "يحدث هذا مرة واحدة فقط. بعدها تعمل التلاوة على جهازك دون إنترنت.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
            )
        } else {
            Text(
                r.loadError, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = { r.retryLoad() }) { Text("إعادة المحاولة") }
            TextButton(onClick = { showDetail = !showDetail }) { Text(if (showDetail) "إخفاء التفاصيل" else "التفاصيل") }
            if (showDetail) {
                Text(
                    r.loadDetail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ───────── شاشة القراءة ─────────

@Composable
private fun OrnamentDivider() {
    val q = LocalQ.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(q.gold.copy(alpha = 0.5f))
        )
        StarOrnament(q.gold, MaterialTheme.colorScheme.background, Modifier.size(26.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(q.gold.copy(alpha = 0.5f))
        )
    }
}

@Composable
private fun TopBar(onSettings: () -> Unit, onReport: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier
                .size(width = 60.dp, height = 52.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable { onReport() },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            ReportIcon(MaterialTheme.colorScheme.primary, Modifier.size(26.dp))
            Text("التقرير", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("سورة التحريم", fontFamily = QuranFont, fontSize = 28.sp, color = MaterialTheme.colorScheme.onBackground)
            Text("مدنية · ١٢ آية", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onSettings, modifier = Modifier.size(width = 60.dp, height = 52.dp)) {
            TuneIcon(MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(26.dp))
        }
    }
}

@Composable
fun ReaderScreen(r: Reciter) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showResults by rememberSaveable { mutableStateOf(false) }
    var showReport by rememberSaveable { mutableStateOf(false) }
    var dialogKey by remember { mutableIntStateOf(-1) }
    var showIntro by remember { mutableStateOf(!r.seenIntro) }
    var askMic by remember { mutableStateOf(false) }
    var focusKey by remember { mutableIntStateOf(-1) }
    val review = r.review
    val listening = r.phase == Phase.LISTENING

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) r.start() else r.notify("لم يُسمح بالميكروفون. فعّله من إعدادات التطبيق ثم أعد المحاولة.")
    }

    fun onMicTap() {
        when (r.phase) {
            Phase.LISTENING -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); r.stop() }
            Phase.READY, Phase.DONE -> {
                val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); r.start() }
                else askMic = true
            }
            else -> {}
        }
    }

    // لا تنطفئ الشاشة أثناء التلاوة والتحليل.
    DisposableEffect(r.phase) {
        view.keepScreenOn = r.phase == Phase.LISTENING || r.phase == Phase.ANALYZING
        onDispose { view.keepScreenOn = false }
    }

    // تتبّع الآية الجارية أثناء التلاوة.
    val curAyah = if (r.cursor >= 0) r.cursor / 100 else -1
    LaunchedEffect(curAyah, listening) {
        if (listening && curAyah in 1..r.ayahCount) listState.animateScrollToItem(curAyah)
    }

    // الانتقال إلى كلمة اختارها المستخدم من الملاحظات.
    LaunchedEffect(focusKey) {
        if (focusKey >= 0) {
            val a = focusKey / 100
            if (a in 1..r.ayahCount) listState.animateScrollToItem(a)
            delay(2600)
            focusKey = -1
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(onSettings = { showSettings = true }, onReport = { showReport = true })
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 230.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item { OrnamentDivider() }
                itemsIndexed(r.mushaf) { idx, words ->
                    val a = idx + 1
                    AyahBlock(
                        ayah = a, words = words, selected = a == r.startAyah,
                        canSelect = r.phase == Phase.READY || r.phase == Phase.DONE, listening = listening,
                        textSp = r.textSp, liveGood = emptySet(), cursor = r.cursor, review = review,
                        focusKey = focusKey, mismatch = a in r.mism,
                        onSelect = { r.chooseAyah(a) },
                        onWordTap = { key -> dialogKey = key }
                    )
                }
            }
        }
        RecordDock(
            r = r, onTap = { onMicTap() }, onSummary = { showResults = true },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (showResults && r.phase == Phase.DONE) {
        ResultsSheet(
            r = r, onDismiss = { showResults = false },
            onFocus = { key -> focusKey = key; showResults = false }
        )
    }
    if (showSettings) SettingsSheet(r = r, onDismiss = { showSettings = false })
    if (showReport) ReportSheet(r = r, onDismiss = { showReport = false })
    val dialogIssue = if (dialogKey >= 0) review?.issues?.firstOrNull { it.ayah * 100 + it.word == dialogKey } else null
    LaunchedEffect(dialogIssue == null, dialogKey) {
        if (dialogKey >= 0 && dialogIssue == null) dialogKey = -1
    }
    if (dialogIssue != null) WordDialog(dialogIssue, onDismiss = { dialogKey = -1 })

    if (showIntro) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("كيف تعمل؟") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("١. اضغط على الآية التي تريد أن تبدأ منها.")
                    Text("٢. اضغط الميكروفون واقرأ بصوت واضح في مكان هادئ.")
                    Text("٣. نُظلّل بالأخضر ما وافق المتوقع أثناء قراءتك، وبعد انتهائك ننبّهك إلى ما يستحق المراجعة مع السبب.")
                    Text(
                        "التقييم آلي وقد يخطئ، ولا يغني عن معلّم مؤهَّل.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { r.markIntroSeen(); showIntro = false }) { Text("ابدأ") }
            }
        )
    }
    if (askMic) {
        AlertDialog(
            onDismissRequest = { askMic = false },
            title = { Text("نحتاج الميكروفون") },
            text = { Text("لنسمع تلاوتك. تُعالَج تلاوتك على جهازك نفسه.") },
            confirmButton = {
                TextButton(onClick = {
                    askMic = false
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("السماح") }
            },
            dismissButton = { TextButton(onClick = { askMic = false }) { Text("لاحقاً") } }
        )
    }
}

// ───────── ملاحظات التلاوة ─────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ResultsSheet(r: Reciter, onDismiss: () -> Unit, onFocus: (Int) -> Unit) {
    val q = LocalQ.current
    val review = r.review
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("ملاحظات تلاوتك", fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground)

            if (review == null) {
                Text(
                    "لم نتمكن من تحليل التلاوة بدقة هذه المرة. جرّب مرة أخرى في مكان هادئ وبصوت واضح، وبدفعة تلاوة أطول قليلاً.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val frac = if (review.judged == 0) 0f else review.good.toFloat() / review.judged.toFloat()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                        Ring(frac, q.good, MaterialTheme.colorScheme.surfaceVariant, Modifier.size(76.dp))
                        Text(arabicDigits(review.good), fontSize = 22.sp, color = q.good)
                    }
                    Column {
                        Text(
                            "${arabicDigits(review.good)} من ${arabicDigits(review.judged)} كلمة وافقت المتوقع",
                            fontSize = 17.sp, color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            if (review.issues.isEmpty()) "لم نرصد ما يحتاج مراجعة في هذه القراءة."
                            else "${arabicDigits(review.issues.size)} للمراجعة أدناه.",
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (r.presetList.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("مستوى التنبيه", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        r.presetList.forEach { p ->
                            FilterChip(selected = p == r.preset, onClick = { r.choosePreset(p) }, label = { Text(presetLabel(p)) })
                        }
                    }
                    Text(
                        "كلما زادت التنبيهات ظهرت ملاحظات أكثر، وقد يكون بعضها غير دقيق.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (review != null) {
                review.issues.forEach { issue ->
                    val key = issue.ayah * 100 + issue.word
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable(enabled = issue.text.isNotEmpty()) { onFocus(key) }
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (issue.text.isNotEmpty()) {
                                Text(issue.text, fontFamily = QuranFont, fontSize = 28.sp, color = q.review)
                            }
                            Text("الآية ${arabicDigits(issue.ayah)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        issue.notes.forEach { n ->
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    for (i in 1..3) {
                                        Box(
                                            Modifier
                                                .size(7.dp)
                                                .background(if (i <= n.level) q.review else q.review.copy(alpha = 0.2f), CircleShape)
                                        )
                                    }
                                }
                                Text(
                                    n.type + " — " + n.explain + if (n.experimental) " (تجريبي)" else "",
                                    fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }
                }
                if (review.issues.isNotEmpty()) {
                    Text(
                        "النقاط الثلاث تدلّ على قوة الدليل الآلي. اضغط أي بطاقة لتنتقل إلى الكلمة.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "التقييم آلي وقد يخطئ، ولا يغني عن معلّم مؤهَّل. لا يُعدّ حكماً شرعياً على تلاوتك.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ───────── الإعدادات وأدوات المطوّر ─────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(r: Reciter, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text("الإعدادات", fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("حجم الخط", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = r.textSp, onValueChange = { r.chooseTextSp(it) }, valueRange = 22f..44f)
                Text(
                    "بِسۡمِ ٱللَّهِ", fontFamily = QuranFont, fontSize = r.textSp.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            if (r.presetList.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("مستوى التنبيه", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        r.presetList.forEach { p ->
                            FilterChip(selected = p == r.preset, onClick = { r.choosePreset(p) }, label = { Text(presetLabel(p)) })
                        }
                    }
                }
            } else {
                Text(
                    "يظهر خيار مستوى التنبيه بعد أول تلاوة.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "النموذج: TajweedFormer V4 من Quran Lab (ترخيص NPL-1.2). خط المصحف: Scheherazade New (SIL OFL).",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


// ───────── نافذة «لماذا أخطأت هنا؟» ─────────

@Composable
private fun LevelDots(level: Int, color: androidx.compose.ui.graphics.Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..3) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(if (i <= level) color else color.copy(alpha = 0.2f), CircleShape)
            )
        }
    }
}

@Composable
fun WordDialog(issue: Issue, onDismiss: () -> Unit) {
    val q = LocalQ.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("لماذا أخطأت هنا؟") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (issue.text.isNotEmpty()) {
                        Text(issue.text, fontFamily = QuranFont, fontSize = 34.sp, color = q.review)
                    }
                    Text("الآية ${arabicDigits(issue.ayah)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                issue.notes.forEach { n ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(q.reviewBg.copy(alpha = 0.5f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LevelDots(n.level, q.review)
                            Text(
                                n.type + if (n.experimental) " · تجريبي" else "",
                                fontSize = 16.sp, color = q.review
                            )
                        }
                        Text(n.explain, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
                        if (n.fix.isNotEmpty()) Text(n.fix, fontSize = 15.sp, color = q.good)
                    }
                }
                Text(
                    "قراءة آلية وقد تخطئ، ولا تغني عن معلّم مؤهَّل.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("فهمت") } }
    )
}

// ───────── نافذة التقرير الموحّدة ─────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(r: Reciter, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(r.phase, r.bench, r.benchRunning) { r.refreshReport() }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2200)
            copied = false
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("تقرير الاختبار والتشخيص", fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "كل بيانات الاختبار والتشخيص في مكان واحد. اضغط «نسخ» ثم الصق التقرير في المحادثة.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(r.reportText))
                    copied = true
                },
                enabled = r.reportText.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(if (copied) "تم النسخ ✓" else "نسخ التقرير بالكامل", fontSize = 17.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { r.refreshReport() }) { Text("تحديث") }
                OutlinedButton(
                    onClick = { r.runBench() },
                    enabled = !r.benchRunning && (r.phase == Phase.READY || r.phase == Phase.DONE)
                ) { Text("قياس سرعة الهاتف") }
            }
            Text(
                r.reportText.ifBlank { "جارٍ تجهيز التقرير…" },
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
