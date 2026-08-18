# 11 个搜索源 — Scrapling 适用性评估

> 范围：`app/.../service/source/*.kt`（11 个 `SearchSource` 实现）
> 评估目标：哪些能用 Scrapling 替代 / 替代价值 / 兼容性问题

## 总览

| # | 源 ID | 类型 | HTTP 库 | 解析库 | 编码 | 反爬 | Scrapling 适用 | 推荐 fetcher |
|---|---|---|---|---|---|---|---|---|
| 1 | `wanzhan` | JSON GET | OkHttp | JSONObject | utf-8 | 多 Key + 限流 | ✅ 纯结构化 | `Fetcher` |
| 2 | `pansou_252` | JSON POST | OkHttp | JSONObject | utf-8 | 无 | ✅ 纯结构化 | `Fetcher` |
| 3 | `api52` | JSON GET | OkHttp | JSONObject | utf-8 | 需 Key + 限流 | ✅ 纯结构化 | `Fetcher` |
| 4 | `duanju` | JSON 双 GET 并发 | OkHttp | JSONObject | utf-8 | 无 | ✅ 纯结构化 | `Fetcher` |
| 5 | `xiaoshuo` | JSON GET | OkHttp | JSONObject | utf-8 | 无 | ✅ 纯结构化 | `Fetcher` |
| 6 | `gutendex` | JSON GET | OkHttp | JSONObject | utf-8 | 无 | ✅ 纯结构化 | `Fetcher` |
| 7 | `openlibrary` | JSON GET | OkHttp | JSONObject | utf-8 | 无 | ✅ 纯结构化 | `Fetcher` |
| 8 | `pansou_cc` | HTML | OkHttp | Jsoup | utf-8 | **Cloudflare** | ✅ | **`StealthyFetcher`**（CF 解锁） |
| 9 | `aiqu225` | HTML + **二跳详情** | OkHttp | Jsoup | **GBK** | 慢 CDN | ✅ | `Fetcher`（GBK 手动解码） |
| 10 | `haisou` | HTML (Vue/Nuxt SPA) | OkHttp | Jsoup | utf-8 | **JS 渲染** | ✅ | **`DynamicFetcher`**（或 `Fetcher` + 退化） |
| 11 | `duanju` | 同上 #4 | | | | | | |

11 个里 **6 个是纯 JSON API**（Fetcher 即可），**3 个是 HTML 解析**（其中 1 个 GBK、1 个 CF 拦截、1 个 SPA 渲染），**2 个含二跳详情**（`pansou_cc` 拿 /goto 链接、`aiqu225` 拿 .txt 镜像）。

## 适用度分类

### A. JSON 纯 API（7 个）— 适用，但 Scrapling 不带来显著收益

**wanzhan / pansou_252 / api52 / duanju / xiaoshuo / gutendex / openlibrary**

- 现状：OkHttp + `org.json.JSONObject` + 手写 `for i in 0 until len(arr)` 解析。
- Scrapling 替换：`Fetcher.get(url, impersonate=...)` + `json.loads(body)`。
- **替换价值**：低。Scrapling 的「自适应解析」对 JSON 没用，TLS 指纹 + CF 解锁对这些直连 API 也没用。
- **仍然推荐替换的场景**：
  1. PC 端独立抓取进程（`E:/New/dashensou/scraper/`）需要这些源时——已经支持 `wanzhan`，剩下的（`pansou_252` / `api52` / `duanju` / `xiaoshuo` / `gutendex` / `openlibrary`）只要复制 `wanzhan.py` 改 5 行 JSON 字段名即可。
  2. APK 端如果将来 OkHttp 抽离到 `HttpClient` 共用，那就在 PC 端统一走 Scrapling，避免在 Python 侧再实现一遍重试 / Key 健康 / 限流。

### B. HTML 解析（3 个）— 适用，Scrapling 价值明显

#### `pansou_cc` (185 行) — Cloudflare
- 现状：OkHttp + Jsoup.select(`"div.resource-item-wrap"`, `"h3.resource-title a"`, `".resource-meta .em"`, `".other-info .time"`)。详情页再发一次（`/goto/` 链接 + `#pwd` 提取码）。
- Scrapling 替换：
  - 列表：`StealthyFetcher.fetch(url, solve_cloudflare=True)` + `page.css("div.resource-item-wrap")`
  - 详情：`StealthyFetcher.fetch(detail_url)` + `page.css("a.button[href^=/goto/]").first`
- **替换收益**：
  - TLS 指纹被 Cloudflare 拦截是已知痛点（Kotlin 端目前靠 `Mozilla/5.0` UA 顶一顶，CF 升级后会失效）。
  - `StealthyFetcher` 用 `curl_cffi` 模拟 Chrome JA3，**直接解决 CF 拦截**。
