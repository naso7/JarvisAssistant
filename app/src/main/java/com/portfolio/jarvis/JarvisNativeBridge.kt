package com.portfolio.jarvis

class JarvisNativeBridge(private val onTokenCallback: (String) -> Unit) {

    companion object {
        init {
            System.loadLibrary("jarvis")
        }
    }

    @Suppress("unused")
    fun onTokenGenerated(token: String) {
        onTokenCallback(token)
    }

    external fun initEngines(whisperModelPath: String, llamaModelPath: String): Boolean
    external fun transcribeAudio(audioSamples: FloatArray): String
    external fun askJarvis(userText: String): String
    external fun stopGeneration()
    external fun clearHistory()
}