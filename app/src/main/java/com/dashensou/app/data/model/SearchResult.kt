package com.dashensou.app.data.model

enum class ResourceCategory {
    ALL,
    EBOOK,
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
    val category: ResourceCategory = ResourceCategory.ALL,
    val fileType: String? = null,
    val isValid: Boolean = true
)
