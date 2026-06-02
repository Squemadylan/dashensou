package com.dashensou.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.databinding.ItemSearchResultBinding
import com.dashensou.app.util.NetDiskUtils

class SearchResultAdapter(
    private val results: List<SearchResult>,
    private val onDownloadClick: (SearchResult) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val result = results[position]
        holder.bind(result)
    }

    override fun getItemCount(): Int = results.size

    inner class ViewHolder(private val binding: ItemSearchResultBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(result: SearchResult) {
            binding.title.text = result.title
            binding.description.text = result.description
            binding.netdiskType.text = NetDiskUtils.getNetDiskTypeName(result.netDiskType)
            binding.size.text = result.size
            binding.date.text = result.date

            binding.downloadBtn.setOnClickListener {
                onDownloadClick(result)
            }
        }
    }
}
