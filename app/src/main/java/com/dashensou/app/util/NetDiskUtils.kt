package com.dashensou.app.util

import com.dashensou.app.data.model.NetDiskType

/**
 * Net-disk app package + intent scheme table. The other
 * name-related helpers live in [DiskLabels].
 */
object NetDiskUtils {

    /** Quark browser / Quark pan — either may handle magnet & share links. */
    val QUARK_PACKAGE_CANDIDATES: List<String> = listOf(
        "com.quark.browser",
        "com.quark.pan"
    )

    /**
     * Map a [NetDiskType] to the package name of its Android client.
     * Returns null for [NetDiskType.DIRECT_URL] / [NetDiskType.OTHER]
     * (no app to launch).
     */
    fun getNetDiskPackageName(type: NetDiskType): String? = when (type) {
        NetDiskType.BAIDU -> "com.baidu.netdisk"
        NetDiskType.QUARK -> QUARK_PACKAGE_CANDIDATES.first()
        NetDiskType.XUNLEI -> "com.xunlei.downloadprovider"
        NetDiskType.ALIYUN -> "com.alicloud.databox"
        NetDiskType.YUNPAN123 -> "com.yunpan.www"
        else -> null
    }

    /**
     * Build the custom-scheme URL the net-disk app is registered to
     * receive. Falls back to the original https:// URL for types
     * with no custom scheme (xunlei / unknown).
     */
    /** Same scheme used for pan.quark.cn shares — works as magnet/ed2k fallback. */
    fun buildQuarkSchemeUrl(url: String): String = "quark://$url"

    fun buildNetDiskIntentUrl(url: String, type: NetDiskType): String = when (type) {
        NetDiskType.BAIDU -> "bdpan://$url"
        NetDiskType.QUARK -> buildQuarkSchemeUrl(url)
        NetDiskType.XUNLEI -> url
        NetDiskType.ALIYUN -> "aliyunpan://$url"
        NetDiskType.YUNPAN123 -> "pan123://$url"
        else -> url
    }

    /**
     * Append an extraction code to a net-disk share URL when the link
     * itself does not already carry one. Baidu / Quark / Xunlei / Aliyun
     * clients recognise `?pwd=` (or `&pwd=`) and auto-fill the code.
     */
    fun appendExtractionCode(url: String, type: NetDiskType, code: String?): String {
        val pwd = code?.trim()?.takeIf { it.isNotBlank() } ?: return url
        if (url.isBlank()) return url
        val lower = url.lowercase()
        if (lower.contains("pwd=") || lower.contains("password=")) return url
        val sep = if (url.contains('?')) "&" else "?"
        return "$url${sep}pwd=$pwd"
    }
}
