package com.hailbell

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.sqrt

/**
 * Bug reports: the screens the user passed through, the shake that opens the report screen, the
 * screenshot taken just before it opens, and the device details sent with the report. Nothing leaves
 * the phone until the user taps Send on the report screen.
 */
internal object BugReports {
    const val MAX_SCREENS = 10
    private const val MAX_SCREEN_LENGTH = 80
    private const val SCREENSHOT_FILE = "hailbell-report.jpg"

    private val screens = ArrayDeque<String>()

    /** Remembers [name] as the latest screen, keeping the last [MAX_SCREENS]. */
    fun track(name: String) {
        val clean = name.trim().take(MAX_SCREEN_LENGTH)
        if (clean.isEmpty()) return
        synchronized(screens) {
            if (screens.lastOrNull() == clean) return
            screens.addLast(clean)
            while (screens.size > MAX_SCREENS) screens.removeFirst()
        }
    }

    fun screens(): List<String> = synchronized(screens) { screens.toList() }

    /** For tests: forget the tracked screens. */
    fun resetScreens() = synchronized(screens) { screens.clear() }

    /** The `report` object the server expects next to the message text. */
    fun fields(appVersion: String?, build: Long?, device: String, os: String, locale: String, screens: List<String>): Map<String, Any> =
        buildMap {
            appVersion?.takeIf { it.isNotBlank() }?.let { put("appVersion", it) }
            build?.let { put("build", it.toString()) }
            put("device", device)
            put("os", os)
            put("locale", locale)
            put("screens", screens)
        }

    /** [fields] for this phone and app. */
    fun fields(context: Context): Map<String, Any> {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val build = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }
        return fields(
            appVersion = info?.versionName, build = build, device = Client.deviceModel(),
            os = "Android ${Build.VERSION.RELEASE}", locale = Locale.getDefault().toString(), screens = screens(),
        )
    }

    // opening the report screen

    private var opening = false

    /** Takes a screenshot of [activity]'s window, then opens the report screen over it. */
    fun open(activity: Activity) {
        if (opening || activity is BugReportActivity) return
        opening = true
        capture(activity) { bitmap ->
            Hailbell.scope.launch {
                val file = bitmap?.let { save(activity.applicationContext, it) }
                opening = false
                if (activity.isFinishing || activity.isDestroyed) { file?.delete(); return@launch }
                val intent = Intent(activity, BugReportActivity::class.java)
                file?.let { intent.putExtra(BugReportActivity.EXTRA_SCREENSHOT, it.absolutePath) }
                activity.startActivity(intent)
            }
        }
    }

    fun screenshotFile(context: Context) = File(context.cacheDir, SCREENSHOT_FILE)

    private suspend fun save(context: Context, bitmap: Bitmap): File? = withContext(Dispatchers.IO) {
        runCatching {
            val file = screenshotFile(context)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            file
        }.getOrNull().also { bitmap.recycle() }
    }

    /** The window as it is on screen. PixelCopy sees what the GPU drew; drawing the views is the fallback. */
    private fun capture(activity: Activity, done: (Bitmap?) -> Unit) {
        val window = activity.window
        val view = window?.decorView
        if (view == null || view.width <= 0 || view.height <= 0) return done(null)
        val bitmap = try {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return done(null)
        }
        fun drawn(): Bitmap? = runCatching { bitmap.eraseColor(0); view.draw(Canvas(bitmap)); bitmap }.getOrNull()
        try {
            PixelCopy.request(window, bitmap, { result ->
                done(if (result == PixelCopy.SUCCESS) bitmap else drawn())
            }, Handler(Looper.getMainLooper()))
        } catch (e: IllegalArgumentException) {
            // The window has no surface yet.
            done(drawn())
        }
    }

    // shake to report

    private var registered = false
    private var resumed: WeakReference<Activity>? = null
    private var listening = false
    private val detector = ShakeDetector()

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            if (detector.onReading(v[0], v[1], v[2], event.timestamp / 1_000_000)) {
                resumed?.get()?.let(::open)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumed = WeakReference(activity)
            updateShake()
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumed?.get() === activity) resumed = null
            updateShake()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    /** Called from configure: follows which of the app's activities is on screen. */
    fun watch(context: Context) {
        if (registered) return
        (context.applicationContext as? Application ?: return).registerActivityLifecycleCallbacks(lifecycle)
        registered = true
    }

    /**
     * Listens to the accelerometer only while [Hailbell.shakeToReport] is on and one of the app's
     * activities, other than the report screen, is resumed. Otherwise the sensor is off.
     */
    fun updateShake() {
        val activity = resumed?.get()
        val want = Hailbell.shakeToReport && Hailbell.isConfigured && activity != null && activity !is BugReportActivity
        if (want == listening) return
        val sensors = Hailbell.context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        if (want) {
            val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            detector.reset()
            sensors.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        } else {
            sensors.unregisterListener(sensorListener)
        }
        listening = want
    }
}

/**
 * Tells a shake from a bump. A reading counts as a peak when the total acceleration is over
 * [threshold] times gravity. Readings closer together than [peakGap] ms belong to the same peak. A
 * shake is [peaks] peaks within [window] ms, and after one, nothing fires for [cooldown] ms.
 */
internal class ShakeDetector(
    private val threshold: Float = 2.7f,
    private val peaks: Int = 2,
    private val window: Long = 500,
    private val peakGap: Long = 100,
    private val cooldown: Long = 1500,
) {
    private val times = ArrayDeque<Long>()
    private var lastPeak = Long.MIN_VALUE / 2
    private var quietUntil = Long.MIN_VALUE

    /** One accelerometer reading in m/s², at [now] ms. True when it completes a shake. */
    fun onReading(x: Float, y: Float, z: Float, now: Long): Boolean {
        val g = sqrt(x * x + y * y + z * z) / GRAVITY
        if (g < threshold || now < quietUntil) return false
        if (now - lastPeak < peakGap) return false
        lastPeak = now
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > window) times.removeFirst()
        if (times.size < peaks) return false
        times.clear()
        quietUntil = now + cooldown
        return true
    }

    fun reset() {
        times.clear()
        lastPeak = Long.MIN_VALUE / 2
        quietUntil = Long.MIN_VALUE
    }
}

/** Standard gravity in m/s², the same as SensorManager.GRAVITY_EARTH, kept here so tests run on the JVM. */
private const val GRAVITY = 9.80665f
