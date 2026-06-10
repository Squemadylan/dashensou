# 大神搜 (DaShenSou)

一个面向 Android 的多源网盘资源搜索与下载工具：聚合 11 个网盘/电子书搜索源的结果，做去重与打分排序，再以「打开网盘 App / WebView 中转 / 直接下载到 `Download/`」三种方式交付。

- **包名 / applicationId**：`com.dashensou.app`
- **版本**：`1.0.0`（versionCode 1）
- **minSdk**：26（Android 8.0）· **targetSdk / compileSdk**：34（Android 14）
- **语言**：Kotlin 1.9 · **AGP**：8.2.2 · **Gradle**：8.5 · **JDK**：17
- **架构**：Activity + 轻量 MVVM（`SearchViewModel` / `DownloadViewModel`），搜索/下载拆分为 `Service`，数据走 Room + Flow
- **远程仓库**：`https://github.com/Squemadylan/dashensou.git`（`master` 分支）

---

## 1. 目录结构

```
dashensou/
├── build.gradle              # 根 Gradle，声明 AGP / Kotlin / KSP 版本
├── settings.gradle           # 阿里云镜像 + 单 module 项目
├── gradle.properties         # JVM/构建参数，JBR 兼容（加 --add-exports 等）
├── gradle/wrapper/           # Gradle 8.5 wrapper
├── app/
│   ├── build.gradle          # 依赖、签名、ViewBinding、abiFilters（armeabi-v7a/arm64-v8a）
│   ├── proguard-rules.pro    # OkHttp/Gson/Room 保留规则
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
│       │   │   ├── MainActivity.kt         # thin shell：Tab 切换、Intent 分发、失败弹窗
│       │   │   ├── WebViewActivity.kt      # 网盘中转页（慢页面检测、提取码复制）
│       │   │   ├── search/SearchViewModel.kt
│       │   │   ├── download/DownloadViewModel.kt
│       │   │   └── *Adapter.kt             # 搜索结果 / 下载记录列表
│       │   └── util/                       # NetDiskUtils / PansouGotoResolver / SourcePrefs 等
│       └── res/                            # drawable / layout / menu / mipmap-* / values / xml
└── .trae/                     # 内部 PRD、技术文档与构建规则（GBK 编码，本地阅读）
```

---

## 2. 核心能力

| 模块 | 关键行为 |
| --- | --- |
| 搜索页 | 底部导航 + 顶部 Tab（**全部 / 电子书 / 网盘**），后台并发请求所有**已启用**的源，统一打分排序；切换 Tab 仅前端过滤，不重复发请求；空关键词时显示占位空态。 |
| 下载页 | 卡片式记录 + 进度条 / 状态色块；`DownloadProgressPoller` 进程级 2 秒轮询 `DownloadManager` 同步真实进度（切 Tab 不冻结）；支持暂停/恢复/重试/删除，已完成条目点击用系统应用打开。 |
| 我的页 | QQ 群加群入口（`mqqapi://card/show_pslcard` 深链，群号 `182225274`）、复制群号、**11 个数据源开关**（`SourcePrefs` + SharedPreferences 持久化）、版本号与关于说明。 |
| WebView 中转 | 对 `pansou.cc` 等「详情页才有分享链接」的源，先 `fetchDetail` 再经 `PansouGotoResolver`（headless WebView 解析 `/goto/` JS 跳转）拿真实分享 URL；解析失败则降级到 `WebViewActivity`；白名单域名命中后跳对应网盘 App，并支持「复制提取码」。 |
| 直接下载 | 对能解析到直链的源（如 `aiqu225` 二次跳转到 `downbook` 镜像），用 OkHttp + `MediaStore.Downloads` 写入 `Download/<分类>/`，Android 10+ 无需 `MANAGE_EXTERNAL_STORAGE`。 |
| 磁力/ed2k | 识别 `magnet:` / `ed2k:` 链接，复制到剪贴板并尝试用夸克浏览器打开离线下载。 |

底部导航三页：**搜索 / 下载 / 我的**（原「历史」Tab 已替换为「我的」）。

---

## 3. 搜索源清单

全部实现 `SearchSource` 接口（`search(keyword, page, category) -> SearchOutcome`）。`SearchService.defaultSources()` 中的默认启用状态如下；用户可在「我的」页覆盖，设置写入 `source_prefs`。

