/*
 * Takes one photo with the glasses camera through the Meta Wearables Device Access Toolkit
 * (session → stream → capturePhoto → stop), reusing the sample's CameraViewModel, and returns it as
 * a JPEG data: URL for the page's popup.  NON VERIFICATO SU HARDWARE.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.experience

import android.graphics.Bitmap
import android.util.Base64
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.camera.CameraViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.camera.CapturePreview
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object GlassesShot {
  suspend fun take(vm: CameraViewModel, requestPermission: suspend (Permission) -> PermissionStatus): String? {
    val st = vm.uiState
    val ownSession = !st.value.isSessionActive
    try {
      if (ownSession) {
        vm.startSession()
        withTimeoutOrNull(15_000) { st.first { it.isSessionActive } } ?: return null
      }
      if (!st.value.isStreaming) {
        vm.startStreaming()
        withTimeoutOrNull(20_000) {
          st.first { (it.isStreaming && it.hasReceivedFirstFrame) || it.showCameraPermissionRedirectConfirm }
        }
        if (st.value.showCameraPermissionRedirectConfirm) {
          vm.confirmCameraPermissionRedirect(requestPermission)
          withTimeoutOrNull(40_000) { st.first { it.isStreaming && it.hasReceivedFirstFrame } }
        }
        if (!st.value.isStreaming) return null
        delay(700) // let exposure settle
      }
      vm.capturePhoto()
      val shot = withTimeoutOrNull(12_000) { st.first { it.activePreview is CapturePreview.Photo } } ?: return null
      val bmp = (shot.activePreview as CapturePreview.Photo).bitmap
      val url =
          withContext(Dispatchers.Default) {
            val k = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
            val scaled =
                if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
                else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
          }
      vm.dismissCapturePreview()
      return url
    } finally {
      vm.stopStreaming()
      if (ownSession) vm.endSession()
    }
  }
}
