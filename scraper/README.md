# dashensou Scraper

把 `app/.../source/*.kt` 里的搜索源拆出一个**独立 Python 抓取进程**，供 PC 端：
- 批量补抓（一次性拉大量关键词，做离线索引）
- 镜像/备份（万一某个源挂了，本地有原始响应可以二次解析）
- 本地研究（看哪个源稳定、哪个反爬最严）

**只用了 Scrapling 的「过程」**：三个 Fetcher + Adaptor 解析器。不引入 Spider、Feed Export、中间件。  
**数据每次单独存储**：每次 run 都新建一个目录，原始响应 + 解析结果双写。

---

## 安装

需要 Python 3.10+。

### 一键脚本
```bash
# Windows
install.bat

# Linux / WSL
./install.sh
```

### 手动
```bash
cd E:/New/dashensou/scraper
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements.txt
.venv/Scripts/python -m pip install -e .
.venv/Scripts/scrapling install   # 首次使用 fetcher 前必须装浏览器依赖
```

## 用法

```bash
# 全源（仅启用项）
.venv/Scripts/python -m dashensou_scraper "三国演义"

# 指定源
.venv/Scripts/python -m dashensou_scraper "三国演义" --only pansou_cc,aiqu225

# 列表 + 二跳详情（pansou_cc / aiqu225 支持）
.venv/Scripts/python -m dashensou_scraper "三国演义" --only aiqu225 --detail --max-detail 3

# 指定页码 / 类别 / 输出目录
.venv/Scripts/python -m dashensou_scraper "三国演义" --page 1 --category ebook --runs-dir ./runs
```

## 当前已实现的源（4 个）

| 源 ID | 类型 | Fetcher | charset | 反爬 | Detail 二跳 | 状态 |
|---|---|---|---|---|---|---|
| `wanzhan` | JSON | `Fetcher` | utf-8 | — | ❌ | ✅ 模板就绪 |
| `pansou_cc` | HTML | `StealthyFetcher` | utf-8 | Cloudflare | ✅ 详情页 → `/goto/` + `#pwd` | ✅ 真站已验（16 条） |
| `aiqu225` | HTML | `Fetcher` | **GBK** | 慢 CDN | ✅ 详情页 → softdown → .txt 直链 | ✅ 真站已验（4 条 + 2 直链） |
| `haisou` | HTML (SPA) | `DynamicFetcher` | utf-8 | JS 渲染 | ❌ | ⚠️ 模板就绪（真站当前返回首页） |

> 其余 7 个 JSON 源（`pansou_252` / `api52` / `duanju` / `xiaoshuo` / `gutendex` / `openlibrary` / `haisou` 列表）
> 复制 `wanzhan.py` 改 5 行字段名即可，**每个 10-30 分钟**。

## 落盘结构

每次调用在 `runs/` 下创建一个新目录：

```
runs/
  2026-07-09T10-00-19_三国演义/
    manifest.json                       # run 元信息 + 各源汇总
    aiqu225/
      request.json                      # URL / headers / charset
      raw.bin                           # 原始响应（HTML/GBK）
      raw.meta.json                     # status_code / size / fetcher / ts
      results.json                      # 解析结果（SearchResult 列表）
      details.json                      # --detail 时存在，每条详情一行
      error.json                        # 失败时存在
    pansou_cc/
      ...
```

**每次 run 一个目录，不覆盖、不跨 run 合并。**

## 测试用的真实 run（已附在 zip 里）

- `runs/2026-07-09T10-00-19_三国演义_aiqu225/` — aiqu225 列表 4 条 + 2 条详情直链
- `runs/2026-07-09T10-00-43_三国演义_pansou_cc/` — pansou.cc 列表 16 条 + 1 条详情（提取码已解出）

## Scrapling 在这场景下的卖点

1. **反爬分层**：先 `Fetcher`，被拦了就升级 `StealthyFetcher`，再不行上 `DynamicFetcher`。
2. **Chrome 内核自动化**：`DynamicFetcher` 跑 Playwright，JS 渲染后的 DOM 拿来直接解析。
3. **零存储耦合**：本项目不依赖 Scrapling 自带的 Feed Export / Checkpoint，所以每次 run 独立一份。

## 已知边界

- Scrapling 拖 Chromium，首次 install 会下载约 100 MB 浏览器。
- `DynamicFetcher.fetch` 的 `timeout` 是**毫秒**，本项目 `FetchPlan.timeout_s`（秒）已在 `http.py` 内部自动换算。
- Playwright Sync API 不能跑在 asyncio 循环里；本项目用 `asyncio.to_thread` / `loop.run_in_executor + functools.partial` 把阻塞调用挪到线程池。
- Scrapling 内部 cssselect **要求属性值带引号**（`[href*="softdownfree.asp"]`），裸值 `[href*=/softdownfree.asp/]` 会抛 `SelectorSyntaxError`。
- 解析字段与 Kotlin 版本**字段名一致**，便于将来做 APK↔Python 数据互通（FastAPI 可直接回吐 APK 用同一 JSON 结构）。

## 复现命令

```bash
.venv/Scripts/python -m dashensou_scraper "三国演义" --only aiqu225 --detail --max-detail 2
.venv/Scripts/python -m dashensou_scraper "三国演义" --only pansou_cc --detail --max-detail 1
.venv/Scripts/python -m dashensou_scraper "三国演义" --only haisou
.venv/Scripts/python -m dashensou_scraper "三国演义" --only wanzhan
```
