package com.dashensou.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * 与仓库 [app/update.json] 对应，经 GitHub Raw / jsDelivr 拉取。
 */
data class UpdateManifest(
    @SerializedName("versionCode") val versionCode: Int = 0,
    @SerializedName("versionName") val versionName: String = "",
    @SerializedName("minVersionCode") val minVersionCode: Int = 0,
    @SerializedName("apkUrl") val apkUrl: String = "",
    @SerializedName("changelog") val changelog: String = "",
    /** 可选；非空时下载完成后校验 APK SHA-256（十六进制小写） */
    @SerializedName("sha256") val sha256: String? = null,
    /** 可选；国内或 GitHub 不可达时，跳转网盘/浏览器手动下载（HTTPS） */
    @SerializedName("manualUpdateUrl") val manualUpdateUrl: String? = null
)
