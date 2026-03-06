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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /* 录音权限请求结果处理 */
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        isAudioPermissionGranted.value = isGranted
    }

    /* 录音权限状态 */
    private var isAudioPermissionGranted = mutableStateOf(false)

    /**
     * 活动创建时调用
     *
     * @param savedInstanceState 活动创建时的保存实例状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 检查录音权限
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            isAudioPermissionGranted.value = true
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        // 设置内容视图
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

/**
 * 主活动界面
 *
 * @param modifier 修饰符
 */
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
    // 录音作业
    var recordJob by remember { mutableStateOf<Job?>(null) }
    val isRecording = recordJob?.isActive == true
    val coroutineScope = rememberCoroutineScope()
    val view = LocalView.current
    // 保持屏幕常亮
    DisposableEffect(isRecording) {
        if (isRecording) {
            view.keepScreenOn = true
        }
        onDispose {
            view.keepScreenOn = false
        }
    }
    // 界面布局
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.weight(0.5f))
        // 音符显示框
        Box(
            modifier = Modifier
                .size(180.dp)
                .clip(CircleShape)
                .background(
                    if (isRecording) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ), contentAlignment = Alignment.Center
        ) {
            Text(
                text = noteName,
                fontSize = 80.sp,
                fontWeight = FontWeight.Bold,
                color = if (isRecording) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 音符范围显示框
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (frequency > 0) "${frequency.roundToInt()} Hz" else "-- Hz",
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.weight(1f))
        // 音符范围卡片
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "最低音",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            minNoteName,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "最高音",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            maxNoteName,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF44336)
                        )
                    }
                }
                // 最小音高标签
                Spacer(modifier = Modifier.height(24.dp))
                // 钢琴音域可视化图表
                PianoRangeChart(
                    minMidi = minMidi, maxMidi = maxMidi, currentMidi = lastAcceptedMidi
                )
            }
        }
        // 录音按钮
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
                                val isLargeJump =
                                    lastAcceptedMidi != -1 && abs(midi - lastAcceptedMidi) >= 7
                                // 大跳变检测
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
                                // 对单帧大跳变做确认(避免瞬时误判拉坏音域极值)
                                val stableMidi = medianInt(recentMidiWindow)
                                val stableFreq = medianFloat(recentFreqWindow)
                                lastAcceptedMidi = stableMidi
                                frequency = stableFreq
                                noteName = getNoteNameFromMidi(stableMidi)
                                // 限制在人类合理发声范围
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
            Text(
                if (isRecording) "停止测试" else "开始测试",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
        // 最小音高标签
        Spacer(modifier = Modifier.height(8.dp))
        // 重置记录按钮
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
            }) {
            Text("重置记录", fontSize = 16.sp)
        }
    }
}

/**
 * 开始音频捕获
 *
 * @param onPitchDetected 检测到音高时的回调函数(参数为频率和音名)
 */
suspend fun startAudioCapture(onPitchDetected: suspend (Float, String) -> Unit) =
    withContext(Dispatchers.IO) {
        try {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = 2048
            val overlap = 0
            val minBufferSize =
                AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                return@withContext
            }
            // 确保真实的缓冲区足够大
            val actualBufferSize = maxOf(minBufferSize, bufferSize * 2)
            // 使用原始麦克风输入(避免系统通话处理链对音高的影响)
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioEncoding,
                actualBufferSize
            )
            // 检查AudioRecord是否初始化成功
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext
            }
            // 尝试开启Android硬件层的系统级降噪(过滤背景环境音)
            var noiseSuppressor: NoiseSuppressor? = null
            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(audioRecord.audioSessionId)
                    noiseSuppressor?.enabled = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            // 构建TarsosDSP的音频格式描述符
            val tarsosDSPFormat = TarsosDSPAudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            // 最新检测结果的临时容器
            var latestPitch = -1f
            var currentRms = 0f
            // 构建TarsosDSP的YIN音高检测器
            val pitchHandler =
                PitchDetectionHandler { result: PitchDetectionResult, _: AudioEvent ->
                    val pitch = result.pitch
                    latestPitch =
                        if (pitch in 65.0f..1100.0f && result.probability > 0.85f && currentRms > 0.015f) {
                            pitch
                        } else {
                            -1f
                        }
                }
            // 使用YIN算法创建PitchProcessor
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
                        for (i in 0 until readSize) {
                            val sample = readBuffer[i] / 32768.0f
                            floatBuffer[i] = sample
                            sumSquare += sample * sample
                        }
                        // 实时计算音频帧的声音能量大小
                        currentRms = sqrt(sumSquare / readSize)
                        for (i in readSize until bufferSize) {
                            floatBuffer[i] = 0f
                        }
                        val audioEvent = AudioEvent(tarsosDSPFormat)
                        audioEvent.floatBuffer = floatBuffer
                        audioEvent.overlap = overlap
                        pitchProcessor.process(audioEvent)
                        // 平滑处理: 防止八度跳跃(Octave Error)
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

