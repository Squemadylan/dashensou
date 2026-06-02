package com.dashensou.app.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dashensou.app.App
import com.dashensou.app.R
import com.dashensou.app.data.model.DownloadRecord
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.databinding.ActivityMainBinding
import com.dashensou.app.service.DownloadManager
import com.dashensou.app.service.SearchService
import com.dashensou.app.service.source.AiQuSource
import com.dashensou.app.service.source.PansouCcSource
import com.dashensou.app.service.source.SearchOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var searchService: SearchService
    private lateinit var pansouSource: PansouCcSource
    private lateinit var aiquSource: AiQuSource
    private lateinit var downloadManager: DownloadManager
    private lateinit var downloadAdapter: DownloadRecordAdapter

    private var currentCategory = ResourceCategory.ALL
    private var currentPage = 1
    private var currentKeyword = ""
    private var searchResults = mutableListOf<SearchResult>()
    private var currentTab = TAB_SEARCH
    private var progressPollingJob: Job? = null

    companion object {
        private const val TAB_SEARCH = 0
        private const val TAB_HISTORY = 1
        private const val TAB_DOWNLOADS = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        searchService = SearchService()
        pansouSource = (searchService.sources.firstOrNull { it is PansouCcSource } as? PansouCcSource)
            ?: PansouCcSource()
        aiquSource = (searchService.sources.firstOrNull { it is AiQuSource } as? AiQuSource)
            ?: AiQuSource()
        downloadManager = DownloadManager(this)

        setupViews()
        setupSearch()
        setupCategoryTabs()
        setupBottomNav()
        setupRecyclerView()
        setupDownloadAdapter()
        loadRecommendations()
    }

    private fun setupViews() {
        binding.downloadResults.layoutManager = LinearLayoutManager(this)
        binding.historyResults.layoutManager = LinearLayoutManager(this)
    }

    private fun setupSearch() {
        binding.searchInput.setOnEditorActionListener { _, actionId, event ->
            val isSearchAction = actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.action == KeyEvent.ACTION_DOWN &&
                    (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER))
            if (isSearchAction) {
                performSearch()
                true
            } else {
                false
            }
        }
    }

    private fun setupCategoryTabs() {
        binding.categoryTabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                currentCategory = when (tab.position) {
                    0 -> ResourceCategory.ALL
                    1 -> ResourceCategory.EBOOK
                    2 -> ResourceCategory.MOVIE
                    3 -> ResourceCategory.TV
                    else -> ResourceCategory.ALL
                }
                performSearch()
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_search -> {
                    switchToTab(TAB_SEARCH)
                    true
                }
                R.id.nav_history -> {
                    switchToTab(TAB_HISTORY)
                    true
                }
                R.id.nav_download -> {
                    switchToTab(TAB_DOWNLOADS)
                    true
                }
                else -> false
            }
        }
    }

    private fun switchToTab(tab: Int) {
        currentTab = tab
        binding.searchPage.visibility = if (tab == TAB_SEARCH) View.VISIBLE else View.GONE
        binding.historyPage.visibility = if (tab == TAB_HISTORY) View.VISIBLE else View.GONE
        binding.downloadPage.visibility = if (tab == TAB_DOWNLOADS) View.VISIBLE else View.GONE

        when (tab) {
            TAB_SEARCH -> {}
            TAB_HISTORY -> loadHistory()
            TAB_DOWNLOADS -> {
                loadDownloads()
                startProgressPolling()
            }
        }
    }

    private fun setupRecyclerView() {
        binding.searchResults.layoutManager = LinearLayoutManager(this)
        binding.refreshLayout.setOnRefreshListener {
            currentPage = 1
            performSearch()
        }
    }

    private fun setupDownloadAdapter() {
        downloadAdapter = DownloadRecordAdapter { record ->
            AlertDialog.Builder(this)
                .setTitle("删除记录")
                .setMessage("确定删除 \"${record.title}\" 的下载记录？")
                .setPositiveButton("删除") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        App.database.downloadRecordDao().deleteDownloadRecord(record)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
        binding.downloadResults.adapter = downloadAdapter
    }

    private fun performSearch() {
        val keyword = binding.searchInput.text.toString().trim()
        if (keyword.isEmpty()) {
            loadRecommendations()
            return
        }

        currentKeyword = keyword
        currentPage = 1
        binding.refreshLayout.isRefreshing = true

        lifecycleScope.launch {
            val outcome = searchService.search(keyword, currentPage, currentCategory)
            saveSearchHistory(keyword)

            binding.refreshLayout.isRefreshing = false
            searchResults.clear()
            when (outcome) {
                is SearchOutcome.Success -> {
                    searchResults.addAll(outcome.results)
                    updateRecyclerView()
                    if (outcome.results.isEmpty()) {
                        Toast.makeText(this@MainActivity, "没有找到相关资源", Toast.LENGTH_SHORT).show()
                    }
                }
                is SearchOutcome.Failure -> {
                    updateRecyclerView()
                    Toast.makeText(
                        this@MainActivity,
                        "搜索失败: ${outcome.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun loadRecommendations() {
        binding.searchInput.setText("")
        currentKeyword = ""
        searchResults.clear()
        val recommendations = listOf(
            SearchResult(
                id = "1",
                title = "三体全集.epub",
                description = "刘慈欣经典科幻小说",
                url = "https://pan.baidu.com/s/xxx",
                netDiskType = NetDiskType.BAIDU,
                size = "2.3MB",
                date = "2024-01-15",
                category = ResourceCategory.EBOOK
            ),
            SearchResult(
                id = "2",
                title = "流浪地球2.mp4",
                description = "2023年科幻巨制",
                url = "https://pan.quark.cn/s/xxx",
                netDiskType = NetDiskType.QUARK,
                size = "4.5GB",
                date = "2024-02-20",
                category = ResourceCategory.MOVIE
            ),
            SearchResult(
                id = "3",
                title = "狂飙 全39集",
                description = "2023年爆款电视剧",
                url = "https://pan.xunlei.com/s/xxx",
                netDiskType = NetDiskType.XUNLEI,
                size = "28GB",
                date = "2024-01-10",
                category = ResourceCategory.TV
            )
        )
        searchResults.addAll(recommendations)
        updateRecyclerView()
    }

    private fun updateRecyclerView() {
        val filtered = filterByCategory(searchResults, currentCategory)
        binding.searchResults.adapter = SearchResultAdapter(filtered) { result ->
            handleDownload(result)
        }
    }

    private fun filterByCategory(results: List<SearchResult>, category: ResourceCategory): List<SearchResult> {
        if (category == ResourceCategory.ALL) return results
        return results.filter { result ->
            val ft = result.fileType
            when (category) {
                ResourceCategory.EBOOK -> ft == null || ft in listOf("pdf", "epub", "mobi", "txt", "ebook", "zip", "html", "azw3", "archive")
                ResourceCategory.MOVIE, ResourceCategory.TV -> ft == "video"
                else -> true
            }
        }
    }

    private fun handleDownload(result: SearchResult) {
        when {
            result.sourceName == pansouSource.displayName -> fetchAndOpenPansou(result)
            result.netDiskType == NetDiskType.OTHER || result.url.contains("/info/") -> fetchAndOpenPansou(result)
            else -> openOrDownload(result)
        }
    }

    private fun fetchAndOpenPansou(result: SearchResult) {
        binding.refreshLayout.isRefreshing = true
        lifecycleScope.launch {
            val detail = pansouSource.fetchDetail(result.url)
            binding.refreshLayout.isRefreshing = false
            if (detail == null) {
                Toast.makeText(
                    this@MainActivity,
                    "未找到网盘链接，请稍后重试",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            openWebView(detail.gotoUrl, detail.netDiskType, detail.password)
        }
    }

    private fun openWebView(gotoUrl: String, netDiskType: NetDiskType, password: String?) {
        val intent = android.content.Intent(this, WebViewActivity::class.java)
        intent.putExtra(WebViewActivity.EXTRA_GOTO_URL, gotoUrl)
        intent.putExtra(WebViewActivity.EXTRA_NET_DISK_TYPE, netDiskType.name)
        intent.putExtra(WebViewActivity.EXTRA_PASSWORD, password)
        startActivity(intent)
    }

    private fun openOrDownload(result: SearchResult) {
        when (result.netDiskType) {
            NetDiskType.DIRECT_URL -> {
                downloadManager.downloadFile(result, result.category)
                Toast.makeText(this, "开始下载", Toast.LENGTH_SHORT).show()
            }
            else -> {
                val success = downloadManager.openNetDiskApp(result)
                if (success) {
                    Toast.makeText(this, "正在打开", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "未找到相关应用", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            App.database.searchHistoryDao().getAllSearchHistory().collect { historyList ->
                if (historyList.isEmpty()) {
                    binding.historyResults.adapter = EmptyAdapter("暂无搜索历史")
                } else {
                    val keywords = historyList.map { it.keyword }.distinct()
                    binding.historyResults.adapter = HistoryAdapter(keywords) { keyword ->
                        binding.searchInput.setText(keyword)
                        currentCategory = ResourceCategory.ALL
                        binding.categoryTabs.selectTab(binding.categoryTabs.getTabAt(0))
                        switchToTab(TAB_SEARCH)
                        performSearch()
                    }
                }
            }
        }
    }

    private fun loadDownloads() {
        lifecycleScope.launch {
            App.database.downloadRecordDao().getAllDownloadRecords().collect { records ->
                downloadAdapter.submitList(records)
                binding.downloadEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
                binding.downloadResults.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun startProgressPolling() {
        progressPollingJob?.cancel()
        progressPollingJob = lifecycleScope.launch {
            while (isActive && currentTab == TAB_DOWNLOADS) {
                downloadManager.queryAndUpdateProgress()
                delay(2000)
            }
        }
    }

    private suspend fun saveSearchHistory(keyword: String) {
        withContext(Dispatchers.IO) {
            val existing = App.database.searchHistoryDao().getSearchHistoryByKeyword(keyword)
            if (existing != null) {
                App.database.searchHistoryDao().updateSearchHistory(existing.copy(
                    searchTime = System.currentTimeMillis(),
                    searchCount = existing.searchCount + 1
                ))
            } else {
                App.database.searchHistoryDao().insertSearchHistory(
                    com.dashensou.app.data.model.SearchHistory(keyword = keyword)
                )
            }
        }
    }
}
