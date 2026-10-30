package net.enjoytech.timeshiftradio

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import java.net.URLDecoder
import net.enjoytech.langateway.GatewayResult
import net.enjoytech.langateway.LanGateway
import net.enjoytech.langateway.compose.GatewayLockedContent
import net.enjoytech.langateway.compose.GatewaySetupForm
import net.enjoytech.timeshiftradio.ui.theme.Accent
import net.enjoytech.timeshiftradio.ui.theme.BodyDark
import net.enjoytech.timeshiftradio.ui.theme.BodyOnDark
import net.enjoytech.timeshiftradio.ui.theme.TimeShiftRadioTheme

private const val TAG = "TimeShiftRadio"
private const val APP_TITLE = "TimeShiftRadio"

/** LAN 認証ゲートウェイの台帳（lan_gateway の services.yaml）のサービス名 */
private const val GATEWAY_SERVICE = "time_shift_radio"

private const val PREFS_NAME = "webview_prefs"
private const val KEY_LAST_URL = "last_url"
private const val KEY_WEBVIEW_STATE = "webview_state"

/** LOCKED: 指紋待ち・エラー / SETUP: 端末の登録 / READY: 表示中 */
enum class Stage { LOCKED, SETUP, READY }

/**
 * 自宅サーバのタイムシフトラジオ（~/time_shift_radio の Next.js PWA + /api）を、LAN 認証ゲートウェイ
 * （所在は登録画面で入れる。サービス名 time_shift_radio）を通して WebView で開く。
 * 録音の再生（<audio> の /api/recordings/<id>/stream）は WebView の Cookie で、
 * 保存（/api/recordings/<id>/download）は DownloadManager に Cookie を渡してゲートウェイを通る。
 */
class MainActivity : FragmentActivity() {

    /** フッター操作・状態保存・ネットワーク復旧リロードのためのWebView参照 */
    private var webViewRef: WebView? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Activity 再生成時に WebView の状態（履歴・現在 URL）を戻すための Bundle */
    private var savedWebViewState: Bundle? = null

    val stage = mutableStateOf(Stage.LOCKED)
    val message = mutableStateOf("")
    val busy = mutableStateOf(false)

    /** LAN 認証ゲートウェイ */
    val gateway by lazy { LanGateway(this, service = GATEWAY_SERVICE) }

    /** セッションが開いたあとに WebView で開く URL（WebView がまだ無いときに預かる） */
    private var pendingUrl: String? = null

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

        savedWebViewState = savedInstanceState?.getBundle(KEY_WEBVIEW_STATE)
        registerNetworkCallback()

        setContent {
            when (stage.value) {
                Stage.READY -> TimeShiftRadioTheme {
                    MainScreen(title = APP_TITLE, activity = this)
                }
                Stage.SETUP -> GatewayFrame(APP_TITLE) {
                    GatewaySetupForm(
                        initialUrl = gateway.setupUrl,
                        message = message.value,
                        busy = busy.value,
                        onSubmit = { url, code -> enroll(url, code) }
                    )
                }
                Stage.LOCKED -> GatewayFrame(APP_TITLE) {
                    GatewayLockedContent(
                        message = message.value,
                        busy = busy.value,
                        onRetry = { unlock() },
                        onSetup = { toSetup("") }
                    )
                }
            }
        }

