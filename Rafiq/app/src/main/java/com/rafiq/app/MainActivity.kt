package com.rafiq.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.core.content.ContextCompat

enum class Phase { SETUP, GUIDED, RECITE, RESULT }
enum class WS { PENDING, OK, WRONG, HINT }

const val VERSE = "اللَّهُ لَا إِلَٰهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ"
val WORDS = VERSE.split(" ")
// الشيخ المنشاوي - آية الكرسي (2:255)
const val AUDIO_URL = "https://everyayah.com/data/Minshawy_Murattal_128kbps/002255.mp3"

val TIPS = mapOf(
    0 to "انتبه! لم تضبط الشدة على اللام وضمة الهاء في لفظ الجلالة، وراعِ تفخيم اللام بعد الضمة.",
    1 to "انتبه! أخطأت في مدّ الألف في «لا» (مدّ أصلي بمقدار حركتين) أو في فتحة اللام.",
    2 to "انتبه! أخطأت في كسرة الهمزة أو فتحة اللام والهاء، ولم تمدّ الألف الخنجرية في «إلٰه» حقها.",
    3 to "انتبه! لم تمنح الحرف المشدد (اللام) حقه الصوتي، أو أخطأت في حركة الهمزة.",
    4 to "انتبه! أخطأت في ضمّ الهاء أو في مدّ الواو (صلة) في «هو».",
    5 to "انتبه! لم تُظهر الشدة على الياء في «الحيّ» مع الغنّة، أو أخطأت في ضمّها.",
    6 to "انتبه! أخطأت في فتحة القاف أو في مدّ الواو في «القيّوم»، أو لم تُظهر الشدة على الياء."
)

fun norm(s: String): String = s
    .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
    .replace(Regex("[أإآٱ]"), "ا")
    .replace('ى', 'ي').replace('ة', 'ه')
    .replace("ا", "")

class Engine(private val ctx: Context) {
    var phase by mutableStateOf(Phase.SETUP)
    var target by mutableStateOf(3)
    var counter by mutableStateOf(3)
    var playing by mutableStateOf(false)
    var status by mutableStateOf("")
    val states = mutableStateListOf<WS>().apply { repeat(WORDS.size) { add(WS.PENDING) } }

    private val h = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var player: MediaPlayer? = null
    private var session = 0
    private var progress = 0
    private var base = 0
    private var lastActivity = 0L
    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)

    fun success() = states.all { it == WS.OK }

    fun startCycle() { counter = target; goGuided() }

    fun goGuided() {
        stopRec(); phase = Phase.GUIDED; progress = 0; base = 0
        status = "اقرأ الآية بصوتك…"; startRec()
    }

    fun goRecite() {
        stopRec(); stopAudio()
        for (i in states.indices) states[i] = WS.PENDING
        progress = 0; base = 0; phase = Phase.RECITE
        lastActivity = SystemClock.elapsedRealtime()
        status = "سَمِّع الآية غيباً…"; startRec(); tick()
    }

    fun finish() {
        stopRec(); h.removeCallbacksAndMessages(null)
        for (i in states.indices) if (states[i] == WS.PENDING) states[i] = WS.WRONG
        phase = Phase.RESULT
    }

    fun explain(i: Int): String =
        if (states[i] == WS.HINT) "نسيتَ هذه الكلمة فلُقِّنتَها. أعد حفظ موضعها جيداً."
        else TIPS[i] ?: "راجع التشكيل وأحكام التجويد في هذه الكلمة."

    private fun tick() {
        h.postDelayed({
            if (phase != Phase.RECITE) return@postDelayed
            val now = SystemClock.elapsedRealtime()
            if (now - lastActivity >= 7000 && progress < WORDS.size && states[progress] == WS.PENDING) {
                states[progress] = WS.HINT; lastActivity = now
            }
            tick()
        }, 500)
    }

    private fun startRec() {
        stopRec()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { status = "خدمة التعرف على الصوت غير متوفرة"; return }
        val id = ++session
        base = progress
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: android.os.Bundle?) {}
            override fun onError(e: Int) { restart(id) }
            override fun onResults(b: android.os.Bundle?) { if (id == session) tokens(b)?.let { handle(it) }; restart(id) }
            override fun onPartialResults(b: android.os.Bundle?) { if (id == session) tokens(b)?.let { handle(it) } }
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }

    private fun restart(id: Int) {
        if (id != session) return
        if ((phase == Phase.GUIDED && !playing) || phase == Phase.RECITE) {
            h.postDelayed({ if (id == session) startRec() }, 300)
        }
    }

    private fun tokens(b: android.os.Bundle?): List<String>? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            ?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }

    private fun handle(t: List<String>) {
        val n = WORDS.size
        if (phase == Phase.GUIDED) {
            if (t.size >= n && (0 until n).all { norm(t[it]) == norm(WORDS[it]) }) {
                stopRec()
                counter = (counter - 1).coerceAtLeast(0)
                if (counter == 0) goRecite() else startRec()
            }
        } else if (phase == Phase.RECITE) {
            for (j in t.indices) {
                val idx = base + j
                if (idx >= n) break
                if (states[idx] == WS.HINT) continue
                val ok = norm(t[j]) == norm(WORDS[idx])
                if (!ok && states[idx] != WS.WRONG) tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
                if (!(states[idx] == WS.WRONG && ok)) states[idx] = if (ok) WS.OK else WS.WRONG
            }
            val np = minOf(n, base + t.size)
            if (np > progress) { progress = np; lastActivity = SystemClock.elapsedRealtime() }
            if (progress >= n) finish()
        }
    }

    fun toggleAudio() {
        if (playing) { stopAudio(); if (phase == Phase.GUIDED) startRec(); return }
        stopRec(); playing = true
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setDataSource(AUDIO_URL)
            setOnPreparedListener { it.start() }
            setOnCompletionListener { stopAudio(); if (phase == Phase.GUIDED) startRec() }
            setOnErrorListener { _, _, _ -> stopAudio(); status = "تعذّر تشغيل الصوت (تحقق من الإنترنت)"; if (phase == Phase.GUIDED) startRec(); true }
            prepareAsync()
        }
    }

    private fun stopAudio() { player?.release(); player = null; playing = false }
    private fun stopRec() { session++; rec?.destroy(); rec = null }
    fun release() { h.removeCallbacksAndMessages(null); stopRec(); stopAudio(); tone.release() }
}

