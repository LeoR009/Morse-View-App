package com.example.morse_view_app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ============================================================
// Activity
// ============================================================

val morseColorScheme = darkColorScheme(
    primary = Color(0xFF39FF14),
    onPrimary = Color.Black,
    background = Color(0xFF121418),
    onBackground = Color.White,
    surface = Color(0xFF121418),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1E232B),
    onSurfaceVariant = Color(0xFFB0B7C3),
    outline = Color(0xFF5A6270)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 画面全体を使いつつ、ステータスバーのアイコンは常に白系にする
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            MaterialTheme(colorScheme = morseColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121418),
                    contentColor = Color.White
                ) {
                    MorseApp()
                }
            }
        }
    }
}

// ============================================================
// 1000Hz ビープ音生成
// ============================================================

class ToneGenerator1000Hz(context: Context) {
    private val frequency = 1000.0
    @Volatile private var isToneActive = false
    @Volatile private var isRunning = true
    private val audioThread: Thread
    private val audioTrack: AudioTrack

    init {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // 端末ネイティブのサンプルレートとバースト（1回に処理されるフレーム数）に合わせる
        val sampleRate = audioManager
            .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        val burst = (audioManager
            .getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256)
            .coerceIn(64, 2048)

        val minBufferFrames = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) / 2
        val capacityFrames = maxOf(minBufferFrames, burst * 4)

        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(capacityFrames * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        audioTrack = builder.build()

        // 実際に使うバッファは小さく絞る（先行書き込み量 = 遅延）
        // 音切れが起きたら下のループで少しずつ増やす
        var targetFrames = burst * 2
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioTrack.setBufferSizeInFrames(targetFrames)
        }

        audioTrack.play()

        audioThread = Thread {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val buffer = ShortArray(burst)
            var phase = 0.0
            val phaseIncrement = 2.0 * PI * frequency / sampleRate

            var currentAmplitude = 0.0
            // 3ミリ秒で音量を0⇔1変化
            val fadeStep = 1.0 / (sampleRate * 0.003)
            var lastUnderruns = -1

            while (isRunning) {
                for (i in 0 until burst) {
                    if (isToneActive) {
                        currentAmplitude = (currentAmplitude + fadeStep).coerceAtMost(1.0)
                    } else {
                        currentAmplitude = (currentAmplitude - fadeStep).coerceAtLeast(0.0)
                    }

                    if (currentAmplitude > 0.0) {
                        buffer[i] = (sin(phase) * Short.MAX_VALUE * 0.9 * currentAmplitude).toInt().toShort()
                        phase += phaseIncrement
                        if (phase >= 2.0 * PI) {
                            phase -= 2.0 * PI
                        }
                    } else {
                        buffer[i] = 0
                        phase = 0.0
                    }
                }
                audioTrack.write(buffer, 0, burst)

                // 音切れが増えたらバッファを1バースト分だけ増やす
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val underruns = audioTrack.underrunCount
                    if (lastUnderruns < 0) {
                        lastUnderruns = underruns // 起動直後の分は無視
                    } else if (underruns > lastUnderruns) {
                        lastUnderruns = underruns
                        val next = minOf(targetFrames + burst, audioTrack.bufferCapacityInFrames)
                        if (next > targetFrames) {
                            targetFrames = next
                            audioTrack.setBufferSizeInFrames(targetFrames)
                        }
                    }
                }
            }

            try {
                audioTrack.stop()
                audioTrack.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.apply {
            start()
        }
    }

