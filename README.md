# 大神搜 (DaShenSou)

一个面向 Android 的多源网盘资源搜索与下载工具:聚合 11 个网盘/电子书搜索源的结果,做去重与打分排序,再以"打开网盘 App / WebView 中转 / 直接下载到 `Download/`"三种方式交付。

- **包名 / applicationId**:`com.dashensou.app`
- **minSdk**:26(Android 8.0)·**targetSdk / compileSdk**:34(Android 14)
- **语言**:Kotlin 1.9 · **AGP**:8.2.2 · **Gradle**:8.5 · **JDK**:17
- **架构**:Activity + 轻量 MVVM,搜索/下载拆分为 `Service`,数据走 Room + Flow
- **远程仓库**:`https://github.com/Squemadylan/dashensou.git`(`master` 分支)

---

## 1. 目录结构

```
dashensou/
├── build.gradle              # 根 Gradle,声明 AGP / Kotlin / KSP 版本
├── settings.gradle           # 阿里云镜像 + 单 module 项目
├── gradle.properties         # JVM/构建参数,JBR 兼容(加 --add-exports 等)
├── gradle/wrapper/           # Gradle 8.5 wrapper
├── app/
│   ├── build.gradle          # 依赖、签名、ViewBinding、abiFilters(armeabi-v7a/arm64-v8a)
│   ├── proguard-rules.pro    # Retrofit/OkHttp/Gson/Room 保留规则
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/dashensou/app/
│       │   ├── App.kt                      # Application,初始化 Room DB
│       │   ├── data/model/                 # SearchResult / SearchHistory / DownloadRecord
│       │   ├── data/recommend/             # 默认推荐源(空输入态)
│       │   ├── database/                   # AppDatabase + 2 个 DAO
│       │   ├── service/                    # SearchService / DownloadManager / DirectDownloader
│       │   ├── service/source/             # 11 个搜索源实现
│       │   ├── ui/                         # MainActivity / WebViewActivity / 4 个 Adapter
│       │   └── util/                       # FileOpener / NetDiskUtils / FileUtils
│       └── res/                            # drawable / layout / menu / mipmap-* / values / xml
└── .trae/                     # 内部 PRD、技术文档与构建规则(GBK 编码,本地阅读)
```

---

## 2. 核心能力

| 模块 | 关键行为 |
| --- | --- |
| 搜索页 | 底部导航 + 顶部 Tab(全部 / 电子书 / 视频),后台并发请求所有启用的源,统一打分排序;支持关键字命中加分、网盘类型加权、时效衰减、提取码存在性加分。 |
| 历史页 | Room 持久化,关键词去重,点选回到搜索页;同一关键词多次搜索累加 `searchCount`。 |
| 下载页 | 卡片式记录 + 进度条 / 状态色块,2 秒轮询 `DownloadManager` 同步真实进度;长按弹出删除确认,已完成条目点击用系统应用打开。 |
| WebView 中转 | 对 `pansou.cc` 这类"详情页才有分享链接"的源,先抓详情再加载中转页;中转页域名白名单命中后跳对应网盘 App,并支持"复制提取码"。 |
| 直接下载 | 对能解析到直链的源(如 `aiqu225` 二次跳转到 `downbook` 镜像),用 OkHttp + `MediaStore.Downloads` 写入 `Download/<分类>/`,在 Android 10+ 受限存储下无需 `MANAGE_EXTERNAL_STORAGE`。 |
| 推荐占位 | 搜索框为空时回退到 `BundledRecommendationSource` 的 3 条占位卡片,UI 不留白。 |

---

## 3. 搜索源清单

全部实现 `SearchSource` 接口(`search(keyword, page, category) -> SearchOutcome`)。`SearchService.defaultSources()` 中的启用状态:

