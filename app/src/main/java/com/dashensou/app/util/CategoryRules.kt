package com.dashensou.app.util

import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult

/**
 * The single source of truth for "is this search hit a 全部 / 电子书 /
 * 网盘 candidate". Used by:
 *  - SearchUiState.visibleResults (the tab filter that runs on the
 *    UI thread, no source involvement)
 *  - any source that wants to drop a card at parse time (so the
 *    source can save the network round-trip for a result that would
 *    be filtered out anyway)
 *
 * The ALL branch is the union — the front-end filter must not drop
 * anything. EBOOK shows a hit when its fileType is an ebook extension
 * OR the netDiskType is DIRECT_URL (aiqu-style .txt mirrors). NETDISK
 * shows a hit when the netDiskType is one of the real 5 netdisks OR
 * OTHER (pansou.cc 中转页).
 */
object CategoryRules {

    val REAL_NETDISK_TYPES: Set<NetDiskType> = setOf(
        NetDiskType.BAIDU,
        NetDiskType.QUARK,
        NetDiskType.XUNLEI,
        NetDiskType.ALIYUN,
        NetDiskType.YUNPAN123
    )

    fun matches(result: SearchResult, category: ResourceCategory): Boolean =
        when (category) {
            ResourceCategory.ALL -> true
            ResourceCategory.EBOOK -> isEbook(result)
            ResourceCategory.NETDISK -> isNetDisk(result)
            // Historical legacy values — kept for any older code that
            // still references them. The UI no longer shows these tabs.
            ResourceCategory.MOVIE -> isVideo(result)
            ResourceCategory.TV -> isVideo(result)
        }

    fun isEbook(r: SearchResult): Boolean {
        if (r.netDiskType in REAL_NETDISK_TYPES) return false
        // OTHER 几乎都是 pansou.cc / 52api 这类中转页,不是电子书
        if (r.netDiskType == NetDiskType.OTHER) return false
        // DIRECT_URL 在 aiqu 那里是 .txt 小说,归电子书
        val ft = r.fileType
        return ft == null || ft in FileTypes.EBOOK || ft == "txt" || ft == "mobi" ||
            ft == "pdf" || ft == "epub" || ft == "html" || ft == "azw3" || ft == "archive"
    }

    fun isNetDisk(r: SearchResult): Boolean =
        r.netDiskType in REAL_NETDISK_TYPES || r.netDiskType == NetDiskType.OTHER

    private fun isVideo(r: SearchResult): Boolean {
        val ft = r.fileType
        return ft == null || ft == "video" || ft == "magnet"
    }

    /**
     * Title-only pre-filter for sources that want to skip work for
     * a card the front-end tab would hide anyway. The result is
     * conservative — it may let a few through that the result-level
     * rule would have caught (because the source may be about to
     * assign a fileType hint), but it never drops something the
     * result-level rule would have shown.
     */
    fun matchesByNetDisk(
        netDisk: NetDiskType,
        fileType: String?,
        category: ResourceCategory
    ): Boolean {
        return when (category) {
            ResourceCategory.ALL -> true
            ResourceCategory.NETDISK -> netDisk in REAL_NETDISK_TYPES || netDisk == NetDiskType.OTHER
            ResourceCategory.EBOOK -> {
                if (netDisk in REAL_NETDISK_TYPES) return false
                if (netDisk == NetDiskType.OTHER) return false
                val ft = fileType
                ft == null || ft in FileTypes.EBOOK || ft == "txt" || ft == "mobi" ||
                    ft == "pdf" || ft == "epub" || ft == "html" || ft == "azw3" || ft == "archive"
            }
            ResourceCategory.MOVIE, ResourceCategory.TV -> {
                val ft = fileType
                ft == null || ft == "video" || ft == "magnet"
            }
        }
    }
}