    /** アプリ内音量（0.0〜1.0） */
    fun setVolume(gain: Float) {
        try {
            audioTrack.setVolume(gain.coerceIn(0f, 1f))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun start() {
        isToneActive = true
    }

    fun stop() {
        isToneActive = false
    }

    fun release() {
        isRunning = false
        isToneActive = false
    }
}

// ============================================================
// モールス符号テーブル
// ============================================================

enum class MorseLanguage { ALPHABET, JAPANESE }

/** 国際モールス符号（英数字・記号） */
val internationalMorse: Map<Char, String> = mapOf(
    'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".",
    'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---",
    'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---",
    'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-",
    'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--",
    'Z' to "--..",
    '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
    '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
    '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '\'' to ".----.", '!' to "-.-.--",
    '/' to "-..-.", '(' to "-.--.", ')' to "-.--.-", '&' to ".-...", ':' to "---...",
    ';' to "-.-.-.", '=' to "-...-", '+' to ".-.-.", '-' to "-....-", '_' to "..--.-",
    '"' to ".-..-.", '$' to "...-..-", '@' to ".--.-."
)

/** 和文モールス符号（カタカナ・濁点/半濁点・長音・句読点・括弧・数字） */
val wabunMorse: Map<Char, String> = mapOf(
    'イ' to ".-", 'ロ' to ".-.-", 'ハ' to "-...", 'ニ' to "-.-.", 'ホ' to "-..", 'ヘ' to ".",
    'ト' to "..-..", 'チ' to "..-.", 'リ' to "--.", 'ヌ' to "....", 'ル' to "-.--.", 'ヲ' to ".---",
    'ワ' to "-.-", 'カ' to ".-..", 'ヨ' to "--", 'タ' to "-.", 'レ' to "---", 'ソ' to "---.",
    'ツ' to ".--.", 'ネ' to "--.-", 'ナ' to ".-.", 'ラ' to "...", 'ム' to "-",
    'ウ' to "..-", 'ヰ' to ".-..-", 'ノ' to "..--", 'オ' to ".-...", 'ク' to "...-",
    'ヤ' to ".--", 'マ' to "-..-", 'ケ' to "-.--", 'フ' to "--..", 'コ' to "----",
    'エ' to "-.---", 'テ' to ".-.--", 'ア' to "--.--", 'サ' to "-.-.-", 'キ' to "-.-..",
    'ユ' to "-..--", 'メ' to "-...-", 'ミ' to "..-.-", 'シ' to "--.-.", 'ヱ' to ".--..",
    'ヒ' to "--..-", 'モ' to "-..-.", 'セ' to ".---.", 'ス' to "---.-", 'ン' to ".-.-.",
    '\u3099' to "..",        // 濁点 ゛
    '\u309A' to "..--.",     // 半濁点 ゜
    'ー' to ".--.-",
    '、' to ".-.-.-", '。' to ".-.-..", '「' to "-.--.-", '」' to ".-..-.",
    '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
    '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----."
)

private val smallKanaMap: Map<Char, Char> = mapOf(
    'ァ' to 'ア', 'ィ' to 'イ', 'ゥ' to 'ウ', 'ェ' to 'エ', 'ォ' to 'オ',
    'ッ' to 'ツ', 'ャ' to 'ヤ', 'ュ' to 'ユ', 'ョ' to 'ヨ', 'ヮ' to 'ワ',
    'ヵ' to 'カ', 'ヶ' to 'ケ'
)

// ============================================================
// 変換ロジック
// ============================================================

/** 和文用に正規化：ひらがな→カタカナ、小書き文字→大文字、濁点を分離 */
fun normalizeForWabun(input: String): String {
    val pre = input.replace("゛", "\u3099").replace("゜", "\u309A")
    val nfkc = Normalizer.normalize(pre, Normalizer.Form.NFKC)
    val converted = buildString {
        for (c in nfkc) {
            var ch = c
            if (ch in '\u3041'..'\u3096') ch = (ch.code + 0x60).toChar() // ひらがな→カタカナ
            ch = smallKanaMap[ch] ?: ch
            ch = when (ch) {
                ',' -> '、'
                '.' -> '。'
                '(' -> '「'
                ')' -> '」'
                '-' -> 'ー'
                else -> ch
            }
            append(ch)
        }
    }
    // ガ → カ + 濁点 に分解
    return Normalizer.normalize(converted, Normalizer.Form.NFD)
}

data class EncodeResult(val morse: String, val unsupported: String)

fun encodeText(text: String, japanese: Boolean): EncodeResult {
    val table = if (japanese) wabunMorse else internationalMorse
    val src = if (japanese) {
        normalizeForWabun(text)
    } else {
        Normalizer.normalize(text, Normalizer.Form.NFKC).uppercase()
    }
    val unsupported = LinkedHashSet<Char>()
    val words = src.split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .map { word ->
            word.mapNotNull { ch ->
                val code = table[ch]
                if (code == null) unsupported.add(ch)
                code
            }.joinToString(" ")
        }
        .filter { it.isNotEmpty() }
    return EncodeResult(words.joinToString(" / "), unsupported.joinToString(" "))
}

/**
 * モールス符号 → 文章
 * ・文字間：スペース / 単語間："/" またはスペース3つ以上、改行
 * ・「・」「－」なども受け付ける
 */
fun decodeMorse(input: String, japanese: Boolean, hiragana: Boolean): String {
    val table = if (japanese) wabunMorse else internationalMorse
    val reverse = table.entries.associate { it.value to it.key }

    val cleaned = buildString {
        for (c in input) {
            append(
                when (c) {
                    '.', '・', '･', '•', '·', '．' -> '.'
                    '-', '－', '−', '–', '—', '―', 'ー', '_' -> '-'
                    '／', '|' -> '/'
                    else -> c
                }
            )
        }
    }

    val words = cleaned.split("/")
        .flatMap { it.split(Regex("[ \\t\\u3000]{3,}|\\n+")) }

    val decodedWords = words
        .map { word ->
            word.trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .joinToString("") { token -> reverse[token]?.toString() ?: "?" }
        }
        .filter { it.isNotEmpty() }

    var result = decodedWords.joinToString(" ")
    if (japanese) {
        // 例：　カ + 濁点 → ガ に合成
        result = Normalizer.normalize(result, Normalizer.Form.NFC)
        if (hiragana) {
            result = buildString {
                for (c in result) {
                    append(if (c in '\u30A1'..'\u30F6') (c.code - 0x60).toChar() else c)
                }
            }
        }
    }
    return result
}

private const val PLAYBACK_SAMPLE_RATE = 44100

/** スライダー位置(0〜1)を音量ゲインに変換。2乗にすると聴感上なめらかに変化する */
fun volumeToGain(volume: Float): Float = volume * volume

/**
 * モールス文字列（"... --- ... / ..."）を、無音も含めた1本のPCM波形にあらかじめ変換する
 * 音の長さ・間隔はすべてサンプル数で決まるので、再生側の遅延やスレッドのゆらぎの影響を受けない
 * 1単位 = 1.2 / WPM 秒（PARIS基準）
 */
fun renderMorsePcm(morse: String, wpm: Float): ShortArray {
    val sampleRate = PLAYBACK_SAMPLE_RATE
    val unit = (1.2f * sampleRate / wpm).roundToInt()

    // (音か無音か, 単位数) の並びに展開
    val segments = ArrayList<Pair<Boolean, Int>>()
    for (token in morse.split(" ").filter { it.isNotEmpty() }) {
        if (token == "/") {
            segments.add(false to 4)              // 文字間3 + 4 = 単語間7
        } else {
            for (symbol in token) {
                when (symbol) {
                    '.' -> { segments.add(true to 1); segments.add(false to 1) }
                    '-' -> { segments.add(true to 3); segments.add(false to 1) }
                }
            }
            segments.add(false to 2)              // 符号間1 + 2 = 文字間3
        }
    }
    if (segments.isEmpty()) return ShortArray(0)

    // 先頭・末尾に無音を足して、再生開始時の音切れや終了時の途切れを防ぐ
    val pad = sampleRate / 5
    val total = pad * 2 + segments.sumOf { it.second } * unit
    val pcm = ShortArray(total)

    val fade = (sampleRate * 0.005).toInt()       // 5msのフェードでクリックノイズを防止
    var pos = pad
    for ((isTone, units) in segments) {
        val n = units * unit
        if (isTone) {
            for (i in 0 until n) {
                val env = when {
                    i < fade -> 0.5 * (1.0 - cos(PI * i / fade))
                    i >= n - fade -> 0.5 * (1.0 - cos(PI * (n - 1 - i) / fade))
                    else -> 1.0
                }
                val s = sin(2.0 * PI * 1000.0 * i / sampleRate) * env * 0.9 * Short.MAX_VALUE
                pcm[pos + i] = s.toInt().toShort()
            }
        }
        pos += n
    }
    return pcm
}

/** 波形を先に作ってから、そのまま途切れなく流す。キャンセルすると即座に止まる */
suspend fun playMorse(morse: String, wpm: Float, gainProvider: () -> Float) {
    withContext(Dispatchers.IO) {
        val pcm = renderMorsePcm(morse, wpm)
        if (pcm.isEmpty()) return@withContext

        val minBuf = AudioTrack.getMinBufferSize(
            PLAYBACK_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(PLAYBACK_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 16384))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            track.setVolume(gainProvider().coerceIn(0f, 1f))
            track.play()
            var offset = 0
            while (offset < pcm.size) {
                ensureActive() // 停止ボタンで止められるよう、小さな単位で書き込む
                track.setVolume(gainProvider().coerceIn(0f, 1f)) // 再生中の音量変更も反映
                val len = minOf(2048, pcm.size - offset)
                val written = track.write(pcm, offset, len)
                if (written <= 0) break
                offset += written
            }
            // バッファに残った分が再生され終わるまで待つ
            val deadline = System.currentTimeMillis() + 2000
            while (track.playbackHeadPosition < pcm.size &&
                System.currentTimeMillis() < deadline
            ) {
                delay(20)
            }
        } finally {
            try {
                track.pause()
                track.flush() // 停止時に残りの音を即座に捨てる
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
            track.release()
        }
    }
}

fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("morse", text))
    Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
}

