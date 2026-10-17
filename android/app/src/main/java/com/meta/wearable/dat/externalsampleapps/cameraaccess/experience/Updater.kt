/*
 * In-app updater for the VisionOttica demo builds.
 * At launch it reads version.json from the "apk-latest" GitHub release; when a newer build exists it
 * offers "Aggiorna ora", downloads the APK and hands it to Android's installer (the user confirms).
 * Requires the repository (or at least its releases) to be publicly downloadable.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.experience

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
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

  const val APK_URL = BASE + "VisionOttica-Meta.apk"

  /**
   * Blocking. Downloads the APK with Android's DownloadManager (handles GitHub's redirect to its CDN,
   * retries on flaky connections, shows a system notification), reporting progress 0..1.
   */
  fun download(context: Context, onProgress: (Float) -> Unit): File? =
      runCatching {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
            val out = File(dir, "VisionOttica-Meta.apk").apply { if (exists()) delete() }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id =
                dm.enqueue(
                    DownloadManager.Request(Uri.parse(APK_URL))
                        .setTitle("VisionOttica Meta")
                        .setDescription("Aggiornamento in download")
                        .setMimeType("application/vnd.android.package-archive")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        .setDestinationUri(Uri.fromFile(out))
                )
            while (true) {
              Thread.sleep(400)
              dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) return null
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                when (status) {
                  DownloadManager.STATUS_SUCCESSFUL -> return out.takeIf { it.length() > 0 }
                  DownloadManager.STATUS_FAILED -> return null
                  else -> Unit
                }
              }
            }
            null as File?
          }
          .getOrNull()

  /** Fallback: let the browser download the APK (the user then taps the finished download). */
  fun openInBrowser(context: Context) {
    runCatching {
      context.startActivity(
          Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    }
  }

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
  var failed by remember { mutableStateOf(false) }

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
                val file =
                    withContext(Dispatchers.IO) {
                      Updater.download(context) { p -> scope.launch(Dispatchers.Main) { progress = p } }
                    }
                downloading = false
                if (file == null) {
                  failed = true
                  message = "Download non riuscito. Riprova, oppure scaricala dal browser."
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
        if (failed)
            TextButton(onClick = { Updater.openInBrowser(context) }) { Text("Scarica dal browser") }
        else TextButton(enabled = !downloading, onClick = { info = null }) { Text("Più tardi") }
      },
  )
}