- **兼容性注意**：
  - `Jsoup.selectFirst("a[href^=/goto/]")` → `page.css("a[href^=/goto/]").first`，API 形状变了。
  - 提取码在 `#pwd` 元素中，CSS 选择器完全一致，**零修改**。
  - 详情页里有 `/goto/` 重定向链，Scrapling 的 `page.url` 拿到的可能是重定向后 URL。`extractPassword` 内部那段 regex（`提取密码`）可以原样保留。
  - 替换后**单源 timeout 预算可从 4.5s 降到 2s**（不再被 CF 拖慢）。

#### `aiqu225` (245 行) — GBK + 二跳
- 现状：OkHttp + `charset="GBK"` + Jsoup。详情页是二跳：`/txt-xx/softdownfree.asp` → `txt*.downbook*.com/*.txt`。
- Scrapling 替换：
  - 列表：`Fetcher.get(url, impersonate=...)` + `body.decode("gbk", errors="replace")` + `Adaptor(content=...)` + `page.css("div.search-card")`
  - 详情第 1 跳：`Fetcher.get(softdown_url)` + 找"第N下载地址" anchor
  - 详情第 2 跳：直接 `Fetcher.get(txt_mirror_url)` 拿 `Content-Length`（验证大小）
- **替换收益**：
  - GBK 解码逻辑（`HttpClient.getString(url, charset=CHARSET)`）Scrapling 不直接做，需要在 storage 层 / 包装层手动 `bytes.decode("gbk", errors="replace")`。
  - **多步跳转能保持 Session**：Scrapling 的 `Fetcher` 默认跟随 redirect，且能用 `page.url` 拿到最终 URL。
- **兼容性注意**：
  - `.absUrl("href")` (Jsoup) → 自己在 Python 里写 `if href.startswith("http") else BASE_URL + href`（Kotlin 端已经是这样实现的，保持不变）。
  - `Jsoup.parseBodyFragment(it).body().text()`（Kotlin 端在第 143 行、196 行用）—— 用来把带 HTML 标记的 title 干净化。Scrapling 的 `el.text` 直接给纯文本，**这一步可省略**。
  - 二跳页面的 mirror URL **是 .txt 文件**，不要用 `Adaptor` 解析（`selector()` 会拒绝非 HTML 文本并报 `ValueError`）。直接当 string 处理就好。

#### `haisou` (263 行) — Vue/Nuxt SPA
- 现状：Kotlin 端**已经发现它是 SPA**，HTML 里搜不到结果卡，会 fallback 返回空列表（`page loaded but no results in HTML; results may require JS rendering`）。
- Scrapling 替换：**`DynamicFetcher.fetch(url, headless=True, network_idle=True)`** —— Playwright 渲染，JS 跑完后再取 DOM。
- **替换收益**：
  - 直接从"基本搜不到"变成"能搜到"，**这是 11 个源里替换价值最大的一个**。
  - `network_idle=True` 等待 Vue mount + 内部 API 完成后才开始解析，避免拿到空 DOM。
- **兼容性注意**：
  - **Playwright 拖 Chromium**，Android APK 端不能直接用 —— **这是 Scrapling 不能进 APK 的另一个原因**。
  - 在 PC 端独立抓取进程里跑 OK，但首次部署要 `scrapling install` 下载浏览器（~100MB）。
  - 解析路径可复用（同一份 CSS 选择器），但**不能在手机没运行时实时搜**——它是离线补抓。
  - 内存占用大，并发度建议 ≤ 2（每个 Chromium 实例 ~300MB）。

### C. 不属于搜索源但相关的细节抓取

| 路径 | 用途 | 替换方案 |
|---|---|---|
| `PansouCcSource.fetchDetail` | 列表点了之后去详情页拿 /goto/ + #pwd | 上面 B 类已包含 |
| `AiQuSource.fetchDetail` | 列表点了之后拿真正的 .txt 直链 | 上面 B 类已包含 |

## 兼容性问题（重点关注）

### 1. 协程 / 异步模型
- Kotlin：`suspend fun search()` + `withContext(Dispatchers.IO)` + `coroutineScope { async ... await() }`。
- Python：`asyncio` + `aiohttp` 风格。Scrapling 三个 Fetcher **都是同步函数**（基于 `curl_cffi` / `playwright`），不返回 awaitable。
- 替换策略：
  - 单源内部用 `loop = asyncio.get_event_loop(); result = await loop.run_in_executor(None, http.get, ...)`
  - 或者干脆**串行**（11 个源 6-8s 内能跑完，不并发也行）。

### 2. 数据模型字段名
- Kotlin `SearchResult` 字段是 camelCase（`netDiskType`, `sourceName`, `sourceId`, `isValid`, `requiresWebView`, `extractionCode`）。
- Python `pydantic` 模型**已经对齐**这些字段名（见 `scraper/src/dashensou_scraper/models.py`），将来用 FastAPI 包装后可以直接喂回 APK 端。

