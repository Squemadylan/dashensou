package com.dashensou.app.ui



import android.content.ClipData

import android.content.ClipboardManager

import android.content.Context

import android.content.Intent

import android.content.res.Configuration

import android.net.Uri

import android.os.Bundle

import android.util.Log

import android.view.KeyEvent

import android.view.View

import android.view.inputmethod.EditorInfo

import android.widget.CompoundButton

import android.widget.LinearLayout
import android.widget.ProgressBar

import android.widget.TextView

import android.widget.Toast

import androidx.activity.viewModels

import androidx.appcompat.app.AlertDialog

import androidx.appcompat.app.AppCompatActivity

import androidx.appcompat.app.AppCompatDelegate

import androidx.core.content.ContextCompat

import androidx.core.view.WindowCompat

import androidx.lifecycle.Lifecycle

import androidx.lifecycle.lifecycleScope

import androidx.lifecycle.repeatOnLifecycle

import androidx.recyclerview.widget.LinearLayoutManager

import com.dashensou.app.R

import com.dashensou.app.data.model.DownloadRecord

import com.dashensou.app.data.model.NetDiskType

import com.dashensou.app.data.model.ResourceCategory

import com.dashensou.app.data.model.SearchResult

import com.dashensou.app.databinding.ActivityMainBinding

import com.dashensou.app.service.DownloadManager

import com.dashensou.app.service.source.AiQuSource
import com.dashensou.app.service.source.HaiSouSource

import com.dashensou.app.service.source.FailureKind

import com.dashensou.app.service.source.PansouCcSource

import com.dashensou.app.service.source.SearchOutcome

import com.dashensou.app.ui.download.DownloadViewModel

import com.dashensou.app.ui.search.SearchViewModel

import com.dashensou.app.util.DiskLabels

import com.dashensou.app.util.PansouGotoResolver
import com.dashensou.app.util.SourceHealthChecker

import com.dashensou.app.util.AppUpdateManager

import com.dashensou.app.util.SourcePrefs

import com.dashensou.app.util.UrlKinds
import com.dashensou.app.util.BanManager
import com.dashensou.app.util.ReportManager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull



/**

 * P1#7: thin shell. The previous version of this class was 600+ lines

 * doing tab switching, dialog rendering, search coroutine orchestration,

 * progress polling, file cleanup, and every download row's action

 * handler in one place. The bulk of that logic now lives in:

 *

 *   - [SearchViewModel]   (search-input state, results, category)

 *   - [DownloadViewModel] (downloads list, row actions, file cleanup)

 *

 * What stays here: view setup, click wiring, Intent dispatch (WebView,

 * chooser, system downloads, QQ group join), and the per-kind failure

 * dialog (which needs an Activity to show).

 */

class MainActivity : AppCompatActivity() {



    private lateinit var binding: ActivityMainBinding

    private lateinit var banManager: BanManager
    private lateinit var reportManager: ReportManager

    private lateinit var pansouSource: PansouCcSource

    private lateinit var aiquSource: AiQuSource
    private lateinit var haisouSource: HaiSouSource

    private lateinit var searchAdapter: SearchResultAdapter

    private lateinit var downloadAdapter: DownloadRecordAdapter



    private val searchViewModel: SearchViewModel by viewModels()

    private val downloadViewModel: DownloadViewModel by viewModels {

        DownloadViewModelFactory(application, DownloadManager)

    }



    private var currentTab = TAB_SEARCH

    private var lastFailureShown: SearchOutcome.Failure? = null

    // Currently running "fetch share URL → open net-disk app" coroutine.

    // Replaced (and the previous one cancelled) every time the user

    // taps a download button. Auto-cancelled in onDestroy so a

    // configuration change or back press doesn't leave a coroutine

    // bound to a stale activity writing to a deleted view binding.

    private var healthChecker: SourceHealthChecker? = null

    private val healthLatencyMap = mutableMapOf<String, SourceHealthChecker.Result>()

    private var inFlightDownloadJob: Job? = null

    private var startupUpdateCheckDone = false

    private var appliedNightMask = Configuration.UI_MODE_NIGHT_UNDEFINED

    private var suppressThemeSwitcher = false

    private var suppressCategoryTabCallback = false



    private data class ThemeUiSnapshot(

        val currentTab: Int,

        val bottomNavId: Int,

        val searchKeyword: String,

        val categoryTabIndex: Int

    )



    companion object {

        private const val TAG = "MainActivity"

        /** adb / deep-link query: `--es q 关键词` */
        const val EXTRA_QUERY = "q"

        private const val TAB_SEARCH = 0

        private const val TAB_DOWNLOADS = 1

        private const val TAB_MINE = 2



        // Theme switcher (Mine page 3-segment toggle). Persisted in

        // a dedicated SharedPreferences file so the choice survives

        // cold restarts. Value is one of the AppCompatDelegate.MODE_NIGHT_*

        // constants.

        private const val THEME_PREFS_NAME = "theme_prefs"

        private const val THEME_PREFS_KEY = "night_mode"



        // P0#mine: QQ group join deep link. We use QQ 8.x+'s public

        // `mqqapi://card/show_pslcard?card_type=group&...` scheme

        // which lands directly in the group profile card with a

        // one-tap "加群" button. The earlier `mqqopensdkapi://...`

        // deep link (PC-era) and the `https://qm.qq.com/q/<group>`

        // URL (now HTTP 404) are both dead ends on modern mobile QQ.

        private const val QQ_GROUP_NUMBER = "182225274"

        private const val QQ_JOIN_URL =

            "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$QQ_GROUP_NUMBER&card_type=group&source=external"

    }



