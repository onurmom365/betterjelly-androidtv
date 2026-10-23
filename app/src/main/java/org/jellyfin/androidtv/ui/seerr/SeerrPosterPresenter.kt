package org.jellyfin.androidtv.ui.seerr

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.AsyncImageView

/** Native Leanback cards keep the existing remote row/scroll behavior. */
class SeerrPosterPresenter : Presenter() {
	private class Holder(val root: LinearLayout, val image: AsyncImageView, val badge: TextView, val title: TextView, val meta: TextView) : ViewHolder(root)
	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val context = parent.context
		fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
		val root = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			layoutParams = ViewGroup.LayoutParams(dp(142), dp(264))
			setPadding(dp(5), dp(5), dp(5), dp(5))
			isFocusable = true; isFocusableInTouchMode = true
		}
		val poster = FrameLayout(context).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(194)) }
		val image = AsyncImageView(context).apply {
			scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
			background = GradientDrawable().apply { setColor(Color.rgb(24, 34, 55)); cornerRadius = dp(7).toFloat() }
			clipToOutline = true
		}
		poster.addView(image, FrameLayout.LayoutParams(-1, -1))
		val badge = TextView(context).apply {
			textSize = 10f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
			setPadding(dp(7), dp(4), dp(7), dp(4))
		}
		poster.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(10) })
		root.addView(poster)
		val title = TextView(context).apply { textSize = 14f; setTextColor(Color.WHITE); maxLines = 2; setPadding(0, dp(7), 0, 0) }
		val meta = TextView(context).apply { textSize = 11f; setTextColor(Color.rgb(173, 187, 212)); maxLines = 1 }
		root.addView(title); root.addView(meta)
		root.setOnFocusChangeListener { _, focused ->
			root.background = GradientDrawable().apply {
				cornerRadius = dp(10).toFloat(); setColor(if (focused) Color.rgb(31, 44, 70) else Color.TRANSPARENT)
				setStroke(dp(2), if (focused) Color.rgb(151, 133, 255) else Color.TRANSPARENT)
			}
			root.animate().scaleX(if (focused) 1.04f else 1f).scaleY(if (focused) 1.04f else 1f).setDuration(120).start()
		}
		return Holder(root, image, badge, title, meta)
	}
	override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
		val holder = viewHolder as Holder
		val title = item as SeerrTitle
		holder.image.load(tmdbImage(title.posterPath), placeholder = ContextCompat.getDrawable(holder.root.context, R.drawable.tile_port_video))
		holder.badge.text = if (title.mediaType == "tv") "SERIES" else "MOVIE"
		holder.badge.background = GradientDrawable().apply {
			cornerRadius = 24f; setColor(if (title.mediaType == "tv") Color.rgb(164, 68, 183) else Color.rgb(51, 107, 200))
		}
		holder.title.text = title.displayTitle
		holder.meta.text = listOf(title.year, title.displayStatus()).filter { it.isNotBlank() }.joinToString(" · ")
		holder.root.contentDescription = "${title.displayTitle}, ${title.year}, ${title.displayStatus()}"
	}
	override fun onUnbindViewHolder(viewHolder: ViewHolder) { (viewHolder as Holder).image.load() }
}
