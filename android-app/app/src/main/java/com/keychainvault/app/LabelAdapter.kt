package com.keychainvault.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Simple list of entry labels (TOTP or password entries), with filtering. */
class LabelAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit
) :
    RecyclerView.Adapter<LabelAdapter.Holder>() {

    private val allItems = mutableListOf<String>()
    private val visibleIndices = mutableListOf<Int>()

    fun setItems(labels: List<String>) {
        allItems.clear()
        allItems.addAll(labels)
        // Reset filter
        visibleIndices.clear()
        for (i in labels.indices) visibleIndices.add(i)
        notifyDataSetChanged()
    }

    /** Filter by substring (case-insensitive). Empty query shows all. */
    fun filter(query: String) {
        val q = query.trim().lowercase()
        visibleIndices.clear()
        for (i in allItems.indices) {
            if (q.isEmpty() || allItems[i].lowercase().contains(q)) {
                visibleIndices.add(i)
            }
        }
        notifyDataSetChanged()
    }

    fun clear() {
        allItems.clear()
        visibleIndices.clear()
        notifyDataSetChanged()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.itemLabelText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_label, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val origIndex = visibleIndices[position]
        holder.label.text = allItems[origIndex]
        holder.itemView.setOnClickListener { onClick(origIndex) }
        holder.itemView.setOnLongClickListener {
            onLongClick(origIndex)
            true
        }
    }

    override fun getItemCount(): Int = visibleIndices.size
}
