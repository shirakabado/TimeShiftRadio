# TimeShiftRadio 仕様書

## 1. 概要

**TimeShiftRadio** は、自宅サーバ上で稼働するタイムシフトラジオ（番組表・予約・ライブラリ）の
Web UI を、WebView で表示する Android クライアントです。
サーバ側の画面をそのまま表示することに徹し、アプリ側では **接続断からの復旧** と
**閲覧状態の保持** に責務を絞っています。

- **パッケージ名**: `net.enjoytech.timeshiftradio`
- **対応 OS**: Android 8.0（API 26）以上
- **ターゲット SDK**: 36 / **コンパイル SDK**: 37
- **言語 / UI**: Kotlin + Jetpack Compose（Material3）
- **接続先**: `http://<TimeShiftRadio サーバ>:<PORT>`（`BuildConfig.TARGET_URL`、→ 10.1）

---

## 2. 機能スコープ

### 含む

| 機能 | 内容 |
|------|------|
| WebView 表示 | 指定 URL をアプリ内で表示。外部ブラウザには遷移させない |
| リロード | キャッシュを破棄して初期 URL を再読込 |
| 履歴を戻る | フッタボタンと端末の戻るキーの双方から |
| 終了 | `finishAndRemoveTask()` でタスクごと終了 |
| ローディング表示 | 読み込み中は半透明黒オーバーレイ＋白スピナー |
| エラーページ | メインフレームのエラー時に再試行ボタン付き HTML を差し込み |
| ネットワーク復旧検知 | 復旧時にキャッシュクリア＋自動リロード |
| 状態復元 | プロセス終了・再生成後も直前の URL / 履歴へ復帰 |
| 横画面対応 | システムバー・カットアウトを避けた safe-area 確保 |

### 含まない

- ネイティブの音声再生（再生はサーバ側 Web UI に委譲）
- 番組表・予約・ライブラリのアプリ側実装（すべてサーバ側の責務）
- 認証・ログイン UI
- 接続先 URL の設定画面（ビルド時定数）
- CSS 注入によるページ外観の変更（サーバ側が独自のダークテーマを持つため不要と判断）

---

## 3. 画面仕様

**Header(64dp 固定) + Body(可変) + Footer(64dp 固定)** の3分割レイアウト。
Header / Footer は Accent カラー `#628DB6` に白文字で固定します。

### 3.1 Header

| 要素 | 内容 |
|------|------|
| 背景 | Accent `#628DB6`、`statusBarsPadding()` 適用 |
| タイトル | `TimeShiftRadio`（24sp / Bold / 白 / 中央揃え） |

### 3.2 Body

| 要素 | 内容 |
|------|------|
| WebView | `TARGET_URL` のページを表示。`layoutParams` は `MATCH_PARENT` を明示 |
| ローディング | 読み込み中のみ、黒 40% のオーバーレイと `CircularProgressIndicator`（白）を重ねる |

### 3.3 Footer

| ボタン | 動作 |
|--------|------|
| `リロード` | `clearCache(true)` の後 `loadUrl(TARGET_URL)` |
| `戻る` | `canGoBack()` が true のとき `goBack()`。履歴がなければ何もしない |
| `終了` | `finishAndRemoveTask()` |

3ボタンは `weight(1f)` で均等幅。CircleShape + 白 1dp 枠線、背景は Accent。

### 3.4 Edge-to-Edge / safe-area

- `enableEdgeToEdge()` でステータスバー・ナビゲーションバーを黒背景・白アイコンに統一
- `Scaffold(contentWindowInsets = WindowInsets(0))` で自動パディングを無効化
- ルート `Column` に `WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)` を適用

  横画面ではナビゲーションバーとカットアウトが**左右**に来るため、Header の
  `statusBarsPadding()`（上）と Footer の `navigationBarsPadding()`（下）だけでは
  左右が抜け、コンテンツがそれらの下に潜り込む。これを防ぐための必須処理。

---

## 4. WebView 設定

