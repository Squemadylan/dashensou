# 大神搜 (DaShenSou)

一个面向 Android 的多源网盘资源搜索与下载工具。聚合 11 个搜索源的结果，做去重与打分排序，再以「打开网盘 App / WebView 中转 / 直接下载到 Download/」三种方式交付。

## 项目概览

| 项目 | 信息 |
|------|------|
| 包名 | `com.dashensou.app` |
| 版本 | 1.0.0 (versionCode 1) |
| minSdk / targetSdk | 26 (Android 8.0) / 34 (Android 14) |
| 语言 | Kotlin 1.9 |
| 构建工具 | AGP 8.2.2 · Gradle 8.5 · JDK 17 |
| 架构 | Activity + 轻量 MVVM + Room + Flow |
| 远程仓库 | https://github.com/Squemadylan/dashensou.git |

---

## 主要功能

### 搜索
- **多源并发**：同时请求所有启用的数据源，2500ms 超时收口，单源失败不拖死整次搜索
- **智能打分 v2**：源码权重 + 网盘类型分 + 时效衰减 + 关键词命中加成 + 标题缺失惩罚
- **自动去重**：按 `<网盘类型>|<标题前40字>|<URL去参>` 跨源合并
- **分类过滤**：全部 / 电子书 / 网盘 三 Tab，切换仅客户端过滤，不重复请求

### 下载
- **系统 DownloadManager**：私有目录存储，失败兜底公共目录
- **直链下载**：OkHttp + MediaStore.Downloads，Android 10+ 写入 `Download/分类/`
- **进度跟踪**：进程级 2 秒轮询所有 DOWNLOADING 记录，状态写入 Room
- **支持操作**：暂停 / 恢复 / 重试 / 删除 / 用系统应用打开

### 网盘跳转
- **PansouGotoResolver**：Headless WebView 解析 pansou.cc 的 JS 跳转链接
- **WebViewActivity**：白名单域名命中后拉起对应 App（百度/夸克/阿里/迅雷/123云盘）
- **三段回退**：`Scheme 指定包` → `Chooser 指定包` → `无限定向 Chooser`

### 其他
- **主题切换**：浅色 / 深色 / 跟随系统
- **强制/可选更新**：启动时拉取 `app/update.json`；`local < minVersionCode` 强制更新（不可关闭），`local < versionCode` 可选更新（24h 节流）；支持应用内下载 APK + FileProvider 安装，失败可走网盘/浏览器手动更新
- **磁力/ed2k**：识别 magnet:/ed2k: 链接，复制到剪贴板并尝试用夸克打开
- **源健康检查**：手动探测所有已启用源的可访问性

---

## 目录结构

```
dashensou/
├── build.gradle              # 根 Gradle，声明 AGP / Kotlin / KSP 版本
├── settings.gradle           # 项目配置
├── gradle.properties         # JVM/构建参数
├── gradle/wrapper/           # Gradle 8.5 wrapper
├── app/
│   ├── build.gradle          # 依赖、签名、ViewBinding、abiFilters
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/dashensou/app/
│       │   ├── App.kt                      # Application，初始化 Room DB + 下载进度轮询
│       │   ├── data/model/                 # SearchResult / SearchHistory / DownloadRecord
│       │   ├── database/                   # AppDatabase + 2 个 DAO
│       │   ├── net/                        # HttpClient（全局 OkHttp 单例）
│       │   ├── service/                    # SearchService / DownloadManager / DirectDownloader
│       │   │   └── source/                 # 11 个搜索源实现
│       │   ├── ui/
│       │   │   ├── MainActivity.kt         # 主界面，Tab 切换、Intent 分发
│       │   │   ├── WebViewActivity.kt      # WebView 中转页
│       │   │   ├── search/SearchViewModel.kt
│       │   │   ├── download/DownloadViewModel.kt
│       │   │   └── *Adapter.kt             # 列表适配器
│       │   └── util/                       # 工具类
│       └── res/                            # 资源文件
└── .trae/                     # 内部文档（GBK 编码）
```

---

## 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Kotlin 1.9 |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 (Android 14) |
| 架构 | Activity + MVVM |
| 异步 | Kotlin Coroutines + Flow |
| 数据库 | Room v2 |
| HTTP | OkHttp（全局单例）|
| HTML 解析 | Jsoup |
| UI | ViewBinding + Material Design 3 + RecyclerView |

