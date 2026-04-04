package com.ghostwan.podcasto

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.ghostwan.podcasto.ui.theme.PodcastoTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    val openPlayer = mutableStateOf(false)
    val sharedYouTubeUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            PodcastoTheme {
                PodcastoNavHost(
                    openPlayerRequest = openPlayer,
                    sharedYouTubeUrl = sharedYouTubeUrl,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("OPEN_PLAYER", false)) {
            openPlayer.value = true
        }
        // Handle shared text (e.g. YouTube URL from share menu) — only in full flavor
        if (BuildConfig.YOUTUBE_ENABLED && intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null) {
                // Extract URL from shared text (may contain extra text around the URL)
                val urlRegex = Regex("""https?://[^\s]+""")
                val url = urlRegex.find(sharedText)?.value
                if (url != null && com.ghostwan.podcasto.data.remote.YouTubeExtractor.isYouTubeChannelUrl(url)) {
                    sharedYouTubeUrl.value = url
                }
            }
        }
    }
}
