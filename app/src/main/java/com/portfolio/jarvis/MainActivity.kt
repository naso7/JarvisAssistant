package com.portfolio.jarvis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class JarvisState {
    LOADING,    // Загрузка моделей в ОЗУ
    IDLE,       // Ожидание команды
    LISTENING,  // Запись голоса с микрофона
    THINKING,   // Распознавание и генерация ответа
    SPEAKING    // Озвучка ответа
}

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var nativeBridge: JarvisNativeBridge
    private val audioRecorder = AudioRecorderHelper()
    private lateinit var actionExecutor: JarvisActionExecutor
    private var tts: TextToSpeech? = null

    private var currentState by mutableStateOf(JarvisState.LOADING)
    private var statusText by mutableStateOf("ИНИЦИАЛИЗАЦИЯ ЯДРА...")
    private var recognizedQuery by mutableStateOf("")
    private var jarvisResponse by mutableStateOf("")

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Для работы Джарвиса нужен микрофон, сэр.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        actionExecutor = JarvisActionExecutor(this)
        tts = TextToSpeech(this, this)

        // Инициализируем мост с коллбэком для потокового вывода букв на экран
        nativeBridge = JarvisNativeBridge { token ->
            runOnUiThread {
                // Не выводим технические теги действий на экран
                if (!token.contains("[") && !token.contains("]") && !token.contains("ACTION")) {
                    jarvisResponse += token
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            JarvisHudScreen()
        }

        // Загружаем модели в фоновом потоке
        loadModelsAsync()
    }

    private fun loadModelsAsync() {
        Thread {
             // Папка приложения во внутренней памяти: /sdcard/Android/data/com.portfolio.jarvis/files/
            val baseDir = getExternalFilesDir(null)
            val whisperPath = File(baseDir, "stt_model.bin").absolutePath
            val llamaPath = File(baseDir, "llm_model.gguf").absolutePath

            val whisperExists = File(whisperPath).exists()
            val llamaExists = File(llamaPath).exists()

            if (!whisperExists || !llamaExists) {
                runOnUiThread {
                    statusText = "ОШИБКА: ФАЙЛЫ МОДЕЛЕЙ НЕ НАЙДЕНЫ"
                    jarvisResponse = "Поместите файлы stt_model.bin и llm_model.gguf в папку:\n${baseDir?.absolutePath}"
                }
                return@Thread
            }

            val success = nativeBridge.initEngines(whisperPath, llamaPath)
            runOnUiThread {
                if (success) {
                    currentState = JarvisState.IDLE
                    statusText = "СИСТЕМЫ В НОРМЕ. УДЕРЖИВАЙТЕ РЕАКТОР"
                    speak("Все системы запущены и готовы к работе, сэр.")
                } else {
                    statusText = "СБОЙ ЗАГРУЗКИ НЕЙРОСЕТЕЙ"
                }
            }
        }.start()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ru", "RU")
            // Делаем голос ниже и солиднее, как у дворецкого Тони Старка
            tts?.setPitch(0.82f)
            tts?.setSpeechRate(1.05f)
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        currentState = JarvisState.SPEAKING
        statusText = "ПЕРЕДАЧА ДАННЫХ..."
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_UTTERANCE")
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    @Composable
    fun JarvisHudScreen() {
        val coroutineScope = rememberCoroutineScope()
        val scrollState = rememberScrollState()
        val latestState by rememberUpdatedState(currentState)

        LaunchedEffect(jarvisResponse) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }

        val reactorColor by animateColorAsState(
            targetValue = when (currentState) {
                JarvisState.LOADING -> Color(0xFF555555)
                JarvisState.IDLE -> Color(0xFF00E5FF)
                JarvisState.LISTENING -> Color(0xFFFF1744)
                JarvisState.THINKING -> Color(0xFFFFD600)
                JarvisState.SPEAKING -> Color(0xFF00B0FF)
            },
            animationSpec = tween(300),
            label = "reactorColor"
        )

        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF060B14))
                .systemBarsPadding()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. ВЕРХНИЙ БЛОК: Жестко закрепленная шапка
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "J.A.R.V.I.S.",
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 6.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "ВАШ ИНТЕЛЛЕКТУАЛЬНЫЙ ПОМОЩНИК",
                    color = Color(0xFFFFB300),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center
                )
            }

            // 2. ЦЕНТРАЛЬНЫЙ БЛОК: Занимает всё свободное пространство (weight = 1f), поэтому центр НИКОГДА не сдвигается
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Увеличили высоту до 56.dp и добавили Spacer(24.dp), чтобы текст не касался реактора
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = statusText,
                        color = reactorColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                        maxLines = 2
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(210.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    if (latestState == JarvisState.IDLE || latestState == JarvisState.SPEAKING) {
                                        tts?.stop()
                                        nativeBridge.stopGeneration()
                                        currentState = JarvisState.LISTENING
                                        statusText = "СЛУШАЮ ВАС, СЭР..."
                                        recognizedQuery = ""
                                        jarvisResponse = ""
                                        audioRecorder.startRecording()

                                        tryAwaitRelease()

                                        currentState = JarvisState.THINKING
                                        statusText = "АНАЛИЗ ГОЛОСОВОГО ВВОДА..."

                                        coroutineScope.launch(Dispatchers.IO) {
                                            try {
                                                val samples = audioRecorder.stopRecordingAndGetSamples()
                                                val text = nativeBridge.transcribeAudio(samples).trim()

                                                withContext(Dispatchers.Main) {
                                                    if (text.isBlank()) {
                                                        currentState = JarvisState.IDLE
                                                        statusText = "РЕЧЬ НЕ РАСПОЗНАНА. ПОПРОБУЙТЕ СНОВА"
                                                        return@withContext
                                                    }
                                                    recognizedQuery = "Вы: $text"
                                                    statusText = "ОБРАБОТКА ЗАПРОСА..."
                                                }

                                                if (text.isNotBlank()) {
                                                    val rawReply = nativeBridge.askJarvis(text)
                                                    val cleanReply = actionExecutor.processAndCleanResponse(rawReply)

                                                    withContext(Dispatchers.Main) {
                                                        jarvisResponse = cleanReply.ifBlank { "Не удалось сформировать ответ, сэр." }
                                                        speak(jarvisResponse)
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            } finally {
                                                withContext(Dispatchers.Main) {
                                                    currentState = JarvisState.IDLE
                                                    statusText = "ГОТОВ К СЛЕДУЮЩЕЙ КОМАНДЕ"
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = Offset(size.width / 2, size.height / 2)
                        val baseRadius = size.minDimension / 2.5f

                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(reactorColor.copy(alpha = 0.35f), Color.Transparent),
                                center = center,
                                radius = baseRadius * pulseScale * 1.25f
                            ),
                            radius = baseRadius * pulseScale * 1.25f,
                            center = center
                        )
                        drawCircle(
                            color = reactorColor,
                            radius = baseRadius * pulseScale,
                            center = center,
                            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                        )
                        drawCircle(
                            color = reactorColor.copy(alpha = 0.8f),
                            radius = baseRadius * 0.45f,
                            center = center
                        )
                    }

                    Text(
                        text = if (currentState == JarvisState.LISTENING) "ГОВОРИТЕ" else "ЗАЖАТЬ",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                }
            }

            // 3. НИЖНИЙ БЛОК: Жестко заданная высота (210.dp), чтобы появление текста не сдвигало реактор вверх
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .background(Color(0xFF0D1726), shape = RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                ) {
                    if (recognizedQuery.isNotEmpty()) {
                        Text(
                            text = recognizedQuery,
                            color = Color(0xFF80D8FF),
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Text(
                        text = jarvisResponse.ifEmpty { "Ожидание голосовых инструкций..." },
                        color = Color.White,
                        fontSize = 15.sp,
                        lineHeight = 20.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        tts?.stop()
                        nativeBridge.stopGeneration()
                        recognizedQuery = ""
                        jarvisResponse = "Очистка памяти диалога..."
                        currentState = JarvisState.IDLE
                        statusText = "ГОТОВ К СЛЕДУЮЩЕЙ КОМАНДЕ"

                        coroutineScope.launch(Dispatchers.IO) {
                            nativeBridge.clearHistory()
                            withContext(Dispatchers.Main) {
                                jarvisResponse = "Память диалога очищена, сэр."
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))
                ) {
                    Text("СБРОС КОНТЕКСТА", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }
                }