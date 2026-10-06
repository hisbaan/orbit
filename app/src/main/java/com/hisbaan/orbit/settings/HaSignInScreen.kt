package com.hisbaan.orbit.settings

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Home Assistant's own login page (password, 2FA, whatever the user has set up) in a web
 * view, as HA's companion app does it. The redirect back to Orbit carries the auth code and
 * is caught here before it loads.
 */
@SuppressLint("SetJavaScriptEnabled") // HA's login page is a JavaScript app
@Composable
fun HaSignInScreen(signIn: HaSignIn, onNavigation: (String) -> Boolean, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onCancel)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sign in to Home Assistant", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            onNavigation(request.url.toString())

                        // Backstop for navigations that skip shouldOverrideUrlLoading.
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            if (onNavigation(url)) view.stopLoading()
                        }
                    }
                    loadUrl(signIn.authorizeUrl)
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}
