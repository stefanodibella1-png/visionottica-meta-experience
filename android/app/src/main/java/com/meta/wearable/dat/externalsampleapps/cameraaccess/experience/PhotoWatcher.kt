/*
 * Shows in the app the photo just taken with the glasses ("Hey Meta, scatta una foto").
 * The glasses save to the Meta AI app, which imports new media into the phone gallery
 * (automatic import must be on). We watch the gallery for an image newer than the command
 * and hand a downscaled JPEG to the page.  NON VERIFICATO SU HARDWARE.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.experience

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.provider.MediaStore
import android.util.Base64
import java.io.ByteArrayOutputStream

object PhotoWatcher {
  @Volatile private var token = 0

  fun cancel() {
    token++
  }

  /** Blocking poll (run off the main thread). Returns a data: URL, or null on timeout/cancel. */
  fun waitForNewPhoto(context: Context, sinceMs: Long, timeoutMs: Long): String? {
    val my = ++token
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end && my == token) {
      latestSince(context, sinceMs)?.let { uri -> return runCatching { toDataUrl(context, uri) }.getOrNull() }
      Thread.sleep(2000)
    }
    return null
  }

  private fun latestSince(context: Context, sinceMs: Long): android.net.Uri? {
    val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
    val sel = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
    val args = arrayOf(((sinceMs / 1000) - 5).toString())
    return runCatching {
          context.contentResolver.query(col, proj, sel, args, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            if (c.moveToFirst()) ContentUris.withAppendedId(col, c.getLong(0)) else null
          }
        }
        .getOrNull()
  }

  private fun toDataUrl(context: Context, uri: android.net.Uri): String {
    val src = ImageDecoder.createSource(context.contentResolver, uri)
    val bmp =
        ImageDecoder.decodeBitmap(src) { dec, info, _ ->
          val w = info.size.width
          val h = info.size.height
          val k = minOf(1f, 1280f / maxOf(w, h))
          dec.setTargetSize((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
          dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
    return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
  }
}
