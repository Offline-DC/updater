package com.offlineinc.dumbdownlauncher.updater

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PatternMatcher
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The screen shown while the launcher replaces its own package (Figma "dumb
 * product design", "dp2 loading screen", node 3609:7836): the white line-art
 * duck with "we're installing the update" under it, centred on
 * primary/background, and "don't turn off ur phone" near the bottom.
 *
 * It lives in its own package so it stays in front for the whole swap; the
 * launcher's own processes are all killed while its package is replaced. On
 * the Gflip6 (2026-10-28), an app in front stayed there for the whole
 * `pm install -r` of the launcher and Android never tried to bring HOME up.
 *
 * ## Contract with the launcher's install shell
 *
 * The launcher's root shell drives this screen; this app knows nothing about
 * the install:
 *
 * - **Open:** `am start -W -n com.offlineinc.dumbdownlauncher.updater/.UpdatingActivity`
 *   (root only: the activity isn't exported). Optional
 *   `--el timeout_ms <ms>` overrides [DEFAULT_TIMEOUT_MS].
 * - **Close:** `am start -n .../.UpdatingActivity --ez done true` once the
 *   launcher has drawn behind this screen (Android logs "Displayed
 *   com.offlineinc.dumbdownlauncher/..."). This screen is the one in front
 *   then, so the intent changes nothing visible; it finishes and removes its
 *   own task, so it doesn't linger in recents. The shell force-stops the
 *   package a second later as a backstop.
 * - **Hold the front:** `am start -n .../.UpdatingActivity --ez hold_front true`
 *   right before the shell starts the launcher (delivered to this instance;
 *   nothing visible changes). For [HOLD_FRONT_MS] after that, the first time
 *   this screen loses the front it puts itself straight back on top, so the
 *   launcher cold-starts *behind* it instead of showing its own starting
 *   window (black + old duck) for the whole cold start. Doing it here rather
 *   than with a second `am start` from the shell shortens the gap from ~1 s
 *   (a flash of the old duck, Gflip6 2026-10-28) to however long Android
 *   takes to pause this activity. It moves its own task to the front
 *   (ActivityManager.moveTaskToFront); a startActivity on itself made Android
 *   create a second instance instead. Only once, and only in that window, so an
 *   incoming call at any other time is never covered.
 *
 *   Re-fronting also turns this window translucent (it looks the same:
 *   it still paints a solid background). Android only starts and draws the
 *   activity under a translucent one; under an opaque updater the
 *   launcher's MainActivity wasn't created until the updater closed, then
 *   took ~2 s to draw, which showed as a flash (Gflip6 2026-10-28). Not
 *   translucent from the start: during the install the old launcher sits
 *   under us, and Android would keep trying to restart it while its package
 *   is locked ("start failure", force-stops). So: opaque until the launcher
 *   has been started under us, translucent from then on, and the shell closes us once
 *   Android logs "Displayed com.offlineinc.dumbdownlauncher/...".
 * - **Safety timeout:** if nobody closes it, after the timeout it starts the
 *   launcher's home screen and finishes, so the user is never stuck here.
 *   The full timeout (10 min) only applies while the install may still be
 *   running (dexopt can take minutes on the HIT).
 * - **Backstop once the launcher has been replaced** (v2, 2026-10-29): this
 *   screen watches for it itself, via PACKAGE_REPLACED (registered at runtime;
 *   a manifest receiver wouldn't get it) and by polling the launcher's
 *   versionCode every [POLL_MS]. From then on, if nobody hands back within
 *   [AFTER_REPLACE_MS] (or [AFTER_HOLD_MS] after hold_front), it starts the
 *   launcher's home screen and closes. It can't open updates v2 itself (not
 *   exported), so the install script and the new launcher's backstop
 *   (SelfUpdateInstaller.startHandoffBackstop) hand back first; this is the
 *   last resort. On 2026-10-29 the install script died with the old launcher
 *   and this screen sat there until the 10-minute timeout.
 *
 * Keys: everything is swallowed except volume, call/end and power. HOME can't
 * be intercepted by an ordinary activity; if it's pressed mid-swap Android
 * asks for the frozen launcher, fails, and falls back to the next task, which
 * is this one.
 *
 * ## Units: Figma pixels, not dp or sp
 *
 * Everything is sized in the Figma frame's own units: one unit is the screen
 * width / 240, i.e. one physical pixel on a 240 × 320 flip. Not dp or sp,
 * because the phones don't run at their physical density: the Gflip6 reports
 * physical 160 but an override of 120 (1dp = 0.75px) and font_scale 1.4, so
 * dp-sized things came out 25% smaller than the Figma and sp-sized text came
 * out at whatever the text-size setting said (and wrapped). The phone's
 * text-size setting is ignored here on purpose: this screen is up for a few
 * seconds and has to look like the design.
 *
 * Sizes are above the Figma's by request (2026-10-28): duck 120 × 125 (Figma
 * 96 × 100), label 18 (Figma 16), supportive line 16 (Figma 14).
 */
class UpdatingActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val onTimeout = Runnable { giveUp("safety timeout reached") }
    private val onNoHandback = Runnable { giveUp("nobody handed back after the launcher was replaced") }

    /** The launcher's versionCode when this screen opened; -1 if unknown. */
    private var launcherVersionAtStart = -1L
    private var launcherReplaced = false

    private val replacedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onLauncherReplaced("PACKAGE_REPLACED")
        }
    }

    private val pollLauncherVersion = object : Runnable {
        override fun run() {
            if (launcherReplaced) return
            val now = launcherVersion()
            if (launcherVersionAtStart >= 0 && now >= 0 && now != launcherVersionAtStart) {
                onLauncherReplaced("versionCode $launcherVersionAtStart -> $now")
                return
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    /** One Figma unit in physical pixels (screen width / 240). */
    private var unitPx = 1f

    /** Uptime until which losing the front means "put me back" (once). */
    private var holdFrontUntil = 0L

    /** Whether the window is currently translucent; kept across a recreate. */
    private var translucent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A long install on a phone left alone must not let the screen sleep
        // into the lock screen mid-update.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        unitPx = resources.displayMetrics.widthPixels / FIGMA_WIDTH
        // Theme is translucent only so this can be switched later; opaque
        // while the install runs (see the class KDoc). If Android recreated
        // us after we had gone translucent, stay translucent: an opaque new
        // instance hides the launcher again (2026-10-28 14:24).
        val wasTranslucent = savedInstanceState?.getBoolean(STATE_TRANSLUCENT, false) == true
        setWindowTranslucent(wasTranslucent)
        setContentView(buildContent())
        Log.i(
            TAG,
            "onCreate ${System.identityHashCode(this)}: " +
                (if (savedInstanceState == null) "new instance" else "recreated, translucent=$wasTranslucent"),
        )

        val timeoutMs = intent.getLongExtra(EXTRA_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)
            .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        // configChanges in the manifest keeps the flip from recreating this
        // activity, so onCreate (and this timer) runs once per update.
        handler.postAtTime(onTimeout, SystemClock.uptimeMillis() + timeoutMs)
        Log.i(TAG, "shown; unit=${unitPx}px; safety timeout ${timeoutMs}ms")

        launcherVersionAtStart = launcherVersion()
        val filter = IntentFilter(Intent.ACTION_PACKAGE_REPLACED).apply {
            addDataScheme("package")
            addDataSchemeSpecificPart(LAUNCHER_PACKAGE, PatternMatcher.PATTERN_LITERAL)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(replacedReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(replacedReceiver, filter)
        }
        handler.postDelayed(pollLauncherVersion, POLL_MS)
        Log.i(TAG, "watching for the launcher to be replaced (versionCode now $launcherVersionAtStart)")

        readHoldFront(intent)
    }

    /** The launcher's installed versionCode, or -1 while it can't be read (mid-install). */
    private fun launcherVersion(): Long = try {
        val info = packageManager.getPackageInfo(LAUNCHER_PACKAGE, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (t: Throwable) {
        -1L
    }

    private fun onLauncherReplaced(how: String) {
        if (launcherReplaced) return
        launcherReplaced = true
        handler.removeCallbacks(pollLauncherVersion)
        handler.removeCallbacks(onNoHandback)
        handler.postDelayed(onNoHandback, AFTER_REPLACE_MS)
        Log.i(TAG, "launcher replaced ($how); waiting up to ${AFTER_REPLACE_MS}ms for the hand-back")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readHoldFront(intent)
    }

    private fun readHoldFront(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_DONE, false) == true) {
            Log.i(TAG, "done; closing and removing my task")
            holdFrontUntil = 0L
            finishAndRemoveTask()
            return
        }
        if (intent?.getBooleanExtra(EXTRA_HOLD_FRONT, false) == true) {
            holdFrontUntil = SystemClock.uptimeMillis() + HOLD_FRONT_MS
            // The hand-back has started; give the launcher time to draw
            // behind us (a debug build took ~20 s), then give up.
            launcherReplaced = true
            handler.removeCallbacks(pollLauncherVersion)
            handler.removeCallbacks(onNoHandback)
            handler.postDelayed(onNoHandback, AFTER_HOLD_MS)
            Log.i(TAG, "hold_front: holding the front for ${HOLD_FRONT_MS}ms; hand-back expected within ${AFTER_HOLD_MS}ms")
        }
    }

    /**
     * [Activity.setTranslucent] is public from API 30; below that (the HIT,
     * API 27) the same calls are the hidden convertToTranslucent /
     * convertFromTranslucent, which API 27 doesn't restrict. Failure only
     * means a flash comes back, so it's logged and ignored.
     */
    private fun setWindowTranslucent(translucent: Boolean) {
        this.translucent = translucent
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                setTranslucent(translucent)
            } else if (translucent) {
                val listener = Class.forName("android.app.Activity\$TranslucentConversionListener")
                Activity::class.java
                    .getDeclaredMethod("convertToTranslucent", listener, android.app.ActivityOptions::class.java)
                    .apply { isAccessible = true }
                    .invoke(this, null, null)
            } else {
                Activity::class.java
                    .getDeclaredMethod("convertFromTranslucent")
                    .apply { isAccessible = true }
                    .invoke(this)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "couldn't make the window ${if (translucent) "translucent" else "opaque"}", t)
        }
    }

    override fun onPause() {
        super.onPause()
        if (SystemClock.uptimeMillis() < holdFrontUntil) {
            holdFrontUntil = 0L
            // Now, not on hold_front itself: by now the launcher has just
            // been started and is what sits under us. Earlier, the task under
            // us would be whatever app was open before the update.
            setWindowTranslucent(true)
            // moveTaskToFront, not startActivity: on the Gflip6
            // (2026-10-28 16:14) startActivity on this singleTask activity
            // made Android create a NEW instance and finish this one, and the
            // new, opaque instance's start-up was the flash. Moving the task
            // keeps this same instance (and its translucent window). Allowed
            // because this activity still has a visible window while it
            // pauses (Android 10+ background-start rules).
            try {
                (getSystemService(ACTIVITY_SERVICE) as ActivityManager).moveTaskToFront(taskId, 0)
                Log.i(TAG, "lost the front while holding it; moved task $taskId back on top (${System.identityHashCode(this)})")
            } catch (t: Throwable) {
                Log.w(TAG, "couldn't move my task back on top", t)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_TRANSLUCENT, translucent)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "config changed in place: $newConfig")
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy ${System.identityHashCode(this)} finishing=$isFinishing")
        handler.removeCallbacks(onTimeout)
        handler.removeCallbacks(onNoHandback)
        handler.removeCallbacks(pollLauncherVersion)
        try { unregisterReceiver(replacedReceiver) } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (event.keyCode in PASS_THROUGH_KEYS) super.dispatchKeyEvent(event) else true

    /** Nobody closed us in time: hand the user back to the launcher's home screen. */
    private fun giveUp(why: String) {
        Log.w(TAG, "$why; starting the launcher's home screen and closing")
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setComponent(ComponentName(LAUNCHER_PACKAGE, LAUNCHER_HOME))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
            // Still mid-install or not installed: closing still beats staying.
            Log.w(TAG, "couldn't start the launcher", t)
        }
        finishAndRemoveTask()
    }

    // ── layout (Figma 240 × 320 frame) ─────────────────────────────────────

    private fun buildContent(): FrameLayout {
        val medium = Typeface.createFromAsset(assets, FONT_MEDIUM)
        val duckW = u(DUCK_WIDTH)
        val duckH = u(DUCK_HEIGHT)

        // The duck and the label, 8 units apart, centred. Full width, so the
        // label is centred on the screen itself (see [text]).
        val centre = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                ImageView(this@UpdatingActivity).apply {
                    setImageBitmap(duckBitmap(duckW, duckH))
                    // The bitmap is already exactly the view's size, so this
                    // is 1:1 whatever density the bitmap reports.
                    scaleType = ImageView.ScaleType.FIT_XY
                    importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(duckW, duckH),
            )
            addView(
                text("we're installing the update", medium, LABEL_SIZE, R.color.updating_text_strong),
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = u(8f) },
            )
        }

        // Supportive line: Figma puts its bottom ~20 units above the bottom edge.
        val bottom = text("don't turn off ur phone", medium, SUPPORTIVE_SIZE, R.color.updating_text_weak)

        return FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.updating_background))
            addView(centre, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
            addView(
                bottom,
                FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM)
                    .apply { bottomMargin = u(20f) },
            )
        }
    }

    /**
     * The duck at exactly [w] × [h] pixels, so the ImageView draws it 1:1.
     *
     * The source (drawable-nodpi, 480 × 500, Lanczos-scaled from Figma's
     * 2025 × 2100 "white duck 1") is shrunk here in steps of at most 2×. A
     * single big bilinear shrink, which is what Android does when it scales
     * a bitmap at draw time, skips pixels and breaks the thin strokes up;
     * halving step by step averages them instead.
     */
    private fun duckBitmap(w: Int, h: Int): Bitmap {
        var bmp = BitmapFactory.decodeResource(
            resources, R.drawable.updating_duck, BitmapFactory.Options().apply { inScaled = false },
        )
        while (bmp.width / 2 >= w && bmp.height / 2 >= h) {
            bmp = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
        }
        if (bmp.width != w || bmp.height != h) {
            bmp = Bitmap.createScaledBitmap(bmp, w, h, true)
        }
        return bmp
    }

    /**
     * One line of Display Medium, [size] in Figma units, centred on the
     * screen's full width.
     *
     * - No forced line height, and padding from the text's real ink bounds:
     *   Helvetica Now Display's "g" and "p" reach further below the baseline
     *   than the font's reported descent, so a box sized from the font
     *   metrics (or from Figma's 110% leading) clipped them (4058W,
     *   2026-10-28). Any ink outside ascent/descent becomes padding.
     * - `maxLines = 1` rather than `isSingleLine`: single-line mode turns on
     *   horizontal scrolling, which lays the text out in an unbounded width
     *   and is the likely reason it sat a few pixels off centre (4058W,
     *   2026-10-28).
     * - If the text would ever be wider than the screen minus [SIDE_MARGIN] on
     *   each side, it shrinks to fit instead of wrapping or being cut.
     */
    private fun text(value: String, face: Typeface, size: Float, colorRes: Int) =
        TextView(this).apply {
            text = value
            typeface = face
            maxLines = 1
            setHorizontallyScrolling(false)
            gravity = Gravity.CENTER_HORIZONTAL
            includeFontPadding = false
            setTextColor(getColor(colorRes))
            var px = size * unitPx
            paint.typeface = face
            paint.textSize = px
            val maxWidth = resources.displayMetrics.widthPixels - 2 * u(SIDE_MARGIN)
            while (paint.measureText(value) > maxWidth && px > unitPx) {
                px -= unitPx / 2
                paint.textSize = px
            }
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px)

            val fm = paint.fontMetricsInt
            val ink = Rect().also { paint.getTextBounds(value, 0, value.length, it) }
            // +1px each side for anti-aliasing at the ink's edge.
            val padTop = maxOf(0, fm.ascent - ink.top + 1)
            val padBottom = maxOf(0, ink.bottom - fm.descent + 1)
            setPadding(0, padTop, 0, padBottom)
        }

    /** Figma units → physical pixels. */
    private fun u(value: Float): Int = (value * unitPx).roundToInt()

    companion object {
        private const val TAG = "DumbUpdater"

        const val EXTRA_TIMEOUT_MS = "timeout_ms"
        const val EXTRA_HOLD_FRONT = "hold_front"
        const val EXTRA_DONE = "done"
        private const val STATE_TRANSLUCENT = "translucent"

        /** Long enough to cover the shell's `am start` of the launcher. */
        private const val HOLD_FRONT_MS = 5_000L

        /** Long enough for a slow HIT install (dexopt can take minutes there). */
        const val DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L

        /** How often to check whether the launcher's package has been replaced. */
        private const val POLL_MS = 2_000L

        /** After the launcher was replaced, how long to wait for anyone to start the hand-back. */
        private const val AFTER_REPLACE_MS = 60_000L

        /** After hold_front, how long to wait for the launcher to draw and the "done". */
        private const val AFTER_HOLD_MS = 90_000L
        private const val MIN_TIMEOUT_MS = 10 * 1000L
        private const val MAX_TIMEOUT_MS = 30 * 60 * 1000L

        private const val FIGMA_WIDTH = 240f

        /** Text never gets closer than this to the screen edges (Figma's (240 - 216) / 2). */
        private const val SIDE_MARGIN = 12f

        // The launcher's SelfUpdateInstallingScreen draws the same duck on the
        // same pixels before this screen comes up: keep its sizes in step.

        /** Figma's 96 × 100 slot × 1.25. */
        private const val DUCK_WIDTH = 120f
        private const val DUCK_HEIGHT = 125f

        /**
         * Figma's 16 and 14, made larger. "we're installing the update" is
         * ~205 units wide at 18 on the 240-unit screen; ~19 is the most that
         * fits on one line with a margin.
         */
        private const val LABEL_SIZE = 18f
        private const val SUPPORTIVE_SIZE = 16f

        private const val LAUNCHER_PACKAGE = "com.offlineinc.dumbdownlauncher"
        private const val LAUNCHER_HOME = "com.offlineinc.dumbdownlauncher.MainActivity"

        /** Helvetica Now Display Medium, subset to Basic Latin + curly quotes + ellipsis (~16 KB). */
        private const val FONT_MEDIUM = "fonts/helvetica_now_display_medium_subset.ttf"

        private val PASS_THROUGH_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_CALL,
            KeyEvent.KEYCODE_ENDCALL,
            KeyEvent.KEYCODE_POWER,
        )
    }
}
