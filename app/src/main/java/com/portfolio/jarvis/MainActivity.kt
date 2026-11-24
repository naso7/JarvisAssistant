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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import android.net.Uri
import android.provider.OpenableColumns

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

    private var modelsMissing by mutableStateOf(false)

    // Выбор файла модели слуха (stt_model.bin)
    private val pickSttLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importModelFile(it, "stt_model.bin") }
    }

    // Выбор файла модели мозга (llm_model.gguf)
    private val pickLlmLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importModelFile(it, "llm_model.gguf") }
    }
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
                    modelsMissing = true
                    statusText = "ТРЕБУЕТСЯ УСТАНОВКА МОДЕЛЕЙ"
                    val missingList = buildString {
                        if (!whisperExists) append("• Модель слуха (stt_model.bin)\n")
                        if (!llamaExists) append("• Модель мозга (llm_model.gguf)\n")
                    }
                    jarvisResponse = "Не найдены файлы нейросетей:\n$missingList\nВыберите их из папки «Загрузки» кнопками ниже:"
                }
                return@Thread
            }

            runOnUiThread { modelsMissing = false }

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

    private fun importModelFile(uri: Uri, targetFileName: String) {
        Thread {
            runOnUiThread {
                modelsMissing = false
                currentState = JarvisState.LOADING
                statusText = "КОПИРОВАНИЕ $targetFileName..."
                jarvisResponse = "Пожалуйста, подождите, идет перенос нейросети в рабочую директорию..."
            }
            try {
                val baseDir = getExternalFilesDir(null)
                val targetFile = File(baseDir, targetFileName)

                contentResolver.openInputStream(uri)?.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 65536) // Быстрое копирование блоками по 64 КБ
                    }
                }

                runOnUiThread {
                    jarvisResponse = "Файл $targetFileName успешно установлен!"
                }
                // Сразу проверяем, все ли файлы на месте, и запускаем движок
                loadModelsAsync()
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    modelsMissing = true
                    statusText = "ОШИБКА КОПИРОВАНИЯ ФАЙЛА"
                    jarvisResponse = "Не удалось скопировать файл: ${e.message}"
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
    // Плавное бесконечное вращение внутренних турбин реактора
        val rotationAngle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(12000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotationAngle"
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
                        val maxR = size.minDimension / 2.15f

                        // 1. Внешнее радиальное свечение вокруг реактора
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(reactorColor.copy(alpha = 0.4f), Color.Transparent),
                                center = center,
                                radius = maxR * 1.15f
                            ),
                            radius = maxR * 1.15f,
                            center = center
                        )

                        // 2. Светящийся энергетический круг под катушками
                        val glowRingRadius = maxR * 0.80f
                        drawCircle(
                            color = reactorColor.copy(alpha = 0.85f),
                            radius = glowRingRadius,
                            center = center,
                            style = Stroke(width = maxR * 0.22f)
                        )

                        // Темный обод по самому краю
                        drawCircle(
                            color = Color(0xFF0A1424),
                            radius = maxR * 0.92f,
                            center = center,
                            style = Stroke(width = 3.dp.toPx())
                        )

                        // 3. Вращающиеся внутренние концентрические кольца (как на картинке)
                        rotate(degrees = rotationAngle, pivot = center) {
                            drawCircle(
                                color = reactorColor.copy(alpha = 0.5f),
                                radius = maxR * 0.58f,
                                center = center,
                                style = Stroke(width = 2.dp.toPx())
                            )
                            drawCircle(
                                color = Color(0xFF80D8FF).copy(alpha = 0.35f),
                                radius = maxR * 0.49f,
                                center = center,
                                style = Stroke(width = 3.dp.toPx())
                            )
                            drawCircle(
                                color = reactorColor.copy(alpha = 0.6f),
                                radius = maxR * 0.41f,
                                center = center,
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                        }

                        // 4. 10 массивных темных магнитных катушек по окружности (как на фото)
                        val coilCount = 10
                        for (i in 0 until coilCount) {
                            val angle = i * (360f / coilCount)
                            rotate(degrees = angle, pivot = center) {
                                val coilWidth = maxR * 0.24f
                                val coilHeight = maxR * 0.31f
                                val topY = center.y - maxR * 0.98f

                                // Темный металлический корпус катушки
                                drawRoundRect(
                                    color = Color(0xFF070D19),
                                    topLeft = Offset(center.x - coilWidth / 2, topY),
                                    size = androidx.compose.ui.geometry.Size(coilWidth, coilHeight),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
                                )
                                // Тонкая светящаяся окантовка катушки
                                drawRoundRect(
                                    color = reactorColor.copy(alpha = 0.7f),
                                    topLeft = Offset(center.x - coilWidth / 2, topY),
                                    size = androidx.compose.ui.geometry.Size(coilWidth, coilHeight),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                                    style = Stroke(width = 1.5.dp.toPx())
                                )
                            }
                        }

                        // 5. Три массивные опорные перемычки (на 120, 240 и 360 градусов)
                        for (i in 0 until 3) {
                            val angle = i * 120f + 60f
                            rotate(degrees = angle, pivot = center) {
                                val strutWidth = maxR * 0.14f
                                val strutHeight = maxR * 0.34f
                                drawRect(
                                    color = Color(0xFF070D19),
                                    topLeft = Offset(center.x - strutWidth / 2, center.y - maxR * 0.68f),
                                    size = androidx.compose.ui.geometry.Size(strutWidth, strutHeight)
                                )
                            }
                        }

                        // 6. Яркий пульсирующий центральный сердечник с белым ядром
                        val coreRadius = maxR * 0.33f * pulseScale
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White,
                                    Color(0xFFE0F7FA),
                                    reactorColor
                                ),
                                center = center,
                                radius = coreRadius
                            ),
                            radius = coreRadius,
                            center = center
                        )
                    }


                    val buttonLabel = if (currentState == JarvisState.LISTENING) "ГОВОРИТЕ" else "ЗАЖАТЬ"

                    Box(contentAlignment = Alignment.Center) {
                        // Нижний слой: темная объемная окантовка букв
                        Text(
                            text = buttonLabel,
                            style = TextStyle(
                                color = Color(0xFF060B14),
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 15.sp,
                                letterSpacing = 1.5.sp,
                                drawStyle = Stroke(width = 12f)
                            )
                        )
                        // Верхний слой: яркая белая заливка внутри
                        Text(
                            text = buttonLabel,
                            style = TextStyle(
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 15.sp,
                                letterSpacing = 1.5.sp
                            )
                        )
                    }

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

                if (modelsMissing) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { pickSttLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))
                        ) {
                            Text("ИМПОРТ STT (.bin)", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { pickLlmLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFB300))
                        ) {
                            Text("ИМПОРТ LLM (.gguf)", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                } else {
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
                }