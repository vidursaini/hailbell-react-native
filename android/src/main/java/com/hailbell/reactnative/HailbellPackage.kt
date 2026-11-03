package com.hailbell.reactnative

import android.app.Application
import android.content.Context
import expo.modules.core.interfaces.ApplicationLifecycleListener
import expo.modules.core.interfaces.Package

/**
 * Runs in `Application.onCreate`, before any JS: configures Hailbell from the App ID the config plugin
 * wrote to the manifest, as the Android SDK asks. A push that starts the app in the background, or a
 * tapped reply, then finds the SDK ready.
 */
class HailbellPackage : Package {
  override fun createApplicationLifecycleListeners(context: Context): List<ApplicationLifecycleListener> =
    listOf(object : ApplicationLifecycleListener {
      override fun onCreate(application: Application) {
        HailbellRN.onApplicationCreate(application)
      }
    })
}
