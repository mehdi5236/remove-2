package com.example.itemexporter

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

object Prefs {
    const val DEFAULT_TRIGGER = "حذف اقلام فروشگاهی"
    private fun sp(c: Context) = c.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    fun trigger(c: Context): String =
        sp(c).getString("trigger", DEFAULT_TRIGGER)?.takeIf { it.isNotBlank() } ?: DEFAULT_TRIGGER

    fun setTrigger(c: Context, v: String) = sp(c).edit().putString("trigger", v.trim()).apply()

    fun lastUri(c: Context): Uri? = sp(c).getString("lastUri", null)?.let { Uri.parse(it) }
    fun setLastUri(c: Context, u: Uri) = sp(c).edit().putString("lastUri", u.toString()).apply()

    fun btnX(c: Context) = sp(c).getInt("btnX", 24)
    fun btnY(c: Context) = sp(c).getInt("btnY", 420)
    fun setBtnPos(c: Context, x: Int, y: Int) = sp(c).edit().putInt("btnX", x).putInt("btnY", y).apply()
}

object Storage {
    const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    /** Saves into Downloads/ItemExporter using MediaStore (no storage permission needed on Android 10+). */
    fun save(context: Context, fileName: String, mime: String, bytes: ByteArray): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ItemExporter")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("cannot create file")
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("cannot open file")
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return uri
    }
}
