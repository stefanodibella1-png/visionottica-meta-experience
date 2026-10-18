/*
 * VisionOttica Meta Experience — 3D showroom (web) hosted in a WebView, bridged to the
 * Meta Wearables Device Access Toolkit state owned by WearablesViewModel.
 *
 * The 3D experience is the same web app published as an artifact, bundled offline in
 * assets/web and served through WebViewAssetLoader (ES modules need an https origin).
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.experience

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesUiState
import java.util.Locale
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val HOST = "appassets.androidplatform.net"

private fun mimeFor(path: String): String =
    when (path.substringAfterLast('.', "").lowercase()) {
      "html" -> "text/html"
      "js", "mjs" -> "text/javascript"
      "json" -> "application/json"
      "css" -> "text/css"
      "png" -> "image/png"
      "jpg", "jpeg" -> "image/jpeg"
      "webp" -> "image/webp"
      "svg" -> "image/svg+xml"
      "mp3" -> "audio/mpeg"
      "wav" -> "audio/wav"
      "glb" -> "model/gltf-binary"
      else -> "application/octet-stream"
    }

private fun isText(path: String) =
    path.substringAfterLast('.', "").lowercase() in setOf("html", "js", "mjs", "json", "css", "svg")
private const val START_URL = "https://$HOST/assets/web/index.html"

/** JSON snapshot of the DAT state that the web layer shows in its status chip. */
fun WearablesUiState.toBridgeJson(): String =
    JSONObject()
        .put("registered", isRegistered)
        .put("registering", isRegistering)
        .put("devices", devices.size)
        .put("active", hasActiveDevice)
        .put("canRegister", canRegister)
        .put("firmwareUpdate", isFirmwareUpdateRequired)
        .put("dat", "1.0.1")
        .toString()

/**
 * Methods the web page may call as `window.VOBridge.*`. Only our own bundled page is ever loaded
 * (navigation to other origins is sent to the system browser), so the bridge is not exposed to
 * third-party content. JavaScript calls arrive on a binder thread: hop to the main thread.
 */
class VOBridge(
    private val onConnect: () -> Unit,
    private val onOpenGlassesCamera: () -> Unit,
    private val status: () -> String,
    private val tts: NativeVoice,
    private val onWakeEnabled: (Boolean) -> Unit,
    private val listener: () -> SofiaListener?,
    private val js: (String) -> Unit,
    private val context: android.content.Context,
    private val requestPhotoPermission: ((Boolean) -> Unit) -> Unit,
) {
  private val main = Handler(Looper.getMainLooper())

  // ---- photo taken with the glasses → shown in the app (via the gallery import of the Meta AI app)
  @JavascriptInterface
  fun watchPhoto(sinceMs: Double, timeoutMs: Double) {
    main.post {
      requestPhotoPermission { ok ->
        if (!ok) {
          js("window.voNative && voNative.onPhoto(null, 'denied')")
          return@requestPhotoPermission
        }
        Thread {
              val url = PhotoWatcher.waitForNewPhoto(context, sinceMs.toLong(), timeoutMs.toLong())
              main.post {
                js("window.voNative && voNative.onPhoto(${if (url == null) "null" else JSONObject.quote(url)}, ${if (url == null) "'timeout'" else "null"})")
              }
            }
            .start()
      }
    }
  }

  @JavascriptInterface fun cancelPhoto() = PhotoWatcher.cancel()

  @JavascriptInterface fun connectGlasses() = main.post { onConnect() }.let {}

  @JavascriptInterface fun openGlassesCamera() = main.post { onOpenGlassesCamera() }.let {}

  @JavascriptInterface fun status(): String = status.invoke()

  /** Native Android text-to-speech (the WebView has no speechSynthesis). */
  @JavascriptInterface fun speak(text: String, lang: String): Boolean = tts.speak(text, lang)

  @JavascriptInterface fun stopSpeaking() = tts.stop()

  @JavascriptInterface fun isSpeaking(): Boolean = tts.isSpeaking()

  // ---- "Ehi Sofia"
  @JavascriptInterface fun setWakeEnabled(on: Boolean) = main.post { onWakeEnabled(on) }.let {}

  @JavascriptInterface fun listenQuestion() = main.post { listener()?.listenQuestion() }.let {}

  @JavascriptInterface fun resumeWake() = main.post { listener()?.resumeWake() }.let {}

  @JavascriptInterface fun pauseWake(p: Boolean) = main.post { listener()?.pause(p) }.let {}

  // ---- Translator (ML Kit, on device)
  @JavascriptInterface fun canTranslate(): Boolean = true

  @JavascriptInterface
  fun translate(id: Int, text: String, src: String, tgt: String) {
    main.post {
      SofiaTranslator.translate(
          text,
          src,
          tgt,
          onStatus = { st -> js("window.voNative && voNative.onTranslateStatus && voNative.onTranslateStatus($id, '$st')") },
      ) { out, err ->
        js(
            "window.voNative && voNative.onTranslate($id, ${JSONObject.quote(out ?: "")}, ${JSONObject.quote(err ?: "")})"
        )
      }
    }
  }

  // ---- Sofia's Claude answers via the VisionOttica server
  @JavascriptInterface fun hasAi(): Boolean = SofiaBrain.isConfigured

  @JavascriptInterface
  fun askSofia(id: Int, payloadJson: String) {
    Thread {
          val answer = SofiaBrain.ask(payloadJson)
          val err = answer == null
          main.post {
            js("window.voNative && voNative.onAnswer($id, ${org.json.JSONObject.quote(answer ?: "")}, $err)")
          }
        }
        .start()
  }
}

