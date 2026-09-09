package com.cseini.byd.karaoke

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.cseini.byd.karaoke.data.QueueItem

/** 검색 결과. 기본은 목록형(item_result), 일반 유튜브 검색 모드면 썸네일 카드(item_result_card). */
class ResultAdapter(
    private val onReserve: (QueueItem) -> Unit,
    private val onPlayNow: (QueueItem) -> Unit,
) : RecyclerView.Adapter<ResultAdapter.VH>() {

    private val items = ArrayList<QueueItem>()
    private var general = false   // true = 썸네일 카드

    fun submit(list: List<QueueItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** 일반 유튜브 검색 모드(썸네일 카드) on/off. 바뀌면 뷰타입이 달라져 다시 그린다. */
    fun setGeneral(g: Boolean) {
        if (general == g) return
        general = g
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.result_title)
        val channel: TextView = v.findViewById(R.id.result_channel)
        val reserve: Button? = v.findViewById(R.id.btn_reserve)
        val playNow: Button? = v.findViewById(R.id.btn_play_now)
        val thumb: ImageView? = v.findViewById(R.id.result_thumb)   // 카드 모드에만 존재
    }

    override fun getItemViewType(position: Int): Int = if (general) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == 1) R.layout.item_result_card else R.layout.item_result
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.channel.text = item.channel
        holder.thumb?.load("https://img.youtube.com/vi/${item.videoId}/mqdefault.jpg")
        holder.reserve?.setOnClickListener { onReserve(item) }
        holder.playNow?.setOnClickListener { onPlayNow(item) }
        holder.itemView.setOnClickListener { onPlayNow(item) }
    }

    override fun getItemCount(): Int = items.size
}