| 源 ID | 显示名 | 类型 | 协议 | 默认启用 | 备注 |
| --- | --- | --- | --- | --- | --- |
| `wanzhan` | 万站 API(聚合) | 网盘 | HTTPS/JSON | ✅ | 多 Key 轮询 + 健康冷却(2 次连续失败冷却 120s),全局 4s 节奏控制 |
| `pansou_252` | PanSou 盘搜(`so.252035.xyz`) | 网盘 | HTTPS/JSON | ✅ | POST `api/search`,`merged_by_type` 按网盘类型聚合 |
| `pansou_cc` | 盘搜搜(`pansou.cc`) | 网盘 | HTML + Jsoup | ✅ | 列表需 `fetchDetail` 二次解析 |
| `panclub_quark` / `panclub_baidu` / `panclub_alipan` | 网盘俱乐部 `pan.club` | 网盘 | HTML + Jsoup | ✅ | 一个抽象基类 + 三个具体源(夸克 / 百度 / 阿里),按 `onclick` 抽链接 |
| `xiaoshuo` | 爱下电子书(`xcvts.cn`) | 直链 | HTTPS/JSON | ✅ | 连接 / 读超时 2s,故意在 `SOURCE_TIMEOUT_MS` 内自返 |
| `aiqu225` | aiqu225(GBK 编码) | 直链 | HTML + Jsoup | ✅ | 二段式:详情页 → `softdownfree.asp` → `downbook` 的 `.txt` 镜像 |
| `api52` | 52API(`52api.cn`) | 网盘 | HTTPS/JSON | ✅ | 自带默认 Key,quark+baidu 并发拉取 |
| `openlibrary` | Open Library | 直链 | HTTPS/JSON | ❌ | 运行时默认关 |
| `gutendex` | Gutendex(公版书) | 直链 | HTTPS/JSON | ❌ | 运行时默认关 |

其它内部模块:

- `BundledRecommendationSource`:`data/recommend/`,空输入态占位。

---

## 4. 关键架构决策

- **并发 + 超时收口**:`SearchService` 对每个启用源 `async(IO) + withTimeoutOrNull(2500ms)`,单源崩溃不会拖死搜索流程;返回 `SearchOutcome.Success(emptyList())` 而不是抛出去,让 UI 拿到的是"这个源没结果",不是"搜索失败"。
- **排序打分**:源码分(10–100)+ 网盘类型分(0–30,百度 > 夸克 > 阿里 > 迅雷 > 123 > 直链 > 其他)+ 标题命中 + 关键字多 Token 命中 + 时效衰减。关键字命中权重最高(最高 +200),所以"标题前缀匹配"的条目基本稳定占据前列。
- **去重 key**:`<netDiskType>|<title40 字(去非中英数字)>|<url 去 query>`,同源 / 跨源重复会合并成一条,只保留权重最高的源做主条目,提取码跨源取首个非空。
- **下载两套机制**:
  - **系统 DownloadManager**:`getExternalFilesDir(Download)/DaShenSou/<subDir>/` 私有目录,失败兜底公共 `Download/DaShenSou_<sub>_<file>`(DM 行为不可控时才走这条)。
  - **OkHttp + MediaStore**:`enqueueDirectDownload` 走 `MediaStore.Downloads.IS_PENDING` 两阶段写入,可稳定落 `Download/Book|Movie|TV|Other/` 而不依赖存储权限。
  - **文件名兜底**:优先 `SearchResult.fileType` 提示,其次 URL 路径里 `substringAfterLast('.')`,再不行就 `.download`,**绝不**从整段 URL 猜扩展名(避免 CDN 链接把 `type=txt` 误判成 `.txt`)。
- **WebView 中转**:只对 `pan.baidu.com` / `pan.quark.cn` / `aliyundrive.com` / `123pan.com` 等白名单域放行 `shouldOverrideUrlLoading`,命中后直接拉起对应 App;不命中就继续在 WebView 内展示。
- **打开网盘 App 的三段回退**:`openBySchemeWithPackage`(网盘自定义 scheme,如 `bdpan://`)→ `openByChooserWithPackage`(兜底 HTTP)→ `openByChooser`(最后系统选择器)。`NetDiskUtils` 维护包名映射(夸克用 `com.quark.browser`、迅雷用 `com.xunlei.downloadprovider` 等)。
- **Room 迁移**:`v1 → v2` 给 `download_records` 加了 `downloadId` 列,并 `fallbackToDestructiveMigration()` 作为开发期安全网;发版前需要替换成正式 Migration。