// ============================================================
// モールス木のノード定義
// ============================================================

enum class ShapeType { CIRCLE, RECT_H, RECT_V, ANTENNA }

data class MorseNode(
    val char: String,
    val code: String,
    val x: Float,
    val y: Float,
    val shape: ShapeType
)

// モールス木SVG（6696 x 7960）の座標テーブル
val morseNodes = listOf(
    MorseNode("START", "", 3346f, 255f, ShapeType.ANTENNA),
    // 第1階層
    MorseNode("T", "-", 2656f, 1070f, ShapeType.RECT_H),
    MorseNode("E", ".", 3976f, 1080f, ShapeType.CIRCLE),
    // 第2階層
    MorseNode("M", "--", 1476f, 1070f, ShapeType.RECT_H),
    MorseNode("N", "-.", 2656f, 4380f, ShapeType.CIRCLE),
    MorseNode("A", ".-", 3976f, 4380f, ShapeType.RECT_V),
    MorseNode("I", "..", 4806f, 1080f, ShapeType.CIRCLE),
    // 第3階層
    MorseNode("O", "---", 306f, 1070f, ShapeType.RECT_H),
    MorseNode("G", "--.", 1476f, 2180f, ShapeType.CIRCLE),
    MorseNode("K", "-.-", 1476f, 4380f, ShapeType.RECT_H),
    MorseNode("D", "-..", 2656f, 6580f, ShapeType.CIRCLE),
    MorseNode("W", ".--", 3976f, 6580f, ShapeType.RECT_V),
    MorseNode("R", ".-.", 4806f, 4380f, ShapeType.CIRCLE),
    MorseNode("U", "..-", 4806f, 2180f, ShapeType.RECT_V),
    MorseNode("S", "...", 5636f, 1080f, ShapeType.CIRCLE),
    // 第4階層
    MorseNode("Q", "--.-", 306f, 2180f, ShapeType.RECT_H),
    MorseNode("Z", "--..", 1476f, 3280f, ShapeType.CIRCLE),
    MorseNode("Y", "-.--", 305f, 4380f, ShapeType.RECT_H),
    MorseNode("C", "-.-.", 1476f, 5480f, ShapeType.CIRCLE),
    MorseNode("X", "-..-", 1476f, 6580f, ShapeType.RECT_H),
    MorseNode("B", "-...", 2656f, 7680f, ShapeType.CIRCLE),
    MorseNode("J", ".---", 3976f, 7680f, ShapeType.RECT_V),
    MorseNode("P", ".--.", 4806f, 6580f, ShapeType.CIRCLE),
    MorseNode("L", ".-..", 5636f, 4380f, ShapeType.CIRCLE),
    MorseNode("F", "..-.", 4806f, 3280f, ShapeType.CIRCLE),
    MorseNode("V", "...-", 5636f, 2180f, ShapeType.RECT_V),
    MorseNode("H", "....", 6466f, 1080f, ShapeType.CIRCLE)
)