    override fun onCreate(savedInstanceState: Bundle?) {

        // Apply the persisted theme mode BEFORE super.onCreate() so
        // that the first frame uses the correct day / night color
        // tokens. uiMode is in configChanges, so toggling theme does
        // NOT recreate this Activity — we re-inflate the content view
        // so every ?attr/ theme color is resolved against the new mode.

        applyStoredThemeMode()

        super.onCreate(savedInstanceState)

        appliedNightMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        inflateAndWireContent(savedInstanceState, restoreSnapshot = null)

        observeViewModels()

        setupThemeSwitcher()

        // adb: am start -n com.dashensou.app/.ui.MainActivity --es q 关键词
        intent?.getStringExtra(EXTRA_QUERY)?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
            binding.searchInput.setText(q)
            searchViewModel.search(q, page = 1)
        }

        // 设备封禁：后台拉取 GitHub banlist.txt，命中进封禁页，未命中正常使用
        banManager = BanManager(this)
        reportManager = ReportManager(this, banManager)
        runBanCheck()

    }

    /**
     * 设备封禁校验：后台拉取 GitHub banlist.txt。
     * 命中 → 原生封禁页 + 钉钉「已阻止」告警；未命中 → 设备上线被动上报（24h 去重）。
     */
    private fun runBanCheck() {
        Thread {
            val banned = banManager.isBanned()
            runOnUiThread {
                if (banned) {
                    showBannedScreen()
                    reportManager.reportBannedAttempt()
                } else {
                    Thread { reportManager.reportIfNeeded("launch") }.start()
                }
            }
        }.start()
    }

    /**
     * 原生封禁页（纯代码 View，不依赖 H5/主题/网络，被封时一定可渲染）。
     * 居中显示「本设备已被封禁」+ 设备码（长按复制）+ 申诉邮箱（mailto）。
     */
    private fun showBannedScreen() {
        val id = banManager.getDeviceId()
        val d = resources.displayMetrics.density
        val pad24 = (24 * d).toInt()
        val pad16 = (16 * d).toInt()
        val pad4 = (4 * d).toInt()

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(pad24, pad24, pad24, pad24)
            setBackgroundColor(0xFF111118.toInt())
        }

        val title = android.widget.TextView(this).apply {
            text = "本设备已被封禁"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, pad16)
        }

        val idLabel = android.widget.TextView(this).apply {
            text = "设备识别码（长按可复制）"
            textSize = 13f
            setTextColor(0xFF9999AA.toInt())
        }

        val idView = android.widget.TextView(this).apply {
            text = id
            textSize = 20f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFE0E0E0.toInt())
            setPadding(0, pad4, 0, pad4)
            setOnLongClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("device_id", id))
                Toast.makeText(this@MainActivity, "设备码已复制", Toast.LENGTH_SHORT).show()
                true
            }
        }

        val hint = android.widget.TextView(this).apply {
            text = "如需申诉，请邮件联系开发者"
            textSize = 13f
            setTextColor(0xFF888899.toInt())
            setPadding(0, (24 * d).toInt(), 0, pad4)
            gravity = android.view.Gravity.CENTER
        }

        val email = "sauyh@qq.com"
        val emailView = android.widget.TextView(this).apply {
            text = email
            textSize = 15f
            setTextColor(0xFF6EA8FE.toInt())
            setPadding(0, 0, 0, pad4)
            gravity = android.view.Gravity.CENTER
            paint.isUnderlineText = true
            setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = android.net.Uri.parse("mailto:$email")
                        putExtra(Intent.EXTRA_SUBJECT, "大神搜 — 设备封禁申诉")
                        putExtra(Intent.EXTRA_TEXT, "我的设备识别码：$id\n")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("email", email))
                    Toast.makeText(this@MainActivity, "未找到邮件应用，邮箱已复制", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val emailHint = android.widget.TextView(this).apply {
            text = "请发送设备识别码以便核实"
            textSize = 12f
            setTextColor(0xFF707080.toInt())
            gravity = android.view.Gravity.CENTER
        }

        layout.addView(title)
        layout.addView(idLabel)
        layout.addView(idView)
        layout.addView(hint)
        layout.addView(emailView)
        layout.addView(emailHint)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        if (!startupUpdateCheckDone) {
            startupUpdateCheckDone = true
            AppUpdateManager.runStartupCheck(this)
        }
    }



    /**
     * Inflate [activity_main] and wire listeners. Called on cold start and
     * after an in-place theme switch. ViewModel collectors in
     * [observeViewModels] are started only once from [onCreate].
     */
    private fun inflateAndWireContent(

        savedInstanceState: Bundle?,

        restoreSnapshot: ThemeUiSnapshot?

    ) {

        binding = ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding.root)



        val searchService = searchViewModel.searchService

        pansouSource = searchService.sources.firstOrNull { it is PansouCcSource } as? PansouCcSource

            ?: PansouCcSource()

        aiquSource = searchService.sources.firstOrNull { it is AiQuSource } as? AiQuSource

            ?: AiQuSource()

        haisouSource = searchService.sources.firstOrNull { it is HaiSouSource } as? HaiSouSource
            ?: HaiSouSource()



        SourcePrefs.applyTo(this, searchService.sources)



        setupViews()

        setupSearchInput()

        setupCategoryTabs()

        setupBottomNav(savedInstanceState, preserveTab = restoreSnapshot != null)

        setupSearchAdapter()

        setupDownloadAdapter()

        setupMinePage()



        if (restoreSnapshot != null) {

            restoreThemeUiSnapshot(restoreSnapshot)

            renderSearchState(searchViewModel.state.value)

            renderDownloads(downloadViewModel.records.value)

        }



        applySystemBarAppearance()

    }



    private fun captureThemeUiSnapshot() = ThemeUiSnapshot(

        currentTab = currentTab,

        bottomNavId = binding.bottomNav.selectedItemId,

        searchKeyword = binding.searchInput.text?.toString().orEmpty(),

        categoryTabIndex = binding.categoryTabs.selectedTabPosition.coerceAtLeast(0)

    )



    private fun restoreThemeUiSnapshot(snapshot: ThemeUiSnapshot) {

        binding.searchInput.setText(snapshot.searchKeyword)

        suppressCategoryTabCallback = true

        binding.categoryTabs.getTabAt(snapshot.categoryTabIndex)?.select()

        suppressCategoryTabCallback = false

        binding.bottomNav.selectedItemId = snapshot.bottomNavId

        switchToTab(snapshot.currentTab)

    }



    override fun onConfigurationChanged(newConfig: Configuration) {

        super.onConfigurationChanged(newConfig)

        val newMask = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK

        if (newMask == appliedNightMask) return

        appliedNightMask = newMask

        delegate.applyDayNight()

        val snapshot = captureThemeUiSnapshot()

        inflateAndWireContent(savedInstanceState = null, restoreSnapshot = snapshot)

        setupThemeSwitcher()

    }



    override fun onDestroy() {

        // Cancel any in-flight "fetch share URL" coroutine so a

        // configuration change doesn't leak a coroutine writing to

        // a deleted view binding. The OkHttp call is cancelled

        // through cooperative cancellation.

        inFlightDownloadJob?.cancel()

        inFlightDownloadJob = null

        super.onDestroy()

    }



    private fun setupViews() {

        binding.downloadResults.layoutManager = LinearLayoutManager(this)

    }



    private fun setupSearchInput() {

        binding.searchInput.setOnEditorActionListener { _, actionId, event ->

            val isSearchAction = actionId == EditorInfo.IME_ACTION_SEARCH ||

                actionId == EditorInfo.IME_ACTION_GO ||

                actionId == EditorInfo.IME_ACTION_DONE ||

                (event != null && event.action == KeyEvent.ACTION_DOWN &&

                    (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER))

            if (isSearchAction) {

                val keyword = binding.searchInput.text.toString().trim()

                searchViewModel.search(keyword, page = 1)

                true

            } else {

                false

            }

        }

        // Clearing the input via backspace / focus loss resets the

        // results list back to the "no keyword" empty state.

        binding.searchInput.setOnFocusChangeListener { _, hasFocus ->

            if (!hasFocus && binding.searchInput.text.isNullOrBlank()) {

                searchViewModel.clear()

            }

        }

    }



    private fun setupCategoryTabs() {

        binding.categoryTabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {

            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {

                if (suppressCategoryTabCallback) return

                // 3 tabs in the layout (全部 / 电子书 / 网盘). The

                // visibleResults filter in SearchUiState handles the

                // rest:

                //   ALL     — every result the sources returned

                //   EBOOK   — fileType is an ebook extension, or

                //             netDiskType is DIRECT_URL (aiqu's .txt

                //             mirrors). Excludes net-disk share rows.

                //   NETDISK — netDiskType is BAIDU/QUARK/XUNLEI/

                //             ALIYUN/YUNPAN123, plus OTHER which is

                //             almost always a "中转页" (pansou.cc etc).

                val category = when (tab.position) {

                    0 -> ResourceCategory.ALL

                    1 -> ResourceCategory.EBOOK

                    2 -> ResourceCategory.NETDISK

                    else -> ResourceCategory.ALL

                }

                // P1#10: tab switch never fires a network call. Only an

                // edit-to-keyword or pull-to-refresh hits the sources.

                searchViewModel.setCategory(category)

            }



            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}

            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}

        })

    }



    private fun setupBottomNav(savedInstanceState: Bundle?, preserveTab: Boolean = false) {

        binding.bottomNav.setOnItemSelectedListener { item ->

            when (item.itemId) {

                R.id.nav_search -> { switchToTab(TAB_SEARCH); true }

                R.id.nav_download -> { switchToTab(TAB_DOWNLOADS); true }

                R.id.nav_mine -> { switchToTab(TAB_MINE); true }

                else -> false

            }

        }

        // Highlight the default tab (search) on first paint — without

        // this the BottomNav shows no item selected until the user

        // taps one.

        // Do not clobber restored state on recreation (theme switch, rotation).

        // savedInstanceState is null only on the very first launch, so the

        // user stays on whichever tab they left the app on.

        if (!preserveTab && savedInstanceState == null &&

            binding.bottomNav.selectedItemId != R.id.nav_search) {

            binding.bottomNav.selectedItemId = R.id.nav_search

        }

    }



    private fun switchToTab(tab: Int) {

        currentTab = tab

        binding.searchPage.visibility = if (tab == TAB_SEARCH) View.VISIBLE else View.GONE

        binding.downloadPage.visibility = if (tab == TAB_DOWNLOADS) View.VISIBLE else View.GONE

        binding.minePage.visibility = if (tab == TAB_MINE) View.VISIBLE else View.GONE

    }



    private fun setupSearchAdapter() {

        searchAdapter = SearchResultAdapter { result -> handleDownload(result) }

        binding.searchResults.layoutManager = LinearLayoutManager(this)

        binding.searchResults.adapter = searchAdapter

        binding.refreshLayout.setOnRefreshListener {

            // P0#ux: when there is no keyword, SearchViewModel.refresh()

            // is a no-op (its clear() emits a state that equals the

            // current one, so the StateFlow doesn't re-emit and our

            // `isRefreshing = state.loading` binding never gets the

            // chance to flip the spinner off). SwipeRefreshLayout only

            // dismisses the indicator when isRefreshing is set to false,

            // so without this guard the spinner spins forever on a blank

            // search page.

            if (searchViewModel.state.value.keyword.isBlank()) {

                binding.refreshLayout.isRefreshing = false

            } else {

                searchViewModel.refresh()

            }

        }

    }



    private fun setupDownloadAdapter() {

        downloadAdapter = DownloadRecordAdapter(

            onDeleteClick = { record -> confirmDelete(record) },

            onOpenClick = { record -> downloadViewModel.open(record) },

            onRetryClick = { record -> downloadViewModel.retry(record) },

            onOpenFolderClick = { _ -> downloadViewModel.openDownloadsFolder() },

            onPauseClick = { record -> downloadViewModel.pause(record) },

            onResumeClick = { record -> downloadViewModel.resume(record) }

        )

        binding.downloadResults.adapter = downloadAdapter

    }



    private fun setupMinePage() {

        // P0#mine: populate the "我的" tab. The QQ group entry is the

        // primary feedback channel; the source list is informational and

        // not user-toggleable from this view.

        binding.mineVersion.text = try {

            val pkg = packageManager.getPackageInfo(packageName, 0)

            val version = pkg.versionName ?: "?"

            "v$version"

        } catch (e: Exception) {

            ""

        }

        binding.mineQqGroup.setOnClickListener { launchQQGroupJoin() }

        binding.mineCopyQq.setOnClickListener {

            copyToClipboard("qq_group_number", QQ_GROUP_NUMBER)

            Toast.makeText(this, "已复制群号 $QQ_GROUP_NUMBER", Toast.LENGTH_SHORT).show()

        }

        renderMineSources()

        binding.mineCheckUpdate.setOnClickListener {
            AppUpdateManager.runManualCheck(this)
        }

        binding.mineManualUpdate.setOnClickListener {
            AppUpdateManager.openManualUpdatePage(this)
        }

        binding.mineHealthCheckButton.setOnClickListener {
            startHealthCheck()
        }

    }



    private fun renderMineSources() {

        val container = binding.mineSourcesContainer

        container.removeAllViews()

        val density = resources.displayMetrics.density

        val gap = (8 * density).toInt()

        healthLatencyMap.clear()

        val sources = searchViewModel.searchService.sources

        for ((index, source) in sources.withIndex()) {

            val row = layoutInflater.inflate(R.layout.item_mine_source, container, false)

            val name = row.findViewById<TextView>(R.id.source_name)

            val status = row.findViewById<TextView>(R.id.source_status)

            val latency = row.findViewById<TextView>(R.id.source_latency)

            val switch = row.findViewById<CompoundButton>(R.id.source_switch)

            name.text = source.displayName

            applySourceStatus(status, source.enabled)

            // Read current persisted value first so the switch reflects

            // what the user actually saved, not just the in-memory flag

            // (which may have been mutated by a prior toggle in this

            // session).

            val persisted = SourcePrefs.isEnabled(this, source)

            if (source.enabled != persisted) source.enabled = persisted

            switch.setOnCheckedChangeListener(null)

            switch.isChecked = persisted

            switch.setOnCheckedChangeListener { _, isChecked ->

                source.enabled = isChecked

                SourcePrefs.setEnabled(this, source, isChecked)

                applySourceStatus(status, isChecked)

            }

            // Apply cached health result if available

            healthLatencyMap[source.id]?.let { result ->

                applyHealthResultToViews(latency, status, result)

            }

            val lp = LinearLayout.LayoutParams(

                LinearLayout.LayoutParams.MATCH_PARENT,

                LinearLayout.LayoutParams.WRAP_CONTENT

            )

            if (index > 0) lp.topMargin = gap

            container.addView(row, lp)

        }

    }



    private fun applySourceStatus(view: TextView, enabled: Boolean) {

        view.setText(if (enabled) R.string.source_enabled else R.string.source_disabled)

        view.setTextColor(

            ContextCompat.getColor(

                this,

                if (enabled) R.color.status_success else R.color.text_hint

            )

        )

    }



    private fun startHealthCheck() {

        healthChecker?.shutdown()

        healthChecker = SourceHealthChecker(searchViewModel.searchService.sources)

        binding.mineHealthCheckButton.isEnabled = false

        binding.mineHealthCheckButton.text = getString(R.string.health_check_running)

        lifecycleScope.launch {

            healthChecker?.start(

                onResult = { result ->

                    healthLatencyMap[result.sourceId] = result

                    runOnUiThread {

                        refreshHealthViewForSource(result.sourceId, result)

                    }

                },

                onDone = {

                    runOnUiThread {

                        binding.mineHealthCheckButton.isEnabled = true

                        binding.mineHealthCheckButton.text = getString(R.string.health_check_button)

                    }

                }

            )

        }

    }



    private fun refreshHealthViewForSource(sourceId: String, result: SourceHealthChecker.Result) {

        val container = binding.mineSourcesContainer

        val sources = searchViewModel.searchService.sources

        val index = sources.indexOfFirst { it.id == sourceId }

        if (index < 0 || index >= container.childCount) return



        val row = container.getChildAt(index)

        val status = row.findViewById<TextView>(R.id.source_status)

        val latency = row.findViewById<TextView>(R.id.source_latency)



        applyHealthResultToViews(latency, status, result)

    }



    private fun applyHealthResultToViews(

        latencyView: TextView,

        statusView: TextView,

        result: SourceHealthChecker.Result

    ) {

        when (result.status) {

            SourceHealthChecker.Status.OK -> {

                latencyView.text = getString(R.string.health_latency_ms, result.latencyMs)

                latencyView.setTextColor(ContextCompat.getColor(this, R.color.status_success))

                latencyView.visibility = View.VISIBLE

                statusView.text = getString(R.string.source_enabled)

                statusView.setTextColor(ContextCompat.getColor(this, R.color.status_success))

            }

            SourceHealthChecker.Status.FAIL -> {

                latencyView.text = getString(R.string.health_latency_ms, result.latencyMs)

                latencyView.setTextColor(ContextCompat.getColor(this, R.color.status_error))

                latencyView.visibility = View.VISIBLE

                statusView.text = result.message.ifEmpty { getString(R.string.source_fail) }

                statusView.setTextColor(ContextCompat.getColor(this, R.color.status_error))

            }

            SourceHealthChecker.Status.SKIPPED -> {

                latencyView.visibility = View.GONE

                statusView.text = getString(R.string.source_disabled)

                statusView.setTextColor(ContextCompat.getColor(this, R.color.text_hint))

            }

        }

    }



    private fun launchQQGroupJoin() {

        // P0#mine: take the user straight into mobile QQ's group

        // profile card. We deliberately do NOT use

        //   - `mqqopensdkapi://bizAgent/qm/qr?url=...` — that's a

        //     desktop-era deep link; on mobile QQ it lands in

        //     QRJumpActivity which shows "加群失败".

        //   - `https://qm.qq.com/q/<group>` — that URL is no longer

        //     hosted; it returns HTTP 404.

        // The `mqqapi://card/show_pslcard?...&card_type=group&...`

        // scheme is QQ 8.x+'s public API for opening a group / public

        // account profile card directly inside QQ. The user lands on

        // the group card with a one-tap "加群" button — no browser,

        // no copy-paste, no detour.

        val qqIntent = Intent(Intent.ACTION_VIEW, Uri.parse(QQ_JOIN_URL))

        if (isIntentResolvable(qqIntent)) {

            try {

                startActivity(qqIntent)

                return

            } catch (e: Exception) {

                Log.w(TAG, "QQ group card failed, falling back to clipboard", e)

            }

        } else {

            Log.w(TAG, "QQ is not installed; copying group number")

        }

        copyToClipboard("qq_group_number", QQ_GROUP_NUMBER)

        Toast.makeText(this, "未找到 QQ，已复制群号 $QQ_GROUP_NUMBER", Toast.LENGTH_LONG).show()

    }



    private fun isIntentResolvable(intent: Intent): Boolean {

        val resolveInfos = packageManager.queryIntentActivities(intent, 0)

        return resolveInfos.isNotEmpty()

    }



    private fun observeViewModels() {

        // Search state.

        lifecycleScope.launch {

            repeatOnLifecycle(Lifecycle.State.STARTED) {

                searchViewModel.state.collect { renderSearchState(it) }

            }

        }

        // Downloads list.

        lifecycleScope.launch {

            repeatOnLifecycle(Lifecycle.State.STARTED) {

                downloadViewModel.records.collect { records -> renderDownloads(records) }

            }

        }

        // In-memory progress stream: updates only the row's progress bar
        // and MB text, never rebinds the full row.
        lifecycleScope.launch {

            repeatOnLifecycle(Lifecycle.State.STARTED) {

                DownloadManager.progressUpdates.collect { progress ->
                    downloadAdapter.updateProgressOnly(
                        recordId = progress.recordId,
                        downloadedBytes = progress.downloadedBytes,
                        totalBytes = progress.totalBytes
                    )
                }

            }

        }

    }



    private fun renderSearchState(state: com.dashensou.app.ui.search.SearchViewModel.SearchUiState) {

        binding.refreshLayout.isRefreshing = state.isLoading

        // P0#ux: block pull-to-refresh on the empty/landing state. A

        // refresh with no keyword is a no-op in the ViewModel; leaving

        // the gesture enabled just makes the spinner get stuck (see

        // setupSearchAdapter for the listener-side guard). Keeping the

        // SRL enabled only when there's a keyword also matches the

        // user mental model — "刷新" only makes sense for an actual

        // search.

        binding.refreshLayout.isEnabled = state.keyword.isNotBlank()

        searchAdapter.submitList(state.results)

        val isEmpty = state.results.isEmpty() && state.keyword.isEmpty()

        binding.searchEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE

        binding.searchResults.visibility = if (isEmpty) View.GONE else View.VISIBLE



        // Render the failure dialog at most once per Failure instance so

        // we don't spam the user when the StateFlow re-emits on

        // configuration change.

        val failure = state.failure

        if (failure != null && failure !== lastFailureShown) {

            lastFailureShown = failure

            showSearchFailureDialog(failure)

        }

        if (failure == null) {

            lastFailureShown = null

        }

    }



    private fun renderDownloads(records: List<DownloadRecord>) {

        downloadAdapter.submitList(records)

        binding.downloadEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE

        binding.downloadResults.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE

        // Paint in-flight progress on rows that are currently downloading.
        // Progress data comes from in-memory snapshot; we don't write it
        // to the DB while the transfer is running so the row is never
        // rebind()-ed mid-download.
        records.forEach { record ->
            if (record.status == com.dashensou.app.data.model.DownloadStatus.DOWNLOADING) {
                val snap = DownloadManager.peekProgress(record.id)
                if (snap != null) {
                    downloadAdapter.updateProgressOnly(
                        recordId = snap.recordId,
                        downloadedBytes = snap.downloadedBytes,
                        totalBytes = snap.totalBytes
                    )
                }
            }
        }

    }



    private fun confirmDelete(record: DownloadRecord) {

        AlertDialog.Builder(this)

            .setTitle("删除记录")

            .setMessage("确定删除 \"${record.title}\" 的下载记录？")

            .setPositiveButton("删除") { _, _ -> downloadViewModel.delete(record) }

            .setNegativeButton("取消", null)

            .show()

    }



    /**

     * Per-kind failure hint (P1#16). One-shot, with a primary action

     * that maps to "retry" or "switch keyword" depending on the cause.

     */

    private fun showSearchFailureDialog(outcome: SearchOutcome.Failure) {

        val (suggestion, primaryLabel, primaryAction) = when (outcome.kind) {

            FailureKind.NETWORK -> Triple(

                "看起来网络连不上。请检查 WiFi / 数据连接。",

                "重试"

            ) { searchViewModel.refresh() }

            FailureKind.TIMEOUT -> Triple(

                outcome.message.ifBlank {
                    "搜索超时。可能是网络较慢或源站无响应,稍后再试。"
                },

                "重试"

            ) { searchViewModel.refresh() }

            FailureKind.SOURCE_DOWN -> Triple(

                "部分聚合源暂时不可用。稍后再试一次通常就好。",

                "重试"

            ) { searchViewModel.refresh() }

            FailureKind.PARSE -> Triple(

                "某个源返回了无法识别的内容,可能是它换了页面结构。",

                "换个关键词"

            ) {

                binding.searchInput.setText("")

                binding.searchInput.requestFocus()

            }

            FailureKind.EMPTY -> Triple(

                "没找到匹配的资源。换个关键词试试?",

                "换个关键词"

            ) {

                binding.searchInput.setText("")

                binding.searchInput.requestFocus()

            }

            FailureKind.UNKNOWN -> Triple(

                outcome.message,

                "重试"

            ) { searchViewModel.refresh() }

        }

        Toast.makeText(this, outcome.message, Toast.LENGTH_LONG).show()

        AlertDialog.Builder(this)

            .setTitle("搜索没成功")

            .setMessage(suggestion)

            .setPositiveButton(primaryLabel) { _, _ -> primaryAction() }

            .setNegativeButton("取消") { _, _ -> searchViewModel.clearFailure() }

            .show()

    }



    /**

     * Download flow. The result object's sourceId / netDiskType is the

     * dispatch key:

     *   - aiqu225 (前向匹配): resolve 到 .txt 直链后 enqueue

     *   - pansou.cc (含 /info/ 中转 URL): resolve /goto/ → share URL

     *   - DIRECT_URL:        enqueue a direct download (we own the bytes)

     *   - 其它 (wanzhan / 52api 等): result.url 是分享 URL,直接 copy+open

     *

     * P1: aiqu 标成 DIRECT_URL 只是为了让"电子书 tab"过滤时能正确

     * 归到电子书那一边,但下载流程里 aiqu 仍然走 fetchDetail 解析,

     * 因此 aiqu 的 sourceId 匹配放在所有 netDiskType 判断之前。

     *

     * P0#ux: the dispatch keys off the stable [SearchResult.sourceId]

     * (which mirrors [com.dashensou.app.service.source.SearchSource.id]),

     * not the user-facing [SearchResult.sourceName]. The display name

     * can be renamed for privacy / branding without breaking routing.

     */

    private fun handleDownload(result: SearchResult) {

        when {

            // 1) aiqu225 — 走 fetchDetail 拿 .txt 真实 URL 再 enqueue

            result.sourceId == aiquSource.id -> fetchDirectDownloadAiqu(result)

            // 1b) haisou — 详情页被"邀请码"墙挡住,抓详情页拿真实网盘链接+码
            result.sourceId == haisouSource.id -> fetchAndOpenHaiSou(result)

            // 2) pansou.cc (含 /info/ 中转 URL) — 走 fetchDetail 拿 goto 链接

            result.sourceId == pansouSource.id

                || result.url.contains("/info/") -> fetchAndSharePansouCc(result)

            // 3) 磁力 / ed2k — 夸克浏览器可打开并离线下载

            UrlKinds.isTorrentLike(result.url) -> openTorrentInQuark(result)

            // 5) 真正的直链 (wanzhan / 52api 等的"直链"类条目)

            //    result.url 就是 .txt/.pdf 真实下载 URL,直接 enqueue

            result.netDiskType == NetDiskType.DIRECT_URL -> {

                // 直链源 (免费小带宽 CDN) 经常几 KB/s 慢,弹一个选择:
                //   - 直接下: 当文件小时够用,无需装夸克
                //   - 推夸克: 夸克有自己的高速下载通道,通常 5-10x 快
                val quarkAvailable = DownloadManager.isQuarkInstalled()
                val builder = AlertDialog.Builder(this)
                    .setTitle("选择下载方式")
                    .setMessage(
                        if (quarkAvailable)
                            "直链源速度可能较慢,推荐用夸克离线下载。\n\n用直链 = 当前 App 内下载\n用夸克 = 由夸克浏览器接管,速度更快"
                        else
                            "直链源速度可能较慢,推荐安装夸克浏览器获得更快的下载速度"
                    )
                    .setPositiveButton("用直链") { _, _ ->
                        DownloadManager.enqueueDirectDownload(
                            title = result.title,
                            url = result.url,
                            category = result.category,
                            fileType = result.fileType
                        )
                        Toast.makeText(this, "已开始下载 (可能较慢)", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)

                if (quarkAvailable) {
                    builder.setNeutralButton("用夸克") { _, _ ->
                        DownloadManager.openHttpInQuark(result.url)
                    }
                }

                builder.show()

            }

            // 6) 兜底:其它 — result.url 已经是分享 URL,直接 copy+open

            else -> shareAndOpenNetDiskApp(result, result.url, result.extractionCode)

        }

    }



    private fun openTorrentInQuark(result: SearchResult) {

        copyToClipboard("torrent_link", result.url)

        when (DownloadManager.openTorrentInQuark(result.url)) {

            DownloadManager.TorrentOpenResult.SUCCESS -> Toast.makeText(

                this,

                "已复制链接,夸克浏览器已打开(可在夸克里离线下载)",

                Toast.LENGTH_LONG

            ).show()

            DownloadManager.TorrentOpenResult.NOT_INSTALLED -> Toast.makeText(

                this,

                "已复制链接,请安装夸克浏览器后重试",

                Toast.LENGTH_LONG

            ).show()

            DownloadManager.TorrentOpenResult.OPEN_FAILED -> Toast.makeText(

                this,

                if (DownloadManager.isQuarkInstalled()) {

                    "链接已复制,夸克未能自动打开,请在夸克中粘贴链接离线下载"

                } else {

                    "已复制链接,请安装夸克浏览器后重试"

                },

                Toast.LENGTH_LONG

            ).show()

        }

    }





    private fun fetchAndSharePansouCc(result: SearchResult) {

        launchDownloadJob("获取网盘链接超时，请稍后重试") {

            val detail = withTimeoutOrNull(10_000L) {

                pansouSource.fetchDetail(result.url)

            }

            if (detail == null) {

                return@launchDownloadJob DownloadOutcome.Failure("获取网盘链接超时，请稍后重试")

            }

            // pansou.cc `/goto/` pages decrypt the real share URL in JS.

            // Resolve it first; fall back to WebView if the headless pass

            // times out (slow network / heavy RSA page).

            val resolvedShare = withTimeoutOrNull(10_000L) {

                PansouGotoResolver.resolve(this@MainActivity, detail.gotoUrl)

            }

            val enriched = result.copy(

                netDiskType = detail.netDiskType,

                extractionCode = detail.password

            )

            if (resolvedShare.isNullOrBlank()

                || resolvedShare.contains("pansou.cc/goto", ignoreCase = true)

            ) {

                return@launchDownloadJob DownloadOutcome.Success {

                    startActivity(

                        Intent(this@MainActivity, WebViewActivity::class.java).apply {

                            putExtra(WebViewActivity.EXTRA_GOTO_URL, detail.gotoUrl)

                            putExtra(WebViewActivity.EXTRA_NET_DISK_TYPE, detail.netDiskType.name)

                            putExtra(WebViewActivity.EXTRA_PASSWORD, detail.password)

                        }

                    )

                    if (!detail.password.isNullOrBlank()) {

                        copyToClipboard("netdisk_password", detail.password)

                        Toast.makeText(

                            this@MainActivity,

                            "提取码已复制: ${detail.password}",

                            Toast.LENGTH_SHORT

                        ).show()

                    }

                }

            }

            DownloadOutcome.Success {

                shareAndOpenNetDiskApp(enriched, resolvedShare, detail.password)

            }

        }

    }



    private fun fetchDirectDownloadAiqu(result: SearchResult) {

        launchDownloadJob("获取下载链接超时，请稍后重试") {

            val detail = withTimeoutOrNull(8_000L) {

                aiquSource.fetchDetail(result.url)

            }

            if (detail == null) {

                return@launchDownloadJob DownloadOutcome.Failure("获取下载链接超时，请稍后重试")

            }

            DownloadOutcome.Success {

                DownloadManager.enqueueDirectDownload(

                    title = result.title,

                    url = detail.gotoUrl,

                    category = result.category,

                    fileType = result.fileType

                )

                Toast.makeText(this@MainActivity, "已加入下载（Download/Book）", Toast.LENGTH_SHORT).show()

            }

        }

    }



    /**

     * haisou: the result's `sourceUrl` is the gated detail page

     * `haisou.cc/s/{hsid}`. Scrape it for the real net-disk share URL and the

     * access code ("邀请码"), then open that + copy the code. Falls back to

     * the best-effort `result.url` (built from share_code) if scraping fails.

     */

    private fun fetchAndOpenHaiSou(result: SearchResult) {

        launchDownloadJob("获取分享链接超时，请稍后重试") {

            val hsid = result.sourceUrl.substringAfter("/s/")
                .substringBefore("/").substringBefore("?")

            val resolved = if (hsid.isNotBlank()) {

                withTimeoutOrNull(10_000L) { haisouSource.resolveRealShare(hsid) }

            } else null

            val realUrl = resolved?.url ?: result.url

            val pwd = resolved?.pwd ?: result.extractionCode

            Log.i(TAG, "fetchAndOpenHaiSou: hsid='$hsid' -> $realUrl pwd=${pwd ?: "(none)"}")

            DownloadOutcome.Success {

                shareAndOpenNetDiskApp(result, realUrl, pwd)

            }

        }

    }



    /**

     * Common scaffolding for every "tap a card → resolve share URL

     * → open net-disk app" path. The block runs on Dispatchers.IO

     * via the OkHttp call; once it returns, the resulting success

     * callback is invoked on the main thread (because lifecycleScope

     * dispatches on Main by default).

     *

     * The previous code had three near-identical copies of this loop.

     * P1#18: the previous version also forgot to cancel an

     * already-running fetch when the user tapped another card. The

     * single Job we keep on [inFlightDownloadJob] replaces the prior

     * one and cancels it, so the user can never trigger two parallel

     * OkHttp calls from rapid taps.

     */

    private fun launchDownloadJob(

        @Suppress("UNUSED_PARAMETER") errorPlaceholder: String,

        block: suspend () -> DownloadOutcome

    ) {

        inFlightDownloadJob?.cancel()

        binding.refreshLayout.isRefreshing = true

        inFlightDownloadJob = lifecycleScope.launch {

            try {

                when (val outcome = block()) {

                    is DownloadOutcome.Success -> outcome.onMain()

                    is DownloadOutcome.Failure -> Toast.makeText(

                        this@MainActivity,

                        outcome.message,

                        Toast.LENGTH_SHORT

                    ).show()

                }

            } catch (e: kotlinx.coroutines.CancellationException) {

                // Silent — a newer tap replaced us, or onDestroy killed us.

                throw e

            } catch (e: Exception) {

                Toast.makeText(this@MainActivity, "获取失败: ${e.message}", Toast.LENGTH_LONG).show()

            } finally {

                binding.refreshLayout.isRefreshing = false

            }

        }

    }



    private sealed class DownloadOutcome {

        data class Success(val onMain: () -> Unit) : DownloadOutcome()

        data class Failure(val message: String) : DownloadOutcome()

    }



    /**

     * 把真实网盘分享 URL 复制到系统剪贴板,然后调起对应的网盘 app。

     * 这是 "下载资源" 按钮的核心动作 — 用户在网盘 app 里打开后,

     * 大多数网盘都会自动读剪贴板并把资源转存到自己的网盘里。

     */

    private fun shareAndOpenNetDiskApp(result: SearchResult, shareUrl: String, password: String?) {

        if (shareUrl.isBlank()) {

            Toast.makeText(this, "网盘链接为空", Toast.LENGTH_SHORT).show()

            return

        }

        val urlWithPwd = com.dashensou.app.util.NetDiskUtils.appendExtractionCode(

            shareUrl,

            result.netDiskType,

            password ?: result.extractionCode

        )

        val pwd = password ?: result.extractionCode

        // 1) 复制到剪贴板 — 含密码(如有)便于用户识别是哪条资源

        val clipboardText = if (!pwd.isNullOrBlank()) "$urlWithPwd  提取码:$pwd"

        else urlWithPwd

        copyToClipboard("download_resource", clipboardText)

        // 2) 调起网盘 app

        val effectiveResult = result.copy(

            url = urlWithPwd,

            extractionCode = pwd ?: result.extractionCode

        )

        val success = DownloadManager.openNetDiskApp(effectiveResult)

        val diskName = diskLabel(result.netDiskType)

        if (success) {

            val pwdHint = if (!pwd.isNullOrBlank()) "（含提取码）" else ""

            Toast.makeText(

                this,

                "已复制 $diskName 链接$pwdHint,网盘 app 已打开",

                Toast.LENGTH_LONG

            ).show()

        } else {

            Toast.makeText(this, "已复制链接,未找到 $diskName app", Toast.LENGTH_LONG).show()

        }

    }



    private fun copyToClipboard(label: String, text: String) {

        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        cm.setPrimaryClip(ClipData.newPlainText(label, text))

    }



    private fun diskLabel(type: NetDiskType): String = DiskLabels.short(type)



    // ----------------------------------------------------------------

    // Theme switcher (Mine page 3-segment toggle)

    // ----------------------------------------------------------------



    private fun applyStoredThemeMode() {

        val mode = readThemeModePref()

        if (AppCompatDelegate.getDefaultNightMode() != mode) {

            AppCompatDelegate.setDefaultNightMode(mode)

        }

    }



    private fun applyThemeMode(mode: Int, persist: Boolean = true) {

        if (persist) writeThemeModePref(mode)

        val snapshot = captureThemeUiSnapshot()

        AppCompatDelegate.setDefaultNightMode(mode)

        delegate.localNightMode = mode

        delegate.applyDayNight()

        appliedNightMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        inflateAndWireContent(savedInstanceState = null, restoreSnapshot = snapshot)

        setupThemeSwitcher()

    }



    private fun applySystemBarAppearance() {

        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        val lightBars = nightMode != Configuration.UI_MODE_NIGHT_YES

        window.statusBarColor = ContextCompat.getColor(this, R.color.colorSurface)

        window.navigationBarColor = ContextCompat.getColor(this, R.color.colorSurface)

        WindowCompat.getInsetsController(window, window.decorView).apply {

            isAppearanceLightStatusBars = lightBars

            isAppearanceLightNavigationBars = lightBars

        }

    }



    private fun setupThemeSwitcher() {

        val group = binding.mineThemeGroup

        val current = readThemeModePref()

        val initialButton = when (current) {

            AppCompatDelegate.MODE_NIGHT_NO -> R.id.mine_theme_light

            AppCompatDelegate.MODE_NIGHT_YES -> R.id.mine_theme_dark

            else -> R.id.mine_theme_system

        }

        suppressThemeSwitcher = true

        group.check(initialButton)

        suppressThemeSwitcher = false

        group.clearOnButtonCheckedListeners()

        group.addOnButtonCheckedListener { _, checkedId, isChecked ->

            if (suppressThemeSwitcher || !isChecked) return@addOnButtonCheckedListener

            val mode = when (checkedId) {

                R.id.mine_theme_light -> AppCompatDelegate.MODE_NIGHT_NO

                R.id.mine_theme_dark -> AppCompatDelegate.MODE_NIGHT_YES

                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM

            }

            applyThemeMode(mode)

        }

    }



    private fun themePrefs() =

        getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE)



    private fun readThemeModePref(): Int =

        themePrefs().getInt(THEME_PREFS_KEY, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)



    private fun writeThemeModePref(mode: Int) {

        themePrefs().edit().putInt(THEME_PREFS_KEY, mode).apply()

    }

}



/**

 * AndroidViewModel factory so the Application + DownloadManager are

 * injected into [DownloadViewModel]. The default factory only knows

 * how to build no-arg ViewModels, which DownloadViewModel isn't.

 */

class DownloadViewModelFactory(

    private val app: android.app.Application,

    private val downloadManager: DownloadManager

) : androidx.lifecycle.ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")

    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {

        if (modelClass.isAssignableFrom(DownloadViewModel::class.java)) {

            return DownloadViewModel(app, downloadManager) as T

        }

        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")

    }

}


