package com.dashensou.app.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.databinding.ItemSearchResultBinding
import com.dashensou.app.service.linkcheck.LinkCheckStatus
import com.dashensou.app.util.DiskLabels
import com.dashensou.app.util.UrlKinds
import android.graphics.Paint
import androidx.core.content.ContextCompat
import com.dashensou.app.R

class SearchResultAdapter(
    private val onDownloadClick: (SearchResult) -> Unit
) : ListAdapter<SearchResult, SearchResultAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemSearchResultBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(result: SearchResult) {
            binding.resultTitle.text = result.title
            binding.resultDesc.text = result.description
            binding.resultSource.text = DiskLabels.short(result.netDiskType)
            binding.resultSize.text = result.size
            binding.resultDate.text = result.date

            val struck = result.linkCheckStatus == LinkCheckStatus.BAD
            binding.resultTitle.paintFlags = if (struck) {
                binding.resultTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                binding.resultTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            bindLinkStatus(result.linkCheckStatus)

            // P1#17: provenance row. result_sourceName is the aggregator
            // (万站API / PanSou 盘搜 / pansou.cc / aiqu225 / etc),
            // as distinct from the net-disk type ("夸克"/"度盘"/"...")
            // which is the right-hand tag. Show it on its own line so the
            // user can see "来源: 万站API" without confusing it with
            // "网盘: 夸克".
            val sourceName = result.sourceName.trim()
            if (sourceName.isNotEmpty()) {
                binding.resultSourceName.text = "来源: $sourceName"
                binding.resultSourceName.visibility = View.VISIBLE
            } else {
                binding.resultSourceName.visibility = View.GONE
            }

            // P1#8 + "下载资源" 改造: 搜索结果只负责列出清单,真正
            // 的下载动作统一走 "下载资源" 按钮 — 点击后由 MainActivity
            // 解析出真实网盘 URL,复制到剪贴板,并把对应的网盘 app
            // 调起来。DIRECT_URL(直链)直接走系统 DownloadManager,
            // 按钮简化为 "下载"。
            binding.resultDownloadBtn.text = when {
                result.netDiskType == NetDiskType.DIRECT_URL -> "下载"
                UrlKinds.isTorrentLike(result.url) -> "夸克离线下载"
                else -> "下载资源"
            }
            binding.resultDownloadBtn.isEnabled =
                result.linkCheckStatus != LinkCheckStatus.BAD

            binding.resultDownloadBtn.setOnClickListener {
                onDownloadClick(result)
            }

            // P1#11: long-press anywhere on the card pops a context
            // menu. The list is rendered as AlertDialog.setItems rather
            // than a BottomSheet so we don't have to ship a new layout
            // file -- the items are the three things users most often
            // want to do with a search hit that doesn't show up as a
            // 1-click "open in app" path.
            binding.root.setOnLongClickListener {
                showContextMenu(binding.root.context, result)
                true
            }
        }

        private fun bindLinkStatus(status: LinkCheckStatus) {
            val chip = binding.resultLinkStatus
            val ctx = chip.context
            when (status) {
                LinkCheckStatus.UNCHECKED -> {
                    chip.visibility = View.GONE
                }
                LinkCheckStatus.CHECKING -> {
                    chip.visibility = View.VISIBLE
                    chip.text = "检测中"
                    chip.setTextColor(ContextCompat.getColor(ctx, R.color.colorOnSurfaceVariant))
                }
                LinkCheckStatus.OK -> {
                    chip.visibility = View.GONE
                }
                LinkCheckStatus.BAD -> {
                    chip.visibility = View.VISIBLE
                    chip.text = "已失效"
                    chip.setTextColor(ContextCompat.getColor(ctx, R.color.status_error))
                }
                LinkCheckStatus.LOCKED -> {
                    chip.visibility = View.VISIBLE
                    chip.text = "需密码"
                    chip.setTextColor(ContextCompat.getColor(ctx, R.color.status_pending))
                }
                LinkCheckStatus.UNSUPPORTED,
                LinkCheckStatus.UNCERTAIN -> {
                    chip.visibility = View.GONE
                }
            }
        }

        private fun showContextMenu(context: Context, result: SearchResult) {
            val items = arrayOf("复制链接", "在浏览器打开", "查看来源")
            AlertDialog.Builder(context)
                .setTitle(result.title)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> copyLink(context, result)
                        1 -> openInBrowser(context, result)
                        2 -> showSourceInfo(context, result)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        private fun copyLink(context: Context, result: SearchResult) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = when {
                UrlKinds.isTorrentLike(result.url) -> result.url
                result.url.startsWith("http") -> result.url
                else -> result.sourceUrl.takeIf { it.startsWith("http") } ?: result.url
            }
            cm.setPrimaryClip(ClipData.newPlainText("search_result_url", text))
            Toast.makeText(context, "已复制链接", Toast.LENGTH_SHORT).show()
        }

        private fun openInBrowser(context: Context, result: SearchResult) {
            val url = result.url.takeIf { it.startsWith("http") }
                ?: result.sourceUrl.takeIf { it.startsWith("http") }
                ?: return
            try {
                // P1#quality: the onClick context here is an Activity
                // (itemView.context returns the host Activity for a
                // RecyclerView item), so FLAG_ACTIVITY_NEW_TASK isn't
                // required and we drop it to keep the back stack
                // predictable (back from the browser returns the user
                // to MainActivity, not to a new task root).
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "未找到可用的浏览器", Toast.LENGTH_SHORT).show()
            }
        }

    private fun showSourceInfo(context: Context, result: SearchResult) {
        val lines = buildList {
            add("来源聚合源: ${result.sourceName.ifBlank { "(未知)" }}")
            add("网盘类型: ${DiskLabels.short(result.netDiskType)}")
                add("文件类型: ${result.fileType ?: "(未识别)"}")
                add("标题: ${result.title}")
                if (result.extractionCode.isNullOrBlank().not()) {
                    add("提取码: ${result.extractionCode}")
                }
                add("链接: ${result.url}")
            }
            AlertDialog.Builder(context)
                .setTitle("来源信息")
                .setMessage(lines.joinToString("\n"))
                .setPositiveButton("复制链接") { _, _ -> copyLink(context, result) }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<SearchResult>() {
        override fun areItemsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean {
            return oldItem == newItem
        }
    }
}
