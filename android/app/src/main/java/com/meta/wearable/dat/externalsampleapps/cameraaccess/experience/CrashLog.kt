/*
 * If the app ever crashes, the next launch shows what happened (so it can be reported and fixed)
 * instead of failing silently.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.experience

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import java.io.File

object CrashLog {
  private fun file(c: Context) = File(c.filesDir, "last_crash.txt")

  fun install(c: Context) {
    val app = c.applicationContext
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
      runCatching {
        file(app).writeText("VisionOttica Meta ${BuildConfig.VERSION_NAME}\n" + android.util.Log.getStackTraceString(e).take(6000))
      }
      previous?.uncaughtException(t, e)
    }
  }

  /** Shows the last crash once, if any. */
  fun showIfAny(a: Activity) {
    val f = file(a)
    if (!f.exists()) return
    val text = runCatching { f.readText() }.getOrDefault("")
    f.delete()
    AlertDialog.Builder(a)
        .setTitle("L'app si era chiusa per un errore")
        .setMessage("Fai uno screenshot e invialo:\n\n" + text.take(1500))
        .setPositiveButton("OK", null)
        .setNeutralButton("Copia") { _, _ ->
          (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
              .setPrimaryClip(ClipData.newPlainText("crash", text))
        }
        .show()
  }
}
