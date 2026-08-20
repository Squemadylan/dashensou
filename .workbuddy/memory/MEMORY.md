# 项目记忆 — 大神搜 (dashensou)

## 数据源扩展（2026-08-20）
- 新增 `Quark4kSource`：夸克论坛 Flarum API 搜索，解析帖子内容中的夸克网盘链接+提取码
- 新增 `YunsoSource`：云搜聚合 POST API，解析 HTML 中 `<a url="..." pa="密码">` 标签
- 新增 `U3c3Source`：u3c3 磁力搜索，解析 torrent-list 表格（默认禁用，用户按需开启）
- `UrlKinds.unescapeJsonUrl()`：还原 JSON 转义 URL（`\/` → `/`），已应用到 PanSouSource 和 WanzhanApiSource
- PanHub.shenzjd.com 的缓存 TTL 与大神搜 LinkChecker 完全一致（OK 24h / BAD 6h / LOCKED 12h / UNCERTAIN 30m）

## PanHub .ts → Android .kt 移植坑（2026-08-20）
移植 TypeScript 插件为 SearchSource 时踩过的坑：
1. **`const val` 不能用对象类型** — `MediaType`、`Regex` 等非 primitive 必须用 `val`
2. **Kotlin regex 没有 `.matcher()` 方法** — 用 `RE.findAll(s).forEach { match -> ... }`
3. **`forEach` lambda 内不能用 `break/continue`** — 用 `return@forEach`
4. **Jsoup `Elements.filter` lambda 有两个参数** — 签名 `(el, index) ->`
5. **`optString(key)` 无默认值时返回 `String?`** — 需要 `?: ""` 兜底

## SearchSource 注册模板
每个新源三处修改：
1. `SearchService.kt` 加 import + `defaultSources()` 注册 + `sourceWeight()` case
2. 权重参考：已知源 80-100，新源 50 左右
3. URL 从 JSON 取时调 `UrlKinds.unescapeJsonUrl()`

## 开发风格
- 能用就行的实用主义，不做无谓扩展
- 执行层面一次性修复所有问题，不逐个确认
- 简洁直接，编号列表/表格优先
