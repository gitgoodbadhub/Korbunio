package de.lesecuritae.korbuino

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Pure text handling of the continuous dictation (tested on the JVM). */
object SpeechText {
    /** Every pause of the speaker ends one piece; pieces are joined with a comma so they read as separate products. */
    fun append(text: String, piece: String): String {
        val clean = piece.trim()
        if (clean.isEmpty()) return text
        val base = text.trim().trimEnd(',').trim()
        return if (base.isEmpty()) clean else "$base, $clean"
    }

    /** Stop after this many silent attempts in a row (each lasts a few seconds). */
    const val MAX_SILENT_ROUNDS = 2

    /** Hard limit of one dictation. */
    const val MAX_MILLIS = 120_000L
}

/**
 * Keeps listening across pauses until [stop] is called (Korbunio Plus).
 *
 * Android's recogniser ends a session after a short pause; this class starts the next session right
 * away and collects the pieces. It uses the phone's speech service (on the Galaxy S25 Samsung's or
 * Google's, offline where the language pack is installed). Needs the RECORD_AUDIO permission.
 */
class ContinuousSpeech(
    context: Context,
    private val onText: (text: String, partial: String) -> Unit,
    private val onEnd: (error: String?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    private var active = false
    private var text = ""
    private var silentRounds = 0
    private var startedAt = 0L

    val available: Boolean get() = recognizer != null

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        // Requests for longer pauses; some recognisers ignore them, the restart below covers that.
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)

    init {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                if (!active) return
                onText(text, best(partialResults))
            }

            override fun onResults(results: Bundle?) {
                val piece = best(results)
                text = SpeechText.append(text, piece)
                silentRounds = if (piece.isBlank()) silentRounds + 1 else 0
                onText(text, "")
                next()
            }

            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> { silentRounds++; next() }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> if (active) handler.postDelayed({ listen() }, 400)
                    SpeechRecognizer.ERROR_CLIENT -> if (active) handler.postDelayed({ listen() }, 300)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> finish("Mikrofon-Berechtigung fehlt")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        finish("Spracherkennung braucht Netz oder das deutsche Offline-Sprachpaket")
                    else -> finish(if (text.isBlank()) "Spracherkennung nicht möglich (Fehler $error)" else null)
                }
            }
        })
    }

    /** Starts listening; recognised pieces are appended to [prefix]. */
    fun start(prefix: String) {
        val r = recognizer ?: return onEnd("Keine Spracherkennung auf diesem Gerät")
        text = prefix.trim()
        silentRounds = 0
        startedAt = System.currentTimeMillis()
        active = true
        onText(text, "")
        r.cancel()
        listen()
    }

    /** Ends the dictation; what was said so far is kept. */
    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        onEnd(null)
    }

    fun destroy() {
        active = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
    }

    private fun next() {
        if (!active) return
        if (silentRounds >= SpeechText.MAX_SILENT_ROUNDS || System.currentTimeMillis() - startedAt > SpeechText.MAX_MILLIS) {
            finish(null)
        } else handler.postDelayed({ listen() }, 150)
    }

    private fun listen() {
        if (!active) return
        runCatching { recognizer?.startListening(intent) }.onFailure { finish("Spracherkennung nicht möglich") }
    }

    private fun finish(error: String?) {
        if (!active) return
        active = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        onEnd(error)
    }

    private fun best(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
}
