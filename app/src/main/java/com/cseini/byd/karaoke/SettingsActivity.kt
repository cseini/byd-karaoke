package com.cseini.byd.karaoke

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.cseini.byd.karaoke.data.RecordingItem

/** 설정(독립 Activity 호스트). 실제 로직은 [SettingsScreen]. */
class SettingsActivity : AppCompatActivity(), ScreenHost {

    private lateinit var screen: SettingsScreen

    override val embedded = false
    override fun onScreenBack() = finish()
    override fun onReplayRecording(item: RecordingItem) {}   // 설정에는 재생 없음

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = layoutInflater.inflate(R.layout.activity_settings, null)
        setContentView(root)
        screen = SettingsScreen(root, this)
    }
}