        when {
            // 回転・プロセス復帰で、セッションがまだ使えるなら指紋を求め直さない
            savedWebViewState != null && gateway.isEnrolled && gateway.isSessionValid() -> {
                pendingUrl = gateway.startUrl
                stage.value = Stage.READY
            }
            gateway.isEnrolled -> unlock()
            else -> stage.value = Stage.SETUP
        }
    }

    override fun onResume() {
        super.onResume()
        // セッションが切れていたら、画面を出す前に指紋で取り直す
        if (stage.value == Stage.READY && !gateway.isSessionValid()) {
            lock("セッションの期限が切れました")
            unlock()
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
     * 開き直すのはゲートウェイの URL だけ（それ以外ならゲートウェイの最初の画面）。
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
                    val wv = webViewRef ?: return@runOnUiThread
                    if (stage.value != Stage.READY) return@runOnUiThread
                    wv.clearCache(true)
                    val current = wv.url
                    if (current != null && isGatewayUrl(current)) wv.reload()
                    else homeUrl?.let { wv.loadUrl(it) }
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

    // ── 端末の登録とセッション（lan-gateway-android） ───────

    private fun lock(msg: String) {
        message.value = msg
        stage.value = Stage.LOCKED
        // READY を離れると画面ごと WebView が捨てられる。戻ったときは新しい WebView に開かせる
        webViewRef = null
    }

    private fun toSetup(msg: String) {
        webViewRef = null
        gateway.reset()
        message.value = msg
        stage.value = Stage.SETUP
    }

    /** 登録画面から。登録コードで端末を登録し、端末トークンを指紋で暗号化して保存する */
    private fun enroll(input: String, code: String) {
        busy.value = true
        message.value = "登録しています…"
        gateway.enroll(this, input, code) { handle(it) }
    }

    /** 起動時・期限切れ時。指紋で端末トークンを取り出し、セッションに交換する */
    fun unlock() {
        lock("指紋認証でログインしてください")
        busy.value = true
        gateway.unlock(this) { handle(it) }
    }

    private fun handle(result: GatewayResult) {
        busy.value = false
        when (result) {
            is GatewayResult.Ready -> {
                message.value = ""
                // 前に開いていたゲートウェイの画面に戻る。ゲートウェイ以外（前の版の :9360）は使わない
                val last = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_URL, null)
                openWhenReady(last?.takeIf { isGatewayUrl(it) } ?: result.url)
                stage.value = Stage.READY
            }
            is GatewayResult.Failed ->
                if (result.error.needsEnrollment) toSetup(result.error.message)
                else if (stage.value == Stage.SETUP) message.value = result.error.message
                else lock(result.error.message)
            GatewayResult.Cancelled -> finish()
        }
    }

    /** ゲートウェイの所在の下の URL か（ホストだけでなくポートまで見る） */
    fun isGatewayUrl(url: String): Boolean {
        val base = gateway.baseUrl ?: return false
        return url.startsWith("$base/")
    }

    private fun openWhenReady(url: String) {
        val wv = webViewRef
        if (wv == null) {
            pendingUrl = url
            return
        }
        val bundle = savedWebViewState
        savedWebViewState = null
        val restored = bundle != null && wv.restoreState(bundle) != null
        if (!restored) {
            // 再起動後の白画面（失敗レスポンスのディスクキャッシュ）を防ぐ
            wv.clearCache(true)
            wv.loadUrl(url)
        }
    }

    /** MainScreen が WebView を作ったときに呼ぶ。預かっていた URL を開く */
    fun onWebViewCreated(wv: WebView) {
        webViewRef = wv
        pendingUrl?.let {
            pendingUrl = null
            openWhenReady(it)
        }
    }

    /** WebView にゲートウェイの 401（セッション切れ）が返ったとき */
    fun onSessionExpired() {
        if (stage.value != Stage.READY) return
        lock("セッションの期限が切れました")
        unlock()
    }

    /** 最初の画面（ゲートウェイの下）。セッションが開く前は null */
    val homeUrl: String? get() = gateway.startUrl
}

