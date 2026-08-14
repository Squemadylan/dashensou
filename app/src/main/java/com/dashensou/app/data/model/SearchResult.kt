package com.dashensou.app.data.model

import com.dashensou.app.service.linkcheck.LinkCheckStatus

enum class ResourceCategory {
    ALL,
    EBOOK,
    /** 真实的网盘分享(夸克/百度/阿里/迅雷/123)。与 EBOOK 的核心区别
     *  是这里的结果是 "要再去网盘 app 转存/打开" 的中转 URL,而不是
     *  本机直接下载的文件。 */
    NETDISK,
    // 历史遗留 — 旧 tab 用过,MOVIE 和 NETDISK 在新 UI 里语义一致,
    // 这里保留避免下游 enum 解析失败。Tab 实际不展示这两个。
    MOVIE,
    TV
}

enum class NetDiskType {
    BAIDU,
    QUARK,
    XUNLEI,
    ALIYUN,
    YUNPAN123,
    OTHER,
    DIRECT_URL
}

data class SearchResult(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val url: String = "",
    val netDiskType: NetDiskType = NetDiskType.OTHER,
    val size: String = "",
    val date: String = "",
    val sourceUrl: String = "",
    val sourceName: String = "",
    /**
     * Stable identifier of the [com.dashensou.app.service.source.SearchSource]
     * that produced this result (e.g. "pansou_252", "aiqu225"). This is
     * a machine-readable key — UI must keep using [sourceName] for display.
     *
     * Internal dispatch (download routing, scoring, logging) keys off this id
     * so that user-facing renames of the displayName never break behavior.
     */
    val sourceId: String = "",
    val category: ResourceCategory = ResourceCategory.ALL,
    val fileType: String? = null,
    val isValid: Boolean = true,
    val requiresWebView: Boolean = false,
    val extractionCode: String? = null,
    /** Share-link probe result; updated asynchronously after search. */
    val linkCheckStatus: LinkCheckStatus = LinkCheckStatus.UNCHECKED
)
