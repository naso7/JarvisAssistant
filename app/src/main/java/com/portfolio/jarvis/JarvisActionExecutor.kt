package com.portfolio.jarvis

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import android.util.Log
import java.util.Calendar

class JarvisActionExecutor(private val context: Context) {

    // Словарь популярных русских произношений для англоязычных приложений
    private val appAliases = mapOf(
        // Игры
        "майнкрафт" to "minecraft",
        "майн" to "minecraft",
        "маинкрафт" to "minecraft",
        "роблокс" to "roblox",
        "роблакс" to "roblox",
        "роблекс" to "roblox",

        // Приложения
        "яндекс музыка" to "музыка",
        "яндекс музыку" to "музыка",
        "музыка" to "музыка",
        "ютуб" to "youtube",
        "ютьюб" to "youtube",
        "телеграм" to "telegram",
        "телеграмм" to "telegram",
        "телега" to "telegram",
        "ватсап" to "whatsapp",
        "вотсап" to "whatsapp",
        "вк" to "vk",
        "вконтакте" to "vk",
        "браузер" to "chrome",
        "хром" to "chrome",
        "яндекс" to "yandex",
        "карты" to "maps",
        "калькулятор" to "calculator",
        "камера" to "camera",
        "настройки" to "settings",
        "галерея" to "gallery",
        "ozone" to "ozon",
        "озон" to "ozon",
        "азон" to "ozon",

        )

    fun processAndCleanResponse(rawResponse: String): String {

        Log.d("JarvisAction", "Сырой текст для парсинга: '$rawResponse'")
        var cleanText = rawResponse

        // 1. Запуск любого приложения (ВЫСШИЙ ПРИОРИТЕТ — предотвращает ложное срабатывание фонарика)
        val openAppRegex = Regex("""\[ACTION:OPEN_APP:([^\]]+)\]""", RegexOption.IGNORE_CASE)
        val openAppMatch = openAppRegex.find(cleanText)

        if (openAppMatch != null) {
            val rawAppName = openAppMatch.groupValues.getOrNull(1)?.trim() ?: ""
            cleanText = cleanText.replace(openAppMatch.value, "")

            if (rawAppName.isNotEmpty()) {
                val launched = launchAppByName(rawAppName)
                if (!launched) {
                    cleanText = "К сожалению, приложение «$rawAppName» не найдено на этом устройстве, сэр."
                }
            }
        } else {
            // 2. Таймер на N минут: [ACTION:TIMER:10]
            val timerRegex = Regex("""\[ACTION:TIMER:(\d+)\]""", RegexOption.IGNORE_CASE)
            timerRegex.find(cleanText)?.let { match ->
                val minutes = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 1
                Log.d("JarvisAction", "Запуск таймера на $minutes мин.")
                setTimer(minutes)
                cleanText = cleanText.replace(match.value, "")
            }

            // 3. Будильник на точное время: [ACTION:ALARM_TIME:07:30]
            val alarmTimeRegex = Regex("""\[ACTION:ALARM_TIME:(\d{1,2})[:.](\d{2})\]""", RegexOption.IGNORE_CASE)
            alarmTimeRegex.find(cleanText)?.let { match ->
                val hour = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 8
                val minute = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                Log.d("JarvisAction", "Установка будильника на $hour:$minute")
                setAlarm(hour, minute)
                cleanText = cleanText.replace(match.value, "")
            }

            // 4. Будильник через N минут: [ACTION:ALARM_IN:10]
            val alarmInRegex = Regex("""\[ACTION:ALARM_IN:(\d+)\]""", RegexOption.IGNORE_CASE)
            alarmInRegex.find(cleanText)?.let { match ->
                val addMinutes = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 5
                val calendar = Calendar.getInstance().apply {
                    add(Calendar.MINUTE, addMinutes)
                }
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                val minute = calendar.get(Calendar.MINUTE)
                Log.d("JarvisAction", "Установка будильника через $addMinutes мин. (на $hour:$minute)")
                setAlarm(hour, minute)
                cleanText = cleanText.replace(match.value, "")
            }

            // 5. Включение / выключение фонарика (срабатывает только если не было других команд)
            if (cleanText.contains("[ACTION:FLASHLIGHT_ON]", ignoreCase = true)) {
                toggleFlashlight(true)
            }
            if (cleanText.contains("[ACTION:FLASHLIGHT_OFF]", ignoreCase = true)) {
                toggleFlashlight(false)
            }
        }

        // 6. Очищаем все оставшиеся или дублирующиеся технические теги [ACTION:...]
        cleanText = cleanText.replace(Regex("""\[ACTION:[^\]]*\]""", RegexOption.IGNORE_CASE), "")

        return cleanText.trim()
    }

    private fun launchAppByName(requestedName: String): Boolean {
        try {
            val pm = context.packageManager
            val normalizedQuery = requestedName.lowercase().trim()
            // Проверяем словарь синонимов (например "ютуб" -> "youtube")
            val searchTarget = appAliases[normalizedQuery] ?: normalizedQuery

            // Получаем список всех приложений, у которых есть иконка запуска на рабочем столе
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val installedApps = pm.queryIntentActivities(mainIntent, 0)

            var bestPackageName: String? = null

            // Сначала ищем точное совпадение имени, затем частичное вхождение
            for (resolveInfo in installedApps) {
                val label = resolveInfo.loadLabel(pm).toString().lowercase()
                val pkg = resolveInfo.activityInfo.packageName

                if (label == searchTarget || label == normalizedQuery) {
                    bestPackageName = pkg
                    break
                }
                if (bestPackageName == null && (label.contains(searchTarget) || label.contains(normalizedQuery))) {
                    bestPackageName = pkg
                }
            }

            if (bestPackageName != null) {
                val launchIntent = pm.getLaunchIntentForPackage(bestPackageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launchIntent != null) {
                    Log.d("JarvisAction", "Запуск приложения: $bestPackageName (по запросу '$requestedName')")
                    context.startActivity(launchIntent)
                    return true
                }
            } else {
                Log.w("JarvisAction", "Приложение '$requestedName' ($searchTarget) не найдено")
            }
        } catch (e: Exception) {
            Log.e("JarvisAction", "Ошибка при запуске приложения", e)
        }
        return false
    }

    private fun toggleFlashlight(state: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            cameraManager.setTorchMode(cameraId, state)
        } catch (e: Exception) {
            Log.e("JarvisAction", "Ошибка фонарика", e)
        }
    }

    private fun setTimer(minutes: Int) {
        try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Таймер Джарвиса ($minutes мин)")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("JarvisAction", "Ошибка установки таймера", e)
        }
    }

    private fun setAlarm(hour: Int, minute: Int) {
        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Будильник Джарвиса")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("JarvisAction", "Ошибка установки будильника", e)
        }
    }
}
