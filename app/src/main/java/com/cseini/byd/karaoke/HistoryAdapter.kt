package com.cseini.byd.karaoke

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.cseini.byd.karaoke.data.PlayHistoryItem

/** 최근 부른 노래 앨범 카드(썸네일·곡명·가수·점수·날짜). 누르면 바로 부르기. */
class HistoryAdapter(
    val onPlay: (PlayHistoryItem) -> Unit,
    val onScore: (PlayHistoryItem) -> Unit,
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val items = ArrayList<PlayHistoryItem>()

    // 가로 캐러셀에서 카드 폭을 고정한다(0=셀 폭 따름, 세로 그리드 등). 썸네일 높이는 방향별로 코드에서 준다.
    var fixedWidthDp: Int = 0
    var fixedThumbHeightDp: Int = 0

    fun submit(list: List<PlayHistoryItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.tile_thumb)
        val score: TextView = v.findViewById(R.id.tile_score)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_history_tile, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.thumb.load("https://img.youtube.com/vi/${item.videoId}/mqdefault.jpg")
        if (item.score >= 0) {
            holder.score.visibility = View.VISIBLE
            holder.score.text = "👍 ${item.score}점"
            holder.score.setOnClickListener { onScore(item) }
        } else {
            holder.score.visibility = View.GONE
        }
        holder.itemView.setOnClickListener { onPlay(item) }
        val density = holder.itemView.resources.displayMetrics.density
        val lp = holder.itemView.layoutParams
        lp.width = if (fixedWidthDp > 0) (fixedWidthDp * density).toInt()
        else ViewGroup.LayoutParams.MATCH_PARENT
        holder.itemView.layoutParams = lp
        if (fixedThumbHeightDp > 0) {
            val tp = holder.thumb.layoutParams
            tp.height = (fixedThumbHeightDp * density).toInt()
            holder.thumb.layoutParams = tp
        }
    }
}
