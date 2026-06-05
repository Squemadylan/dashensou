package com.dashensou.app.util

/** Helpers for non-HTTP share links (magnet / ed2k) returned by aggregators. */
object UrlKinds {
    fun isMagnet(url: String): Boolean = url.trim().lowercase().startsWith("magnet:")
    fun isEd2k(url: String): Boolean = url.trim().lowercase().startsWith("ed2k:")
    fun isTorrentLike(url: String): Boolean = isMagnet(url) || isEd2k(url)
}
