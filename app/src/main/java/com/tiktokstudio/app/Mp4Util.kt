package com.tiktokstudio.app

import java.io.RandomAccessFile

/**
 * بيمسح بيانات الموقع (صندوق ©xyz داخل moov/udta) من ملف MP4 في مكانه: بيغيّر نوع الصندوق لـ free ويصفّر محتواه.
 * مفيش أي تغيير في الفيديو أو الصوت أو الطوابع الزمنية. بيرجع true لو لقى ومسح.
 * مهم: النتيجة بتتأكد بعدها في verifyOutput (قراءة الموقع من الملف الناتج).
 */
fun stripLocation(path: String): Boolean = RandomAccessFile(path, "rw").use { f ->
    fun walk(start: Long, end: Long): Boolean {
        var pos = start; var changed = false
        while (pos + 8 <= end) {
            f.seek(pos)
            val size32 = f.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4); f.readFully(type)
            var hdr = 8L; var size = size32
            if (size32 == 1L) { size = f.readLong(); hdr = 16 } else if (size32 == 0L) size = end - pos
            if (size < hdr || pos + size > end) break
            val t = String(type, Charsets.ISO_8859_1)
            if (t == "moov" || t == "udta") {
                if (walk(pos + hdr, pos + size)) changed = true
            } else if (t == "\u00A9xyz") {
                f.seek(pos + 4); f.write("free".toByteArray(Charsets.ISO_8859_1))
                f.seek(pos + hdr); f.write(ByteArray((size - hdr).toInt().coerceIn(0, 4096)))
                changed = true
            }
            pos += size
        }
        return changed
    }
    walk(0, f.length())
}
