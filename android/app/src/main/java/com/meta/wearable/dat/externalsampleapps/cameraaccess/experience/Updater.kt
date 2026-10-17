/*
 * In-app updater for the VisionOttica demo builds.
 * At launch it reads version.json from the "apk-latest" GitHub release; when a newer build exists it
 * offers "Aggiorna ora", downloads the APK and hands it to Android's installer (the user confirms).
 * Requires the repository (or at least its releases) to be publicly downloadable.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.experience

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

object Updater {
  private const val BASE =
      "https://github.com/stefanodibella1-png/visionottica-meta-experience/releases/download/apk-latest/"

  data class Info(val versionCode: Int, val versionName: String)

  private fun open(url: String): HttpURLConnection =
      (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 10_000
        readTimeout = 30_000
      }

  /** Blocking. Returns the published build if it is newer than this one, else null. */
  fun checkNewer(): Info? =
      runCatching {
            val c = open(BASE + "version.json")
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            Info(j.getInt("versionCode"), j.optString("versionName"))
          }
          .getOrNull()
          ?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }

  /** Blocking. Downloads the APK into cache/updates, reporting progress 0..1. */
  fun download(context: Context, onProgress: (Float) -> Unit): File? =
      runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "VisionOttica-Meta.apk")
            val c = open(BASE + "VisionOttica-Meta.apk")
            if (c.responseCode != 200) return null
            val total = c.contentLengthLong.coerceAtLeast(1)
            c.inputStream.use { input ->
              out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                  val n = input.read(buf)
                  if (n < 0) break
                  output.write(buf, 0, n)
                  done += n
                  onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
              }
            }
            out
          }
          .getOrNull()

  /** Opens Android's installer. Returns false if the user must first allow installs from this app. */
  fun install(context: Context, apk: File): Boolean {
    if (!context.packageManager.canRequestPackageInstalls()) {
      context.startActivity(
          Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
      return false
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    return true
  }
}

@Composable
fun UpdatePrompt() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var info by remember { mutableStateOf<Updater.Info?>(null) }
  var downloading by remember { mutableStateOf(false) }
  var progress by remember { mutableFloatStateOf(0f) }
  var apk by remember { mutableStateOf<File?>(null) }
  var message by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) { info = withContext(Dispatchers.IO) { Updater.checkNewer() } }

  val current = info ?: return
  AlertDialog(
      onDismissRequest = { if (!downloading) info = null },
      title = { Text("Nuova versione disponibile") },
      text = {
        Column {
          Text("VisionOttica Meta ${current.versionName} è pronta. Installata: ${BuildConfig.VERSION_NAME}.")
          if (downloading) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
          }
          message?.let { Text(it, modifier = Modifier.padding(top = 12.dp)) }
        }
      },
      confirmButton = {
        TextButton(
            enabled = !downloading,
            onClick = {
              val ready = apk
              if (ready != null) {
                if (!Updater.install(context, ready))
                    message = "Consenti l'installazione da questa app, poi torna qui e tocca di nuovo Installa."
                return@TextButton
              }
              downloading = true
              message = null
              scope.launch {
                val file = withContext(Dispatchers.IO) { Updater.download(context) { progress = it } }
                downloading = false
                if (file == null) {
                  message = "Download non riuscito. Controlla la connessione e riprova."
                } else {
                  apk = file
                  if (!Updater.install(context, file))
                      message = "Consenti l'installazione da questa app, poi torna qui e tocca Installa."
                }
              }
            },
        ) {
          Text(if (apk != null) "Installa" else if (downloading) "Scarico…" else "Aggiorna ora")
        }
      },
      dismissButton = {
        TextButton(enabled = !downloading, onClick = { info = null }) { Text("Più tardi") }
      },
  )
}
