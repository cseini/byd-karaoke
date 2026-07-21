package com.cseini.byd.karaoke

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cseini.byd.karaoke.data.QueueStore

/** 예약 대기열 화면. 삭제·전체삭제·첫 곡부터 재생. */
class QueueActivity : AppCompatActivity() {

    private lateinit var queue: QueueStore
    private lateinit var emptyView: TextView
    private val adapter = QueueAdapter(onRemove = { removeAt(it) })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_queue)
        queue = QueueStore(this)
        emptyView = findViewById(R.id.queue_empty)

        findViewById<RecyclerView>(R.id.queue_list).apply {
            layoutManager = LinearLayoutManager(this@QueueActivity)
            adapter = this@QueueActivity.adapter
        }

        findViewById<Button>(R.id.btn_play_first).setOnClickListener { playFirst() }
        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            queue.clear(); refresh(); toast("대기열을 비웠습니다")
        }
        NavBar.wire(this, QueueActivity::class.java)
        refresh()
    }

    private fun refresh() {
        val items = queue.all()
        adapter.submit(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun removeAt(index: Int) {
        queue.removeAt(index)
        refresh()
    }

    private fun playFirst() {
        val first = queue.pollFirst()
        if (first == null) { toast("예약된 곡이 없습니다"); return }
        startActivity(PlaybackActivity.intent(this, first.videoId, first.title, fromQueue = true))
        finish()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
