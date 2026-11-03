package com.hailbell

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * A review card's button. Google Play's in-app rating sheet opens over the chat, as the system
 * prompt does on iOS. When Play can't offer it, the card's Play listing opens instead.
 *
 * Play shows the sheet only to apps installed from Play (or internal app sharing), and at most a
 * few times a year per person. Otherwise `launchReviewFlow` finishes without showing anything and
 * without an error, so the listing link is kept only for when Play answers with an error.
 */
internal object Review {
    /** Swapped for Play's `FakeReviewManager` in tests. */
    internal var managerFor: (Context) -> ReviewManager = { ReviewManagerFactory.create(it) }

    fun open(activity: Activity, listingUrl: String?) {
        val manager = runCatching { managerFor(activity) }.getOrNull() ?: return openListing(activity, listingUrl)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (activity.isFinishing) return@addOnCompleteListener
            if (request.isSuccessful) manager.launchReviewFlow(activity, request.result)
            else openListing(activity, listingUrl)
        }
    }

    private fun openListing(activity: Activity, url: String?) {
        url ?: return
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}

/** The activity behind a Compose context, which can be wrapped (for example by a theme). */
internal tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.activity()
    else -> null
}
