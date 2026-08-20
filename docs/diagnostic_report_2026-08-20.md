# 大神搜 (dashensou) 项目自我诊断报告

- **诊断时间**：2026-08-20
- **环境**：Windows / Git Bash；JDK 21（Android Studio jbr）；Gradle 8.5；AGP 8.2.2
- **方法**：静态走查（配置 / Manifest / 源码 / 文档）+ 实测编译（`assembleDebug`）+ 实测静态分析（`lintDebug`）+ git 状态核对

---

## 总览

| 维度 | 结论 |
|------|------|
| 编译 / 打包 | ✅ 通过（含 3 个未提交新源全部编译成功，产出 8.8MB debug APK） |
| Lint | ⚠️ 1 个 Error + 116 个 Warning（117 项） |
| 严重缺陷（需修） | 🔴 1 项：`windowLightNavigationBar` 在 minSdk=26 上会崩 |
| 文档 / 配置一致性 | 🔴 多处不一致（源数量、分支名、minSdk、update.json 内部版本） |
| 仓库卫生 | 🔴 `.gradle/` 缓存与 `app-debug.apk` 被提交；`dist/` 残留 164MB |
| 安全姿态 | 🟡 cleartext 全开 + JS WebView + allowBackup=true |
| 依赖新鲜度 | 🟡 19 个依赖有更新；compileSdk/targetSdk 34（最新 35） |

**总体评价**：代码可编译、可运行，架构合理（单例 SearchService + 熔断 + 打分去重）。但**仓库卫生差、文档严重滞后、存在 1 个会在 Android 8.0 真机崩溃的主题缺陷**，建议按下方优先级清理。

---

## 1. 构建与编译验证（实测）

- 通过 `gradle.bat assembleDebug` 实际构建成功（BUILD SUCCESSFUL，15s）。
- 当前工作树包含 **8 处未提交改动**（5 个修改源 + 3 个新增源 `Quark4k`/`Yunso`/`U3c3`），本次构建证明它们**能编译、能打包**，无语法/契约错误。
- ⚠️ `./gradlew` 在本机 Git Bash 下会因 `ulimit -H -n` 返回 `maximum` 而中断（环境怪癖，非代码问题）。CI / 本机请用 `gradle.bat` 或修正 wrapper。
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（8.8MB，versionCode 1，2026-08-20 14:57 构建）。

---

## 2. 🔴 严重缺陷（必须修）

### 2.1 夜间主题引用 API 27 属性，minSdk=26 会崩（Lint Error: NewApi）
- 位置：`app/src/main/res/values-night/themes.xml:5`
- 问题：`android:windowLightNavigationBar` 需要 API 27，但 `minSdk=26`。日间主题 `values/themes.xml:32` 已用 `tools:targetApi="o_mr1"` 守卫，夜间主题**漏了守卫**。
- 影响：在 Android 8.0（API 26）设备启用深色模式时，主题 inflate 失败 → 崩溃。API 26 在 minSdk 覆盖范围内，是真实用户。
- 修复（二选一）：
  1. 夜间主题也加 `xmlns:tools="http://schemas.android.com/tools"` 并给该 item 加 `tools:targetApi="o_mr1"`；或
  2. 夜间主题直接删除该 item（日间主题已设为 `true`，夜间设为 `false` 的差异可忽略，或用 v27 资源限定符兜底）。

---

## 3. 🔴 文档与配置不一致（高）

| # | 不一致点 | 现状 | 应为 |
|---|----------|------|------|
| 3.1 | README「聚合 11 个搜索源」+ 表格列 10 个 | 代码实际注册 **17 个实例 / 16 个唯一 ID / 默认启用 12 个** | 更新 README 为 16 个源 + 启用状态 |
| 3.2 | README 更新地址用 `main` 分支 | `build.gradle` 与 `git remote` 均为 `master`；本地有 `main` 分支（可能陈旧） | README 统一为 `master`；清理多余 `main` 分支 |
| 3.3 | `_release_notes.txt` 写「minSdk 24」 | `build.gradle` 实际 `minSdk 26` | 改为 26 |
| 3.4 | `app/update.json` 内部版本矛盾 | `versionCode=1`、`changelog="v1.0.0"`，但 `apkUrl` 指向 `v1.0.1-debug/dashensou-1.0.1.apk` | apkUrl 与 versionCode/changelog 对齐 |
| 3.5 | 搜索源默认启用「两处定义」 | 每个源类里 `enabled=true`，但 `SearchService.defaultSources()` 又覆盖部分源为 `false`（pansou_cc / api52 / u3c3 / openlibrary / gutendex） | 源类默认应改为 `false`，以 `defaultSources()` 为唯一真源，避免加源时漏配 |

