package com.cseini.byd.karaoke

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.cseini.byd.karaoke.data.RecordingItem

/** 최근 부른 노래 타일(썸네일). 누르면 바로 부르기. */
class HistoryAdapter(val onPlay: (RecordingItem) -> Unit) :
    RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val items = ArrayList<RecordingItem>()

    fun submit(list: List<RecordingItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.tile_thumb)
        val title: TextView = v.findViewById(R.id.tile_title)
        val score: TextView = v.findViewById(R.id.tile_score)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_history_tile, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.thumb.load("https://img.youtube.com/vi/${item.videoId}/mqdefault.jpg")
        if (item.score >= 0) {
            holder.score.visibility = View.VISIBLE
            holder.score.text = "${item.score}점"
        } else {
            holder.score.visibility = View.GONE
        }
        holder.itemView.setOnClickListener { onPlay(item) }
    }
}
