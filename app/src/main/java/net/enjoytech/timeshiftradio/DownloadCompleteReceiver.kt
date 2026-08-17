package net.enjoytech.timeshiftradio

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.util.Log

private const val TAG = "TimeShiftRadio"

/**
 * 保存した録音を MediaStore に登録する。
 *
 * Android 10 以降、DownloadManager が自動でメディア登録するのは Downloads
 * コレクションだけで、`Music/` に直接置いたファイルはスキャンされない。登録が無いと
 * 音楽アプリの一覧に出てこないうえ、scoped storage 配下ではファイル権限が
 * `rw-rw---- <このアプリ>` になるため他アプリからは開けない（保存はできているのに
 * 再生できない、という状態になる）。ダウンロード完了時に自分でスキャンさせる。
 */
class DownloadCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId == -1L) return

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return
        val target = manager.query(DownloadManager.Query().setFilterById(downloadId))
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                )
                if (status != DownloadManager.STATUS_SUCCESSFUL) {
                    Log.w(TAG, "download not successful (status=$status), skip media scan")
                    return@use null
                }
                val localUri = cursor.getString(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                )
                val mimeType = cursor.getString(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)
                )
                val path = localUri?.let { Uri.parse(it).path } ?: return@use null
                path to (mimeType ?: "audio/mp4")
            } ?: return

        val (path, mimeType) = target
        MediaScannerConnection.scanFile(
            context.applicationContext,
            arrayOf(path),
            arrayOf(mimeType)
        ) { scannedPath, uri ->
            // uri が null なら登録に失敗している（音楽アプリの一覧には出てこない）
            Log.i(TAG, "media scan finished: $scannedPath -> $uri")
        }
    }
}
