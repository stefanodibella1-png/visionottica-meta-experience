/*
 * "Ehi Sofia" — hands-free wake word + one question, using Android's SpeechRecognizer (it-IT).
 *
 * Modes:
 *  WAKE     : listens in a loop; any phrase containing "Sofia" wakes her up. If the wake phrase
 *             already contains a question ("Ehi Sofia, quanto dura la batteria?") it is answered
 *             directly.
 *  QUESTION : one-shot listening for the visitor's question after Sofia's greeting.
 *  IDLE     : not listening (Sofia is thinking/speaking).
 *
 * The web layer pauses listening while Sofia speaks so she never wakes herself up.
 * Microphone: the phone's, or the glasses' when Android routes Bluetooth audio input to them.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.experience

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import org.json.JSONObject

class SofiaListener(
    private val context: Context,
    /** Runs a JavaScript snippet in the page (main thread). */
    private val js: (String) -> Unit,
) {
  private enum class Mode {
    WAKE,
    QUESTION,
    IDLE,
  }

  private val main = Handler(Looper.getMainLooper())
  private var recognizer: SpeechRecognizer? = null
  private var mode = Mode.IDLE
  private var paused = false
  var enabled = false
    private set

  private val wakeRegex = Regex("\\b(sofia|sophia|sofìa)\\b")
  private val startRunnable = Runnable { startListening() }

  fun setEnabled(on: Boolean) {
    enabled = on
    if (on) {
      mode = Mode.WAKE
      startSoon(0)
    } else {
      mode = Mode.IDLE
      stopRecognizer()
    }
  }

  /** Called by the page while Sofia is speaking (true) and when she has finished (false). */
  fun pause(p: Boolean) {
    paused = p
    if (p) {
      if (mode == Mode.WAKE) cancelRecognizer()
    } else if (enabled && mode == Mode.WAKE) {
      startSoon(450)
    }
  }

  fun listenQuestion() {
    mode = Mode.QUESTION
    startSoon(120)
  }

  fun resumeWake() {
    mode = if (enabled) Mode.WAKE else Mode.IDLE
    if (enabled) startSoon(500)
  }

  fun destroy() {
    enabled = false
    main.removeCallbacks(startRunnable)
    recognizer?.destroy()
    recognizer = null
  }

  private fun startSoon(delayMs: Long) {
    main.removeCallbacks(startRunnable)
    main.postDelayed(startRunnable, delayMs)
  }

  private fun startListening() {
    if (mode == Mode.IDLE || (mode == Mode.WAKE && (paused || !enabled))) return
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      js("window.voNative && voNative.onListen('unavailable')")
      return
    }
    val r =
        recognizer
            ?: SpeechRecognizer.createSpeechRecognizer(context).also {
              it.setRecognitionListener(listener)
              recognizer = it
            }
    val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
          putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
    runCatching {
          r.cancel()
          r.startListening(intent)
        }
        .onFailure { Log.w(TAG, "startListening failed", it) }
    js("window.voNative && voNative.onListen('${mode.name.lowercase()}')")
  }

  private fun cancelRecognizer() = runCatching { recognizer?.cancel() }

  private fun stopRecognizer() {
    main.removeCallbacks(startRunnable)
    cancelRecognizer()
  }

  private fun texts(b: Bundle?): List<String> =
      b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()

  private val metaWord = Regex("\\b(meta|metà|metta|mehta)\\b")
  private val musicWord = Regex("\\b(musica|music|canzone|brano|playlist)\\b")

  /** "Hey Meta, …" said by the visitor (the glasses answer it): tell the page what kind of command it was. */
  private fun metaKind(t: String): String =
      when {
        musicWord.containsMatchIn(t) -> "music"
        Regex("\\b(foto|fotografia|scatta)").containsMatchIn(t) -> "photo"
        Regex("\\b(video|registra)").containsMatchIn(t) -> "video"
        Regex("tradu").containsMatchIn(t) -> "translate"
        else -> "other"
      }

  private fun checkWake(b: Bundle?, isFinal: Boolean) {
    if (isFinal) {
      val all = texts(b).map { it.lowercase() }
      val meta = all.firstOrNull { metaWord.containsMatchIn(it) && !wakeRegex.containsMatchIn(it) }
      val sofiaMusic = all.firstOrNull { wakeRegex.containsMatchIn(it) && musicWord.containsMatchIn(it) }
      if (meta != null || sofiaMusic != null) {
        val kind = if (sofiaMusic != null) "music" else metaKind(meta!!)
        js("window.voNative && voNative.onMetaCommand && voNative.onMetaCommand('$kind', ${JSONObject.quote(meta ?: sofiaMusic!!)})")
        if (enabled && !paused) startSoon(400)
        return
      }
    }
    val hit = texts(b).map { it.lowercase() }.firstOrNull { wakeRegex.containsMatchIn(it) }
    if (hit != null) {
      mode = Mode.IDLE
      cancelRecognizer()
      val after = hit.substring(wakeRegex.find(hit)!!.range.last + 1).trim(' ', ',', '.', '!', '?')
      if (isFinal && after.split(Regex("\\s+")).size >= 3) emitHeard(after)
      else js("window.voNative && voNative.onWake()")
    } else if (isFinal && enabled && !paused) {
      startSoon(150)
    }
  }

  private fun emitHeard(text: String) {
    mode = Mode.IDLE
    js("window.voNative && voNative.onHeard(${JSONObject.quote(text)})")
  }

  private val listener =
      object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
          if (mode == Mode.WAKE) checkWake(partialResults, isFinal = false)
        }

        override fun onResults(results: Bundle?) {
          when (mode) {
            Mode.WAKE -> checkWake(results, isFinal = true)
            Mode.QUESTION -> emitHeard(texts(results).firstOrNull().orEmpty())
            Mode.IDLE -> Unit
          }
        }

        override fun onError(error: Int) {
          when (mode) {
            Mode.QUESTION -> emitHeard("")
            Mode.WAKE ->
                if (enabled && !paused) {
                  startSoon(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 900 else 250)
                }
            Mode.IDLE -> Unit
          }
        }
      }

  companion object {
    private const val TAG = "SofiaListener"
  }
}
