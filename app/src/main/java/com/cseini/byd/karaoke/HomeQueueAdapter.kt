package com.cseini.byd.karaoke

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cseini.byd.karaoke.data.QueueItem

/** 검색 홈의 예약 목록 — 폰(리모컨)으로 넣은 곡이 재생 전에도 보이도록. */
class HomeQueueAdapter(
    val onPlay: (QueueItem) -> Unit,
    val onDelete: (QueueItem) -> Unit,
) : RecyclerView.Adapter<HomeQueueAdapter.VH>() {

    private val items = ArrayList<QueueItem>()

    fun submit(list: List<QueueItem>) {
        if (items == list) return          // 주기 갱신이라 변화 없을 때 깜빡임 방지
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.q_title)
        val delete: TextView = v.findViewById(R.id.q_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_queue_side, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = "${position + 1}. ${item.title}"
        holder.title.setOnClickListener { onPlay(item) }   // 제목 탭 = 바로 부르기
        holder.delete.setOnClickListener { onDelete(item) }
    }
}