val Bg = Color(0xFF0F2B2A); val CardC = Color(0xFF173E3B); val Gold = Color(0xFFD4AF37)
val Cream = Color(0xFFF5EBD0); val Red = Color(0xFFEF4444); val Green = Color(0xFF4ADE80); val Orange = Color(0xFFFFA94D)
// لاستخدام خط Cairo: ضع Cairo-Regular.ttf في res/font ثم استبدل بـ FontFamily(Font(R.font.cairo_regular))
val Cairo: FontFamily = FontFamily.Serif

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { App() }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val e = remember { Engine(ctx) }
    DisposableEffect(Unit) { onDispose { e.release() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) e.startCycle() }
    var dialog by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(Bg).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text("رَفِيق", color = Gold, fontSize = 38.sp, fontWeight = FontWeight.Bold, fontFamily = Cairo)
        Text("Rafiq", color = Cream, fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))

        when (e.phase) {
            Phase.SETUP -> {
                Text("عدد مرات التكرار المطلوبة للقراءة الصحيحة", color = Cream, fontSize = 18.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    RoundBtn("+") { e.target++ }
                    Text("${e.target}", color = Gold, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                    RoundBtn("−") { if (e.target > 1) e.target-- }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(3, 5, 10).forEach { n ->
                        OutlinedButton(onClick = { e.target = n }) { Text("$n", color = Cream) }
                    }
                }
                Spacer(Modifier.height(28.dp))
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) e.startCycle()
                        else launcher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Bg),
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) { Text("ابدأ الدورة", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            }

            Phase.GUIDED -> {
                Text("القراءة الموجَّهة", color = Gold, fontSize = 18.sp)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CardC).padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(VERSE, color = Cream, fontSize = 34.sp, lineHeight = 60.sp, textAlign = TextAlign.Center, fontFamily = Cairo)
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { e.toggleAudio() }, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Bg)) {
                    Text(if (e.playing) "إيقاف الصوت" else "استمع للآية بصوت الشيخ المنشاوي")
                }
                Spacer(Modifier.height(20.dp))
                Box(Modifier.size(110.dp).clip(CircleShape).background(Gold), contentAlignment = Alignment.Center) {
                    Text("${e.counter}", color = Bg, fontSize = 52.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text("المتبقي من القراءات الصحيحة", color = Cream, fontSize = 13.sp)
                Text(e.status, color = Cream.copy(alpha = 0.7f), fontSize = 13.sp)
            }

            Phase.RECITE, Phase.RESULT -> {
                val result = e.phase == Phase.RESULT
                if (!result) {
                    Box(Modifier.size(90.dp).clip(CircleShape).background(Green.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                        Text("🎙", fontSize = 40.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("التسميع الذكي — بدون لمس الهاتف", color = Cream, fontSize = 14.sp)
                } else if (e.success()) {
                    Box(Modifier.size(120.dp).clip(CircleShape).background(Green), contentAlignment = Alignment.Center) {
                        Text("✔", color = Bg, fontSize = 64.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("أحسنت وبارك الله فيك! تسميع متقن بالتشكيل والتجويد الصحيح", color = Green, fontSize = 20.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                } else {
                    Text("اضغط على الكلمات الملوّنة لمعرفة الخطأ", color = Orange, fontSize = 16.sp)
                }
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 160.dp).clip(RoundedCornerShape(24.dp)).background(CardC).padding(20.dp), contentAlignment = Alignment.Center) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        WORDS.forEachIndexed { i, w ->
                            val s = e.states[i]
                            if (s != WS.PENDING) {
                                val c = when (s) { WS.WRONG -> Red; WS.HINT -> Orange; else -> if (result) Green else Cream }
                                val clickable = result && (s == WS.WRONG || s == WS.HINT)
                                Text(w, color = c, fontSize = 32.sp, fontFamily = Cairo,
                                    modifier = if (clickable) Modifier.clickable { dialog = e.explain(i) } else Modifier)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                if (!result) {
                    Button(onClick = { e.finish() }, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("إيقاف التسجيل") }
                } else if (e.success()) {
                    Button(onClick = { e.phase = Phase.SETUP }, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Bg)) { Text("دورة جديدة") }
                } else {
                    Button(
                        onClick = { e.counter = 1; e.goGuided() },
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Bg)
                    ) { Text("إعادة الآية (مرة واحدة)") }
                }
            }
        }
    }

    dialog?.let {
        AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("حسناً") } },
            title = { Text("تنبيه") }, text = { Text(it) }
        )
    }
}

@Composable
fun RoundBtn(label: String, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(CircleShape).background(Gold).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(label, color = Bg, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
}
