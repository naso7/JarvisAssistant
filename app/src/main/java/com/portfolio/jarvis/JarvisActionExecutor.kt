package com.portfolio.jarvis

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock

class JarvisActionExecutor(private val context: Context) {

    fun processAndCleanResponse(rawResponse: String): String {
        var cleanText = rawResponse

        // 1. Включение фонарика
        if (cleanText.contains("[ACTION:FLASHLIGHT_ON]")) {
            toggleFlashlight(true)
            cleanText = cleanText.replace("[ACTION:FLASHLIGHT_ON]", "")
        }

        // 2. Выключение фонарика
        if (cleanText.contains("[ACTION:FLASHLIGHT_OFF]")) {
            toggleFlashlight(false)
            cleanText = cleanText.replace("[ACTION:FLASHLIGHT_OFF]", "")
        }

        // 3. Установка таймера (формат [ACTION:TIMER:5])
        val timerRegex = Regex("""\[ACTION:TIMER:(\d+)\]""")
        val match = timerRegex.find(cleanText)
        if (match != null) {
            // Берем элемент с индексом 1 (первая группа захвата в скобках)
            val minutes = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 1
            setTimer(minutes)
            cleanText = cleanText.replace(match.value, "")
        }

        return cleanText.trim()
    }

    private fun toggleFlashlight(state: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            // Берем первую камеру из списка (обычно это основная камера со вспышкой)
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            cameraManager.setTorchMode(cameraId, state)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setTimer(minutes: Int) {
        try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Таймер Джарвиса")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
