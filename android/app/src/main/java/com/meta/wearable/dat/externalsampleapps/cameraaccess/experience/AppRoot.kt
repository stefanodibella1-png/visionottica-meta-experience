/*
 * VisionOttica Meta Experience — top-level navigation.
 *
 * - "experience": the 3D showroom with Sofia (WebView), shown at launch.
 * - "glasses":    Meta's official DAT flow (registration → session → live camera from the
 *                 glasses → photo/video), reused unchanged from the CameraAccess sample.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.experience

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.CameraAccessScaffold
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

@Composable
fun AppRoot(
    viewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    onRequestRecordAudioPermission: suspend () -> Boolean,
) {
  val activity = LocalContext.current as Activity
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  var screen by rememberSaveable { mutableStateOf("experience") }

  BackHandler(enabled = screen != "experience") { screen = "experience" }

  if (screen == "experience") {
    ExperienceScreen(
        uiState = uiState,
        onConnect = {
          // Not registered yet → Meta AI app registration flow (DAT). Registered → glasses screen.
          if (uiState.isRegistered) screen = "glasses"
          else if (uiState.canStartRegistration) viewModel.startRegistration(activity)
          else screen = "glasses"
        },
        onOpenGlassesCamera = { screen = "glasses" },
        onRequestRecordAudioPermission = onRequestRecordAudioPermission,
    )
  } else {
    Box(modifier = Modifier.fillMaxSize()) {
      CameraAccessScaffold(
          viewModel = viewModel,
          onRequestWearablesPermission = onRequestWearablesPermission,
          onRequestRecordAudioPermission = onRequestRecordAudioPermission,
      )
      Button(
          onClick = { screen = "experience" },
          modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC0B1424)),
      ) {
        Text("← Showroom", fontSize = 16.sp, color = Color.White)
      }
    }
  }
}