---

## 5. UI 与设计

- **配色**(摘 `colors.xml`):
  - 主色:`#7F5DFE` → `#5B21B6` 渐变;强调 `#FFE066` 霓虹黄
  - 辅助:`#06B6D4` 青、`#EC4899` 品红、`#22D3EE` 霓虹青
  - 背景:`#0F0F23` 深夜空、`#1A1A3C` 卡片底;半透明白 `glass=#33FFFFFF`
- **自绘资源**:`bg_glass_card`(玻璃卡)、`bg_neon_button`(霓虹边)、`progress_gradient`(渐变进度条)、`ic_lightning_crown`(品牌 Logo)、`ic_nav_*` 三个底部导航图标。
- **布局层级**:`activity_main.xml` 用 `FrameLayout` 装三页(搜索 / 历史 / 下载),搜索页 = 顶部渐变背景 + `Material TextInputLayout` 搜索框 + `TabLayout` 分类 + `SwipeRefreshLayout` 包 `RecyclerView`。
- **触屏**:按钮高度 ≥ 48dp,搜索框带发光边、分类 Tab 用选中渐变,列表卡片在 hover / 按下时有缩放。
- **国际化**:目前只接中文(`values/strings.xml` 全部中文);`netdisk_*` 一组枚举由 `NetDiskUtils.getNetDiskTypeName` 渲染,新增网盘时需要同时给两处补字符串。

---

## 6. 数据库

`AppDatabase`(Room v2,2 张表):

- `search_history(id PK, keyword, searchTime, searchCount)`:`SearchHistoryDao` 暴露 `Flow<List<SearchHistory>>`,MainActivity 直接 collect,按时间倒序展示。
- `download_records(id PK, title, url, filePath, fileSize, downloadSize, status, downloadTime, netDiskType, category, downloadId)`:
  - 状态:`PENDING / DOWNLOADING / PAUSED / COMPLETED / FAILED`
  - `downloadId` 是 `DownloadManager.enqueue(...)` 返回的 `Long`,用于查询进度 / 取消。
  - `filePath` 对直链下载是 `Download/<sub>/<name>`(相对路径),由 `FileOpener` 在 `MediaStore` 中反查 URI 再 `ACTION_VIEW` 打开。

---

## 7. 构建与运行

本机已经验证过的工具链(见 `.trae/rules/project_rules.md`):

- **JDK 17**:`C:\Program Files\Android\Android Studio\jbr`
- **Android SDK**:`C:\Users\Squema-Mini\AppData\Local\Android\Sdk`
- **Gradle 8.5**(系统级,不走 wrapper):`C:\Users\Squema-Mini\.gradle\wrapper\dists\gradle-8.5-bin\5t9huq95ubn472n8rpzujfbqh\gradle-8.5\bin\gradle.bat`

### Debug 构建并安装到设备

```powershell
$env:JAVA_HOME      = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME   = "C:\Users\Squema-Mini\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT = "C:\Users\Squema-Mini\AppData\Local\Android\Sdk"
& "C:\Users\Squema-Mini\.gradle\wrapper\dists\gradle-8.5-bin\5t9huq95ubn472n8rpzujfbqh\gradle-8.5\bin\gradle.bat" assembleDebug --no-daemon
```

产物:`app\build\outputs\apk\debug\app-debug.apk`(约 8.4MB,当前已有一份 2026-06-04 构建)。

```powershell
& "C:\Users\Squema-Mini\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices
& "C:\Users\Squema-Mini\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s <deviceId> install -r -t "E:\New\dashensou\app\build\outputs\apk\debug\app-debug.apk"
& "C:\Users\Squema-Mini\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s <deviceId> shell pm list packages com.dashensou.app
```

### Release 构建

`release` 块已开 R8 + 资源压缩,APK 体积会显著缩小,abiFilters 仍限定 `armeabi-v7a / arm64-v8a`。`proguard-rules.pro` 已为 Retrofit/OkHttp/Gson/Room 写了保留规则,**新增注解或反射点时记得同步补 keep 规则**。Release 出包需要自配签名。

