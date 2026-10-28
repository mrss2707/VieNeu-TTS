package com.vieneu.mobile.g2p

import android.content.Context
import java.io.File

/**
 * Từ điển SEAZ nằm trong assets của APK (không nén) nhưng lõi Rust mmap theo ĐƯỜNG DẪN
 * file thật → chép ra `filesDir/sea_g2p/` một lần. Lần sau chỉ so kích thước (chép dở
 * bị bắt vì chép vào file tạm rồi rename).
 */
object SeaG2pAssets {
    const val ASSET_PATH = "sea_g2p/vi.seaz"

    /** @return đường dẫn tuyệt đối tới vi.seaz sẵn sàng truyền cho [SeaG2pBackend]. */
    @Synchronized
    fun install(context: Context, assetPath: String = ASSET_PATH): File {
        val dir = File(context.filesDir, "sea_g2p").apply { mkdirs() }
        val dest = File(dir, File(assetPath).name)
        // openFd chỉ hoạt động khi asset KHÔNG bị nén (app phải đặt noCompress += "seaz").
        // Nếu bị nén thì không biết trước kích thước → chép lại mỗi lần cho chắc, thay vì ném lỗi.
        val expected = runCatching { context.assets.openFd(assetPath).use { it.length } }.getOrNull()
        if (expected != null && dest.isFile && dest.length() == expected) return dest

        val tmp = File(dir, dest.name + ".tmp")
        context.assets.open(assetPath).use { input ->
            tmp.outputStream().use { out -> input.copyTo(out, bufferSize = 1 shl 20) }
        }
        check(tmp.length() > 0 && (expected == null || tmp.length() == expected)) {
            "Chép $assetPath thiếu: ${tmp.length()} != $expected"
        }
        check(tmp.renameTo(dest)) { "Không rename được ${tmp.name}" }
        return dest
    }
}