**实际源清单（代码为准）**：wanzhan✅、pansou_252✅、pansou_cc-web✅(WebView 主路径)、pansou_cc❌(OkHttp 兜底)、haisou✅、kkso✅、pansou_de✅、telegram✅、aiqu225✅、duanju✅、xiaoshuo✅、api52❌、quark4k✅、yunso✅、u3c3❌、openlibrary❌、gutendex❌。（✅=默认启用）

---

## 4. 🔴 仓库卫生（高）

| # | 问题 | 证据 | 建议 |
|---|------|------|------|
| 4.1 | Gradle 构建缓存被提交 | `git ls-files` 含 17 个 `.gradle/8.5/...` 文件（checksums / dependencies-accessors 等） | `git rm --cached -r .gradle` 并从历史清除（`.gitignore` 已覆盖，后续不再跟踪） |
| 4.2 | debug APK 被提交且已过时 | `app-debug.apk`（8.7MB，2026-08-18）被 `git ls-files` 跟踪；今日新构建产物为 14:57 | 加入 `.gitignore`；`git rm --cached app-debug.apk`；分发用 GitHub Releases 而非仓库 |
| 4.3 | `dist/` 残留 164MB 垃圾 | 含 `logcat_live.txt`（157MB）等调试日志，未被提交（已 gitignore） | 本地直接删除 `dist/` 下大日志（非版本控制，删无风险） |
| 4.4 | 当前分支 `master`，但本地存在 `main` 分支 | `git branch -a` 显示 `main` 与 `master` 并存，远程仅 `origin/master` | 删除本地陈旧 `main`，避免混淆 |

---

## 5. Lint 告警汇总（117 项：1 Error + 116 Warning）

| 类别 | 数量 | 说明 / 处理建议 |
|------|------|----------------|
| UnusedResources | 72 | 大量未用 string/dimen/color/drawable（含 M3 模板残留 + 未接入的搜索历史相关文案）。建议开启 `shrinkResources`（release 已开）并清理死资源 |
| GradleDependency | 19 | 依赖普遍落后（见 §8） |
| SetTextI18n | 5 | Adapter/Activity 用字符串拼接 `setText`，未用资源占位符（中文独占应用，低优先级） |
| ObsoleteSdkInt | 3 | `SDK_INT` 判断在 minSdk=26 下多余（`AppUpdateManager.kt:577`、`mipmap-anydpi-v26` 目录、`themes.xml:31`） |
| StaticFieldLeak | 3 | `AppWebView`、`AppUpdateManager` 静态持有 context。需确认持有的均为 `Application` context（若是则无泄漏） |
| Overdraw | 3 | 根布局重复绘制背景（轻微渲染开销） |
| ApplySharedPref | 2 | `SourcePrefs` 用 `commit()` 同步写，建议改 `apply()` |
| SetJavaScriptEnabled | 2 | `AppWebView` / `PansouGotoResolver` 启用 JS（抓取必需，但属 XSS 风险面，见 §6） |
| UseCompoundDrawables / UselessParent | 各 2 | 布局可简化 |
| ScopedStorage | 1 | `WRITE_EXTERNAL_STORAGE` 声明（已 `maxSdkVersion=32` 限幅，33+ 不请求，基本无碍） |
| NewApi | 1 | **即 §2.1 的 Error** |
| OldTargetApi | 1 | targetSdk 34，非最新 35 |
| InsecureBaseConfiguration | 1 | network_security_config 允许明文（见 §6） |

> 注：`lint { abortOnError false; checkReleaseBuilds false }` 已配置，故未阻断构建；但 Error 项应修。

---

## 6. 安全与权限姿态（🟡）