---

## 搜索源清单

| 源 ID | 显示名 | 类型 | 协议 | 默认启用 |
|-------|--------|------|------|----------|
| `wanzhan` | 万站聚合 | 网盘 | HTTPS/JSON | ✅ |
| `pansou_252` | 网盘来源 | 网盘 | HTTPS/JSON | ✅ |
| `pansou_cc` | 搜盘来源 | 网盘 | HTML + Jsoup | ✅ |
| `haisou` | 海搜 | 网盘 | - | ✅ |
| `duanju` | 短剧 | 网盘 | - | ✅ |
| `xiaoshuo` | 电子书直链 | 直链 | HTTPS/JSON | ✅ |
| `aiqu225` | 电子书搜索 | 直链 | HTML + Jsoup | ✅ |
| `api52` | 聚合搜索 | 网盘 | HTTPS/JSON | ❌ |
| `openlibrary` | 海外图书 | 直链 | HTTPS/JSON | ❌ |
| `gutendex` | 海外公版 | 直链 | HTTPS/JSON | ❌ |

---

## 数据库

`AppDatabase`（Room v2，2 张表）：

- **`search_history`**：搜索历史记录（表和 DAO 已存在，UI 未接入）
- **`download_records`**：下载记录
  - 状态：`PENDING / DOWNLOADING / PAUSED / COMPLETED / FAILED`
  - `downloadId` 是 `DownloadManager.enqueue()` 返回的 Long
  - 直链下载的 `filePath` 格式为 `Download/<分类>/<文件名>`

---

## 构建与运行

### 环境要求
- JDK 17
- Android SDK
- Gradle 8.5

### Debug 构建

```powershell
# 设置环境变量
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\Squema-Mini\AppData\Local\Android\Sdk"

# 构建
.\gradlew assembleDebug

# 安装到设备
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

---

## 权限

- `INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE`
- 存储：API ≤ 32 用 `READ/WRITE_EXTERNAL_STORAGE`，API 33+ 用 `READ_MEDIA_IMAGES/VIDEO/AUDIO`
- `DOWNLOAD_WITHOUT_NOTIFICATION / FOREGROUND_SERVICE`
- `<queries>` 声明网盘 App 包名（百度/夸克/阿里/迅雷/123云盘）

---

## 应用更新（强制更新）

逻辑对齐 Dubaixia 的 `AppUpdateManager`：

| 条件 | 行为 |
|------|------|
| `localVersionCode < minVersionCode` | **强制更新**：不可取消、拦截返回键；可「立即更新」或「网盘手动更新」 |
| `minVersionCode ≤ local < versionCode` | **可选更新**：可稍后；启动检查 24 小时内最多提示一次 |
| `local ≥ versionCode` | 已是最新 |

配置文件：
- 远程：`https://raw.githubusercontent.com/Squemadylan/dashensou/main/app/update.json`（jsDelivr 镜像备用）
- 仓库内：`app/update.json`
- 内置兜底：`app/src/main/assets/update_manifest_fallback.json`（仅启动检查可用；手动检查不走兜底）

发版时同步更新 `versionCode` / `versionName` / `minVersionCode` / `apkUrl`（HTTPS）/ 可选 `sha256` / 可选 `manualUpdateUrl`，并把 APK 挂到 `apkUrl` 指向的地址。需要强制老用户升级时，将 `minVersionCode` 设为新版本的 `versionCode`。

「我的」页提供「检查更新」与「网盘手动更新」入口。

---

## 已知限制

1. **暂无测试**：没有单测和 instrumentation 测试
2. **搜索历史 UI 未接入**：表和 DAO 保留但无 UI 调用
3. **第三方源可能变脸**：pansou.cc、aiqu225 等非官方站随时可能改版
4. **Wanzhan API 需要 Key**：上线前建议注入自有 Key
5. **Room 迁移**：`fallbackToDestructiveMigration()` 仅作开发期安全网
6. **ProGuard 残留**：部分规则来自其他项目，发版前建议清理

---

## 后续可优化方向

- 恢复或重做搜索历史功能
- WebView 白名单扩展（新接网盘时同步更新三处映射）
- WorkManager 失败重试
- 自适应超时（Wi-Fi / 弱网分级）
- 默认开启海外源（OpenLibrary / Gutendex 作为公版书兜底）