// ============================================================
// アプリ全体（タブ）
// ============================================================

@Composable
fun MorseApp() {
    // 音源はタブ間で共有する
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("morse_prefs", Context.MODE_PRIVATE) }
    var volume by remember { mutableStateOf(prefs.getFloat("volume", 0.8f)) }
    val toneGenerator = remember {
        ToneGenerator1000Hz(context).also { it.setVolume(volumeToGain(volume)) }
    }
    DisposableEffect(Unit) {
        onDispose { toneGenerator.release() }
    }

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("キー入力", "英数字変換", "和文変換")

    // safeDrawingPadding: カメラ穴(ディスプレイカットアウト)・ステータスバー・
    // ナビゲーションバー・キーボードを避けて配置する
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF121418),
            contentColor = Color(0xFF39FF14)
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, maxLines = 1) }
                )
            }
        }

        // アプリ内音量（キー入力・変換の再生の両方に対応）
        VolumeBar(
            volume = volume,
            onChange = {
                volume = it
                toneGenerator.setVolume(volumeToGain(it))
            },
            onFinished = { prefs.edit().putFloat("volume", volume).apply() }
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (selectedTab) {
                0 -> MorseKeyScreen(toneGenerator)
                1 -> ConverterScreen(MorseLanguage.ALPHABET, volume)
                else -> ConverterScreen(MorseLanguage.JAPANESE, volume)
            }
        }
    }
}

