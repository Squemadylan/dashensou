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
            binding.resultTitle.text = result.title
            binding.resultDesc.text = result.description
            binding.resultSource.text = result.sourceName
            binding.resultSize.text = result.size
            binding.resultDate.text = result.date

            binding.resultDownloadBtn.setOnClickListener {
                onDownloadClick(result)
            }
        }
    }
}
