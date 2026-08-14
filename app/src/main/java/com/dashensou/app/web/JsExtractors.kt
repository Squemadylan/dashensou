package com.dashensou.app.web


/**
 * JS extraction snippets evaluated inside the shared WebView.
 *
 * Contract: each snippet is an IIFE that returns the value DIRECTLY
 * (an Array or Object — NOT a `JSON.stringify(...)` string).
 *
 * Why: Android's `WebView.evaluateJavascript` JSON-encodes whatever the
 * script returns. If the script returns `JSON.stringify(items)` (a string),
 * WebView wraps it in quotes → Kotlin receives `"[...]"` and `JSONArray()`
 * throws. Returning the Array/Object directly lets WebView encode it as
 * `[...]` / `{...}`, which `JSONArray` / `JSONObject` parse cleanly.
 * `null`/`undefined` → Kotlin receives `null` (handled as a failure).
 *
 * Why JS-side extraction rather than re-parsing `WebView.getContentDescription()`:
 *   - DOM `textContent` is already decoded (GBK pages come back as proper
 *     CJK strings, no charset handling needed in Kotlin).
 *   - CSS selectors are shorter and more familiar than the equivalent Jsoup
 *     expressions, and translate directly from the original Kotlin selectors.
 */
object JsExtractors {

    /**
     * pansou.cc search result list.
     * Mirrors `PansouCcSource`'s `div.resource-item-wrap` chain.
     */
    fun pansouList(): String = """
        (function(){
            var items = [];
            var nodes = document.querySelectorAll('div.resource-item-wrap');
            for (var i = 0; i < nodes.length; i++) {
                var el = nodes[i];
                var a = el.querySelector('h3.resource-title a');
                if (!a) continue;
                var title = (a.textContent || '').trim();
                var href = a.getAttribute('href') || '';
                if (!title || !href) continue;
                items.push({
                    title: title,
                    href: href,
                    size: ((el.querySelector('.resource-meta .em') || {}).textContent || '').trim(),
                    date: ((el.querySelector('.other-info .time') || {}).textContent || '').trim()
                });
            }
            return items;
        })();
    """.trimIndent()

    /**
     * pansou.cc detail page — extract `/goto/` URL and `#pwd` extraction code.
     */
    fun pansouDetail(): String = """
        (function(){
            var a = document.querySelector('a.button[href^="/goto/"]')
                  || document.querySelector('a[href^="/goto/"]');
            var pwdEl = document.querySelector('.resource-meta #pwd')
                     || document.querySelector('#pwd')
                     || document.querySelector('.copy-item #pwd');
            var pwd = (pwdEl && pwdEl.textContent || '').trim();
            return {
                goto: a ? (a.getAttribute('href') || '') : '',
                password: pwd
            };
        })();
    """.trimIndent()

}