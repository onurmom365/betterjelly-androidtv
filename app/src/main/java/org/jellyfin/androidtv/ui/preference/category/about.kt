package org.jellyfin.androidtv.ui.preference.category

import android.os.Build
import android.content.Intent
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.preference.dsl.OptionsScreen
import org.jellyfin.androidtv.ui.preference.dsl.link
import org.jellyfin.androidtv.ui.preference.dsl.action
import org.jellyfin.androidtv.ui.update.UpdateActivity
import org.jellyfin.androidtv.ui.preference.screen.LicensesScreen

fun OptionsScreen.aboutCategory() = category {
	setTitle(R.string.pref_about_title)

	link {
		// Hardcoded strings for troubleshooting purposes
		title = "Jellyfin app version"
		content = "betterjelly-androidtv ${BuildConfig.VERSION_NAME} ${BuildConfig.BUILD_TYPE}"
		icon = R.drawable.ic_jellyfin
	}

	action {
		title = "Check for updates"
		content = "Review, download and install updates for this fork. Settings are kept."
		icon = R.drawable.ic_jellyfin
		onActivate = { context.startActivity(Intent(context, UpdateActivity::class.java)) }
	}

	link {
		setTitle(R.string.pref_device_model)
		content = "${Build.MANUFACTURER} ${Build.MODEL}"
		icon = R.drawable.ic_tv
	}

	link {
		setTitle(R.string.licenses_link)
		setContent(R.string.licenses_link_description)
		icon = R.drawable.ic_guide
		withFragment<LicensesScreen>()
	}
}
