package com.wici.androidalbumdemo

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SplatCache {
    const val DEFAULT_MAX_BYTES = 1_073_741_824L
    private const val TAG = "AlbumSplatCache"
    private const val DIR_NAME = "splat-stream-cache"
    const val FORMAT_SPLAT = "splat"
    const val FORMAT_FP16_V1 = "fp16v1"
    const val FP16_ROW_BYTES = 20
    const val FP16_HEADER_BYTES = 24
    private val FP16_MAGIC = byteArrayOf('W'.code.toByte(), 'I'.code.toByte(), 'C'.code.toByte(), 'I'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte(), '6'.code.toByte())
    private val unsafeNameChars = Regex("[^A-Za-z0-9_.-]+")

    data class Entry(
        val key: String,
        val file: File,
        val bytes: Long,
        val records: Int,
        val format: String = FORMAT_SPLAT,
        val rowBytes: Int = 32,
        val headerBytes: Int = 0
    )

    data class PayloadInfo(
        val format: String,
        val rowBytes: Int,
        val headerBytes: Int,
        val records: Int,
        val bytes: Long
    )

    data class StoreResult(
        val stored: Boolean,
        val bytes: Long,
        val totalBytes: Long,
        val evictedCount: Int,
        val evictedBytes: Long,
        val reason: String? = null
    )

    fun keyFor(photoId: String, density: String?): String {
        val safePhotoId = photoId.ifBlank { "unknown" }.replace(unsafeNameChars, "-")
        val safeDensity = (density ?: "backend-default").replace(unsafeNameChars, "-")
        return "$safePhotoId--$safeDensity"
    }

    fun lookup(context: Context, key: String, rowBytes: Int): Entry? {
        val file = fileFor(context, key)
        if (!file.isFile || file.length() <= 0L) return null
        val info = inspect(file, rowBytes)
        if (info == null) {
            Log.w(TAG, "cacheCorrupt key=$key bytes=${file.length()} rowBytes=$rowBytes; deleting")
            file.delete()
            return null
        }
        touch(file)
        return Entry(key, file, info.bytes, info.records, info.format, info.rowBytes, info.headerBytes)
    }

    fun inspect(file: File, rowBytes: Int): PayloadInfo? {
        val bytes = file.length()
        if (bytes <= 0L) return null
        if (bytes >= FP16_HEADER_BYTES) {
            val header = ByteArray(FP16_HEADER_BYTES)
            file.inputStream().use { input ->
                var offset = 0
                while (offset < header.size) {
                    val n = input.read(header, offset, header.size - offset)
                    if (n < 0) break
                    offset += n
                }
                if (offset == header.size && header.copyOfRange(0, FP16_MAGIC.size).contentEquals(FP16_MAGIC)) {
                    val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    buffer.position(FP16_MAGIC.size)
                    val version = buffer.int
                    val compactRowBytes = buffer.int
                    val records = buffer.int
                    buffer.int // flags
                    val expectedBytes = FP16_HEADER_BYTES + records.toLong() * compactRowBytes.toLong()
                    return if (version == 1 && compactRowBytes == FP16_ROW_BYTES && records > 0 && expectedBytes == bytes) {
                        PayloadInfo(FORMAT_FP16_V1, compactRowBytes, FP16_HEADER_BYTES, records, bytes)
                    } else {
                        null
                    }
                }
            }
        }
        if (bytes % rowBytes != 0L) return null
        return PayloadInfo(FORMAT_SPLAT, rowBytes, 0, (bytes / rowBytes).toInt(), bytes)
    }

    fun tempFile(context: Context, key: String): File {
        val dir = cacheDir(context)
        return File(dir, "${safeKey(key)}.${System.nanoTime()}.tmp")
    }

    fun commit(context: Context, key: String, tmp: File, maxBytes: Long): StoreResult {
        val bytes = tmp.length()
        if (bytes <= 0L) {
            tmp.delete()
            return StoreResult(false, bytes, totalBytes(context), 0, 0, "empty")
        }
        if (maxBytes <= 0L) {
            tmp.delete()
            return StoreResult(false, bytes, totalBytes(context), 0, 0, "disabled")
        }

        val finalFile = fileFor(context, key)
        finalFile.parentFile?.mkdirs()
        if (finalFile.exists()) finalFile.delete()
        if (!tmp.renameTo(finalFile)) {
            tmp.copyTo(finalFile, overwrite = true)
            tmp.delete()
        }
        touch(finalFile)

        val eviction = trimToCap(context, maxBytes)
        val stored = finalFile.isFile
        return StoreResult(
            stored = stored,
            bytes = bytes,
            totalBytes = eviction.totalBytes,
            evictedCount = eviction.evictedCount,
            evictedBytes = eviction.evictedBytes,
            reason = if (stored) null else "entry-larger-than-cap"
        )
    }

    fun totalBytes(context: Context): Long =
        cacheDir(context).listFiles { file -> file.isFile && file.extension == "splat" }
            ?.sumOf { it.length() }
            ?: 0L

    private fun trimToCap(context: Context, maxBytes: Long): EvictionResult {
        val files = cacheDir(context).listFiles { file -> file.isFile && file.extension == "splat" }
            ?.toMutableList()
            ?: mutableListOf()
        var total = files.sumOf { it.length() }
        var evictedCount = 0
        var evictedBytes = 0L
        files.sortWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        for (file in files) {
            if (total <= maxBytes) break
            val bytes = file.length()
            if (file.delete()) {
                total -= bytes
                evictedCount++
                evictedBytes += bytes
                Log.i(TAG, "cacheEvict file=${file.name} bytes=$bytes totalAfter=$total cap=$maxBytes")
            }
        }
        return EvictionResult(total, evictedCount, evictedBytes)
    }

    private data class EvictionResult(
        val totalBytes: Long,
        val evictedCount: Int,
        val evictedBytes: Long
    )

    private fun fileFor(context: Context, key: String): File =
        File(cacheDir(context), "${safeKey(key)}.splat")

    private fun safeKey(key: String): String =
        key.replace(unsafeNameChars, "-")

    private fun cacheDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    private fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }
}
