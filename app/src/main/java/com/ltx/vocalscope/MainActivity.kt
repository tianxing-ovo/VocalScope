package com.ltx.vocalscope

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.pitch.PitchDetectionHandler
import be.tarsos.dsp.pitch.PitchDetectionResult
import be.tarsos.dsp.pitch.PitchProcessor
import com.ltx.vocalscope.ui.theme.VocalScopeTheme
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 主活动类
 *
 * @author tianxing
 */
class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        isAudioPermissionGranted.value = isGranted
    }

    private var isAudioPermissionGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            isAudioPermissionGranted.value = true
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            VocalScopeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val permissionGranted by isAudioPermissionGranted
                    if (permissionGranted) {
                        VocalScopeScreen(modifier = Modifier.padding(innerPadding))
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("需要录音权限才能测试音域，请在设置中授予权限")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VocalScopeScreen(modifier: Modifier = Modifier) {
    var noteName by remember { mutableStateOf("--") }
    var frequency by remember { mutableFloatStateOf(0f) }
    var minNoteName by remember { mutableStateOf("--") }
    var maxNoteName by remember { mutableStateOf("--") }
    var minMidi by remember { mutableIntStateOf(Int.MAX_VALUE) }
    var maxMidi by remember { mutableIntStateOf(Int.MIN_VALUE) }
    var lastAcceptedMidi by remember { mutableIntStateOf(-1) }
    var jumpCandidateMidi by remember { mutableIntStateOf(-1) }
    var jumpCandidateCount by remember { mutableIntStateOf(0) }
    val recentMidiWindow = remember { ArrayDeque<Int>() }
    val recentFreqWindow = remember { ArrayDeque<Float>() }

    var recordJob by remember { mutableStateOf<Job?>(null) }
    val isRecording = recordJob?.isActive == true
    val coroutineScope = rememberCoroutineScope()
    val view = LocalView.current

    DisposableEffect(isRecording) {
        if (isRecording) {
            view.keepScreenOn = true
        }
        onDispose {
            view.keepScreenOn = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.weight(0.5f))

        Box(
            modifier = Modifier
                .size(180.dp)
                .clip(CircleShape)
                .background(
                    if (isRecording) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = noteName,
                fontSize = 80.sp,
                fontWeight = FontWeight.Bold,
                color = if (isRecording) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (frequency > 0) "${frequency.roundToInt()} Hz" else "-- Hz",
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.weight(1f))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp, horizontal = 0.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("最低音", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(minNoteName, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("最高音", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(maxNoteName, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF44336))
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // 钢琴音域可视化图表
                PianoRangeChart(minMidi = minMidi, maxMidi = maxMidi, currentMidi = lastAcceptedMidi)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                if (isRecording) {
                    recordJob?.cancel()
                    recordJob = null
                    frequency = 0f
                    noteName = "--"
                    lastAcceptedMidi = -1
                    jumpCandidateMidi = -1
                    jumpCandidateCount = 0
                    recentMidiWindow.clear()
                    recentFreqWindow.clear()
                } else {
                    lastAcceptedMidi = -1
                    jumpCandidateMidi = -1
                    jumpCandidateCount = 0
                    recentMidiWindow.clear()
                    recentFreqWindow.clear()
                    recordJob = coroutineScope.launch {
                        startAudioCapture { f, _ ->
                            // 限制在人类合理发声范围
                            if (f in 65f..1100f) {
                                val midi = getMidiNoteNumber(f)
                                if (midi == -1) {
                                    return@startAudioCapture
                                }

                                // 对单帧大跳变做确认，避免瞬时误判拉坏音域极值
                                val isLargeJump = lastAcceptedMidi != -1 && abs(midi - lastAcceptedMidi) >= 7
                                if (isLargeJump) {
                                    if (midi == jumpCandidateMidi) {
                                        jumpCandidateCount += 1
                                    } else {
                                        jumpCandidateMidi = midi
                                        jumpCandidateCount = 1
                                    }
                                    if (jumpCandidateCount < 2) {
                                        return@startAudioCapture
                                    }
                                }

                                jumpCandidateMidi = -1
                                jumpCandidateCount = 0
                                recentMidiWindow.addLast(midi)
                                recentFreqWindow.addLast(f)
                                if (recentMidiWindow.size > 3) recentMidiWindow.removeFirst()
                                if (recentFreqWindow.size > 3) recentFreqWindow.removeFirst()

                                if (recentMidiWindow.size < 3) {
                                    lastAcceptedMidi = midi
                                    frequency = f
                                    noteName = getNoteNameFromMidi(midi)
                                    return@startAudioCapture
                                }

                                val stableMidi = medianInt(recentMidiWindow)
                                val stableFreq = medianFloat(recentFreqWindow)
                                lastAcceptedMidi = stableMidi

                                frequency = stableFreq
                                noteName = getNoteNameFromMidi(stableMidi)

                                if (stableMidi < minMidi) {
                                    minMidi = stableMidi
                                    minNoteName = noteName
                                }
                                if (stableMidi > maxMidi) {
                                    maxMidi = stableMidi
                                    maxNoteName = noteName
                                }
                            }
                        }
                    }
                }
            },
            modifier = Modifier
                .height(56.dp)
                .fillMaxWidth(0.8f),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (isRecording) "停止测试" else "开始测试", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = {
                minMidi = Int.MAX_VALUE
                maxMidi = Int.MIN_VALUE
                minNoteName = "--"
                maxNoteName = "--"
                lastAcceptedMidi = -1
                jumpCandidateMidi = -1
                jumpCandidateCount = 0
                recentMidiWindow.clear()
                recentFreqWindow.clear()
            }
        ) {
            Text("重置记录", fontSize = 16.sp)
        }
    }
}


/**
 * 使用 TarsosDSP YIN 算法进行工业级音高检测
 *
 * 从 Android AudioRecord 读取 PCM 数据，喂给 TarsosDSP 的 PitchProcessor (YIN 算法) 进行高精度音高分析。
 */
suspend fun startAudioCapture(onPitchDetected: suspend (Float, String) -> Unit) = withContext(Dispatchers.IO) {
    try {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

        // TarsosDSP 推荐的 buffer 大小：2048 样本，重叠 0
        val bufferSize = 2048
        val overlap = 0

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            return@withContext
        }

        // 确保真实的缓冲区足够大
        val actualBufferSize = maxOf(minBufferSize, bufferSize * 2)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, // 使用原始麦克风输入，减少系统通话处理链对音高的影响
            sampleRate,
            channelConfig,
            audioEncoding,
            actualBufferSize
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            return@withContext
        }

        // 尝试开启 Android 硬件层的系统级降噪（过滤背景环境音）
        var noiseSuppressor: NoiseSuppressor? = null
        if (NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(audioRecord.audioSessionId)
                noiseSuppressor?.enabled = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // ─── 构建 TarsosDSP 的 YIN 音高检测器 ───
        val tarsosDSPFormat = TarsosDSPAudioFormat(sampleRate.toFloat(), 16, 1, true, false)

        // 最新检测结果的临时容器
        var latestPitch = -1f
        var currentRms = 0f // 当前底噪/音量

        val pitchHandler = PitchDetectionHandler { result: PitchDetectionResult, _: AudioEvent ->
            val pitch = result.pitch
            // 过滤条件：1.探测到音高 2.置信度极高(>0.85) 3.音量(RMS)大于底噪阈值(剔除远处杂音)
            // 限制音高范围在 65Hz(C2) 到 1100Hz(C6以上) 之间，防止极端错误
            latestPitch = if (pitch in 65.0f..1100.0f && result.probability > 0.85f && currentRms > 0.015f) {
                pitch
            } else {
                -1f
            }
        }

        // 使用 YIN 算法创建 PitchProcessor
        val pitchProcessor = PitchProcessor(
            PitchProcessor.PitchEstimationAlgorithm.YIN,
            sampleRate.toFloat(),
            bufferSize,
            pitchHandler
        )

        val readBuffer = ShortArray(bufferSize)
        val floatBuffer = FloatArray(bufferSize)

        audioRecord.startRecording()

        try {
            while (isActive) {
                val readSize = audioRecord.read(readBuffer, 0, bufferSize)
                if (readSize > 0) {
                    var sumSquare = 0f
                    // 将 Short PCM 数据转换为 Float（-1.0 ~ 1.0 范围），这是 TarsosDSP 要求的格式
                    for (i in 0 until readSize) {
                        val sample = readBuffer[i] / 32768.0f
                        floatBuffer[i] = sample
                        sumSquare += sample * sample
                    }
                    // 实时计算音频帧的声音能量大小 (Root Mean Square)
                    currentRms = sqrt(sumSquare / readSize)

                    // 如果实际读取的帧数不足 bufferSize，用 0 填充
                    for (i in readSize until bufferSize) {
                        floatBuffer[i] = 0f
                    }

                    // 构造 TarsosDSP 的 AudioEvent 并将 float 数据喂给 YIN 检测器
                    val audioEvent = AudioEvent(tarsosDSPFormat)
                    audioEvent.floatBuffer = floatBuffer
                    audioEvent.overlap = overlap

                    // 调用 TarsosDSP 的 PitchProcessor 处理这一帧
                    pitchProcessor.process(audioEvent)

                    // 平滑处理：防止八度跳跃 (Octave Error)
                    // 如果单帧突变，将其平滑处理
                    if (latestPitch > 0) {
                        val note = getNoteName(latestPitch)
                        if (note != "--") {
                            withContext(Dispatchers.Main) {
                                onPitchDetected(latestPitch, note)
                            }
                        }
                    }
                }
            }
        } finally {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
            audioRecord.release()
            noiseSuppressor?.release()
        }
    } catch (e: SecurityException) {
        e.printStackTrace()
    }
}

/**
 * 频率转音名（国际标准 MIDI 编号方式）
 *
 * 以 A4 = 440Hz 为基准，通过对数运算将任意频率映射到最近的半音音名。
 */
private val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

fun getMidiNoteNumber(frequency: Float): Int {
    if (frequency <= 0f) return -1
    val noteNum = (12 * log2(frequency / 440f) + 69).roundToInt()
    return if (noteNum in 0..127) noteNum else -1
}

fun getNoteNameFromMidi(noteNum: Int): String {
    if (noteNum !in 0..127) return "--"
    val octave = (noteNum / 12) - 1
    return "${noteNames[noteNum % 12]}$octave"
}

fun getNoteName(frequency: Float): String {
    return getNoteNameFromMidi(getMidiNoteNumber(frequency))
}

fun medianInt(values: Collection<Int>): Int {
    val sorted = values.sorted()
    return sorted[sorted.size / 2]
}

fun medianFloat(values: Collection<Float>): Float {
    val sorted = values.sorted()
    return sorted[sorted.size / 2]
}

@Composable
fun PianoRangeChart(minMidi: Int, maxMidi: Int, currentMidi: Int) {
    val textMeasurer = rememberTextMeasurer()

    val startMidi = 41 // F2
    val endMidi = 74 // D5
    val whiteKeys = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    val noteNamesNoSharp = arrayOf("C", "", "D", "", "E", "F", "", "G", "", "A", "", "B")

    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
        val totalWhiteKeys = 20 // F2 到 D5 共有 20 个白键
        val keyWidth = size.width / totalWhiteKeys

        // 底板颜色，顺便作为白键的缝隙颜色
        drawRect(color = Color.LightGray, size = size)

        var whiteKeyCount = 0
        val keyCenters = mutableMapOf<Int, Float>()

        // Pass 1: 画所有的白键
        for (m in startMidi..endMidi) {
            if (whiteKeys.contains(m % 12)) {
                val left = whiteKeyCount * keyWidth
                
                // 判断当前键是否在个人音域内
                val isInRange = m in minMidi..maxMidi
                val keyColor = if (isInRange) Color(0xFF8A9CEB) else Color.White
                val textColor = if (isInRange) Color.White else Color.LightGray

                drawRect(
                    color = keyColor,
                    topLeft = Offset(left + 1f, 0f), // 留出一点缝隙
                    size = Size(keyWidth - 2f, size.height)
                )

                // 给每个白键标上名字 (C3, D3, E3...)
                val octave = (m / 12) - 1
                val noteName = noteNamesNoSharp[m % 12]
                val noteText = "$noteName$octave"
                
                val textLayoutResult = textMeasurer.measure(
                    text = noteText,
                    style = TextStyle(fontSize = 10.sp, color = textColor, fontWeight = FontWeight.SemiBold)
                )
                drawText(
                    textLayoutResult = textLayoutResult,
                    color = textColor,
                    topLeft = Offset(
                        x = left + (keyWidth - textLayoutResult.size.width) / 2f,
                        y = size.height - textLayoutResult.size.height - 8f
                    )
                )

                keyCenters[m] = left + keyWidth / 2
                whiteKeyCount++
            }
        }

        // Pass 2: 画所有的黑键
        whiteKeyCount = 0
        for (m in startMidi..endMidi) {
            if (whiteKeys.contains(m % 12)) {
                whiteKeyCount++
            } else {
                val left = whiteKeyCount * keyWidth - (keyWidth * 0.4f)
                val bWidth = keyWidth * 0.8f
                val bHeight = size.height * 0.55f
                // 绘制带一点圆角的黑键
                drawRoundRect(
                    color = Color(0xFF2C2C2C), // 黑灰色
                    topLeft = Offset(left, 0f),
                    size = Size(bWidth, bHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                )
                keyCenters[m] = left + bWidth / 2
            }
        }

        // （已移除了旧的半透明跨度条，改由白键变色直接表示音域）

        // 绘制正在发声的音高位置（实时游标）
        if (currentMidi in startMidi..endMidi) {
            val cx = keyCenters[currentMidi]
            if (cx != null) {
                drawCircle(
                    color = Color(0xFFFFEB3B), // 黄色警示点
                    radius = 16f,
                    center = Offset(cx, size.height * 0.8f)
                )
            }
        }
    }
}
