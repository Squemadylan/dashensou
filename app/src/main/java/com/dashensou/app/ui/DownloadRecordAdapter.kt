package com.dashensou.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dashensou.app.R
import com.dashensou.app.data.model.DownloadRecord
import com.dashensou.app.data.model.DownloadStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadRecordAdapter(
    private val onDeleteClick: (DownloadRecord) -> Unit,
    private val onOpenClick: (DownloadRecord) -> Unit = {},
    private val onRetryClick: (DownloadRecord) -> Unit = {},
    private val onOpenFolderClick: (DownloadRecord) -> Unit = {},
    private val onPauseClick: (DownloadRecord) -> Unit = {},
    private val onResumeClick: (DownloadRecord) -> Unit = {}
) : ListAdapter<DownloadRecord, DownloadRecordAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download_record, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.download_title)
        private val statusView: TextView = itemView.findViewById(R.id.download_status)
        private val sizeView: TextView = itemView.findViewById(R.id.download_size)
        private val timeView: TextView = itemView.findViewById(R.id.download_time)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.download_progress)
        private val progressText: TextView = itemView.findViewById(R.id.download_progress_text)
        private val actionBtn: ImageButton = itemView.findViewById(R.id.download_action_btn)
        private val folderBtn: ImageButton = itemView.findViewById(R.id.download_folder_btn)

        fun bind(record: DownloadRecord) {
            titleView.text = record.title

            val progress = if (record.fileSize > 0) {
                ((record.downloadSize.toDouble() / record.fileSize) * 100).toInt().coerceIn(0, 100)
            } else 0

            // P1#14: row icon used to be a hard-coded X for every state, so a
            // completed record's "open" affordance was hidden behind the row
            // tap and the X icon was the only way to delete. Switch the icon
            // AND the click target by status, and demote destructive
            // "delete" to the long-press menu.
            when (record.status) {
                DownloadStatus.PENDING -> {
                    statusView.text = "等待中"
                    statusView.setTextColor(0xFFFFA000.toInt())
                    progressBar.visibility = View.GONE
                    progressText.visibility = View.GONE
                    actionBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    actionBtn.contentDescription = "删除"
                    actionBtn.setOnClickListener { onDeleteClick(record) }
                }
                DownloadStatus.DOWNLOADING -> {
                    statusView.text = "下载中"
                    statusView.setTextColor(0xFF2196F3.toInt())
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = progress
                    progressText.visibility = View.VISIBLE
                    val downloadedMb = String.format("%.1f", record.downloadSize / 1048576.0)
                    val totalMb = if (record.fileSize > 0) String.format("%.1f", record.fileSize / 1048576.0) else "?"
                    progressText.text = "$downloadedMb / $totalMb MB"
                    actionBtn.setImageResource(android.R.drawable.ic_media_pause)
                    actionBtn.contentDescription = "暂停"
                    actionBtn.setOnClickListener { onPauseClick(record) }
                }
                DownloadStatus.PAUSED -> {
                    statusView.text = "已暂停"
                    statusView.setTextColor(0xFFFFA000.toInt())
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = progress
                    progressText.visibility = View.VISIBLE
                    progressText.text = "$progress% (已暂停)"
                    actionBtn.setImageResource(android.R.drawable.ic_media_play)
                    actionBtn.contentDescription = "继续"
                    actionBtn.setOnClickListener { onResumeClick(record) }
                }
                DownloadStatus.COMPLETED -> {
                    statusView.text = "已完成"
                    statusView.setTextColor(0xFF4CAF50.toInt())
                    progressBar.visibility = View.GONE
                    progressText.visibility = View.GONE
                    actionBtn.setImageResource(android.R.drawable.ic_menu_view)
                    actionBtn.contentDescription = "打开"
                    actionBtn.setOnClickListener { onOpenClick(record) }
                }
                DownloadStatus.FAILED -> {
                    statusView.text = "下载失败"
                    statusView.setTextColor(0xFFF44336.toInt())
                    progressBar.visibility = View.GONE
                    progressText.visibility = View.VISIBLE
                    progressText.text = "点击重试 / 长按删除"
                    actionBtn.setImageResource(android.R.drawable.ic_menu_revert)
                    actionBtn.contentDescription = "重试"
                    actionBtn.setOnClickListener { onRetryClick(record) }
                }
            }

            if (record.fileSize > 0 && record.status != DownloadStatus.COMPLETED) {
                sizeView.text = String.format("%.1f MB", record.fileSize / 1048576.0)
            } else {
                sizeView.text = ""
            }

            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            timeView.text = sdf.format(Date(record.downloadTime))

            // P1#9.5: the "open downloads folder" shortcut. A secondary icon
            // is easier to discover than burying it in a long-press menu.
            folderBtn.setOnClickListener { onOpenFolderClick(record) }
            folderBtn.visibility = View.VISIBLE

            // Row-level tap target:
            //   COMPLETED -> open with system app
            //   FAILED    -> retry the download (P0#2: the row used to
            //               say "tap to retry" but the listener was
            //               never wired)
            //   else      -> no row tap (use the action button or the
            //               long-press menu for destructive ops)
            when (record.status) {
                DownloadStatus.COMPLETED -> {
                    itemView.isClickable = true
                    itemView.isFocusable = true
                    itemView.setOnClickListener { onOpenClick(record) }
                }
                DownloadStatus.FAILED -> {
                    itemView.isClickable = true
                    itemView.isFocusable = true
                    itemView.setOnClickListener { onRetryClick(record) }
                }
                else -> {
                    itemView.isClickable = false
                    itemView.isFocusable = false
                    itemView.setOnClickListener(null)
                }
            }

            // Long-press anywhere on the row always offers "delete" so
            // the action icon doesn't have to be overloaded with
            // destructive ops.
            itemView.setOnLongClickListener {
                onDeleteClick(record)
                true
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DownloadRecord>() {
        override fun areItemsTheSame(oldItem: DownloadRecord, newItem: DownloadRecord): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: DownloadRecord, newItem: DownloadRecord): Boolean {
            return oldItem == newItem
        }
    }
}