### 常见问题

- 编译报错:参考 `android-build-install` skill 列举的 8 类常见修复(KAPT→KSP 切换、`jlink` 缺失、`styles.xml` 缺 `android:` 前缀等),不要靠重试绕过。
- `adb devices` 为空:确认 USB 调试已开且已授权。
- 设备掉线(`device 'xxx' not found`):重新跑 `adb devices` 取最新 id 再 `install`。
- 源代码没变但 APK 已存在:直接 `install -r`,不必全量 rebuild。

---

## 8. 权限与外部交互

`AndroidManifest.xml`:

- 网络:`INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE`。
- 存储:API ≤ 32 的 `READ/WRITE_EXTERNAL_STORAGE`,API 33+ 的 `READ_MEDIA_IMAGES/VIDEO/AUDIO`;`DownloadManager` 走系统 API 不需要额外权限。
- 通知:`DOWNLOAD_WITHOUT_NOTIFICATION` + `FOREGROUND_SERVICE`。
- 包名可见:`<queries>` 块声明 6 个网盘 App 包名,Android 11+ 才能 `Intent.ACTION_VIEW` 拉起它们。
- `android:usesCleartextTraffic="true"` + `network_security_config.xml` 信任系统 CA,允许 HTTP 源(部分网盘搜索站是明文 HTTP)。
- `FileProvider` 授权 `${applicationId}.fileprovider`,`file_paths.xml` 仅声明 `external-path`。

---

## 9. 已知限制 / 注意事项

1. **没有单测 / instrumentation 测试**:`app/src/test` 与 `app/src/androidTest` 都不存在,`testImplementation` 仅占位。改搜索源解析逻辑时建议补一些 HTML 快照 + JSON 的离线断言。
2. **网络源随时变脸**:`pansou.cc / pan.club / aiqu225` 都是非官方站,`onclick` 抽链接、`?pwd=` 提码等一旦改版,`PanClubSource` / `PansouCcSource` / `AiQuSource.fetchDetail` 三处要跟着调。
3. **Wanzhan API 需要 Key**:`WanzhanApiSource.apiKeys` 留空时走 `apiKey=null`,有调用频率限制;上线前最好注入自有 Key,见 `SearchService` 构造。
4. **直链下载分类目录**:`subDirFor` 写死 `Book / Movie / TV / Other`,没有按网盘类型再分;如果想"百度资源单独一个子文件夹",需要改 `subDirFor(category, netDiskType)`。
5. **Room 迁移策略**:`fallbackToDestructiveMigration()` 仅作为本地开发期安全网,生产构建前需要为每一次 schema bump 写正式 Migration。
6. **ProGuard 残留**:`-keep class com.example.chatbot.data.model.** { *; }` 这条是其他项目带过来的,与本项目无关,清掉。
7. **Git 工作区脏**:`master` 上有 20 个未提交修改 + 8 个新增文件(包括 `DirectDownloader` / `FileOpener` / `PanClubSource` / `Api52Source` / `PanSouSource` / `.trae/rules/` 等),发版前需要整理。

---

## 10. 后续可优化方向

- 抽象 `RecommendationSource` 已有,接一个远端热词 / 排行接口只需替换 `MainActivity.onCreate` 里的注入点。
- `WebViewActivity` 当前硬编码白名单 9 个网盘域名,新接一个网盘时改 `NET_DISK_DOMAINS` 数组 + `NetDiskUtils` 的 `getNetDiskType / getNetDiskPackageName / buildNetDiskIntentUrl` 三处映射。
- 引入 `WorkManager` 做"失败任务自动重试`,`DownloadManager.STATUS_FAILED` 时不需要再让用户手动进 App 看红条。
- `SearchService` 当前每源 2.5s 超时硬编码,可以根据网络环境(2G / Wi-Fi)分级,弱网下放更多耐心给 Wanzhan API 这类付费源。
- `OpenLibrarySource` / `GutendexSource` 已写完但默认关闭,适合给"全部"页做"公版书兜底",避免搜不到中文资源时页面整页空白。