// 定义音名数组
private val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/**
 * 频率转MIDI编号
 *
 * @param frequency 输入的频率值
 * @return 对应的MIDI编号
 */
fun getMidiNoteNumber(frequency: Float): Int {
    if (frequency <= 0f) return -1
    val noteNum = (12 * log2(frequency / 440f) + 69).roundToInt()
    return if (noteNum in 0..127) noteNum else -1
}

/**
 * MIDI编号转音名
 *
 * @param noteNum 输入的MIDI编号
 * @return 对应的音名
 */
fun getNoteNameFromMidi(noteNum: Int): String {
    if (noteNum !in 0..127) return "--"
    val octave = (noteNum / 12) - 1
    return "${noteNames[noteNum % 12]}$octave"
}

/**
 * 频率转音名
 *
 * @param frequency 输入的频率值
 * @return 对应的音名
 */
fun getNoteName(frequency: Float): String {
    return getNoteNameFromMidi(getMidiNoteNumber(frequency))
}

/**
 * 计算一组整数的中值
 *
 * @param values 输入的整数集合
 * @return 中值
 */
fun medianInt(values: Collection<Int>): Int {
    val sorted = values.sorted()
    return sorted[sorted.size / 2]
}

/**
 * 计算一组浮点数的中值
 *
 * @param values 输入的浮点数集合
 * @return 中值
 */
fun medianFloat(values: Collection<Float>): Float {
    val sorted = values.sorted()
    return sorted[sorted.size / 2]
}

/**
 * 绘制钢琴键盘的音域图表
 *
 * @param minMidi 个人音域的最低MIDI编号
 * @param maxMidi 个人音域的最高MIDI编号
 * @param currentMidi 当前检测到的MIDI编号
 */
@Composable
fun PianoRangeChart(minMidi: Int, maxMidi: Int, currentMidi: Int) {
    val textMeasurer = rememberTextMeasurer()
    // F2
    val startMidi = 41
    // D5
    val endMidi = 74
    val whiteKeys = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    val noteNamesNoSharp = arrayOf("C", "", "D", "", "E", "F", "", "G", "", "A", "", "B")
    // 绘制钢琴键盘
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
    ) {
        // F2到D5共有20个白键
        val totalWhiteKeys = 20
        val keyWidth = size.width / totalWhiteKeys
        // 绘制钢琴键盘的底板
        drawRect(color = Color.LightGray, size = size)
        var whiteKeyCount = 0
        val keyCenters = mutableMapOf<Int, Float>()
        // 画所有的白键
        for (m in startMidi..endMidi) {
            if (whiteKeys.contains(m % 12)) {
                val left = whiteKeyCount * keyWidth
                // 判断当前键是否在个人音域内
                val isInRange = m in minMidi..maxMidi
                val keyColor = if (isInRange) Color(0xFF8A9CEB) else Color.White
                val textColor = if (isInRange) Color.White else Color.LightGray
                // 绘制白键
                drawRect(
                    color = keyColor,
                    topLeft = Offset(left + 1f, 0f),
                    size = Size(keyWidth - 2f, size.height)
                )
                // 给每个白键标上名字
                val octave = (m / 12) - 1
                val noteName = noteNamesNoSharp[m % 12]
                val noteText = "$noteName$octave"
                // 计算文本宽度并居中绘制
                val textLayoutResult = textMeasurer.measure(
                    text = noteText, style = TextStyle(
                        fontSize = 10.sp, color = textColor, fontWeight = FontWeight.SemiBold
                    )
                )
                drawText(
                    textLayoutResult = textLayoutResult, color = textColor, topLeft = Offset(
                        x = left + (keyWidth - textLayoutResult.size.width) / 2f,
                        y = size.height - textLayoutResult.size.height - 8f
                    )
                )
                keyCenters[m] = left + keyWidth / 2
                whiteKeyCount++
            }
        }
        // 画所有的黑键
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
                    color = Color(0xFF2C2C2C),
                    topLeft = Offset(left, 0f),
                    size = Size(bWidth, bHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                )
                keyCenters[m] = left + bWidth / 2
            }
        }
        // 绘制正在发声的音高位置(实时游标)
        if (currentMidi in startMidi..endMidi) {
            val cx = keyCenters[currentMidi]
            if (cx != null) {
                drawCircle(
                    color = Color(0xFFFFEB3B), radius = 16f, center = Offset(cx, size.height * 0.8f)
                )
            }
        }
    }
}