| 設定 | 値 | 理由 |
|------|-----|------|
| `javaScriptEnabled` | `true` | サーバ側 UI が JS 前提 |
| `domStorageEnabled` | `true` | 同上 |
| `builtInZoomControls` | `true` | 番組表の拡大縮小 |
| `displayZoomControls` | `false` | 画面上のズームボタンは非表示 |
| `useWideViewPort` / `loadWithOverviewMode` | `true` | ビューポート指定を尊重 |
| `mediaPlaybackRequiresUserGesture` | `false` | ラジオ再生をユーザー操作なしで開始可能にする |
| `layoutParams` | `MATCH_PARENT` × `MATCH_PARENT` | **必須**（→ 4.1） |

### 4.1 `layoutParams` を明示する理由

`WebView(ctx)` を生成しただけで `layoutParams` を与えないと、親 ViewGroup が既定の
`WRAP_CONTENT` を割り当てる。WebView はこれを「高さ未確定」として Blink に渡すため、
**ビューポート単位（`vh` / `dvh` / `svh` / `lvh`）がすべて `0px` に解決される**。
`height: Nvh` を持つ要素（地図コンテナ等）が高さ 0 に潰れるが、JS エラーは出ず
`onPageFinished` まで正常に完了するため原因が分かりにくい。
`AndroidView` に `Modifier.fillMaxSize()` を付けても View 自身の LayoutParams は別物なので防げない。

`window.innerHeight` は正しい値を返す点に注意。切り分けは実測で行う:

```javascript
(function () {
  var p = document.createElement('div');
  p.style.cssText = 'position:absolute;left:-9999px;width:1px;height:100vh';
  document.body.appendChild(p);
  var vh = getComputedStyle(p).height;
  p.remove();
  return '100vh=' + vh + ' innerHeight=' + window.innerHeight;
})();
```

実機（SM-A253Q）での実測値: `100vh=639.467px` / `innerHeight=639` → 正常。

---

## 5. 接続断からの復旧

VPN 経由でサーバに接続する構成では、トンネル断の後にアプリケーション層からは同じ
インターフェース（`tun0`）に見えるため、Chromium 内部のソケットプールが死んだ接続を
掴み続け、画面が白いまま復旧しないことがある。以下の4点で対策する。

| # | 対策 | 実装箇所 |
|---|------|----------|
| 1 | 起動時の `clearCache(true)` | WebView `factory`（新規起動時のみ） |
| 2 | ネットワーク復旧時のキャッシュクリア＋リロード | `ConnectivityManager.registerNetworkCallback` |
| 3 | メインフレームのエラー時に再試行 UI を表示 | `WebViewClient.onReceivedError` |
| 4 | 外部ブラウザに逃がさない | `shouldOverrideUrlLoading` が常に `false` |

### 5.1 起動時キャッシュクリア

WebView（Chromium）はエラーレスポンスや空レスポンスをディスクキャッシュに保持するため、
再起動しても白画面が継続することがある。復元時（`restoreState`）は履歴を尊重したいので、
**新規起動時のみ** `clearCache(true)` を実行する。

### 5.2 ネットワーク復旧検知

`NET_CAPABILITY_INTERNET` を持つネットワークを監視し、`onAvailable` でキャッシュを破棄して
リロードする。`registerNetworkCallback` は**登録直後に現在のネットワークで `onAvailable` を
1回発火する**ため、これを復旧イベントと誤認しないよう最初の1回だけ読み飛ばす。

### 5.3 エラーページ

`onReceivedError` で `request.isForMainFrame` が true のときのみ、エラーコード・説明・
失敗 URL と「再試行」ボタンを含む HTML を差し込む。
`loadDataWithBaseURL` の baseURL に**失敗した URL を渡す**ことで、ページ内の
`location.reload()` が本来の URL を再取得するようになる。

---

## 6. 状態復元（先祖返り防止）

バックグラウンドでプロセスが停止されると、初期 URL に戻ってしまう（先祖返り）。
3段構えで防ぐ。

| # | 対策 | カバー範囲 |
|---|------|-----------|
| 1 | `WebView.saveState` / `restoreState` | Activity 再生成（履歴・スクロール位置ごと復元） |
| 2 | `SharedPreferences` への最終 URL 保存 | プロセス完全終了（Bundle ごと失われるケース） |
| 3 | `android:configChanges` | 画面回転等の Configuration 変更（そもそも再生成させない） |

