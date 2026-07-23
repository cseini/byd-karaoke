package com.cseini.byd.karaoke

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cseini.byd.karaoke.data.QueueItem

/** 검색 결과 리스트. 각 항목: 예약 / 바로 부르기. */
class ResultAdapter(
    private val onReserve: (QueueItem) -> Unit,
    private val onPlayNow: (QueueItem) -> Unit,
) : RecyclerView.Adapter<ResultAdapter.VH>() {

    private val items = ArrayList<QueueItem>()

    fun submit(list: List<QueueItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.result_title)
        val channel: TextView = v.findViewById(R.id.result_channel)
        val reserve: Button = v.findViewById(R.id.btn_reserve)
        val playNow: Button = v.findViewById(R.id.btn_play_now)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_result, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.channel.text = item.channel
        holder.reserve.setOnClickListener { onReserve(item) }
        holder.playNow.setOnClickListener { onPlayNow(item) }
        holder.itemView.setOnClickListener { onPlayNow(item) }
    }

    override fun getItemCount(): Int = items.size
}
