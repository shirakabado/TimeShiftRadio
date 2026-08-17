package net.enjoytech.timeshiftradio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 保存ファイル名の決定ロジック。
 *
 * サーバー（Starlette の FileResponse）は日本語ファイル名を RFC 5987 の
 * `filename*=utf-8''...` だけで返す。`URLUtil.guessFileName()` はこれを読めず、
 * URL が `/api/recordings/15/download` なので番組名を失う。そこを自前で
 * ほどけているかを確認する。
 */
class DownloadFileNameTest {

    private val url = "http://192.168.75.20:9360/api/recordings/15/download"

    @Test
    fun `RFC 5987 の日本語ファイル名をほどく`() {
        val disposition = "attachment; filename*=utf-8''2026-08-18_%E3%81%BE%E3%81%84%E3%81" +
            "%AB%E3%81%A1%E3%83%8F%E3%83%B3%E3%82%B0%E3%83%AB%E8%AC%9B%E5%BA%A7%20%E5%8F%8D" +
            "%E5%BE%A9%E9%9A%8E%E6%AE%B5%E3%80%9C%E3%83%91%E3%83%B3%E3%83%9C%E3%82%AF%E3%82" +
            "%B1%E3%83%80%E3%83%B3%E3%80%9C%EF%BC%88%EF%BC%99%EF%BC%92%EF%BC%89.m4a"

        assertEquals(
            "2026-08-18_まいにちハングル講座 反復階段〜パンボクケダン〜（９２）.m4a",
            resolveFileName(url, disposition, "audio/mp4")
        )
    }

    @Test
    fun `プラス記号を空白に化けさせない`() {
        val disposition = "attachment; filename*=UTF-8''a+b%20c.m4a"
        assertEquals("a+b c.m4a", resolveFileName(url, disposition, "audio/mp4"))
    }

    @Test
    fun `パス区切りはファイル名に持ち込まない`() {
        val disposition = "attachment; filename*=utf-8''%2Fetc%2Fpasswd"
        assertEquals("_etc_passwd", resolveFileName(url, disposition, "audio/mp4"))
    }

    @Test
    fun `空白のみの名前は既定名にする`() {
        assertEquals("recording.m4a", sanitizeFileName("   "))
    }
}
