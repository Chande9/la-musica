/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Spotify login using an embedded WebView.
 * Loads Spotify's web login page, which supports all auth methods
 * (email/password, Facebook, Google, Apple). After successful login,
 * the WebView redirect to open.spotify.com is intercepted and the
 * sp_dc cookie is extracted to fetch an access token.
 *
 * Token acquisition uses TOTP (Time-based One-Time Password) generated
 * from a community-maintained shared secret, following the approach used
 * by the Spotube Spotify plugin. The token is fetched entirely in the
 * background using HttpURLConnection — no web player, no rate limit issues.
 *
 * Reference: https://github.com/sonic-liberation/spotube-plugin-spotify
 */

package com.metrolist.music.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.metrolist.music.utils.BrokenLogin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import com.metrolist.music.R
import com.metrolist.music.constants.SpotifyAccessTokenKey
import com.metrolist.music.constants.SpotifySpDcKey
import com.metrolist.music.constants.SpotifySpKeyKey
import com.metrolist.music.constants.SpotifyTokenExpiryKey
import com.metrolist.music.constants.SpotifyUserIdKey
import com.metrolist.music.constants.SpotifyUsernameKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.dataStore
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.SpotifyAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }
    var retryCount by remember { mutableIntStateOf(0) }
    val tokenFetchStarted = remember { AtomicBoolean(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.spotify_login)) },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
        )

        if (isLoading || isProcessing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val bl = BrokenLogin.nextId("login")
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()

                    WebView(ctx).apply {
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(false)
                        settings.userAgentString = USER_AGENT_DESKTOP


                        /*
                         * The WebView implementation is a separate, auto-updating system package.
                         * "It worked days ago and now the page is black" is very often that package
                         * updating underneath us, so its version is the first thing worth knowing —
                         * and it is not recoverable from any other line in a logcat.
                         */
                        BrokenLogin.i(
                            bl, "webview.create",
                            BrokenLogin.kv(
                                "webViewPackage" to BrokenLogin.trap(bl, "webview.package") {
                                    WebView.getCurrentWebViewPackage()?.let { "${it.packageName}/${it.versionName}" }
                                },
                                "userAgent" to BrokenLogin.shortUrl(settings.userAgentString, 110),
                                "javaScript" to settings.javaScriptEnabled,
                                "domStorage" to settings.domStorageEnabled,
                                "thirdPartyCookies" to cookieManager.acceptThirdPartyCookies(this),
                                "acceptCookie" to cookieManager.acceptCookie(),
                                "mixedContent" to settings.mixedContentMode,
                                // Platform dark inversion. Applied on top of a page that is
                                // already dark, this turns text dark-on-dark — a WebView that
                                // reports everything painted while showing the user black.
                                "algorithmicDarkening" to BrokenLogin.trap(bl, "webview.darkening") {
                                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                                        settings.isAlgorithmicDarkeningAllowed
                                    } else {
                                        "n/a<33"
                                    }
                                },
                                "uiNightMode" to BrokenLogin.trap(bl, "webview.nightMode") {
                                    ctx.resources.configuration.uiMode and
                                        android.content.res.Configuration.UI_MODE_NIGHT_MASK
                                },
                                "loginUrl" to BrokenLogin.shortUrl(SpotifyAuth.LOGIN_URL),
                            ),
                        )

                        /*
                         * Surfaces what the page itself reports. Without a WebChromeClient a page
                         * that fails entirely in JavaScript looks identical to one that rendered
                         * fine — which is exactly the black-screen case being chased here.
                         */
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                                if (message != null) {
                                    val op = when (message.messageLevel()) {
                                        ConsoleMessage.MessageLevel.ERROR -> "console.error"
                                        ConsoleMessage.MessageLevel.WARNING -> "console.warn"
                                        else -> "console"
                                    }
                                    val details = BrokenLogin.kv(
                                        "level" to message.messageLevel(),
                                        "line" to message.lineNumber(),
                                        "source" to BrokenLogin.shortUrl(message.sourceId(), 90),
                                        "msg" to message.message(),
                                    )
                                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                        BrokenLogin.e(bl, op, details)
                                    } else {
                                        BrokenLogin.d(bl, op, details)
                                    }
                                }
                                return true
                            }

                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                // A page that stalls partway shows up here as a progress that
                                // never reaches 100 despite onPageFinished having fired.
                                if (newProgress % 25 == 0) {
                                    BrokenLogin.d(bl, "progress", BrokenLogin.kv("percent" to newProgress))
                                }
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                // An empty or error title on a "finished" page is a strong signal
                                // the document is not the login form.
                                BrokenLogin.i(bl, "title", BrokenLogin.kv("title" to title))
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                Timber.d("SpotifyLogin: page started: $url")
                                BrokenLogin.i(bl, "page.started", BrokenLogin.kv("url" to BrokenLogin.shortUrl(url)))
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                // Not overridden before, so every network-level failure — including
                                // one on the main frame, which paints a blank view — was silent.
                                val isMainFrame = request?.isForMainFrame == true
                                val details = BrokenLogin.kv(
                                    "mainFrame" to isMainFrame,
                                    "code" to BrokenLogin.trap(bl, "error.code") { error?.errorCode },
                                    "desc" to BrokenLogin.trap(bl, "error.desc") { error?.description?.toString() },
                                    "method" to request?.method,
                                    "url" to BrokenLogin.shortUrl(request?.url?.toString()),
                                )
                                if (isMainFrame) BrokenLogin.e(bl, "page.error", details)
                                else BrokenLogin.w(bl, "resource.error", details)
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?,
                            ) {
                                val isMainFrame = request?.isForMainFrame == true
                                val details = BrokenLogin.kv(
                                    "mainFrame" to isMainFrame,
                                    "status" to errorResponse?.statusCode,
                                    "reason" to errorResponse?.reasonPhrase,
                                    "mime" to errorResponse?.mimeType,
                                    "url" to BrokenLogin.shortUrl(request?.url?.toString()),
                                )
                                if (isMainFrame) BrokenLogin.e(bl, "page.httpError", details)
                                else BrokenLogin.w(bl, "resource.httpError", details)
                            }

                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: SslErrorHandler?,
                                error: SslError?,
                            ) {
                                BrokenLogin.e(
                                    bl, "page.sslError",
                                    BrokenLogin.kv(
                                        "primaryError" to error?.primaryError,
                                        "url" to BrokenLogin.shortUrl(error?.url),
                                    ),
                                )
                                // Preserve the default (cancel): never weaken TLS to get a login through.
                                super.onReceivedSslError(view, handler, error)
                            }

                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?,
                            ): Boolean {
                                /*
                                 * The prime suspect for a black WebView. When the renderer dies the
                                 * view keeps its surface and simply paints nothing; returning false
                                 * (the default) also kills the hosting app process. Log it and
                                 * return true so the app survives and the trace makes it to logcat.
                                 */
                                BrokenLogin.e(
                                    bl, "renderer.gone",
                                    BrokenLogin.kv(
                                        "didCrash" to detail?.didCrash(),
                                        "rendererPriorityAtExit" to detail?.rendererPriorityAtExit(),
                                    ),
                                )
                                BrokenLogin.trap(bl, "renderer.destroy") { view?.destroy() }
                                return true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                Timber.d("SpotifyLogin: page finished: $url")
                                BrokenLogin.i(
                                    bl, "page.finished",
                                    BrokenLogin.kv(
                                        "url" to BrokenLogin.shortUrl(url),
                                        "progress" to BrokenLogin.trap(bl, "page.progress") { view?.progress },
                                        "contentHeight" to BrokenLogin.trap(bl, "page.height") { view?.contentHeight },
                                        "title" to BrokenLogin.trap(bl, "page.title") { view?.title },
                                    ) + " " + BrokenLogin.describeCookies(
                                        BrokenLogin.trap(bl, "page.cookies") {
                                            CookieManager.getInstance().getCookie("https://open.spotify.com")
                                        },
                                    ),
                                )
                                // Runs on every page: the collapsed layout is re-created by each
                                // navigation within the login flow, not just the first load.
                                applyLoginLayoutFix(bl, view)
                                probeDom(bl, view)

                                if (url?.startsWith("https://open.spotify.com") == true &&
                                    tokenFetchStarted.compareAndSet(false, true)
                                ) {
                                    Timber.d("SpotifyLogin: extracting token from onPageFinished")
                                    extractAndFetchToken(
                                        view = view,
                                        context = context,
                                        scope = scope,
                                        navController = navController,
                                        setProcessing = { isProcessing = it },
                                        setStatus = { statusMessage = it },
                                        setError = { hasError = it },
                                        tokenFetchStarted = tokenFetchStarted,
                                    )
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val requestUrl = request?.url?.toString() ?: return false
                                Timber.d("SpotifyLogin: navigating to: $requestUrl")
                                BrokenLogin.i(
                                    bl, "navigate",
                                    BrokenLogin.kv(
                                        "url" to BrokenLogin.shortUrl(requestUrl),
                                        "mainFrame" to request.isForMainFrame,
                                        "redirect" to request.isRedirect,
                                    ),
                                )

                                if (requestUrl.startsWith("https://open.spotify.com")) {
                                    val spDc = extractSpDcCookie()
                                    BrokenLogin.i(
                                        bl, "redirect.toOpenSpotify",
                                        BrokenLogin.kv("spDc" to BrokenLogin.redact(spDc)),
                                    )
                                    if (spDc != null && tokenFetchStarted.compareAndSet(false, true)) {
                                        Timber.d("SpotifyLogin: sp_dc available at redirect, processing immediately")
                                        extractAndFetchToken(
                                            view = view,
                                            context = context,
                                            scope = scope,
                                            navController = navController,
                                            setProcessing = { isProcessing = it },
                                            setStatus = { statusMessage = it },
                                            setError = { hasError = it },
                                            tokenFetchStarted = tokenFetchStarted,
                                        )
                                        return true
                                    }
                                    // sp_dc not ready yet — let the page load so
                                    // onPageFinished can pick up the cookie later
                                    Timber.d("SpotifyLogin: sp_dc not ready at redirect, deferring to onPageFinished")
                                    return false
                                }

                                return false
                            }
                        }

                        loadUrl(SpotifyAuth.LOGIN_URL)
                    }
                },
            )

            if (isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!hasError) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        Text(
                            text = statusMessage.ifEmpty {
                                stringResource(R.string.spotify_logging_in)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (hasError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (hasError) {
                            Spacer(modifier = Modifier.height(16.dp))
                            TextButton(
                                onClick = {
                                    hasError = false
                                    isProcessing = false
                                    statusMessage = ""
                                    tokenFetchStarted.set(false)
                                    retryCount++
                                },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Asks the loaded document what it actually contains.
 *
 * `onPageFinished` with `progress=100` and a non-zero `contentHeight` only proves a document
 * exists — not that the login form is in it. Spotify's login is a JavaScript app on a dark
 * background, so a shell that mounted without its form is indistinguishable from a correctly
 * rendered page from the outside, and looks like a black screen to the user. This reports whether
 * the inputs are actually there, which is the difference between "Spotify refused us" and
 * "the page is fine but something failed to mount".
 *
 * Reads structure only — element counts, sizes, readyState. No field values are touched.
 */
private fun probeDom(bl: String, view: WebView?) {
    if (!BrokenLogin.ENABLED || view == null) return

    /*
     * The page is measured in CSS pixels by Chromium; this is the Android View that has to put
     * those pixels on screen. Chromium can report a perfectly laid-out, high-contrast document
     * while the View itself is zero-sized, transparent, detached or not shown — in which case
     * nothing about the page matters.
     */
    BrokenLogin.i(
        bl, "viewState",
        BrokenLogin.kv(
            "width" to view.width,
            "height" to view.height,
            "alpha" to view.alpha,
            "visibility" to view.visibility,
            "isShown" to view.isShown,
            "attached" to view.isAttachedToWindow,
            "hwAccelerated" to view.isHardwareAccelerated,
            "layerType" to view.layerType,
            "scaleX" to view.scaleX,
            "scaleY" to view.scaleY,
            "translationY" to view.translationY,
            "parent" to (view.parent?.javaClass?.simpleName ?: "NONE"),
        ),
    )
    val js = """
        (function () {
          try {
            var b = document.body;
            var vis = 0, painted = 0, all = document.querySelectorAll('*');
            for (var i = 0; i < all.length; i++) {
              var el = all[i];
              var r = el.getBoundingClientRect();
              if (r.width > 0 && r.height > 0) {
                vis++;
                // Size alone is not visibility: an element can be laid out and still be
                // invisible. This is what separates "rendered" from "merely present".
                var cs = getComputedStyle(el);
                if (cs.visibility !== 'hidden' && cs.display !== 'none' && parseFloat(cs.opacity) > 0.01) painted++;
              }
            }
            // What is actually on top at the centre of the viewport. If this is the body or an
            // overlay rather than the form, something is covering the page.
            var mid = document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2);
            var midDesc = mid ? (mid.tagName + (mid.id ? '#' + mid.id : '') +
                (mid.className && typeof mid.className === 'string'
                  ? '.' + mid.className.trim().split(/\s+/).slice(0, 2).join('.') : '')) : 'null';
            var inp = document.querySelector('input');
            var inpDesc = '-';
            if (inp) {
              var ir = inp.getBoundingClientRect();
              var ics = getComputedStyle(inp);
              inpDesc = Math.round(ir.x) + ',' + Math.round(ir.y) + ' ' +
                Math.round(ir.width) + 'x' + Math.round(ir.height) +
                ' vis=' + ics.visibility + ' op=' + ics.opacity + ' disp=' + ics.display;
            }
            /*
             * Contrast, not presence. Everything above says the page is painted, yet the user
             * sees black — which is exactly what an already-dark page looks like after a second,
             * platform-level dark inversion is applied on top of it: dark text on dark background.
             * If these colours collapse onto each other, that is the bug; if they are contrasting,
             * the page really is fine and the failure is in compositing to the screen.
             */
            function col(el, prop) {
              return el ? getComputedStyle(el)[prop] : '-';
            }
            /*
             * Walk from the login field up to the root. The page background composites but the
             * content does not, and body.scrollHeight is 0 — the signature of an ancestor that is
             * zero-height (or otherwise collapsed) and clipping everything inside it. Clipping is
             * invisible to every check above: it changes neither getBoundingClientRect nor
             * visibility/opacity, so an element can report "painted" while being cropped away.
             * The first ancestor with height 0 and a non-visible overflow is the culprit.
             */
            var chain = [];
            var node = inp;
            for (var depth = 0; node && depth < 10; depth++) {
              var ncs = getComputedStyle(node);
              chain.push({
                t: node.tagName +
                   (node.className && typeof node.className === 'string'
                     ? '.' + node.className.trim().split(/\s+/).slice(0, 1).join('') : ''),
                h: ncs.height,
                ch: node.clientHeight,
                oh: node.offsetHeight,
                ov: ncs.overflow,
                pos: ncs.position,
                disp: ncs.display,
                tr: ncs.transform === 'none' ? '-' : 'set',
                cont: ncs.contain
              });
              node = node.parentElement;
            }
            var btn = document.querySelector('button');
            var colours = {
              htmlBg: col(document.documentElement, 'backgroundColor'),
              bodyColor: col(b, 'color'),
              inputColor: col(inp, 'color'),
              inputBg: col(inp, 'backgroundColor'),
              buttonColor: col(btn, 'color'),
              buttonBg: col(btn, 'backgroundColor'),
              colorScheme: col(document.documentElement, 'colorScheme'),
              prefersDark: window.matchMedia &&
                window.matchMedia('(prefers-color-scheme: dark)').matches
            };
            return JSON.stringify({
              readyState: document.readyState,
              bodyChildren: b ? b.children.length : -1,
              htmlLen: b ? b.innerHTML.length : -1,
              textLen: b ? (b.innerText || '').trim().length : -1,
              elements: all.length,
              visibleElements: vis,
              paintedElements: painted,
              centreElement: midDesc,
              firstInput: inpDesc,
              inputs: document.querySelectorAll('input').length,
              passwordInputs: document.querySelectorAll('input[type=password]').length,
              buttons: document.querySelectorAll('button').length,
              iframes: document.querySelectorAll('iframe').length,
              forms: document.querySelectorAll('form').length,
              innerW: window.innerWidth,
              innerH: window.innerHeight,
              bodyH: b ? b.scrollHeight : -1,
              bg: b ? getComputedStyle(b).backgroundColor : '-',
              htmlH: document.documentElement.clientHeight,
              htmlScrollH: document.documentElement.scrollHeight,
              bodyOverflow: b ? getComputedStyle(b).overflow : '-',
              htmlOverflow: getComputedStyle(document.documentElement).overflow,
              colours: colours,
              ancestors: chain
            });
          } catch (e) {
            return JSON.stringify({ probeError: String(e) });
          }
        })();
    """.trimIndent()
    BrokenLogin.trap(bl, "dom.probe") {
        view.evaluateJavascript(js) { result ->
            BrokenLogin.i(bl, "dom", result?.trim('"')?.replace("\\\"", "\"") ?: "null")
        }
    }

    /*
     * The WebView is created and told to load inside AndroidView's factory — before Compose has
     * measured it. If the page lays out while the view still has zero height, JavaScript that
     * computes sizes once (Spotify's <main> is position:absolute and ends up 48px tall around
     * 729px of content) keeps those numbers forever, even though the viewport is 375x703 by the
     * time anything is measured.
     *
     * Telling the page the viewport changed is the cheapest way to find out. If <main> grows on a
     * resize, the bug is the load racing the layout and the fix is to load after measurement — not
     * to patch anyone's CSS. If it stays 48px, the collapse is intrinsic to the page and an
     * injected override is the only route left.
     */
}

/**
 * Undoes the collapsed height chain on Spotify's login page.
 *
 * Measured on the live page: `<main>` is `position: absolute`, `overflow: auto` and **48 px tall**
 * around a 729 px `<section>`, with `div`, `body` and `html` above it all computing to `0px`. The
 * form is fully laid out, opaque, white-on-dark and simply cropped away, which is why every
 * presence check reported it as painted while the screen stayed black.
 *
 * Ruled out by measurement before resorting to this: page load failures, HTTP/SSL errors, a dead
 * renderer, reCAPTCHA blocking the form, View size/alpha/attachment, dark inversion, contrast,
 * WebView compositing, the desktop User-Agent, and a load-versus-measure race (a synthetic
 * `resize` leaves `main` at 48 px).
 *
 * This is a workaround on someone else's stylesheet, so it is written to age as well as such a
 * thing can:
 *  - selectors are element names, never the generated class names (`sc-hLBbgQ` is a build hash and
 *    changes on every Spotify deploy)
 *  - it only relaxes heights and overflow; nothing is repositioned, hidden or restyled
 *  - if Spotify fixes the page, `height: auto` and `overflow: visible` are what the page would
 *    compute anyway, so the override becomes a no-op rather than a new bug
 */
private fun applyLoginLayoutFix(bl: String, view: WebView?) {
    if (view == null) return
    val js = """
        (function () {
          try {
            var m = document.querySelector('main');
            var before = m ? getComputedStyle(m).height : '-';
            var box = '-';
            if (m) {
              var s = getComputedStyle(m);
              box = 'pos=' + s.position + ' top=' + s.top + ' bottom=' + s.bottom +
                    ' minH=' + s.minHeight + ' maxH=' + s.maxHeight + ' flex=' + s.flex +
                    ' basis=' + s.flexBasis + ' align=' + s.alignSelf + ' box=' + s.boxSizing;
            }
            var id = 'meld-login-layout-fix';
            if (!document.getElementById(id)) {
              var st = document.createElement('style');
              st.id = id;
              st.textContent =
                'html, body { height: auto !important; min-height: 100% !important; overflow: visible !important; }' +
                'body > div { height: auto !important; min-height: 100% !important; }' +
                'main { position: static !important; height: auto !important;' +
                '       min-height: 100dvh !important; max-height: none !important;' +
                '       overflow: visible !important; }';
              document.head.appendChild(st);
            }
            var after = m ? getComputedStyle(m).height : '-';
            return JSON.stringify({
              mainBefore: before,
              mainAfter: after,
              mainBox: box,
              bodyH: document.body ? document.body.scrollHeight : -1,
              innerH: window.innerHeight,
              applied: !!document.getElementById(id)
            });
          } catch (e) {
            return JSON.stringify({ probeError: String(e) });
          }
        })();
    """.trimIndent()
    BrokenLogin.trap(bl, "layoutFix") {
        view.evaluateJavascript(js) { result ->
            BrokenLogin.i(bl, "layoutFix", result?.trim('"')?.replace("\\\"", "\"") ?: "null")
        }
    }
}

/**
 * Attempts to read the sp_dc cookie from the CookieManager.
 * Returns null if the cookie is not yet available.
 */
private fun extractSpDcCookie(): String? {
    val allCookies = CookieManager.getInstance().getCookie("https://open.spotify.com")
    if (allCookies.isNullOrBlank()) return null

    return allCookies.split(";")
        .mapNotNull { cookie ->
            val parts = cookie.trim().split("=", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }
        .firstOrNull { it.first == "sp_dc" && it.second.isNotBlank() }
        ?.second
}

/**
 * Extracts sp_dc/sp_key cookies, stops the WebView, and fetches the access
 * token in the background using [SpotifyAuth.fetchAccessToken].
 *
 * Uses [tokenFetchStarted] as an atomic guard so only one invocation ever runs,
 * preventing the race between shouldOverrideUrlLoading and onPageFinished.
 */
private fun extractAndFetchToken(
    view: WebView?,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    navController: NavController,
    setProcessing: (Boolean) -> Unit,
    setStatus: (String) -> Unit,
    setError: (Boolean) -> Unit,
    tokenFetchStarted: AtomicBoolean,
) {
    val bl = BrokenLogin.nextId("token")
    val cookieManager = CookieManager.getInstance()
    val allCookies = cookieManager.getCookie("https://open.spotify.com")
    Timber.d("SpotifyLogin: cookies present: ${!allCookies.isNullOrBlank()}")
    BrokenLogin.i(bl, "token.begin", BrokenLogin.describeCookies(allCookies))

    val cookieMap = allCookies?.split(";")
        ?.mapNotNull { cookie ->
            val parts = cookie.trim().split("=", limit = 2)
            if (parts.size == 2 && parts[0].trim().isNotEmpty()) {
                parts[0].trim() to parts[1].trim()
            } else {
                null
            }
        }?.toMap() ?: emptyMap()

    val spDc = cookieMap["sp_dc"]
    if (spDc.isNullOrBlank()) {
        Timber.w("SpotifyLogin: sp_dc not found in cookies (keys: ${cookieMap.keys})")
        // The login page completed but never produced the session cookie. Whether any cookie at
        // all arrived separates "login never actually happened" from "cookie was dropped".
        BrokenLogin.e(
            bl, "token.noCookie",
            BrokenLogin.kv("cookieNames" to cookieMap.keys.joinToString(",").ifEmpty { "NONE" }),
        )
        setProcessing(true)
        setStatus(context.getString(R.string.spotify_login_error_no_cookie))
        setError(true)
        tokenFetchStarted.set(false)
        return
    }

    val spKey = cookieMap["sp_key"] ?: ""
    Timber.d("SpotifyLogin: sp_dc found (${spDc.take(8)}...), starting token fetch")

    setProcessing(true)
    setError(false)
    setStatus(context.getString(R.string.spotify_status_verifying))

    view?.stopLoading()
    view?.loadUrl("about:blank")

    scope.launch(Dispatchers.IO) {
        try {
            context.dataStore.edit { prefs ->
                prefs[SpotifySpDcKey] = spDc
                prefs[SpotifySpKeyKey] = spKey
            }

            withContext(Dispatchers.Main) {
                setStatus(context.getString(R.string.spotify_status_connecting))
            }
            Timber.d("SpotifyLogin: fetching access token via SpotifyAuth (with TOTP)...")

            // SpotifyAuth chains three remote dependencies — the TOTP secret gist, Spotify's
            // server-time endpoint and /api/token — and collapses them into one Result. Logging
            // the failure here is the only place the distinction survives.
            val tokenResult = SpotifyAuth.fetchAccessToken(spDc, spKey)
            tokenResult.onFailure { BrokenLogin.fail(bl, "token.fetch", it) }
            val token = tokenResult.getOrThrow()
            Timber.d("SpotifyLogin: token obtained (anonymous=${token.isAnonymous})")
            BrokenLogin.i(
                bl, "token.ok",
                BrokenLogin.kv(
                    "anonymous" to token.isAnonymous,
                    "accessToken" to BrokenLogin.redact(token.accessToken),
                    "expiresAt" to token.accessTokenExpirationTimestampMs,
                ),
            )
            Spotify.accessToken = token.accessToken

            withContext(Dispatchers.Main) {
                setStatus(context.getString(R.string.spotify_status_loading_profile))
            }
            Timber.d("SpotifyLogin: fetching user profile...")

            Spotify.me().onSuccess { user ->
                Timber.d("SpotifyLogin: logged in as ${user.displayName} (${user.id})")
                context.dataStore.edit { prefs ->
                    prefs[SpotifyUsernameKey] = user.displayName ?: user.id
                    prefs[SpotifyUserIdKey] = user.id
                }
            }.onFailure { e ->
                Timber.w(e, "SpotifyLogin: could not fetch profile (non-fatal)")
            }

            context.dataStore.edit { prefs ->
                prefs[SpotifyAccessTokenKey] = token.accessToken
                prefs[SpotifyTokenExpiryKey] = token.accessTokenExpirationTimestampMs
            }

            withContext(Dispatchers.Main) {
                setStatus(context.getString(R.string.spotify_login_success))
            }
            Timber.d("SpotifyLogin: login complete, navigating back")

            delay(300)

            withContext(Dispatchers.Main) {
                navController.navigateUp()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "SpotifyLogin: login failed — ${e.message}")
            BrokenLogin.fail(bl, "login.failed", e)
            val errorMsg = classifyLoginError(context, e)
            withContext(Dispatchers.Main) {
                setStatus(errorMsg)
                setError(true)
            }
            tokenFetchStarted.set(false)
        }
    }
}

/**
 * Maps authentication exceptions to user-friendly error messages.
 */
private fun classifyLoginError(context: Context, e: Exception): String {
    val msg = e.message.orEmpty()
    return when {
        "anonymous" in msg || "expired" in msg ->
            context.getString(R.string.spotify_login_error_expired)
        "HTTP 403" in msg || "HTTP 401" in msg ->
            context.getString(R.string.spotify_login_error_rejected)
        "gist" in msg.lowercase() || "nuance" in msg.lowercase() ->
            context.getString(R.string.spotify_login_error_network)
        "UnknownHostException" in msg || "timeout" in msg.lowercase() ||
            "SocketTimeoutException" in e.javaClass.simpleName ->
            context.getString(R.string.spotify_login_error_network)
        else ->
            context.getString(R.string.spotify_login_error)
    }
}

/**
 * Desktop Chrome User-Agent. Required by the social login providers:
 * - Facebook's mobile JS has compatibility issues with Android WebView
 * - Spotify and social login providers render more stable desktop pages
 *
 * A mobile UA was tried here and reverted. It did **not** fix the black login screen — Spotify's
 * `<main>` still collapsed to 48 px around 729 px of content under both user agents, so the page
 * is responsive rather than desktop-vs-mobile and the UA was never the cause. What it did do was
 * send Facebook OAuth to `m.facebook.com`, whose bundle then threw
 * `Uncaught TypeError: Cannot read properties of undefined (reading 'getElementsByTagName')`
 * and left the login unusable — exactly the incompatibility this constant exists to avoid.
 *
 * The collapsed layout is handled where it actually occurs, in [applyLoginLayoutFix].
 */
private const val USER_AGENT_DESKTOP =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