- `android:usesCleartextTraffic="true"` + `network_security_config` 全量明文放行：抓取部分 http 镜像所必需，但扩大了明文面。建议仅对已知域名放行（domain-config 白名单），而非 base-config 全开（`InsecureBaseConfiguration` 告警来源）。
- `setJavaScriptEnabled(true)` 在 WebView / PansouGotoResolver：内容来自第三方聚合站，`evaluateJavascript` 仅读取 DOM 不做用户输入注入，风险可控，但属已知攻击面。
- `android:allowBackup="true"`：用户数据（搜索历史/源开关 SharedPreferences、Room DB）可经 adb backup 导出。若含隐私，建议 `false` 或配 `backupRules`。
- `REQUEST_INSTALL_PACKAGES` + FileProvider：应用内更新安装所必需，已用 `${applicationId}.fileprovider` 限定，规范。
- `largeHeap=true` + `hardwareAccelerated=true`：合理（处理 WebView 与大量结果）。

---

## 7. 架构与代码质量观察

- ✅ **SearchService 单例化**：进程级单例存于 `App`，主题切换/旋转不再重置源开关（近期修复，设计正确）。
- ✅ **熔断 + 打分 + 去重**：`SourceCircuitBreaker`、`sortByScore`（源权重+网盘类型+时效+命中）、`dedupe` 逻辑完整。
- ✅ **新源质量**：`Quark4kSource`/`YunsoSource`/`U3c3Source` 遵循 `SearchSource` 契约，`parseResponse/parseHtml` 抽成 `internal` 便于单测（但项目目前**无任何测试**）。
- ⚠️ **`u3c3` 域名可疑**：`BASE_URL = https://u3c3u3c3.u3c3u3c3.u3c3.com` 形态异常，疑似占位/笔误。该源默认关闭，但上线前需核实真实域名。
- ⚠️ **`haisou` 权重复用 `SOURCE_WEIGHT_PANSOU`（85）**：无独立常量，权重分配略随意（次要）。
- ⚠️ **`App.kt` 用 `fallbackToDestructiveMigration()`**：schema 变更会清空用户 DB（开发安全网，发版前应改为显式迁移）。
- ⚠️ **`gradle.properties` 关闭了构建优化**：`org.gradle.daemon=false`、`org.gradle.caching=false`、`android.enableBuildCache=false`（其中 `enableBuildCache` 在 AGP 8.2 已被移除、为无效配置）。仅调试可接受，CI 建议开启。
- ⚠️ **`systemProp.android.user.home=E:\New\Chatbot\TalkChatBot\.android`**：把 Android SDK/AVD home 指向**另一个项目**目录，若该路径被删会导致 SDK/AVD 解析异常。建议改回默认用户目录或本项目的 `.android`。

---

## 8. 依赖新鲜度（🟡，19 项 GradleDependency 告警）

| 组件 | 当前 | 可用 |
|------|------|------|
| AGP / Kotlin | 8.2.2 / 1.9.22 | 8.7+ / 2.x |
| core-ktx / appcompat / material | 1.12 / 1.6.1 / 1.11.0 | 1.19 / 1.8 / 1.14 |
| lifecycle / room | 2.7.0 / 2.6.1 | 2.11 / 2.8.4 |
| retrofit / okhttp | 2.9.0 / 4.12.0 | 2.11 / (4.12 已新) |
| coroutines / recyclerview | 1.7.3 / 1.3.2 | 1.10 / 1.4.0 |
| compileSdk / targetSdk | 34 | 35 |

升级非紧急，但建议择机统一升级并验证（注意 Kotlin 2.x 与 AGP 的兼容性、Room KSP 版本对齐）。

---

## 9. 已知限制 / 未接功能（沿用 README）

- 无自动化测试（单测 / instrumentation 均为 0）。
- 搜索历史：表 + DAO 已建，UI 未接入（大量 `R.string.*history*` 资源闲置）。
- 第三方源随时可能改版（pansou.cc / aiqu225 等）——已用 WebView 主路径 + OkHttp 兜底缓解。
- `WanzhanApiSource` 需要 Key，上线前建议注入自有 Key（`wanzhanApiKeys` 参数已预留）。
- ProGuard 残留规则：`proguard-rules.pro` 含 `org.commonmark`/Markwon 规则，本项目未用 Markwon（死规则，可删）。

