/*
 * Sofia's answers inside the app come from Claude through the VisionOttica server (server/ folder),
 * which keeps the Anthropic API key. The app never contains the key.
 *
 * Configure at build time (local.properties or CI secrets):
 *   sofia_endpoint  = https://<your-project>.vercel.app/api/sofia
 *   sofia_app_token = shared token also set on the server (blocks casual abuse)
 * If no endpoint is configured, the page falls back to VisionOttica's recorded answers (offline).
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.experience

import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object SofiaBrain {
  val isConfigured: Boolean
    get() = BuildConfig.SOFIA_ENDPOINT.startsWith("https://")

  /** Blocking call — run off the main thread. Returns Sofia's answer text, or null on failure. */
  fun ask(payloadJson: String): String? {
    if (!isConfigured) return null
    return runCatching {
          val conn = URL(BuildConfig.SOFIA_ENDPOINT).openConnection() as HttpURLConnection
          conn.requestMethod = "POST"
          conn.connectTimeout = 10_000
          conn.readTimeout = 30_000
          conn.doOutput = true
          conn.setRequestProperty("content-type", "application/json")
          conn.setRequestProperty("x-app-token", BuildConfig.SOFIA_APP_TOKEN)
          conn.outputStream.use { it.write(payloadJson.toByteArray()) }
          val ok = conn.responseCode in 200..299
          val body = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
          conn.disconnect()
          if (!ok || body == null) null else JSONObject(body).optString("text").ifBlank { null }
        }
        .getOrNull()
  }
}
