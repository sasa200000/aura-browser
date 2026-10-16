package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.browser.BrowserScreen
import com.example.ui.browser.BrowserViewModel
import com.example.ui.theme.AuraBrowserTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A link shared from another app must reach the browser, not be dropped.
        externalUrl.value = extractUrl(intent)
        setContent {
            AuraBrowserTheme(darkTheme = true) {
                val browserViewModel: BrowserViewModel = viewModel()
                Surface(modifier = Modifier.fillMaxSize()) {
                    BrowserScreen(viewModel = browserViewModel, externalUrl = externalUrl)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTask keeps one window; a newly shared link reuses it.
        externalUrl.value = extractUrl(intent)
    }

    private fun extractUrl(intent: Intent?): String? {
        if (intent == null) return null
        val action = intent.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return null
        intent.dataString?.takeIf { it.isNotBlank() }?.let { return it }
        @Suppress("DEPRECATION")
        return intent.getStringExtra(Intent.EXTRA_TEXT)
            ?.trim()
            ?.substringBefore(' ')
            ?.takeIf { it.startsWith("http") }
    }

    companion object {
        /** Latest URL handed to the app by another app; consumed by the browser screen. */
        val externalUrl = MutableStateFlow<String?>(null)
    }
}