/** 登録画面・ロック画面。表示中と同じヘッダーとフッター（終了だけ）、ボディはサーバの画面と同じダーク */
@Composable
fun GatewayFrame(title: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            background = BodyDark,
            onBackground = BodyOnDark,
            surface = BodyDark,
            onSurface = BodyOnDark
        )
    ) {
        Scaffold(
            containerColor = BodyDark,
            // ライブラリの見出しは色を指定しない（周りの文字色を使う）ので、暗いボディに合わせて明るい色にする
            contentColor = BodyOnDark,
            contentWindowInsets = WindowInsets(0)
        ) { _ ->
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            ) {
                Header(title)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(BodyDark)
                ) { content() }
                Footer {
                    FooterButton("終了", Modifier.weight(1f)) {
                        (context as? Activity)?.finishAndRemoveTask()
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(title: String) {
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
}

@Composable
private fun Footer(content: @Composable RowScope.() -> Unit) {
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
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
fun MainScreen(
    title: String,
    activity: MainActivity
) {
    val context = LocalContext.current
    val gateway = activity.gateway
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }

    // 端末の戻るキーはWebViewの履歴を辿る（履歴が無ければアプリ終了）
    BackHandler(enabled = canGoBack) {
        webView?.takeIf { it.canGoBack() }?.goBack()
    }

    Scaffold(containerColor = BodyDark, contentColor = BodyOnDark, contentWindowInsets = WindowInsets(0)) {
        Column(
            Modifier
                .fillMaxSize()
                // 横画面でコンテンツがナビゲーションバー／ディスプレイカットアウトの下に潜らないよう
                // 左右のsafe-areaを確保する。上下はヘッダー/フッターで処理するのでHorizontalに限定。
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        ) {
            // ── Header ──────────────────────────────────────────
            Header(title)

            // ── Body (WebView) ──────────────────────────────────
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(BodyDark)
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
                            // ゲートウェイの画面に平文の部品を混ぜさせない・ファイルを読ませない
                            LanGateway.hardenWebSettings(settings)
                            // 読み込み中に白く光らない（サーバの画面と同じダーク）
                            setBackgroundColor(BodyDark.toArgb())

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
                                    // （ゲートウェイの画面だけ残す。前の版の :9360 は残さない）
                                    url?.takeIf { activity.isGatewayUrl(it) }?.let {
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
                                    val failingUrl = request.url?.toString() ?: activity.homeUrl
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

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: WebResourceResponse?
                                ) {
                                    // ゲートウェイの 401 はセッション切れ。画面の中の API・音声でも指紋で取り直す
                                    if (gateway.isSessionExpired(request, errorResponse)) activity.onSessionExpired()
                                }

                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: SslErrorHandler?,
                                    error: SslError?
                                ) {
                                    // ゲートウェイの証明書を確かめられないときは、続行させない（盗み見の恐れがある）
                                    handler?.cancel()
                                    Toast.makeText(ctx, "ゲートウェイの証明書を確かめられません", Toast.LENGTH_LONG).show()
                                }
                            }

                            // WebView 単体はダウンロード機能を持たない。これを付けないと、
                            // ライブラリ画面の「保存」（Content-Disposition: attachment）は
                            // 数十KB読んだところで接続を切って何も残らない。
                            setDownloadListener { downloadUrl, userAgent, disposition, mimeType, _ ->
                                startDownload(activity, downloadUrl, userAgent, disposition, mimeType)
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                                    Log.d(TAG, "${msg?.messageLevel()}: ${msg?.message()}")
                                    return true
                                }
                            }

                            webView = this
                            // 開く URL は、セッションが開いたときに MainActivity が決める
                            // （復元の優先順位: Bundle > 前に開いていたゲートウェイの URL > 最初の画面）
                            activity.onWebViewCreated(this)
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
            Footer {
                FooterButton("リロード", Modifier.weight(1f)) {
                    // リロードはゲートウェイの最初の画面だけ（前の版の :9360 は開かない）
                    val home = activity.homeUrl ?: return@FooterButton
                    webView?.clearCache(true)
                    webView?.loadUrl(home)
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

/**
 * 録音ファイルを Android の DownloadManager に渡して保存する。
 *
 * 保存先は端末の「Music」（API 29 以降は権限不要で公開領域に書ける）。
 * API 28 以下は WRITE_EXTERNAL_STORAGE が必要になるため、権限の要らない
 * アプリ専用領域に落とす（この端末では通らない経路だが、minSdk 26 なので残す）。
 */
private fun startDownload(
    activity: MainActivity,
    url: String,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?
) {
    val context: Context = activity
    // ゲートウェイの外（前の版の :9360 など）からは落とさない。Cookie はゲートウェイのものだけ渡す
    if (!activity.isGatewayUrl(url)) {
        Log.w(TAG, "download refused (not gateway): $url")
        Toast.makeText(context, "ゲートウェイの外のファイルは保存できません", Toast.LENGTH_LONG).show()
        return
    }
    // DownloadManager は WebView の外で通信するので、セッションが切れていると 401 で失敗する。先に取り直す
    if (!activity.gateway.isSessionValid()) {
        Toast.makeText(context, "セッションの期限が切れました。ログインしてからもう一度保存してください", Toast.LENGTH_LONG).show()
        activity.onSessionExpired()
        return
    }
    val fileName = resolveFileName(url, contentDisposition, mimeType)
    val request = DownloadManager.Request(Uri.parse(url)).apply {
        setMimeType(mimeType)
        setTitle(fileName)
        setDescription(APP_TITLE)
        userAgent?.let { addRequestHeader("User-Agent", it) }
        // ゲートウェイのセッション（gw_session）を含む WebView の Cookie を付ける。無いとゲートウェイが 401 で断る。
        // DownloadManager はアプリの networkSecurityConfig（自宅 CA）で TLS を確かめる（Android 7 以降）
        CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, fileName)
        } else {
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MUSIC, fileName)
        }
    }

    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
    val enqueued = manager?.let { runCatching { it.enqueue(request) } }
    if (enqueued?.isSuccess == true) {
        Toast.makeText(context, "「$fileName」を保存しています", Toast.LENGTH_SHORT).show()
    } else {
        val reason = enqueued?.exceptionOrNull()?.message ?: "DownloadManager が使えません"
        Log.w(TAG, "download failed: $reason")
        Toast.makeText(context, "保存に失敗しました: $reason", Toast.LENGTH_LONG).show()
    }
}

/**
 * 保存するファイル名を決める。
 *
 * `URLUtil.guessFileName()` は `filename=` しか解釈せず、日本語ファイル名で使われる
 * RFC 5987 の `filename*=utf-8''...` を読めない（URLが /api/recordings/15/download
 * なので "download.bin" のような名前になってしまう）。まず `filename*=` を自分で
 * ほどき、無ければ従来の推測に任せる。
 */
internal fun resolveFileName(url: String, contentDisposition: String?, mimeType: String?): String {
    val extended = contentDisposition?.let { EXT_FILENAME.find(it) }
    if (extended != null) {
        val charset = extended.groupValues[1].ifBlank { "UTF-8" }
        val encoded = extended.groupValues[2].trim('"')
        val decoded = runCatching {
            // URLDecoder は "+" を空白にしてしまうので、先に守ってから戻す
            URLDecoder.decode(encoded.replace("+", "%2B"), charset)
        }.getOrNull()
        if (!decoded.isNullOrBlank()) return sanitizeFileName(decoded)
    }
    return sanitizeFileName(URLUtil.guessFileName(url, contentDisposition, mimeType))
}

/** パス区切り等をファイル名に使えない文字を落とす（サーバー側でも除去済みだが二重に守る） */
internal fun sanitizeFileName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|\x00-\x1f]"""), "_").trim().ifBlank { "recording.m4a" }

/** Content-Disposition の filename*=charset''pct-encoded を取り出す */
internal val EXT_FILENAME = Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)

private fun errorHtml(code: Int, description: String, failingUrl: String?): String = """
    <html>
    <head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
    <body style="display:flex;justify-content:center;align-items:center;height:100vh;margin:0;font-family:sans-serif;background:#0d1117;color:#c9d1d9;">
      <div style="text-align:center;padding:24px;">
        <h2 style="margin:0 0 12px;">ページを読み込めません</h2>
        <p style="margin:0 0 4px;">エラーコード: $code</p>
        <p style="margin:0 0 4px;">$description</p>
        <p style="margin:0 0 20px;font-size:12px;color:#8b949e;word-break:break-all;">${failingUrl ?: ""}</p>
        <button onclick="location.reload()"
                style="background-color:#628DB6;color:#fff;border:none;border-radius:8px;height:40px;padding:0 24px;font-size:16px;">
          再試行
        </button>
      </div>
    </body>
    </html>
""".trimIndent()
