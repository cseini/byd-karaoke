package com.cseini.byd.karaoke

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.cseini.byd.karaoke.data.RecordingItem

/** 녹음함(독립 Activity 호스트). 실제 로직은 [RecordingsScreen]. */
class RecordingsActivity : AppCompatActivity(), ScreenHost {

    private lateinit var screen: RecordingsScreen

    override val embedded = false
    override fun onScreenBack() = finish()
    override fun onReplayRecording(item: RecordingItem) {
        startActivity(PlaybackActivity.replayIntent(this, item))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = layoutInflater.inflate(R.layout.activity_recordings, null)
        setContentView(root)
        screen = RecordingsScreen(root, this)
    }

    override fun onResume() {
        super.onResume()
        screen.refresh()
    }

    override fun onStop() {
        super.onStop()
        screen.destroy()
    }
}
