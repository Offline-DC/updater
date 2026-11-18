package com.offlineinc.dumbdownlauncher.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Does nothing, on purpose: receiving this broadcast is the whole point.
 *
 * The launcher's install script sends it (as root, `am broadcast -n`) while a
 * launcher update is still downloading. Delivering a broadcast makes Android
 * start this app's process, so that a few seconds later, when the download is
 * done and the script runs `am start` on [UpdatingActivity], the process
 * already exists and the screen comes up as a warm start instead of a cold
 * one. On the HIT slider (Android 8.1, 2026-11-09) the cold start was about a
 * second of the five between "download complete" and "installing", all of it
 * spent with the user looking at a full progress bar.
 *
 * A receiver rather than a service or a second activity because it is the one
 * component that starts a process and shows nothing: no window, no
 * notification, no task in recents. Android may kill the idle process again
 * before the install starts (it holds nothing to keep it alive); then the
 * updater cold-starts exactly as it did before this existed. Nothing depends
 * on the warm-up having worked.
 *
 * Not exported, like the activity: only root and the system can send to it.
 * The script passes --include-stopped-packages, because it force-stops this
 * app at the end of every update and Android withholds broadcasts from a
 * stopped app otherwise.
 */
class WarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("UpdatingActivity", "warmed: process up ahead of the install")
    }
}