| 源 ID | 显示名（我的页） | 类型 | 协议 | 默认启用 | 备注 |
| --- | --- | --- | --- | --- | --- |
| `wanzhan` | 万站聚合 | 网盘 | HTTPS/JSON | ✅ | 多 Key 轮询 + 健康冷却（2 次连续失败冷却 120s），全局 4s 节奏控制 |
| `pansou_252` | 网盘来源 | 网盘 | HTTPS/JSON | ✅ | POST `api/search`，`merged_by_type` 按网盘类型聚合 |
| `pansou_cc` | 搜盘来源 | 网盘 | HTML + Jsoup | ✅ | 列表轻量返回；用户点下载时 `fetchDetail` + `PansouGotoResolver` 二次解析 |
| `panclub_quark` | 夸克网盘 | 网盘 | HTML + Jsoup | ✅ | 抽象基类 `PanClubSearchBase`；搜索只抓列表，点下载时 `resolveShareUrl` |
| `panclub_baidu` | 百度网盘 | 网盘 | HTML + Jsoup | ✅ | 同上 |
| `panclub_alipan` | 阿里云盘 | 网盘 | HTML + Jsoup | ✅ | 同上 |
| `xiaoshuo` | 电子书直链 | 直链 | HTTPS/JSON | ✅ | 连接/读超时 2s，在 `SOURCE_TIMEOUT_MS` 内自返 |
| `aiqu225` | 电子书搜索 | 直链 | HTML + Jsoup | ✅ | 二段式：详情页 → `softdownfree.asp` → `downbook` 的 `.txt` 镜像 |
| `api52` | 聚合搜索 | 网盘 | HTTPS/JSON | ❌ | 自带默认 Key，quark+baidu 并发拉取；可在「我的」页手动开启 |
| `openlibrary` | 海外图书 | 直链 | HTTPS/JSON | ❌ | 可在「我的」页手动开启 |
| `gutendex` | 海外公版 | 直链 | HTTPS/JSON | ❌ | 可在「我的」页手动开启 |

路由与打分均使用稳定的 `sourceId`，`displayName` 仅供 UI 展示，改名不影响下载分发逻辑。

---

## 4. 关键架构决策

### 4.1 MVVM 分层

- **`SearchViewModel`**：持有搜索关键词、分类、结果列表、加载态与失败信息（`SearchUiState`）；取消上一次 in-flight 搜索，避免快速连击导致旧结果覆盖新结果。
- **`DownloadViewModel`**：订阅 `DownloadRecordDao` 的 Flow，处理行级操作（打开/暂停/恢复/重试/删除/打开下载目录）。
- **`MainActivity`**：仅负责 View 绑定、底部导航、下载按钮的多源分发（aiqu / pansou.cc / pan.club / 直链 / 磁力）、失败弹窗与 Intent 跳转。

### 4.2 搜索

- **并发 + 超时收口**：对每个启用源 `async(IO) + withTimeoutOrNull(2500ms)`，单源超时/异常不拖死整次搜索；每源结果上限 200 条。
- **相关性打分（v2）**：
  - 基础分 = 源码权重（10–100）+ 网盘类型分（0–30）+ 时效衰减
  - 标题**未**包含关键词：**-200**（`MISS_PENALTY`），确保源权重无法把无关结果顶到前面
  - 全词命中 +150，分词命中 +35/词，位置靠前 +40，短标题 +8
- **去重 key**：`<netDiskType>|<title40字(去非中英数字)>|<url去query>`，跨源重复合并为一条，提取码取首个非空。
- **失败分类**：`NETWORK / TIMEOUT / SOURCE_DOWN / PARSE / EMPTY / UNKNOWN`，UI 按类型给出重试或换词建议。

### 4.3 下载

- **系统 DownloadManager**：`getExternalFilesDir(Download)/DaShenSou/<subDir>/` 私有目录，失败兜底公共 `Download/DaShenSou_<sub>_<file>`。
- **OkHttp + MediaStore**：`enqueueDirectDownload` 走 `MediaStore.Downloads.IS_PENDING` 两阶段写入，落 `Download/Book|Movie|TV|Other/`。
- **文件名兜底**：优先 `SearchResult.fileType`，其次 URL 路径扩展名，再不行 `.download`；不从整段 URL query 猜扩展名。
- **`DownloadProgressPoller`**：Application 级单例，2s 轮询所有 `DOWNLOADING` 记录，写回 Room；UI 纯 Flow 消费，不绑定 Activity 生命周期。

