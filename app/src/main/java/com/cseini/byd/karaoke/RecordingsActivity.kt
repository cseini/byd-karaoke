package com.cseini.byd.karaoke

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cseini.byd.karaoke.data.RecordingItem
import com.cseini.byd.karaoke.data.RecordingStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 녹음함: 부른 노래 목록 → 반주와 함께 다시 듣기 / 삭제. */
class RecordingsActivity : AppCompatActivity() {

    private lateinit var store: RecordingStore
    private lateinit var empty: TextView
    private lateinit var deleteSelectedBtn: Button
    private lateinit var selectAllBtn: Button
    private val adapter = RecordingAdapter(
        onPlay = { startActivity(PlaybackActivity.replayIntent(this, it)) },
        onShare = { shareRecording(it) },
        onDelete = { confirmDelete(it) },
        onSelectionChanged = { updateSelectionBar(it) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings)

        store = RecordingStore(this)
        empty = findViewById(R.id.empty)
        deleteSelectedBtn = findViewById(R.id.btn_delete_selected)
        selectAllBtn = findViewById(R.id.btn_select_all)
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        selectAllBtn.setOnClickListener { adapter.toggleSelectAll() }
        deleteSelectedBtn.setOnClickListener { confirmDeleteSelected() }
        findViewById<RecyclerView>(R.id.recordings).apply {
            layoutManager = LinearLayoutManager(this@RecordingsActivity)
            adapter = this@RecordingsActivity.adapter
        }
        NavBar.wire(this, RecordingsActivity::class.java)
    }

    private fun updateSelectionBar(count: Int) {
        deleteSelectedBtn.isEnabled = count > 0
        deleteSelectedBtn.text = if (count > 0) "선택 삭제 ($count)" else "선택 삭제"
        val total = adapter.itemCount
        selectAllBtn.text = if (count > 0 && count == total) "전체 해제" else "전체 선택"
    }

    private fun confirmDeleteSelected() {
        val sel = adapter.selectedItems()
        if (sel.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("녹음 삭제")
            .setMessage("선택한 ${sel.size}개 녹음을 삭제할까요?")
            .setPositiveButton("삭제") { _, _ -> store.removeItems(sel); refresh() }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val items = store.all()
        adapter.submit(items)   // 선택 상태 초기화 포함
        updateSelectionBar(0)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun shareRecording(item: RecordingItem) {
        val src = java.io.File(item.path)
        if (!src.exists()) {
            android.widget.Toast.makeText(this, "파일이 없습니다", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
            val dst = java.io.File(dir, src.name)
            src.copyTo(dst, overwrite = true)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", dst)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "audio/x-wav"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(send, "녹음 공유"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "공유 실패: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(item: RecordingItem) {
        AlertDialog.Builder(this)
            .setTitle("녹음 삭제")
            .setMessage("'${item.title}' 녹음을 삭제할까요?")
            .setPositiveButton("삭제") { _, _ -> store.remove(item); refresh() }
            .setNegativeButton("취소", null)
            .show()
    }
}

private class RecordingAdapter(
    val onPlay: (RecordingItem) -> Unit,
    val onShare: (RecordingItem) -> Unit,
    val onDelete: (RecordingItem) -> Unit,
    val onSelectionChanged: (Int) -> Unit,
) : RecyclerView.Adapter<RecordingAdapter.VH>() {

    private val items = ArrayList<RecordingItem>()
    private val selected = HashSet<String>()   // 선택된 항목 path 집합
    private val dateFmt = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)

    fun submit(list: List<RecordingItem>) {
        items.clear()
        items.addAll(list)
        selected.clear()
        notifyDataSetChanged()
    }

    fun selectedItems(): List<RecordingItem> = items.filter { it.path in selected }

    /** 전부 선택 ↔ 전부 해제 토글. */
    fun toggleSelectAll() {
        if (selected.size == items.size) selected.clear()
        else { selected.clear(); items.forEach { selected.add(it.path) } }
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val check: CheckBox = v.findViewById(R.id.rec_check)
        val title: TextView = v.findViewById(R.id.rec_title)
        val sub: TextView = v.findViewById(R.id.rec_sub)
        val play: Button = v.findViewById(R.id.btn_play)
        val share: Button = v.findViewById(R.id.btn_share)
        val delete: Button = v.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        val scoreText = if (item.score >= 0) "🎯 ${item.score}점" else "채점 없음"
        holder.sub.text = "$scoreText · ${dateFmt.format(Date(item.at))}"
        // 재활용 시 리스너가 남아 잘못 토글되지 않도록 먼저 해제하고 상태만 반영
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = item.path in selected
        holder.check.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selected.add(item.path) else selected.remove(item.path)
            onSelectionChanged(selected.size)
        }
        holder.play.setOnClickListener { onPlay(item) }
        holder.share.setOnClickListener { onShare(item) }
        holder.delete.setOnClickListener { onDelete(item) }
        holder.itemView.setOnClickListener { onPlay(item) }
    }
}
