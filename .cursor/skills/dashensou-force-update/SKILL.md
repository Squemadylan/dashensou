---
name: dashensou-force-update
description: >-
  大神搜 (dashensou) 应用内强制/可选更新发版流程：改 update.json、打 APK、上传
  GitHub Release、推送 master。在用户提到强制更新、强更、发版更新、取消强制更新、
  update.json、minVersionCode、AppUpdateManager 时使用。
---

# 大神搜强制更新发版

基于 `AppUpdateManager` + 远程 `app/update.json`（对齐 Dubaixia 逻辑）。

## 判定规则

| 条件 | 结果 |
|------|------|
| `local < minVersionCode` | **强制更新**（不可关闭） |
| `minVersionCode ≤ local < versionCode` | **可选更新**（可稍后，启动 24h 节流） |
| `local ≥ versionCode` | 已是最新 |

- 强制老用户：`minVersionCode` = 新包的 `versionCode`
- 只提醒不强制：提高 `versionCode`，`minVersionCode` 保持旧值
- 取消强制：把 `minVersionCode` 降到仍允许使用的最低 `versionCode`

## 必改文件（三处同步）

1. `app/build.gradle` — `versionCode` / `versionName`
2. `app/update.json` — 远程清单（客户端拉取）
3. `app/src/main/assets/update_manifest_fallback.json` — 与上一份**内容一致**（离线兜底）

## `update.json` 模板

```json
{
  "versionCode": 3,
  "versionName": "1.1.0",
  "minVersionCode": 3,
  "apkUrl": "https://github.com/Squemadylan/dashensou/releases/download/v1.1.0/dashensou-1.1.0.apk",
  "changelog": "v1.1.0: describe changes in ASCII only.",
  "sha256": "optional_lowercase_hex",
  "manualUpdateUrl": "https://github.com/Squemadylan/dashensou/releases"
}
```

约束：

- `minVersionCode <= versionCode`，且两者都 `> 0`
- `apkUrl` / `manualUpdateUrl` 必须 `https://`
- **changelog 用 ASCII**（中文经 PowerShell/gh API 易损坏 JSON，导致 Gson 解析失败后走兜底）
- `sha256` 可选；有则下载后校验，失败会删包报错

## 发版步骤（按顺序）

复制并勾选：

```
- [ ] 1. bump app/build.gradle versionCode / versionName
- [ ] 2. assembleDebug（或 release）
- [ ] 3. gh release 上传 APK（文件名纯 ASCII）
- [ ] 4. 核对 Release 真实 asset name，填写 apkUrl
- [ ] 5. 校验 apkUrl HEAD/GET 非 404
- [ ] 6. 计算 sha256 写入两份 json（可选但推荐）
- [ ] 7. 同步 update.json + update_manifest_fallback.json
- [ ] 8. commit + push origin master（不是 main）
- [ ] 9. 旧版手机冷启动，logcat 确认
```

### 1–2. 版本与打包

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\Squema-Mini\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.5-bin\5t9huq95ubn472n8rpzujfbqh\gradle-8.5\bin\gradle.bat" assembleDebug --no-daemon
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 3–5. Release 与 apkUrl

GitHub 直链格式：

```text
https://github.com/Squemadylan/dashensou/releases/download/<tag>/<asset-name>
```

`<asset-name>` 必须是 Release **真实文件名**（`assets[].name`），不是友好显示名。

```powershell
# 上传（示例：本地先复制为纯 ASCII 名）
Copy-Item app\build\outputs\apk\debug\app-debug.apk .\dashensou-1.1.0.apk
gh release create "v1.1.0" ".\dashensou-1.1.0.apk" --repo Squemadylan/dashensou --title "v1.1.0" --notes "..."

# 核对真实文件名
gh release view v1.1.0 --repo Squemadylan/dashensou --json assets --jq ".assets[] | {name,url}"
```

### 6. SHA256

```powershell
(Get-FileHash ".\dashensou-1.1.0.apk" -Algorithm SHA256).Hash.ToLower()
```

### 7–8. 推送清单

**必须 push 到 `master`**。客户端 `AppUpdateManager` / `BuildConfig` 读取：

- `https://raw.githubusercontent.com/Squemadylan/dashensou/master/app/update.json`
- `https://cdn.jsdelivr.net/gh/Squemadylan/dashensou@master/app/update.json`

多源会取 **versionCode 最高** 的一份；任一镜像残留更高的旧强制配置，仍会强更。

推送后可清 jsDelivr：

```powershell
curl.exe -sL "https://purge.jsdelivr.net/gh/Squemadylan/dashensou@master/app/update.json"
```

### 9. 验证

手机保持**旧 versionCode**，勿先装新包。冷启动后：

```powershell
adb logcat -s AppUpdateManager:D AppUpdateManager:I AppUpdateManager:W
```

期望强更：`Force update required: local=X min=Y`  
期望取消：加载到 `versionCode`，且**无** `Force update required`。

## 取消强制更新

1. 改两份 json：`minVersionCode` ≤ 当前允许版本（常与现网 `versionCode` 对齐或更低）
2. push `master`
3. 若已装包内嵌兜底仍是强制配置，需重装/发版带新 `update_manifest_fallback.json` 的包；否则网络失败时仍可能走旧兜底

## 常见坑

1. **apkUrl 404**：asset 名与 Release 真实 `name` 不一致（中文名 / `#label`）
2. **changelog 非 ASCII**：JSON 损坏 → 全部源解析失败 → 走内置兜底（可能仍是旧强更）
3. **推错分支**：改了 `main` 但客户端读 `master`（或反过来）
4. **CDN 残留高 versionCode**：`max(versionCode)` 策略会继续强更；以 master 为准并 purge
5. **先装了新包再测强更**：`local >= minVersionCode`，看不到强制弹窗

## 相关代码

- `app/src/main/java/com/dashensou/app/util/AppUpdateManager.kt`
- `app/src/main/java/com/dashensou/app/data/model/UpdateManifest.kt`
- 启动：`MainActivity.onResume` → `AppUpdateManager.runStartupCheck`
- 手动：我的页 → `runManualCheck` / `openManualUpdatePage`