### 4.4 网盘跳转

- **`PansouGotoResolver`**：主线程 headless WebView 加载 `/goto/` 页，监听重定向到已知网盘域名后返回真实 URL（10s 超时）。
- **`WebViewActivity`**：白名单域（`pan.baidu.com` / `pan.quark.cn` / `aliyundrive.com` / `123pan.com` 等）命中后拉起对应 App；10 秒慢页面检测，可降级系统浏览器或复制链接。
- **打开网盘 App 三段回退**：`openBySchemeWithPackage` → `openByChooserWithPackage` → `openByChooser`；`NetDiskUtils` 维护包名映射。

### 4.5 数据

- **Room v2**：`search_history` + `download_records`；当前 `fallbackToDestructiveMigration()` 仅作开发期安全网，发版前需补正式 Migration。
- **`search_history` 表已建但 UI 未接入**：`SearchHistoryDao` 存在，尚无写入/展示逻辑（历史 Tab 已移除）。

---

## 5. UI 与设计

- **配色**（摘 `colors.xml`）：
  - 主色：`#7F5DFE` → `#5B21B6` 渐变；强调 `#FFE066` 霓虹黄
  - 辅助：`#06B6D4` 青、`#EC4899` 品红、`#22D3EE` 霓虹青
  - 背景：`#0F0F23` 深夜空、`#1A1A3C` 卡片底；半透明白 `glass=#33FFFFFF`
- **自绘资源**：`bg_glass_card`（玻璃卡）、`bg_neon_button`（霓虹边）、`progress_gradient`（渐变进度条）、`ic_lightning_crown`（品牌 Logo）、`ic_nav_*` 三个底部导航图标。
- **布局层级**：`activity_main.xml` 用 `FrameLayout` 装三页（**搜索 / 下载 / 我的**）；搜索页 = 顶部渐变背景 + `Material TextInputLayout` 搜索框 + `TabLayout`（全部/电子书/网盘）+ `SwipeRefreshLayout` 包 `RecyclerView`（无关键词时禁用下拉刷新）。
- **国际化**：目前仅中文（`values/strings.xml`）；网盘类型名由 `DiskLabels` / `NetDiskUtils` 渲染。

---

## 6. 数据库

`AppDatabase`（Room v2，2 张表）：

- `search_history(id PK, keyword, searchTime, searchCount)`：`SearchHistoryDao` 已定义查询/插入接口，**当前无 UI 调用**。
- `download_records(id PK, title, url, filePath, fileSize, downloadSize, status, downloadTime, netDiskType, category, downloadId)`：
  - 状态：`PENDING / DOWNLOADING / PAUSED / COMPLETED / FAILED`
  - `downloadId` 是 `DownloadManager.enqueue(...)` 返回的 `Long`，用于查询进度/取消。
  - `filePath` 对直链下载是 `Download/<sub>/<name>`（相对路径），由 `FileOpener` 在 `MediaStore` 中反查 URI 再 `ACTION_VIEW` 打开。

---

## 7. 构建与运行

本机已验证的工具链（见 `.trae/rules/project_rules.md`）：

- **JDK 17**：`C:\Program Files\Android\Android Studio\jbr`
- **Android SDK**：`C:\Users\Squema-Mini\AppData\Local\Android\Sdk`
- **Gradle 8.5**（系统级，不走 wrapper）：`C:\Users\Squema-Mini\.gradle\wrapper\dists\gradle-8.5-bin\5t9huq95ubn472n8rpzujfbqh\gradle-8.5\bin\gradle.bat`

### Debug 构建并安装到设备

