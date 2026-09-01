package com.kryptos.android.keyboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import java.util.concurrent.Executor

class VoiceInput(context: Context, private val listener: Listener) {

    private val app: Context = context.applicationContext

    interface Listener {
        fun onVoicePartial(text: String)
        fun onVoiceResult(text: String)
        fun onVoiceFailure(failure: Failure)
    }

    enum class Failure { PERMISSION, LANGUAGE, NO_SPEECH, BUSY, UNAVAILABLE, OTHER }

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    fun start(languageTag: String) {
        if (listening) return
        if (!isSupported(app)) { listener.onVoiceFailure(Failure.UNAVAILABLE); return }
        if (!hasPermission(app)) { listener.onVoiceFailure(Failure.PERMISSION); return }
        val speech = recognizer ?: runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
        }.getOrNull()
        if (speech == null) { listener.onVoiceFailure(Failure.UNAVAILABLE); return }
        recognizer = speech
        speech.setRecognitionListener(callbacks)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            if (Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
                putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            }
        }
        listening = true
        runCatching { speech.startListening(intent) }.onFailure {
            listening = false
            listener.onVoiceFailure(Failure.OTHER)
        }
    }

    fun stop() {
        if (!listening) return
        runCatching { recognizer?.stopListening() }
    }

    fun cancel() {
        listening = false
        runCatching { recognizer?.cancel() }
    }

    fun release() {
        listening = false
        val speech = recognizer ?: return
        recognizer = null
        runCatching { speech.cancel() }
        runCatching { speech.destroy() }
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            if (!listening) return
            listening = false
            listener.onVoiceFailure(failureOf(error))
        }

        override fun onResults(results: Bundle?) {
            if (!listening) return
            listening = false
            listener.onVoiceResult(bestOf(results))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!listening) return
            listener.onVoicePartial(bestOf(partialResults))
        }
    }

    private fun bestOf(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

    private fun failureOf(error: Int): Failure = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Failure.PERMISSION
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> Failure.LANGUAGE
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Failure.NO_SPEECH
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Failure.BUSY
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> Failure.UNAVAILABLE
        else -> Failure.OTHER
    }

    private class ModelRequest(private val speech: SpeechRecognizer) : ModelDownloadListener {
        private val main = Handler(Looper.getMainLooper())
        private val expiry = Runnable { done() }
        private var finished = false

        fun start(intent: Intent) {
            arm()
            val started = runCatching {
                speech.triggerModelDownload(intent, Executor { main.post(it) }, this)
            }.isSuccess
            if (!started) done()
        }

        override fun onProgress(completedPercent: Int) = arm()
        override fun onScheduled() = arm()
        override fun onSuccess() = done()
        override fun onError(error: Int) = done()

        private fun arm() {
            main.removeCallbacks(expiry)
            main.postDelayed(expiry, MODEL_TIMEOUT_MS)
        }

        private fun done() {
            if (finished) return
            finished = true
            main.removeCallbacks(expiry)
            runCatching { speech.destroy() }
        }
    }

    companion object {
        private const val OPENERS = "([{«\"'“„‘"
        private const val MODEL_TIMEOUT_MS = 10 * 60 * 1000L

        fun isSupported(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        fun hasPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun canRequestModel(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 33 && isSupported(context)

        private fun modelIntent(context: Context, tag: String): Intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }

        fun requestModel(context: Context, languageTag: String) {
            val app = context.applicationContext
            if (!canRequestModel(app)) return
            val speech = runCatching {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
            }.getOrNull() ?: return
            if (Build.VERSION.SDK_INT >= 34) {
                ModelRequest(speech).start(modelIntent(app, languageTag))
                return
            }
            runCatching { speech.triggerModelDownload(modelIntent(app, languageTag)) }
            runCatching { speech.destroy() }
        }

        fun languageTag(code: String): String {
            val system = Locale.getDefault()
            if (system.language.equals(code, ignoreCase = true) && system.country.length == 2) {
                return "$code-${system.country.uppercase(Locale.ROOT)}"
            }
            return when (code) {
                "ru" -> "ru-RU"
                "de" -> "de-DE"
                "zh" -> "zh-CN"
                else -> "en-US"
            }
        }

        fun needsLeadingSpace(before: String, chinese: Boolean): Boolean {
            if (chinese) return false
            val last = before.lastOrNull() ?: return false
            return !last.isWhitespace() && last !in OPENERS
        }
    }
}
