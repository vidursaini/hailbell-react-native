package com.hailbell

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * A review card's button opens the app's Play Store listing, where the customer can rate it.
 *
 * Google's in-app review sheet is not used here. Play shows it at most a few times a year per person
 * and only to apps installed from Play, and when it decides not to show it, nothing happens and no
 * error comes back, so a tapped button would do nothing. Google's guidance is that a button the user
 * taps should open the Play Store listing instead
 * (https://developer.android.com/guide/playcore/in-app-review#when-to-request).
 */
internal object Review {
    fun open(activity: Activity, listingUrl: String?) {
        val url = listingUrl ?: return
        val uri = Uri.parse(url)
        // Prefer the Play Store app; the web listing is the fallback when it isn't installed.
        val play = Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending")
        runCatching { activity.startActivity(play) }
            .recoverCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
}

/** The activity behind a Compose context, which can be wrapped (for example by a theme). */
internal tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.activity()
    else -> null
}
