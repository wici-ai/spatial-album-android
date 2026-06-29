package com.wici.androidalbumdemo

import android.content.Context
import android.util.Log
import java.io.File

object SplatCache {
    const val DEFAULT_MAX_BYTES = 1_073_741_824L
    private const val TAG = "AlbumSplatCache"
    private const val DIR_NAME = "splat-stream-cache"
    private val unsafeNameChars = Regex("[^A-Za-z0-9_.-]+")

    data class Entry(
        val key: String,
        val file: File,
        val bytes: Long,
        val records: Int
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
        val bytes = file.length()
        if (bytes % rowBytes != 0L) {
            Log.w(TAG, "cacheCorrupt key=$key bytes=$bytes rowBytes=$rowBytes; deleting")
            file.delete()
            return null
        }
        touch(file)
        return Entry(key, file, bytes, (bytes / rowBytes).toInt())
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
