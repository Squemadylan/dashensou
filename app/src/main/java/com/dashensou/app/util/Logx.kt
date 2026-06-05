package com.dashensou.app.util

import android.util.Log
import com.dashensou.app.BuildConfig

/**
 * Thin wrapper around [android.util.Log] that drops debug-level lines
 * in release builds. Warnings / errors still ship in release because
 * the user-visible crash report path depends on them.
 *
 * Why: the search fan-out and the download poller each log a per-call
 * line, and on a chatty device that's tens of KB/s of logcat traffic
 * with no production value. Centralising the gating in one place means
 * we don't sprinkle `if (BuildConfig.DEBUG)` checks across the codebase.
 */
object Logx {
    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
    }

    fun d(tag: String, msg: String, tr: Throwable) {
        if (BuildConfig.DEBUG) Log.d(tag, msg, tr)
    }

    fun i(tag: String, msg: String) {
        // info-level stays on in release — useful for "user did X" tracing
        Log.i(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
    }
}
