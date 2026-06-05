package com.dashensou.app.util

import android.content.Context
import android.content.SharedPreferences
import com.dashensou.app.service.source.SearchSource

/**
 * Persistent per-source on/off switch.
 *
 * Storage: a single `SharedPreferences` file (`source_prefs`) with one
 * boolean entry per source id (`enabled_<id>`). The file is deliberately
 * separate from other app settings so a future "reset sources" action
 * can clear it without touching the user's other preferences.
 *
 * Default behaviour: if a source has no entry yet, the source's current
 * `enabled` value is used (so first-run honours the in-code defaults
 * declared in `SearchService.defaultSources`) and is then written back
 * so the next read is a hit.
 *
 * Note: source ids are stable strings declared by each `SearchSource`
 * implementation. If you rename a source id, treat the old id as a
 * different source — the pref entry for the old id will be ignored.
 */
object SourcePrefs {

    private const val FILE = "source_prefs"
    private const val KEY_PREFIX = "enabled_"
    private const val WRITE_ON_READ_DEFAULT = true

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context, source: SearchSource): Boolean {
        val sp = prefs(ctx)
        val key = KEY_PREFIX + source.id
        if (!sp.contains(key)) {
            // First read for this id: seed it with the source's own
            // default so the UI and the next read agree.
            sp.edit().putBoolean(key, source.enabled).apply()
        }
        return sp.getBoolean(key, source.enabled)
    }

    fun setEnabled(ctx: Context, source: SearchSource, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PREFIX + source.id, enabled).apply()
    }

    /**
     * Apply persisted flags to a live list of sources. Mutates each
     * source's `enabled` var so subsequent `SearchService.search()`
     * calls see the user's choice.
     *
     * Sources whose id has no pref entry are seeded on first call.
     */
    fun applyTo(ctx: Context, sources: List<SearchSource>) {
        for (s in sources) {
            val v = isEnabled(ctx, s)
            if (s.enabled != v) s.enabled = v
        }
    }

    /** Wipe all per-source flags and revert to in-code defaults. */
    fun reset(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
