package com.dashensou.app.util

/** Helpers for non-HTTP share links (magnet / ed2k) returned by aggregators. */
object UrlKinds {
    fun isMagnet(url: String): Boolean = url.trim().lowercase().startsWith("magnet:")
    fun isEd2k(url: String): Boolean = url.trim().lowercase().startsWith("ed2k:")
    fun isTorrentLike(url: String): Boolean = isMagnet(url) || isEd2k(url)

    /**
     * Restore JSON-escaped URL slashes: `https:\/\/pan.quark.cn\/s\/xxx` → `https://pan.quark.cn/s/xxx`.
     *
     * Several aggregator APIs (yunso, quark4k's Flarum API, some pansou mirrors)
     * return URLs with backslash-escaped slashes in the JSON body. Without this
     * normalization the regex URL extractor won't match them and results are lost.
     *
     * Ported from PanHub server/core/plugins/panLink.ts extractLinksFromText().
     */
    fun unescapeJsonUrl(raw: String): String = raw.replace(Regex("\\\\/"), "/")
}
