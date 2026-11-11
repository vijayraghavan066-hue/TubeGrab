package com.vj.spellbee.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Text-to-speech "pronouncer" voice. */
class Pronouncer(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pending: Pair<String, Float>? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            ready = true
            pending?.let { (text, rate) -> say(text, rate) }
            pending = null
        }
    }

    fun say(text: String, rate: Float = 0.9f) {
        if (!ready) {
            pending = text to rate
            return
        }
        tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bee")
    }

    val isSpeaking: Boolean get() = ready && tts.isSpeaking

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    companion object {
        /** "cat" -> "C, A, T" so the voice reads letter names. */
        fun spellOut(word: String): String = word.filter { it.isLetter() }.uppercase().toList().joinToString(", ")
    }
}
