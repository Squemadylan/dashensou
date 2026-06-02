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
    private val onDeleteClick: (DownloadRecord) -> Unit
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

        fun bind(record: DownloadRecord) {
            titleView.text = record.title

            val progress = if (record.fileSize > 0) {
                ((record.downloadSize.toDouble() / record.fileSize) * 100).toInt().coerceIn(0, 100)
            } else 0

            when (record.status) {
                DownloadStatus.PENDING -> {
                    statusView.text = "等待中"
                    statusView.setTextColor(0xFFFFA000.toInt())
                    progressBar.visibility = View.GONE
                    progressText.visibility = View.GONE
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
                }
                DownloadStatus.PAUSED -> {
                    statusView.text = "已暂停"
                    statusView.setTextColor(0xFFFFA000.toInt())
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = progress
                    progressText.visibility = View.VISIBLE
                    progressText.text = "$progress% (已暂停)"
                }
                DownloadStatus.COMPLETED -> {
                    statusView.text = "已完成"
                    statusView.setTextColor(0xFF4CAF50.toInt())
                    progressBar.visibility = View.GONE
                    progressText.visibility = View.GONE
                }
                DownloadStatus.FAILED -> {
                    statusView.text = "下载失败"
                    statusView.setTextColor(0xFFF44336.toInt())
                    progressBar.visibility = View.GONE
                    progressText.visibility = View.VISIBLE
                    progressText.text = "点击重试"
                }
            }

            if (record.fileSize > 0 && record.status != DownloadStatus.COMPLETED) {
                sizeView.text = String.format("%.1f MB", record.fileSize / 1048576.0)
            } else {
                sizeView.text = ""
            }

            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            timeView.text = sdf.format(Date(record.downloadTime))

            actionBtn.setOnClickListener {
                onDeleteClick(record)
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
