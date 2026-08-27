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

    // 가로 캐러셀에서 카드 폭을 고정한다(그 외엔 그리드/리스트 셀 폭을 따름).
    var fixedWidthDp: Int = 0

    fun submit(list: List<PlayHistoryItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.tile_thumb)
        val title: TextView = v.findViewById(R.id.tile_title)
        val artist: TextView = v.findViewById(R.id.tile_artist)
        val date: TextView = v.findViewById(R.id.tile_date)
        val score: TextView = v.findViewById(R.id.tile_score)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_history_tile, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val (song, artist) = splitTitle(item.title)
        holder.title.text = song
        holder.artist.text = artist
        holder.date.text = if (item.at > 0) DATE_FMT.format(java.util.Date(item.at)) else ""
        holder.thumb.load("https://img.youtube.com/vi/${item.videoId}/mqdefault.jpg")
        if (item.score >= 0) {
            holder.score.visibility = View.VISIBLE
            holder.score.text = "👍 ${item.score}점"
            holder.score.setOnClickListener { onScore(item) }
        } else {
            holder.score.visibility = View.GONE
        }
        holder.itemView.setOnClickListener { onPlay(item) }
        val lp = holder.itemView.layoutParams
        lp.width = if (fixedWidthDp > 0)
            (fixedWidthDp * holder.itemView.resources.displayMetrics.density).toInt()
        else ViewGroup.LayoutParams.MATCH_PARENT
        holder.itemView.layoutParams = lp
    }

    companion object {
        private val DATE_FMT = java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.KOREA)

        // "[TJ노래방] 곡명 - 가수 / TJ Karaoke" 같은 제목에서 곡명·가수를 뽑는다.
        // 형식이 안 맞으면 통째로 곡명, 가수는 빈칸.
        private fun splitTitle(raw: String): Pair<String, String> {
            var t = raw.trim()
            t = t.replace(Regex("^\\[[^\\]]*]\\s*"), "")      // 앞의 [TJ노래방] 등 제거
            t = t.substringBefore(" / ").trim()                // 뒤의 / TJ Karaoke 제거
            val dash = t.indexOf(" - ")
            return if (dash > 0) t.substring(0, dash).trim() to t.substring(dash + 3).trim()
            else t to ""
        }
    }
}
