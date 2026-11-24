package com.portfolio.jarvis

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import android.util.Log
import java.util.Calendar

class JarvisActionExecutor(private val context: Context) {

    fun processAndCleanResponse(rawResponse: String): String {
        Log.d("JarvisAction", "Сырой текст для парсинга: '$rawResponse'")
        var cleanText = rawResponse

        // 1. Включение фонарика
        if (cleanText.contains("[ACTION:FLASHLIGHT_ON]", ignoreCase = true)) {
            toggleFlashlight(true)
            cleanText = cleanText.replace(Regex("""\[ACTION:FLASHLIGHT_ON\]""", RegexOption.IGNORE_CASE), "")
        }

        // 2. Выключение фонарика
        if (cleanText.contains("[ACTION:FLASHLIGHT_OFF]", ignoreCase = true)) {
            toggleFlashlight(false)
            cleanText = cleanText.replace(Regex("""\[ACTION:FLASHLIGHT_OFF\]""", RegexOption.IGNORE_CASE), "")
        }

        // 3. Установка ТАЙМЕРА на N минут: [ACTION:TIMER:10]
        val timerRegex = Regex("""\[ACTION:TIMER:(\d+)\]""", RegexOption.IGNORE_CASE)
        timerRegex.find(cleanText)?.let { match ->
            val minutes = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 1
            Log.d("JarvisAction", "Запуск таймера на $minutes мин.")
            setTimer(minutes)
            cleanText = cleanText.replace(match.value, "")
        }

        // 4. Установка БУДИЛЬНИКА на точное время (поддерживает 07:30, 7:30, 07.30): [ACTION:ALARM_TIME:07:30]
        val alarmTimeRegex = Regex("""\[ACTION:ALARM_TIME:(\d{1,2})[:.](\d{2})\]""", RegexOption.IGNORE_CASE)
        alarmTimeRegex.find(cleanText)?.let { match ->
            val hour = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 8
            val minute = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            Log.d("JarvisAction", "Установка будильника на $hour:$minute")
            setAlarm(hour, minute)
            cleanText = cleanText.replace(match.value, "")
        }

        // 5. Установка БУДИЛЬНИКА через N минут: [ACTION:ALARM_IN:10]
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

        // Очищаем любые оставшиеся технические теги
        cleanText = cleanText.replace(Regex("""\[ACTION:[^\]]*\]""", RegexOption.IGNORE_CASE), "")

        return cleanText.trim()
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
                // false гарантирует открытие и срабатывание системных часов на OnePlus и Huawei
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("JarvisAction", "Ошибка установки будильника", e)
        }
    }
}
