package com.cseini.byd.karaoke

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cseini.byd.karaoke.data.RecordingItem
import com.cseini.byd.karaoke.data.RecordingStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 역대 랭킹: 채점된 곡을 점수 내림차순으로 보여준다(전체 역대). */
class RankingActivity : AppCompatActivity() {

    private lateinit var store: RecordingStore
    private lateinit var empty: TextView
    private val adapter = RankingAdapter(onPlay = { startActivity(PlaybackActivity.replayIntent(this, it)) })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ranking)

        store = RecordingStore(this)
        empty = findViewById(R.id.ranking_empty)
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<RecyclerView>(R.id.ranking_list).apply {
            layoutManager = LinearLayoutManager(this@RankingActivity)
            adapter = this@RankingActivity.adapter
        }
    }

    override fun onResume() {
        super.onResume()
        val ranked = store.all()
            .filter { it.score >= 0 }
            .sortedWith(compareByDescending<RecordingItem> { it.score }.thenByDescending { it.at })
        adapter.submit(ranked)
        empty.visibility = if (ranked.isEmpty()) View.VISIBLE else View.GONE
    }
}

private class RankingAdapter(val onPlay: (RecordingItem) -> Unit) :
    RecyclerView.Adapter<RankingAdapter.VH>() {

    private val items = ArrayList<RecordingItem>()
    private val dateFmt = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)

    fun submit(list: List<RecordingItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val badge: TextView = v.findViewById(R.id.rank_badge)
        val title: TextView = v.findViewById(R.id.rank_title)
        val date: TextView = v.findViewById(R.id.rank_date)
        val score: TextView = v.findViewById(R.id.rank_score)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_ranking, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val rank = position + 1
        val ctx = holder.itemView.context
        holder.badge.text = "$rank"
        val badgeColor = when (rank) {
            1 -> R.color.tj_gold
            2 -> R.color.tj_silver
            3 -> R.color.tj_bronze
            else -> R.color.tj_panel_light
        }
        holder.badge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, badgeColor))
        holder.badge.setTextColor(
            ContextCompat.getColor(ctx, if (rank <= 3) R.color.tj_bg_deep else R.color.tj_text_primary)
        )
        holder.title.text = item.title
        holder.date.text = dateFmt.format(Date(item.at))
        holder.score.text = "${item.score}점"
        holder.itemView.setOnClickListener { onPlay(item) }
    }
}
