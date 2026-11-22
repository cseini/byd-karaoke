package com.cseini.byd.karaoke

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.LinearLayout
import android.widget.TextView
import com.cseini.byd.karaoke.data.SearchHistoryStore

/**
 * 검색창 최근 검색어 드롭다운 — 줄마다 ↖(검색창에 불러와 수정) + ✕(개별 삭제), 맨 아래 "모두 지우기".
 * 목록은 앱이 직접 채우므로 필터는 아무것도 거르지 않는다(기본 필터는 따로 복사본을 들고 있어 새 목록이 안 보인다).
 */
class SearchHistoryAdapter(private val ctx: Context) :
    ArrayAdapter<String>(ctx, android.R.layout.simple_dropdown_item_1line) {

    companion object { const val CLEAR_ALL = "\u0000CLEAR_ALL" }

    /** ↖ — 검색창에 글자만 채운다. */
    var onFill: ((String) -> Unit)? = null

    private val passthrough = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults =
            FilterResults().apply { count = this@SearchHistoryAdapter.count }

        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            notifyDataSetChanged()
        }
    }

    override fun getFilter(): Filter = passthrough

    fun replace(items: List<String>) {
        setNotifyOnChange(false)
        clear()
        addAll(items.filter { it.isNotBlank() })
        if (items.isNotEmpty()) add(CLEAR_ALL)
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val item = getItem(position) ?: return View(ctx)
        val d = ctx.resources.displayMetrics.density
        fun pad(v: View, h: Int, vv: Int) = v.setPadding((h * d).toInt(), (vv * d).toInt(), (h * d).toInt(), (vv * d).toInt())
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), 0, 0, 0)
            minimumHeight = (48 * d).toInt()
        }
        val tv = TextView(ctx).apply {
            text = if (item == CLEAR_ALL) "최근 검색어 모두 지우기" else item
            textSize = 16f
            setTextColor(ctx.getColor(if (item == CLEAR_ALL) R.color.tj_text_hint else R.color.tj_text_primary))
        }
        row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (item != CLEAR_ALL) {
            row.addView(TextView(ctx).apply {
                text = "↖"; textSize = 18f; setTextColor(ctx.getColor(R.color.tj_text_secondary))
                gravity = Gravity.CENTER; pad(this, 14, 10)
                setOnClickListener { onFill?.invoke(item) }
            })
            row.addView(TextView(ctx).apply {
                text = "✕"; textSize = 16f; setTextColor(ctx.getColor(R.color.tj_text_hint))
                gravity = Gravity.CENTER; pad(this, 14, 10)
                setOnClickListener {
                    SearchHistoryStore.remove(ctx, item)
                    replace(SearchHistoryStore.recent(ctx))
                }
            })
        }
        return row
    }
}