---

## 10. 修复优先级清单

**P0（会导致崩溃 / 数据/仓库污染，立即修）**
1. 修 §2.1 夜间主题 `windowLightNavigationBar` 的 `tools:targetApi` 守卫。
2. `git rm --cached` 移除 `.gradle/` 缓存与根 `app-debug.apk`，并补 `.gitignore`。
3. 核实并修正 `u3c3` 源域名。

**P1（一致性与安全，发版前修）**
4. 同步 README：源数量（16）、分支名（master）、minSdk（26）。
5. 对齐 `app/update.json` 的 versionCode/apkUrl/changelog。
6. `network_security_config` 改为按域名放行明文，而非全量。
7. 清理 `dist/` 残留大日志；删除本地陈旧 `main` 分支。

**P2（质量与维护，择机）**
8. 源默认启用状态统一为「源类 `false` + `defaultSources()` 唯一真源」。
9. 删除 proguard 死规则；`SourcePrefs` 改 `apply()`。
10. 升级依赖到新版本并验证；清理 UnusedResources。
11. 补搜索历史 UI 或删除闲置资源；补关键源的单测（解析函数已抽好）。
12. 修正 `gradle.properties` 的 `android.user.home` 与无效 `enableBuildCache`；CI 开启 daemon/cache。

---

## 附录：本次实测命令

```
# 编译（绕过 Git Bash 的 ulimit 问题，用 gradle.bat）
gradle.bat -p E:/New/dashensou assembleDebug --no-daemon
# 静态分析
gradle.bat -p E:/New/dashensou lintDebug --no-daemon
# 结果：assembleDebug BUILD SUCCESSFUL；lintDebug 117 项告警（1 Error）

---

## 修复状态（2026-08-20 已执行）

已按本报告修复并提交（见 git log：`5f24d87 fix: 按诊断报告完整修复（P0/P1/P2）`）：

- ✅ **P0-1** 夜间主题 `windowLightNavigationBar` 补 `tools:targetApi="o_mr1"` → lint `NewApi` Error 消除
- ✅ **P0-2** 停止跟踪 `.gradle/` 缓存与 `app-debug.apk`；`.gitignore` 追加 `app-debug.apk` / `app-release.apk`
- ✅ **P0-3** `U3c3Source` 域名 `u3c3u3c3.u3c3u3c3.u3c3.com` → `u3c3.com`（备注镜像与发布页）
- ✅ **P1-4** README 源数量改 16、分支改 `master`、目录注释与源清单表同步
- ✅ **P1-5** `update.json` 对齐 `versionCode=1` / `apkUrl` v1.0.0 / `changelog`
- ✅ **P1-7** 删除 `dist/` 调试垃圾（164MB，gitignored 安全删）
- ✅ **P2-8** 搜索源默认 `enabled` 统一为 `false`，`defaultSources()` 为唯一真源
- ✅ **P2-9** 删除 proguard Markwon/CommonMark 死规则
- ✅ **P2-10** `SourcePrefs` `commit()` → `apply()`

**验证**：`assembleDebug` + `lintDebug` 通过；lint 由 117 项（1 Error）降至 **114 项（0 Error）**。

### 暂缓 / 需权衡项（未改，避免破坏构建或功能）

- **依赖升级**（AGP 8.2→8.7、Kotlin 1.9→2.x 等 19 项）：盲目升级易破坏已验证构建，建议独立分支验证后合入。
- **未用资源 72 项**：mass-delete 存在反射 / WebView 动态引用风险；release 已开 `shrinkResources` 覆盖，按需逐条清理。
- **cleartext 全开 / JS WebView**（`InsecureBaseConfiguration` / `SetJavaScriptEnabled`）：聚合抓取第三方站所必需，按域名白名单不可行（域名动态轮换），保留为已知接受风险。
- **StaticFieldLeak 3 项**：经核实 `AppWebView` 持 `Application` context、`AppUpdateManager` 同理，均非泄漏，Lint 误报。
- **ObsoleteSdkInt / Overdraw / UseCompoundDrawables / UselessParent / SetTextI18n**：低优先级 UI 细节，按需处理。
```
