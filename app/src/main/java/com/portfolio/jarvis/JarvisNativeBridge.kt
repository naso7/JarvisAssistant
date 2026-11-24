package com.portfolio.jarvis

class JarvisNativeBridge(
    private val onTokenCallback: ((String) -> Unit)? = null
) {
    companion object {
        init {
            System.loadLibrary("jarvis")
        }
    }

    external fun initEngines(whisperModelPath: String, llamaModelPath: String): Boolean
    external fun transcribeAudio(audioSamples: FloatArray): String
    external fun askJarvis(userText: String): String
    external fun stopGeneration()
    external fun clearHistory()

    // Этот метод вызывается напрямую из C++ (native-lib.cpp) для потокового вывода букв
    fun onTokenGenerated(token: String) {
        onTokenCallback?.invoke(token)
    }
}
