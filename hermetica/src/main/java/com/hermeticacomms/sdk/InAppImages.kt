package com.hermeticacomms.sdk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** In-app images: downloaded once, decoded at about the size they are shown, and kept in a small memory cache. */
internal object InAppImages {
    private const val MAX_BYTES = 8 * 1024 * 1024 // a larger file is not an in-app image; skip it rather than risk running out of memory

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtLeast(1)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(url: String): Bitmap? = cache.get(url)

    /** Blocking: call off the main thread. Null when the download or decode fails. */
    fun load(url: String, width: Int, height: Int): Bitmap? {
        cache.get(url)?.let { return it }
        val bytes = download(url) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.also { cache.put(url, it) }
    }

    /** The largest power of two that keeps the decoded image at least [reqW] x [reqH] (the view crops it to fill). */
    fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var s = 1
        while (reqW > 0 && reqH > 0 && w / (s * 2) >= reqW && h / (s * 2) >= reqH) s *= 2
        return s
    }

    private fun download(url: String): ByteArray? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode !in 200..299 || conn.contentLengthLong > MAX_BYTES) null
            else conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: IOException) {
        null
    } catch (_: ClassCastException) {
        null // not an http(s) URL
    }
}
