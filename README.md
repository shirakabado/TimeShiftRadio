# TimeShiftRadio

自宅サーバ上のタイムシフトラジオ（番組表・予約・ライブラリ）を WebView で表示する Android クライアント。
VPN / LAN 経由での接続断からの復旧と、プロセス終了後の閲覧状態の復元に対応しています。

## 主要機能

- 自宅サーバ `http://<TimeShiftRadio サーバ>:<PORT>` の WebView 表示（外部ブラウザには遷移しない）
- ネットワーク復旧時のキャッシュクリア＋自動リロード（VPN 再接続後の白画面対策）
- メインフレームの接続エラー時に再試行ボタン付きエラーページを差し込み
- プロセス終了・クラッシュ後も直前の画面へ復帰（先祖返り防止）
- 端末の戻るキーで WebView 履歴を「戻る」
- フッタボタン: リロード / 戻る / 終了
- 横画面でもシステムバー・ディスプレイカットアウトを避けて全内容を表示

## 画面構成

Header(64dp 固定) + Body(WebView) + Footer(64dp 固定) の3分割レイアウト。
Header / Footer は Accent カラー `#628DB6`、Body はサーバ側ページの配色をそのまま表示します。

| 要素 | 内容 |
|------|------|
| Header | タイトル `TimeShiftRadio` |
| Body | サーバのページを表示する WebView。読み込み中は半透明オーバーレイ＋スピナー |
| Footer | `リロード`（キャッシュクリア＋再読込） / `戻る`（履歴を1つ戻る） / `終了` |

## 技術スタック

- 言語: Kotlin 2.2.10
- UI: Jetpack Compose (Material3) / Compose BOM `2026.02.01`
- minSdk 26 / targetSdk 36 / compileSdk 37
- Android Gradle Plugin 9.2.1 / Gradle 9.4.1
- 主要ライブラリ: `androidx.activity.compose`, `androidx.core.ktx`, `androidx.lifecycle.runtime.ktx`

## セットアップ

1. Android Studio で本ディレクトリを開く
2. `local.properties` に SDK パスを設定（初回自動生成）
3. **接続先 URL を設定する。** `local.properties`（Git 管理外）に次の行を追加:

   ```properties
   timeshift.url=http://<TimeShiftRadio サーバ>:<PORT>
   ```

   環境変数 `TIMESHIFT_URL` でも指定可（CI 向け）。優先順位は
   `local.properties` > 環境変数 > プレースホルダ `http://localhost:9360`。
   未設定のままビルドすると Gradle が警告を出し、実行時は接続エラー画面になります。
4. 端末から当該サーバに到達可能であること（同一 LAN もしくは VPN 接続）を確認
5. ビルド＆実行

## ディレクトリ構成

```
app/src/main/
├── java/net/enjoytech/timeshiftradio/
│   ├── MainActivity.kt          # WebView 本体・復旧処理・3分割レイアウト
│   └── ui/theme/                # Accent #628DB6 ベースのテーマ
├── res/
│   ├── drawable/                # アダプティブアイコン背景（単色）
│   ├── mipmap-*/                # ランチャーアイコン（前景・レガシー・丸型）
│   └── values/
└── AndroidManifest.xml
docs/
└── specification.md             # 仕様書
```

## ビルド

```bash
./gradlew assembleDebug
```

## 関連ドキュメント

- [仕様書](docs/specification.md)
