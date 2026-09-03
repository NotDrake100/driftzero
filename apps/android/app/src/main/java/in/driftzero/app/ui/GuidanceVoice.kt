package `in`.driftzero.app.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Speaks turn cues and mirrors every utterance to logcat as
 * [LOG_TAG] so a drive log can prove the voice path fired.
 */
internal class GuidanceVoice(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile
    private var ready: Boolean = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.US
        }
        Log.i(LOG_TAG, if (ready) "tts ready" else "tts unavailable")
    }

    fun speak(text: String) {
        Log.i(LOG_TAG, text)
        if (!ready || text.isEmpty()) {
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "driftzero-cue")
    }

    fun close() {
        ready = false
        tts.stop()
        tts.shutdown()
    }

    companion object {
        const val LOG_TAG: String = "DriftZeroVoice"
    }
}
