/*
 * Translator station: free, on-device translation with Google ML Kit (no API key, no cost).
 * Each language model (~30 MB) is downloaded once on first use, then works offline.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.experience

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

object SofiaTranslator {
  private val cache = HashMap<String, Translator>()

  /** [src]/[tgt] are BCP-47 tags ("it-IT", "en-US", "zh-CN"). Calls back on the main thread. */
  fun translate(text: String, src: String, tgt: String, onStatus: (String) -> Unit, done: (String?, String?) -> Unit) {
    val s = TranslateLanguage.fromLanguageTag(src.substringBefore('-'))
    val t = TranslateLanguage.fromLanguageTag(tgt.substringBefore('-'))
    if (s == null || t == null) return done(null, "lingua non supportata")
    if (s == t) return done(text, null)
    val key = "$s>$t"
    val tr =
        cache.getOrPut(key) {
          Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(s).setTargetLanguage(t).build())
        }
    onStatus("download")
    tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
        .addOnSuccessListener {
          tr.translate(text)
              .addOnSuccessListener { done(it, null) }
              .addOnFailureListener { done(null, it.message ?: "errore") }
        }
        .addOnFailureListener { done(null, "download lingua non riuscito: serve internet la prima volta") }
  }
}