### 3. 鉴权 / Key 池
- `wanzhan` 和 `api52` 都有多 Key 轮换 + 健康检查（Kotlin 端有 `KeyHealth` 状态机 + 指数退避 + 全局最小间隔 4s）。
- Scrapling 不管这块，**逻辑在 source 的 `parse()` 之前自己做**（或挪到上层 `run.py` 的调度器）。
- **风险**：从 OkHttp 切到 Scrapling `Fetcher`，Key 池的并发安全（`synchronized(healthLock)`）在 Python 端需要换成 `asyncio.Lock` 或 `threading.Lock`。

### 4. 错误码语义
- Kotlin 区分 5 种 `FailureKind`（NETWORK / TIMEOUT / SOURCE_DOWN / PARSE / EMPTY）—— UI 上对应不同提示文案。
- Scrapling 的 `FetchError` 只有一个字符串 message，需要在 `run.py` 的 `_run_one` 里**按 status_code / 异常类型映射回 5 种 kind**。
- `scraper/.../models.py` 已经有 `FailureKind` 枚举，`storage.py` 写盘时落 `error.json`，但**目前没接回 Kotlin 的 `SearchOutcome.Failure` 语义**——如果将来 APK 调 Python 服务的 API，需要在 FastAPI 层做映射。

### 5. Android 不兼容点
- `Playwright` / `curl_cffi` 都**不能在 Android 上跑**——Scrapling 不能塞进 APK。
- **正确架构**（已经在 `E:/New/dashensou/scraper/` 落地）：
  - APK 端：保留 OkHttp + Jsoup，作为**手机端实时抓取**。
  - PC 端：Scrapling 独立进程，作为**离线补抓 + 数据备份 + 调参**。
  - 两者通过**同一份 JSON schema** 互通（`pydantic` 模型与 Kotlin `SearchResult` 字段一一对应）。

### 6. 选择器 API 差异（最容易踩的坑）

| 操作 | Jsoup (Kotlin) | Scrapling (Python) |
|---|---|---|
| 取所有匹配 | `doc.select("div.x")` | `page.css("div.x")` |
| 取第一个 | `doc.selectFirst("div.x")` | `page.css("div.x").first` |
| 文本 | `el.text()` | `el.text`（属性，无括号） |
| 属性 | `el.attr("href")` | `el.attrib.get("href", "")` |
| 子元素 | `el.select("a")` | `el.css("a")` |
| 多个匹配遍历 | `for el in doc.select(...)` | `for el in page.css(...)`（同 `Selectors` 可迭代） |
| `abs:href` 解析 | `el.attr("abs:href")` | **没有**，要手动拼接（Kotlin 端多数情况下也是手拼的，影响小） |

### 7. SPA 站点的反爬
- `haisou` 的"Vue/Nuxt SPA"是表象，深层可能还有：① fingerprint 检测、② headless 特征检测、③ 协议层 TLS 校验。
- `DynamicFetcher` 用 Playwright 默认 stealth 配置，**对多数国产 SPA 够用**；若仍被拦，可加上 `playwright-stealth`（Scrapling 0.4 内置 `real_chrome` 选项）。

## 推荐落地节奏

1. **先做 PC 端补抓**（已在做，✅ `E:/New/dashensou/scraper/` 落地）：
   - 补全剩下 5 个 JSON 源（`pansou_252` / `api52` / `duanju` / `xiaoshuo` / `openlibrary`）—— 复制 `wanzhan.py` 改 5 行字段名即可，**30 分钟/个**。
   - 补 `aiqu225`（GBK）—— 已有模板，10 分钟。
   - 补 `haisou`（SPA）—— 用 `DynamicFetcher` 模板，**重点验证 selector 在 JS 渲染后能选到**。
   - 补 `pansou_cc`（CF）—— 用 `StealthyFetcher` + `solve_cloudflare=True`，**首次跑要装浏览器内核**。
2. **APK 端保持现状**。Kotlin 的 OkHttp + Jsoup 链已经稳定，**没充分理由不要换**。
3. **如果将来要做"云端搜索服务"**（手机端 + PC 端共用同一抓取层）：
   - 在 `scraper/` 基础上加 FastAPI 暴露 `/search?q=...`。
   - 字段直接用现有 pydantic 模型，**APK 解析响应时的字段名都不变**。

## 已落地 vs 待做

| 源 | Python 端已写？ | Fetcher 选型 | 状态 |
|---|---|---|---|
| wanzhan | ✅ `wanzhan.py` | `Fetcher` | 已端到端验证 |
| pansou_cc | ✅ `pansou_cc.py` | `StealthyFetcher` | 模板就绪，需 `scrapling install` |
| aiqu225 | ✅ `aiqu225.py` | `Fetcher`（GBK） | 模板就绪 |
| pansou_252 | ❌ | `Fetcher` | 待补 |
| api52 | ❌ | `Fetcher` | 待补 |
| duanju | ❌ | `Fetcher`（双端点并发） | 待补 |
| xiaoshuo | ❌ | `Fetcher` | 待补 |
| gutendex | ❌ | `Fetcher` | 待补 |
| openlibrary | ❌ | `Fetcher` | 待补 |
| haisou | ❌ | `DynamicFetcher` | 待补（SPA 验证） |
