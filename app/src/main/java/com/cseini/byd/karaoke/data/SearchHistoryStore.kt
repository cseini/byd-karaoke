package com.cseini.byd.karaoke.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 최근 검색어 — SharedPreferences(최대 20, 최신 앞). */
object SearchHistoryStore {
    private const val PREF = "karaoke_search_history"
    private const val KEY = "recent"
    private const val MAX = 20
    private val gson = Gson()
    private val type = object : TypeToken<List<String>>() {}.type

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun recent(ctx: Context): List<String> =
        runCatching { gson.fromJson<List<String>>(prefs(ctx).getString(KEY, null), type) }.getOrNull() ?: emptyList()

    fun add(ctx: Context, q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        val list = recent(ctx).toMutableList()
        list.removeAll { it.equals(query, ignoreCase = true) }
        list.add(0, query)
        while (list.size > MAX) list.removeAt(list.size - 1)
        prefs(ctx).edit().putString(KEY, gson.toJson(list)).apply()
    }

    fun remove(ctx: Context, q: String) {
        val list = recent(ctx).filterNot { it.equals(q, ignoreCase = true) }
        prefs(ctx).edit().putString(KEY, gson.toJson(list)).apply()
    }

    fun clear(ctx: Context) { prefs(ctx).edit().remove(KEY).apply() }
}
