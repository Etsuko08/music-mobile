/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.ui.screens.settings

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import com.convx.music.ui.ios.compat.AppTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.convx.music.LocalPlayerAwareWindowInsets
import com.convx.music.R
import com.convx.music.constants.DiscordTokenKey
import com.convx.music.ui.component.FrostedTopBar
import com.convx.music.ui.utils.backToMain
import com.convx.music.utils.rememberPreference
import org.json.JSONTokener

// Discord's web app removes window.localStorage from the page; an about:blank iframe
// shares the origin and still exposes the real storage.
private const val DISCORD_TOKEN_SCRIPT = """(() => {
    const read = () => {
        try { const v = window.localStorage.getItem('token'); if (v) return v; } catch (_) {}
        let frame;
        try {
            frame = document.body.appendChild(document.createElement('iframe'));
            return frame.contentWindow.localStorage.getItem('token') || '';
        } catch (_) { return ''; } finally { if (frame) frame.remove(); }
    };
    const value = read();
    if (!value) return '';
    try { return JSON.parse(value); } catch (_) { return value; }
})()"""

private const val MOTOROLA = "motorola"
private const val SAMSUNG_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; SM-S921U; Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscordLoginScreen(navController: NavController) {
    val discordTokenPref = rememberPreference(DiscordTokenKey, "")
    var webView by remember { mutableStateOf<WebView?>(null) }
    var tokenCaptured by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
            webView = null
        }
    }

    // Keep Chromium's hardware-accelerated surface out of Haze/Glass backdrop captures to eliminate rapid flickering.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colorScheme.background),
    ) {
        FrostedTopBar(
            title = stringResource(R.string.action_login),
            titleAlpha = 1f,
            onBack = navController::navigateUp,
            onBackLongClick = navController::backToMain,
        )

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                ),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(false)
                    // Discord's 2FA challenge (and its captcha widget) runs on a
                    // separate origin and needs third-party cookies to complete.
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    // Motorola WebView needs the Samsung user agent to complete Discord login.
                    if (Build.MANUFACTURER.equals(MOTOROLA, ignoreCase = true)) {
                        settings.userAgentString = SAMSUNG_USER_AGENT
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() }
                            if (tokenCaptured || uri?.host != "discord.com" || uri.path?.trimEnd('/') != "/app") return

                            var attempts = 0
                            val captureTask = object : Runnable {
                                override fun run() {
                                    if (tokenCaptured || view.url?.let { current ->
                                            runCatching { Uri.parse(current) }.getOrNull()?.let {
                                                it.host == "discord.com" && it.path?.trimEnd('/') == "/app"
                                            }
                                        } != true
                                    ) return

                                    attempts++
                                    view.evaluateJavascript(DISCORD_TOKEN_SCRIPT) { encodedValue ->
                                        if (tokenCaptured) return@evaluateJavascript
                                        val storedValue = runCatching {
                                            JSONTokener(encodedValue.orEmpty()).nextValue() as? String
                                        }.getOrNull().orEmpty()
                                        val token = runCatching {
                                            JSONTokener(storedValue).nextValue() as? String
                                        }.getOrNull() ?: storedValue.removeSurrounding("\"")
                                        if (token.isNotBlank() && token != "null" && token != "undefined") {
                                            tokenCaptured = true
                                            discordTokenPref.value = token
                                            navController.navigateUp()
                                        } else if (attempts < 12) {
                                            view.postDelayed(this, 500)
                                        }
                                    }
                                }
                            }
                            view.post(captureTask)
                        }
                    }

                    webView = this
                    loadUrl("https://discord.com/login")
                }
            }
        )
    }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }
}
