package net.enjoytech.timeshiftradio

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import net.enjoytech.timeshiftradio.ui.theme.Accent
import net.enjoytech.timeshiftradio.ui.theme.TimeShiftRadioTheme

private const val TAG = "TimeShiftRadio"
private const val APP_TITLE = "TimeShiftRadio"

/**
 * 接続先URL。リポジトリに含めないよう、`local.properties` の `timeshift.url`
 * （または環境変数 `TIMESHIFT_URL`）からビルド時に埋め込む。設定方法は README を参照。
 */
private val TARGET_URL = BuildConfig.TARGET_URL

private const val PREFS_NAME = "webview_prefs"
private const val KEY_LAST_URL = "last_url"
private const val KEY_WEBVIEW_STATE = "webview_state"

class MainActivity : ComponentActivity() {

    /** フッター操作・状態保存・ネットワーク復旧リロードのためのWebView参照 */
    private var webViewRef: WebView? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * registerNetworkCallback は登録直後に現在のネットワークで onAvailable を1回発火する。
     * これを復旧イベントと誤認してリロードしないよう、最初の1回だけ読み飛ばす。
     */
    private var skipFirstNetworkEvent = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // debugビルドではChrome DevTools（chrome://inspect）からWebViewのDOMを調査できるようにする
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val restoredState = savedInstanceState?.getBundle(KEY_WEBVIEW_STATE)
        registerNetworkCallback()

        setContent {
            TimeShiftRadioTheme {
                MainScreen(
                    title = APP_TITLE,
                    url = TARGET_URL,
                    savedBundle = restoredState,
                    onWebViewCreated = { webViewRef = it }
                )
            }
        }
    }

    /** プロセス終了・Activity再生成に備えてWebViewの履歴とスクロール位置を保存する */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webViewRef?.let { wv ->
            outState.putBundle(KEY_WEBVIEW_STATE, Bundle().also { wv.saveState(it) })
        }
    }

    /**
     * VPN再接続等でネットワークが復旧したときに自動リロードする。
     * VPN経由だとインターフェースが同一（tun0）のままなので、
     * Chromiumのソケットプールが死んだ接続を掴み続ける。キャッシュごと捨てて張り直す。
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (skipFirstNetworkEvent) {
                    skipFirstNetworkEvent = false
                    return
                }
                Log.d(TAG, "network available -> reload")
                runOnUiThread {
                    webViewRef?.clearCache(true)
                    webViewRef?.reload()
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "network lost")
            }
        }
        networkCallback = callback
        cm.registerNetworkCallback(request, callback)
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { cm.unregisterNetworkCallback(it) }
        }
        networkCallback = null
        webViewRef = null
    }
}

@Composable
fun MainScreen(
    title: String,
    url: String,
    savedBundle: Bundle?,
    onWebViewCreated: (WebView) -> Unit
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }

    // 端末の戻るキーはWebViewの履歴を辿る（履歴が無ければアプリ終了）
    BackHandler(enabled = canGoBack) {
        webView?.takeIf { it.canGoBack() }?.goBack()
    }

    Scaffold(contentWindowInsets = WindowInsets(0)) {
        Column(
            Modifier
                .fillMaxSize()
                // 横画面でコンテンツがナビゲーションバー／ディスプレイカットアウトの下に潜らないよう
                // 左右のsafe-areaを確保する。上下はヘッダー/フッターで処理するのでHorizontalに限定。
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        ) {
            // ── Header ──────────────────────────────────────────
            Surface(
                color = Accent,
                contentColor = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        title,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // ── Body (WebView) ──────────────────────────────────
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.White)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            // 必須。省略するとWRAP_CONTENTになり、vh/dvh/svh/lvh が全て0pxに解決される
                            // （地図コンテナや height:Nvh の要素が高さ0に潰れる）
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            // ラジオ再生をユーザー操作なしで開始できるようにする
                            settings.mediaPlaybackRequiresUserGesture = false

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean = false // 外部ブラウザに飛ばさずアプリ内で開く

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: android.graphics.Bitmap?
                                ) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false
                                    canGoBack = view?.canGoBack() == true
                                    // プロセスが完全に死んでBundleごと失われた場合のフォールバック
                                    url?.let {
                                        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                            .edit().putString(KEY_LAST_URL, it).apply()
                                    }
                                }

                                override fun doUpdateVisitedHistory(
                                    view: WebView?,
                                    url: String?,
                                    isReload: Boolean
                                ) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    canGoBack = view?.canGoBack() == true
                                }

                                // 通信断時に白画面で放置せず、リトライ手段を必ず出す
                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    if (request?.isForMainFrame != true) return
                                    isLoading = false
                                    val failingUrl = request.url?.toString() ?: url
                                    val code = error?.errorCode ?: 0
                                    val desc = error?.description?.toString() ?: ""
                                    Log.w(TAG, "onReceivedError $code $desc $failingUrl")
                                    // baseURLに失敗したURLを渡すことで location.reload() が本来のURLを再取得する
                                    view?.loadDataWithBaseURL(
                                        failingUrl,
                                        errorHtml(code, desc, failingUrl),
                                        "text/html",
                                        "UTF-8",
                                        null
                                    )
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                                    Log.d(TAG, "${msg?.messageLevel()}: ${msg?.message()}")
                                    return true
                                }
                            }

                            onWebViewCreated(this)
                            webView = this

                            if (savedBundle != null) {
                                restoreState(savedBundle)
                            } else {
                                // 再起動後の白画面（失敗レスポンスのディスクキャッシュ）を防ぐ
                                clearCache(true)
                                val lastUrl = ctx
                                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .getString(KEY_LAST_URL, null)
                                loadUrl(lastUrl ?: url)
                            }
                        }
                    }
                )

                if (isLoading) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }

            // ── Footer ──────────────────────────────────────────
            Surface(
                color = Accent,
                contentColor = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FooterButton("リロード", Modifier.weight(1f)) {
                        webView?.clearCache(true)
                        webView?.loadUrl(url)
                    }
                    FooterButton("戻る", Modifier.weight(1f)) {
                        webView?.takeIf { it.canGoBack() }?.goBack()
                    }
                    FooterButton("終了", Modifier.weight(1f)) {
                        (context as? Activity)?.finishAndRemoveTask()
                    }
                }
            }
        }
    }
}

@Composable
private fun FooterButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = Accent,
            contentColor = Color.White
        ),
        border = BorderStroke(1.dp, Color.White),
        shape = CircleShape,
        elevation = ButtonDefaults.buttonElevation(0.dp)
    ) {
        Text(label)
    }
}

private fun errorHtml(code: Int, description: String, failingUrl: String?): String = """
    <html>
    <head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
    <body style="display:flex;justify-content:center;align-items:center;height:100vh;margin:0;font-family:sans-serif;background:#f5f7fa;color:#1c1c1e;">
      <div style="text-align:center;padding:24px;">
        <h2 style="margin:0 0 12px;">ページを読み込めません</h2>
        <p style="margin:0 0 4px;">エラーコード: $code</p>
        <p style="margin:0 0 4px;">$description</p>
        <p style="margin:0 0 20px;font-size:12px;color:#666;word-break:break-all;">${failingUrl ?: ""}</p>
        <button onclick="location.reload()"
                style="background-color:#628DB6;color:#fff;border:none;border-radius:8px;height:40px;padding:0 24px;font-size:16px;">
          再試行
        </button>
      </div>
    </body>
    </html>
""".trimIndent()
