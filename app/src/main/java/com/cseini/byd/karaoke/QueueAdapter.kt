package com.cseini.byd.karaoke

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cseini.byd.karaoke.data.QueueItem

/** 예약 대기열 리스트. 각 항목 삭제 가능. */
class QueueAdapter(
    private val onRemove: (Int) -> Unit,
) : RecyclerView.Adapter<QueueAdapter.VH>() {

    private val items = ArrayList<QueueItem>()

    fun submit(list: List<QueueItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val index: TextView = v.findViewById(R.id.queue_index)
        val title: TextView = v.findViewById(R.id.queue_title)
        val remove: Button = v.findViewById(R.id.btn_remove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.index.text = (position + 1).toString()
        holder.title.text = item.title
        holder.remove.setOnClickListener { onRemove(holder.bindingAdapterPosition) }
    }

    override fun getItemCount(): Int = items.size
}
