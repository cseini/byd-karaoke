package com.cseini.byd.karaoke

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.cseini.byd.karaoke.data.RecordingItem

/** 역대 랭킹(독립 Activity 호스트). 실제 로직은 [RankingScreen]. */
class RankingActivity : AppCompatActivity(), ScreenHost {

    private lateinit var screen: RankingScreen

    override val embedded = false
    override fun onScreenBack() = finish()
    override fun onReplayRecording(item: RecordingItem) {
        startActivity(PlaybackActivity.replayIntent(this, item))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = layoutInflater.inflate(R.layout.activity_ranking, null)
        setContentView(root)
        screen = RankingScreen(root, this)
    }

    override fun onResume() {
        super.onResume()
        screen.refresh()
    }
}