```powershell
$env:JAVA_HOME        = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME     = "C:\Users\Squema-Mini\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT = "C:\Users\Squema-Mini\AppData\Local\Android\Sdk"
& "C:\Users\Squema-Mini\.gradle\wrapper\dists\gradle-8.5-bin\5t9huq95ubn472n8rpzujfbqh\gradle-8.5\bin\gradle.bat" assembleDebug --no-daemon
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

```powershell
& "C:\Users\Squema-Mini\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices
& "C:\Users\Squema-Mini\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s <deviceId> install -r -t "E:\New\dashensou\app\build\outputs\apk\debug\app-debug.apk"
& "C:\Users\Squema-Mini\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s <deviceId> shell pm list packages com.dashensou.app
```

### Release 构建

`release` 块已开 R8 + 资源压缩，abiFilters 限定 `armeabi-v7a / arm64-v8a`。Release 出包需要自配签名。

### 常见问题

- 编译报错：参考 `android-build-install` skill 列举的常见修复（KAPT→KSP、`jlink` 缺失、`styles.xml` 缺 `android:` 前缀等）。
- `adb devices` 为空：确认 USB 调试已开且已授权。
- 设备掉线：重新跑 `adb devices` 取最新 id 再 `install`。
- APK 已存在且源码未变：直接 `install -r`，不必全量 rebuild。

---

## 8. 权限与外部交互

`AndroidManifest.xml`：

- 网络：`INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE`。
- 存储：API ≤ 32 的 `READ/WRITE_EXTERNAL_STORAGE`，API 33+ 的 `READ_MEDIA_IMAGES/VIDEO/AUDIO`；`DownloadManager` 走系统 API 不需要额外权限。
- 通知：`DOWNLOAD_WITHOUT_NOTIFICATION` + `FOREGROUND_SERVICE`。
- 包名可见：`<queries>` 块声明网盘 App 包名，Android 11+ 才能 `Intent.ACTION_VIEW` 拉起它们。
- `android:usesCleartextTraffic="true"` + `network_security_config.xml` 信任系统 CA，允许 HTTP 源。
- `FileProvider` 授权 `${applicationId}.fileprovider`，`file_paths.xml` 仅声明 `external-path`。

---

## 9. 已知限制 / 注意事项

1. **没有单测 / instrumentation 测试**：`app/src/test` 与 `app/src/androidTest` 不存在。改搜索源解析逻辑时建议补 HTML 快照 + JSON 的离线断言。
2. **搜索历史 UI 已移除**：`search_history` 表与 DAO 仍保留，但无写入/展示；若需恢复历史 Tab，需补 ViewModel + UI 接入。
3. **网络源随时变脸**：`pansou.cc / pan.club / aiqu225` 等非官方站，`onclick` 抽链接、`/goto/` JS 解密等一旦改版，`PanClubSource` / `PansouCcSource` / `AiQuSource.fetchDetail` / `PansouGotoResolver` 需跟着调。
4. **Wanzhan API 需要 Key**：`WanzhanApiSource.apiKeys` 留空时走 `apiKey=null`，有调用频率限制；上线前最好注入自有 Key（`SearchService` 构造参数）。
5. **直链下载分类目录**：`subDirFor` 写死 `Book / Movie / TV / Other`，未按网盘类型再分。
6. **Room 迁移策略**：`fallbackToDestructiveMigration()` 仅作开发期安全网，生产构建前需为每次 schema bump 写正式 Migration。
7. **ProGuard 残留**：`proguard-rules.pro` 中 `com.example.chatbot.**` 的 keep 规则来自其他项目，与本项目无关，发版前建议清理。

---

## 10. 后续可优化方向

- **恢复或重做搜索历史**：复用现有 `SearchHistoryDao`，或在搜索框下方做热词 chips。
- **WebView 白名单扩展**：新接网盘时同步改 `PansouGotoResolver.NET_DISK_DOMAINS`、`WebViewActivity` 白名单与 `NetDiskUtils` 三处映射。
- **WorkManager 失败重试**：`DownloadManager.STATUS_FAILED` 时自动排队重试，减少用户手动进 App 看红条。
- **自适应超时**：`SearchService` 当前每源 2.5s 硬编码，可按 Wi-Fi / 弱网分级，给 Wanzhan 等付费源更多耐心。
- **默认开启海外源**：`OpenLibrarySource` / `GutendexSource` 已写完，适合作为「全部」页的公版书兜底。
- **整理开发调试产物**：工作区中的 `probe_*.py`、`debug_*.png`、`logcat.txt` 等本地探测文件建议加入 `.gitignore` 或移出仓库。