/** Thin wrapper around Android TextToSpeech, preferring an Italian female voice when present. */
class NativeVoice(context: android.content.Context) {
  @Volatile private var ready = false
  @Volatile private var speaking = false
  private val tts: TextToSpeech =
      TextToSpeech(context.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS }

  init {
    tts.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) {
            speaking = true
          }

          override fun onDone(utteranceId: String?) {
            speaking = false
          }

          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String?) {
            speaking = false
          }
        }
    )
  }

  fun speak(text: String, lang: String): Boolean {
    if (!ready || text.isBlank()) return false
    val locale = Locale.forLanguageTag(lang.ifBlank { "it-IT" })
    tts.language = locale
    tts.voices
        ?.filter { it.locale.language == locale.language && !it.isNetworkConnectionRequired }
        ?.sortedByDescending { it.quality }
        ?.firstOrNull()
        ?.let { tts.voice = it }
    speaking = true
    return tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vo-${System.nanoTime()}") ==
        TextToSpeech.SUCCESS
  }

  fun stop() {
    tts.stop()
    speaking = false
  }

  fun isSpeaking(): Boolean = speaking || tts.isSpeaking

  fun shutdown() = tts.shutdown()
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun ExperienceScreen(
    uiState: WearablesUiState,
    onConnect: () -> Unit,
    onOpenGlassesCamera: () -> Unit,
    onRequestRecordAudioPermission: suspend () -> Boolean,
    modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val voice = remember { NativeVoice(context) }
  val webRef = remember { arrayOfNulls<WebView>(1) }
  val runJs: (String) -> Unit = { code -> webRef[0]?.evaluateJavascript(code, null) }
  val sofiaListener = remember { SofiaListener(context) { code -> webRef[0]?.evaluateJavascript(code, null) } }
  val latestState = remember { arrayOf(uiState) }
  latestState[0] = uiState

  // <input type="file"> support (phone camera photo, audio file) for the web panels.
  val fileCallback = remember { arrayOfNulls<ValueCallback<Array<Uri>>>(1) }
  val fileLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback[0]?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
        fileCallback[0] = null
      }

  val photoPermCb = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
  val photoPermLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        photoPermCb[0]?.invoke(granted)
        photoPermCb[0] = null
      }

  val webView = remember {
    // Serve bundled assets with explicit MIME types: ES modules are refused unless served as
    // JavaScript, and a refused module leaves the page stuck on the loading screen.
    val assets = context.assets
    val loader =
        WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/") { path ->
              runCatching {
                    WebResourceResponse(mimeFor(path), if (isText(path)) "utf-8" else null, assets.open(path))
                  }
                  .getOrNull()
            }
            .build()
    WebView(context).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.mediaPlaybackRequiresUserGesture = false
      // The page sizes its own UI; the system font-size setting would enlarge it until panels overlap.
      settings.textZoom = 100
      settings.allowFileAccess = false
      settings.allowContentAccess = false
      webViewClient =
          object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
              val url = request.url
              if (url.host == HOST) return false
              // tel:, mailto:, external sites → system apps
              runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
              return true
            }
          }
      webChromeClient =
          object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
              fileCallback[0]?.onReceiveValue(null)
              fileCallback[0] = filePathCallback
              return runCatching { fileLauncher.launch(fileChooserParams.createIntent()) }.isSuccess
            }
          }
      addJavascriptInterface(
          VOBridge(
              onConnect = onConnect,
              onOpenGlassesCamera = onOpenGlassesCamera,
              status = { latestState[0].toBridgeJson() },
              tts = voice,
              onWakeEnabled = { on ->
                if (!on) sofiaListener.setEnabled(false)
                else
                    scope.launch {
                      if (onRequestRecordAudioPermission()) sofiaListener.setEnabled(true)
                      else runJs("window.voNative && voNative.onListen('denied')")
                    }
              },
              listener = { sofiaListener },
              js = runJs,
              context = context,
              requestPhotoPermission = { cb ->
                val perm =
                    if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
                    else android.Manifest.permission.READ_EXTERNAL_STORAGE
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED)
                    cb(true)
                else {
                  photoPermCb[0] = cb
                  photoPermLauncher.launch(perm)
                }
              },
          ),
          "VOBridge",
      )
      webRef[0] = this
      loadUrl(START_URL)
    }
  }

  // Push DAT state changes into the page (status chip, connect flow).
  LaunchedEffect(uiState) {
    webView.evaluateJavascript(
        "window.voNative && window.voNative.onStatus(${uiState.toBridgeJson()})",
        null,
    )
  }

  DisposableEffect(Unit) {
    webView.onResume()
    onDispose {
      webView.onPause()
      voice.stop()
      sofiaListener.destroy()
    }
  }

  AndroidView(factory = { webView }, modifier = modifier.fillMaxSize())
}