- 保存先: `webview_prefs` / キー `last_url`、`onPageFinished` で毎回更新
- 復元順序: `savedInstanceState` があれば `restoreState`、なければ `last_url`、それも無ければ `TARGET_URL`
- `configChanges`: `orientation|screenSize|screenLayout|keyboardHidden`

---

## 7. パーミッション

| パーミッション | 用途 |
|----------------|------|
| `INTERNET` | サーバへの HTTP 接続 |
| `ACCESS_NETWORK_STATE` | `registerNetworkCallback` によるネットワーク復旧検知 |

`android:usesCleartextTraffic="true"` を設定。接続先が LAN 内の平文 HTTP であり、
Android 9 以降は既定でクリアテキスト通信がブロックされるため。

---

## 8. アイコン

元画像（ラジオ・カレンダー・ヘッドホン・再生/時計の3Dレンダー）からアダプティブアイコンを生成。

- **セーフゾーン**: 108dp キャンバスのうち中央 72dp（66.6%）のみが確実に表示される。
  被写体の外接矩形の **1.5倍**（108/72）の範囲を切り出して 108dp に縮小し、
  被写体がセーフゾーンに収まるようにしている
- **前景**: 全面が不透明なクリーム色（元画像の地色 `#FCEBDB`）。接地影をそのまま保持できる
- **背景**: 同じ `#FCEBDB` の単色。パララックス時にも継ぎ目が出ない
- **`<monochrome>` は未設定**: 前景が全面不透明なため、テーマアイコンに使うと
  塗り潰された図形になってしまう。テーマアイコン適用時は通常アイコンにフォールバックする

生成物は 5 密度（mdpi〜xxxhdpi）の WebP:
`ic_launcher_foreground`（108dp 相当）/ `ic_launcher`（レガシー正方形）/ `ic_launcher_round`（レガシー円形）

---

## 9. MemScope 連携

自作アプリ共通規約に従い、`AndroidManifest.xml` の `<application>` 直下に
GitHub リポジトリを宣言する。MemScope はこの値から README を取得して説明文を表示する。

```xml
<meta-data
    android:name="net.enjoytech.memscope.github"
    android:value="shirakabado/TimeShiftRadio" />
```

README はリポジトリルートに標準名 `README.md` で置き、冒頭を 1〜2 文の説明段落で始めること。

---

## 10. ビルド構成

### 10.1 接続先 URL（`BuildConfig.TARGET_URL`）

接続先は自宅サーバの内部アドレスであり、リポジトリが公開されているため
**ソースには含めず**、ビルド時に `BuildConfig` へ埋め込む。

| 優先順位 | 指定元 | 用途 |
|---------|--------|------|
| 1 | `local.properties` の `timeshift.url` | 通常の開発（Git 管理外） |
| 2 | 環境変数 `TIMESHIFT_URL` | CI / 自動ビルド |
| 3 | `http://localhost:9360` | 未設定時のプレースホルダ |

未設定時は Gradle が警告を出し、実行時は §5.3 のエラーページが表示される。

```kotlin
// app/build.gradle.kts
buildConfigField("String", "TARGET_URL", "\"$timeShiftUrl\"")

buildFeatures {
    buildConfig = true   // BuildConfig 生成に必須
}
```

`buildFeatures.buildConfig = true` を忘れると `BuildConfig` クラスが生成されず、
`MainActivity.kt` の参照が未解決になる点に注意。

### 10.2 その他

- `compileSdk = 37`

  `androidx.core:core-ktx:1.19.0` と `androidx.lifecycle:lifecycle-runtime-compose:2.11.0` が
  API 37 以上でのコンパイルを要求するため。36 のままでは AAR メタデータチェックで失敗する。
  `minSdk` / `targetSdk` は変更不要。

- リソース XML の `<?xml?>` 宣言は必ずファイル先頭に置くこと。
  Image Asset ウィザードが生成するアイコン XML はライセンスコメントの後ろに宣言を置くため
  XML として不正になり、`:app:mergeDebugResources` が失敗する。
