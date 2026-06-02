package com.dashensou.app.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.dashensou.app.R
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchHistory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.databinding.ActivityMainBinding
import com.dashensou.app.service.DownloadManager
import com.dashensou.app.service.SearchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var searchService: SearchService
    private lateinit var downloadManager: DownloadManager
    private var currentCategory = ResourceCategory.ALL
    private var currentPage = 1
    private var currentKeyword = ""
    private var searchResults = mutableListOf<SearchResult>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        searchService = SearchService()
        downloadManager = DownloadManager(this)

        setupSearch()
        setupCategoryTabs()
        setupBottomNav()
        setupRecyclerView()
        loadRecommendations()
    }

    private fun setupSearch() {
        binding.searchInput.setOnEditorActionListener { _, _, _ ->
            performSearch()
            true
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
                    binding.searchInput.requestFocus()
                    true
                }
                R.id.nav_history -> {
                    showHistory()
                    true
                }
                R.id.nav_download -> {
                    showDownloads()
                    true
                }
                else -> false
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

    private fun performSearch() {
        val keyword = binding.searchInput.text.toString().trim()
        if (keyword.isEmpty()) {
            loadRecommendations()
            return
        }

        currentKeyword = keyword
        currentPage = 1
        binding.refreshLayout.isRefreshing = true

        CoroutineScope(Dispatchers.IO).launch {
            val results = searchService.search(keyword, currentPage, currentCategory)
            saveSearchHistory(keyword)

            withContext(Dispatchers.Main) {
                searchResults.clear()
                searchResults.addAll(results)
                updateRecyclerView()
                binding.refreshLayout.isRefreshing = false

                if (results.isEmpty()) {
                    Toast.makeText(this@MainActivity, R.string.no_results, Toast.LENGTH_SHORT).show()
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
                netDiskType = com.dashensou.app.data.model.NetDiskType.BAIDU,
                size = "2.3MB",
                date = "2024-01-15",
                category = ResourceCategory.EBOOK
            ),
            SearchResult(
                id = "2",
                title = "流浪地球2.mp4",
                description = "2023年科幻巨制",
                url = "https://pan.quark.cn/s/xxx",
                netDiskType = com.dashensou.app.data.model.NetDiskType.QUARK,
                size = "4.5GB",
                date = "2024-02-20",
                category = ResourceCategory.MOVIE
            ),
            SearchResult(
                id = "3",
                title = "狂飙 全39集",
                description = "2023年爆款电视剧",
                url = "https://pan.xunlei.com/s/xxx",
                netDiskType = com.dashensou.app.data.model.NetDiskType.XUNLEI,
                size = "28GB",
                date = "2024-01-10",
                category = ResourceCategory.TV
            )
        )
        searchResults.addAll(recommendations)
        updateRecyclerView()
    }

    private fun updateRecyclerView() {
        binding.searchResults.adapter = SearchResultAdapter(searchResults) { result ->
            handleDownload(result)
        }
    }

    private fun handleDownload(result: SearchResult) {
        when (result.netDiskType) {
            com.dashensou.app.data.model.NetDiskType.DIRECT_URL -> {
                downloadManager.downloadFile(result, result.category)
                Toast.makeText(this, R.string.downloading, Toast.LENGTH_SHORT).show()
            }
            else -> {
                val success = downloadManager.openNetDiskApp(result)
                if (success) {
                    Toast.makeText(this, R.string.open_netdisk, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.netdisk_not_installed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun saveSearchHistory(keyword: String) {
        val history = SearchHistory(keyword = keyword)
        // Save to database logic here
    }

    private fun showHistory() {
        Toast.makeText(this, R.string.search_history, Toast.LENGTH_SHORT).show()
    }

    private fun showDownloads() {
        Toast.makeText(this, R.string.download_history, Toast.LENGTH_SHORT).show()
    }
}
