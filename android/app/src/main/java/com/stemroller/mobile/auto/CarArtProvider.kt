package com.stemroller.mobile.auto

import android.content.ContentProvider
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import coil3.SingletonImageLoader
import com.stemroller.mobile.App
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Album art for Android Auto, which only loads `content://` artwork. Serves a local copy,
 * filled from the art the phone UI already cached (downloads warm it at 600 px, so offline
 * downloads have art in the car) or, failing that, fetched from Plex once.
 *
 * Also draws the A–Z folder tiles: without art, Auto puts a warning triangle in their place.
 */
class CarArtProvider : ContentProvider() {
    private val dir by lazy { File(context!!.cacheDir, "car-art").apply { mkdirs() } }

    override fun onCreate() = true

    override fun getType(uri: Uri) = if (uri.path == "/$LETTER") "image/png" else "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.path == "/$LETTER") return letterTile(uri.getQueryParameter(LETTER) ?: "#")
        val thumb = uri.getQueryParameter(THUMB) ?: throw FileNotFoundException(uri.toString())
        val file = File(dir, sha1(thumb) + ".jpg")
        if (!file.exists() || file.length() == 0L) fill(thumb, file)
        if (!file.exists()) throw FileNotFoundException(thumb)
        file.setLastModified(System.currentTimeMillis())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun fill(thumb: String, file: File) {
        val g = App.graph
        val tmp = File(dir, file.name + ".tmp")
        val disk = SingletonImageLoader.get(context!!).diskCache
        for (px in PHONE_SIZES) {
            val url = g.plex.artUrl(thumb, px) ?: continue
            val snap = disk?.openSnapshot(url) ?: continue
            try {
                snap.data.toFile().copyTo(tmp, overwrite = true)
            } finally {
                snap.close()
            }
            if (tmp.renameTo(file)) return trim()
        }
        if (!g.reach.lastKnown) return
        val url = g.plex.artUrl(thumb, PX) ?: return
        runCatching {
            g.imageHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return
                tmp.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
            }
            if (tmp.renameTo(file)) trim()
        }
        tmp.delete()
    }

    /** A big white letter on a flat dark tile, readable at a glance on the 800×480 screen. */
    private fun letterTile(letter: String): ParcelFileDescriptor {
        val l = letter.take(1).uppercase().ifEmpty { "#" }
        val file = File(dir, "letter-${l.first().code}.png")
        if (!file.exists() || file.length() == 0L) {
            val bmp = Bitmap.createBitmap(LETTER_PX, LETTER_PX, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.rgb(0x2C, 0x2C, 0x2E))
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = LETTER_PX * 0.55f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val y = LETTER_PX / 2f - (p.descent() + p.ascent()) / 2f
            c.drawText(l, LETTER_PX / 2f, y, p)
            val tmp = File(dir, file.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            tmp.renameTo(file)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(".jpg") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.stemroller.mobile.carart"
        private const val THUMB = "thumb"
        private const val LETTER = "letter"
        private const val LETTER_PX = 256
        /** Comfortably sharp on the 480-pixel-tall Miata screen, and what downloads cache. */
        const val PX = 600
        private val PHONE_SIZES = intArrayOf(600, 720)
        private const val MAX_FILES = 2000

        fun uri(thumb: String?): Uri? =
            if (thumb.isNullOrEmpty()) null
            else Uri.Builder().scheme("content").authority(AUTHORITY).path("art").appendQueryParameter(THUMB, thumb).build()

        /** The tile for an A–Z folder ("#" for numbers and symbols). */
        fun letterUri(letter: String): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY).path(LETTER).appendQueryParameter(LETTER, letter).build()

        private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
