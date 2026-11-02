package org.jellyfin.androidtv.ui.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.util.applyTheme

class UpdateActivity : FragmentActivity() {
	private val model by lazy { ViewModelProvider(this)[UpdateViewModel::class.java] }
	private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		model.message(if (installPermissionGranted(this)) "Permission enabled. Choose Install update to continue."
			else "Installation permission is still disabled. Allow Jellyfin in Install unknown apps, then return here.")
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		applyTheme()
		super.onCreate(savedInstanceState)
		setContent {
			JellyfinTheme {
				val state by model.state.collectAsState()
				BackHandler { model.cancel(); finish() }
				UpdateScreen(state, model, onBack = { model.cancel(); finish() }, onInstall = {
					if (!installPermissionGranted(this)) {
						try { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
						catch (_: ActivityNotFoundException) { model.message("Open device Settings → Install unknown apps and allow Jellyfin. Return here and choose Install update.") }
						catch (_: SecurityException) { model.message("This device blocked install settings. Allow Jellyfin in the device's Install unknown apps menu, then return here.") }
					} else model.install { file ->
						if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
							try { startActivity(updateInstallIntent(this, file)) }
							catch (_: ActivityNotFoundException) { model.message("No APK installer is available. Use Downloader code 8769319 on a supported Android/Fire OS device.") }
							catch (_: SecurityException) { model.message("Installation was blocked. Allow Jellyfin in Install unknown apps, then try Install update again.") }
						} else model.message("Update is verified. Return to this screen and choose Install update.")
					}
				})
			}
		}
	}
}

@Composable
private fun UpdateScreen(state: UpdateUiState, model: UpdateViewModel, onBack: () -> Unit, onInstall: () -> Unit) {
	val focus = remember { FocusRequester() }
	// A state change can remove the previously focused button (Download -> Install/Cancel).
	LaunchedEffect(state.busy, state.ready, state.release?.code) { focus.requestFocus() }
	val canDownload = state.release != null && state.release.minSdk <= android.os.Build.VERSION.SDK_INT
	val primary = when { state.busy -> "Cancel"; state.ready -> "Install update"; canDownload -> "Download update"; else -> "Check again" }
	val background = Color(0xFF090F1C)
	Column(Modifier.fillMaxSize().background(background).padding(horizontal = 48.dp, vertical = 28.dp)) {
		Text("Jellyfin updates", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
		Text("betterjelly-androidtv · Installed ${BuildConfig.VERSION_NAME}", color = Color(0xFFA9B7CF), fontSize = 15.sp)
		Spacer(Modifier.height(18.dp))
		Text(state.message, color = Color.White, fontSize = 19.sp)
		state.progress?.let { percent ->
			Spacer(Modifier.height(12.dp))
			Box(Modifier.fillMaxWidth().height(7.dp).background(Color(0xFF2D3B55))) {
				Box(Modifier.fillMaxWidth(percent / 100f).height(7.dp).background(Color(0xFFA697FF)))
			}
			Text("$percent%", color = Color(0xFFA9B7CF), fontSize = 15.sp)
		}
		Spacer(Modifier.height(18.dp))
		Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
			Button(onClick = { when { state.busy -> model.cancel(); state.ready -> onInstall(); canDownload -> model.download(); else -> model.check() } },
				modifier = Modifier.focusRequester(focus), shape = RoundedCornerShape(9.dp),
				colors = ButtonDefaults.colors(containerColor = Color(0xFF6C4BEF), contentColor = Color.White,
					focusedContainerColor = Color(0xFFA697FF), focusedContentColor = background)) { Text(primary, fontSize = 17.sp) }
			if (!state.busy && state.release != null) Button(onClick = model::check) { Text("Check again", fontSize = 17.sp) }
			Button(onClick = onBack) { Text("Back", fontSize = 17.sp) }
		}
		Spacer(Modifier.height(14.dp))
		Button(onClick = model::toggleAutomatic) { Text("Automatic checks: ${if (state.automatic) "On" else "Off"}", fontSize = 15.sp) }
		Text("Automatic checks run once a day, even while the app stays open. Prompts appear only on Home. Downloads and installation begin when you choose them. Android may ask you to allow Jellyfin to install updates and confirm Install.",
			color = Color(0xFFA9B7CF), fontSize = 14.sp, modifier = Modifier.padding(vertical = 14.dp))
		state.release?.notes?.takeIf(String::isNotBlank)?.let { notes ->
			// Focusable scrolling works with a remote even for release notes longer than the screen.
			Text(notes, color = Color(0xFFCED6E5), fontSize = 14.sp,
				modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).focusable().padding(top = 4.dp))
		}
	}
}
