package com.dashensou.app.util

import com.dashensou.app.data.model.NetDiskType

object NetDiskUtils {

    fun getNetDiskType(url: String): NetDiskType {
        return when {
            url.contains("pan.baidu.com") -> NetDiskType.BAIDU
            url.contains("pan.quark.cn") -> NetDiskType.QUARK
            url.contains("pan.xunlei.com") -> NetDiskType.XUNLEI
            url.contains("www.aliyundrive.com") || url.contains("aliyundrive.cn") -> NetDiskType.ALIYUN
            url.contains("123pan.com") -> NetDiskType.YUNPAN123
            else -> {
                if (isDirectDownloadUrl(url)) {
                    NetDiskType.DIRECT_URL
                } else {
                    NetDiskType.OTHER
                }
            }
        }
    }

    fun isDirectDownloadUrl(url: String): Boolean {
        val extensions = listOf(".txt", ".pdf", ".epub", ".mobi", ".azw3", ".mp3", ".mp4", ".mkv", ".avi", ".zip", ".rar", ".7z")
        return extensions.any { url.lowercase().contains(it) }
    }

    fun getNetDiskTypeName(type: NetDiskType): String {
        return when (type) {
            NetDiskType.BAIDU -> "百度网盘"
            NetDiskType.QUARK -> "夸克网盘"
            NetDiskType.XUNLEI -> "迅雷网盘"
            NetDiskType.ALIYUN -> "阿里云盘"
            NetDiskType.YUNPAN123 -> "123云盘"
            NetDiskType.DIRECT_URL -> "直接下载"
            else -> "其他"
        }
    }

    fun getNetDiskPackageName(type: NetDiskType): String? {
        return when (type) {
            NetDiskType.BAIDU -> "com.baidu.netdisk"
            NetDiskType.QUARK -> "com.quark.browser"
            NetDiskType.XUNLEI -> "com.xunlei.downloadprovider"
            NetDiskType.ALIYUN -> "com.alicloud.databox"
            NetDiskType.YUNPAN123 -> "com.yunpan.www"
            else -> null
        }
    }

    fun buildNetDiskIntentUrl(url: String, type: NetDiskType): String {
        return when (type) {
            NetDiskType.BAIDU -> {
                "bdpan://share?url=$url"
            }
            NetDiskType.QUARK -> {
                "quark://pan/share?url=$url"
            }
            else -> url
        }
    }
}