// ============================================================
// タブ1：キー入力
// ============================================================

@Composable
fun MorseKeyScreen(toneGenerator: ToneGenerator1000Hz) {
    var inputCode by remember { mutableStateOf("") }
    var isPressing by remember { mutableStateOf(false) }
    var resetJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val currentNode = morseNodes.find { it.code == inputCode && inputCode.isNotEmpty() }

    // ランプの明るさ(0〜1)。消灯時に余韻が残る
    val antennaLamp = remember { Animatable(0f) }
    val nodeLamps = remember { morseNodes.map { Animatable(0f) } }

    LaunchedEffect(isPressing) {
        antennaLamp.animateTo(if (isPressing) 1f else 0f, lampSpec(isPressing))
    }

    LaunchedEffect(inputCode) {
        morseNodes.forEachIndexed { index, node ->
            if (node.shape == ShapeType.ANTENNA) return@forEachIndexed
            val target = when {
                inputCode.isEmpty() || !inputCode.startsWith(node.code) -> 0f
                node.code == inputCode -> 1f
                else -> 0.45f
            }
            val lamp = nodeLamps[index]
            if (lamp.value != target) {
                launch { lamp.animateTo(target, lampSpec(target > lamp.value)) }
            }
        }
    }

    // KEYボタンもアンテナと同じ明るさに連動させる
    val keyLamp = antennaLamp.value
    val keyColor = lerp(Color(0xFF2C313C), Color(0xFFCFE600), keyLamp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 回路基板エリア（6696 : 7960）
        Box(
            modifier = Modifier
                .weight(1f)
                .aspectRatio(6696f / 7960f),
            contentAlignment = Alignment.Center
        ) {
            // 1. 発光レイヤー（ゆっくり点灯・消灯）
            Canvas(modifier = Modifier.fillMaxSize()) {
                val scale = size.width / 6696f

                // 上部アンテナマーク
                val antennaAlpha = antennaLamp.value
                if (antennaAlpha > 0.01f) {
                    val antennaPath = Path().apply {
                        moveTo(3346f * scale, 480f * scale)
                        lineTo(3670.76f * scale, 30f * scale)
                        lineTo(3021.24f * scale, 30f * scale)
                        close()
                    }
                    drawPath(
                        path = antennaPath,
                        color = Color(0xFFCFE600).copy(alpha = antennaAlpha)
                    )
                }

                // 入力経路のノード
                morseNodes.forEachIndexed { index, node ->
                    val alpha = nodeLamps[index].value
                    if (alpha <= 0.01f) return@forEachIndexed

                    when (node.shape) {
                        ShapeType.CIRCLE -> {
                            drawCircle(
                                color = Color(0xFF39FF14).copy(alpha = alpha),
                                radius = 215f * scale,
                                center = Offset(node.x * scale, node.y * scale)
                            )
                        }
                        ShapeType.RECT_H -> {
                            val w = 580f * scale
                            val h = 310f * scale
                            drawRect(
                                color = Color(0xFFC00000).copy(alpha = alpha),
                                topLeft = Offset(node.x * scale - w / 2, node.y * scale - h / 2),
                                size = Size(w, h)
                            )
                        }
                        ShapeType.RECT_V -> {
                            val w = 290f * scale
                            val h = 530f * scale
                            drawRect(
                                color = Color(0xFFC00000).copy(alpha = alpha),
                                topLeft = Offset(node.x * scale - w / 2, node.y * scale - h / 2),
                                size = Size(w, h)
                            )
                        }
                        ShapeType.ANTENNA -> Unit
                    }
                }
            }

            // 2. 枠線ベクター画像
            Image(
                painter = painterResource(id = R.drawable.ic_morse_frame),
                contentDescription = "Morse Tree Frame",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        // 解読文字の表示
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (inputCode.isEmpty()) "READY" else "${currentNode?.char ?: "?"}  [ $inputCode ]",
                color = if (currentNode != null) Color(0xFF39FF14) else Color.White,
                fontSize = 28.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // キー入力ボタン
        Box(
            modifier = Modifier
                .padding(bottom = 16.dp)
                .size(96.dp)
                .clip(CircleShape)
                .background(keyColor)
                .border(3.dp, Color.White, CircleShape)
                .pointerInput(Unit) {
                    // 指が触れたイベントの処理の中で直接音を鳴らす（別コルーチン経由にしない）
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        resetJob?.cancel() // 1秒リセットタイマーを一時解除
                        isPressing = true
                        toneGenerator.start()

                        var up: PointerInputChange? = null
                        try {
                            up = waitForUpOrCancellation() // 指を離すまで待機
                        } finally {
                            // 長短の判定はタッチイベント自身の時刻で行う（処理の遅れの影響を受けない）
                            val endTime = up?.uptimeMillis ?: SystemClock.uptimeMillis()
                            isPressing = false
                            toneGenerator.stop()

                            // 0.2秒未満なら短点、0.2秒以上なら長点
                            val symbol = if (endTime - down.uptimeMillis < 200L) "." else "-"
                            if (inputCode.length < 4) {
                                inputCode += symbol
                            }

                            // 1秒後に経路をリセットし全消灯するタイマーを開始
                            resetJob = coroutineScope.launch {
                                delay(1000L)
                                inputCode = ""
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "KEY",
                color = lerp(Color.White, Color.Black, keyLamp),
                fontSize = 18.sp
            )
        }
    }
}

// ============================================================
// タブ2・3：変換画面（英数字 / 和文 共通）
// ============================================================

@Composable
fun ConverterScreen(language: MorseLanguage, volume: Float) {
    val isJapanese = language == MorseLanguage.JAPANESE
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentVolume by rememberUpdatedState(volume)

    var encodeMode by rememberSaveable { mutableStateOf(true) }      // true: 文章→モールス
    var plainText by rememberSaveable { mutableStateOf("") }
    var morseText by rememberSaveable { mutableStateOf("") }
    var wpm by rememberSaveable { mutableStateOf(15f) }
    var useHiragana by rememberSaveable { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var playJob by remember { mutableStateOf<Job?>(null) }

    val encoded = remember(plainText, isJapanese) { encodeText(plainText, isJapanese) }
    val decoded = remember(morseText, isJapanese, useHiragana) {
        decodeMorse(morseText, isJapanese, useHiragana)
    }

    // タブを離れるとscopeごとキャンセルされ、playMorse内のfinallyで音が止まる
    fun stopPlayback() {
        playJob?.cancel()
        isPlaying = false
    }

    fun startPlayback(morse: String) {
        val speed = wpm
        val previous = playJob
        playJob = scope.launch {
            previous?.cancelAndJoin() // 前の再生の後始末が終わってから開始
            isPlaying = true
            try {
                playMorse(morse, speed) { volumeToGain(currentVolume) }
            } finally {
                isPlaying = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 変換方向の切り替え
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeButton("文章 → モールス", encodeMode, Modifier.weight(1f)) {
                stopPlayback()
                encodeMode = true
            }
            ModeButton("モールス → 文章", !encodeMode, Modifier.weight(1f)) {
                stopPlayback()
                encodeMode = false
            }
        }

        if (encodeMode) {
            // ---------- 文章 → モールス ----------
            OutlinedTextField(
                value = plainText,
                onValueChange = { plainText = it },
                label = {
                    Text(if (isJapanese) "ひらがな・カタカナ・数字を入力" else "英数字を入力")
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            if (encoded.unsupported.isNotEmpty()) {
                Text(
                    text = "変換できない文字（スキップ）: ${encoded.unsupported}\n" +
                            if (isJapanese) "※漢字・英字はひらがな／カタカナに直して入力してください"
                            else "※日本語は「和文変換」タブで変換できます",
                    color = Color(0xFFFFB74D),
                    fontSize = 13.sp
                )
            }

            OutputCard(
                text = encoded.morse,
                placeholder = "ここにモールス符号が表示されます",
                monospace = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (isPlaying) stopPlayback() else startPlayback(encoded.morse)
                    },
                    enabled = encoded.morse.isNotEmpty() || isPlaying,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isPlaying) "■ 停止" else "▶ 音で再生")
                }
                OutlinedButton(
                    onClick = { copyToClipboard(context, encoded.morse) },
                    enabled = encoded.morse.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("コピー")
                }
            }

            Text("速度: ${wpm.roundToInt()} WPM", fontSize = 14.sp)
            Slider(
                value = wpm,
                onValueChange = { wpm = it },
                valueRange = 5f..30f
            )
        } else {
            // ---------- モールス → 文章 ----------
            OutlinedTextField(
                value = morseText,
                onValueChange = { morseText = it },
                label = { Text("モールス符号（文字間：スペース／単語間：/）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp
                )
            )

            // 入力補助ボタン
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QuickKey("・", Modifier.weight(1f)) { morseText += "." }
                QuickKey("－", Modifier.weight(1f)) { morseText += "-" }
                QuickKey("␣", Modifier.weight(1f)) { morseText += " " }
                QuickKey("/", Modifier.weight(1f)) { morseText += " / " }
                QuickKey("⌫", Modifier.weight(1f)) { morseText = morseText.dropLast(1) }
            }

            if (isJapanese) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Switch(checked = useHiragana, onCheckedChange = { useHiragana = it })
                    Text("ひらがなで表示")
                }
            }

            OutputCard(
                text = decoded,
                placeholder = "ここに解読結果が表示されます",
                monospace = false
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { copyToClipboard(context, decoded) },
                    enabled = decoded.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("結果をコピー")
                }
                OutlinedButton(
                    onClick = { morseText = "" },
                    enabled = morseText.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("入力をクリア")
                }
            }

            Text(
                text = if (isJapanese)
                    "※和文では「゛」「゜」も符号で入力します（例：バ ＝ ハ「-...」＋濁点「..」）"
                else
                    "※解読できない符号は「?」で表示されます",
                color = Color(0xFFB0B7C3),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val padding = PaddingValues(horizontal = 8.dp)
    if (selected) {
        Button(onClick = onClick, modifier = modifier, contentPadding = padding) {
            Text(label, fontSize = 14.sp, maxLines = 1)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = padding) {
            Text(label, fontSize = 14.sp, maxLines = 1)
        }
    }
}

@Composable
private fun QuickKey(label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(label, fontSize = 20.sp)
    }
}

@Composable
private fun OutputCard(text: String, placeholder: String, monospace: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E232B))
    ) {
        Text(
            text = text.ifEmpty { placeholder },
            color = if (text.isEmpty()) Color(0xFF7A828F) else Color.White,
            fontSize = 20.sp,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )
    }
}


// ============================================================
// 共通部品
// ============================================================

// ランプの応答時間（ミリ秒）。好みに合わせて調整
private const val LAMP_RISE_MS = 60    // 点灯
private const val LAMP_FALL_MS = 300   // 消灯

private fun lampSpec(rising: Boolean): AnimationSpec<Float> =
    if (rising) tween(LAMP_RISE_MS, easing = LinearEasing)
    else tween(LAMP_FALL_MS, easing = LinearOutSlowInEasing)

@Composable
private fun VolumeBar(volume: Float, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = when {
                volume <= 0f -> "🔇"
                volume < 0.5f -> "🔉"
                else -> "🔊"
            },
            fontSize = 18.sp
        )
        Slider(
            value = volume,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${(volume * 100).roundToInt()}%",
            fontSize = 13.sp,
            modifier = Modifier.width(44.dp)
        )
    }
}