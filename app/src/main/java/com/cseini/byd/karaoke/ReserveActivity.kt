package com.cseini.byd.karaoke

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cseini.byd.karaoke.data.QueueItem
import com.cseini.byd.karaoke.data.QueueStore
import com.cseini.byd.karaoke.share.ReserveServer
import com.cseini.byd.karaoke.share.qrBitmap

/** 폰(리모컨) 예약 화면: 서버 켜기 → QR 안내 → 예약된 곡 목록(부르기/삭제). */
class ReserveActivity : AppCompatActivity() {

    private lateinit var queue: QueueStore
    private lateinit var serverBox: View
    private lateinit var qr: ImageView
    private lateinit var urlText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var queueEmpty: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val adapter = QueueAdapter(
        onPlay = { playAndRemove(it) },
        onDelete = { queue.removeByVideoId(it.videoId); refreshQueue() },
    )

    private val poll = object : Runnable {
        override fun run() { refreshQueue(); ui.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reserve)
        queue = QueueStore(this)
        serverBox = findViewById(R.id.server_box)
        qr = findViewById(R.id.reserve_qr)
        urlText = findViewById(R.id.reserve_url)
        toggleBtn = findViewById(R.id.btn_toggle)
        queueEmpty = findViewById(R.id.queue_empty)
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        toggleBtn.setOnClickListener { toggleServer() }
        findViewById<RecyclerView>(R.id.queue_list).apply {
            layoutManager = LinearLayoutManager(this@ReserveActivity)
            adapter = this@ReserveActivity.adapter
        }
        NavBar.wire(this, ReserveActivity::class.java)
        reflectServerState()
    }

    override fun onResume() {
        super.onResume()
        ui.post(poll)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(poll)
    }

    private fun toggleServer() {
        if (ReserveServer.isRunning()) {
            ReserveServer.stop()
            toast("예약 서버를 껐습니다")
        } else {
            val url = ReserveServer.start(this)
            if (url == null) {
                toast("네트워크에 연결돼 있지 않습니다. 폰 핫스팟에 차를 연결하세요.")
            }
        }
        reflectServerState()
    }

    private fun reflectServerState() {
        val url = ReserveServer.url
        if (ReserveServer.isRunning() && url != null) {
            toggleBtn.text = "예약 서버 끄기"
            serverBox.visibility = View.VISIBLE
            qr.setImageBitmap(qrBitmap(url, 400))
            urlText.text = url
        } else {
            toggleBtn.text = "예약 서버 켜기"
            serverBox.visibility = View.GONE
        }
    }

    private fun refreshQueue() {
        queue.reload()
        val items = queue.all()
        adapter.submit(items)
        queueEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun playAndRemove(item: QueueItem) {
        queue.removeByVideoId(item.videoId)
        startActivity(PlaybackActivity.intent(this, item.videoId, item.title, fromQueue = true))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

private class QueueAdapter(
    val onPlay: (QueueItem) -> Unit,
    val onDelete: (QueueItem) -> Unit,
) : RecyclerView.Adapter<QueueAdapter.VH>() {

    private val items = ArrayList<QueueItem>()

    fun submit(list: List<QueueItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.q_num)
        val title: TextView = v.findViewById(R.id.q_title)
        val play: Button = v.findViewById(R.id.q_play)
        val delete: Button = v.findViewById(R.id.q_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_queue_reserve, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.num.text = "${position + 1}"
        holder.title.text = item.title
        holder.play.setOnClickListener { onPlay(item) }
        holder.delete.setOnClickListener { onDelete(item) }
    }
}
